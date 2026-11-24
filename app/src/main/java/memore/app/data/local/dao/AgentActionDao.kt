package memore.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import memore.app.data.model.AgentAction

/** 动作表（2026-11-15）：一次批准 = 一行，内部的若干命令用 command_ids 串起来。 */
@Dao
interface AgentActionDao {

    /** 幂等：同一个 action id 重复提交不会写第二遍。 */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(action: AgentAction): Long

    @Query("SELECT * FROM agent_actions WHERE id = :id")
    suspend fun byId(id: String): AgentAction?

    @Query("SELECT * FROM agent_actions WHERE status = :status ORDER BY created_at DESC")
    suspend fun byStatus(status: String): List<AgentAction>

    @Query("SELECT * FROM agent_actions ORDER BY created_at DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<AgentAction>

    /** 导出用：全部动作（信的历史）。 */
    @Query("SELECT * FROM agent_actions ORDER BY created_at")
    suspend fun all(): List<AgentAction>

    @Query(
        "UPDATE agent_actions SET status = :status, note = :note, " +
            "executed_at = CASE WHEN :executedAt > 0 THEN :executedAt ELSE executed_at END, " +
            "reverted_at = CASE WHEN :revertedAt > 0 THEN :revertedAt ELSE reverted_at END WHERE id = :id"
    )
    suspend fun finish(
        id: String,
        status: String,
        note: String,
        executedAt: Long = 0L,
        revertedAt: Long = 0L
    )

    @Query("UPDATE agent_actions SET snapshot = :snapshot WHERE id = :id")
    suspend fun setSnapshot(id: String, snapshot: String)

    /**
     * 动作内部步骤的 id 列表（2026-11-15）。
     *
     * 必须先插动作、再插命令、最后回写这一列——否则"相关记录"永远空。
     */
    @Query("UPDATE agent_actions SET command_ids = :ids WHERE id = :id")
    suspend fun setCommandIds(id: String, ids: String)
}
