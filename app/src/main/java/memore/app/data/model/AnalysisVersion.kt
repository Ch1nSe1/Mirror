package memore.app.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 一次分析的快照（2026-11-19）。
 *
 * 为什么要有它：`records.analysis` 是"最新一版"，重跑会覆盖；而秒针定格的原则是
 * **历史不覆盖**。所以每次分析追加一行，记录当时用的模型、提示词版本、输入指纹和产出。
 * 一条记录保留最近 5 版（见 AnalysisVersionDao.insertThenTrim）。
 */
@Entity(
    tableName = "analysis_versions",
    foreignKeys = [
        ForeignKey(
            entity = Record::class,
            parentColumns = ["id"],
            childColumns = ["record_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("record_id")]
)
data class AnalysisVersion(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "record_id") val recordId: String,
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "model", defaultValue = "") val model: String = "",
    @ColumnInfo(name = "prompt_version", defaultValue = "") val promptVersion: String = "",
    /** 正文 + 增修的指纹；和这次分析用的输入对得上才不是 STALE。 */
    @ColumnInfo(name = "input_hash", defaultValue = "") val inputHash: String = "",
    @ColumnInfo(name = "summary", defaultValue = "") val summary: String = "",
    @ColumnInfo(name = "emotion_json", defaultValue = "[]") val emotionJson: String = "[]",
    /** OK / FAILED */
    @ColumnInfo(name = "status", defaultValue = "OK") val status: String = "OK",
    @ColumnInfo(name = "reason", defaultValue = "") val reason: String = ""
)
