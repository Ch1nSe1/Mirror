package memore.app.data.model

import androidx.compose.runtime.Immutable
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** 标签组：组织壳，非标签本身。成员 = group_id 指向某组的标签。 */
@Entity(
    tableName = "tag_groups",
    indices = [Index(value = ["name"], unique = true)]
)
@Immutable
data class TagGroup(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,

    @ColumnInfo(name = "name")
    val name: String,

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis()
)
