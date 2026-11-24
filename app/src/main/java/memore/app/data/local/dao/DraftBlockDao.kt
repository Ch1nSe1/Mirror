package memore.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import memore.app.data.model.DraftBlock
import kotlinx.coroutines.flow.Flow

@Dao
interface DraftBlockDao {

    /** 顺序 = id 升序（插入顺序）；屏幕上的 1、2、3 由此按下标算。 */
    @Query("SELECT * FROM draft_blocks ORDER BY id ASC")
    fun observeAll(): Flow<List<DraftBlock>>

    /** 某一本草稿的块（草稿页只看自己这本）。 */
    @Query("SELECT * FROM draft_blocks WHERE draft_id = :draftId ORDER BY id ASC")
    fun observeByDraft(draftId: Long): Flow<List<DraftBlock>>

    @Query("SELECT COUNT(*) FROM draft_blocks")
    fun observeCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM draft_blocks WHERE draft_id = :draftId")
    fun observeCountByDraft(draftId: Long): Flow<Int>

    @Query("SELECT COUNT(*) FROM draft_blocks WHERE draft_id = :draftId")
    suspend fun countBlocks(draftId: Long): Int

    /**
     * 一次性取某一本的块（按 draft_id **精确**取，绝不掺别的本）。
     * 用于"从抽屉里直接对某一本发起讨论"——那时屏幕上开着的是另一本。
     */
    @Query("SELECT * FROM draft_blocks WHERE draft_id = :draftId ORDER BY id ASC")
    suspend fun getByDraft(draftId: Long): List<DraftBlock>

    /** 最后编辑时间（改过算改动时间，没改过算创建时间）—— 给「新建」菜单上的提示用。 */
    @Query("SELECT MAX(COALESCE(updated_at, created_at)) FROM draft_blocks")
    fun observeLastEditedAt(): Flow<Long?>

    @Insert
    suspend fun insert(block: DraftBlock): Long

    @Query("UPDATE draft_blocks SET content = :content, updated_at = :now WHERE id = :id")
    suspend fun updateContent(id: Long, content: String, now: Long)

    @Query("DELETE FROM draft_blocks WHERE id = :id")
    suspend fun delete(id: Long)

    /** 删一本草稿时把它的块一并删掉（手动级联，避免给这张小表加外键约束）。 */
    @Query("DELETE FROM draft_blocks WHERE draft_id = :draftId")
    suspend fun deleteByDraft(draftId: Long)
}
