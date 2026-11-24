package memore.app.data.repository

import memore.app.data.local.dao.DialDao
import memore.app.data.local.dao.RecordDao
import memore.app.data.vector.MonthlyTopicsService
import memore.app.data.vector.TopicThreads
import memore.app.data.local.dao.TickDao
import memore.app.data.model.Dial
import memore.app.data.model.DialTick
import memore.app.data.model.Record
import memore.app.data.model.Tick
import kotlinx.coroutines.flow.Flow

/**
 * 表盘仓库：**一段时期的框**（包络若干刻度）。
 *
 * 和刻度是两套东西，边界划死（用户 2026-10-20：「不要更改任何现有刻度系统的东西，你表盘就是新系统」）：
 * - 表盘**不参与记录归属**：记录归哪个刻度、导出的 `tick_id` 与阶段权重，全都不经过这里；
 * - 表盘**没有权重、没有切片、没有定格**：它自己没有内容可锁（「打算盘」那一套 2026-10-20 已删）；
 * - 它对刻度只做两件事：**读**（哪几段、起止）和**关联**（多对多），一个字都不写 `ticks` 表。
 *
 * 一个刻度可以被多个表盘框住（多父）—— 时期天然重叠，「今年这十一个月」和「考研那几个月」能同时看见同一段。
 */
class DialRepository(
    private val dialDao: DialDao,
    private val tickDao: TickDao,
    private val recordDao: RecordDao,
    /** 表盘主题的数据源：月度主题快照（默认读主题页那份缓存）。测试里可注入假数据。 */
    private val themeSnapshot: (suspend () -> MonthlyTopicsService.Snapshot?)? = null,
    /** 表盘主题的数据源：跨月线索（默认读线索缓存）。 */
    private val threadList: (suspend () -> List<TopicThreads.Thread>)? = null
) {

    fun observeDials(): Flow<List<Dial>> = dialDao.observeAll()

    suspend fun allDials(): List<Dial> = dialDao.all()

    suspend fun getDial(id: String): Dial? = dialDao.getById(id)

    /** 新建一个框（终点可空 = 进行中）。名字空着兜个占位，之后可改。 */
    suspend fun createDial(name: String, startMs: Long, endMs: Long? = null): Dial {
        val dial = Dial(
            id = java.util.UUID.randomUUID().toString(),
            name = name.trim().ifBlank { "未命名" },
            startMs = startMs,
            endMs = endMs
        )
        dialDao.insert(dial)
        return dial
    }

    suspend fun renameDial(id: String, name: String) {
        val d = dialDao.getById(id) ?: return
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        dialDao.update(d.copy(name = trimmed, updatedAt = System.currentTimeMillis()))
    }

    suspend fun updateDialBounds(id: String, startMs: Long, endMs: Long?) {
        val d = dialDao.getById(id) ?: return
        dialDao.update(d.copy(startMs = startMs, endMs = endMs, updatedAt = System.currentTimeMillis()))
    }

    suspend fun updateDialSummary(id: String, summary: String) = dialDao.updateSummary(id, summary)

    suspend fun swapDialSummary(id: String) = dialDao.swapSummary(id)

    /** 删表盘 = 只删这个框：关联清掉，**刻度一个不动**（它们只是变回未归属）。 */
    suspend fun deleteDial(id: String) {
        dialDao.deleteLinksOfDial(id)
        dialDao.delete(id)
    }

    /** 把刻度加进这个框（幂等）。只碰关联表，不碰 `ticks`。 */
    suspend fun linkTick(dialId: String, tickId: String) = dialDao.link(DialTick(dialId, tickId))

    suspend fun unlinkTick(dialId: String, tickId: String) = dialDao.unlink(dialId, tickId)

    /** 框里包着的刻度，**按起点新的在前**（和 App 其他地方一致）。已软删的刻度自动不出现。 */
    suspend fun ticksOfDial(dialId: String): List<Tick> {
        val ids = dialDao.tickIdsOf(dialId).toSet()
        if (ids.isEmpty()) return emptyList()
        return tickDao.getActive().filter { it.id in ids }.sortedByDescending { it.startMs }
    }

    /** 每个刻度被哪些表盘框着（多父）。 */
    suspend fun dialIdsByTick(): Map<String, Set<String>> =
        dialDao.allLinks().groupBy { it.tickId }.mapValues { (_, rows) -> rows.map { it.dialId }.toSet() }

    /** 只读：现有活跃刻度（供「加刻度」挑）。**不写** `ticks` 表。 */
    suspend fun allActiveTicks(): List<Tick> = tickDao.getActive()

    /** 导入用：还原一个框（id 冲突则忽略）。 */
    suspend fun importDial(dial: Dial) = dialDao.insertIgnore(dial)

    /** 这个框的时期里有哪些活跃记录（不按刻度筛；A1 口径的分段由 [groupRecordsByTick] 做）。 */
    suspend fun recordsOfDial(dial: Dial): List<Record> {
        val end = dial.endMs ?: System.currentTimeMillis()
        return recordDao.getActiveRecordsInRange(dial.startMs, end)
    }

    /**
     * 表盘「主导主题」的数据源（2026-11-18）：**跨月线索**，不是月度主题。
     *
     * 为什么用线索：表盘是一段时间，问的是"这一期的主线"。
     * 月度主题的名字每月独立生成（同一条线可能叫「关系结构动力」也可能叫「依恋与自我剖析」），
     * 跨月会把一条线拆成好几个名字、把计数摊薄；线索表存在的唯一目的就是把它们
     * 对齐成 5~8 条稳定的线（`files/topic_threads.json`）。
     *
     * 取数：月度主题缓存给出「主题名 → 记录」，线索给出「线索 → 主题名」，
     * 两层接起来就是「线索 → 记录」。没被任何线索覆盖的月度主题，退回用它自己的名字，
     * 保证不丢记录。
     */
    suspend fun themeNamesByRecord(dial: Dial): Map<String, List<String>> {
        val end = dial.endMs ?: System.currentTimeMillis()
        val inRange = recordDao.getActiveRecordsInRange(dial.startMs, end).map { it.id }.toSet()
        if (inRange.isEmpty()) return emptyMap()
        val snap = themeSnapshot?.let { runCatching { it() }.getOrNull() } ?: return emptyMap()

        // 主题名 → 记录集合
        val recordsOfTheme = HashMap<String, MutableSet<String>>()
        snap.months.forEach { month ->
            month.clusters.forEach { cluster ->
                val name = cluster.name.trim()
                if (name.isEmpty()) return@forEach
                recordsOfTheme.getOrPut(name) { mutableSetOf() }.addAll(cluster.recordIds)
            }
        }

        // 线索 → 主题名（没有线索缓存时留空，下面会退回月度主题名）
        val threads = threadList?.let { runCatching { it() }.getOrNull() }.orEmpty()

        val out = HashMap<String, MutableList<String>>()
        val coveredThemes = HashSet<String>()
        threads.forEach { thread ->
            val threadName = thread.name.trim()
            if (threadName.isEmpty()) return@forEach
            thread.members.forEach { member ->
                val theme = member.trim()
                coveredThemes.add(theme)
                recordsOfTheme[theme].orEmpty().forEach { id ->
                    if (id in inRange) out.getOrPut(id) { mutableListOf() }.add(threadName)
                }
            }
        }
        // 没被线索覆盖的主题：用自己的名字兜底
        recordsOfTheme.forEach { (theme, ids) ->
            if (theme in coveredThemes) return@forEach
            ids.forEach { id ->
                if (id in inRange) out.getOrPut(id) { mutableListOf() }.add(theme)
            }
        }
        return out.mapValues { (_, v) -> v.distinct() }
    }
}
