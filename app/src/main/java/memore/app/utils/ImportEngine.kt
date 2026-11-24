package memore.app.utils

import android.content.Context
import android.net.Uri
import com.google.gson.GsonBuilder
import memore.app.data.model.AliasGroup
import memore.app.data.model.AnalysisData
import memore.app.data.model.Dial
import memore.app.data.model.Record
import memore.app.data.model.RecordType
import memore.app.data.model.RedactionGroup
import memore.app.data.model.SliceEntry
import memore.app.data.model.Tick
import memore.app.data.model.TickSlice
import memore.app.data.repository.AliasMappingRepository
import memore.app.data.repository.DialRepository
import memore.app.data.repository.ProtocolRepository
import memore.app.data.repository.RecordRepository
import memore.app.data.repository.TagRepository
import memore.app.data.repository.TagGroupRepository
import memore.app.data.repository.TickRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.zip.ZipInputStream

data class ImportPreview(
    val totalCount: Int,
    val timeRange: Pair<String, String>,
    val newCount: Int,
    val existingIds: Set<String>,
    /** 整体备份包的提示（schema 版本不一致等）；为空表示无话可说。 */
    val backupNote: String? = null,
    /**
     * 包里"**带真密文且被锁定**"的记录条数。
     * 本机已有密码且它 > 0 时，需要一个"那台设备的凭据"才能把这批记录接管过来（否则只能跳过）。
     */
    val packageLockedCount: Int = 0,
    /** 包里的加密配置（已转成本地实体形状）；null = 包里没带。用于校验用户输入的旧凭据。 */
    val packageEncryptionConfig: memore.app.data.model.EncryptionConfig? = null,
    /** 包里带的草稿本数 / 块数（0 = 这个包没有草稿块）。 */
    val packageDraftCount: Int = 0,
    val packageDraftBlockCount: Int = 0
)

data class ImportResult(
    val imported: Int,
    val skipped: Int,
    val systemInstruction: String? = null,
    val promptsImported: Boolean = false,
    val hasEncryptionConflict: Boolean = false,
    val damagedLockedCount: Int = 0,
    val skippedLockedCount: Int = 0,
    /** 本次真正还原的草稿本数（空本已剔除）。 */
    val draftsImported: Int = 0,
    /** 因为"同名 + 同创建时间"已在本机而跳过的本数。 */
    val draftsSkipped: Int = 0
)

object ImportEngine {

    private val gson = GsonBuilder().setPrettyPrinting().create()

    // ---- 进度上报（纯观察，不参与任何业务判断） ----

    private val _progress = kotlinx.coroutines.flow.MutableStateFlow<OpProgress?>(null)

    /** 当前导入进度；为 null 表示没有导入在跑。UI 直接 collectAsState 即可。 */
    val progress: kotlinx.coroutines.flow.StateFlow<OpProgress?> = _progress

    private fun reportProgress(phase: String, done: Int = 0, total: Int = 0, detail: String? = null) {
        _progress.value = OpProgress(phase, done, total, detail)
    }

    private fun clearProgress() { _progress.value = null }

    /** 取 SAF uri 的字节大小（拿不到就返回 null → 那段进度只能不定量）。 */
    private fun sizeOfUri(context: Context, uri: Uri): Long? = try {
        context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.SIZE), null, null, null)
            ?.use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null }
    } catch (_: Exception) { null }

    /**
     * 统计已读字节的包装流，仅用于给"还原文件"这段算进度。
     *
     * 按总量的 1%（下限 64 KB）**节流上报** —— 8 KB 一块地读 267 MB 会触发几万次回调，
     * 每次都写一次 StateFlow 会把 UI 刷爆。
     */
    private class CountingStream(
        private val delegate: java.io.InputStream,
        private val totalBytes: Long,
        private val onProgress: (Long) -> Unit
    ) : java.io.InputStream() {
        private var count = 0L
        private var lastReported = 0L
        private val stride = (totalBytes / 100).coerceAtLeast(64L * 1024L)

        private fun bump(n: Int) {
            count += n
            if (count - lastReported >= stride) { lastReported = count; onProgress(count) }
        }

        override fun read(): Int {
            val b = delegate.read(); if (b >= 0) bump(1); return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val n = delegate.read(b, off, len); if (n > 0) bump(n); return n
        }

        override fun close() { delegate.close() }
        override fun available(): Int = delegate.available()
    }

    /**
     * 整体备份包的说明文字（预览时展示）。
     * 主要目的是 DB schema 版本校验：旧包还原到新库、或新包还原到旧库时，必须让用户知道。
     */
    internal fun backupNote(data: ImportData): String? {
        val meta = data.backup_meta ?: return null
        val local = memore.app.data.local.APP_DB_VERSION
        val head = buildString {
            append("整体备份包（App ${meta.app_version.ifBlank { "?" }}）\n")
            append("记录 ${meta.record_count} 条")
            if (meta.trash_count > 0) append("、回收站 ${meta.trash_count} 条")
            if (meta.version_count > 0) append("、历史版本 ${meta.version_count} 条")
            if (meta.draft_count > 0) append("、草稿 ${meta.draft_count} 本")
        }
        return when {
            meta.db_version == local -> head
            meta.db_version > local ->
                "$head\n⚠ 该包来自更新的数据库版本（v${meta.db_version}，本机 v$local），可能有本机认不出的数据被忽略。"
            else ->
                "$head\n⚠ 该包来自更旧的数据库版本（v${meta.db_version}，本机 v$local），导入时按当前版本规则还原。"
        }
    }

    /** 迁移/初始化标记类偏好键：导出带、导入不写，否则新机会跳过必要的初始化。 */
    internal fun isMigrationMarkerKey(key: String): Boolean =
        key.endsWith("_seeded") || key.contains("_migration_")

    /**
     * 纯函数（可测）：包里哪些草稿该还原。
     *
     * 规则（用户 2026-10-11 拍板）：
     * - **空本不还原**（一个块都没有）—— 本机也有"空草稿不许堆"的规矩；
     * - **同名 + 同创建时间** = 同一本 → 跳过，反复导入不会堆重复。
     *
     * @return (要还原的, 跳过的)
     */
    internal fun draftsToRestore(
        pkg: List<ExportedDraft>,
        existing: Set<Pair<String, Long>>
    ): Pair<List<ExportedDraft>, List<ExportedDraft>> {
        val new = mutableListOf<ExportedDraft>()
        val skipped = mutableListOf<ExportedDraft>()
        pkg.forEach { d ->
            val hasContent = d.blocks.any { it.content.isNotBlank() }
            when {
                !hasContent -> Unit                                   // 空本：静默丢掉，不算"跳过"
                (d.name to d.createdAt) in existing -> skipped += d
                else -> new += d
            }
        }
        return new to skipped
    }

    /**
     * 带凭据的偏好键：导入时**本机已有密钥就不覆盖**。
     * - `ai_api_key`：单键密钥。
     * - `api_presets`：复合 JSON，各预设的端点/模型/密钥打包在一起，密钥藏在里面。
     */
    internal fun isCredentialPrefKey(key: String): Boolean =
        key == "ai_api_key" || key == "api_presets"

    /**
     * 纯函数：api_presets 的 JSON 原文里是否存在非空密钥。
     * 用于判断"本机是否已经配好密钥"——新机（预设密钥全空）允许整体恢复，已配好的机器不许被覆盖。
     * 读不动（格式异常）时返回 true，宁可不动本机配置。
     */
    internal fun apiPresetsHaveKey(raw: String?): Boolean {
        if (raw.isNullOrBlank()) return false
        return try {
            val arr = gson.fromJson(raw, com.google.gson.JsonArray::class.java)
            arr?.any { el ->
                (el as? com.google.gson.JsonObject)?.get("apiKey")?.asString?.isNotBlank() == true
            } ?: false
        } catch (_: Exception) {
            true
        }
    }

    /** 本机在该键上是否已经存有可用凭据。 */
    private fun hasLocalCredential(prefs: android.content.SharedPreferences, key: String): Boolean = when (key) {
        "ai_api_key" -> !prefs.getString(key, "").isNullOrEmpty()
        "api_presets" -> apiPresetsHaveKey(prefs.getString(key, null))
        else -> false
    }

    data class ImportData(
        val system_instruction: String? = null,
        val version: String = "",
        val export_time: String = "",
        val count: Int = 0,
        val rejected_merges: List<ImportRejectedMerge>? = null,
        val alias_groups: List<AliasGroup>? = null,
        val prompts: Map<String, String> = emptyMap(),
        val import_enabled: Boolean = true,
        val records: List<ImportRecord> = emptyList(),
        val ticks: List<ImportTick>? = null,
        /** 表盘（一段时期的框，包络刻度）：老包没有这个键 → null → 整段跳过。 */
        val dials: List<ImportDial>? = null,
        val node_hierarchy: Map<String, List<String>>? = null,
        val tag_hierarchy: Map<String, List<String>>? = null,
        val encryption_config: memore.app.utils.ExportEncryptionConfig? = null,
        // ===== 整体备份块：旧包 / 普通包一律为 null → 全部跳过，行为与旧版一致 =====
        val backup_meta: BackupMeta? = null,
        val versions: List<ExportedVersion>? = null,
        val trash: List<ImportRecord>? = null,
        val album_state: ExportedAlbumState? = null,
        val sounds: Map<String, List<String>>? = null,
        val settings: Map<String, Map<String, PrefEntry>>? = null,
        val attachments_index: Map<String, List<ExportedAttachment>>? = null,
        /** 脱敏映射组（整体备份随包） */
        val redaction_groups: List<RedactionGroup>? = null,
        /**
         * 真草稿（多本 + 块）。老包 / 没勾「草稿」的包没有这个键 → null → 整段跳过。
         */
        val drafts: List<ExportedDraft>? = null,
        // ===== 2026-11-16 v2：以前导出漏掉的三块（老包没有这些键 → null → 跳过）=====
        val zones: List<ExportedZone>? = null,
        val origins: List<ExportedOrigin>? = null,
        val concept_categories: List<ExportedConceptCategory>? = null,
        val manifest: ExportManifest? = null,
        // ===== 2026-11-20：新结构（老包没有 → null → 跳过） =====
        val anchors: List<ExportedAnchor>? = null,
        val analysis_versions: List<ExportedAnalysisVersion>? = null,
        val agent_actions: List<ExportedAgentAction>? = null,
        val agent_commands: List<ExportedAgentCommand>? = null
    )

    /** 导入的音乐库条目（元数据 + zip 相对路径，文件本体随包还原）。 */

    /** 导入的刻度（还原 tick 本体 + 定格快照）。 */
    data class ImportTick(
        val id: String,
        val name: String,
        val start: String,
        val end: String? = null,
        val weight: Double = 0.0,
        val record_count: Int = 0,
        /** 刻度总结（后记） */
        val summary: String? = null,
        /** 刻度回看（与总结分开） */
        val review: String? = null,
        val emotion: List<ImportSliceEntry>? = null,
        val nodes: List<ImportSliceEntry>? = null
    )

    data class ImportSliceEntry(val name: String? = null, val weight: Double = 0.0, val count: Int = 0)

    /** 导入的表盘：一段时期 + 它包着的刻度 id（多父，所以关系在表盘这边）。 */
    data class ImportDial(
        val name: String,
        val start: String,
        val end: String? = null,
        val summary: String? = null,
        val tick_ids: List<String> = emptyList()
    )

    data class ImportRejectedMerge(
        val keep: String,
        val remove: String,
        val rejected_at: Long
    )

    data class ImportedAlbumFootnote(
        val content: String,
        val created_at: Long,
        val updated_at: Long? = null
    )

    data class ImportRecord(
        val id: String,
        val type: String = "ESSAY",
        val content: String? = null,
        val dialogue_ai_content: String? = null,
        val dialogue_ai_reply: String? = null,
        val notes: String? = null,
        /** 来源 id（2026-11-12 补）。旧包没有这个键 → null = 未标。 */
        val origin_id: String? = null,
        val tags: List<String>? = null,
        val protocols: List<String>? = null,
        val timestamp: String? = null,
        val starred: Boolean = false,
        val deleted: Boolean = false,
        val correction_of_id: String? = null,
        val linked_ids: List<String>? = null,
        /** 关联（内联）：本条「type」→ to_id。导入时据此重建有向关系边。 */
        val relations_out: List<ExportedRelationOut>? = null,
        val ai_status: String? = null,
        val emotional_tags: Any? = null,
        val memory_nodes: List<String>? = null,
        val meta: Any? = null,
        val attachment_count: Int? = null,
        val analysis: Any? = null,
        val is_locked: Boolean = false,
        val was_locked: Boolean = false,
        val encrypted_data: String? = null,
        val iv: String? = null,
        val auth_tag: String? = null,
        val album_footnotes: List<ImportedAlbumFootnote>? = null,
        /** 仅整体备份包有：修改时间（普通包为 null → 用创建时间兜底）。 */
        val updated_at: String? = null,
        /** 仅整体备份包有：该记录的加密版本号（普通包为 null → 用默认 1）。 */
        val enc_version: Int? = null
    )

    /**
     * 把包里的加密配置块转成本地实体形状。
     *
     * ⚠️ 每个字段都要 `?:`：Gson 用 Unsafe 绕过构造器，**早期导出的包缺少后加的键**
     * （如 `question_salt`）时字段会是 null，直接传给非空参数就抛 NPE。
     * 抽成一个函数是为了让"预览"与"真正导入"两条路用同一份映射，不会漂移。
     */
    private fun toLocalConfig(c: ExportEncryptionConfig): memore.app.data.model.EncryptionConfig =
        memore.app.data.model.EncryptionConfig(
            enabled = true,
            iterations = c.key_iterations,
            passwordSalt = c.password_salt ?: "",
            passwordHash = c.password_hash ?: "",
            keySalt = c.key_salt ?: "",
            question = c.question ?: "",
            questionHash = c.question_hash ?: "",
            questionSalt = c.question_salt ?: "",
            // 缺键时 Gson 留下 0（Unsafe 清零）→ 按 v1（hex 盐）语义处理，与老包相符
            encVersion = c.enc_version.coerceAtLeast(1),
            answerSalt = c.answer_salt ?: "",
            answerHash = c.answer_hash ?: "",
            wrappedKey = c.wrapped_key ?: "",
            wrappedKeyPw = c.wrapped_key_pw ?: ""
        )

    suspend fun preview(context: Context, uri: Uri): Result<ImportPreview> = withContext(Dispatchers.IO) {
        try {
            val jsonText = readJsonFromUri(context, uri)
            if (jsonText.contains(DAMAGE_MARKER)) {
                damageBlockedFile(context, uri)
                return@withContext Result.failure(Exception("此文件已被标记为受损，禁止导入"))
            }
            val data = gson.fromJson(jsonText, ImportData::class.java)
            if (!data.import_enabled) {
                damageBlockedFile(context, uri)
                return@withContext Result.failure(Exception("此文件被标记为禁止导入"))
            }
            val records = data.records
            if (records.isEmpty()) return@withContext Result.failure(Exception("文件中没有记录"))

            val timestamps = records.mapNotNull { r ->
                parseIsoZ(r.timestamp ?: "")
            }.sorted()
            val timeRange = if (timestamps.size >= 2) {
                formatDateOnly(timestamps.first()) to formatDateOnly(timestamps.last())
            } else if (timestamps.size == 1) {
                formatDateOnly(timestamps.first()) to formatDateOnly(timestamps.first())
            } else "?" to "?"

            Result.success(ImportPreview(
                totalCount = records.size,
                timeRange = timeRange,
                newCount = records.size,
                existingIds = emptySet(),
                backupNote = backupNote(data),
                packageLockedCount = records.count { it.is_locked && !it.encrypted_data.isNullOrEmpty() },
                packageEncryptionConfig = data.encryption_config?.let { toLocalConfig(it) },
                packageDraftCount = data.drafts?.count { d -> d.blocks.any { it.content.isNotBlank() } } ?: 0,
                packageDraftBlockCount = data.drafts?.sumOf { d -> d.blocks.count { it.content.isNotBlank() } } ?: 0
            ))
        } catch (e: Exception) {
            Result.failure(Exception("解析失败: ${e.message}"))
        }
    }

    suspend fun execute(
        context: Context,
        uri: Uri,
        recordRepo: RecordRepository,
        tagRepo: TagRepository,
        protocolRepo: ProtocolRepository? = null,
        aliasMappingRepo: AliasMappingRepository? = null,
        encryptionConfigDao: memore.app.data.local.dao.EncryptionConfigDao? = null,
        tickRepo: TickRepository? = null,
        tagGroupRepository: TagGroupRepository? = null,
        redactionRepository: memore.app.data.repository.RedactionRepository? = null,
        /** 真草稿仓库：包里带 drafts 块时用来还原（null = 不还原草稿）。 */
        draftRepository: memore.app.data.repository.DraftRepository? = null,
        // 2026-11-16 v2：以前导出漏掉的三块，导入端在这里还原
        zoneDao: memore.app.data.local.dao.ZoneDao? = null,
        originDao: memore.app.data.local.dao.OriginDao? = null,
        /**
         * 本机已有密码时：用"**那台设备**"的密钥，把包里那些外来密文记录解开后接管过来。
         * 传 null = 无法接管 → 那批记录仍然跳过（旧行为）。
         */
        alienKey: javax.crypto.SecretKey? = null,
        /** 表盘（独立的一层）：包里带 dials 时用来还原框与关联。声明在末尾避免打乱位置参数。 */
        dialRepository: DialRepository? = null,
        /** 直接读写锚点/分析版本/agent 历史（为 null 时这几块跳过）。 */
        database: memore.app.data.local.AppDatabase? = null
    ): Result<ImportResult> = withContext(Dispatchers.IO) {
        val importedIds = mutableListOf<String>()
        /** 本次还原出来的草稿本 id（失败回滚时要连本带块一起删掉）。 */
        val importedDraftIds = mutableListOf<Long>()
        val copiedAttachments = mutableListOf<java.io.File>()
        val copiedVoiceFiles = mutableListOf<java.io.File>()
        val copiedSounds = mutableListOf<java.io.File>()
        /** 2026-11-16：files 里的账本（ai_runs / insights），随整体备份还原。 */
        val copiedMetaFiles = mutableListOf<java.io.File>()
        /** 落地的自定义提示音：文件名 → 新绝对路径（用于把包里的旧路径改写成本机路径）。 */
        val soundPathByBase = mutableMapOf<String, String>()
        suspend fun rollbackImported() {
            importedIds.forEach { id ->
                try { recordRepo.deleteAllAttachments(id) } catch (_: Exception) {}
                try { recordRepo.permanentlyDeleteRecord(id) } catch (_: Exception) {}
            }
            copiedAttachments.forEach { f -> try { if (f.exists()) f.delete() } catch (_: Exception) {} }
            copiedVoiceFiles.forEach { f -> try { if (f.exists()) f.delete() } catch (_: Exception) {} }
            copiedSounds.forEach { f -> try { if (f.exists()) f.delete() } catch (_: Exception) {} }
            // 草稿：连本带块一并撤掉（"失败即终止、不留半截"）
            importedDraftIds.forEach { id -> try { draftRepository?.deleteDraft(id) } catch (_: Exception) {} }
            try { java.io.File(context.filesDir, "attachments_tmp").deleteRecursively() } catch (_: Exception) {}
        }
        try {
            val jsonText = readJsonFromUri(context, uri)
            if (jsonText.contains(DAMAGE_MARKER)) {
                damageBlockedFile(context, uri)
                return@withContext Result.failure(Exception("此文件已被标记为受损，禁止导入"))
            }
            val data = gson.fromJson(jsonText, ImportData::class.java)
            if (!data.import_enabled) {
                damageBlockedFile(context, uri)
                return@withContext Result.failure(Exception("此文件被标记为禁止导入"))
            }
            val records = data.records
            // 整体备份把回收站记录单独放在 trash[]；一起还原（回收站记录 deleted=true，还原后仍在回收站）
            val allRecords = records + (data.trash ?: emptyList())
            if (allRecords.isEmpty()) return@withContext Result.failure(Exception("文件中没有记录"))

            // 预判加密配置冲突：本地已有密码 且 导入包带加密配置 → 导入的锁定记录无法用本机密码解密 → 跳过并提示
            val encConflict = data.encryption_config != null && encryptionConfigDao != null &&
                (try { encryptionConfigDao.get() != null } catch (_: Exception) { false })
            // 2026-11-16：v5「门闩式」包 —— 锁定记录的正文就在 content 里，**没有密文可解**。
            // 旧逻辑把"is_locked=1"一律当成"外来密文"，于是把整批锁定记录当作打不开的数据丢掉
            // （新机导入时用户看到的就是「N 条锁定记录因密码不同未导入」）。这里按包的版本分流。
            val pkgIsGate = data.encryption_config?.enc_version == 5
            var skippedLocked = 0

            var imported = 0
            var skipped = 0
            // 进度：这一个阶段是可以精确计数的（按条数）
            reportProgress("正在导入记录…", 0, allRecords.size)

            allRecords.forEach { r ->
                try {
                    val existingRecord = recordRepo.getRecordById(r.id)
                    if (existingRecord != null) {
                        skipped++
                        return@forEach
                    }
                    // 加密配置冲突：外来锁定记录用**本机密码打不开**。
                    // 若用户提供了"那台设备的凭据"（alienKey），就用它把密文**解开**、按「待真锁」导入
                    // （明文 + is_locked=1、密文留空）—— 随后由本机密钥补锁（lockPendingEncryptionRecords），
                    // 于是这批记录最终就用**本机密码**读了。这正是"已有密码那就用新密码"的实现。
                    // 拿不到 alienKey 时退回旧行为：跳过，绝不导成打不开的假数据。
                    var adoptedPayload: memore.app.utils.RecordPayload? = null
                    // 只有"旧式密文包"才需要接管逻辑；v5 包里的锁定记录本来就是明文，直接照搬。
                    if (!pkgIsGate && encConflict && r.is_locked && !r.encrypted_data.isNullOrEmpty()) {
                        val cipher = r.encrypted_data
                        if (alienKey != null && !cipher.isNullOrEmpty()) {
                            adoptedPayload = memore.app.utils.EncryptionManager.unpackRecordPayload(
                                memore.app.utils.EncryptionManager.decryptContent(
                                    cipher, r.iv ?: "", r.auth_tag ?: "", alienKey
                                )
                            )
                        } else {
                            skipped++
                            skippedLocked++
                            return@forEach
                        }
                    }

                    val recordType = try { RecordType.valueOf(r.type) } catch (_: Exception) { RecordType.ESSAY }
                    val createdAt = parseIsoZ(r.timestamp ?: "") ?: System.currentTimeMillis()

                    val emotionalTagsJson = when (val et = r.emotional_tags) {
                        is List<*> -> gson.toJson(et)
                        is String -> et
                        else -> "[]"
                    }

                    val analysisObj: AnalysisData? = try {
                        if (r.analysis != null) gson.fromJson(gson.toJson(r.analysis), AnalysisData::class.java) else null
                    } catch (e: Exception) { LogUtil.e("ImportEngine", "analysis parse", e); null }

                    // 三种落库形态：
                    //  ① adoptedPayload != null：外来密文已用旧设备密钥解出 → 按「待真锁」（明文 + 锁标记）入库
                    //  ② r.was_locked：包里本来就是明文 + 曾锁定标记 → 同上
                    //  ③ 其余：普通记录（有密文的原样搬，明文照搬）
                    //
                    // 2026-11-16（v5）：只有"**锁着、有密文、却没明文**"才是真正的坏数据；
                    // v5 包是"锁着 + 明文 + 无密文"，绝不能清空 —— 那等于把记录偷走。
                    val adopted = adoptedPayload
                    val plaintextLocked = adopted != null || r.was_locked
                    val blankBecauseCipher = !pkgIsGate && adopted == null && !r.was_locked && r.is_locked &&
                        r.content.isNullOrEmpty() && !r.encrypted_data.isNullOrEmpty()

                    val record = Record(
                        id = r.id,
                        type = recordType,
                        content = if (blankBecauseCipher) "" else (adopted?.content ?: r.content ?: ""),
                        dialogueAiContent = if (blankBecauseCipher) "" else (adopted?.dialogueAiContent ?: r.dialogue_ai_content ?: ""),
                        dialogueAiReply = if (blankBecauseCipher) "" else (adopted?.dialogueAiReply ?: r.dialogue_ai_reply ?: ""),
                        notes = if (blankBecauseCipher) "" else (adopted?.notes ?: r.notes ?: ""),
                        originId = r.origin_id,
                        createdAt = createdAt,
                        updatedAt = parseIsoZ(r.updated_at ?: "") ?: createdAt,
                        isAnchored = r.starred,
                        isDeleted = r.deleted,
                        correctionOfId = r.correction_of_id,
                        linkedIds = r.linked_ids?.let { gson.toJson(it) } ?: "",
                        aiStatus = r.ai_status ?: "NONE",
                        emotionalTags = if (adopted != null) adopted.emotionalTags else emotionalTagsJson,
                        analysis = if (adopted != null) {
                            adopted.analysisJson?.let {
                                try { gson.fromJson(it, AnalysisData::class.java) } catch (e: Exception) {
                                    LogUtil.e("ImportEngine", "adopted analysis parse", e); null
                                }
                            }
                        } else analysisObj,
                        attachmentsCount = 0,
                        isLocked = r.is_locked || r.was_locked,
                        encVersion = r.enc_version ?: 1,
                        encryptedData = if (plaintextLocked) "" else (r.encrypted_data ?: ""),
                        iv = if (plaintextLocked) "" else (r.iv ?: ""),
                        authTag = if (plaintextLocked) "" else (r.auth_tag ?: "")
                    )

                    recordRepo.addRecord(record).getOrThrow()
                    importedIds.add(r.id)

                    (r.tags ?: emptyList()).forEach { tagName ->
                        // 缺键时 Gson 会留下 null（Kotlin 的默认值**不会**生效 —— 它绕过构造器），
                        // 所以这里必须兜底：否则整个导入会可见地失败并回滚（不丢数据，但白折腾一趟）。
                        val tag = tagRepo.getOrCreateTag(tagName)
                        tagRepo.addTagToRecord(r.id, tag.id)
                    }

                    r.protocols?.forEach { protocolName ->
                        if (protocolRepo != null) {
                            val protocol = protocolRepo.getOrCreateProtocol(protocolName)
                            protocolRepo.addProtocolToRecord(r.id, protocol.id)
                        }
                    }

                    imported++
                    reportProgress("正在导入记录…", imported, allRecords.size)
                } catch (e: Exception) {
                    rollbackImported()
                    clearProgress()
                    return@withContext Result.failure(Exception("导入失败: ${e.message}"))
                }
            }

            try { protocolRepo?.refreshAllCounts() } catch (e: Exception) { LogUtil.e("ImportEngine", "refreshAllCounts protocol", e) }
            try { tagRepo.refreshAllCounts() } catch (e: Exception) { LogUtil.e("ImportEngine", "refreshAllCounts tag", e) }

            // 回锁修正：导入的"待真锁"记录（decrypt 导出的曾锁定记录），若本机密钥已在缓存（已设密码），立即用本机密码真正加密
            // 2026-11-16（v5）：本机是门闩式时**绝不能**再补锁 —— 那会把明文重新变成密文，
            // 而 v5 的 UI 根本不解密文，结果是记录再也读不出来。
            try {
                if (!memore.app.utils.EncryptionManager.isV5()) {
                    val k = memore.app.utils.EncryptionManager.getCachedKey()
                    if (k != null) recordRepo.lockPendingEncryptionRecords(k)
                }
            } catch (_: Exception) {}

            // 还原刻度（含定格快照）：终结刻度 frozenAt=endMs，若导出带 emotion/nodes 则还原切片，否则由 app 重新定格。
            data.ticks?.forEach { t ->
                try {
                    val startMs = parseIsoZ(t.start) ?: return@forEach
                    val endMs = t.end?.let { parseIsoZ(it) }
                    val sliceSnapshot = if (!t.emotion.isNullOrEmpty() || !t.nodes.isNullOrEmpty()) {
                        gson.toJson(TickSlice(
                            frozenAt = endMs ?: System.currentTimeMillis(),
                            emotion = (t.emotion ?: emptyList()).map { SliceEntry(it.name ?: "", it.weight, it.count) },
                            nodes = (t.nodes ?: emptyList()).map { SliceEntry(it.name ?: "", it.weight, it.count) }
                        ))
                    } else null
                    tickRepo?.importTick(Tick(
                        id = t.id, name = t.name,
                        startMs = startMs, endMs = endMs,
                        frozenAt = endMs, sliceSnapshot = sliceSnapshot,
                        summary = t.summary.orEmpty(),
                        review = t.review.orEmpty(),
                        createdAt = startMs, updatedAt = startMs
                    ))
                    // 终结刻度若导出未带切片内容，用导入的记录重新定格，保证还原后也是"定格"。
                    if (endMs != null && sliceSnapshot == null) {
                        try { tickRepo?.refreshSlice(t.id) } catch (e: Exception) { LogUtil.e("ImportEngine", "tick refreeze", e) }
                    }
                } catch (e: Exception) { LogUtil.e("ImportEngine", "tick import", e) }
            }

            // 还原表盘（一段时期的框）：id 冲突则忽略；关联按 tick_id 挂回去（刻度 id 导入时是保留的）。
            data.dials?.forEach { d ->
                try {
                    val startMs = parseIsoZ(d.start) ?: return@forEach
                    val endMs = d.end?.let { parseIsoZ(it) }
                    val dialId = java.util.UUID.randomUUID().toString()
                    dialRepository?.importDial(Dial(
                        id = dialId, name = d.name,
                        startMs = startMs, endMs = endMs,
                        summary = d.summary.orEmpty(),
                        createdAt = startMs, updatedAt = startMs
                    ))
                    d.tick_ids.forEach { tid -> try { dialRepository?.linkTick(dialId, tid) } catch (_: Exception) {} }
                } catch (e: Exception) { LogUtil.e("ImportEngine", "dial import", e) }
            }


            val importedTagNames = records.flatMap { it.tags ?: emptyList() }.toSet()
            // 还原标签组归属（tag_hierarchy → 建组 + 成员标签入组）
            data.tag_hierarchy?.forEach { (groupName, memberNames) ->
                try {
                    if (tagGroupRepository != null && groupName.isNotBlank()) {
                        val group = tagGroupRepository.create(groupName)
                        memberNames.forEach { memberName ->
                            if (memberName.isNotBlank() && memberName in importedTagNames) {
                                val tag = tagRepo.getOrCreateTag(memberName)
                                tagGroupRepository.addMember(group.id, tag.id)
                            }
                        }
                    }
                } catch (e: Exception) { LogUtil.e("ImportEngine", "tag_hierarchy import", e) }
            }

            // ===== 2026-11-16 v2：还原以前漏掉的三块 =====
            // 只还原"包里确实有成员/引用"的东西，凭空造空的来源/分类没有意义。
            // 2026-11-16：来源必须**全字段**还原（三个权重 + 排序 + 创建时间）。
            // 只写 name/wLink 的后果是真机上的"来源系统完全变了"：排序回到默认、聚类与统计权重归 1。
            // 本机已有同 id 时不覆盖（避免二次导入把本机手改的权重冲掉）。
            data.origins?.forEach { o ->
                try {
                    val dao = originDao ?: return@forEach
                    val exists = runCatching { dao.byId(o.id) }.getOrNull() != null
                    if (!exists) {
                        dao.upsert(memore.app.data.model.Origin(
                            id = o.id, name = o.name, builtin = o.builtin,
                            wLink = o.w_link, wCluster = o.w_cluster, wStats = o.w_stats, linkThreshold = o.link_threshold,
                            sortOrder = o.sort_order,
                            createdAt = if (o.created_at > 0) o.created_at else System.currentTimeMillis()
                        ))
                    }
                } catch (e: Exception) { LogUtil.e("ImportEngine", "origin import", e) }
            }
            // 2026-11-16（用户定的规则「同名就合并」）：分类按**名字**去重，而不是按 id 硬插。
            // 原来无条件 insert：同名分类会留下两条，反复导入越积越多。
            data.zones?.forEach { z ->
                try {
                    val dao = zoneDao ?: return@forEach
                    // 内置区（锚点/AI 札记）由本机自建，不随包覆盖
                    val zoneExists = runCatching { dao.all().any { it.id == z.id } }.getOrDefault(false)
                    if (!z.builtin && !zoneExists) {
                        dao.upsert(memore.app.data.model.Zone(
                            id = z.id, name = z.name, condition = z.condition, builtin = false
                        ))
                    }
                    // 成员：只挂"本机确实存在"的记录，且已经在区里的不重复加（二次导入幂等）
                    val current = runCatching { dao.memberIds(z.id).toSet() }.getOrDefault(emptySet())
                    z.member_ids.filter { it !in current && recordRepo.getRecordById(it) != null }.forEach { rid ->
                        dao.addMember(memore.app.data.model.ZoneRecord(zoneId = z.id, recordId = rid))
                    }
                } catch (e: Exception) { LogUtil.e("ImportEngine", "zone import", e) }
            }


            data.alias_groups?.let { groups ->
                val existing = aliasMappingRepo?.load()?.toMutableList() ?: mutableListOf()
                val existingIds = existing.map { it.id }.toSet()
                groups.filter { it.id !in existingIds }.forEach { existing.add(it) }
                aliasMappingRepo?.save(existing)
            }

            // Restore attachments from ZIP
            try {
                if (isZipSource(context, uri)) {
                    // 进度：这一段按**已读字节**报。ZIP 是流式读的（拿不到条目总数），
                    // 但"读了多少字节"是最诚实的进度 —— 而且拿不到文件大小就退回不定量。
                    val totalBytes = sizeOfUri(context, uri)
                    val rawStream = context.contentResolver.openInputStream(uri)
                        ?: throw Exception("无法打开文件")
                    val stream: java.io.InputStream = if (totalBytes != null && totalBytes > 0) {
                        CountingStream(rawStream, totalBytes) { read ->
                            val readKb = (read / 1024).toInt()
                            val totalKb = (totalBytes / 1024).toInt()
                            reportProgress(
                                "正在还原附件与音乐…", readKb, totalKb,
                                detail = "已还原 ${readKb / 1024} MB / ${totalKb / 1024} MB"
                            )
                        }
                    } else {
                        reportProgress("正在还原附件与音乐…")
                        rawStream
                    }
                    val zis = java.util.zip.ZipInputStream(stream)
                    while (true) {
                        val entry = zis.nextEntry ?: break
                        val entryName = entry.name
                        if (entryName.startsWith("attachments/")) {
                            val parts = entryName.split("/".toRegex(), 3)
                            if (parts.size >= 3) {
                                val recordId = parts[1]
                                val fileName = parts[2]
                                // 只还原"本机确实存在这条记录"的附件。
                                // 原来判断的是"包里有这条记录" —— 于是「本机已有密码」时被跳过的那些锁定记录，
                                // 其附件文件照样会被拷进来、并写一条指向不存在记录的附件行，成为**孤儿附件**：
                                // 白占磁盘、无任何用途（相册走 INNER JOIN records，界面上也看不到）。
                                if (recordRepo.getRecordById(recordId) != null) {
                                    val targetDir = java.io.File(context.filesDir, "attachments/$recordId")
                                    targetDir.mkdirs()
                                    val targetFile = java.io.File(targetDir, fileName)
                                    java.io.FileOutputStream(targetFile).use { fos -> zis.copyTo(fos) }
                                    copiedAttachments.add(targetFile)
                                    val type = if (fileName.matches(Regex(".*\\.(jpg|jpeg|png|gif|webp|bmp)", RegexOption.IGNORE_CASE))) "IMAGE" else "AUDIO"
                                    // 整体备份：按清单还原附件的时长与添加时间（普通包无清单 → 维持旧行为）
                                    val meta = data.attachments_index?.get(recordId)?.firstOrNull { it.file_name == fileName }
                                    recordRepo.saveAttachment(memore.app.data.model.Attachment(
                                        recordId = recordId, fileName = fileName,
                                        filePath = targetFile.absolutePath,
                                        type = meta?.type ?: type, fileSize = targetFile.length(),
                                        duration = meta?.duration,
                                        createdAt = meta?.created_at ?: System.currentTimeMillis()
                                    ))
                                }
                            }
                        } else if (entryName.startsWith("voice_cache/")) {
                            val parts = entryName.split("/", limit = 3)
                            if (parts.size >= 2 && recordRepo.getRecordById(parts[1]) != null) {
                                val recordId = parts[1]
                                val fileName = if (parts.size >= 3) parts[2] else ""
                                if (fileName == "${recordId}.json" || fileName == "${recordId}.wav") {
                                    val targetDir = java.io.File(context.filesDir, "voice_cache")
                                    targetDir.mkdirs()
                                    val targetFile = java.io.File(targetDir, fileName)
                                    java.io.FileOutputStream(targetFile).use { fos -> zis.copyTo(fos) }
                                    copiedVoiceFiles.add(targetFile)
                                }
                            }
                        } else if (entryName.startsWith("sound_custom/")) {
                            // 自定义提示音音频（整体备份）；绑定关系在 data.json 的 sounds 块里
                            val fileName = entryName.removePrefix("sound_custom/")
                            if (fileName.isNotBlank() && !fileName.contains('/')) {
                                val dir = java.io.File(context.filesDir, "sound_custom").apply { mkdirs() }
                                val destFile = java.io.File(dir, fileName)
                                java.io.FileOutputStream(destFile).use { fos -> zis.copyTo(fos) }
                                copiedSounds.add(destFile)
                                soundPathByBase[fileName] = destFile.absolutePath
                            }
                        } else if (entryName.startsWith("meta/")) {
                            // 2026-11-16：files/ 里那些不在 DB 的账本（整体备份才带）
                            //   meta/ai_runs.jsonl            → 向量索引页的「AI 记录」
                            //   meta/insights/archive.jsonl   → 洞察档案
                            //   meta/insights/last_raw.txt    → 最近一次洞察原文
                            val rel = entryName.removePrefix("meta/")
                            val ok = rel == "ai_runs.jsonl" ||
                                rel == "insights/archive.jsonl" ||
                                rel == "insights/last_raw.txt" ||
                                rel == "monthly_topics.json" ||
                                rel == "topic_threads.json"
                            if (ok) {
                                val dest = java.io.File(context.filesDir, rel)
                                dest.parentFile?.mkdirs()
                                java.io.FileOutputStream(dest).use { fos -> zis.copyTo(fos) }
                                copiedMetaFiles.add(dest)
                            }
                        }
                        zis.closeEntry()
                    }
                    zis.close()
                }
            } finally { }

            // 导入加密配置
            val encConf = data.encryption_config
            var hasEncryptionConflict = false
            if (encConf != null && encryptionConfigDao != null) {
                try {
                    val dao = encryptionConfigDao
                    val existing = dao.get()
                    if (existing == null) {
                        // 字段映射（含空值兜底）见 toLocalConfig —— 预览与真正导入共用同一份，不会漂移
                        dao.upsert(toLocalConfig(encConf))
                        // ⚠️ 必须立刻刷新加密状态缓存。
                        //
                        // `EncryptionManager.hasPassword()` 读的是**内存缓存** `passwordConfigured`，
                        // 而它只在 App `onCreate` 里刷过一次。导入是运行时发生的，若不在这里刷新，
                        // 新设备上会出现死路：设置页认为"本机没有密码" → 给你看「设置密码」→
                        // 填完点完成时守卫查数据库发现**确实有配置**（导入进来的）→ 拒绝。
                        // 而唯一正确的入口「加密管理 → 修改密码」此时够不着，除非杀掉 App 重开。
                        EncryptionManager.refreshConfigStatus()
                    } else {
                        // 本地已有密码配置 → 不覆盖。
                        // 2026-11-16：规则（用户定的）=「新机有密码就用新的，没密码就继承旧的」。
                        // v5 包没有密文，两者并存不需要任何"接管"，所以**不再报冲突**；
                        // 只有旧密文包才值得提醒（否则那句话会吓人一跳，且删不掉）。
                        if (!pkgIsGate) hasEncryptionConflict = true
                    }
                } catch (e: Exception) { LogUtil.e("ImportEngine", "encryption config insert", e) }
            }

            // 统计"损坏的加密记录"：有锁定记录但文件缺少 encryption_config 块
            val damagedLockedCount = if (encConf == null) {
                records.count { it.is_locked }
            } else 0

            // 恢复图文集注脚（默认随 data.json 导出；锁定记录导出时不含注脚）
            try {
                val footnoteMap = records.mapNotNull { r ->
                    val notes = r.album_footnotes?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                    r.id to notes.map { memore.app.data.album.AlbumFootnote(it.content, it.created_at, it.updated_at) }
                }.toMap()
                if (footnoteMap.isNotEmpty()) {
                    val store = memore.app.data.album.AlbumStateStore(java.io.File(context.filesDir, "album_state.json"))
                    store.importFootnotes(footnoteMap)
                }
            } catch (e: Exception) {
                LogUtil.e("ImportEngine", "album footnotes import", e)
            }

            // 导入提示词（阶段三）
            val promptsImported = if (data.prompts.isNotEmpty()) {
                try {
                    memore.app.core.ai.Prompts.importAll(data.prompts)
                    true
                } catch (e: Exception) { LogUtil.e("ImportEngine", "prompts import", e); false }
            } else false

            // 导入记录关系（有向带类型，内联在每条记录的 relations_out 上）。
            // 走**第二遍**：边的一端可能在循环里排在后面，第一遍边写会因"端点还不存在"被拒。
            // 只写两端都在本机存在的边 —— 记录可能因"本机已存在"被跳过、或压根没导进来。
            // 同一条边在内联格式里会出现两次（A 的 out / B 的 in），靠 (from,to,type) 唯一索引自动去重。
            run {
                var imported = 0
                val relDao = database?.recordRelationDao()
                allRecords.forEach { r ->
                    r.relations_out?.forEach { rel ->
                        try {
                            if (recordRepo.getRecordById(r.id) != null && recordRepo.getRecordById(rel.to_id) != null) {
                                if (relDao != null) {
                                    relDao.insert(
                                        memore.app.data.model.RecordRelation(
                                            fromId = r.id,
                                            toId = rel.to_id,
                                            type = rel.type,
                                            why = rel.why.orEmpty(),
                                            quoteA = rel.quote_a.orEmpty(),
                                            quoteB = rel.quote_b.orEmpty(),
                                            source = rel.source ?: memore.app.data.model.RecordRelation.SOURCE_USER,
                                            validFrom = rel.valid_from ?: System.currentTimeMillis(),
                                            validTo = rel.valid_to
                                        )
                                    )
                                    imported++
                                } else if (recordRepo.addRelation(r.id, rel.to_id, rel.type)) {
                                    imported++
                                }
                            }
                        } catch (e: Exception) { LogUtil.e("ImportEngine", "relation import", e) }
                    }
                }
                if (imported > 0) LogUtil.i("ImportEngine 关系导入 $imported 条")
            }

            // ===== 2026-11-20：锚点 / 分析版本 / agent 历史（老包没有这些键 → 跳过） =====
            data.anchors?.let { list ->
                val dao = database?.recordEntityDao()
                if (dao != null) {
                    runCatching {
                        val rows = list.filter { recordRepo.getRecordById(it.record_id) != null }
                            .map {
                                memore.app.data.model.RecordEntity(
                                    recordId = it.record_id,
                                    name = it.name,
                                    kind = it.kind,
                                    quote = it.quote,
                                    createdAt = if (it.created_at > 0) it.created_at else System.currentTimeMillis()
                                )
                            }
                        dao.insertAll(rows)
                        LogUtil.i("ImportEngine 锚点导入 ${rows.size} 个")
                    }.onFailure { LogUtil.e("ImportEngine", "anchors import", it) }
                }
            }

            data.analysis_versions?.let { list ->
                val dao = database?.analysisVersionDao()
                if (dao != null) {
                    runCatching {
                        val touched = HashSet<String>()
                        list.filter { recordRepo.getRecordById(it.record_id) != null }.forEach { v ->
                            dao.insert(
                                memore.app.data.model.AnalysisVersion(
                                    recordId = v.record_id,
                                    model = v.model,
                                    promptVersion = v.prompt_version,
                                    inputHash = v.input_hash,
                                    summary = v.summary,
                                    emotionJson = v.emotion_json,
                                    status = v.status,
                                    reason = v.reason,
                                    createdAt = if (v.created_at > 0) v.created_at else System.currentTimeMillis()
                                )
                            )
                            touched += v.record_id
                        }
                        touched.forEach { dao.trim(it, 5) }
                        LogUtil.i("ImportEngine 分析版本导入 ${list.size} 条")
                    }.onFailure { LogUtil.e("ImportEngine", "analysis versions import", it) }
                }
            }

            data.agent_actions?.let { list ->
                val dao = database?.agentActionDao()
                if (dao != null) {
                    runCatching {
                        list.forEach { a ->
                            dao.insert(
                                memore.app.data.model.AgentAction(
                                    id = a.id,
                                    actor = a.actor,
                                    title = a.title,
                                    commandIds = a.command_ids,
                                    status = a.status,
                                    requiresConfirm = a.requires_confirm,
                                    note = a.note,
                                    createdAt = a.created_at,
                                    executedAt = a.executed_at,
                                    revertedAt = a.reverted_at
                                )
                            )
                        }
                        LogUtil.i("ImportEngine agent 动作导入 ${list.size} 条")
                    }.onFailure { LogUtil.e("ImportEngine", "agent actions import", it) }
                }
            }

            data.agent_commands?.let { list ->
                val dao = database?.agentCommandDao()
                if (dao != null) {
                    runCatching {
                        list.forEach { c ->
                            dao.insert(
                                memore.app.data.model.AgentCommand(
                                    id = c.id,
                                    kind = c.kind,
                                    payload = c.payload,
                                    status = c.status,
                                    note = c.note,
                                    createdAt = c.created_at,
                                    executedAt = c.executed_at,
                                    batchId = c.batch_id
                                )
                            )
                        }
                        LogUtil.i("ImportEngine agent 命令导入 ${list.size} 条")
                    }.onFailure { LogUtil.e("ImportEngine", "agent commands import", it) }
                }
            }

            // ================= 整体备份块还原（旧包 / 普通包这些字段为 null → 整段跳过） =================

            // 1) 记录的历史版本快照：只给本次真正新建的记录补，避免覆盖本机已有历史
            data.versions?.let { versions ->
                try {
                    versions.filter { it.record_id in importedIds }.forEach { v ->
                        recordRepo.addVersionRaw(v.record_id, v.content, v.created_at)
                    }
                } catch (e: Exception) { LogUtil.e("ImportEngine", "versions import", e) }
            }

            // 2) 图文集组织状态（选中项 + 置顶；注脚已在上一步合并）
            data.album_state?.let { st ->
                try {
                    val store = memore.app.data.album.AlbumStateStore(java.io.File(context.filesDir, "album_state.json"))
                    // 2026-11-23：星标不能只认"这次新插入的记录"。
                    // 导入到已有库时同 id 会被跳过，importedIds 不含它们；父记录本机存在就该恢复星标。
                    val pinnedExisting = st.pinned.filter { id ->
                        id in importedIds || recordRepo.getRecordById(id) != null
                    }.toSet()
                    store.importState(
                        selected = st.selected.filterKeys { it in importedIds },
                        pinned = pinnedExisting
                    )
                } catch (e: Exception) { LogUtil.e("ImportEngine", "album state import", e) }
            }

            // 3) 自定义提示音绑定：把包里的旧绝对路径改写成本机落地路径（内置 assets 路径原样保留）
            data.sounds?.let { overrides ->
                try {
                    val remapped = overrides.mapValues { (_, assets) ->
                        assets.map { a ->
                            if (a.contains("sound_custom")) soundPathByBase[java.io.File(a).name] ?: a else a
                        }
                    }
                    SoundConfigStore(context).saveAllOverrides(remapped)
                } catch (e: Exception) { LogUtil.e("ImportEngine", "sounds import", e) }
            }

            // 4) 设置全量（三类特殊处理见下）
            data.settings?.let { files ->
                files.forEach { (fileName, entries) ->
                    try {
                        val target = context.getSharedPreferences(fileName, android.content.Context.MODE_PRIVATE)
                        val editor = target.edit()
                        for ((key, entry) in entries) {
                            // 迁移/初始化标记：导出带了但不写入，否则新机跳过必要的初始化
                            if (isMigrationMarkerKey(key)) continue
                            // 带凭据的键（含复合键 api_presets 里的密钥）：本机已有密钥就不覆盖
                            if (isCredentialPrefKey(key) && hasLocalCredential(target, key)) continue
                            when (val v = ExportEngine.decodePrefValue(entry.type, entry.value)) {
                                is Boolean -> editor.putBoolean(key, v)
                                is Int -> editor.putInt(key, v)
                                is Long -> editor.putLong(key, v)
                                is Float -> editor.putFloat(key, v)
                                is Set<*> -> editor.putStringSet(key, v.map { it.toString() }.toSet())
                                is String -> editor.putString(key, v)
                                else -> Unit
                            }
                        }
                        editor.apply()
                    } catch (e: Exception) { LogUtil.e("ImportEngine", "settings import $fileName", e) }
                }
            }

            // 5) 脱敏映射组（整体备份随包）：按 id 合并，不覆盖本机同 id 的组
            data.redaction_groups?.let { groups ->
                try {
                    val existing = redactionRepository?.loadGroups() ?: emptyList()
                    val existingIds = existing.map { it.id }.toSet()
                    val merged = existing + groups.filter { it.id.isNotBlank() && it.id !in existingIds }
                    if (merged.size != existing.size) redactionRepository?.saveGroups(merged)
                } catch (e: Exception) { LogUtil.e("ImportEngine", "redaction groups import", e) }
            }

            // 6) 真草稿（多本 + 块）：本机没有"同名 + 同创建时间"那本就还原；**id 全部重建**
            //    （草稿之间没有互相引用，重建成本极低）。空本不还原。
            var draftsImported = 0
            var draftsSkipped = 0
            data.drafts?.let { pkgDrafts ->
                try {
                    val existingKeys = draftRepository?.allDrafts()?.map { it.name to it.createdAt }?.toSet() ?: emptySet()
                    val (restore, skippedDrafts) = draftsToRestore(pkgDrafts, existingKeys)
                    draftsSkipped = skippedDrafts.size
                    restore.forEach { d ->
                        val newId = draftRepository?.importDraft(
                            draft = memore.app.data.model.Draft(
                                name = d.name,
                                createdAt = d.createdAt,
                                updatedAt = d.updatedAt,
                                archived = d.archived,
                                pinned = d.pinned
                            ),
                            blocks = d.blocks.map { b ->
                                memore.app.data.model.DraftBlock(
                                    content = b.content.trim(),
                                    createdAt = b.createdAt,
                                    updatedAt = b.updatedAt,
                                    kind = b.kind,
                                    draftId = 0
                                )
                            }
                        )
                        if (newId != null) { importedDraftIds += newId; draftsImported++ }
                    }
                    LogUtil.i("ImportEngine 草稿导入 $draftsImported 本（跳过 $draftsSkipped 本）")
                } catch (e: Exception) { LogUtil.e("ImportEngine", "drafts import", e) }
            }

            clearProgress()
            Result.success(ImportResult(imported = imported, skipped = skipped, systemInstruction = data.system_instruction, promptsImported = promptsImported, hasEncryptionConflict = hasEncryptionConflict, damagedLockedCount = damagedLockedCount, skippedLockedCount = skippedLocked, draftsImported = draftsImported, draftsSkipped = draftsSkipped))
        } catch (e: Exception) {
            rollbackImported()
            clearProgress()
            Result.failure(Exception("导入失败: ${e.message}"))
        }
    }

    private fun readJsonFromUri(context: Context, uri: Uri): String {
        if (!isZipSource(context, uri)) {
            return context.contentResolver.openInputStream(uri)?.use { stream -> String(stream.readBytes(), Charsets.UTF_8) }
                ?: throw Exception("无法打开文件")
        }
        val zis = java.util.zip.ZipInputStream(context.contentResolver.openInputStream(uri))
        zis.use {
            while (true) {
                val entry = it.nextEntry ?: break
                if (entry.name == "data.json") {
                    val bytes = it.readBytes()
                    it.closeEntry()
                    return String(bytes, Charsets.UTF_8)
                }
                it.closeEntry()
            }
        }
        throw Exception("ZIP 包中未找到 data.json")
    }

    /** 只读前 2 字节判断是否为 ZIP，不整读进内存。 */
    private fun isZipSource(context: Context, uri: Uri): Boolean = try {
        context.contentResolver.openInputStream(uri)?.use { s ->
            java.io.BufferedInputStream(s).use { b ->
                b.mark(2)
                val b0 = b.read(); val b1 = b.read()
                b.reset()
                b0 == 0x50 && b1 == 0x4B
            }
        } ?: false
    } catch (_: Exception) {
        false
    }

    /** 拒导入文件在导入尝试后直接损坏：用随机字节覆盖原文件，只保留识别标记。 */
    private fun damageBlockedFile(context: Context, uri: Uri) {
        try {
            val original = runCatching {
                context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: ByteArray(64)
            }.getOrDefault(ByteArray(64))
            val payload = ByteArray(original.size.coerceAtLeast(64))
            java.security.SecureRandom().nextBytes(payload)
            val marker = DAMAGE_MARKER.toByteArray(Charsets.UTF_8)
            val out = java.io.ByteArrayOutputStream(marker.size + payload.size)
            out.write(marker)
            out.write(0x00)
            out.write(payload)
            context.contentResolver.openOutputStream(uri, "w")?.use { it.write(out.toByteArray()) }
        } catch (e: Exception) {
            LogUtil.e("ImportEngine", "damageBlockedFile", e)
        }
    }

    private const val DAMAGE_MARKER = "MIRROR_DAMAGED::BLOCKED"
}

