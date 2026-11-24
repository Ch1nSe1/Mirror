package memore.app.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.compose.runtime.Immutable

/**
 * 来源（生产者），2026-11-11。
 *
 * 和标签、协议并列的第三条轴，但语义不同：
 *  - 标签/协议回答「这条讲什么 / 遵循什么」——用于浏览筛选
 *  - **来源回答「这条是谁生产的」**——用于决定它该不该算进结构
 *
 * 一条记录只有一个来源；null = 未标（按 1.0 参与一切）。
 *
 * ⚠️ 三个权重分开（2026-11-11 二次修正）：用户要的是"同一个来源，在聚类/关联/统计里
 * 可以给不同的权重"，而不是"一个权重 + 三个开关"。权重 0 = 不参与该项。
 *
 * ⚠️ 命名：这里叫 origin，是为了和 Record 上的 `source_draft_id`（草稿转录来源）区分。
 */
@Entity(tableName = "origins")
@Immutable
data class Origin(
    @PrimaryKey
    val id: String,

    val name: String,

    /** 参与聚类（话题 / 母题）的权重，0 = 不参与。 */
    @ColumnInfo(name = "w_cluster")
    val wCluster: Float = 1f,

    /** 参与关联（候选与建边）的权重，0 = 不参与。 */
    @ColumnInfo(name = "w_link")
    val wLink: Float = 1f,

    /** 参与统计（字数、条数、情绪比例）的权重，0 = 不参与。 */
    @ColumnInfo(name = "w_stats")
    val wStats: Float = 1f,

    /**
     * 关联准入阈值（2026-11-17，来源系统的**阈值型**条目）。
     *
     * 这条来源的记录，相似度必须 ≥ 它才进关联候选；1.0 = 永不进。
     * 与 [wLink]（结构权重）分工：阈值管"进不进"，权重管"进了之后排第几"。
     * 默认值不是拍脑袋：用真机 321 条记录、bge-small 的 top8 相似度分布校准过
     * （原创中位 0.705、对话 0.748、转录 0.821）。
     */
    @ColumnInfo(name = "link_threshold")
    val linkThreshold: Float = 0.70f,

    @ColumnInfo(name = "sort_order")
    val sortOrder: Int = 0,

    /** 内置来源（可改名、可改权重，删除时记录变「未标」）。 */
    val builtin: Boolean = false,

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis()
) {
    /** 三项权重的代表值（列表里显示用：取非零的最小值，全零则 0）。 */
    val summaryWeight: Float
        get() {
            val nz = listOf(wCluster, wLink, wStats).filter { it > 0f }
            return nz.minOrNull() ?: 0f
        }

    companion object {
        const val ID_ORIGINAL = "origin_original"
        const val ID_DIALOGUE = "origin_dialogue"
        const val ID_REWRITE = "origin_rewrite"
        const val ID_TRANSCRIPT = "origin_transcript"
        const val ID_AI = "origin_ai"
        /** 2026-11-21：AI 札记的默认来源（Agent 自己写的）。可见，但不能手动选。 */
        const val ID_AGENT_AI = "origin_agent_ai"

        /**
         * 内置五条。
         *
         * 阈值按真机数据校准（2026-11-17，321 条记录的 top8 相似度分布：
         * 原创 p50 0.705 / 对话 0.748 / 转录 0.821），所以：
         *   原创 0.70、对话 0.72、摘录 0.68、转录 0.78
         * AI 转录 = 1.0（永不进候选，它本来就不该和自己写的分析互相关联）。
         */
        fun builtins(now: Long = System.currentTimeMillis()): List<Origin> = listOf(
            Origin(ID_ORIGINAL, "原创", 1.0f, 1.0f, 1.0f, linkThreshold = 0.70f, sortOrder = 0, builtin = true, createdAt = now),
            Origin(ID_DIALOGUE, "对话", 0.8f, 0.8f, 0.8f, linkThreshold = 0.72f, sortOrder = 1, builtin = true, createdAt = now),
            Origin(ID_REWRITE, "摘录再创作", 0.7f, 0.7f, 0.7f, linkThreshold = 0.68f, sortOrder = 2, builtin = true, createdAt = now),
            Origin(ID_TRANSCRIPT, "转录", 0.5f, 0.5f, 0.5f, linkThreshold = 0.78f, sortOrder = 3, builtin = true, createdAt = now),
            // AI 写的分析：不参与关联（阈值 1.0 + 权重 0），不进统计；叙事权重沿用 0.3
            Origin(ID_AI, "转录（AI）", 0.3f, 0.0f, 0.0f, linkThreshold = 1.0f, sortOrder = 4, builtin = true, createdAt = now),
            // 2026-11-21：AI 札记的默认来源。三项权重全 0、阈值 1.0；可见但不可手动选。
            Origin(ID_AGENT_AI, "AI", 0.0f, 0.0f, 0.0f, linkThreshold = 1.0f, sortOrder = 5, builtin = true, createdAt = now)
        )
    }
}
