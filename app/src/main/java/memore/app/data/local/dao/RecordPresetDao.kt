package memore.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import memore.app.data.model.RecordPreset

@Dao
interface RecordPresetDao {

    /**
     * 排序＝"哪个后执行就在最前面"：
     * 先按最近使用时间降序，再按创建时间降序。刚建的（last_used_at=0）会排在
     * 用过的后面，但创建时间新的在同组里靠前 —— 正是用户描述的"先执行的就是第二个"。
     */
    @Query("SELECT * FROM record_presets ORDER BY last_used_at DESC, created_at DESC")
    suspend fun all(): List<RecordPreset>

    @Insert
    suspend fun insert(preset: RecordPreset): Long

    @Update
    suspend fun update(preset: RecordPreset)

    @Query("DELETE FROM record_presets WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("UPDATE record_presets SET last_used_at = :at WHERE id = :id")
    suspend fun touch(id: Long, at: Long)
}
