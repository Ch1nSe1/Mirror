package memore.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import memore.app.data.model.Origin
import kotlinx.coroutines.flow.Flow

/** 来源（生产者）表。见 [Origin] 的注释：它决定"这条该不该算进结构"。 */
@Dao
interface OriginDao {

    @Query("SELECT * FROM origins ORDER BY sort_order ASC")
    fun observeAll(): Flow<List<Origin>>

    @Query("SELECT * FROM origins ORDER BY sort_order ASC")
    suspend fun all(): List<Origin>

    @Query("SELECT * FROM origins WHERE id = :id")
    suspend fun byId(id: String): Origin?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(origin: Origin)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(list: List<Origin>)

    @Update
    suspend fun update(origin: Origin)

    @Query("DELETE FROM origins WHERE id = :id")
    suspend fun delete(id: String)

    /** 删除来源前把它的记录改成未标（NULL）。 */
    @Query("UPDATE records SET origin_id = NULL WHERE origin_id = :id")
    suspend fun clearRecordsOf(id: String)

    /** 某来源下的记录（核对用：点进去看这批到底是谁）。 */
    @Query("SELECT * FROM records WHERE origin_id = :id AND is_deleted = 0 AND correction_of_id IS NULL ORDER BY created_at DESC")
    suspend fun recordsOf(id: String): List<memore.app.data.model.Record>

    @Query("SELECT COUNT(*) FROM records WHERE origin_id = :id AND is_deleted = 0 AND correction_of_id IS NULL")
    suspend fun countRecords(id: String): Int

    @Query("SELECT COUNT(*) FROM records WHERE origin_id IS NULL AND is_deleted = 0 AND correction_of_id IS NULL")
    suspend fun countUnassigned(): Int

    /** 把所有未标记录一次性归到某个来源（修复误删来源后的一次性回填）。 */
    @Query("UPDATE records SET origin_id = :id WHERE origin_id IS NULL AND is_deleted = 0 AND correction_of_id IS NULL")
    suspend fun assignAllUnassigned(id: String)

    /** 批量指派（自动识别的一次性写入）。 */
    @Query("UPDATE records SET origin_id = :id WHERE id IN (:recordIds)")
    suspend fun assign(recordIds: List<String>, id: String)

    /** 批量清成「未标」（批量改来源选「不标」时用，一条 SQL 而不是逐条）。 */
    @Query("UPDATE records SET origin_id = NULL WHERE id IN (:recordIds)")
    suspend fun clearForRecords(recordIds: List<String>)

    /**
     * 2026-11-21：AI 札记的来源回填到「AI」。
     * 只改“未标”或被旧规则猜成「转录（AI）」(origin_ai) 的；显式设过的其他来源不动。
     */
    @Query("UPDATE records SET origin_id = :newId WHERE type = 'AI_NOTE' AND (origin_id IS NULL OR origin_id = :oldAiId)")
    suspend fun assignAiNoteOrigins(newId: String, oldAiId: String)
}
