package memore.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import memore.app.data.model.AgentCommand

/**
 * agent 命令队列（2026-11-13）。
 *
 * 全部是简单读写：真正的业务逻辑在 AgentCommandBus 里，走 repository 执行。
 */
@Dao
interface AgentCommandDao {

    /** 幂等：同一个 command_id 重复提交不会写第二遍。 */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(command: AgentCommand): Long

    @Query("SELECT * FROM agent_commands WHERE id = :id")
    suspend fun byId(id: String): AgentCommand?

    @Query("SELECT * FROM agent_commands WHERE batch_id = :batchId AND status = 'pending' ORDER BY created_at")
    suspend fun pendingOfBatch(batchId: String): List<AgentCommand>

    @Query("SELECT * FROM agent_commands WHERE status = :status ORDER BY created_at")
    suspend fun byStatus(status: String): List<AgentCommand>

    @Query("SELECT COUNT(*) FROM agent_commands WHERE status = 'pending'")
    suspend fun pendingCount(): Int

    /** 导出用：全部命令。 */
    @Query("SELECT * FROM agent_commands ORDER BY created_at")
    suspend fun all(): List<AgentCommand>

    @Query("UPDATE agent_commands SET status = :status, executed_at = :at, note = :note WHERE id = :id")
    suspend fun finish(id: String, status: String, note: String, at: Long = System.currentTimeMillis())

    @Query("DELETE FROM agent_commands WHERE status IN ('done','rejected') AND executed_at < :before")
    suspend fun purgeOld(before: Long)
}
