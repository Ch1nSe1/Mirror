package memore.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import memore.app.data.model.EmbeddingState
import memore.app.data.model.RecordVector

@Dao
interface VectorDao {

    @Query("SELECT * FROM record_vectors WHERE record_id = :recordId ORDER BY chunk_index ASC")
    suspend fun byRecord(recordId: String): List<RecordVector>

    @Query("SELECT * FROM record_vectors")
    suspend fun all(): List<RecordVector>

    @Query("SELECT * FROM record_vectors WHERE model = :model")
    suspend fun allForModel(model: String): List<RecordVector>

    @Query("SELECT COUNT(DISTINCT record_id) FROM record_vectors")
    suspend fun indexedRecordCount(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(rows: List<RecordVector>)

    @Query("DELETE FROM record_vectors WHERE record_id = :recordId")
    suspend fun deleteByRecord(recordId: String)

    @Query("DELETE FROM record_vectors WHERE model != :model")
    suspend fun deleteOtherModels(model: String)

    @Query("SELECT * FROM embedding_state")
    suspend fun allStates(): List<EmbeddingState>

    @Query("SELECT * FROM embedding_state WHERE record_id = :recordId")
    suspend fun stateOf(recordId: String): EmbeddingState?

    @Query("SELECT record_id FROM embedding_state WHERE status = 'OK' AND model = :model")
    suspend fun okIds(model: String): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertState(state: EmbeddingState)

    @Query("DELETE FROM embedding_state WHERE record_id = :recordId")
    suspend fun deleteState(recordId: String)

    @Query("DELETE FROM embedding_state WHERE record_id NOT IN (SELECT id FROM records)")
    suspend fun pruneOrphanStates()

    @Query("DELETE FROM record_vectors")
    suspend fun deleteAllVectors()

    @Query("DELETE FROM embedding_state")
    suspend fun deleteAllStates()

    @Query("DELETE FROM record_vectors WHERE record_id NOT IN (SELECT id FROM records)")
    suspend fun pruneOrphanVectors()
}
