package memore.app.data.vector

import android.content.Context
import memore.app.core.ai.AiProtocol
import memore.app.data.model.Record
import memore.app.data.repository.RecordRepository
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * AI 洞察（2026-11-08 重写：单一存储）。
 *
 * 唯一数据源：files/insights/archive.jsonl
 *  - 每条洞察一行，含 id / kind / at / targets / claims / meta
 *  - 单条、批量、近一月全部写这里；不再有第二个文件
 *  - 历史遗留的 insight_single_*.json 在首次读取时导入一次，然后删除
 *
 * 读取：
 *  - archive()            全部（最新在前）
 *  - savedForRecord(id)   某条记录的单条洞察（最新在前）
 *
 * 删除：deleteInsight(id) —— 只删一行，没有第二份可删。
 */
class InsightService(
    private val context: Context,
    private val repository: RecordRepository
) {

    data class Claim(
        val text: String,
        val quote: String,
        val date: String,
        val recordId: String?,
        val kind: String,
        val quoteTo: String? = null,
        val dateTo: String? = null,
        val see: String? = null
    )

    data class Entry(
        val id: String, val at: Long, val kind: String, val meta: String,
        val targets: List<String>, val claims: List<Claim>
    )

    private val cacheDir = File(context.filesDir, "insights").apply { runCatching { mkdirs() } }
    private val dayFmt = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA)
    private val archiveFile = File(cacheDir, "archive.jsonl")
    private val rawFile = File(cacheDir, "last_raw.txt")

    /** 上一次请求的诊断信息（空结果时给 UI 用）。 */
    var lastDiag: String = ""
        private set

    // ---------- 底层读写 ----------

    private fun writeAtomic(target: File, text: String) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(target)) {
            target.writeText(text)
            tmp.delete()
        }
    }

    private fun readLines(): List<JSONObject> {
        if (!archiveFile.exists()) return emptyList()
        return archiveFile.readLines().mapNotNull { line ->
            runCatching { JSONObject(line) }.getOrNull()
        }
    }

    private fun writeLines(lines: List<JSONObject>) {
        writeAtomic(archiveFile, lines.joinToString("\n") + if (lines.isEmpty()) "" else "\n")
    }

    private fun entryOf(o: JSONObject, defaultKind: String): Entry? {
        val cl = o.optJSONArray("claims") ?: return null
        if (cl.length() == 0) return null
        return Entry(
            id = o.optString("id").ifBlank { o.optLong("at").toString() },
            at = o.optLong("at"),
            kind = o.optString("kind"),
            meta = o.optString("meta"),
            targets = runCatching {
                val t = o.optJSONArray("targets") ?: JSONArray()
                (0 until t.length()).map { t.getString(it) }
            }.getOrDefault(emptyList()),
            claims = (0 until cl.length()).map { j -> claimFromJson(cl.getJSONObject(j), defaultKind) }
        )
    }

    private fun claimFromJson(c: JSONObject, defaultKind: String) = Claim(
        text = c.optString("text", ""),
        quote = c.optString("quote", ""),
        date = c.optString("date", ""),
        recordId = c.optString("recordId", "").takeIf { it.isNotBlank() },
        kind = c.optString("kind", defaultKind),
        quoteTo = c.optString("quoteTo", "").takeIf { it.isNotBlank() },
        dateTo = c.optString("dateTo", "").takeIf { it.isNotBlank() },
        see = c.optString("see", "").takeIf { it.isNotBlank() }
    )

    private fun claimToJson(c: Claim) = JSONObject()
        .put("text", c.text).put("quote", c.quote).put("date", c.date)
        .put("recordId", c.recordId ?: "").put("kind", c.kind)
        .put("quoteTo", c.quoteTo ?: "").put("dateTo", c.dateTo ?: "")
        .put("see", c.see ?: "")

    // ---------- 迁移（只跑一次） ----------

    private fun migrateLegacyOnce() {
        val marker = File(cacheDir, ".migrated_v4")
        if (marker.exists()) return
        runCatching {
            val legacy = cacheDir.listFiles { f -> f.name.startsWith("insight_single_") && f.name.endsWith(".json") }
                ?: emptyArray()
            if (legacy.isNotEmpty()) {
                val lines = readLines().toMutableList()
                legacy.forEach { f ->
                    runCatching {
                        val arr = JSONArray(f.readText())
                        for (i in 0 until arr.length()) {
                            val o = arr.getJSONObject(i)
                            val claimArr = o.optJSONArray("claims") ?: continue
                            val entry = JSONObject()
                                .put("id", UUID.randomUUID().toString())
                                .put("at", o.optLong("at"))
                                .put("fp", o.optString("fp"))
                                .put("kind", "single")
                                .put("promptVersion", PROMPT_VERSION)
                                .put("meta", "单条")
                                .put("targets", o.optJSONArray("targets") ?: JSONArray())
                                .put("claims", claimArr)
                            lines.add(entry)
                        }
                    }
                }
                writeLines(lines)
            }
            legacy.forEach { runCatching { it.delete() } }
            marker.writeText("1")
        }
    }

    // ---------- 写入 ----------

    fun save(
        kind: String, targetIds: List<String>, claims: List<Claim>,
        meta: String = "", recordId: String? = null, template: String = ""
    ) {
        if (claims.isEmpty()) return
        migrateLegacyOnce()
        val fp = fingerprint(kind, targetIds, claims)
        val lines = readLines().toMutableList()
        val dup = lines.any { it.optString("fp") == fp }
        if (dup) return
        lines.add(
            JSONObject()
                .put("id", UUID.randomUUID().toString())
                .put("at", System.currentTimeMillis())
                .put("fp", fp)
                .put("kind", kind)
                .put("promptVersion", PROMPT_VERSION)
                .put("meta", meta)
                .put("template", template)
                .put("recordId", recordId ?: "")
                .put("targets", JSONArray(targetIds))
                .put("claims", JSONArray().also { a -> claims.forEach { a.put(claimToJson(it)) } })
        )
        writeLines(lines)
    }

    private fun fingerprint(kind: String, targetIds: List<String>, claims: List<Claim>): String =
        (kind + "|" + targetIds.sorted().joinToString(",") + "|" +
            claims.joinToString(";") { it.text + it.quote + (it.see ?: "") }).hashCode().toString()

    // ---------- 读取 ----------

    /** 全部洞察，最新在前。 */
    fun archive(): List<Entry> {
        migrateLegacyOnce()
        return readLines()
            .mapNotNull { o -> runCatching { entryOf(o, "turn") }.getOrNull() }
            .sortedByDescending { it.at }
    }

    /** 某条记录的单条洞察，最新在前。 */
    fun savedForRecord(recordId: String): List<Entry> =
        archive().filter { it.kind == "single" && (it.targets.contains(recordId) || recordId.isBlank()) }

    fun latestRecent(): Entry? = archive().firstOrNull { it.kind == "recent" }

    // ---------- 删除 ----------

    fun deleteInsight(id: String) {
        migrateLegacyOnce()
        val lines = readLines()
        val keep = lines.filterNot { it.optString("id") == id || it.optLong("at").toString() == id }
        writeLines(keep)
    }

    // ---------- 四种视角（固定提示词，用户选） ----------

    /** 四个视角，名字即模式名，保存时写进 meta。 */
    val MODES = listOf("隐忧", "动机", "在意", "反证")

    private fun keyOf(mode: String): String = when (mode) {
        "动机" -> memore.app.core.ai.Prompts.KEY_INSIGHT_MOTIVE
        "在意" -> memore.app.core.ai.Prompts.KEY_INSIGHT_VALUE
        "反证" -> memore.app.core.ai.Prompts.KEY_INSIGHT_REVERSE
        else -> memore.app.core.ai.Prompts.KEY_INSIGHT_WORRY
    }

    /** 提示词统一从「提示词管理」取，用户改了立刻生效。 */
    private fun modePrompt(mode: String, body: String): String =
        memore.app.core.ai.Prompts.getPrompt(keyOf(mode)) + "\n\n记录：\n" + body
    /** 统一入口：三种触发只差 records；提示词一律来自「提示词管理」。 */
    suspend fun ask(mode: String, records: List<Record>, kind: String): Result<List<Claim>> {
        if (records.isEmpty()) return Result.failure(Exception("没有可用的记录"))
        val body = records.sortedBy { it.createdAt }.joinToString("\n\n") { line(it) }
        val prompt = memore.app.core.ai.Prompts.getPrompt(keyOf(mode)) + "\n\n记录：\n" + body
        return run(prompt, records, mode)
    }

    suspend fun askSingle(record: Record, mode: String = "隐忧") = ask(mode, listOf(record), "single")

    suspend fun askRecent(mode: String = "隐忧"): Result<List<Claim>> {
        val now = System.currentTimeMillis()
        val from = now - 30L * 86_400_000L
        val all = runCatching { repository.getAllActiveRecords() }.getOrDefault(emptyList())
            .filter { !it.isLocked && it.content.length >= 20 && it.createdAt in from..now }
        if (all.size < 3) return Result.failure(Exception("这 30 天只有 ${all.size} 条记录，看不出变化"))
        // 叙事分量（来源系统的「叙事权重」= wCluster）：0 的来源不参与取样
        val weights = runCatching { repository.originNarrativeWeights() }.getOrDefault(emptyMap())
        return ask(mode, pickForChange(all, weights), "recent")
    }

    suspend fun askBatch(records: List<Record>, mode: String = "隐忧"): Result<List<Claim>> {
        if (records.size < 2) return Result.failure(Exception("至少选 2 条"))
        return ask(mode, records.take(20), "batch")
    }
    // ---------- 内部 ----------

    private fun line(r: Record): String =
        dayFmt.format(Date(r.createdAt)) + "｜" + r.content.trim().replace(Regex("\\s+"), " ").take(600)

    /**
     * 「近一月」的选样（2026-11-17 改：分周分层）。
     *
     * 旧版固定取"最早 2 + 最近 2 + 最长 3 + 情绪最强 2"，月中写得最多的那一段
     * 完全可能一条都不进——模型看到的时间轴是断的，于是"看不出变化"。
     *
     * 现在：把 30 天按时间等分成 4 个桶，每桶各取"最长的一条 + 情绪最强的一条"，
     * 再补最早、最晚各一条（保证首尾在场），不足 10 条用余下最长的补齐。
     *
     * 另外（来源系统「叙事权重」）：权重为 0 的来源（如 AI 转录）直接不取样；
     * 其余来源参与"最长/最动情"的排序时乘上自己的权重——权重低的来源
     * 仍然可能被选中，但不会因为篇幅长就挤掉亲手写的那条。
     */
    private fun pickForChange(records: List<Record>, weights: Map<String, Float>): List<Record> {
        fun weightOf(r: Record): Float = r.originId?.let { weights[it] } ?: 1f
        val usable = records.filter { weightOf(it) > 0f }
        if (usable.isEmpty()) return emptyList()
        val ordered = usable.sortedBy { it.createdAt }
        if (ordered.size <= 10) return ordered
        val picked = LinkedHashMap<String, Record>()
        val first = ordered.first().createdAt
        val span = (ordered.last().createdAt - first).coerceAtLeast(1L)
        val buckets = ordered.groupBy { r ->
            ((r.createdAt - first) * 4L / span).toInt().coerceIn(0, 3)
        }
        buckets.toSortedMap().forEach { (_, list) ->
            list.maxByOrNull { it.content.length * weightOf(it) }?.let { picked[it.id] = it }
            list.maxByOrNull { emotion(it) * weightOf(it) }?.let { picked[it.id] = it }
        }
        picked[ordered.first().id] = ordered.first()
        picked[ordered.last().id] = ordered.last()
        if (picked.size < 10) {
            ordered.sortedByDescending { it.content.length * weightOf(it) }.forEach {
                if (picked.size < 10) picked[it.id] = it
            }
        }
        return picked.values.sortedBy { it.createdAt }.take(10)
    }

    private fun emotion(r: Record): Float =
        memore.app.data.model.EmotionStats.emotionEntries(r).maxOfOrNull { it.weight } ?: 0f

    private suspend fun run(
        prompt: String, scope: List<Record>, kind: String, picked: List<Record>? = null
    ): Result<List<Claim>> = withContext(Dispatchers.IO) {
        val prefs = context.getSharedPreferences("mirror_prefs", Context.MODE_PRIVATE)
        val endpoint = prefs.getString("ai_endpoint", "")?.trim().orEmpty()
        if (endpoint.isBlank()) return@withContext Result.failure(Exception("还没配置 AI 端点"))
        val model = prefs.getString("ai_model", "")?.ifBlank { "qwen2.5:7b" } ?: "qwen2.5:7b"
        val apiKey = prefs.getString("ai_api_key", "") ?: ""
        val text = callAi(endpoint, model, apiKey, prompt).getOrElse { return@withContext Result.failure(it) }
        runCatching { rawFile.writeText(text) }
        val parsed = runCatching { parse(text, scope, kind) }
            .getOrElse {
                lastDiag = "返回不是 JSON"
                return@withContext Result.failure(Exception("AI 返回的不是预期格式"))
            }
        if (parsed.isEmpty() && lastDiag.isBlank()) lastDiag = "AI 认为没有可说的"
        Result.success(parsed)
    }

    private fun parse(text: String, all: List<Record>, kind: String): List<Claim> {
        val maxClaims = if (kind == "judge") 1 else 2
        var clean = text.trim()
        while (clean.startsWith("```")) clean = clean.removePrefix("```json").removePrefix("```").trim()
        val start = clean.indexOf('{')
        val end = clean.lastIndexOf('}')
        if (start < 0 || end <= start) { lastDiag = "返回不是 JSON"; return emptyList() }
        val arr = runCatching {
            JSONObject(clean.substring(start, end + 1)).optJSONArray("claims")
        }.getOrNull() ?: runCatching {
            // 容错：JSON 被截断（max_tokens 用尽）时，捡回前面完整的 claim 对象
            lastDiag = "返回被截断，已捡回完整条目"
            salvageClaims(clean)
        }.getOrDefault(JSONArray())
        if (arr.length() == 0) { lastDiag = "AI 认为没有可说的"; return emptyList() }
        val out = ArrayList<Claim>()
        var noQuote = 0
        var noText = 0
        for (i in 0 until arr.length()) {
            val c = arr.getJSONObject(i)
            val quote = c.optString("quote", "").trim()
            val body = c.optString("text", "").trim()
            if (body.isEmpty()) { noText++; continue }
            if (quote.length < 4) { noQuote++; continue }
            val owner = all.firstOrNull { normalized(it.content).contains(normalized(quote)) } ?: run { noQuote++; null } ?: continue
            val quoteTo = c.optString("quoteTo", "").trim().takeIf { it.length >= 4 }
            val ownerTo = quoteTo?.let { q -> all.firstOrNull { normalized(it.content).contains(normalized(q)) } }
            out += Claim(
                text = body, quote = quote, date = dayFmt.format(Date(owner.createdAt)),
                recordId = owner.id, kind = kind,
                quoteTo = quoteTo, dateTo = ownerTo?.let { dayFmt.format(Date(it.createdAt)) },
                see = c.optString("see", "").trim().takeIf { it.isNotBlank() }
            )
            if (out.size >= maxClaims) break
        }
        if (out.isEmpty()) {
            lastDiag = when {
                noQuote > 0 -> "引文对不上原文（$noQuote 条被丢弃）"
                noText > 0 -> "返回缺少结论（$noText 条被丢弃）"
                else -> "没有可用结论"
            }
        }
        return out
    }

    /** 从被截断的 JSON 文本里捡回完整的 claim 对象（每个都必须闭合）。 */
    private fun salvageClaims(raw: String): JSONArray {
        val out = JSONArray()
        val text = raw.substringAfter("claims", raw)
        var depth = 0
        var startIdx = -1
        for (i in text.indices) {
            when (text[i]) {
                '{' -> { if (depth == 0) startIdx = i; depth++ }
                '}' -> {
                    depth--
                    if (depth == 0 && startIdx >= 0) {
                        runCatching { out.put(JSONObject(text.substring(startIdx, i + 1))) }
                        startIdx = -1
                    }
                }
            }
        }
        return out
    }
    private fun normalized(s: String): String = s.filter { ch ->
        !ch.isWhitespace() && ch !in "「」『』“”‘’\"'·…—–-（）()【】[]，,。.；;：:！!？?、"
    }

    private fun callAi(endpoint: String, model: String, apiKey: String, prompt: String): Result<String> {
        val body = AiProtocol.buildBody(endpoint, model, prompt, temperature = 0.2, numPredict = 2000, numCtx = 8192, formatJson = true)
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(AiProtocol.completionUrl(endpoint)).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10000
                readTimeout = 120000
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
                doOutput = true
            }
            AiProtocol.applyAuth(conn, apiKey)
            OutputStreamWriter(conn.outputStream, "UTF-8").use { it.write(body) }
            val code = conn.responseCode
            if (code == 401) return Result.failure(Exception("API Key 无效"))
            if (code != 200) return Result.failure(Exception("AI 返回错误 (HTTP $code)"))
            val raw = BufferedReader(InputStreamReader(conn.inputStream, "UTF-8")).use { it.readText() }
            val txt = AiProtocol.extractText(raw, endpoint).trim()
            if (txt.isBlank()) Result.failure(Exception("AI 没有返回内容")) else Result.success(txt)
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    companion object {
        const val PROMPT_VERSION = 4
    }
}
