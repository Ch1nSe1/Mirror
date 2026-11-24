package memore.app.data.model

import androidx.compose.runtime.Immutable
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 秒针的刻度：一段可命名、有边界的自我时间区间。
 * 归属规则：记录/增修按各自 created_at 落在 [startMs, endMs) 的刻度（endMs=null 表示进行中/开放，归一 [startMs, now)）。
 * 刻度只是时间区间注解，不拥有记录 —— records 表不加字段。
 */
@Entity(
    tableName = "ticks",
    indices = [Index(value = ["start_ms"]), Index(value = ["deleted_at"])]
)
@Immutable
data class Tick(
    @PrimaryKey
    val id: String,

    @ColumnInfo(name = "name")
    val name: String,

    @ColumnInfo(name = "start_ms")
    val startMs: Long,

    /** null = 进行中/开放刻度（无终点）。 */
    @ColumnInfo(name = "end_ms")
    val endMs: Long? = null,

    /** null = 未删除；非 null = 软删除时间。 */
    @ColumnInfo(name = "deleted_at")
    val deletedAt: Long? = null,

    /** 定格时间（= 终结刻度的 endMs）：非空 = 已定格（切片已锁存，不再随 now 重算）。 */
    @ColumnInfo(name = "frozen_at")
    val frozenAt: Long? = null,

    /** 定格切片快照 JSON（emotion/nodes 分数权重），锁定后不再变；进行中刻度为 null（实时算）。 */
    @ColumnInfo(name = "slice_snapshot")
    val sliceSnapshot: String? = null,

    /**
     * 刻度总结（后记）：用户为这一段时期写的长文，想写多长写多长、随时可改。
     * 与 [sliceSnapshot] 完全独立 —— 写总结**不会**碰定格快照，也不触发重新定格。
     */
    @ColumnInfo(name = "summary")
    val summary: String = "",

    /**
     * 上一版总结（覆盖前自动留档）。只留一版：够用来救"手一滑把整段弄坏了"。
     * 每次保存时由 SQL 原子地 `summary_prev = summary, summary = 新值`，不需要额外读。
     */
    @ColumnInfo(name = "summary_prev")
    val summaryPrev: String = "",

    /**
     * 回看（2026-11-22）：AI 对这一段时间的叙述，与 [summary] 独立保存、分开显示。
     * 重复生成并保存时直接覆盖它，不保留旧版。
     */
    @ColumnInfo(name = "review")
    val review: String = "",

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = System.currentTimeMillis()
)
