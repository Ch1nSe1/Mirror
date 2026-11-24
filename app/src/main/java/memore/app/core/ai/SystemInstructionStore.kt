package memore.app.core.ai

import android.content.Context
import com.google.gson.Gson

data class SystemInstructionPreset(
    val name: String,
    val content: String,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

/**
 * 外部 AI 规则独立存储，与提示词管理互不共用。
 * 规则列表、当前生效规则、导出开关均放 mirror_prefs；旧 system_instruction 自动迁移。
 */
object SystemInstructionStore {

    private const val PREFS = "mirror_prefs"
    private const val KEY_PRESETS = "system_instruction_presets"
    private const val KEY_ACTIVE = "system_instruction_active"
    private const val KEY_EXPORT_ENABLED = "system_instruction_export_enabled"
    private const val KEY_LEGACY = "system_instruction"
    private const val DEFAULT_NAME = "默认规则"
    private const val IMPORTED_NAME = "导入规则"

    /** 默认导出规则：用于约束读取导出数据的外部 AI；含记录关系（引用/补充/更正）的追溯规则。 */
    private const val DEFAULT_CONTENT = """你是 Mirror 记忆数据的读取/整理引擎。以下是一份从记忆工具导出的数据，请忠实读取，不要猜测或编造数据中没有的内容。

数据要点：
- records 为记录列表，每条记录有唯一 id。content 为原始内容（忠实原文，不可修改）。
- 增修（type=AMENDMENT）通过 correction_of_id 指向其原记录 —— 这表示"又写了一段挂在它下面"，可顺带查阅。
- relations_out（关联）/ relations_in（被关联）是记录之间的**有向**关系：type 取 CITE（本条引用了对方）/ SUPPLEMENT（本条补充了对方）/ CORRECT（本条更正或推翻了对方）。relations_in 是反过来看同一批边（谁指向了本条），所以一条边会同时出现在两端的记录里。
- 把关系当**骨架**而不是附注：遇到 CORRECT 时，被更正的那条**不再代表当前事实**，引用它必须标注被谁更正；遇到 SUPPLEMENT 时，两条要合起来读。这比正文本身更能告诉你"现在的你相信什么"。追溯**不限层数**，按需继续。
- 旧的 linked_ids（若存在）是无类型双向关联，仅作兼容参考。
- 若数据含 ticks：ticks 为「秒针」刻度（可命名的时间区间，代表自我的一段），每条记录/增修按 created_at 落在 start<=t<end 的刻度（归属规则见 tick_meta.rule）。刻度的 weight 表示该段自我的相对重要度：高权重刻度优先阅读，但不要忽略低权重刻度（少影响≠割除）。"""

    /**
     * 旧的"三层追溯上限"那句（已废弃）。
     * 存量设备的默认规则里还留着它，而默认规则只在"本机还没有规则"时播种一次 ——
     * 所以光改上面的常量**不会**影响已经播种过的设备，必须靠下面那次一次性清理。
     */
    private const val LEGACY_DEPTH_SENTENCE =
        "- 关联追溯深度最多 3 层（直接关联为第 1 层，其关联记录的关联为第 2 层……），超过 3 层的关联视为历史存档，不再影响当前判断。"

    /**
     * 一次性清理：把存量规则里那句"最多 3 层"删掉。
     *
     * 刻意**只删这一句**、不整段替换 —— 用户可能在默认规则上改过别的内容，那是他的东西，不能动。
     */
    private fun stripLegacyDepthLimit(context: Context, presets: List<SystemInstructionPreset>): List<SystemInstructionPreset> {
        if (presets.none { it.content.contains(LEGACY_DEPTH_SENTENCE) }) return presets
        val cleaned = presets.map { p ->
            if (p.content.contains(LEGACY_DEPTH_SENTENCE)) {
                p.copy(
                    content = p.content.replace(LEGACY_DEPTH_SENTENCE, "").replace("\n\n\n", "\n\n").trimEnd() + "\n",
                    updatedAt = System.currentTimeMillis()
                )
            } else p
        }
        writePresets(context, cleaned)
        syncLegacy(context, cleaned)
        return cleaned
    }

    private val gson = Gson()

    private fun sp(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(context: Context): List<SystemInstructionPreset> {
        val raw = sp(context).getString(KEY_PRESETS, "") ?: ""
        // 2026-11-23：不再直接 Gson 反序列化成非空 Kotlin 字段。
        // 整体备份导入时 JSON 里可能带 null，Gson 会把它塞进 String → 运行时 .contains() NPE，
        // 表现为 release 打开 FIX/读写设置的页面直接闪退。这里逐字段 optString 兜底。
        val presets = if (raw.isBlank()) emptyList() else try {
            val arr = org.json.JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val name = o.optString("name").trim()
                if (name.isBlank()) return@mapNotNull null
                val now = System.currentTimeMillis()
                SystemInstructionPreset(
                    name = name,
                    content = o.optString("content"),
                    createdAt = o.optLong("createdAt", now).takeIf { it > 0 } ?: now,
                    updatedAt = o.optLong("updatedAt", now).takeIf { it > 0 } ?: now
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
        if (presets.isEmpty()) {
            val legacy = sp(context).getString(KEY_LEGACY, "") ?: ""
            if (legacy.isNotBlank()) {
                val migrated = listOf(SystemInstructionPreset(DEFAULT_NAME, legacy))
                writePresets(context, migrated)
                sp(context).edit().putString(KEY_ACTIVE, DEFAULT_NAME).apply()
                syncLegacy(context, migrated)
                return migrated
            }
            // 无旧值时播种默认规则（含 linked_ids 关联追溯），作为"默认导出提示词"。
            val seeded = listOf(SystemInstructionPreset(DEFAULT_NAME, DEFAULT_CONTENT))
            writePresets(context, seeded)
            sp(context).edit().putString(KEY_ACTIVE, DEFAULT_NAME).apply()
            syncLegacy(context, seeded)
            return seeded
        }
        // 存量设备：清掉已废弃的"三层追溯上限"那句（只删那一句，用户其它改动一律保留）
        return stripLegacyDepthLimit(context, presets.distinctBy { it.name })
    }

    fun savePreset(context: Context, name: String, content: String): Boolean {
        val trimmed = name.trim()
        if (trimmed.isBlank()) return false
        val presets = load(context).toMutableList()
        val now = System.currentTimeMillis()
        val idx = presets.indexOfFirst { it.name == trimmed }
        val existed = idx >= 0
        if (existed) {
            presets[idx] = presets[idx].copy(content = content, updatedAt = now)
        } else {
            presets.add(SystemInstructionPreset(trimmed, content, updatedAt = now))
        }
        writePresets(context, presets)
        sp(context).edit().putString(KEY_ACTIVE, trimmed).apply()
        syncLegacy(context, presets)
        return existed
    }

    fun deletePreset(context: Context, name: String): Boolean {
        val presets = load(context).toMutableList()
        if (!presets.removeAll { it.name == name }) return false
        writePresets(context, presets)
        if (activeName(context) == name) {
            val next = presets.firstOrNull()?.name.orEmpty()
            sp(context).edit().putString(KEY_ACTIVE, next).apply()
        }
        syncLegacy(context, presets)
        return true
    }

    fun renamePreset(context: Context, oldName: String, newName: String): Boolean {
        val trimmed = newName.trim()
        if (trimmed.isBlank() || trimmed == oldName) return false
        val presets = load(context).toMutableList()
        if (presets.any { it.name == trimmed }) return false
        val idx = presets.indexOfFirst { it.name == oldName }
        if (idx < 0) return false
        presets[idx] = presets[idx].copy(name = trimmed, updatedAt = System.currentTimeMillis())
        writePresets(context, presets)
        if (activeName(context) == oldName) {
            sp(context).edit().putString(KEY_ACTIVE, trimmed).apply()
        }
        syncLegacy(context, presets)
        return true
    }

    fun activeName(context: Context): String = sp(context).getString(KEY_ACTIVE, "") ?: ""

    fun setActive(context: Context, name: String) {
        sp(context).edit().putString(KEY_ACTIVE, name).apply()
        syncLegacy(context, load(context))
    }

    fun exportEnabled(context: Context): Boolean = sp(context).getBoolean(KEY_EXPORT_ENABLED, true)

    fun setExportEnabled(context: Context, enabled: Boolean) {
        sp(context).edit().putBoolean(KEY_EXPORT_ENABLED, enabled).apply()
    }

    /** 导出时实际使用的规则内容；暂停或无生效规则时为空。 */
    fun current(context: Context): String {
        if (!exportEnabled(context)) return ""
        val name = activeName(context)
        if (name.isBlank()) return ""
        return load(context).firstOrNull { it.name == name }?.content.orEmpty()
    }

    fun applyImported(context: Context, content: String) {
        if (content.isBlank()) return
        savePreset(context, IMPORTED_NAME, content)
        setExportEnabled(context, true)
    }

    private fun writePresets(context: Context, presets: List<SystemInstructionPreset>) {
        sp(context).edit().putString(KEY_PRESETS, gson.toJson(presets)).apply()
    }

    private fun syncLegacy(context: Context, presets: List<SystemInstructionPreset>) {
        val active = activeName(context)
        val content = presets.firstOrNull { it.name == active }?.content.orEmpty()
        sp(context).edit().putString(KEY_LEGACY, content).apply()
    }
}