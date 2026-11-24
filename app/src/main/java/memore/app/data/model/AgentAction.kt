package memore.app.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 一次「动作」（2026-11-15，agent 连接 v2 的地基）。
 *
 * 为什么不再以"命令"为单位：命令是**实现细节**（打标签/改来源/建关联各是一条），
 * 而人做的决策是"把这几条归到一起"这种**成组的动作**。
 * 批准、回执、撤回都该以动作为单位 —— 一条命令一次批准会让人放弃使用。
 *
 * 动作内部仍然是若干条 [AgentCommand]（保留可追溯"这一步做了什么"），
 * 两者是 1 : N，用 [commandIds] 串起来。
 */
@Entity(tableName = "agent_actions")
data class AgentAction(
    @PrimaryKey val id: String,

    /** 谁提的（设备 / 会话 / 工具版本）。只做记录，不是权限。 */
    @ColumnInfo(name = "actor") val actor: String = "",

    /** 一句话说清这个动作要做什么："把 5 条归到「戒断」"。 */
    @ColumnInfo(name = "title") val title: String = "",

    /** 内部步骤的 command id（JSON 数组），便于追查。 */
    @ColumnInfo(name = "command_ids") val commandIds: String = "[]",

    /** proposed / applied / reverted / rejected / failed */
    @ColumnInfo(name = "status") val status: String = STATUS_PROPOSED,

    /** 是否需要二次确认（高危动词）。 */
    @ColumnInfo(name = "requires_confirm") val requiresConfirm: Boolean = false,

    /** 执行前的受影响行（JSON）。撤回要靠它 —— 动作级快照，不是行级版本。 */
    @ColumnInfo(name = "snapshot") val snapshot: String = "",

    /** 人话结果或失败原因。 */
    @ColumnInfo(name = "note") val note: String = "",

    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "executed_at") val executedAt: Long = 0L,
    @ColumnInfo(name = "reverted_at") val revertedAt: Long = 0L
) {
    companion object {
        const val STATUS_PROPOSED = "proposed"
        const val STATUS_APPLIED = "applied"
        const val STATUS_REVERTED = "reverted"
        const val STATUS_REJECTED = "rejected"
        const val STATUS_FAILED = "failed"
    }
}
