package memore.app.data.repository

import android.content.Context
import com.google.gson.Gson
import memore.app.data.model.AliasGroup
import memore.app.utils.EmotionNormalizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class AliasMappingRepository(private val context: Context) {

    private val gson = Gson()
    private val file = File(context.filesDir, "mappings.json")

    suspend fun load(): List<AliasGroup> = withContext(Dispatchers.IO) {
        if (!file.exists()) return@withContext emptyList()
        try {
            val text = file.readText()
            if (text.isBlank()) return@withContext emptyList()
            // 2026-11-21 修（第二次）：不能直接 Gson 反序列化成 AliasGroup——
            // 老数据里有 3 组缺 `kind`、`aliases` 为 null，Gson 会绕开构造函数塞 null 进去，
            // 合并时 NPE → 被 catch 吞成空列表 → 映射表整页空白（用户实测）。
            // 这里逐字段读、给默认值，坏字段直接跳过。
            val arr = org.json.JSONArray(text)
            val groups = (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val name = o.optString("standardName", "").trim()
                if (name.isEmpty()) return@mapNotNull null
                val aliases = o.optJSONArray("aliases")?.let { a ->
                    (0 until a.length()).map { a.optString(it, "") }.filter { it.isNotBlank() }
                } ?: emptyList()
                AliasGroup(
                    id = o.optString("id", java.util.UUID.randomUUID().toString()),
                    standardName = name,
                    aliases = aliases,
                    createdAt = o.optLong("createdAt", System.currentTimeMillis()),
                    updatedAt = o.optLong("updatedAt", System.currentTimeMillis()),
                    kind = o.optString("kind", "").ifBlank {
                        // 老数据可能整组没有 kind：情绪名按 emotion 认，其余算 concept，
                        // 否则同名但一个缺类型会分成两组，等于没合并。
                        if (DEFAULT_EMOTION_NAMES.contains(name)) "emotion" else "concept"
                    }
                )
            }
            mergeAliasGroups(groups)
        } catch (_: Exception) { emptyList() }
    }

    suspend fun save(groups: List<AliasGroup>) = withContext(Dispatchers.IO) {
        val merged = mergeAliasGroups(groups)
        file.writeText(gson.toJson(merged))
        EmotionNormalizer.rebuildCache(merged.filter { it.kind == "emotion" })
    }

    suspend fun addGroup(group: AliasGroup) {
        val groups = load().toMutableList()
        groups.add(group)
        save(groups)
    }

    suspend fun updateGroup(group: AliasGroup) {
        val groups = load().toMutableList()
        val idx = groups.indexOfFirst { it.id == group.id }
        if (idx >= 0) {
            groups[idx] = group.copy(updatedAt = System.currentTimeMillis())
            save(groups)
        }
    }

    suspend fun deleteGroup(id: String) {
        val groups = load().toMutableList()
        groups.removeAll { it.id == id }
        save(groups)
    }

    /** 按标准名找组（忽略大小写/前后空格）。 */
    suspend fun byStandard(name: String): AliasGroup? {
        val n = name.trim()
        if (n.isEmpty()) return null
        return load().firstOrNull { it.standardName.trim().equals(n, ignoreCase = true) }
    }

    /**
     * 2026-11-21：把 [fromName] 这一组并进 [toName]。
     *
     * `from` 的标准名、别名全部并到 `to` 的别名里（去重），然后删掉 `from` 组；
     * `to` 不存在时新建一组。方向由调用方决定（agent 可以建议，但执行要手机批）。
     * @return 合并后的组；任何一组找不到对应写法时返回 null
     */
    suspend fun mergeGroups(fromName: String, toName: String): AliasGroup? {
        val from = fromName.trim()
        val to = toName.trim()
        if (from.isEmpty() || to.isEmpty()) return null
        if (from.equals(to, ignoreCase = true)) return null
        val groups = load().toMutableList()
        val fromIdx = groups.indexOfFirst { it.standardName.trim().equals(from, ignoreCase = true) }
        if (fromIdx < 0) return null
        val fromGroup = groups[fromIdx]
        val toIdx = groups.indexOfFirst { it.standardName.trim().equals(to, ignoreCase = true) }
        if (toIdx == fromIdx) return null
        val merged: AliasGroup
        if (toIdx >= 0) {
            val toGroup = groups[toIdx]
            val aliases = (listOf(fromGroup.standardName) + fromGroup.aliases + toGroup.aliases)
                .map { it.trim() }.filter { it.isNotEmpty() }.distinct()
            merged = toGroup.copy(aliases = aliases, updatedAt = System.currentTimeMillis())
            groups[toIdx] = merged
            groups.removeAt(fromIdx)
        } else {
            // 目标还没有组：用 from 的全部写法建到 to 名下
            val aliases = (listOf(fromGroup.standardName) + fromGroup.aliases)
                .map { it.trim() }.filter { it.isNotEmpty() }.distinct()
            merged = AliasGroup(standardName = to, aliases = aliases, kind = fromGroup.kind)
            groups[fromIdx] = merged
        }
        save(groups)
        return merged
    }

    /** 一次性写入默认情绪映射组，之后以用户编辑为准，不再覆盖。 */
    suspend fun ensureDefaultEmotionMappings() = withContext(Dispatchers.IO) {
        val prefs = context.getSharedPreferences("mirror_prefs", Context.MODE_PRIVATE)
        if (prefs.getBoolean("emotion_defaults_seeded", false)) return@withContext
        val groups = load().toMutableList()
        if (groups.none { it.kind == "emotion" }) {
            groups.addAll(DEFAULT_EMOTION_GROUPS)
            save(groups)
        }
        prefs.edit().putBoolean("emotion_defaults_seeded", true).apply()
    }

    companion object {
        /** 内置情绪标准名（缺 kind 的旧数据靠它推断类型）。 */
        val DEFAULT_EMOTION_NAMES: Set<String> = setOf(
            "喜悦", "悲伤", "焦虑", "愤怒", "恐惧", "孤独",
            "怀念", "期待", "困惑", "失望", "平静", "满足"
        )

        val DEFAULT_EMOTION_GROUPS: List<AliasGroup> = listOf(
            AliasGroup(standardName = "喜悦", aliases = listOf("开心", "高兴", "快乐", "愉快", "欢快", "欣喜"), kind = "emotion"),
            AliasGroup(standardName = "悲伤", aliases = listOf("难过", "伤心", "沮丧", "低落", "哀伤", "痛苦"), kind = "emotion"),
            AliasGroup(standardName = "焦虑", aliases = listOf("紧张", "不安", "担忧", "忧虑", "心慌"), kind = "emotion"),
            AliasGroup(standardName = "愤怒", aliases = listOf("生气", "恼火", "气愤", "火大", "烦躁"), kind = "emotion"),
            AliasGroup(standardName = "恐惧", aliases = listOf("害怕", "惊恐", "恐慌", "畏惧"), kind = "emotion"),
            AliasGroup(standardName = "孤独", aliases = listOf("寂寞", "孤单", "空虚", "孤立"), kind = "emotion"),
            AliasGroup(standardName = "怀念", aliases = listOf("想念", "思念", "回忆", "怀旧"), kind = "emotion"),
            AliasGroup(standardName = "期待", aliases = listOf("期望", "盼望", "向往", "憧憬"), kind = "emotion"),
            AliasGroup(standardName = "困惑", aliases = listOf("迷茫", "疑惑", "不解", "混乱"), kind = "emotion"),
            AliasGroup(standardName = "失望", aliases = listOf("失落", "灰心", "心灰意冷"), kind = "emotion"),
            AliasGroup(standardName = "平静", aliases = listOf("平和", "安宁", "淡定", "放松"), kind = "emotion"),
            AliasGroup(standardName = "满足", aliases = listOf("满意", "知足", "充实"), kind = "emotion")
        )
    }
}

/**
 * 合并同名映射组（2026-11-21）。
 *
 * 内置默认情绪组与导入的映射会各留一份同名的组，列表里同一个情绪出现两行、别名单不一样。
 * 规则：按「标准名（trim + 忽略大小写）+ 类型」合并；别名取并集、保持先后顺序；
 * 同一批里第一个出现的那组决定 id / createdAt（保持稳定）。
 */
internal fun mergeAliasGroups(groups: List<AliasGroup>): List<AliasGroup> {
    val out = LinkedHashMap<String, AliasGroup>()
    for (g in groups) {
        val name = g.standardName.trim()
        if (name.isEmpty()) continue
        val key = name.lowercase() + "|" + g.kind.trim().lowercase()
        val existing = out[key]
        if (existing == null) {
            out[key] = g.copy(standardName = name, aliases = g.aliases.map { it.trim() }.filter { it.isNotEmpty() }.distinct())
        } else {
            val mergedAliases = (existing.aliases + g.aliases.map { it.trim() }.filter { it.isNotEmpty() }).distinct()
            out[key] = existing.copy(
                aliases = mergedAliases,
                updatedAt = maxOf(existing.updatedAt, g.updatedAt)
            )
        }
    }
    return out.values.toList()
}
