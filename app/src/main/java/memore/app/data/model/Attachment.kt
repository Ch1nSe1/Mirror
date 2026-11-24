package memore.app.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "attachments",
    indices = [
        Index(value = ["record_id"]),
        Index(value = ["record_id", "file_name"], unique = true)
    ]
)
data class Attachment(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    @ColumnInfo(name = "record_id")
    val recordId: String,

    @ColumnInfo(name = "file_name")
    val fileName: String,

    @ColumnInfo(name = "file_path")
    val filePath: String,

    @ColumnInfo(name = "type")
    val type: String,

    @ColumnInfo(name = "file_size")
    val fileSize: Long = 0,

    @ColumnInfo(name = "duration")
    val duration: Long? = null,

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "iv")
    val iv: String = "",

    @ColumnInfo(name = "auth_tag")
    val authTag: String = "",

    /** 文件名的加密存储（AES-GCM 密文 JSON {d,i,t}），is_encrypted=1 时使用。 */
    @ColumnInfo(name = "enc_name")
    val encName: String = "",

    @ColumnInfo(name = "is_encrypted")
    val isEncrypted: Boolean = false
)
