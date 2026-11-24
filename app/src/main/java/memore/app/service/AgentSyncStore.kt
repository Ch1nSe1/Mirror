package memore.app.service

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * Agent 增量同步的"快照"（2026-11-15）。
 *
 * 为什么不是只按时间戳：**打标签、加区域都不改 `records.updated_at`**
 * （TagRepository.addTagToRecord 只写 record_tags 表）。只比时间戳会漏掉这些变化。
 *
 * 做法：给每条记录算一个指纹（正文 + 类型 + 来源 + 删除位 + 标签/协议/关联/区域），
 * 存在 App 私有目录的一个 JSON 里。`/changes` 拿当前指纹和上次的快照逐条比：
 *   · 现在有、快照没有 → created
 *   · 两边都有但指纹不同 → changed
 *   · 快照有、现在没有 → removed（包括软删除）
 *
 * 体积：320 条 × 约 20 字节 ≈ 6KB，可以忽略。
 */
class AgentSyncStore(private val context: Context) {

    private val file: File get() = File(context.filesDir, "agent_sync.json")

    data class State(val ts: Long, val fp: Map<String, Int>)

    fun load(): State? = runCatching {
        if (!file.exists()) return null
        val o = JSONObject(file.readText())
        val fpo = o.optJSONObject("fp") ?: return null
        val map = HashMap<String, Int>(fpo.length())
        fpo.keys().forEach { k -> map[k] = fpo.optInt(k) }
        State(o.optLong("ts", 0L), map)
    }.getOrNull()

    fun save(ts: Long, fp: Map<String, Int>) {
        runCatching {
            val fpo = JSONObject()
            fp.forEach { (k, v) -> fpo.put(k, v) }
            JSONObject().put("version", 1).put("ts", ts).put("fp", fpo)
                .toString().let { file.writeText(it) }
        }
    }

    fun clear() = runCatching { file.delete() }

    companion object {
        /** 指纹本体：只取"会改变这条记录含义"的字段。 */
        fun fingerprint(
            id: String,
            content: String,
            type: String,
            originId: String?,
            isDeleted: Boolean,
            tags: List<String>,
            protocols: List<String>,
            relations: List<String>,
            zones: List<String>
        ): Int {
            var h = id.hashCode()
            h = h * 31 + content.hashCode()
            h = h * 31 + type.hashCode()
            h = h * 31 + (originId?.hashCode() ?: 0)
            h = h * 31 + (if (isDeleted) 1 else 0)
            h = h * 31 + tags.sorted().joinToString(",").hashCode()
            h = h * 31 + protocols.sorted().joinToString(",").hashCode()
            h = h * 31 + relations.sorted().joinToString(",").hashCode()
            h = h * 31 + zones.sorted().joinToString(",").hashCode()
            return h
        }
    }
}
