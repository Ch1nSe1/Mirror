package memore.app.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "record_tags",
    primaryKeys = ["record_id", "tag_id"],
    foreignKeys = [
        ForeignKey(
            entity = Record::class,
            parentColumns = ["id"],
            childColumns = ["record_id"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = Tag::class,
            parentColumns = ["id"],
            childColumns = ["tag_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["record_id"]),
        Index(value = ["tag_id"])
    ]
)
data class RecordTagCrossRef(
    @ColumnInfo(name = "record_id")
    val recordId: String,

    @ColumnInfo(name = "tag_id")
    val tagId: Int
)
