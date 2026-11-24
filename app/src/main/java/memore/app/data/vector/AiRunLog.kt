package memore.app.data.vector

import android.content.Context
import memore.app.utils.LogUtil
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * AI 调用记录（2026-11-05）：把"后台到底产出了什么"落到本地文件，界面能自己看。
 *
 * 存 JSONL（一次调用一行），不动数据库——这是观测数据，不是业务数据，不该为它再开一次迁移。
 * 只保留最近 [MAX_LINES] 条，超了从头截断。
 */
object AiRunLog {

    const val MAX_LINES = 200
    private const val FILE = "ai_runs.jsonl"

    data class Run(
        val at: Long,
        val recordId: String,
        val recordSnippet: String,
        val ok: Boolean,
        val error: String?,
        val durationMs: Long,
        val candidates: List<Candidate>,
        val links: List<Link>,
        val dropped: Int,
        val summaryLen: Int,
        val model: String,
        val atText: String = ""
    )

    data class Candidate(val month: String, val text: String, val score: Float)
    data class Link(val toId: String, val toMonth: String, val why: String, val score: Float)

    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA)

    fun append(context: Context, run: Run) {
        runCatching {
            val f = File(context.filesDir, FILE)
            val obj = JSONObject().apply {
                put("at", run.at)
                put("atText", fmt.format(Date(run.at)))
                put("recordId", run.recordId)
                put("snippet", run.recordSnippet)
                put("ok", run.ok)
                put("error", run.error ?: "")
                put("durationMs", run.durationMs)
                put("dropped", run.dropped)
                put("summaryLen", run.summaryLen)
                put("model", run.model)
                put("candidates", JSONArray().apply {
                    run.candidates.forEach {
                        put(JSONObject().put("m", it.month).put("t", it.text).put("s", it.score.toDouble()))
                    }
                })
                put("links", JSONArray().apply {
                    run.links.forEach {
                        put(JSONObject().put("to", it.toId).put("m", it.toMonth).put("w", it.why).put("s", it.score.toDouble()))
                    }
                })
            }
            f.appendText(obj.toString() + "\n")
            // 超过上限：只留最近的
            val lines = f.readLines()
            if (lines.size > MAX_LINES) {
                f.writeText(lines.takeLast(MAX_LINES).joinToString("\n") + "\n")
            }
        }.onFailure { LogUtil.e("AiRunLog", "append", it) }
    }

    /** 最近的调用（新的在前）。 */
    fun read(context: Context, limit: Int = 50): List<Run> {
        return runCatching {
            val f = File(context.filesDir, FILE)
            if (!f.exists()) return emptyList()
            f.readLines().filter { it.isNotBlank() }.takeLast(limit).mapNotNull { line ->
                val o = runCatching { JSONObject(line) }.getOrNull() ?: return@mapNotNull null
                Run(
                    at = o.optLong("at"),
                    atText = o.optString("atText"),
                    recordId = o.optString("recordId"),
                    recordSnippet = o.optString("snippet"),
                    ok = o.optBoolean("ok"),
                    error = o.optString("error").takeIf { it.isNotBlank() },
                    durationMs = o.optLong("durationMs"),
                    dropped = o.optInt("dropped"),
                    summaryLen = o.optInt("summaryLen"),
                    model = o.optString("model"),
                    candidates = o.optJSONArray("candidates")?.let { arr ->
                        (0 until arr.length()).mapNotNull { i ->
                            val c = arr.optJSONObject(i) ?: return@mapNotNull null
                            Candidate(c.optString("m"), c.optString("t"), c.optDouble("s", 0.0).toFloat())
                        }
                    } ?: emptyList(),
                    links = o.optJSONArray("links")?.let { arr ->
                        (0 until arr.length()).mapNotNull { i ->
                            val c = arr.optJSONObject(i) ?: return@mapNotNull null
                            Link(c.optString("to"), c.optString("m"), c.optString("w"), c.optDouble("s", 0.0).toFloat())
                        }
                    } ?: emptyList()
                )
            }.reversed()
        }.getOrDefault(emptyList())
    }

    /** 给界面用：最近一次成功的调用时间，没有就返回 null。 */
    fun lastSuccessAt(context: Context): Long? =
        read(context, 20).firstOrNull { it.ok }?.at
}