package memore.app.data.vector

import android.content.Context
import memore.app.core.ai.AiProtocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * 跨月「线索」对齐（2026-11-17）。
 *
 * 月度主题是每月独立聚类 + 命名的，名字跨月不稳定（同一条线可能叫「关系演变分析」
 * 也可能叫「依恋与自我剖析」）。线索表只做一件事：把这些**已存在的名字**归并成
 * 5~8 条跨月线索，用于画轨迹。
 *
 * 边界（刻意为之）：
 *  · 只发「月份 + 主题名 + 条数」，一句正文都不发；
 *  · 不改月度主题、不参与命名；对齐失败只影响轨迹；
 *  · 结果按「月名 + 条数」的指纹缓存，数据没变就不重复调用；
 *  · 允许模型新增线索名，但成员只能取给定名字（校验后过滤）；
 *  · 失败必须带原因返回（2026-11-18 评审）：空串/网络/解析分别报，不再静默。
 */
object TopicThreads {

    data class Thread(val name: String, val members: List<String>)

    /** 对齐结果：成功带 threads，失败带可展示的原因。两者互斥。 */
    data class AlignResult(val threads: List<Thread>? = null, val error: String? = null)

    private const val FILE = "topic_threads.json"

    private fun file(context: Context): File = File(context.filesDir, FILE)

    /** 当前缓存（fingerprint 与线程表）；没有就返回 null。 */
    fun cached(context: Context): Pair<String, List<Thread>>? = runCatching {
        val f = file(context)
        if (!f.exists()) return null
        val obj = JSONObject(f.readText())
        val fp = obj.optString("fingerprint", "")
        val arr = obj.optJSONArray("threads") ?: return null
        val list = (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val name = o.optString("name", "").trim()
            if (name.isBlank()) return@mapNotNull null
            val ms = o.optJSONArray("members") ?: JSONArray()
            Thread(name, (0 until ms.length()).map { ms.optString(it, "") }.filter { it.isNotBlank() })
        }
        fp to list
    }.getOrNull()

    fun fingerprint(months: List<MonthlyTopicsService.NamedMonth>): String =
        months.joinToString("|") { m ->
            m.month + ":" + m.clusters.joinToString(",") { it.name + "×" + it.count }
        }

    /** 若缓存指纹与当前数据一致，直接返回缓存；否则返回 null。文件读在 IO 线程。 */
    suspend fun cachedIfFresh(
        context: Context,
        months: List<MonthlyTopicsService.NamedMonth>
    ): List<Thread>? = withContext(Dispatchers.IO) {
        val (fp, list) = cached(context) ?: return@withContext null
        if (fp == fingerprint(months)) list else null
    }

    /**
     * 调一次 AI，把月度主题名归并成线索；成功才写缓存。
     * 空内容会重试一次（2026-11-18：实测思考模式偶尔返回空 content）。
     */
    suspend fun align(
        context: Context,
        months: List<MonthlyTopicsService.NamedMonth>
    ): AlignResult = withContext(Dispatchers.IO) {
        if (months.isEmpty()) return@withContext AlignResult(error = "没有月度主题")
        val prefs = context.getSharedPreferences("mirror_prefs", Context.MODE_PRIVATE)
        val endpoint = prefs.getString("ai_endpoint", "")?.trim().orEmpty()
        if (endpoint.isBlank()) return@withContext AlignResult(error = "未配置 AI 端点")
        val model = prefs.getString("ai_model", "qwen2.5:7b") ?: "qwen2.5:7b"
        val apiKey = prefs.getString("ai_api_key", "") ?: ""

        val prompt = buildAlignPrompt(months)
        val body = AiProtocol.buildBody(
            endpoint, model, prompt,
            temperature = 0.2, numPredict = 1600, numCtx = 8192, formatJson = true
        )

        var lastError = "AI 返回无法解析"
        repeat(2) { attempt ->
            val raw = http(endpoint, apiKey, body)
            if (raw == null) {
                lastError = "AI 请求失败"
                return@repeat
            }
            val text = AiProtocol.extractText(raw, endpoint)
            if (text.isBlank()) {
                lastError = "AI 返回为空"
                return@repeat
            }
            val threads = parse(text, months)
            if (threads != null) {
                write(context, months, threads)
                return@withContext AlignResult(threads = threads)
            }
            lastError = "AI 返回无法解析"
        }
        AlignResult(error = lastError)
    }


    /**
     * 对齐提示词（2026-11-19 升级）：除了名字和条数，把**关键词 + 代表句**一起发。
     *
     * 为什么要改：只发名字等于让模型按字面猜同义（「关系结构动力」和「依恋与自我剖析」
     * 看起来毫不相干，实际是一条线）。带上内容后，它判断"这两簇是不是同一条线"靠的是
     * 记录在讲什么，而不是字面上像不像。不发正文，只发每簇一句代表句和几个高频词。
     */
    internal fun buildAlignPrompt(months: List<MonthlyTopicsService.NamedMonth>): String {
        val lines = months.sortedBy { it.month }.map { m ->
            m.month + "：" + m.clusters.sortedByDescending { it.count }.joinToString("、") { c ->
                buildString {
                    append(c.name).append("（").append(c.count).append("条")
                    if (c.keywords.isNotBlank()) append("；关键词 ").append(c.keywords)
                    append("）")
                    if (c.summary.isNotBlank()) append("｜代表句「").append(c.summary).append("」")
                }
            }
        }.joinToString("\n")
        // 2026-11-21：模板只放人话，数据由代码追加。
        return memore.app.core.ai.Prompts.getPrompt(memore.app.core.ai.Prompts.KEY_TOPIC_LINK) +
            "\n\n" + lines
    }


    internal fun parse(text: String, months: List<MonthlyTopicsService.NamedMonth>): List<Thread>? {
        val json = text.trim()
        val start = json.indexOf('{')
        val end = json.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        val obj = runCatching { JSONObject(json.substring(start, end + 1)) }.getOrNull() ?: return null
        val arr = obj.optJSONArray("threads") ?: return null
        val valid = months.flatMap { m -> m.clusters.map { it.name } }.toSet()
        val out = ArrayList<Thread>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val name = o.optString("name", "").trim().take(8)
            if (name.isBlank()) continue
            val ms = o.optJSONArray("members") ?: continue
            val members = (0 until ms.length())
                .map { ms.optString(it, "").trim() }
                .filter { it.isNotBlank() && it in valid }
                .distinct()
            if (members.isNotEmpty()) out += Thread(name, members)
        }
        if (out.size < 2) return null
        return out
    }

    private fun http(endpoint: String, apiKey: String, body: String): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(AiProtocol.completionUrl(endpoint)).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000
                readTimeout = 120000
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
                doOutput = true
            }
            AiProtocol.applyAuth(conn, apiKey)
            OutputStreamWriter(conn.outputStream, "UTF-8").use { it.write(body) }
            if (conn.responseCode != 200) return null
            BufferedReader(InputStreamReader(conn.inputStream, "UTF-8")).use { it.readText() }
        } catch (_: Exception) {
            null
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    /** 原子写：先写 .tmp，再 rename；避免半截 JSON 让下次误判“没整理过”。 */
    private fun write(context: Context, months: List<MonthlyTopicsService.NamedMonth>, threads: List<Thread>) {
        val root = JSONObject()
        root.put("fingerprint", fingerprint(months))
        root.put("threads", JSONArray().apply {
            threads.forEach { t ->
                put(JSONObject().apply {
                    put("name", t.name)
                    put("members", JSONArray(t.members))
                })
            }
        })
        runCatching {
            val tmp = File(context.filesDir, "$FILE.tmp")
            tmp.writeText(root.toString())
            val target = file(context)
            if (target.exists()) target.delete()
            tmp.renameTo(target)
        }
    }
}
