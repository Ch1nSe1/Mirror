package memore.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import memore.app.data.model.EncryptionConfig

@Dao
interface EncryptionConfigDao {

    @Query("SELECT * FROM encryption_config WHERE id = 1")
    suspend fun get(): EncryptionConfig?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(config: EncryptionConfig)

    @Query("DELETE FROM encryption_config")
    suspend fun delete()
}
