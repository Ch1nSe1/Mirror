package memore.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import memore.app.data.model.Version

@Dao
interface VersionDao {

    @Insert
    suspend fun insert(version: Version)

    @Query("SELECT * FROM versions WHERE record_id = :recordId ORDER BY created_at DESC LIMIT 5")
    suspend fun getByRecordId(recordId: String): List<Version>

    /** 全量版本快照（供整体备份） */
    @Query("SELECT * FROM versions ORDER BY record_id ASC, created_at DESC")
    suspend fun getAll(): List<Version>

    @Query("DELETE FROM versions WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM versions WHERE record_id = :recordId AND id NOT IN (SELECT id FROM versions WHERE record_id = :recordId ORDER BY created_at DESC LIMIT 5)")
    suspend fun trimToFive(recordId: String)

    @Transaction
    suspend fun insertAndTrim(version: Version, recordId: String) {
        insert(version)
        trimToFive(recordId)
    }
}
