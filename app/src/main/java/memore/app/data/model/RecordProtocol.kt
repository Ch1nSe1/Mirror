package memore.app.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "record_protocols",
    primaryKeys = ["record_id", "protocol_id"],
    foreignKeys = [
        ForeignKey(
            entity = Record::class,
            parentColumns = ["id"],
            childColumns = ["record_id"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = Protocol::class,
            parentColumns = ["id"],
            childColumns = ["protocol_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["record_id"]),
        Index(value = ["protocol_id"])
    ]
)
data class RecordProtocol(
    @ColumnInfo(name = "record_id")
    val recordId: String,

    @ColumnInfo(name = "protocol_id")
    val protocolId: Int
)
