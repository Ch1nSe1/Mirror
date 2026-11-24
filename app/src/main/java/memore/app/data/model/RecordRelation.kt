package memore.app.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 记录之间的**有向、带类型**关系。
 *
 * 为什么不再继续用 `records.linked_ids`：那是**对称**字段（两边互相包含，有一对原子写维护），
 * 结构上就表达不了"我引用了它"和"它引用了我"的区别；也没法反查"谁引用了我"（只能全表扫 JSON），
 * 删一条还会留下悬空 id。有向关系天然是三元组 (from, to, type) → 一张小表，
 * 与你库里已有的 record_tags / record_memory_nodes 是同一个模式。
 *
 * `type` 存**字符串**而不是枚举：将来加"依赖""矛盾"这类**不用做数据库迁移**；
 * UI 遇到不认识的值原样显示。
 *
 * 与「增修」的分工（重要）：`records.correction_of_id` 表达的是**归属**
 * （"我又写了一段挂在这条下面"，并让增修不进主时间线），**不是**"这条更正了那条"。
 * 真正的语义关系一律走本表 —— 因为增修只适合近期内容，不适合作对老记录的修正。
 */
@Entity(
    tableName = "record_relations",
    indices = [
        Index(value = ["from_id"]),
        Index(value = ["to_id"]),
        Index(value = ["from_id", "to_id", "type"], unique = true)
    ]
)
data class RecordRelation(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /** 发起方（"我"）。 */
    @ColumnInfo(name = "from_id")
    val fromId: String,

    /** 指向方（"它"）。 */
    @ColumnInfo(name = "to_id")
    val toId: String,

    @ColumnInfo(name = "type")
    val type: String,

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),

    /** AI 关联边的一句话解释（≤20 字，只描述"哪里像"，不评判）。手动关系为空。 */
    @ColumnInfo(name = "why", defaultValue = "")
    val why: String = "",

    /** 发起方原句（AI 边必有，≤25 字；界面用来回答"凭什么连"）。手动关系为空。 */
    @ColumnInfo(name = "quote_a", defaultValue = "")
    val quoteA: String = "",

    /** 指向方原句（AI 边必有，≤25 字；两句必须原样存在于各自记录里，否则这条边不落库）。 */
    @ColumnInfo(name = "quote_b", defaultValue = "")
    val quoteB: String = "",

    /** 建立这条边时的向量相似度（只在内部用，界面不显示）。 */
    @ColumnInfo(name = "score", defaultValue = "0")
    val score: Double = 0.0,

    /** 谁建的：USER / AI / VECTOR / AGENT（2026-11-20 起 agent 走 AGENT，不冒充 AI） */
    @ColumnInfo(name = "source", defaultValue = "USER")
    val source: String = "USER",

    /** 这条边何时开始为真（2026-11-19）。重跑分析时旧边只失效，不删除。 */
    @ColumnInfo(name = "valid_from", defaultValue = "0")
    val validFrom: Long = 0,

    /** 何时失效；null = 仍然有效。关联页只显示 null 的边。 */
    @ColumnInfo(name = "valid_to")
    val validTo: Long? = null,

    /** ACTIVE / REJECTED —— 拒绝的边留着当负样本，也防止下次重复推荐。 */
    @ColumnInfo(name = "status", defaultValue = "ACTIVE")
    val status: String = "ACTIVE"
) {
    companion object {
        const val SOURCE_USER = "USER"
        const val SOURCE_AI = "AI"
        const val SOURCE_VECTOR = "VECTOR"
        const val SOURCE_AGENT = "AGENT"
    }
}

/** 关系类型。存的就是这里的字符串常量，加类型不需要数据库迁移。 */
object RecordRelationType {

    /** 引用：拿它当依据。 */
    const val CITE = "CITE"

    /** 补充：在它基础上添东西。 */
    const val SUPPLEMENT = "SUPPLEMENT"

    /** 更正：这条**更正/推翻**了它 —— 涵盖反驳、改口、纠错（比"反驳"宽）。 */
    const val CORRECT = "CORRECT"

    /** AI 关联：向量召回 + AI 判定"这两条像同一件事"。why 存一句话解释。 */
    const val LINK = "LINK"

    /** 可选类型，顺序即 UI 展示顺序。 */
    val ALL = listOf(CITE, SUPPLEMENT, CORRECT)

    /** 短标签（分组标题、chip 用）。 */
    fun label(type: String): String = when (type) {
        LINK -> "关联"
        CITE -> "引用"
        SUPPLEMENT -> "补充"
        CORRECT -> "更正"
        else -> type
    }

    /** 出向读数：本条「引用」→ 那条。 */
    fun outgoingPhrase(type: String): String = when (type) {
        LINK -> "和它有共通之处"
        CITE -> "引用了它"
        SUPPLEMENT -> "补充了它"
        CORRECT -> "更正了它"
        else -> type
    }

    /** 入向读数：那条 → 本条「被引用」。 */
    fun incomingPhrase(type: String): String = when (type) {
        LINK -> "和这条有共通之处"
        CITE -> "引用了这条"
        SUPPLEMENT -> "补充了这条"
        CORRECT -> "更正了这条"
        else -> type
    }
}
