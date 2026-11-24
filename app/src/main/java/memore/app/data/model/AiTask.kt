package memore.app.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "ai_tasks",
    foreignKeys = [
        ForeignKey(
            entity = Record::class,
            parentColumns = ["id"],
            childColumns = ["record_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["status"]),
        Index(value = ["record_id"])
    ]
)
data class AiTask(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    @ColumnInfo(name = "record_id")
    val recordId: String,
    @ColumnInfo(name = "task_type")
    val taskType: String = "ANALYZE",
    val status: String = "PENDING",
    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "last_attempt_at")
    val lastAttemptAt: Long? = null,
    @ColumnInfo(name = "retry_count")
    val retryCount: Int = 0,
    @ColumnInfo(name = "max_retries")
    val maxRetries: Int = 3,
    @ColumnInfo(name = "error_message")
    val errorMessage: String? = null
)
