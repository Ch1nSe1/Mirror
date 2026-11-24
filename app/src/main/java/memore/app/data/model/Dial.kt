package memore.app.data.model

import androidx.compose.runtime.Immutable
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 表盘：**你圈出来的一段时期**，用来包络若干刻度。
 *
 * 为什么不是"年度"：用户 2026-10-19 明说「我说这一年总结是指的这大概 11 个月，不是指年度总结，
 * 也就是指的是一个时期」—— 所以表盘没有"自然年"这个概念：起止自己定、可以只有两个月、
 * 可以跨年、可以「进行中」（[endMs] = null）。
 *
 * 语义边界（关键）：
 * - 表盘是**观察层**，不是归属层：记录的归属仍然只看刻度（`created_at` 落区间 + 起点更晚者优先）。
 *   **表盘没有权重**，不参与导出的 stage 加权。
 * - 一个刻度可以被**多个表盘**包络（多父，见 [DialTick]）—— 时期天然重叠：
 *   「今年这 11 个月」和「考研那几个月」可以同时看见同一批刻度。
 * - 删表盘只删这个"框"：刻度一个不动（它们只是变回未归属）。
 */
@Immutable
@Entity(tableName = "dials")
data class Dial(
    @PrimaryKey
    val id: String,

    @ColumnInfo(name = "name")
    val name: String,

    @ColumnInfo(name = "start_ms")
    val startMs: Long,

    /** null = 进行中（这段时期还没结束）。 */
    @ColumnInfo(name = "end_ms")
    val endMs: Long? = null,

    // 2026-10-20：这里原来的 frozen_at / slice_snapshot（「打算盘」定格）已删除 ——
    // 表盘只是"一段时期的框"，它自己没有内容可锁；被删掉的还有对应的 UI 与迁移（47→48）。
    /** 这一段的总结（后记）：想写多长写多长，随时可改。 */
    @ColumnInfo(name = "summary")
    val summary: String = "",

    /** 上一版总结（覆盖前自动留档，只留一版）。 */
    @ColumnInfo(name = "summary_prev")
    val summaryPrev: String = "",

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = System.currentTimeMillis()
)

/**
 * 表盘 ↔ 刻度（多对多）。
 *
 * 用关联表而不是给 `ticks` 加 `dial_id`：表盘是"框"，同一个刻度要能被两个时期同时框住
 *（用户 2026-10-19 选的多父）。没有外键 —— 和草稿一样手动级联，避免给这两张小表加 FK。
 */
@Entity(
    tableName = "dial_ticks",
    primaryKeys = ["dial_id", "tick_id"],
    indices = [androidx.room.Index(value = ["tick_id"])]
)
@Immutable
data class DialTick(
    @ColumnInfo(name = "dial_id")
    val dialId: String,

    @ColumnInfo(name = "tick_id")
    val tickId: String
)
