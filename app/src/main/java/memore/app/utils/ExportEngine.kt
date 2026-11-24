package memore.app.utils

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.google.gson.GsonBuilder
import memore.app.utils.EncryptionManager
import memore.app.utils.GsonInstance
import memore.app.utils.MemoryNodeNormalizer
import memore.app.data.model.AliasGroup
import memore.app.data.model.AnalysisData
import memore.app.data.model.EmotionTagEntry
import memore.app.data.model.EmotionStats
import memore.app.data.repository.AliasMappingRepository
import memore.app.data.repository.DialRepository
import memore.app.data.model.EmotionalProfileItem
import memore.app.data.model.Record
import memore.app.data.model.RedactionGroup
import memore.app.data.repository.ProtocolRepository
import memore.app.data.repository.RecordRepository
import memore.app.data.repository.TagRepository
import memore.app.data.repository.TickRepository
import memore.app.data.repository.TagGroupRepository
import kotlin.math.max
import kotlin.math.pow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

enum class ExportFormat {
    JSON_ONLY,
    FULL_PACKAGE,

    /**
     * 给 AI 阅读的 Markdown（2026-11-09）：
     * **单向**——只用于把记忆喂给另一个模型/人读，不能导回数据库。
     * 与 JSON 的区别：增修就地合并进父记录、情绪与关联紧贴记录、去掉 id/加密/设置等机器字段。
     */
    AI_MARKDOWN
}

data class ExportProfileMeta(
    val algorithm: String = "time_decayed_weighted_average",
    val records_used: Int,
    val computed_at: Long = System.currentTimeMillis()
)

data class ExportData(
    val system_instruction: String = "",
    val version: String,
    val export_time: String,
    val import_enabled: Boolean = true,
    val emotional_profile: List<EmotionalProfileItem> = emptyList(),
    val profile_meta: ExportProfileMeta? = null,
    val rejected_merges: List<ExportedRejectedMerge>? = null,
    val alias_groups: List<AliasGroup>? = null,
    val prompts: Map<String, String> = emptyMap(),
    val count: Int,
    val records: List<ExportRecord>,
    val encryption_config: ExportEncryptionConfig? = null,
    val ticks: List<ExportedTick> = emptyList(),
    val tick_meta: TickExportMeta? = null,
    /** 表盘（一段时期的框，包络刻度）：空则不写这个键。 */
    val dials: List<ExportedDial>? = null,
    val node_hierarchy: Map<String, List<String>>? = null,
    val tag_hierarchy: Map<String, List<String>>? = null,
    /**
     * 真草稿（多本 + 块）。**默认不导出**（用户 2026-10-11：「这个东西默认关闭」）——
     * 只有显式勾了「草稿」或打开「整体备份」时才写；为 null 时 Gson 不写这个键，输出与旧版逐字节相同。
     */
    val drafts: List<ExportedDraft>? = null,
    // ===== 以下仅为「整体备份」块：只在 ZIP + 整体备份开关打开时写入。
    // 普通导出（纯 JSON / 普通 ZIP）这些字段全为 null，Gson 不写 → 输出与旧版逐字节相同。 =====
    val backup_meta: BackupMeta? = null,
    val versions: List<ExportedVersion>? = null,
    val trash: List<ExportRecord>? = null,
    val album_state: ExportedAlbumState? = null,
    val sounds: Map<String, List<String>>? = null,
    val settings: Map<String, Map<String, PrefEntry>>? = null,
    val attachments_index: Map<String, List<ExportedAttachment>>? = null,
    /**
     * 脱敏映射组（整体备份随包）。
     * 只在整体备份里出现 —— 而整体备份强制不脱敏，所以"映射表"永远不会和"被脱敏的正文"同包，
     * 不会出现"哈希模式下凭映射表反推原文"的自破。
     */
    val redaction_groups: List<RedactionGroup>? = null,
    // ===== 2026-11-16 v2 补洞：以前"全量导出"漏掉的三样 + 包校验清单 =====
    /** 区域（含成员）。以前完全不导出，导入后区域功能等于从零开始。 */
    val zones: List<ExportedZone>? = null,
    /** 来源表本身（名字/内置标记/权重）。以前只导 origin_id，导入后指向不存在的来源。 */
    val origins: List<ExportedOrigin>? = null,
    /** 概念分类（大类）。以前只导 node_hierarchy，分类归属丢失。 */
    val concept_categories: List<ExportedConceptCategory>? = null,
    /** 包清单：schema 版本 + 条目数 + 各块的 sha256（可校验完整性）。 */
    val manifest: ExportManifest? = null,
    // ===== 2026-11-20：新结构补洞（只在整体备份里写） =====
    val anchors: List<ExportedAnchor>? = null,
    val analysis_versions: List<ExportedAnalysisVersion>? = null,
    val agent_actions: List<ExportedAgentAction>? = null,
    val agent_commands: List<ExportedAgentCommand>? = null
)

/** 区域（2026-11-16）：id/名字/内置/条件 JSON/成员 id 列表。 */
data class ExportedZone(
    val id: String,
    val name: String,
    val builtin: Boolean = false,
    val condition: String = "",
    val member_ids: List<String> = emptyList()
)

/** 来源（2026-11-16）：名字、内置、三个权重、排序、创建时间。
 *  只导 name+w_link 会让导入后的排序与权重全部回到默认 —— 真机上表现为"来源系统完全变了"。 */
data class ExportedOrigin(
    val id: String,
    val name: String,
    val builtin: Boolean = false,
    val w_link: Float = 1f,
    val w_cluster: Float = 1f,
    val w_stats: Float = 1f,
    /** 关联准入阈值（2026-11-17 起进包；旧包没有就回落到 0.70）。 */
    val link_threshold: Float = 0.70f,
    val sort_order: Int = 0,
    val created_at: Long = 0L
)

/** 概念分类（2026-11-16）。 */
data class ExportedConceptCategory(
    val id: Long,
    val name: String
)

/** 包清单（2026-11-16）：用来判断"包是不是完整的"。 */
data class ExportManifest(
    val schema_version: Int = 2,
    val app_version: String = "",
    val export_time: String = "",
    val record_count: Int = 0,
    /** 各块的 sha256（块名 → 摘要），空块不写。 */
    val blocks: Map<String, String> = emptyMap()
)

/**
 * 记录关系（有向 + 带类型）的**内联**表示。
 *
 * 刻意**不做成顶层数组**：外部 AI 是逐条读记录的，关系如果只躺在文件末尾的一张表里，
 * 它很可能压根不去交叉引用 —— 而这个字段的价值恰恰在于"读到某条时立刻知道
 * 它更正了谁、又被谁更正了"。所以两个方向都挂在记录自己身上：
 *  - [ExportedRelationOut]：本条「type」→ 目标（关联）
 *  - [ExportedRelationIn]：来源「type」→ 本条（被关联）
 * 一条边会同时出现在两端的记录里（A 的 out 与 B 的 in）—— 这是**有意**的：
 * 单看任何一侧都能用。导入端靠 (from,to,type) 唯一索引自动去重。
 *
 * type 取 `CITE`（引用）/ `SUPPLEMENT`（补充）/ `CORRECT`（更正，即后者推翻或更正前者）。
 */
data class ExportedRelationOut(
    val to_id: String,
    val type: String,
    /** 关联边的一句话解释（只在 type = LINK 时有值）。 */
    val why: String? = null,
    // 2026-11-20：证据与生命周期（旧包没有这些键，导入端按默认值处理）
    val quote_a: String? = null,
    val quote_b: String? = null,
    val source: String? = null,
    val valid_from: Long? = null,
    val valid_to: Long? = null
)

data class ExportedRelationIn(
    val from_id: String,
    val type: String,
    val why: String? = null,
    val quote_a: String? = null,
    val quote_b: String? = null,
    val source: String? = null,
    val valid_from: Long? = null,
    val valid_to: Long? = null
)

/** 锚点（2026-11-20）：人/作品 + 原句。 */
data class ExportedAnchor(
    val record_id: String,
    val name: String,
    val kind: String,
    val quote: String = "",
    val created_at: Long = 0L
)

/** 一次分析的历史快照（2026-11-20）。 */
data class ExportedAnalysisVersion(
    val record_id: String,
    val model: String = "",
    val prompt_version: String = "",
    val input_hash: String = "",
    val summary: String = "",
    val emotion_json: String = "[]",
    val status: String = "OK",
    val reason: String = "",
    val created_at: Long = 0L
)

/** agent 动作（信的历史）。 */
data class ExportedAgentAction(
    val id: String,
    val actor: String = "",
    val title: String = "",
    val command_ids: String = "[]",
    val status: String = "",
    val requires_confirm: Boolean = false,
    val note: String = "",
    val created_at: Long = 0L,
    val executed_at: Long = 0L,
    val reverted_at: Long = 0L
)

data class ExportedAgentCommand(
    val id: String,
    val kind: String,
    val payload: String = "{}",
    val status: String = "",
    val note: String = "",
    val created_at: Long = 0L,
    val executed_at: Long = 0L,
    val batch_id: String = ""
)

// ================= 整体备份（ZIP · 可整机还原）专用块 =================

/**
 * 整体备份自描述块：告诉导入端"这是什么包、来自哪一版、能还原什么"。
 * [db_version] 用于导入前的 schema 校验（旧包还原到新库时给出明确提示）。
 */
data class BackupMeta(
    val kind: String = "full",
    val app_version: String = "",
    val db_version: Int = 0,
    val includes: List<String> = emptyList(),
    val record_count: Int = 0,
    val trash_count: Int = 0,
    val version_count: Int = 0,
    /** 随包的草稿本数（0 = 这个包里没有草稿块）。 */
    val draft_count: Int = 0,
    val created_at: String = ""
)

/**
 * 一本草稿（导出形状）。**块嵌在本里**（不是两张平行表）——
 * 读包的人一眼看到"这本有什么"，导入端重建 id 也只需要一处映射。
 * 空本（一个块都没有）不导出：本机本来就会把空本收掉，包里的空本没有保留价值。
 */
data class ExportedDraft(
    val name: String = "",
    @com.google.gson.annotations.SerializedName("created_at")
    val createdAt: Long = 0,
    @com.google.gson.annotations.SerializedName("updated_at")
    val updatedAt: Long = 0,
    val archived: Boolean = false,
    val pinned: Boolean = false,
    val blocks: List<ExportedDraftBlock> = emptyList()
)

/** 草稿的一块：kind 见 `DraftKind`（MINE / AI / QA / DISCUSS）；顺序 = 数组顺序。 */
data class ExportedDraftBlock(
    val kind: String = "MINE",
    val content: String = "",
    @com.google.gson.annotations.SerializedName("created_at")
    val createdAt: Long = 0,
    @com.google.gson.annotations.SerializedName("updated_at")
    val updatedAt: Long? = null
)

/** 记录的历史版本快照（改前正文，本机只留最近 5 条）。 */
data class ExportedVersion(
    val record_id: String,
    val content: String,
    val created_at: Long
)

/** 图文集组织状态：每张图的选中项 + 置顶（注脚随记录走，不在这里重复）。 */
data class ExportedAlbumState(
    val selected: Map<String, Int> = emptyMap(),
    val pinned: List<String> = emptyList()
)

/**
 * 附件清单项。ZIP 里只有文件本体（`attachments/<记录id>/<文件名>`），
 * 类型/大小/时长/添加时间靠这份清单还原。
 */
data class ExportedAttachment(
    val file_name: String,
    val type: String,
    val file_size: Long,
    val duration: Long? = null,
    val created_at: Long
)

/**
 * 一条 SharedPreferences 记录。
 * 必须显式带 [type]：Gson 反序列化会把 int/long/float 一律读成 Double，
 * 不记类型就无法把值写回原来的偏好类型。
 */
data class PrefEntry(
    val type: String,
    val value: Any? = null
)

/** 音乐库导出项：元数据 + 相对 zip 路径（音乐文件本体打进 ZIP，供导入还原为可播放文件）。 */
/** 秒针 · 刻度导出：可命名时间区间 + 整体权重（W1 纯时间衰减），可选携带定格切片内容。 */
data class ExportedTick(
    val id: String,
    val name: String,
    val start: String,
    val end: String?,
    val weight: Double,
    val record_count: Int,
    /** 刻度总结（后记）：用户为这一段写下的长文。空则不写。 */
    val summary: String? = null,
    /** 刻度回看（2026-11-22 起独立保存；与 summary 分开）。空则不写。 */
    val review: String? = null,
    val emotion: List<ExportedSliceEntry>? = null,
    val nodes: List<ExportedSliceEntry>? = null
)

/** 切片中的一项（定格情绪/节点）：名称 + 衰减权重 + 出现次数。 */
data class ExportedSliceEntry(
    val name: String,
    val weight: Double,
    val count: Int
)

/** 刻度阅读规则/衰减说明，供外部 AI 正确理解 ticks。 */
data class TickExportMeta(
    val rule: String,
    val weight_decay: String,
    val half_life: String,
    val now: String
)

/**
 * 表盘导出：**一段时期**的框（2026-10-19 起的「表盘」）。
 *
 * 和刻度的区别要写清楚给外部 AI：表盘**没有权重**、不参与记录归属；
 * 它只是"这一段时间里有哪些段（[tick_ids]）、这一段的自我长什么样（[emotion] / [nodes]）、
 * 以及用户为这一段写的 [summary]"。
 * 一个刻度可以被多个表盘包着（时期天然重叠），所以关系写在表盘这边、不写进 ticks。
 */
data class ExportedDial(
    val name: String,
    val start: String,
    val end: String?,
    val summary: String? = null,
    /** 这段时期包着的刻度 id（对应 ticks[].id）。表盘只是一层"框"，没有权重、没有切片。 */
    val tick_ids: List<String> = emptyList()
)

/**
 * 随包导出的加密配置块。
 *
 * ⚠️ 字段类型一律**可空**，这不是偷懒，而是如实描述 JSON 反序列化的事实：
 * Gson 用 Unsafe 绕过 Kotlin 构造器，**JSON 里缺键时字段就是 null**，哪怕声明成非空 `String`
 * 也一样 —— 于是 `questionSalt = encConf.question_salt`（非空参数）会当场抛 NPE。
 * 早期导出的包确实没有 `question_salt` 这个键（它是后来才加的）→ 导入配置整块失败、
 * 而且异常被 catch 吞掉、悄无声息 → 新设备上"有密文却没有配置"。
 * 声明成可空后，编译器会强制每个使用点写 `?: ""`，这类事故就不可能再发生。
 */
data class ExportEncryptionConfig(
    val algorithm: String = "PBKDF2WithHmacSHA256",
    val key_iterations: Int = 50000,
    val cipher: String = "AES-256-GCM",
    val password_hash: String? = null,
    val password_salt: String? = null,
    val key_salt: String? = null,
    val question: String? = null,
    val question_hash: String? = null,
    val answer_hash: String? = null,
    val answer_salt: String? = null,
    val wrapped_key: String? = null,
    val enc_version: Int = 1,
    val question_salt: String? = null,
    /**
     * v3：**用密码包裹的主密钥**。必须随包走 —— 否则 v3 配置导入新设备后
     * `encVersion>=3` 却没有 `wrappedKeyPw`，会直接"取不回主密钥"把用户锁死。
     * 声明在末尾 + 可空（空则不写，Gson 默认省略 null）→ v2 包的字节输出不变。
     */
    val wrapped_key_pw: String? = null
)

data class ExportedRejectedMerge(
    val keep: String,
    val remove: String,
    val rejected_at: Long
)

data class ExportMeta(
    val model: String = "",
    val created_at: String = "",
    val analysis_version: String = "1.0",
    val prompt_version: String = "1"
)

data class ExportedAlbumFootnote(
    val content: String,
    val created_at: Long,
    val updated_at: Long? = null
)

data class ExportRecord(
    val id: String,
    val type: String,
    val content: String,
    /** 来源 id（2026-11-12 补）：旧包没有这个键 → 导入方按「未标」处理。 */
    val origin_id: String? = null,
    val dialogue_ai_content: String? = null,
    val dialogue_ai_reply: String? = null,
    val notes: String? = null,
    val tags: List<String>,
    val protocols: List<String>? = null,
    val timestamp: String,
    val starred: Boolean,
    val deleted: Boolean,
    val correction_of_id: String? = null,
    val linked_ids: List<String>? = null,
    /** 关联：本条「type」→ 目标（引用/补充/更正）。空则不写。 */
    val relations_out: List<ExportedRelationOut>? = null,
    /** 被关联：来源「type」→ 本条。空则不写。 */
    val relations_in: List<ExportedRelationIn>? = null,
    val continuity_links: List<String>? = null,
    val ai_status: String? = null,
    val emotional_tags: List<EmotionTagEntry>? = null,
    val memory_nodes: List<String>? = null,
    val meta: ExportMeta? = null,
    val relations: List<String>? = null,
    val attachment_count: Int? = null,
    val analysis: AnalysisData? = null,
    val is_locked: Boolean = false,
    val was_locked: Boolean = false,
    val encrypted_data: String? = null,
    val iv: String? = null,
    val auth_tag: String? = null,
    val album_footnotes: List<ExportedAlbumFootnote>? = null,
    /** 仅整体备份写：修改时间（普通导出不写，保持 JSON 输出不变）。 */
    val updated_at: String? = null,
    /** 仅整体备份写：该记录的加密版本号（将来换算法时用于还原判断）。 */
    val enc_version: Int? = null
)

/**
 * 批量导出（"即时向 AI 补充信息"）专用精简记录：只含正文与附属语义，扁平去重。
 * 不含：加密字段、meta、附件计数、注脚、跨阶段标记、starred / deleted / ai_status 等应用状态。
 * 与主导出（ExportRecord）完全独立，改这里不影响「导出记忆包」。
 */
data class ExportRecordLite(
    val id: String,
    val type: String,
    val timestamp: String,
    val content: String,
    val dialogue_ai_reply: String? = null,
    val notes: String? = null,
    val tags: List<String> = emptyList(),
    val protocols: List<String>? = null,
    val linked_ids: List<String>? = null,
    /** 关联：本条「type」→ 目标（引用/补充/更正）。空则不写。 */
    val relations_out: List<ExportedRelationOut>? = null,
    /** 被关联：来源「type」→ 本条。空则不写。 */
    val relations_in: List<ExportedRelationIn>? = null,
    val correction_of_id: String? = null,
    val summary: String? = null,
    val memory_nodes: List<String>? = null,
    val emotion: List<EmotionTagEntry>? = null
)

object ExportEngine {

    // ================= 给 AI 阅读的 Markdown（单向，不可导入） =================

    /**
     * 把导出的记录渲染成一份 Markdown。
     *
     * 与 JSON 的差别（都是为"另一个模型能读懂"服务的）：
     *  - **增修就地合并**进父记录（JSON 里它们是 correction_of_id 指向父级的两条记录，AI 常常忽略）
     *  - 情绪标签连同**依据原文**贴在记录下面
     *  - 关联边连同**引用句**贴在记录下面，并标出目标日期
     *  - 去掉 id / 加密字段 / 应用状态等机器字段
     */
    fun buildAiMarkdown(data: ExportData): String {
        val byId = data.records.associateBy { it.id }
        val children = data.records.filter { !it.correction_of_id.isNullOrBlank() }
            .groupBy { it.correction_of_id!! }
        // 只渲染"父记录 + 独立记录"；增修作为父记录的一部分出现，不单独成段
        val parents = data.records.filter { it.correction_of_id.isNullOrBlank() }
            .sortedBy { it.timestamp }

        val sb = StringBuilder()
        sb.append("# 记忆记录\n\n")
        sb.append("共 ").append(parents.size).append(" 条")
        if (parents.isNotEmpty()) {
            sb.append("，").append(parents.first().timestamp).append(" ~ ").append(parents.last().timestamp)
        }
        sb.append("。\n\n")
        sb.append("> 这是一份只读导出，用于阅读与分析，不可导回。\n\n")

        parents.forEach { r ->
            sb.append("## ").append(r.timestamp).append(" · ").append(r.type).append('\n')
            val body = r.content.trim()
            sb.append(if (body.isEmpty()) "（无正文）" else body).append("\n\n")

            children[r.id]?.sortedBy { it.timestamp }?.forEach { a ->
                sb.append("**【增修 · ").append(a.timestamp).append("】**\n")
                sb.append(a.content.trim()).append("\n\n")
            }

            val emo = r.analysis?.emotion?.takeIf { it.isNotEmpty() } ?: r.emotional_tags?.takeIf { it.isNotEmpty() }
            if (!emo.isNullOrEmpty()) {
                sb.append("情绪：")
                sb.append(emo.joinToString(" · ") { e ->
                    val w = e.weight?.let { v -> " " + (kotlin.math.round(v * 100) / 100.0) } ?: ""
                    val why = e.why?.takeIf { it.isNotBlank() }?.let { "「$it」" } ?: ""
                    e.name + w + why
                })
                sb.append("\n\n")
            }

            val outs = r.relations_out?.takeIf { it.isNotEmpty() }
            if (!outs.isNullOrEmpty()) {
                outs.forEach { o ->
                    val target = byId[o.to_id]
                    sb.append("关联 → ").append(target?.timestamp ?: o.to_id)
                    sb.append("：").append(o.why.orEmpty()).append('\n')
                }
                sb.append('\n')
            }
            val ins = r.relations_in?.takeIf { it.isNotEmpty() }
            if (!ins.isNullOrEmpty()) {
                ins.forEach { i ->
                    val from = byId[i.from_id]
                    sb.append("关联 ← ").append(from?.timestamp ?: i.from_id)
                    sb.append("：").append(i.why.orEmpty()).append('\n')
                }
                sb.append('\n')
            }

            val extra = buildList {
                if (r.tags.isNotEmpty()) add("标签 " + r.tags.joinToString(" ") { "#$it" })
                r.protocols?.takeIf { it.isNotEmpty() }?.let { add("协议 " + it.joinToString(" ")) }
                if (r.is_locked) add("（加密记录，正文未导出）")
            }
            if (extra.isNotEmpty()) sb.append(extra.joinToString(" · ")).append("\n\n")
        }
        return sb.toString()
    }

    /** 阶段权重：半衰期 6 个月、下限 0.05（与刻度 computeWeight 一致）。 */
    private const val STAGE_HALF_LIFE_MS = 6L * 30L * 24L * 3600L * 1000L
    private const val STAGE_WEIGHT_FLOOR = 0.05

    private val gson = GsonBuilder().setPrettyPrinting().create()

    /** 紧凑输出用（2026-11-09）：去掉缩进，实测体积少 24%。 */
    private val gsonCompact = GsonBuilder().create()

    // ---- 进度上报（纯观察，不参与任何业务判断） ----

    private val _progress = kotlinx.coroutines.flow.MutableStateFlow<OpProgress?>(null)

    /** 当前导出进度；为 null 表示没有导出在跑。UI 直接 collectAsState 即可。 */
    val progress: kotlinx.coroutines.flow.StateFlow<OpProgress?> = _progress

    private fun reportProgress(phase: String, done: Int = 0, total: Int = 0, detail: String? = null) {
        _progress.value = OpProgress(phase, done, total, detail)
    }

    private fun clearProgress() { _progress.value = null }

    /**
     * 顶层提示语末尾的固定强制规则：写死在代码里、不可经 UI 更改，读取导出数据的外部 AI 必须遵守。
     * 在组装 ExportData 时拼到 system_instruction 末尾（即使 system_instruction 为空也独立带上）。
     */
    private val SYSTEM_RULES = """
【强制规则 · 读取导出数据时必须遵守（不可更改）】
规则 R-01（引用链追溯）：records 之外另有 relations 边表，每条边形如 {from_id, to_id, type}，type 取 CITE（from 引用了 to）/ SUPPLEMENT（from 补充了 to）/ CORRECT（from 更正或推翻了 to）。这是**有向**关系：from 是发起方、to 是被指向方。追溯**不限层数** —— 按需继续，直到没有新边或信息已经足够；不要人为截断。旧的 linked_ids 字段是无类型的双向关联，仅作兼容参考。

规则 R-02（关系是骨架，不是附注）：每条记录上的 relations_out（关联）与 relations_in（被关联）是判断"**哪一版才是你现在相信的**"的首要依据，优先级**高于正文本身**。遇到 CORRECT（更正）：**被更正的那条不再代表当前事实**，引用它时必须标注它已被哪条更正；遇到 SUPPLEMENT（补充）：两条应当合起来读，不要只看其中一条；遇到 CITE（引用）：说明该记录被当作依据使用。不要把这些关系罗列成附注 —— 要让它们改变你对内容的理解。 规则 R-03（LINK 是软线索）：type = `LINK` 的边是系统用语义相似度 + 模型判定挑出来的"像同一件事"，带 `why` 一句话解释。它**不是**人工确认的关系，可信度低于 CITE/SUPPLEMENT/CORRECT：可以用来发现"同一件事反复出现"，但**不要**据此推断哪一版是最终结论。

规则 R-03（泛化权重处理）：处理任何数据时，若存在 ticks、tick_meta、weight、priority、importance、usage_count 或任何其他带优先级/出现频率的属性字段，必须检查所有带优先级字段，并作为分析框架的起点；若语义判断与权重冲突，则标注差异但保留权重。
规则 R-04（重要性以内容为准，权重≠重要性）：weight、usage_count 等只反映出现频率或时间衰减，**不代表语义重要性**。一个只出现一两次但分量极重的概念，可能与高频节点同等或更重要。判断重要性必须以**记录正文的语义/上下文**为准，不得因权重低而忽略或轻判低频但重要的内容。
""".trimIndent()

    private fun withSystemRules(base: String): String =
        listOf(base, SYSTEM_RULES).filter { it.isNotBlank() }.joinToString("\n\n")

    /** 节点分类层级：分类名 → 子项节点名（为空则省略）。 */
    /** 标签组层级：组名 → 成员标签名（为空则省略）。 */
    private suspend fun buildTagHierarchy(tagGroupRepository: TagGroupRepository?): Map<String, List<String>>? {
        if (tagGroupRepository == null) return null
        return try {
            val hierarchy = linkedMapOf<String, List<String>>()
            tagGroupRepository.getGroups().forEach { g ->
                val members = tagGroupRepository.getMembers(g.id)
                if (members.isNotEmpty()) hierarchy[g.name] = members.map { it.name }
            }
            hierarchy.takeIf { it.isNotEmpty() }
        } catch (e: Exception) { LogUtil.e("ExportEngine", "tagHierarchy", e); null }
    }

    /** 构建加密配置导出块；enabled=true 且有 dao 时才产出。供 export() 与 exportSelected() 共用。 */
    private suspend fun buildEncryptionConfig(encryptionConfigDao: memore.app.data.local.dao.EncryptionConfigDao?): ExportEncryptionConfig? {
        return encryptionConfigDao?.get()?.takeIf { it.enabled }?.let { ec ->
            ExportEncryptionConfig(
                key_iterations = ec.iterations,
                password_hash = ec.passwordHash,
                password_salt = ec.passwordSalt,
                key_salt = ec.keySalt,
                question = ec.question,
                question_hash = ec.questionHash,
                answer_hash = ec.answerHash,
                answer_salt = ec.answerSalt,
                wrapped_key = ec.wrappedKey.ifBlank { null },
                enc_version = ec.encVersion,
                question_salt = ec.questionSalt,
                wrapped_key_pw = ec.wrappedKeyPw.ifBlank { null }
            )
        }
    }

    suspend fun export(
        context: Context,
        recordRepo: RecordRepository,
        tagRepo: TagRepository,
        format: ExportFormat = ExportFormat.JSON_ONLY,
        protocolRepo: ProtocolRepository? = null,
        protocolIds: Set<Int>? = null,
        aliasMappingRepo: AliasMappingRepository? = null,
        systemInstruction: String = "",
        autoInsert: Boolean = false,
        // ===== 按时间导出 =====
        startTs: Long? = null,
        endTs: Long? = null,
        // ===== 按刻度筛选（与协议为交集；选中刻度时忽略时间窗） =====
        tickIds: Set<String>? = null,
        // ===== 数据开关（默认全开） =====
        includeEmotionalProfile: Boolean = true,
        includeRejectedMerges: Boolean = false,
        includeAliasGroups: Boolean = false,
        includePrompts: Boolean = false,
        includeVoiceCache: Boolean = false,
        includeSlices: Boolean = false,
        /** JSON 紧凑输出（去缩进，体积约少 24%）；默认关 = 与旧版逐字节一致。 */
        compactJson: Boolean = false,
        encryptionConfigDao: memore.app.data.local.dao.EncryptionConfigDao? = null,
        /** 整体备份直接读库用（锚点 / 分析版本 / agent 历史）。普通导出可为 null。 */
        database: memore.app.data.local.AppDatabase? = null,
        decryptLocked: Boolean = false,
        /**
         * v5（2026-11-16）：普通导出是否包含锁定记录。
         * 默认 **false**（排除）；整体备份不看这个开关——备份必须全量。
         */
        includeLocked: Boolean = false,
        redact: Boolean = false,
        redactionGroups: List<RedactionGroup> = emptyList(),
        redactionMode: String = RedactionEngine.MODE_RANDOM,
        importEnabled: Boolean = true,
        tickRepo: TickRepository? = null,
        /** 表盘（独立的一层）：只读它的框与关联，不参与记录的归属与权重。 */
        dialRepository: DialRepository? = null,
        tagGroupRepository: TagGroupRepository? = null,
        /**
         * 真草稿（多本 + 块）：**默认关**（用户 2026-10-11：「这个东西默认关闭，特殊的是全量」）。
         * 打开「整体备份」时由引擎强制包含，不看这个开关。
         */
        includeDrafts: Boolean = false,
        draftRepository: memore.app.data.repository.DraftRepository? = null,
        /**
         * 整体备份（仅对完整数据包 ZIP 生效）：
         * 打开后额外写入 versions / trash / album_state / sounds / settings / attachments_index / backup_meta，
         * 并把数据块强制拉满、强制不脱敏（备份的语义是"原样搬回去"）。
         * 关闭时输出与旧版完全一致（纯 JSON 与普通 ZIP 都不受影响）。
         */
        fullBackup: Boolean = false,
        /** 2026-11-16：整体备份时是否把 API 密钥一并写进包（用户勾选，默认否）。 */
        includeApiKey: Boolean = false,
        /**
         * 交付目标（「另存为…」= SAF `ACTION_CREATE_DOCUMENT` 返回的 uri 打开的流）。
         * 传了就直接写进这个流：**不落 cacheDir、不写 Download、不弹分享**。
         * 不传则沿用旧路径（cacheDir → 保存到 Download + 分享），行为与改动前完全一致。
         */
        output: OutputStream? = null,
        /** 旧交付开关：默认两个都开 = 改动前行为（Download + 分享），不传时一个字节不变。 */
        deliverToDownloads: Boolean = true,
        deliverToShare: Boolean = true,
        // ===== 2026-11-16 v2 补洞：把以前漏掉的三块表读出来 =====
        zoneDao: memore.app.data.local.dao.ZoneDao? = null,
        originDao: memore.app.data.local.dao.OriginDao? = null,
        // ===== 2026-11-16：范围筛选补齐（标签 / 区域 / 来源）=====
        /** 标签 id 白名单；null/空 = 不限。 */
        tagIds: Set<Int>? = null,
        /** 区域 id 白名单；null/空 = 不限。 */
        zoneIds: Set<String>? = null,
        /** 来源 id 白名单；null/空 = 不限。 */
        originIds: Set<String>? = null
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            // 进度：先把"整理数据"这段报出来（这一段是一堆 DB 读取 + 拼 JSON，没有天然刻度 → 不定量）
            reportProgress("正在整理数据…")
            val full = fullBackup && format == ExportFormat.FULL_PACKAGE
            // 整体备份 = 全量语义：忽略时间/协议/刻度筛选。
            // 否则会做出"只有筛选范围记录、却带着全机设置与全部历史版本"的半整体包 ——
            // 版本快照 / 回收站 / 设置 / 提示音本来就只能是全量，筛选只会让包自相矛盾。
            val fProtocolIds: Set<Int>? = if (full) null else protocolIds
            val fTickIds: Set<String>? = if (full) null else tickIds
            val fStartTs: Long? = if (full) null else startTs
            val fEndTs: Long? = if (full) null else endTs
            val allRecords = if (fullBackup || includeLocked) recordRepo.getAllActiveRecordsRaw()
                             else recordRepo.getAllActiveRecords()
            val records = if (fProtocolIds != null && protocolRepo != null) {
                val filtered = mutableListOf<memore.app.data.model.Record>()
                fProtocolIds.forEach { pid ->
                    filtered.addAll(protocolRepo.getRecordsByProtocolId(pid))
                }
                filtered.distinctBy { it.id }.filter { it.id in allRecords.map { r -> r.id } }
            } else allRecords
            val timeFiltered = if (fTickIds != null && fTickIds.isNotEmpty() && tickRepo != null) {
                // 按刻度筛选：与协议为交集；选中刻度时忽略时间窗（刻度本身就是时间窗）。
                val ranges = tickRepo.getActive().filter { it.id in fTickIds }.map { it.startMs to it.endMs }
                records.filter { r -> ranges.any { (s, e) -> r.createdAt >= s && (e == null || r.createdAt < e) } }
            } else {
                records.filter { r ->
                    (fStartTs == null || r.createdAt >= fStartTs) && (fEndTs == null || r.createdAt <= fEndTs)
                }
            }
            // 2026-11-16（v5）：**普通导出默认排除锁定记录**；整体备份强制全量；勾选 includeLocked 才含。
            val lockScoped = if (!fullBackup && !includeLocked) timeFiltered.filter { !it.isLocked }
                             else timeFiltered
            // 2026-11-16：范围筛选补齐（标签 / 区域 / 来源）。整体备份忽略它们（备份必须全量）。
            val tagAllowed: Set<String>? = if (!full) tagIds?.takeIf { it.isNotEmpty() }?.let {
                runCatching { recordRepo.recordIdsByTagIds(it.toList()).toSet() }.getOrElse { emptySet() }
            } else null
            val zoneAllowed: Set<String>? = if (!full) zoneIds?.takeIf { it.isNotEmpty() }?.let { wants ->
                runCatching {
                    zoneDao?.allMembers().orEmpty().filter { it.zoneId in wants }.map { it.recordId }.toSet()
                }.getOrElse { emptySet() }
            } else null
            val timeFilteredScoped = lockScoped.filter { r ->
                (tagAllowed == null || r.id in tagAllowed) &&
                (zoneAllowed == null || r.id in zoneAllowed) &&
                (originIds.isNullOrEmpty() || r.originId in originIds)
            }
            if (timeFilteredScoped.isEmpty()) { clearProgress(); return@withContext Result.failure(Exception("没有可导出的记录")) }
            // 关系映射（内联进每条记录：关联 out / 被关联 in）。
            // 整体备份不过滤（回收站记录也走单独一拨构造，用同一张表）；
            // 普通导出只带**两端都在导出范围内**的边 —— 否则会给出指向文件外记录的悬空引用，
            // 反而让读数据的 AI 困惑。没有关系时整张表为空 → 两个字段都不写，字节与改动前一致。
            val relationMap: Map<String, Pair<List<ExportedRelationOut>, List<ExportedRelationIn>>> = try {
                buildRelationMap(recordRepo.allRelations(), if (full) null else timeFilteredScoped.map { it.id }.toSet())
            } catch (e: Exception) { LogUtil.e("ExportEngine", "relationMap", e); emptyMap() }
            val amends = try {
                recordRepo.getAmendmentsForRecords(timeFilteredScoped.map { it.id })
            } catch (e: Exception) {
                LogUtil.e("ExportEngine", "getAmendmentsForRecords", e); emptyList()
            }
            // 2026-11-17：整体备份还差**孤儿增修**——父记录已经不存在的那种。
            // getAllActiveRecords() 带 `correction_of_id IS NULL`，增修只能靠"父在范围内"补回来；
            // 父没了的孤儿增修永远补不回来，真机实测备份包因此静默少 20 条。
            val orphanAmends = if (fullBackup) {
                try { recordRepo.getAllActiveAmendments() } catch (e: Exception) {
                    LogUtil.e("ExportEngine", "getAllActiveAmendments", e); emptyList()
                }
            } else emptyList()
            val allWithAmends = (timeFilteredScoped + amends + orphanAmends).distinctBy { it.id }
            // recordId → 所属刻度 id + 刻度权重（按 created_at 归属），用于阶段加权与跨阶段连续性标记
            val nowW = System.currentTimeMillis()
            val activeTicks = tickRepo?.getActive() ?: emptyList()
            val tickWeightById = activeTicks.associate { it.id to tickRepo!!.computeWeight(it, nowW) }
            val recordToTickId = memore.app.data.repository.TickMembership.recordTickMap(allRecords, activeTicks)
            val recordStageWeight = allRecords.associate { r ->
                val tickId = recordToTickId[r.id].orEmpty()
                val sw = if (tickId.isNotEmpty()) {
                    tickWeightById[tickId] ?: 1.0
                } else {
                    // 无刻度记录：按自身时间衰减（同 6 个月半衰期 + 下限），避免高估早期未分段历史。
                    val age = (nowW - r.createdAt).coerceAtLeast(0)
                    max(STAGE_WEIGHT_FLOOR, 0.5.pow(age / STAGE_HALF_LIFE_MS.toDouble()))
                }
                r.id to sw
            }
                        val albumState = runCatching {
                memore.app.data.album.AlbumStateStore(java.io.File(context.filesDir, "album_state.json")).load()
            }.getOrDefault(memore.app.data.album.AlbumState())
            val exportRecords = allWithAmends.map {
                buildExportRecord(it, tagRepo, protocolRepo, decryptLocked, albumState.footnotes[it.id], recordToTickId, relationMap, fullBackup = full)
            }.toMutableList()
            // 最新优先：按时间戳降序排列，确保外部 AI 优先读取新记录（包含修正、更新、当前状态）
            exportRecords.sortByDescending { it.timestamp }
            if (autoInsert && systemInstruction.isNotBlank()) {
                val marker = "\n\n（参照根层 system_instruction）"
                exportRecords.take(3).forEachIndexed { index, record ->
                    exportRecords[index] = record.copy(content = record.content + marker)
                }
            }
            val profile = if (includeEmotionalProfile || full) {
                // 阶段加权：每条记录的情绪贡献 × 其所属刻度的权重（新阶段权重大 → 近期情绪主导画像）。
                val acc = mutableMapOf<String, Double>()
                timeFiltered.forEach { r ->
                    val sw = recordStageWeight[r.id] ?: 1.0
                    EmotionStats.emotionEntries(r).forEach { e ->
                        val w = if (e.weight > 0f) e.weight.toDouble() else 0.5
                        acc[e.name] = (acc[e.name] ?: 0.0) + sw * w
                    }
                }
                val total = acc.values.sum()
                acc.entries.sortedByDescending { it.value }
                    .map { EmotionalProfileItem(it.key, it.value, if (total > 0) it.value / total else 0.0) }
            } else emptyList()            // ==== 标签组：分类壳（组名→成员标签名），导入据此还原归属；不参与阶段权重等语义 ====
            var tagHierarchy: Map<String, List<String>>? = null
            if (tagGroupRepository != null) {
                try {
                    val hierarchy = linkedMapOf<String, List<String>>()
                    tagGroupRepository.getGroups().forEach { g ->
                        val members = tagGroupRepository.getMembers(g.id)
                        if (members.isNotEmpty()) hierarchy[g.name] = members.map { it.name }
                    }
                    tagHierarchy = hierarchy.takeIf { it.isNotEmpty() }
                } catch (e: Exception) { LogUtil.e("ExportEngine", "tagHierarchy", e) }
            }
            val rejectedMerges: List<ExportedRejectedMerge>? = null
            val aliasGroups = if (includeAliasGroups || full) {
                try { aliasMappingRepo?.load()?.sortedByDescending { it.updatedAt } }
                catch (e: Exception) { LogUtil.e("ExportEngine", "aliasGroups", e); null }
            } else null
            // ==== 秒针 · 刻度：可命名时间区间 + 整体权重（W1 纯时间衰减） ====
            val nowMs = System.currentTimeMillis()
            val ticksExport = if (tickRepo != null) {
                try {
                    val active = tickRepo.getActive()
                    // 按刻度筛选时，ticks[] 只保留选中的刻度；否则导出全部（最新在前）。
                    val chosen = if (fTickIds?.isNotEmpty() == true) active.filter { it.id in fTickIds } else active
                    chosen.map { tick ->
                        val end = tick.endMs
                        val slice = if (includeSlices || full) {
                            try { tickRepo.loadSlice(tick) } catch (e: Exception) { LogUtil.e("ExportEngine", "tickSlice", e); null }
                        } else null
                        ExportedTick(
                            id = tick.id,
                            name = tick.name,
                            start = formatIsoZ(tick.startMs),
                            end = end?.let { formatIsoZ(it) },
                            weight = tickRepo.computeWeight(tick, nowMs),
                            record_count = timeFiltered.count { r ->
                                r.createdAt >= tick.startMs && (end == null || r.createdAt < end)
                            },
                            summary = tick.summary.ifBlank { null },
                            review = tick.review.ifBlank { null },
                            emotion = slice?.emotion?.takeIf { it.isNotEmpty() }?.sortedByDescending { it.weight }?.map { ExportedSliceEntry(it.name, it.weight, it.count) },
                            nodes = slice?.nodes?.takeIf { it.isNotEmpty() }?.sortedByDescending { it.weight }?.map { ExportedSliceEntry(it.name, it.weight, it.count) }
                        )
                    }
                } catch (e: Exception) { LogUtil.e("ExportEngine", "ticks", e); emptyList() }
            } else emptyList()
            val tickMeta = if (ticksExport.isNotEmpty()) TickExportMeta(
                rule = "每条记录/增修按 created_at 落在 start<=t<end 的刻度；增修按各自创建时间归属。刻度的 weight 表示该段自我的相对重要度：高权重刻度优先阅读，但不要忽略低权重刻度（少影响≠割除）。",
                weight_decay = "exponential",
                half_life = "6个月",
                now = formatIsoZ(nowMs)
            ) else null
            // 表盘：一段时期的框（包络刻度）。**没有权重、没有切片**，只是"这一段时间里有哪几段"。
            val chosenTickIds: Set<String>? = fTickIds?.takeIf { it.isNotEmpty() }
            val dialsExport: List<ExportedDial> = if (dialRepository != null) {
                try {
                    val links = dialRepository.dialIdsByTick()
                    val tickIdsByDial = mutableMapOf<String, MutableList<String>>()
                    links.forEach { (tickId, dialIds) ->
                        dialIds.forEach { d -> tickIdsByDial.getOrPut(d) { mutableListOf() }.add(tickId) }
                    }
                    dialRepository.allDials().sortedByDescending { it.startMs }.map { d ->
                        val pooled = (tickIdsByDial[d.id] ?: emptyList())
                            // 按刻度筛选导出时，表盘只留选中那些刻度（避免给出包外的 id）
                            .let { ids -> if (chosenTickIds == null) ids else ids.filter { it in chosenTickIds } }
                        ExportedDial(
                            name = d.name,
                            start = formatIsoZ(d.startMs),
                            end = d.endMs?.let { formatIsoZ(it) },
                            summary = d.summary.ifBlank { null },
                            tick_ids = pooled.sorted()
                        )
                    }
                } catch (e: Exception) { LogUtil.e("ExportEngine", "dials", e); emptyList() }
            } else emptyList()
            // 图谱可视化 / 候选边：2026-10-11 随概念网络一起删除
            // 加载加密配置（enabled=true 时才导出加密配置块）
            val encConfig = buildEncryptionConfig(encryptionConfigDao)

            // 音乐库（含音频文件，仅 ZIP）：读取元数据 + 打包时写文件

            // 真草稿（多本 + 块）：**默认关**；「整体备份」强制包含。
            val draftsExport: List<ExportedDraft>? =
                collectDrafts(draftRepository, include = includeDrafts, full = full)

            // ================= 整体备份块（仅 full 时收集；否则全 null → 输出与旧版逐字节一致） =================
            // 附件范围：与"记录集"共用同一套（筛选后的记录 + 增修），修掉原先只按协议筛选取附件导致的范围错位。
            val trashRecords: List<Record> = if (full) {
                try { recordRepo.getTrashedRecords() }
                catch (e: Exception) { LogUtil.e("ExportEngine", "getTrashedRecords", e); emptyList() }
            } else emptyList()
            val attachmentScope: List<Record> = (allWithAmends + trashRecords).distinctBy { it.id }
            val versionsExport: List<ExportedVersion>? = if (full) {
                try { recordRepo.getAllVersions().map { ExportedVersion(it.recordId, it.content, it.createdAt) } }
                catch (e: Exception) { LogUtil.e("ExportEngine", "getAllVersions", e); emptyList() }
            } else null
            // 回收站记录：与正常记录同形状（deleted=true），导入端走同一条还原路径
            val trashExport: List<ExportRecord>? = if (full) {
                trashRecords.map {
                    buildExportRecord(it, tagRepo, protocolRepo, decryptLocked, albumState.footnotes[it.id], recordToTickId, relationMap, fullBackup = true)
                }.sortedByDescending { it.timestamp }
            } else null
            val pinnedSorted = albumState.pinned.toList().sorted()
            val albumStateExport: ExportedAlbumState? = when {
                // 2026-11-23：图文集星标不再只随整体备份；普通导出也带上（主图选择仍只在本机）。
                !full && pinnedSorted.isEmpty() -> null
                full -> ExportedAlbumState(albumState.selected, pinnedSorted)
                else -> ExportedAlbumState(pinned = pinnedSorted)
            }
            val soundsExport: Map<String, List<String>>? = if (full) {
                try { SoundConfigStore(context).loadOverrides() }
                catch (e: Exception) { LogUtil.e("ExportEngine", "sound overrides", e); null }
            } else null
            val settingsExport: Map<String, Map<String, PrefEntry>>? = if (full) dumpPrefs(context, includeApiKey) else null
            val attachmentsIndex: Map<String, List<ExportedAttachment>>? =
                if (full) buildAttachmentsIndex(recordRepo, attachmentScope, decryptLocked) else null
            val backupMeta = if (full) BackupMeta(
                kind = "full",
                app_version = appVersionName(context),
                db_version = memore.app.data.local.APP_DB_VERSION,
                includes = listOf(
                    "records", "versions", "trash", "album_state", "sounds", "settings",
                    "attachments", "attachments_index", "voice_cache", "slices", "dials",
                    "encryption_config", "redaction_groups", "drafts"
                ),
                record_count = exportRecords.size,
                trash_count = trashExport?.size ?: 0,
                version_count = versionsExport?.size ?: 0,
                draft_count = draftsExport?.size ?: 0,
                created_at = formatIsoZ()
            ) else null

            // 2026-11-16 v2 补洞：这三块以前不在包里（"全量"其实漏了它们）
            val zonesOut = zoneDao?.let { dao ->
                val members = runCatching { dao.allMembers() }.getOrDefault(emptyList()).groupBy { it.zoneId }
                runCatching { dao.all() }.getOrDefault(emptyList()).map { z ->
                    ExportedZone(
                        id = z.id, name = z.name, builtin = z.builtin, condition = z.condition,
                        member_ids = members[z.id].orEmpty().map { it.recordId }
                    )
                }.takeIf { it.isNotEmpty() }
            }
            val originsOut = originDao?.let { dao ->
                runCatching { dao.all() }.getOrDefault(emptyList()).map {
                    ExportedOrigin(
                        id = it.id, name = it.name, builtin = it.builtin,
                        w_link = it.wLink, w_cluster = it.wCluster, w_stats = it.wStats, link_threshold = it.linkThreshold,
                        sort_order = it.sortOrder, created_at = it.createdAt
                    )
                }.takeIf { it.isNotEmpty() }
            }
            val categoriesOut: List<ExportedConceptCategory>? = null

            // 2026-11-20：新结构只在整体备份里读库导出（普通导出保持逐字节不变）
            val dbRef = database
            val anchorsExport = if (full && dbRef != null) {
                runCatching { kotlinx.coroutines.runBlocking { dbRef.recordEntityDao().allRaw() } }
                    .getOrDefault(emptyList())
                    .map { ExportedAnchor(it.recordId, it.name, it.kind, it.quote, it.createdAt) }
                    .takeIf { it.isNotEmpty() }
            } else null
            val analysisVersionsExport = if (full && dbRef != null) {
                runCatching { kotlinx.coroutines.runBlocking { dbRef.analysisVersionDao().all() } }
                    .getOrDefault(emptyList())
                    .map {
                        ExportedAnalysisVersion(
                            it.recordId, it.model, it.promptVersion, it.inputHash,
                            it.summary, it.emotionJson, it.status, it.reason, it.createdAt
                        )
                    }
                    .takeIf { it.isNotEmpty() }
            } else null
            val agentActionsExport = if (full && dbRef != null) {
                runCatching { kotlinx.coroutines.runBlocking { dbRef.agentActionDao().all() } }
                    .getOrDefault(emptyList())
                    .map {
                        ExportedAgentAction(
                            it.id, it.actor, it.title, it.commandIds, it.status,
                            it.requiresConfirm, it.note, it.createdAt, it.executedAt, it.revertedAt
                        )
                    }
                    .takeIf { it.isNotEmpty() }
            } else null
            val agentCommandsExport = if (full && dbRef != null) {
                runCatching { kotlinx.coroutines.runBlocking { dbRef.agentCommandDao().all() } }
                    .getOrDefault(emptyList())
                    .map {
                        ExportedAgentCommand(
                            it.id, it.kind, it.payload, it.status, it.note,
                            it.createdAt, it.executedAt, it.batchId
                        )
                    }
                    .takeIf { it.isNotEmpty() }
            } else null

            var exportData = ExportData(
                system_instruction = withSystemRules(systemInstruction),
                version = "2.5",
                export_time = formatIsoZ(),
                import_enabled = importEnabled,
                emotional_profile = profile,
                profile_meta = ExportProfileMeta(records_used = profile.size),
                rejected_merges = rejectedMerges?.takeIf { it.isNotEmpty() },
                alias_groups = aliasGroups?.takeIf { it.isNotEmpty() },
                prompts = if (includePrompts || full) memore.app.core.ai.Prompts.exportAll() else emptyMap(),
                count = exportRecords.size,
                records = exportRecords,
                encryption_config = encConfig,
                ticks = ticksExport,
                tick_meta = tickMeta,
                dials = dialsExport.takeIf { it.isNotEmpty() },
                node_hierarchy = null,
                tag_hierarchy = tagHierarchy,
                drafts = draftsExport,
                backup_meta = backupMeta,
                versions = versionsExport,
                trash = trashExport,
                album_state = albumStateExport,
                sounds = soundsExport,
                settings = settingsExport,
                attachments_index = attachmentsIndex,
                redaction_groups = if (full) redactionGroups.takeIf { it.isNotEmpty() } else null,
                anchors = anchorsExport,
                analysis_versions = analysisVersionsExport,
                agent_actions = agentActionsExport,
                agent_commands = agentCommandsExport,
                zones = zonesOut,
                origins = originsOut,
                concept_categories = categoriesOut,
                manifest = ExportManifest(
                    schema_version = 2,
                    app_version = appVersionName(context),
                    export_time = formatIsoZ(),
                    record_count = exportRecords.size,
                    blocks = buildMap {
                        put("enabled", listOfNotNull(
                            "emotion".takeIf { includeEmotionalProfile || full },
                            "rejected".takeIf { includeRejectedMerges || full },
                            "prompts".takeIf { includePrompts || full },
                            "drafts".takeIf { includeDrafts || full },
                            "zones".takeIf { zonesOut != null },
                            "origins".takeIf { originsOut != null },
                            "categories".takeIf { categoriesOut != null },
                            "full_backup".takeIf { full }
                        ).joinToString(","))
                    }
                )
            )
            // 整体备份强制不脱敏：脱敏是"给出去还能藏住"的工具，备份的语义是"原样搬回去"。
            if (!full && redact && redactionGroups.isNotEmpty()) {
                exportData = redactExportData(exportData, redactionGroups, redactionMode)
            }
            val jsonString = buildOrderedJson(exportData, compact = compactJson)
            val timestamp = formatFileTimestamp()
            // 拒导入只写 import_enabled=false 的合法 JSON；损坏动作由导入端在拒绝后执行。
            val payloadBytes = jsonString.toByteArray(Charsets.UTF_8)

            when (format) {
                ExportFormat.JSON_ONLY -> {
                    if (output != null) {
                        output.use { it.write(payloadBytes) }
                    } else {
                        val fileName = "memory_$timestamp.json"
                        val cacheFile = File(context.cacheDir, fileName)
                        FileOutputStream(cacheFile).use { it.write(payloadBytes) }
                        if (deliverToDownloads) saveToDownloads(context, fileName, cacheFile, "application/json")
                        if (deliverToShare) shareFile(context, cacheFile, "application/json", "分享镜像记忆包")
                        // 同理：交付后删掉 cache 副本（分享时不删，留给启动清理）
                        if (!deliverToShare) runCatching { cacheFile.delete() }
                    }
                }
                ExportFormat.AI_MARKDOWN -> {
                    // 只读交付：不写 cacheDir 之外的东西，也不参与导入。
                    val mdBytes = buildAiMarkdown(exportData).toByteArray(Charsets.UTF_8)
                    if (output != null) {
                        output.use { it.write(mdBytes) }
                    } else {
                        val fileName = "memory_$timestamp.md"
                        val cacheFile = File(context.cacheDir, fileName)
                        FileOutputStream(cacheFile).use { it.write(mdBytes) }
                        if (deliverToDownloads) saveToDownloads(context, fileName, cacheFile, "text/markdown")
                        if (deliverToShare) shareFile(context, cacheFile, "text/markdown", "分享记忆记录")
                        if (!deliverToShare) runCatching { cacheFile.delete() }
                    }
                }
                ExportFormat.FULL_PACKAGE -> {
                    val zipName = "memory_$timestamp.zip"
                    // output != null → 「另存为」：直接写进调用方给的流，不落盘、不进 Download、不弹分享。
                    // 否则沿用旧路径：先落 cacheDir，再交付（Download + 分享）。
                    val exportTarget = output
                    val zipFile: File?
                    val rawStream: OutputStream
                    if (exportTarget != null) {
                        zipFile = null
                        rawStream = exportTarget
                    } else {
                        zipFile = File(context.cacheDir, zipName)
                        rawStream = FileOutputStream(zipFile)
                    }
                    ZipOutputStream(BufferedOutputStream(rawStream)).use { zos ->
                        // 进度分母：data.json + 会被写进包的附件 + 语音缓存文件 + 音乐 + 自定义提示音。
                        // 全部是"先数一遍"的廉价统计（一次批量查询 + 若干 exists），**不改写包内容**。
                        val plannedAttachments = try {
                            recordRepo.countAttachmentsForExport(attachmentScope.map { it.id }, decryptLocked)
                        } catch (_: Exception) { 0 }
                        val voiceDirForCount = File(context.filesDir, "voice_cache")
                        val plannedVoice = if (includeVoiceCache || full) {
                            attachmentScope.sumOf { r ->
                                (if (File(voiceDirForCount, "${r.id}.json").exists()) 1 else 0) +
                                    (if (File(voiceDirForCount, "${r.id}.wav").exists()) 1 else 0)
                            }
                        } else 0
                        val plannedSounds = if (full) (File(context.filesDir, "sound_custom").listFiles()?.size ?: 0) else 0
                        val plannedTotal = 1 + plannedAttachments + plannedVoice + plannedSounds
                        var wrote = 0
                        // 每个"计划内的条目"走一步（哪怕源文件缺失、实际没写出字节，也算这一步走过）——
                        // 这样进度条能老老实实走到 100%，不会卡在 98% 让人以为卡死。
                        fun step(detail: String? = null) {
                            wrote++
                            reportProgress("正在写入包…", wrote, plannedTotal, detail)
                        }
                        // data.json
                        zos.putNextEntry(ZipEntry("data.json"))
                        zos.write(payloadBytes)
                        zos.closeEntry()
                        step("data.json")
                        // attachments
                        // 用 attachmentScope（= 筛选后的记录 + 增修 [+ 回收站]），与 data.json 的记录集一致；
                        // 原先进来的是 records（只过协议筛选），按时间/刻度筛选时会把范围外的附件也打进包。
                        attachmentScope.forEach { record ->
                            val atts = try { recordRepo.getAttachments(record.id) }
                            catch (e: Exception) { LogUtil.e("ExportEngine", "getAttachments ${record.id}", e); emptyList() }
                            atts.forEach { att ->
                                // 未开启明文解密时：跳过加密附件；开启时：解密后打进包（临时文件用完即删）
                                val isDecrypting = decryptLocked && att.isEncrypted
                                if (att.isEncrypted && !decryptLocked) return@forEach
                                val key = if (isDecrypting) EncryptionManager.getCachedKey() ?: throw Exception("请先输入加密密码") else null
                                val outName = if (isDecrypting) (EncryptionManager.decryptFileName(att.encName, key!!) ?: att.fileName) else att.fileName
                                val srcFile = if (isDecrypting) {
                                    val tmp = File(context.cacheDir, "att_dec_${att.id}")
                                    EncryptionManager.decryptFileTo(File(att.filePath), tmp, att.iv, att.authTag, key!!)
                                    tmp
                                } else File(att.filePath)
                                try {
                                    if (srcFile.exists()) {
                                        // 2026-11-16：图片/音频已经是压缩格式，再 deflate 只是白烧 CPU 且几乎不变小
                                        zos.putFileSmart("attachments/${record.id}/$outName", srcFile)
                                    }
                                } finally {
                                    if (isDecrypting) srcFile.delete()
                                }
                                step(outName)
                            }
                        }
                        // voice cache（译文 + 音频）默认不导出，由功能选择开关控制
                        // 用 attachmentScope 而非 timeFiltered：与记录集对齐（补上增修 / 回收站的语音缓存）
                        if (includeVoiceCache || full) {
                            val voiceDir = File(context.filesDir, "voice_cache")
                            attachmentScope.forEach { record ->
                                val vj = File(voiceDir, "${record.id}.json")
                                val va = File(voiceDir, "${record.id}.wav")
                                if (vj.exists()) {
                                    zos.putNextEntry(ZipEntry("voice_cache/${record.id}.json"))
                                    vj.inputStream().use { it.copyTo(zos) }
                                    zos.closeEntry()
                                    step("voice/${record.id}.json")
                                }
                                if (va.exists()) {
                                    zos.putNextEntry(ZipEntry("voice_cache/${record.id}.wav"))
                                    va.inputStream().use { it.copyTo(zos) }
                                    zos.closeEntry()
                                    step("voice/${record.id}.wav")
                                }
                            }
                        }
                        // 自定义提示音音频（整体备份）：绑定关系在 data.json 的 sounds 块里
                        if (full) {
                            val soundDir = File(context.filesDir, "sound_custom")
                            soundDir.listFiles()?.forEach { f ->
                                try {
                                    zos.putNextEntry(ZipEntry("sound_custom/${f.name}"))
                                    f.inputStream().use { it.copyTo(zos) }
                                    zos.closeEntry()
                                } catch (e: Exception) { LogUtil.e("ExportEngine", "sound export ${f.name}", e) }
                                step(f.name)
                            }
                        }
                        // 2026-11-16：files/ 里那两本账（不在 DB 里，以前整体备份完全漏掉）
                        //   ai_runs.jsonl          → 向量索引页的「AI 记录」
                        //   insights/archive.jsonl → 洞察档案（新机上"洞察档案没东西"就是这个）
                        if (full) {
                            listOf(
                                "ai_runs.jsonl", "insights/archive.jsonl", "insights/last_raw.txt",
                                // 2026-11-23：主题/线索缓存（AI 命名结果），换机后不必重新烧一次
                                "monthly_topics.json", "topic_threads.json"
                            ).forEach { rel ->
                                val f = File(context.filesDir, rel)
                                if (f.exists() && f.length() > 0) {
                                    try { zos.putFileSmart("meta/$rel", f) }
                                    catch (e: Exception) { LogUtil.e("ExportEngine", "meta export $rel", e) }
                                    step(rel)
                                }
                            }
                        }
                    }
                    if (zipFile != null) {
                        reportProgress("正在交付…")
                        if (deliverToDownloads) saveToDownloads(context, zipName, zipFile, "application/zip")
                        if (deliverToShare) shareFile(context, zipFile, "application/zip", "分享完整备份包")
                        // 交付完就把 cache 里这份整包删掉（2026-10-21）：
                        // 不删的话每导出一次就留 100~300MB，实测堆到过 1.4GB。
                        // 走"分享"时不立刻删——对方 App 可能还在读；那部分交给启动时的 cleanupExportCache（6 小时后清）。
                        if (!deliverToShare) runCatching { zipFile.delete() }
                    }
                }
            }

            clearProgress()
            Result.success(Unit)
        } catch (e: Exception) {
            LogUtil.e("ExportEngine", "export failed", e)
            clearProgress()
            Result.failure(e)
        }
    }

    /**
     * 手动构建 JSON，保证根层级字段顺序：system_instruction 始终位于最前方（version 之前）。
     *
     * 原因：Gson 基于 Java 反射序列化 data class 时，字段顺序依赖 Class.getDeclaredFields()，
     * 而 JVM 规范不保证其返回声明顺序，在 Android ART 上可能出现 system_instruction 不在最前方的情况。
     * 使用 JsonObject 显式按声明顺序 add，可确保外部 AI 优先解析到处理规则。
     */
    /**
     * 2026-11-16：写一个文件进 ZIP，**按扩展名决定是否压缩**。
     *
     * 图片/音频本身就是压缩格式，deflate 之后几乎不变小，只多烧一遍 CPU（181MB 的备份白跑几十秒）。
     * 这类文件用 STORED，但要先算出 size/crc32（ZIP 规范要求 STORED 条目带这两个字段）。
     */
    private val PRECOMPRESSED_EXT = setOf(
        "jpg", "jpeg", "png", "webp", "gif", "heic", "heif",
        "mp3", "m4a", "aac", "ogg", "opus", "flac", "amr",
        "mp4", "3gp", "webm"
    )

    internal fun ZipOutputStream.putFileSmart(name: String, file: File) {
        val ext = file.name.substringAfterLast('.', "").lowercase()
        val entry = ZipEntry(name)
        if (ext in PRECOMPRESSED_EXT) {
            entry.method = ZipEntry.STORED
            val len = file.length()
            entry.size = len
            entry.compressedSize = len
            val crc = java.util.zip.CRC32()
            file.inputStream().use { ins ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = ins.read(buf)
                    if (n < 0) break
                    crc.update(buf, 0, n)
                }
            }
            entry.crc = crc.value
        }
        putNextEntry(entry)
        file.inputStream().use { it.copyTo(this) }
        closeEntry()
    }

    internal fun buildOrderedJson(data: ExportData, compact: Boolean = false): String {
        val obj = com.google.gson.JsonObject()
        obj.addProperty("system_instruction", data.system_instruction)
        if (data.encryption_config != null) obj.add("encryption_config", gson.toJsonTree(data.encryption_config))
        obj.addProperty("version", data.version)
        obj.addProperty("export_time", data.export_time)
        obj.addProperty("import_enabled", data.import_enabled)
        obj.add("emotional_profile", gson.toJsonTree(data.emotional_profile))
        if (data.profile_meta != null) obj.add("profile_meta", gson.toJsonTree(data.profile_meta))
        if (!data.rejected_merges.isNullOrEmpty()) obj.add("rejected_merges", gson.toJsonTree(data.rejected_merges))
        if (!data.alias_groups.isNullOrEmpty()) obj.add("alias_groups", gson.toJsonTree(data.alias_groups))
        if (data.prompts.isNotEmpty()) obj.add("prompts", gson.toJsonTree(data.prompts))
        obj.addProperty("count", data.count)
        obj.add("records", gson.toJsonTree(data.records))
        if (data.ticks.isNotEmpty()) obj.add("ticks", gson.toJsonTree(data.ticks))
        if (data.tick_meta != null) obj.add("tick_meta", gson.toJsonTree(data.tick_meta))
        if (!data.dials.isNullOrEmpty()) obj.add("dials", gson.toJsonTree(data.dials))
        if (data.node_hierarchy != null) obj.add("node_hierarchy", gson.toJsonTree(data.node_hierarchy))
        if (data.tag_hierarchy != null) obj.add("tag_hierarchy", gson.toJsonTree(data.tag_hierarchy))
        // 真草稿（默认关；整体备份强制包含）。null = 不写这个键 → 老配置的输出逐字节不变
        if (data.drafts != null) obj.add("drafts", gson.toJsonTree(data.drafts))
        // ===== 整体备份块（追加在最后，不动前面任何字段的顺序）=====
        if (data.backup_meta != null) obj.add("backup_meta", gson.toJsonTree(data.backup_meta))
        if (data.versions != null) obj.add("versions", gson.toJsonTree(data.versions))
        if (data.trash != null) obj.add("trash", gson.toJsonTree(data.trash))
        if (data.album_state != null) obj.add("album_state", gson.toJsonTree(data.album_state))
        if (data.sounds != null) obj.add("sounds", gson.toJsonTree(data.sounds))
        if (data.settings != null) obj.add("settings", gson.toJsonTree(data.settings))
        if (data.attachments_index != null) obj.add("attachments_index", gson.toJsonTree(data.attachments_index))
        if (data.redaction_groups != null) obj.add("redaction_groups", gson.toJsonTree(data.redaction_groups))
        // ===== v2 补洞块（2026-11-16，追加在最后，不动前面字段顺序）=====
        if (data.zones != null) obj.add("zones", gson.toJsonTree(data.zones))
        if (data.origins != null) obj.add("origins", gson.toJsonTree(data.origins))
        if (data.concept_categories != null) obj.add("concept_categories", gson.toJsonTree(data.concept_categories))
        if (data.manifest != null) obj.add("manifest", gson.toJsonTree(data.manifest))
        // ===== 2026-11-20：新结构（只在整体备份里非 null） =====
        if (data.anchors != null) obj.add("anchors", gson.toJsonTree(data.anchors))
        if (data.analysis_versions != null) obj.add("analysis_versions", gson.toJsonTree(data.analysis_versions))
        if (data.agent_actions != null) obj.add("agent_actions", gson.toJsonTree(data.agent_actions))
        if (data.agent_commands != null) obj.add("agent_commands", gson.toJsonTree(data.agent_commands))
        return if (compact) gsonCompact.toJson(obj) else gson.toJson(obj)
    }

    /**
     * 收集要随包的草稿（**默认关**；「整体备份」强制包含）。
     *
     * - 空本（一个非空块都没有）**不导出** —— 本机本来就会把空本收掉；
     * - **归档的照导**（归档只是收起来，一个字不该丢）；
     * - 返回 null = 这个包里不写 `drafts` 键（Gson 不写 → 老配置输出逐字节不变）；
     *   一本都没有内容时也返回 null（免得包里出现一个空数组）。
     */
    internal suspend fun collectDrafts(
        repo: memore.app.data.repository.DraftRepository?,
        include: Boolean,
        full: Boolean
    ): List<ExportedDraft>? {
        if (!(include || full) || repo == null) return null
        return try {
            repo.allDrafts().mapNotNull { d ->
                val blocks = repo.blocksOf(d.id).filter { it.content.isNotBlank() }
                if (blocks.isEmpty()) return@mapNotNull null
                ExportedDraft(
                    name = d.name,
                    createdAt = d.createdAt,
                    updatedAt = d.updatedAt,
                    archived = d.archived,
                    pinned = d.pinned,
                    blocks = blocks.map { b ->
                        ExportedDraftBlock(kind = b.kind, content = b.content, createdAt = b.createdAt, updatedAt = b.updatedAt)
                    }
                )
            }.takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            LogUtil.e("ExportEngine", "drafts export", e)
            null
        }
    }

    internal fun normalizePersonNames(text: String): String = text // 公版：不做个人化名归一

    /** 脱敏导出副本：脱敏组命中哪里就洗哪里，包括别名映射表的导出内容。 */
    private fun redactExportData(data: ExportData, groups: List<RedactionGroup>, mode: String): ExportData {
         // 脱敏组同时按标准名与别名匹配。
        val effectiveGroups = groups.map { g ->
            val forms = listOf(g.standardName) + g.aliases
            val expanded = (forms + forms.map { normalizePersonNames(it) })
                .distinct()
                .filter { it.isNotBlank() }
            g.copy(aliases = expanded.filter { it != g.standardName })
        }
        // 命名实体跨字段一致性：节点名/标签名/组名等会同时出现在 records/concept_network/node_hierarchy/tag_hierarchy 等，
        // 随机模式下必须用同一份占位，否则外部 AI 无法把层级与记录对应起来。
        val nameMap = mutableMapOf<String, String>()
        val redactName: (String) -> String = { text ->
            if (text.isEmpty() || !isRedactable(text, effectiveGroups)) text
            else nameMap.getOrPut(text) { RedactionEngine.redact(text, effectiveGroups, mode) }
        }
        return data.copy(
            system_instruction = RedactionEngine.redact(data.system_instruction, effectiveGroups, mode),
            // 2026-11-23：album_state 不是正文，不需要脱敏，但必须原样带过去；
            // copy() 没列出的字段会回到默认值 null，之前星标就是这样被丢的。
            album_state = data.album_state,
            emotional_profile = data.emotional_profile.map { it.copy(name = redactName(it.name)) },
            node_hierarchy = data.node_hierarchy?.mapValues { (_, children) -> children.map { redactName(it) } },
            tag_hierarchy = data.tag_hierarchy?.mapValues { (_, members) -> members.map { redactName(it) } },
            rejected_merges = data.rejected_merges?.map { redactRejectedMerge(it, effectiveGroups, mode) },
            alias_groups = data.alias_groups?.map { redactAliasGroup(it, effectiveGroups, mode) },
            prompts = data.prompts.mapValues { (_, value) -> RedactionEngine.redact(value, effectiveGroups, mode) },
            encryption_config = data.encryption_config?.copy(
                question = RedactionEngine.redact(data.encryption_config.question ?: "", effectiveGroups, mode)
            ),
            records = data.records.map { redactRecord(it, effectiveGroups, mode, redactName) },
            ticks = data.ticks.map { t ->
                t.copy(
                    name = redactName(t.name),
                    // 总结是长文，必须整段过脱敏（不是名字替换，是文本替换）
                    summary = t.summary?.let { RedactionEngine.redact(it, effectiveGroups, mode) },
                    review = t.review?.let { RedactionEngine.redact(it, effectiveGroups, mode) },
                    emotion = t.emotion?.map { it.copy(name = redactName(it.name)) },
                    nodes = t.nodes?.map { it.copy(name = redactName(it.name)) }
                )
            },
            dials = data.dials?.map { d ->
                d.copy(
                    name = redactName(d.name),
                    summary = d.summary?.let { RedactionEngine.redact(it, effectiveGroups, mode) }
                )
            }
        )
    }

    /** 判断文本是否命中任一脱敏组（用于命名实体跨字段一致性缓存，防止把非目标文本也入缓存）。 */
    private fun isRedactable(text: String, groups: List<RedactionGroup>): Boolean {
        if (text.isEmpty() || groups.isEmpty()) return false
        return groups.any { g ->
            (listOf(g.standardName) + g.aliases).any { it.isNotBlank() && text.contains(it) }
        }
    }

    @Suppress("USELESS_ELVIS")
    private fun redactAliasGroup(group: AliasGroup, groups: List<RedactionGroup>, mode: String): AliasGroup {
        // Gson 反序列化可能把 kind 置成 null，copy 会 NPE，这里兜底回 "concept"。
        val kind = group.kind ?: "concept"
        return group.copy(
            standardName = RedactionEngine.redact(group.standardName, groups, mode),
            aliases = group.aliases.map { RedactionEngine.redact(it, groups, mode) },
            kind = kind
        )
    }

    private fun redactRejectedMerge(item: ExportedRejectedMerge, groups: List<RedactionGroup>, mode: String): ExportedRejectedMerge {
        return item.copy(
            keep = RedactionEngine.redact(item.keep, groups, mode),
            remove = RedactionEngine.redact(item.remove, groups, mode)
        )
    }

    private fun redactRecord(record: ExportRecord, groups: List<RedactionGroup>, mode: String, redactName: (String) -> String): ExportRecord {
        return record.copy(
            content = RedactionEngine.redact(record.content, groups, mode),
            dialogue_ai_content = record.dialogue_ai_content?.let { RedactionEngine.redact(it, groups, mode) },
            dialogue_ai_reply = record.dialogue_ai_reply?.let { RedactionEngine.redact(it, groups, mode) },
            notes = record.notes?.let { RedactionEngine.redact(it, groups, mode) },
            tags = record.tags.map { redactName(it) },
            protocols = record.protocols?.map { redactName(it) },
            memory_nodes = record.memory_nodes?.map { redactName(it) },
            relations = record.relations?.map { redactName(it) },
            emotional_tags = record.emotional_tags?.map { it.copy(name = redactName(it.name)) },
            analysis = record.analysis?.copy(
                summary = RedactionEngine.redact(record.analysis.summary, groups, mode),
                memory_nodes = record.analysis.memory_nodes.map { redactName(it) },
                emotion = record.analysis.emotion.map { it.copy(name = redactName(it.name)) },
                relations = record.analysis.relations.map { redactName(it) }
            ),
            album_footnotes = record.album_footnotes?.map { it.copy(content = RedactionEngine.redact(it.content, groups, mode)) }
        )
    }

    // ================= 整体备份辅助 =================

    /**
     * 参与整体备份的 prefs 文件。
     * 不含 `prompt_prefs`（提示词走独立的 prompts 块，避免一个东西两个来源）、
     * 不含 `memore_auth`（应用锁凭据，明确不随包）。
     */
    private val BACKUP_PREFS_FILES = listOf(
        "mirror_prefs", "memore_prefs", "feature_console", "voice_prefs",
        "sound_config", "tutorial_prefs"
    )

    /**
     * 有独立导出块、故不重复写进 settings 的偏好键（导入时也以独立块为准）。
     * 外加**AI 密钥**：密钥不随包（用户 2026-10-11 定：「全量包里剔除 ai_api_key」）——
     * 备份包不该是一个"带密钥的文件"；换机重填一次即可，和 PIN 一个道理。
     * （复合键 `api_presets` 里的密钥单独在 [stripApiPresetKeys] 里洗掉。）
     */
    internal val BACKUP_PREFS_EXCLUDED_KEYS = listOf("system_instruction", "ai_api_key")

    /**
     * 洗掉 `api_presets` 里的各家密钥，只留端点 / 模型等非敏感配置。
     * 不洗的话"剔除 ai_api_key"是白做的 —— 密钥还藏在预设 JSON 里。
     * 解析不动时返回原样（宁可原样带，也不要吞掉用户的预设）。
     */
    internal fun stripApiPresetKeys(raw: String?): String? {
        if (raw.isNullOrBlank()) return raw
        return try {
            val arr = gson.fromJson(raw, com.google.gson.JsonArray::class.java) ?: return raw
            arr.forEach { el ->
                (el as? com.google.gson.JsonObject)?.let { o ->
                    if (o.has("apiKey")) o.addProperty("apiKey", "")
                }
            }
            gson.toJson(arr)
        } catch (e: Exception) {
            LogUtil.e("ExportEngine", "stripApiPresetKeys", e)
            raw
        }
    }

    private fun appVersionName(context: Context): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
    } catch (e: Exception) { "" }

    /**
     * 各 prefs 文件全量导出为 { 文件名: { 键: {type, value} } }。
     *
     * 2026-11-16：`includeApiKey` 由用户在「内容 → 附加处理」里勾（默认不勾）。
     * 勾了才把 `ai_api_key` 与 `api_presets` 里的各家密钥原样写进包 ——
     * **整体备份不会强制它**（这是用户明确要的"不受约束"）：包带密钥与否只由这个开关决定，
     * 因为"带密钥的备份文件"本身就是一个泄密面。
     */
    private fun dumpPrefs(context: Context, includeApiKey: Boolean = false): Map<String, Map<String, PrefEntry>> {
        val excluded = if (includeApiKey) BACKUP_PREFS_EXCLUDED_KEYS.filter { it != "ai_api_key" }
                       else BACKUP_PREFS_EXCLUDED_KEYS
        val out = linkedMapOf<String, Map<String, PrefEntry>>()
        BACKUP_PREFS_FILES.forEach { name ->
            try {
                val all = context.getSharedPreferences(name, Context.MODE_PRIVATE).all
                val entries = all.entries
                    .filter { (k, _) -> k !in excluded }
                    .associate { (k, v) ->
                        // 复合键里的密钥：勾了"包含 API 密钥"就原样带，否则洗掉
                        val value = if (k == "api_presets" && !includeApiKey) stripApiPresetKeys(v as? String) ?: v else v
                        k to PrefEntry(prefTypeOf(value), value)
                    }
                if (entries.isNotEmpty()) out[name] = entries
            } catch (e: Exception) { LogUtil.e("ExportEngine", "dumpPrefs $name", e) }
        }
        return out
    }

    /** SharedPreferences 值的类型标签（Gson 会把 int/long/float 统一读成 Double，必须显式记类型）。 */
    internal fun prefTypeOf(value: Any?): String = when (value) {
        is Boolean -> "boolean"
        is Int -> "int"
        is Long -> "long"
        is Float -> "float"
        is Set<*> -> "string_set"
        is String -> "string"
        else -> "string"
    }

    /** 还原方向：按类型标签把 JSON 读回来的值转成 SharedPreferences 能写的类型。 */
    internal fun decodePrefValue(type: String, value: Any?): Any? = when (type) {
        "boolean" -> (value as? Boolean) ?: value?.toString()?.toBoolean()
        "int" -> (value as? Number)?.toInt()
        "long" -> (value as? Number)?.toLong()
        "float" -> (value as? Number)?.toFloat()
        "string_set" -> (value as? List<*>)?.map { it.toString() }?.toSet() ?: emptySet<String>()
        else -> value?.toString()
    }

    /**
     * 附件清单：ZIP 里只有文件本体，类型/大小/时长/添加时间靠这份清单还原。
     * 与打包口径一致 —— 未解密导出时加密附件不进包，故也不进清单。
     */
    private suspend fun buildAttachmentsIndex(
        recordRepo: RecordRepository,
        scope: List<Record>,
        decryptLocked: Boolean
    ): Map<String, List<ExportedAttachment>> {
        val out = linkedMapOf<String, List<ExportedAttachment>>()
        scope.forEach { record ->
            val atts = try { recordRepo.getAttachments(record.id) }
            catch (e: Exception) { LogUtil.e("ExportEngine", "attachmentsIndex ${record.id}", e); emptyList() }
            val visible = atts.filter { !(it.isEncrypted && !decryptLocked) }
            if (visible.isNotEmpty()) {
                out[record.id] = visible.map {
                    ExportedAttachment(
                        file_name = it.fileName,
                        type = it.type,
                        file_size = it.fileSize,
                        duration = it.duration,
                        created_at = it.createdAt
                    )
                }
            }
        }
        return out
    }

    /**
     * 把关系边整理成 `recordId → (关联 out, 被关联 in)`。
     *
     * 同一条边会**同时**出现在两端的记录里（A 的 out 与 B 的 in）—— 这是有意的：
     * 外部 AI 逐条读记录，任何一侧单独看都能用；导入端靠 (from,to,type) 唯一索引自动去重。
     * 按 type 排序只为让输出稳定（同一份数据每次导出字节一致）。
     *
     * [allowIds] 为 null 表示不过滤（整体备份）；否则只保留两端都在集合内的边。
     */
    internal fun buildRelationMap(
        relations: List<memore.app.data.model.RecordRelation>,
        allowIds: Set<String>?
    ): Map<String, Pair<List<ExportedRelationOut>, List<ExportedRelationIn>>> {
        val out = mutableMapOf<String, MutableList<ExportedRelationOut>>()
        val inbound = mutableMapOf<String, MutableList<ExportedRelationIn>>()
        relations.forEach { rel ->
            if (allowIds != null && (rel.fromId !in allowIds || rel.toId !in allowIds)) return@forEach
            out.getOrPut(rel.fromId) { mutableListOf() }.add(ExportedRelationOut(
                rel.toId, rel.type, rel.why.takeIf { it.isNotEmpty() },
                rel.quoteA.takeIf { it.isNotEmpty() }, rel.quoteB.takeIf { it.isNotEmpty() },
                rel.source.takeIf { it != memore.app.data.model.RecordRelation.SOURCE_USER },
                rel.validFrom.takeIf { it > 0 }, rel.validTo
            ))
            inbound.getOrPut(rel.toId) { mutableListOf() }.add(ExportedRelationIn(
                rel.fromId, rel.type, rel.why.takeIf { it.isNotEmpty() },
                rel.quoteA.takeIf { it.isNotEmpty() }, rel.quoteB.takeIf { it.isNotEmpty() },
                rel.source.takeIf { it != memore.app.data.model.RecordRelation.SOURCE_USER },
                rel.validFrom.takeIf { it > 0 }, rel.validTo
            ))
        }
        return (out.keys + inbound.keys).associateWith { id ->
            out[id].orEmpty().sortedBy { it.type } to inbound[id].orEmpty().sortedBy { it.type }
        }
    }

    private suspend fun buildExportRecord(
        record: Record,
        tagRepo: TagRepository,
            protocolRepo: ProtocolRepository? = null,
        decryptLocked: Boolean = false,
        footnotes: List<memore.app.data.album.AlbumFootnote>? = null,
        recordToTickId: Map<String, String> = emptyMap(),
        /**
         * 关系映射：recordId → (出向[关联], 入向[被关联])。
         * 空 map 时两个字段都不写 → 没有关系的用户导出字节与改动前一致。
         */
        relationMap: Map<String, Pair<List<ExportedRelationOut>, List<ExportedRelationIn>>> = emptyMap(),
        /** 整体备份：额外写入 updated_at / enc_version（普通导出不写，保持输出不变）。 */
        fullBackup: Boolean = false
    ): ExportRecord {
        // 明文解密：对锁定记录在内存中解密 payload（仅导出，不改 DB）
        var decrypted: memore.app.utils.RecordPayload? = null
        // v5（2026-11-16）：没有密文可解。锁定记录的正文本来就在 record.content 里，
        // 是否把它写进包由调用方（整体备份 / 勾选包含）决定，见下面 content 的取值。
        if (decryptLocked && record.isLocked && !EncryptionManager.isV5()) {
            val key = EncryptionManager.getCachedKey() ?: throw Exception("请先输入加密密码")
            decrypted = EncryptionManager.unpackRecordPayload(
                EncryptionManager.decryptContent(record.encryptedData, record.iv, record.authTag, key)
            )
        }
        val tags = tagRepo.getTagsForRecord(record.id)
        val protocols = protocolRepo?.getProtocolsForRecord(record.id)
        val rawNodes: List<String>? = null
        val normalizedNodes = rawNodes?.map { MemoryNodeNormalizer.normalize(normalizePersonNames(it)) }
        val emotionalTagsRaw = decrypted?.emotionalTags ?: record.emotionalTags
        val emotionTags = try {
            val entries = GsonInstance.gson.fromJson(emotionalTagsRaw, Array<EmotionTagEntry>::class.java)
            entries?.map { it.copy(name = normalizePersonNames(it.name)) }?.toList()?.ifEmpty { null }
        } catch (e: Exception) {
            LogUtil.e("ExportEngine", "emotionTags object", e)
            try {
                val names = GsonInstance.gson.fromJson(emotionalTagsRaw, Array<String>::class.java)
                names?.map { EmotionTagEntry(normalizePersonNames(it)) }?.toList()?.ifEmpty { null }
            } catch (e2: Exception) { LogUtil.e("ExportEngine", "emotionTags string", e2); null }
        }
        val analysis = decrypted?.analysisJson?.let { json ->
            try { GsonInstance.gson.fromJson(json, AnalysisData::class.java) }
            catch (e: Exception) { LogUtil.e("ExportEngine", "analysis parse", e); null }
        } ?: record.analysis
        // lockedNow=true 表示仍以密文形式导出（未解密），需携带 encrypted_data/iv/auth_tag
        val lockedNow = record.isLocked && decrypted == null
        // 记录间双向关联（linked_ids）随导出携带，供外部 AI 按 id 串起记录
        val linkedIds: List<String>? = runCatching {
            GsonInstance.gson.fromJson(record.linkedIds, Array<String>::class.java)?.toList()?.ifEmpty { null }
        }.getOrNull()
        // 跨阶段连续性标记：linked_ids 中指向"不同刻度"的（跨阶段连接，保留但弱化）
        val ownTick = recordToTickId[record.id].orEmpty()
        val continuityLinks = linkedIds?.filter { targetId ->
            val t = recordToTickId[targetId]
            t != null && t.isNotEmpty() && t != ownTick
        }?.takeIf { it.isNotEmpty() }
        return ExportRecord(
            id = record.id, type = record.type.name, origin_id = record.originId,
            content = if (decrypted != null) normalizePersonNames(decrypted.content)
                      // v5：门闩式下锁定记录的正文就在 record.content（调用方已经决定是否把它放进导出范围）。
                      // 旧系统这里必须给空串，因为密文不能进包；现在没有密文，空串等于丢内容。
                      else if (record.isLocked && !memore.app.utils.EncryptionManager.isV5()) ""
                      else normalizePersonNames(record.content),
            dialogue_ai_content = when {
                decrypted != null && decrypted.dialogueAiContent.isNotBlank() -> normalizePersonNames(decrypted.dialogueAiContent)
                decrypted != null -> null
                record.isLocked -> null
                record.dialogueAiContent.isNotBlank() -> normalizePersonNames(record.dialogueAiContent)
                else -> null
            },
            dialogue_ai_reply = when {
                decrypted != null && decrypted.dialogueAiReply.isNotBlank() -> normalizePersonNames(decrypted.dialogueAiReply)
                decrypted != null -> null
                record.isLocked -> null
                record.dialogueAiReply.isNotBlank() -> normalizePersonNames(record.dialogueAiReply)
                else -> null
            },
            notes = if (decrypted != null) decrypted.notes.ifBlank { null } else if (record.isLocked) null else record.notes.ifBlank { null },
            tags = tags.map { MemoryNodeNormalizer.normalize(normalizePersonNames(it.name)) },
            protocols = protocols?.map { MemoryNodeNormalizer.normalize(normalizePersonNames(it.name)) }?.ifEmpty { null },
            timestamp = formatIsoZ(record.createdAt),
            starred = record.isAnchored, deleted = record.isDeleted,
            correction_of_id = record.correctionOfId,
            linked_ids = linkedIds,
            relations_out = relationMap[record.id]?.first?.takeIf { it.isNotEmpty() },
            relations_in = relationMap[record.id]?.second?.takeIf { it.isNotEmpty() },
            continuity_links = continuityLinks,
            ai_status = if (record.aiStatus == "NONE") null else record.aiStatus,
            emotional_tags = emotionTags, memory_nodes = normalizedNodes,
            meta = analysis?.meta?.let { m -> ExportMeta(m.model, m.created_at, m.analysis_version, m.prompt_version) } ?: ExportMeta(model = "", created_at = ""),
            relations = emptyList(),
            attachment_count = record.attachmentsCount,
            analysis = analysis,
            is_locked = record.isLocked && !decryptLocked,
            was_locked = record.isLocked && decryptLocked,
            encrypted_data = if (lockedNow && record.encryptedData.isNotBlank()) record.encryptedData else null,
            iv = if (lockedNow && record.iv.isNotBlank()) record.iv else null,
            auth_tag = if (lockedNow && record.authTag.isNotBlank()) record.authTag else null,
            album_footnotes = if (record.isLocked && !decryptLocked) null else footnotes?.takeIf { it.isNotEmpty() }?.map {
                ExportedAlbumFootnote(it.content, it.createdAt, it.updatedAt)
            },
            // 仅整体备份：修改时间 + 该记录的加密版本号
            updated_at = if (fullBackup) formatIsoZ(record.updatedAt) else null,
            enc_version = if (fullBackup) record.encVersion else null
        )
    }

    // ================= 批量导出（补充信息）专用精简路径 =================
    // 复用 buildExportRecord 的解密/归一逻辑，再映射为扁平去重的 ExportRecordLite。
    // 与主导出（ExportRecord / buildOrderedJson）完全独立。

    /** 完整导出记录 → 精简记录（去重：summary←analysis.summary，emotion←analysis.emotion）。 */
    private fun toLite(full: ExportRecord): ExportRecordLite = ExportRecordLite(
        id = full.id,
        type = full.type,
        timestamp = full.timestamp,
        content = full.content,
        dialogue_ai_reply = full.dialogue_ai_reply,
        notes = full.notes,
        tags = full.tags,
        protocols = full.protocols,
        linked_ids = full.linked_ids,
        // 关系（尤其"更正"）是判断"哪一版才是当前事实"的首要依据 —— 批量导出也不能丢
        relations_out = full.relations_out,
        relations_in = full.relations_in,
        correction_of_id = full.correction_of_id,
        summary = full.analysis?.summary?.takeIf { it.isNotBlank() } ?: full.dialogue_ai_content?.takeIf { it.isNotBlank() },
        memory_nodes = full.memory_nodes,
        emotion = full.analysis?.emotion?.takeIf { it.isNotEmpty() } ?: full.emotional_tags
    )

    /** 批量导出专用 JSON：只写 version / export_time / count / records。 */
    internal fun buildOrderedJsonLite(version: String, exportTime: String, count: Int, records: List<ExportRecordLite>): String {
        val obj = com.google.gson.JsonObject()
        obj.addProperty("version", version)
        obj.addProperty("export_time", exportTime)
        obj.addProperty("count", count)
        obj.add("records", gson.toJsonTree(records))
        return gson.toJson(obj)
    }

    /** 批量导出专用：脱敏一条精简记录（linked_ids 为 ID，不脱敏）。 */
    private fun redactRecordLite(record: ExportRecordLite, groups: List<RedactionGroup>, mode: String, redactName: (String) -> String): ExportRecordLite =
        record.copy(
            content = RedactionEngine.redact(record.content, groups, mode),
            dialogue_ai_reply = record.dialogue_ai_reply?.let { RedactionEngine.redact(it, groups, mode) },
            notes = record.notes?.let { RedactionEngine.redact(it, groups, mode) },
            tags = record.tags.map { redactName(it) },
            protocols = record.protocols?.map { redactName(it) },
            summary = record.summary?.let { RedactionEngine.redact(it, groups, mode) },
            memory_nodes = record.memory_nodes?.map { redactName(it) },
            emotion = record.emotion?.map { it.copy(name = redactName(it.name)) }
        )

    /** 脱敏组形态展开（与主导出一致：兼容"批量导出也遵守脱敏"）。 */
    private fun expandRedactionGroups(groups: List<RedactionGroup>): List<RedactionGroup> =
        groups.map { g ->
            val forms = listOf(g.standardName) + g.aliases
            val expanded = (forms + forms.map { normalizePersonNames(it) }).distinct().filter { it.isNotBlank() }
            g.copy(aliases = expanded.filter { it != g.standardName })
        }

    private fun saveToDownloads(context: Context, fileName: String, file: File, mime: String) {
        try {
            val cv = android.content.ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)
            if (uri != null) {
                resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
                cv.clear(); cv.put(MediaStore.Downloads.IS_PENDING, 0)
                resolver.update(uri, cv, null, null)
            } else {
                LogUtil.e("ExportEngine", "saveToDownloads", "insert returned null for $fileName")
            }
        } catch (e: Exception) { LogUtil.e("ExportEngine", "saveToDownloads", e) }
    }

    /**
     * 批量导出（时间线「批量选择 → 导出」）。
     *
     * 定位 = **即时向 AI 补充信息**，不是备份；因此只导记录本身，不导任何整体性块。
     * 输出：version / export_time / count / records（扁平精简记录，见 [ExportRecordLite]）。
     *
     * 锁定记录：调用方须先 `ensureKey`（输密码）成功后传 `decryptLocked = true`，导出明文。
     * 全量备份走 [export]（设置页「导出记忆包」），两者互不影响。
     */
    suspend fun exportSelected(context: Context, recordRepo: RecordRepository, tagRepo: TagRepository, ids: List<String>, protocolRepo: ProtocolRepository? = null,
                               startTs: Long? = null, endTs: Long? = null,
                               decryptLocked: Boolean = false,
                               redact: Boolean = false,
                               redactionGroups: List<RedactionGroup> = emptyList(),
                               redactionMode: String = RedactionEngine.MODE_RANDOM,
                               /** 「另存为…」用的输出流；传了就不落 cacheDir、不写 Download、不弹分享。 */
                               output: OutputStream? = null,
                               deliverToDownloads: Boolean = true,
                               deliverToShare: Boolean = true): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            // 批量导出（选中记录）通常很小，只报阶段名不定量即可
            reportProgress("正在导出选中记录…")
            if (ids.isEmpty()) { clearProgress(); return@withContext Result.failure(Exception("Not selected")) }
            var records = recordRepo.getAllActiveRecords().filter { it.id in ids }
            records = records.filter { r ->
                (startTs == null || r.createdAt >= startTs) && (endTs == null || r.createdAt <= endTs)
            }
            if (records.isEmpty()) { clearProgress(); return@withContext Result.failure(Exception("没有可导出的记录")) }
            val amends = try {
                recordRepo.getAmendmentsForRecords(records.map { it.id })
            } catch (e: Exception) {
                LogUtil.e("ExportEngine", "selected getAmendmentsForRecords", e); emptyList()
            }
            val allRecords = (records + amends).distinctBy { it.id }
            // 关系边：只带**两端都在包内**的（与「导出记忆包」的时间范围路径同一口径，
            // 不产生指向包外的悬空引用）。不带关系的话，AI 看不出"这条更正了那条"。
            val relationMap = try {
                buildRelationMap(recordRepo.allRelations(), allRecords.map { it.id }.toSet())
            } catch (e: Exception) {
                LogUtil.e("ExportEngine", "selected relationMap", e); emptyMap()
            }
            // 最新优先；复用 buildExportRecord 做解密/节点归一，再降为精简记录
            var liteList = allRecords
                .map { buildExportRecord(it, tagRepo, protocolRepo, decryptLocked, relationMap = relationMap) }
                .sortedByDescending { it.timestamp }
                .map { toLite(it) }
            if (redact && redactionGroups.isNotEmpty()) {
                val groups = expandRedactionGroups(redactionGroups)
                val nameMap = mutableMapOf<String, String>()
                val redactName: (String) -> String = { text ->
                    if (text.isEmpty() || !isRedactable(text, groups)) text
                    else nameMap.getOrPut(text) { RedactionEngine.redact(text, groups, redactionMode) }
                }
                liteList = liteList.map { redactRecordLite(it, groups, redactionMode, redactName) }
            }
            val jsonString = buildOrderedJsonLite(
                version = "2.5", exportTime = formatIsoZ(), count = liteList.size, records = liteList
            )
            val payload = jsonString.toByteArray(Charsets.UTF_8)
            if (output != null) {
                output.use { it.write(payload) }
            } else {
                val fileName = "memory_selected_${System.currentTimeMillis()}.json"
                val cacheFile = File(context.cacheDir, fileName)
                FileOutputStream(cacheFile).use { it.write(payload) }
                if (deliverToDownloads) saveToDownloads(context, fileName, cacheFile, "application/json")
                if (deliverToShare) shareFile(context, cacheFile, "application/json", "Share")
                // 交付后删掉 cache 副本（分享时留给启动清理）
                if (!deliverToShare) runCatching { cacheFile.delete() }
            }
            clearProgress()
            Result.success(Unit)
        } catch (e: Exception) {
            LogUtil.e("ExportEngine", "exportSelected failed", e)
            clearProgress()
            Result.failure(e)
        }
    }

    private fun shareFile(context: Context, file: File, mime: String, title: String) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = mime; putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }, title).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
    }
}




