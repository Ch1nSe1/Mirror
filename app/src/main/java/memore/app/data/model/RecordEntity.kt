package memore.app.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/**
 * 记录里的锚点（2026-11-19）。
 *
 * 为什么单独一张表：之前"这条记录提到谁/哪首歌/哪里"没有任何结构化记录，
 * 搜索、关联、主题都只能靠字面和向量。锚点是**确定信号**——同一个人名出现在两条记录里，
 * 比"向量相似度 0.7"硬得多。
 *
 * 约束：
 * - 名称来自原文提取，`quote` 必须是正文里的原句（落库前做子串校验）；
 * - 一个记录可以有多条锚点，主键是 (record_id, name, kind)；
 * - 记录删除时级联删除。
 */
@Entity(
    tableName = "record_entities",
    primaryKeys = ["record_id", "name", "kind"],
    foreignKeys = [
        ForeignKey(
            entity = Record::class,
            parentColumns = ["id"],
            childColumns = ["record_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("name"), Index("record_id")]
)
data class RecordEntity(
    @ColumnInfo(name = "record_id") val recordId: String,
    @ColumnInfo(name = "name") val name: String,
    /** PERSON / WORK / PLACE / EVENT / OTHER */
    @ColumnInfo(name = "kind") val kind: String = KIND_OTHER,
    /** 原文里支撑这个锚点的一句（≤40 字），落库前已做子串校验。 */
    @ColumnInfo(name = "quote", defaultValue = "") val quote: String = "",
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis()
) {
    companion object {
        const val KIND_PERSON = "PERSON"
        const val KIND_WORK = "WORK"
        const val KIND_PLACE = "PLACE"
        const val KIND_EVENT = "EVENT"
        const val KIND_OTHER = "OTHER"

        /** 只接受这几种，别的当 OTHER。 */
        val KINDS = setOf(KIND_PERSON, KIND_WORK, KIND_PLACE, KIND_EVENT, KIND_OTHER)

        /** 锚点名称的硬约束：过长/过短/带空白的丢弃。 */
        fun isValidName(name: String): Boolean {
            val n = name.trim()
            return n.isNotEmpty() && n.length <= 20 && !n.contains('\n')
        }
    }
}
