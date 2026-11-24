package memore.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import memore.app.data.model.AiTask
import kotlinx.coroutines.flow.Flow

@Dao
interface AiTaskDao {
    @Insert
    suspend fun insertTask(task: AiTask): Long

    @Update
    suspend fun updateTask(task: AiTask)

    @Query("SELECT COUNT(*) FROM ai_tasks WHERE record_id = :recordId AND status IN ('PENDING', 'PROCESSING', 'WAITING_SERVER')")
    suspend fun countActiveForRecord(recordId: String): Int

    /** 重跑前清掉这条记录的旧失败任务，避免「点一次分析多一条 FAILED」越堆越多。 */
    @Query("DELETE FROM ai_tasks WHERE record_id = :recordId AND status = 'FAILED'")
    suspend fun deleteFailedForRecord(recordId: String)

    @Query("SELECT * FROM ai_tasks WHERE status IN ('PENDING', 'WAITING_SERVER') ORDER BY created_at ASC LIMIT 1")
    suspend fun getNextPendingTask(): AiTask?

    @Query("SELECT * FROM ai_tasks WHERE status = 'PROCESSING'")
    suspend fun getProcessingTasks(): List<AiTask>

    @Query("SELECT COUNT(*) FROM ai_tasks WHERE status IN ('PENDING', 'PROCESSING', 'WAITING_SERVER')")
    suspend fun getPendingCount(): Int

    @Query("SELECT * FROM ai_tasks WHERE status IN ('PENDING', 'PROCESSING', 'WAITING_SERVER') ORDER BY created_at ASC")
    suspend fun getActiveTasks(): List<AiTask>

    /**
     * 活动任务的**可观察**版本 —— 队列弹层与徽标必须用它，不能用一次性查询。
     * 否则 worker 在后台把任务改成 SUCCESS / FAILED 时界面永远不知道，
     * 数量不减、"进行中"不消失（除非手动进队列刷新）。
     */
    @Query("SELECT * FROM ai_tasks WHERE status IN ('PENDING', 'PROCESSING', 'WAITING_SERVER') ORDER BY created_at ASC")
    fun observeActiveTasks(): Flow<List<AiTask>>

    @Query("DELETE FROM ai_tasks WHERE record_id = :recordId")
    suspend fun deleteByRecordId(recordId: String)

    @Query("DELETE FROM ai_tasks WHERE id = :taskId")
    suspend fun deleteTask(taskId: Int)
}
