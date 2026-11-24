package memore.app.data.model

import androidx.compose.runtime.Immutable
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "records",
    indices = [
        Index(value = ["created_at"]),
        Index(value = ["is_deleted"]),
        Index(value = ["is_anchored", "is_deleted", "updated_at"]),
        Index(value = ["correction_of_id"]),
        Index(value = ["is_locked"]),
        Index(value = ["is_deleted", "correction_of_id", "created_at"])
    ]
)
@Immutable
data class Record(
    @PrimaryKey
    val id: String,

    @ColumnInfo(name = "type")
    val type: RecordType,

    @ColumnInfo(name = "content")
    val content: String,

    @Deprecated("Migrated to analysis.summary. Will be removed in V2.0")
    @ColumnInfo(name = "dialogue_ai_content")
    val dialogueAiContent: String = "",

    /** 对话录里 AI 回复的原文；旧数据为空时由 content 启发式切分。 */
    @ColumnInfo(name = "dialogue_ai_reply")
    val dialogueAiReply: String = "",

    @ColumnInfo(name = "ai_status")
    val aiStatus: String = "NONE",

    @Deprecated("Migrated to analysis.emotion. Will be removed in V2.0")
    @ColumnInfo(name = "emotional_tags")
    val emotionalTags: String = "[]",

    @ColumnInfo(name = "notes")
    val notes: String = "",

    @ColumnInfo(name = "analysis")
    val analysis: AnalysisData? = null,

    /** 上一次分析时输入的指纹（正文+增修）。和当前内容对不上 = STALE。 */
    @ColumnInfo(name = "analysis_input_hash", defaultValue = "")
    val analysisInputHash: String = "",

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "is_anchored")
    val isAnchored: Boolean = false,

    @ColumnInfo(name = "is_deleted")
    val isDeleted: Boolean = false,

    @ColumnInfo(name = "attachments_count")
    val attachmentsCount: Int = 0,

    @ColumnInfo(name = "correction_of_id")
    val correctionOfId: String? = null,

    @ColumnInfo(name = "is_locked")
    val isLocked: Boolean = false,

    @ColumnInfo(name = "encrypted_data")
    val encryptedData: String = "",

    @ColumnInfo(name = "iv")
    val iv: String = "",

    @ColumnInfo(name = "auth_tag")
    val authTag: String = "",

    /**
     * 记录加密方案版本（随 EncryptionConfig.encVersion 写入）：
     * 1 = 旧格式；2 = 新格式。仅用于未来算法/格式升级时区分，**解密逻辑不依赖它**。
     *
     * v3（主密钥 + 密码/答案包裹）之后连"换密码"都不再换内容密钥，所以这个字段彻底不参与选钥匙，
     * 只作为历史留档；当前锁定时仍写 2，无需为它做任何迁移。
     */
    @ColumnInfo(name = "enc_version")
    val encVersion: Int = 1,

    /** 与当前记录存在双向关联的记录 id 列表（JSON 数组字符串）。 */
    @ColumnInfo(name = "linked_ids")
    val linkedIds: String = "",

    /**
     * **转录来源**：这条记录是从哪一本草稿的哪一块「转录」来的（null = 不是转录来的）。
     *
     * 为什么要留住它：转录出来的记录在时间线上必须能**回到那本草稿** ——
     * 用户 2026-10-11：「根据这种转录做的记录，在附件和总结图标那一行左边，加一个类似关联的 SVG，
     * 点击之后去到那篇草稿」。id 只在本机有意义，**不随导出包走**（导入时草稿 id 会重建）。
     */
    @ColumnInfo(name = "source_draft_id")
    val sourceDraftId: Long? = null,

    /** 转录来源的那一块（用来落到草稿里精确的一行；块可能已被删，删了就只回本）。 */
    @ColumnInfo(name = "source_block_id")
    val sourceBlockId: Long? = null,

    /**
     * 来源（生产者）：指向 [memore.app.data.model.Origin]。null = 未标。
     *
     * 注意和上面的 `source_draft_id` 不是一回事：那个是"从哪块草稿转录来的"，
     * 这个是"这条记录是谁生产的"（原创 / 对话 / 转录 / 转录（AI）…）。
     */
    @ColumnInfo(name = "origin_id")
    val originId: String? = null
)
