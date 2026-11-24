package memore.app.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * agent 提交的写命令（2026-11-13）。
 *
 * 为什么要有队列而不是直接写：agent 改的是**用户的记忆**，比读错严重得多。
 * 默认进队列、由用户批准，才谈得上"可逆"和"我知情"。
 *
 * id 由客户端生成（command_id）——网络重试时同一条命令不能执行两遍。
 */
@Entity(tableName = "agent_commands")
data class AgentCommand(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    /** create_record / amend_record / tag_record / assign_origin / relate */
    @ColumnInfo(name = "kind")
    val kind: String,

    /** 命令参数，JSON 字符串（原样存，执行时解析） */
    @ColumnInfo(name = "payload")
    val payload: String,

    /** pending / done / rejected / failed */
    @ColumnInfo(name = "status")
    val status: String = STATUS_PENDING,

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "executed_at")
    val executedAt: Long = 0L,

    /** 失败原因；成功时存一句人话摘要（"已给 3 条记录打上「困惑」"） */
    @ColumnInfo(name = "note")
    val note: String = "",

    /** 同批提交的命令共用一个 batchId（2026-11-13）。空串 = 单条命令。 */
    @ColumnInfo(name = "batch_id")
    val batchId: String = ""
) {
    companion object {
        const val STATUS_PENDING = "pending"
        const val STATUS_DONE = "done"
        const val STATUS_REJECTED = "rejected"
        const val STATUS_FAILED = "failed"

        const val KIND_CREATE = "create_record"
        const val KIND_AMEND = "amend_record"
        const val KIND_TAG = "tag_record"
        const val KIND_ORIGIN = "assign_origin"
        const val KIND_RELATE = "relate"
        const val KIND_TAG_MERGE = "tag_merge"

        /**
         * 删除**空标签**（2026-11-15）。
         * 只用于清测试残留；有记录在用的标签会拒绝——删标签会连带删掉记录上的标记，
         * 那不是"清理"，是改数据。
         */
        const val KIND_TAG_DELETE = "delete_tag"

        /** 协议加/去（2026-11-15）：标签有四种动词，协议之前一个都没有。 */
        const val KIND_PROTOCOL = "protocol_record"

        /**
         * v5 迁移（2026-11-16）：把旧的逐条加密整体解回明文、只保留门闩。
         * payload: { password }（旧密码，用完即弃）
         * **不可撤回**：这是单向迁移，撤回没有意义（内容已经是明文了）。
         */
        const val KIND_LOCK_MIGRATE = "lock_migrate"

        /**
         * 刻度编辑（2026-11-15）：create / rename / bounds / summary / delete / restore。
         * 刻度是"给一段时间命名"，agents 之前完全碰不到。
         */
        const val KIND_TICK = "tick_edit"

        /** 表盘编辑（2026-11-15）：create / rename / bounds / summary / delete / link / unlink。 */
        const val KIND_DIAL = "dial_edit"

        // 2026-11-15 补的五种"减/移"动作：之前只有"加"，擦不掉
        /** 软删除（进回收站），可恢复。 */
        const val KIND_TRASH = "trash_record"

        /** 从回收站恢复（trash 的反向）。 */
        const val KIND_RESTORE = "restore_record"

        /** 加密（需要用户当次给的密码，用完即弃）。 */
        const val KIND_LOCK = "lock_record"

        /** 解密（lock 的反向，同样要当次给密码）。 */
        const val KIND_UNLOCK = "unlock_record"

        /** 断开关联。 */
        const val KIND_UNLINK = "unlink_relation"

        /** 区域成员加/移（zone_id = zone_anchor 时改的是锚点标记）。 */
        const val KIND_ZONE = "zone_member"

        /** 新建自定义区域。 */
        const val KIND_ZONE_CREATE = "create_zone"

        /** 拒绝一条关联边（标 REJECTED，留作负样本，不删除）。2026-11-20 */
        const val KIND_REJECT_REL = "reject_relation"

        /** 改一条关联边的类型（CITE / SUPPLEMENT / CORRECT / LINK）。2026-11-20 */
        const val KIND_RETYPE_REL = "retype_relation"

        /** 把被拒绝的边恢复为 ACTIVE。2026-11-20 */
        const val KIND_RESTORE_REL = "restore_relation"

        // 2026-11-20 批次三：补写作与结构化能力
        const val KIND_DRAFT_CREATE = "draft_create"
        const val KIND_DRAFT_APPEND = "draft_append"
        const val KIND_AI_ANALYZE = "ai_analyze"
        const val KIND_TAG_RENAME = "tag_rename"
        const val KIND_PROTOCOL_MERGE = "protocol_merge"
        const val KIND_ZONE_RENAME = "zone_rename"
        const val KIND_ZONE_DELETE = "zone_delete"

        /** 2026-11-21：别名映射管理（add / update / delete / merge）。 */
        const val KIND_ALIAS = "alias"
        /** 2026-11-21：删除一个概念（只删锚点行）。 */
        const val KIND_CONCEPT_DELETE = "concept_delete"

        val KINDS = setOf(
            KIND_CREATE, KIND_AMEND, KIND_TAG, KIND_ORIGIN, KIND_RELATE, KIND_TAG_MERGE,
            KIND_TAG_DELETE, KIND_PROTOCOL, KIND_TICK, KIND_DIAL, KIND_LOCK_MIGRATE,
            KIND_TRASH, KIND_RESTORE, KIND_LOCK, KIND_UNLOCK,
            KIND_UNLINK, KIND_ZONE, KIND_ZONE_CREATE, KIND_REJECT_REL, KIND_RETYPE_REL, KIND_RESTORE_REL,
            KIND_DRAFT_CREATE, KIND_DRAFT_APPEND, KIND_AI_ANALYZE,
            KIND_TAG_RENAME, KIND_PROTOCOL_MERGE, KIND_ZONE_RENAME, KIND_ZONE_DELETE,
            KIND_ALIAS, KIND_CONCEPT_DELETE
        )
    }
}
