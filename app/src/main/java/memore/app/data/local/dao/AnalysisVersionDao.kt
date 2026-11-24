package memore.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import memore.app.data.model.AnalysisVersion

@Dao
interface AnalysisVersionDao {

    @Insert
    suspend fun insert(version: AnalysisVersion): Long

    /** 插入后只保留最近 [keep] 版：重跑不销毁历史，但也不无限增长。 */
    @Query("DELETE FROM analysis_versions WHERE record_id = :recordId AND id NOT IN " +
        "(SELECT id FROM analysis_versions WHERE record_id = :recordId ORDER BY id DESC LIMIT :keep)")
    suspend fun trim(recordId: String, keep: Int)

    suspend fun insertThenTrim(version: AnalysisVersion, keep: Int = 5): Long {
        val id = insert(version)
        trim(version.recordId, keep)
        return id
    }

    @Query("SELECT * FROM analysis_versions WHERE record_id = :recordId ORDER BY id DESC")
    suspend fun forRecord(recordId: String): List<AnalysisVersion>

    @Query("SELECT * FROM analysis_versions WHERE record_id = :recordId ORDER BY id DESC LIMIT 1")
    suspend fun latest(recordId: String): AnalysisVersion?

    @Query("SELECT COUNT(*) FROM analysis_versions WHERE record_id = :recordId")
    suspend fun countFor(recordId: String): Int

    /** 导出用：全部版本。 */
    @Query("SELECT * FROM analysis_versions ORDER BY id")
    suspend fun all(): List<AnalysisVersion>
}
