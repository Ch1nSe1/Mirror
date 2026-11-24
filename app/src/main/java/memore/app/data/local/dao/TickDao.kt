package memore.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import memore.app.data.model.Tick

@Dao
interface TickDao {

    @Insert
    suspend fun insert(tick: Tick)

    /** 导入用：id 已存在则忽略（不覆盖本地已有刻度）。 */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(tick: Tick)

    @Update
    suspend fun update(tick: Tick)

    /** 活跃（未软删）刻度，按起点时间降序（最新时段在前）。 */
    @Query("SELECT * FROM ticks WHERE deleted_at IS NULL ORDER BY start_ms DESC")
    suspend fun getActive(): List<Tick>

    /** 已软删刻度，按删除时间倒序（最近删除在前），供恢复。 */
    @Query("SELECT * FROM ticks WHERE deleted_at IS NOT NULL ORDER BY deleted_at DESC")
    suspend fun getDeleted(): List<Tick>

    @Query("SELECT * FROM ticks WHERE id = :id")
    suspend fun getById(id: String): Tick?

    @Query("SELECT * FROM ticks WHERE deleted_at IS NULL AND start_ms < :start ORDER BY start_ms DESC LIMIT 1")
    suspend fun getPreviousActive(start: Long): Tick?

    @Query("SELECT * FROM ticks WHERE deleted_at IS NULL AND start_ms > :start ORDER BY start_ms ASC LIMIT 1")
    suspend fun getNextActive(start: Long): Tick?

    @Query("SELECT COUNT(*) FROM ticks WHERE deleted_at IS NULL")
    suspend fun activeCount(): Int

    @Query("UPDATE ticks SET deleted_at = :now, updated_at = :now WHERE id = :id")
    suspend fun softDelete(id: String, now: Long = System.currentTimeMillis())

    @Query("UPDATE ticks SET deleted_at = NULL, updated_at = :now WHERE id = :id")
    suspend fun restore(id: String, now: Long = System.currentTimeMillis())

    /**
     * 只改总结，不碰起止/快照 —— 定点更新，绝不可能触发重新定格。
     * 同时把旧总结挤进 summary_prev（SQL 里 RHS 取的都是原值，所以这是原子的一步）。
     */
    @Query("UPDATE ticks SET summary_prev = summary, summary = :summary, updated_at = :now WHERE id = :id")
    suspend fun updateSummary(id: String, summary: String, now: Long = System.currentTimeMillis())

    /** 把 summary_prev 换回来（SQL 的 SET 全部基于原值，所以这一句就是整段交换）。 */
    @Query("UPDATE ticks SET summary = summary_prev, summary_prev = summary, updated_at = :now WHERE id = :id")
    suspend fun swapSummary(id: String, now: Long = System.currentTimeMillis())

    /** 回看单独一列：重复保存直接覆盖，不碰总结、不碰快照。 */
    @Query("UPDATE ticks SET review = :review, updated_at = :now WHERE id = :id")
    suspend fun updateReview(id: String, review: String, now: Long = System.currentTimeMillis())

    @Query("DELETE FROM ticks WHERE id = :id")
    suspend fun permanentlyDelete(id: String)

    @Query("DELETE FROM ticks WHERE id = :id AND deleted_at IS NOT NULL")
    suspend fun clearDeleted(id: String)

    @Query("DELETE FROM ticks WHERE deleted_at IS NOT NULL")
    suspend fun clearAllDeleted()
}
