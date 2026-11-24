package memore.app.data.model

import androidx.room.ColumnInfo

/**
 * 轻量记录投影（2026-11-13）。
 *
 * 只取"聚类要用到的那几列"，绕开 [Record] 上的 `analysis` JSON 反序列化。
 *
 * 为什么要有它：`getAllActiveRecords()` 会把每条记录的 analysis / emotionalTags
 * 一起反序列化。聚类只用 id / 正文 / 来源 / 三个过滤标记，却为此付出了
 * 实测 20–25MB 的连续 GC（logcat 里那串 "Background concurrent copying GC"）。
 *
 * 注意：**这不是 N+1 优化**。Room 的 `@Relation` 本来就是按批查（IN ids），
 * 关联查询一共 4 条 SQL，不随记录数增长。真凶是行太宽，不是次数太多。
 */
data class LightRecord(
    val id: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "content") val content: String,
    @ColumnInfo(name = "notes") val notes: String,
    @ColumnInfo(name = "origin_id") val originId: String?,
    @ColumnInfo(name = "is_locked") val isLocked: Boolean,
    @ColumnInfo(name = "is_deleted") val isDeleted: Boolean,
    @ColumnInfo(name = "correction_of_id") val correctionOfId: String?,
    /**
     * 记录类型（2026-11-15）。
     *
     * 加它的唯一原因：汇总类接口（stats / digest / facets / concepts / suggest）都走这条投影，
     * 而它们必须把 **AI 札记** 排除出去——札记不进任何汇总，只在自己的区域里出现。
     * 没有这一列，那些接口就分不清"用户写的"和"AI 写的"。
     */
    val type: String = "ESSAY"
)

/**
 * 增量同步用的最小投影（2026-11-15）：只要 id + 两个时间戳 + 删除标记。
 *
 * 为什么单开一个：`/changes?since=` 要判断"新建 / 更新 / 删除"，
 * 而 [LightRecord] 里没有 `updated_at`；改用 `Record` 全量又会把 analysis 一起反序列化
 * （那是 20–25MB 的 GC，见上面注释）。四列就够。
 */
data class ChangeRow(
    val id: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "is_deleted") val isDeleted: Boolean
)
