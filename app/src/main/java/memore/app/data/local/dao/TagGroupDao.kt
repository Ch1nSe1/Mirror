package memore.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import memore.app.data.model.TagGroup

@Dao
interface TagGroupDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(group: TagGroup): Long

    @Update
    suspend fun update(group: TagGroup)

    @Query("SELECT * FROM tag_groups ORDER BY name ASC")
    suspend fun getAll(): List<TagGroup>

    @Query("SELECT * FROM tag_groups WHERE id = :id")
    suspend fun getById(id: Int): TagGroup?

    @Query("SELECT * FROM tag_groups WHERE name = :name")
    suspend fun getByName(name: String): TagGroup?

    @Query("DELETE FROM tag_groups WHERE id = :id")
    suspend fun delete(id: Int)

    /** 删除分组时，把其下标签置为未分组（group_id=NULL）。 */
    @Query("UPDATE tags SET group_id = NULL WHERE group_id = :id")
    suspend fun detachTags(id: Int)
}
