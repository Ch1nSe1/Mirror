package memore.app.data.vector

import android.content.Context
import memore.app.core.ai.AiProtocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 「回看」（2026-11-05，RAG 的第一个消费者）。
 *
 * 输入：一段刻度内的记录（抽样）+ 区间外与之相关的旧记录（向量检索）。
 * 输出：一段话，**默认不落库**——看完就走，想留才保存。
 *
 * 为什么不复用「AI 分析」：那条路是逐条产出结构化字段；回看要的是"跨很多条的一次叙述"，
 * 输入规模、提示词、输出形态都不同，硬塞会把两边都搞糊。
 */
class ReviewService(
    private val context: Context,
    private val contextBuilder: ContextBuilder
) {

    private val dayFmt = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA)

    suspend fun review(startMs: Long, endMs: Long, titleHint: String = ""): Result<String> =
        withContext(Dispatchers.IO) {
            val prefs = context.getSharedPreferences("mirror_prefs", Context.MODE_PRIVATE)
            val endpoint = prefs.getString("ai_endpoint", "")?.trim().orEmpty()
            if (endpoint.isBlank()) return@withContext Result.failure(Exception("还没配置 AI 端点"))
            val model = prefs.getString("ai_model", "")?.ifBlank { "qwen2.5:7b" } ?: "qwen2.5:7b"
            val apiKey = prefs.getString("ai_api_key", "") ?: ""

            val ctx = runCatching { contextBuilder.reviewContext(startMs, endMs) }
                .getOrElse { return@withContext Result.failure(it) }
            if (ctx.inRange.isEmpty()) return@withContext Result.failure(Exception("这段时间没有记录"))

            val prompt = buildPrompt(ctx, startMs, endMs, titleHint)
            val body = AiProtocol.buildBody(
                endpoint, model, prompt,
                temperature = 0.6, numPredict = 1200, numCtx = 8192, formatJson = false
            )

            var conn: HttpURLConnection? = null
            try {
                conn = (URL(AiProtocol.completionUrl(endpoint)).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 10000
                    readTimeout = 180000
                    requestMethod = "POST"
                    setRequestProperty("Content-Type", "application/json")
                    doOutput = true
                }
                AiProtocol.applyAuth(conn, apiKey)
                OutputStreamWriter(conn.outputStream, "UTF-8").use { it.write(body) }
                val code = conn.responseCode
                if (code == 401) return@withContext Result.failure(Exception("API Key 无效"))
                if (code != 200) return@withContext Result.failure(Exception("AI 返回错误 (HTTP $code)"))
                val raw = BufferedReader(InputStreamReader(conn.inputStream, "UTF-8")).use { it.readText() }
                val text = AiProtocol.extractText(raw, endpoint).trim()
                if (text.isBlank()) return@withContext Result.failure(Exception("AI 没有返回内容"))
                Result.success(text)
            } catch (e: Exception) {
                Result.failure(e)
            } finally {
                runCatching { conn?.disconnect() }
            }
        }

    private fun buildPrompt(ctx: ContextBuilder.ReviewContext, startMs: Long, endMs: Long, titleHint: String): String {
        val samples = buildString {
            ctx.inRange.forEach { c -> append("- ").append(c.month).append("｜").append(c.text).append('\n') }
        }
        val relatedBlock = if (ctx.related.isEmpty()) "" else buildString {
            append("\n【这个时间段之外，但和它相关的旧记录】\n")
            ctx.related.forEach { c -> append("- ").append(c.month).append("｜").append(c.text).append('\n') }
        }
        val titleLine = if (titleHint.isNotBlank()) "（他自己给这段起的名字：$titleHint）" else ""
        // 2026-11-21：模板只放人话，数据由代码追加。
        val data = buildString {
            append("\n\n【本次数据】\n")
            append("时间段：").append(dayFmt.format(Date(startMs))).append(" ~ ").append(dayFmt.format(Date(endMs)))
            if (titleHint.isNotBlank()) append("（他给这段起的名字：").append(titleHint).append("）")
            append("；共 ").append(ctx.totalInRange).append(" 条记录，下面是抽样：\n")
            append(samples)
            if (relatedBlock.isNotEmpty()) append(relatedBlock)
        }
        return memore.app.core.ai.Prompts.getPrompt(memore.app.core.ai.Prompts.KEY_REVIEW) + data
    }
}
