package memore.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import memore.app.data.model.Draft
import kotlinx.coroutines.flow.Flow

/**
 * 草稿本（多本里的"本"）。
 *
 * 列表默认只给**未归档**的；归档的另用 [observeArchived] 取（页面上"展开归档"时才显示）。
 */
@Dao
interface DraftDao {

    /** 未归档的草稿，最近动过的在前。 */
    @Query("SELECT * FROM drafts WHERE archived = 0 ORDER BY pinned DESC, updated_at DESC")
    fun observeActive(): Flow<List<Draft>>

    @Query("SELECT * FROM drafts WHERE archived = 1 ORDER BY updated_at DESC")
    fun observeArchived(): Flow<List<Draft>>

    @Query("SELECT * FROM drafts WHERE id = :id")
    suspend fun get(id: Long): Draft?

    @Query("SELECT * FROM drafts")
    suspend fun all(): List<Draft>

    @Insert
    suspend fun insert(draft: Draft): Long

    @Query("UPDATE drafts SET name = :name, updated_at = :now WHERE id = :id")
    suspend fun rename(id: Long, name: String, now: Long)

    @Query("UPDATE drafts SET pinned = :pinned, updated_at = :now WHERE id = :id")
    suspend fun setPinned(id: Long, pinned: Boolean, now: Long)

    @Query("UPDATE drafts SET archived = :archived, updated_at = :now WHERE id = :id")
    suspend fun setArchived(id: Long, archived: Boolean, now: Long)

    @Query("UPDATE drafts SET updated_at = :now WHERE id = :id")
    suspend fun touch(id: Long, now: Long)

    @Query("DELETE FROM drafts WHERE id = :id")
    suspend fun delete(id: Long)
}
