package memore.app.data.model

import androidx.room.Embedded
import androidx.room.Ignore
import androidx.room.Junction
import androidx.room.Relation

/**
 * 记录 + 关联数据的聚合模型。
 * 通过 Room @Relation + @Junction 在 Flow 首帧同步组装 tags/protocols，避免异步"弹出"。
 *
 * amendments（增修）是自引用（correction_of_id），Room @Relation 无法表达，
 * 标记 @Transient 由 DAO 的 @Transaction 方法手动组装进此字段。
 */
class RecordWithDetails {
    @Embedded
    var record: Record? = null

    @Relation(
        parentColumn = "id",
        entityColumn = "id",
        associateBy = Junction(
            value = RecordTagCrossRef::class,
            parentColumn = "record_id",
            entityColumn = "tag_id"
        )
    )
    var tags: List<Tag> = emptyList()

    @Relation(
        parentColumn = "id",
        entityColumn = "id",
        associateBy = Junction(
            value = RecordProtocol::class,
            parentColumn = "record_id",
            entityColumn = "protocol_id"
        )
    )
    var protocols: List<Protocol> = emptyList()

    @Ignore
    var amendments: List<Record> = emptyList()

    constructor()

    constructor(record: Record, tags: List<Tag> = emptyList(), protocols: List<Protocol> = emptyList(), amendments: List<Record> = emptyList()) {
        this.record = record
        this.tags = tags
        this.protocols = protocols
        this.amendments = amendments
    }
}
