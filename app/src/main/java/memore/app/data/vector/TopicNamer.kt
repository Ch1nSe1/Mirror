package memore.app.data.vector

import android.content.Context
import memore.app.core.ai.AiProtocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * 用现有 AI 给主题批量命名（BERTopic 的第 6 步：LLM labeling）。
 *
 * 一次请求命名全部簇；失败/未配置就返回 null，调用方回落到 c-TF-IDF 关键词。
 * 输入刻意做小：每簇只给关键词 + 两条短样本，避免把整库正文发出去。
 */
class TopicNamer(private val context: Context) {

    data class Seed(
        val index: Int,
        val keywords: List<String>,
        val samples: List<String>,
        /** 2026-11-22：这一簇里出现过的概念（已按稀有度×次数挑过）。 */
        val concepts: List<String> = emptyList()
    )

    suspend fun name(seeds: List<Seed>, anchors: List<String> = emptyList()): List<String>? = withContext(Dispatchers.IO) {
        if (seeds.isEmpty()) return@withContext null
        val prefs = context.getSharedPreferences("mirror_prefs", Context.MODE_PRIVATE)
        val endpoint = prefs.getString("ai_endpoint", "")?.trim().orEmpty()
        if (endpoint.isBlank()) return@withContext null
        val model = prefs.getString("ai_model", "qwen2.5:7b") ?: "qwen2.5:7b"
        val apiKey = prefs.getString("ai_api_key", "") ?: ""

        val prompt = buildPrompt(seeds, anchors)
        val body = AiProtocol.buildBody(
            endpoint, model, prompt,
            temperature = 0.2, numPredict = 700, numCtx = 4096, formatJson = true
        )

        var conn: HttpURLConnection? = null
        try {
            conn = (URL(AiProtocol.completionUrl(endpoint)).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000
                readTimeout = 60000
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
                doOutput = true
            }
            AiProtocol.applyAuth(conn, apiKey)
            OutputStreamWriter(conn.outputStream, "UTF-8").use { it.write(body) }
            if (conn.responseCode != 200) return@withContext null
            val raw = BufferedReader(InputStreamReader(conn.inputStream, "UTF-8")).use { it.readText() }
            val text = AiProtocol.extractText(raw, endpoint)
            parseNames(text, seeds.size)
        } catch (_: Exception) {
            null
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    internal fun buildPrompt(seeds: List<Seed>, anchors: List<String> = emptyList()): String {
        val seedsBlock = buildString {
            seeds.forEach { s ->
                append("簇 ").append(s.index + 1).append("：")
                if (s.keywords.isNotEmpty()) append("关键词 ").append(s.keywords.joinToString("、")).append("；")
                if (s.concepts.isNotEmpty()) append("概念 ").append(s.concepts.joinToString("、")).append("；")
                s.samples.forEach { append("片段「").append(it.take(60)).append("」；") }
                append('\n')
            }
        }
        val anchorsBlock = if (anchors.isEmpty()) "" else buildString {
            append("已有的跨月线索名：").append(anchors.joinToString("、")).append("。\n")
            append("如果这一簇讲的就是其中某条线索，**直接沿用那个名字**，不要换新词；")
            append("只有确实不属于任何一条时才起新名。\n")
        }
        // 2026-11-21：模板只放人话，数据由代码追加（用户删不掉数据块）。
        val hasConcepts = seeds.any { it.concepts.isNotEmpty() }
        val body = buildString {
            append(memore.app.core.ai.Prompts.getPrompt(memore.app.core.ai.Prompts.KEY_TOPIC_NAME))
            if (hasConcepts) {
                append("\n如果某一簇有明确的共同对象（人 / 作品 / 地点），名字直接用那个对象")
                append("（例如「东京巨蛋」「TestConcept 与告别」），不要泛化成抽象词。")
            }
            if (anchorsBlock.isNotEmpty()) append("\n\n").append(anchorsBlock)
            append("\n\n").append(seedsBlock)
        }
        return body
    }


    private fun parseNames(text: String, expected: Int): List<String>? {
        val cleaned = memore.app.utils.JsonCleaner.clean(text)
        val jsonText = when {
            cleaned.trimStart().startsWith("[") -> "{\"names\":$cleaned}"
            else -> cleaned
        }
        val obj = runCatching { JSONObject(jsonText) }.getOrNull() ?: return null
        val arr: JSONArray = obj.optJSONArray("names") ?: return null
        if (arr.length() < expected) return null
        return (0 until expected).map { i ->
            arr.optString(i, "").trim().take(12)
        }.map { it.ifBlank { "" } }
    }
}