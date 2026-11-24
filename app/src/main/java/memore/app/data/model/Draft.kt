package memore.app.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 一本草稿（多本里的"一本"）。
 *
 * 为什么要有它：草稿**不止一本**（用户 2026-10-10 的要求：像 DeepSeek 的会话列表那样，
 * 不同的"对话窗口"各存各的）；而且**出成品之后草稿仍然要留着** ——
 * 那些不想记进正式版的原文（半成品、被删掉的段落、跑题的想法）就存在这里。
 *
 * **名字由用户自己起**（不做自动取标题）。
 */
@Entity(tableName = "drafts")
data class Draft(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    /** 用户起的名字。 */
    val name: String,
    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),
    /** 最后一次动过的时间（加块 / 改块 / 改名都会刷新）—— 列表按它排序与分组。 */
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = System.currentTimeMillis(),
    /** 归档：列表默认不显示，但内容一个不删、随时可恢复。 */
    @ColumnInfo(name = "archived", defaultValue = "0")
    val archived: Boolean = false,
    /** 置顶：抽屉里排在最上面（照 DeepSeek 的「置顶」分组）。 */
    @ColumnInfo(name = "pinned", defaultValue = "0")
    val pinned: Boolean = false
)
