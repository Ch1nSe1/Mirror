package memore.app.data.model

import androidx.compose.runtime.Immutable
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "tags",
    indices = [
        Index(value = ["name"], unique = true),
        Index(value = ["usage_count"])
    ]
)
@Immutable
data class Tag(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,

    @ColumnInfo(name = "name")
    val name: String,

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "usage_count")
    val usageCount: Int = 0,

    /** 所属标签组（null = 未分组）。 */
    @ColumnInfo(name = "group_id")
    val groupId: Int? = null
)
