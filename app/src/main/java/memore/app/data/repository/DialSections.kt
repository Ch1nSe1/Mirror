package memore.app.data.repository

import memore.app.data.model.EmotionStats
import memore.app.data.model.Record
import memore.app.data.model.Tick

/**
 * 表盘页「记录」那一段的**分段**（用户 2026-10-19 选的 A1）：
 * 按**这个表盘包着的刻度**分节，末尾再加一节「未归属」。
 *
 * [tick] 为 null 就是「未归属」那一节。
 */
data class DialSection(
    val tick: Tick?,
    val records: List<Record>
) {
    /** 节标题：刻度名，或者「未归属」。 */
    val title: String get() = tick?.name ?: "未归属"
}

/**
 * 把一段时期里的记录分到各刻度下（A1 口径）。
 *
 * 规则（和导出的归属口径一致，别处不要另立一套）：
 * - 一条记录归给**时间上包住它、且起点更晚**的那个刻度（[ticks] 里按 startMs 降序找第一个）；
 *   重叠刻度下它只出现一次，不重复。
 * - 只认**这个表盘包着的刻度**（[ticks]）：属于别的表盘的刻度，在这里算「未归属」——
 *   这一层是"这个框里的分段"，不是全局归属表。
 * - 节的顺序：**新的在前**（和 App 别处一致：时间线、秒针列表都是这样），**未归属永远在最后**；
 *   每节内部同样新的在前。
 * - 空节默认不返回（没有记录的刻度不占一行）；表盘页传 [includeEmpty] = true 时照样返回。
 */
fun groupRecordsByTick(
    records: List<Record>,
    ticks: List<Tick>,
    includeEmpty: Boolean = false
): List<DialSection> {
    if (records.isEmpty() && !includeEmpty) return emptyList()
    val ordered = ticks.sortedByDescending { it.startMs }
    val byTick = LinkedHashMap<String, MutableList<Record>>()
    val mine = ArrayList<Record>()
    ordered.forEach { byTick[it.id] = mutableListOf() }
    // 归属：起点更晚的优先（与 ExportEngine 的 firstOrNull + start_ms DESC 同一口径）
    val attributionOrder = ticks.sortedByDescending { it.startMs }
    records.forEach { r ->
        val owner = attributionOrder.firstOrNull { t ->
            r.createdAt >= t.startMs && (t.endMs == null || r.createdAt < t.endMs)
        }
        if (owner == null) mine.add(r) else byTick.getOrPut(owner.id) { mutableListOf() }.add(r)
    }
    val out = ArrayList<DialSection>(ordered.size + 1)
    ordered.forEach { t ->
        val list = byTick[t.id].orEmpty().sortedByDescending { it.createdAt }
        if (list.isNotEmpty() || includeEmpty) out.add(DialSection(t, list))
    }
    if (mine.isNotEmpty()) out.add(DialSection(null, mine.sortedByDescending { it.createdAt }))
    return out
}

// ===================== 期汇总（用户 2026-10-20 选的 A：统计型） =====================

/** 汇总表里的一行：一个情绪 / 一个主题，在各段里的**出现次数**。 */
data class DialCountRow(
    val name: String,
    /** 与 [DialAggregate.segments] 一一对应（不含「未归属」那一节）。 */
    val counts: List<Int>
) {
    val total: Int get() = counts.sum()

    /** 出现在几段里 —— 判断"贯穿"还是"只在某段"就靠它。 */
    val spread: Int get() = counts.count { it > 0 }
}

/**
 * 一期（表盘）的统计型汇总。
 *
 * **口径刻意和刻度页不同**：刻度页的切片是"衰减加权强度"（还带定格快照，回答"这一段自我什么构成"）；
 * 这里只数**原始出现次数**（回答"这个东西在哪几段出现、哪几段没有"）——
 * 不加衰减就不会随时间漂移、也不需要定格，跨段比较才诚实。
 *
 * [segments] 只含真实刻度（新的在前），「未归属」不进表 —— 它不是一段。
 */
data class DialAggregate(
    val segments: List<Tick>,
    val emotions: List<DialCountRow>,
    val themes: List<DialCountRow>,
    /** 每段的主导情绪，形如「矛盾 36」（与 [segments] 对齐；该段没有情绪时是空串）。 */
    val dominantEmotions: List<String> = emptyList()
) {
    val isEmpty: Boolean get() = emotions.isEmpty() && themes.isEmpty()
}

/**
 * 算汇总（纯函数，可测）。
 *
 * - [sections] 来自 [groupRecordsByTick]（「未归属」那一节被忽略）；
 * - [nodeNamesByRecord] 是 recordId → 概念名（[memore.app.data.local.dao.RecordMemoryNodeDao.getNodeSliceRows] 按记录归好）；
 * - 行按"总次数"降序（同数按出现段数、再按名字，保证顺序稳定），只留前 [topEmotions] / [topThemes] 行。
 */
fun buildDialAggregate(
    sections: List<DialSection>,
    nodeNamesByRecord: Map<String, List<String>>,
    topEmotions: Int = 6,
    topThemes: Int = 8
): DialAggregate {
    val segs = sections.mapNotNull { it.tick }
    if (segs.isEmpty()) return DialAggregate(emptyList(), emptyList(), emptyList())
    val segIndex = segs.withIndex().associate { (i, t) -> t.id to i }
    val emotionCounts = HashMap<String, IntArray>()
    val themeCounts = HashMap<String, IntArray>()

    sections.forEach { section ->
        val idx = section.tick?.let { segIndex[it.id] } ?: return@forEach   // 未归属不进表
        section.records.forEach { r ->
            EmotionStats.emotionEntries(r).forEach { e ->
                val name = e.name.trim()
                if (name.isNotEmpty()) emotionCounts.getOrPut(name) { IntArray(segs.size) }[idx]++
            }
            nodeNamesByRecord[r.id].orEmpty().forEach { n ->
                val name = n.trim()
                if (name.isNotEmpty()) themeCounts.getOrPut(name) { IntArray(segs.size) }[idx]++
            }
        }
    }

    fun rowsOf(map: Map<String, IntArray>, limit: Int): List<DialCountRow> =
        map.map { (name, arr) -> DialCountRow(name, arr.toList()) }
            .sortedWith(compareByDescending<DialCountRow> { it.total }.thenByDescending { it.spread }.thenBy { it.name })
            .take(limit)

    // 每段的主导情绪（用全量计数算，不受 topN 影响）
    // 同数时按名字排（HashMap 的遍历顺序不稳定，不 tie-break 会出现"同样数据两次渲染不一样"）
    val dominant = segs.indices.map { i ->
        emotionCounts.entries
            .filter { it.value[i] > 0 }
            .sortedWith(compareByDescending<Map.Entry<String, IntArray>> { it.value[i] }.thenBy { it.key })
            .firstOrNull()
            ?.let { "${it.key} ${it.value[i]}" }
            .orEmpty()
    }

    return DialAggregate(
        segments = segs,
        emotions = rowsOf(emotionCounts, topEmotions),
        themes = rowsOf(themeCounts, topThemes),
        dominantEmotions = dominant
    )
}
