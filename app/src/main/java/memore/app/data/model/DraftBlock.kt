package memore.app.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 草稿块的四类性质。
 *
 * 注意（2026-10-14 起）：**kind 只是"这块怎么来的"，不再决定"送不送 AI"的分工** ——
 * 整理 / 质疑 / 讨论三个功能送进去的是**同一份材料**（正文 + 问答 + 讨论按原顺序拼一段），
 * 哪段是正文、哪段是问答、哪段是讨论交给 AI 自己判断
 *（用户原话：「让 AI 判断哪些是正文哪些是讨论和质疑就行了，不然讨论和质疑给的内容容易重叠」）。
 * 唯一还按 kind 排除的是 [AI] 产出的正文 —— 送回去会反复回炉，而且和正文重复。
 */
object DraftKind {
    /** 作者自己写的（唯一会被编号的一类：1、2、3…）。 */
    const val MINE = "MINE"
    /** AI 整理出来的正文：不编号，且**不再作为下一次整理的输入**。 */
    const val AI = "AI"
    /** 质疑：AI 提出的问题 + 作者的答案（写作时要参考，合适处可引用原话）。 */
    const val QA = "QA"
    /** 讨论：AI 关于"该怎么改、该怎么写"的建议 + 作者的回应（只送给整理，借鉴写作方式）。 */
    const val DISCUSS = "DISCUSS"
    /** 手动拼接的成品（2026-11-19）：不经过 AI，把正文块按顺序拼起来再编辑。 */
    const val RAW = "RAW"
}

/**
 * 草稿的一块。
 *
 * 设计要点：
 * - **独立于 records 表**：草稿不是记录，绝不进时间线 / 搜索 / 情绪统计 / 图谱。
 * - **没有"位置"字段**：列表顺序 = [id] 升序（插入即追加），屏幕上显示的 1、2、3
 *   是**渲染时按下标算的**（只给 [DraftKind.MINE] 编号）。
 * - **kind 只标记来源**：三个 AI 功能送的是同一份材料（[memore.app.data.repository.DraftRepository.buildAiInput]），
 *   只有 [DraftKind.AI] 的产出不进（否则 AI 的输出会被反复回炉）。
 * - 不限字数、不限条数。
 */
@Entity(tableName = "draft_blocks")
data class DraftBlock(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    /** 正文（QA / DISCUSS 类型存的是"问：/答："、"建议：/回应："这种成文格式）。 */
    val content: String,
    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),
    /** 改过一次就记下时间（列表上显示「改 …」）；没改过为 null。 */
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long? = null,
    @ColumnInfo(name = "kind", defaultValue = "MINE")
    val kind: String = DraftKind.MINE,
    /** 属于哪本草稿（多本）。默认 1 是历史数据的老本（迁移时建的那本）。 */
    @ColumnInfo(name = "draft_id", defaultValue = "1")
    val draftId: Long = 1
)
