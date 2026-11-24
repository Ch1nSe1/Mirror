package memore.app.data.repository

import memore.app.data.model.Record
import memore.app.data.model.Tick

/**
 * 记录 ↔ 刻度的归属规则（2026-11-20）。
 *
 * 为什么单独抽出来：归属以前在三个地方各算一遍——
 *  - 导出（ExportEngine 里临时的 `recordToTickId`）；
 *  - 刻度详情（TickRecordsScreen 走 `recordsInRange`）；
 *  - agent 投影（2026-11-20 新增）。
 * 规则本身只有一条：**按 created_at 落在 [startMs, endMs)；endMs=null 视为进行中**。
 * 三处各自实现，改一次要改三遍，还容易在边界上分叉。
 *
 * 注意：刻度只是**时间区间注解**，records 表不加字段、不存成员表；
 * 这里算出来的映射是临时的，不落库。
 */
object TickMembership {

    /** 某个时刻是否落在刻度内。endMs=null 的进行中刻度接受 >= startMs 的时间。 */
    fun contains(tick: Tick, at: Long): Boolean {
        if (tick.deletedAt != null) return false
        if (at < tick.startMs) return false
        val end = tick.endMs ?: return true
        return at < end
    }

    /** 一条记录归哪个刻度：取第一个命中的（刻度理论上不重叠）。 */
    fun tickOf(at: Long, ticks: List<Tick>): Tick? = ticks.firstOrNull { contains(it, at) }

    /** recordId → tickId（没归到任何刻度记为 ""），给导出/投影共用。 */
    fun recordTickMap(records: List<Record>, ticks: List<Tick>): Map<String, String> =
        records.associate { r -> r.id to (tickOf(r.createdAt, ticks)?.id ?: "") }
}
