package memore.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import memore.app.data.model.Dial
import memore.app.data.model.DialTick
import kotlinx.coroutines.flow.Flow

/**
 * 表盘 + 表盘↔刻度 关联。
 *
 * 表盘是**硬删**的（这张表只是"框"）：删框不删内容，刻度一个不动、只是变回未归属。
 * 所以这里没有 `deleted_at`，也没有回收站。
 */
@Dao
interface DialDao {

    /** 进行中的排最前，其余按起点新的在前。 */
    @Query("SELECT * FROM dials ORDER BY end_ms IS NULL DESC, start_ms DESC")
    fun observeAll(): Flow<List<Dial>>

    @Query("SELECT * FROM dials ORDER BY end_ms IS NULL DESC, start_ms DESC")
    suspend fun all(): List<Dial>

    @Query("SELECT * FROM dials WHERE id = :id")
    suspend fun getById(id: String): Dial?

    @Insert
    suspend fun insert(dial: Dial)

    /** 导入用：id 冲突就忽略（不覆盖本机已有表盘）。 */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(dial: Dial)

    @Update
    suspend fun update(dial: Dial)

    /** 写总结：`summary_prev = summary, summary = 新值`，一次 UPDATE 完成。 */
    @Query("UPDATE dials SET summary_prev = summary, summary = :summary, updated_at = :now WHERE id = :id")
    suspend fun updateSummary(id: String, summary: String, now: Long = System.currentTimeMillis())

    /** 换回上一版（两者对调，连按两次可以来回对比）。 */
    @Query("UPDATE dials SET summary = summary_prev, summary_prev = summary, updated_at = :now WHERE id = :id")
    suspend fun swapSummary(id: String, now: Long = System.currentTimeMillis())

    @Query("DELETE FROM dials WHERE id = :id")
    suspend fun delete(id: String)

    // ---- 关联 ----

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun link(row: DialTick)

    @Query("DELETE FROM dial_ticks WHERE dial_id = :dialId AND tick_id = :tickId")
    suspend fun unlink(dialId: String, tickId: String)

    @Query("DELETE FROM dial_ticks WHERE dial_id = :dialId")
    suspend fun deleteLinksOfDial(dialId: String)

    @Query("DELETE FROM dial_ticks WHERE tick_id = :tickId")
    suspend fun deleteLinksOfTick(tickId: String)

    @Query("SELECT tick_id FROM dial_ticks WHERE dial_id = :dialId")
    suspend fun tickIdsOf(dialId: String): List<String>

    @Query("SELECT dial_id FROM dial_ticks WHERE tick_id = :tickId")
    suspend fun dialIdsOf(tickId: String): List<String>

    @Query("SELECT * FROM dial_ticks")
    suspend fun allLinks(): List<DialTick>
}
