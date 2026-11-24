package memore.app.data.repository

import memore.app.data.local.dao.AiTaskDao
import memore.app.data.local.dao.AttachmentDao
import memore.app.data.local.dao.LockRow
import memore.app.data.local.dao.RecordDao
import memore.app.data.local.dao.UnlockRow
import memore.app.data.local.dao.VersionDao
import memore.app.data.model.AiAnalysisResult
import memore.app.data.model.AiTask
import memore.app.data.model.AnalysisData
import memore.app.data.model.AnalysisMeta
import memore.app.data.model.EmotionTagEntry
import memore.app.data.model.EmotionTag
import memore.app.data.model.EmotionalProfileItem
import memore.app.data.model.EmotionStats
import androidx.sqlite.db.SimpleSQLiteQuery
import memore.app.utils.GsonInstance
import memore.app.utils.EmotionNormalizer
import memore.app.data.model.Attachment
import memore.app.data.model.Record
import memore.app.data.model.RecordWithDetails
import memore.app.data.model.Version
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.UnknownHostException
import memore.app.utils.formatIsoZ
import javax.net.ssl.SSLException
import memore.app.utils.LogUtil
import androidx.room.withTransaction
import memore.app.utils.JsonCleaner
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import java.io.File

/** 临时查看时 UI 用到的附件（filePath/fileName 为解密后的缓存路径/原始名）。 */
data class AttachmentView(
    val id: Long,
    val fileName: String,
    val filePath: String,
    val type: String,
    val fileSize: Long,
    val duration: Long?
)

data class AlbumItem(
    val record: Record,
    val imagePaths: List<String>
)

class RecordRepository(
    private val recordDao: RecordDao,
    private val versionDao: VersionDao,
    private val attachmentDao: AttachmentDao,
    private val taskDao: AiTaskDao,
    private val db: memore.app.data.local.AppDatabase? = null
) {

    // ===== 关联边（2026-11-05）=====
    /** 由 AppContainer 注入：给定记录 id 返回候选旧记录（向量 Top-N，按月分桶）。 */
    @Volatile
    var linkCandidatesProvider: (suspend (String) -> List<memore.app.data.vector.ContextBuilder.Candidate>)? = null

    /** 本次分析允许的关联目标（白名单，防幻觉）。 */
    @Volatile
    private var linkCandidatesBlockIds: Set<String> = emptySet()

    /** 候选 id → 向量分，落库边时带上（界面不显示，只给阈值调参用）。 */
    @Volatile
    private var lastCandidateScores: Map<String, Float> = emptyMap()

    /** 候选编号（"1".."8"）→ 记录 id：模型只会抄短号，长 UUID 它一定会改错（实测 8 条里错 2 条）。 */
    @Volatile
    private var lastCandidateIndexToId: Map<String, String> = emptyMap()

    /** 最近一次分析的候选（给 UI 的"AI 记录"用）。 */
    @Volatile
    private var lastCandidates: List<memore.app.data.vector.ContextBuilder.Candidate> = emptyList()

    /** 本次分析的记录正文（给关联边的引证校验用；只在持锁期间有效）。 */

    @Volatile
    private var lastRecordContent: String = ""

    /** 本次分析输入的指纹（正文+增修），写进 records.analysis_input_hash 与版本表。 */
    @Volatile
    private var lastAnalysisInputHash: String = ""
    @Volatile
    private var lastDroppedLinks: Int = 0

    /** 本次分析因"依据不扎根原文"被丢掉的情绪标签数（日志用，看解析是否残缺）。 */
    @Volatile
    private var lastDroppedEmotions: Int = 0

    /**
     * 分析串行锁：下面那组 lastXxx 字段只在本锁持有期间有效。
     * 没有它，两条记录并发分析时后一条的候选会覆盖前一条，导致关联边写错分数/写错目标。
     */
    private val analysisLock = kotlinx.coroutines.sync.Mutex()
    /** why 是否扎根于原文：整句命中，或至少有一段 8 字以上连续命中（容忍模型少量改写）。 */
    private fun groundedIn(why: String, text: String): Boolean {
        if (text.isBlank()) return false
        if (text.contains(why)) return true
        val min = 8
        if (why.length < min) return text.contains(why)
        for (i in 0..(why.length - min)) {
            if (text.contains(why.substring(i, i + min))) return true
        }
        return false
    }

    /** 应用上下文：给 AiRunLog 落盘用。 */
    @Volatile
    var appContext: android.content.Context? = null
    fun getLatestRecords(limit: Int = 30): Flow<List<Record>> {
        return recordDao.getLatestRecords(limit)
    }

    /** 联合查询：时间线记录 + 标签 + 协议（首帧同步，避免异步弹出）。 */
    fun getLatestRecordsWithDetails(limit: Int = 30): Flow<List<RecordWithDetails>> {
        return recordDao.getLatestRecordsWithDetails(limit)
    }

    /** 联合查询（含增修）：时间线记录 + 标签 + 协议 + 增修；响应式（DB 变更自动刷新），组装在 IO 线程。 */
    fun getLatestRecordsWithAmendments(limit: Int = 30): Flow<List<RecordWithDetails>> =
        recordDao.getLatestRecordsWithDetails(limit)
            .flatMapLatest { details ->
                val ids = details.map { it.record!!.id }
                recordDao.getAmendmentsForRecordsFlow(ids)
                    .map { amends ->
                        val amendsByParent = amends.groupBy { it.correctionOfId }
                        details.map { d ->
                            RecordWithDetails(
                                record = d.record!!,
                                tags = d.tags,
                                protocols = d.protocols,
                                amendments = amendsByParent[d.record!!.id] ?: emptyList()
                            )
                        }
                    }
            }
            .flowOn(Dispatchers.IO)

    fun getAnchoredRecords(): Flow<List<Record>> {
        return recordDao.getAnchoredRecords()
    }

    /** 联合查询：锚点记录 + 标签 + 协议（首帧同步）。 */
    fun getAnchoredRecordsWithDetails(): Flow<List<RecordWithDetails>> {
        return recordDao.getAnchoredRecordsWithDetails()
    }

    /** 联合查询（含增修）：锚点记录 + 标签 + 协议 + 增修；响应式（DB 变更自动刷新），组装在 IO 线程。 */
    fun getAnchoredRecordsWithAmendments(): Flow<List<RecordWithDetails>> =
        recordDao.getAnchoredRecordsWithDetails()
            .flatMapLatest { details ->
                val ids = details.map { it.record!!.id }
                recordDao.getAmendmentsForRecordsFlow(ids)
                    .map { amends ->
                        val amendsByParent = amends.groupBy { it.correctionOfId }
                        details.map { d ->
                            RecordWithDetails(
                                record = d.record!!,
                                tags = d.tags,
                                protocols = d.protocols,
                                amendments = amendsByParent[d.record!!.id] ?: emptyList()
                            )
                        }
                    }
            }
            .flowOn(Dispatchers.IO)

    fun getDeletedRecords(): Flow<List<Record>> {
        return recordDao.getDeletedRecords()
    }

    /** 回收站全量（供整体备份，含被独立软删的增修） */
    suspend fun getTrashedRecords(): List<Record> = recordDao.getTrashedRecords()

    /** 全部版本快照（供整体备份） */
    suspend fun getAllVersions(): List<memore.app.data.model.Version> = versionDao.getAll()

    /** 整体备份还原：原样插入一条历史版本（不走 trim，包内本就只带最近 5 条）。 */
    suspend fun addVersionRaw(recordId: String, content: String, createdAt: Long) {
        versionDao.insert(memore.app.data.model.Version(recordId = recordId, content = content, createdAt = createdAt))
    }

    fun searchByDateRange(startTs: Long, endTs: Long): Flow<List<Record>> {
        return recordDao.searchByDateRange(startTs, endTs)
    }

    fun searchByTagName(q: String): Flow<List<Record>> {
        return recordDao.searchByTagName(q)
    }

    fun getRecordsByTagIds(tagIds: List<Int>): Flow<List<Record>> {
        return recordDao.getRecordsByTagIds(tagIds)
    }

    fun getRecordsByProtocolIds(protocolIds: List<Int>): Flow<List<Record>> {
        return recordDao.getRecordsByProtocolIds(protocolIds)
    }

    /**
     * 筛选查询的唯一入口（2026-11-15）：标签与协议可以**同时**生效。
     *
     * 三条分支而不是一条：只用标签/只用协议时走原查询（SQL 更少 JOIN，计划更简单）；
     * 两者都有时才用组合查询。
     */
    fun getRecordsByFilter(tagIds: List<Int>, protocolIds: List<Int>): Flow<List<Record>> = when {
        tagIds.isEmpty() && protocolIds.isEmpty() -> kotlinx.coroutines.flow.flowOf(emptyList())
        protocolIds.isEmpty() -> recordDao.getRecordsByTagIds(tagIds)
        tagIds.isEmpty() -> recordDao.getRecordsByProtocolIds(protocolIds)
        else -> recordDao.getRecordsByTagIdsAndProtocolIds(tagIds, protocolIds)
    }

    /**
     * v5（2026-11-16）：**读给界面的路径，锁定记录一律不带正文**。
     *
     * 旧系统靠"锁定就把 content 清空"来实现隐藏；v5 数据层是明文，
     * 如果继续依赖数据形态，所有读 content 的界面都会把锁定内容漏出来
     * （已实测：时间线卡片、详情页「洞察」、关联卡片）。
     *
     * 所以把"隐藏"收到这一层：所有对外读取都过 [maskLocked]，
     * 真正需要明文的地方（解密、迁移、临时查看）显式用 [getRecordByIdRaw]。
     */
    private fun maskLocked(r: Record): Record = if (!r.isLocked) r else r.copy(
        content = "",
        notes = "",
        dialogueAiContent = "",
        dialogueAiReply = "",
        analysis = null,
        emotionalTags = "[]"
    )

    /** 内部用：绕过遮挡，拿真实明文（临时查看 / 迁移 / 旧解密路径）。 */
    suspend fun getRecordByIdRaw(id: String): Record? = recordDao.getRecordById(id)

    suspend fun getRecordsByIds(ids: List<String>): List<Record> =
        if (ids.isEmpty()) emptyList() else recordDao.getRecordsByIds(ids).map { maskLocked(it) }

    suspend fun getAllActiveRecords(): List<Record> {
        return recordDao.getAllActiveRecords().map { maskLocked(it) }
    }

    /** 内部用：绕过遮挡（整体备份 / 勾选"包含锁定记录"的导出）。 */
    suspend fun getAllActiveRecordsRaw(): List<Record> = recordDao.getAllActiveRecords()

    /** 轻量版：聚类用（不解析 analysis 的那几列）。 */
    suspend fun getAllActiveLight(): List<memore.app.data.model.LightRecord> {
        return recordDao.getAllActiveLight()
    }

    suspend fun addRecord(record: Record): Result<Unit> {
        return try {
            recordDao.insertRecord(record)
            memore.app.data.vector.VectorIndexBus.mark(record.id)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun updateRecord(record: Record): Result<Unit> {
        return try {
            val old = recordDao.getRecordById(record.id)
            if (old != null && (old.aiStatus == "SUCCESS" || old.aiStatus == "FAILED")) {
                recordDao.updateAiStatus(record.id, "STALE")
            }
            recordDao.updateRecord(record)
            memore.app.data.vector.VectorIndexBus.mark(record.id)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun markStale(recordId: String) {
        val record = recordDao.getRecordById(recordId) ?: return
        if (record.aiStatus == "SUCCESS" || record.aiStatus == "FAILED") {
            recordDao.updateAiStatus(recordId, "STALE")
        }
    }

    suspend fun permanentlyDeleteRecord(id: String): Result<Unit> {
        return try {
            // 2026-11-18 修 bug：先级联删掉这条记录的全部增修（含回收站里的）。
            // 旧实现只删父记录，增修行留下来且 correction_of_id 悬空 —— 就是「孤儿增修」的来源。
            // 递归调用自身，让增修也走一遍附件 / 关系 / 标签 / 向量清理。
            runCatching { recordDao.getAllAmendmentsOf(id) }.getOrDefault(emptyList())
                .forEach { a -> runCatching { permanentlyDeleteRecord(a.id) } }

            // 先把磁盘上的附件文件删掉，再删库行（2026-10-21 修）：
            // 原来只删 DB，文件永远留在地盘上 —— 清一次回收站就漏一批，堆起来就是白占几百 MB。
            deleteAttachmentFilesOf(id)
            // 关系边要一起清掉，否则会留下指向不存在记录的悬空边
            try { db?.recordRelationDao()?.deleteAllFor(id) } catch (_: Exception) {}
            // 标签 / 协议关联同理（2026-11-12 补）：这两张关联表只有指向标签/协议的外键，
            // **没有指向 records 的**，所以软删时它们会留着（还原要带回标签），
            // 一旦彻底删除就必须手动清，否则永久堆孤儿（条数统计 join 得掉，但表会一直长）。
            try { db?.tagDao()?.deleteAllForRecord(id) } catch (_: Exception) {}
            try { db?.protocolDao()?.deleteAllForRecord(id) } catch (_: Exception) {}
            recordDao.permanentlyDeleteRecord(id)
            memore.app.data.vector.VectorIndexBus.mark(id)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun clearTrash(): Result<Unit> {
        return try {
            // 2026-11-18：逐条走 permanentlyDeleteRecord，保证每条回收站记录都级联
            // 删掉它的增修、附件、关系、标签/协议关联、向量 —— 不再整批 clearTrash 漏增修。
            // 旧实现直接 recordDao.clearTrash()，孤儿增修就是这么一批批产生的。
            recordDao.getTrashedRecords().forEach { r ->
                runCatching { permanentlyDeleteRecord(r.id) }
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** 删掉某条记录在磁盘上的全部附件文件与它的目录。 */
    private suspend fun deleteAttachmentFilesOf(recordId: String) {
        try {
            val atts = attachmentDao.getForRecord(recordId)
            var dir: java.io.File? = null
            atts.forEach { a ->
                val f = java.io.File(a.filePath)
                if (dir == null) dir = f.parentFile
                runCatching { f.delete() }
            }
            dir?.let { runCatching { it.deleteRecursively() } }
        } catch (_: Exception) {
        }
    }

    /** 该 id 的记录是否存在（**不过滤 is_deleted**：回收站里的记录，附件还得留着）。 */
    suspend fun recordExistsIncludingTrash(id: String): Boolean =
        try { recordDao.countById(id) > 0 } catch (_: Exception) { true }

    suspend fun softDeleteRecord(id: String): Result<Unit> {
        return try {
            // 锁定记录需先解密才能删除（防御纵深；UI 层会先弹密码框解锁）
            if (recordDao.getRecordById(id)?.isLocked == true) {
                return Result.failure(Exception("请先解密此记录再删除"))
            }
            recordDao.softDeleteRecord(id)
            memore.app.data.vector.VectorIndexBus.mark(id)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** 批量软删除：单条 UPDATE 完成，锁定校验由 UI 解锁流程保证。 */
    suspend fun softDeleteRecords(ids: List<String>): Result<Unit> {
        return try {
            if (ids.isEmpty()) return Result.success(Unit)
            recordDao.softDeleteRecords(ids)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun restoreRecord(id: String): Result<Unit> {
        return try {
            recordDao.restoreRecord(id)
            memore.app.data.vector.VectorIndexBus.mark(id)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun toggleAnchor(id: String): Result<Unit> {
        return try {
            recordDao.toggleAnchor(id)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getRecordById(id: String): Record? {
        // v5：锁定记录对界面不可见正文（见 maskLocked 注释）
        return recordDao.getRecordById(id)?.let { maskLocked(it) }
    }

    suspend fun getPendingTaskCount(): Int = taskDao.getPendingCount()

    suspend fun getActiveTasks() = taskDao.getActiveTasks()

    /**
     * 活动任务的 Flow：队列弹层、徽标计数、以及"这条记录是否正在生成"都从它派生。
     * DB 是唯一真相 —— 一次性查询会让界面在 worker 写回后失去同步。
     */
    fun observeActiveTasks(): kotlinx.coroutines.flow.Flow<List<AiTask>> = taskDao.observeActiveTasks()

    suspend fun enqueueTask(recordId: String) {
        // 锁定即只读：锁定记录不触发 AI 分析
        if (recordDao.getRecordById(recordId)?.isLocked == true) return
        // 去重：这条记录已在队列里（含处理中）就不再入队，避免重复点「分析」堆一倍任务
        if (taskDao.countActiveForRecord(recordId) > 0) return
        // 上一次失败留下的任务先清掉：否则每点一次「分析」就多一条 FAILED（今天见过同一条堆 5 条）
        taskDao.deleteFailedForRecord(recordId)
        taskDao.insertTask(AiTask(recordId = recordId))
        recordDao.updateAiStatus(recordId, "QUEUED")
    }

    suspend fun deleteAiTask(taskId: Int) {
        taskDao.deleteTask(taskId)
    }

    /**
     * **启动时自愈**：把上一条进程留下的 PROCESSING 任务接回来。
     *
     * 为什么必须做：worker 把任务标成 PROCESSING 之后，如果进程被杀（或那次 HTTP 卡死后协程被掐），
     * **没有任何人会写回状态** —— 而 `getNextPendingTask()` 只取 PENDING，于是那条任务永远卡住：
     * 卡片一直转圈（转圈集合 = PROCESSING 的任务），而且再也不会被拾起。
     * 用户 2026-10-11 真机上就遇到一条（22:30 点的分析，挂了 10 分钟仍 PROCESSING）。
     *
     * ⚠️ 这**不是**用户禁止的"失败自动重试"：任务没跑完，接着跑才是对的；
     * 但为了不无限循环，中断次数到达 maxRetries 的一律标 FAILED（并把记录的 ai_status 也标上）。
     *
     * @return 接回来（退回 PENDING）的任务数
     */
    suspend fun resumeInterruptedTasks(): Int = withContext(Dispatchers.IO) {
        val interrupted = try { taskDao.getProcessingTasks() } catch (_: Exception) { emptyList() }
        var resumed = 0
        interrupted.forEach { t ->
            try {
                if (t.retryCount >= t.maxRetries) {
                    taskDao.updateTask(
                        t.copy(status = "FAILED", errorMessage = "多次被中断（进程被杀或超时）", lastAttemptAt = System.currentTimeMillis())
                    )
                    recordDao.updateAiStatus(t.recordId, "FAILED")
                } else {
                    taskDao.updateTask(t.copy(status = "PENDING", retryCount = t.retryCount + 1))
                    resumed++
                }
            } catch (e: Exception) {
                LogUtil.e("AI-Resume", "resume interrupted ${t.recordId}", e)
            }
        }
        resumed
    }

    suspend fun updateAiStatus(recordId: String, status: String) {
        recordDao.updateAiStatus(recordId, status)
    }

    /** 把历史情绪标签按当前映射表归一化后写回底层（emotional_tags + analysis.emotion），幂等。 */
    suspend fun migrateEmotionTagsToNormalized(): Int = withContext(Dispatchers.IO) {
        val records = recordDao.getAllActiveRecords().filter { !it.isLocked }
        var migrated = 0
        records.forEach { record ->
            val entries = EmotionStats.emotionEntries(record)
            if (entries.isEmpty()) return@forEach
            val normalized = entries.groupBy { it.name }
                .map { (name, list) -> list.maxByOrNull { it.weight }!!.copy(name = name) }
            val tagsJson = GsonInstance.gson.toJson(normalized)
            val analysisJson = record.analysis?.copy(emotion = normalized)?.let { GsonInstance.gson.toJson(it) }
            if (tagsJson == record.emotionalTags && analysisJson == record.analysis?.let { GsonInstance.gson.toJson(it) }) {
                return@forEach
            }
            val tx = db
            if (tx != null) {
                tx.withTransaction {
                    recordDao.updateEmotionalTags(record.id, tagsJson)
                    recordDao.updateAnalysis(record.id, analysisJson)
                }
            } else {
                recordDao.updateEmotionalTags(record.id, tagsJson)
                recordDao.updateAnalysis(record.id, analysisJson)
            }
            migrated++
        }
        LogUtil.i("AI-Migration 情绪标签底层归一化完成: $migrated 条")
        migrated
    }

    suspend fun getRecordsWithEmotion(): List<Record> {
        return recordDao.getRecordsWithEmotion()
    }

    /** 仅统计活跃（未删、非增修）记录数，避免为计数拉取全表。 */
    suspend fun countActiveRecords(): Int = recordDao.countActiveRecords()

    /** 界面统计/批量全选用：只数"能分析"的记录，避免空正文永远算作未完成。 */
    suspend fun countAnalyzableRecords(): Int = recordDao.countAnalyzableRecords()

    // ---- 有向带类型的记录关系（取代对称的 linked_ids） ----

    /**
     * 建立一条**有向**关系：[fromId] 「[type]」→ [toId]。
     *
     * 与旧的 `linkRecords` 的区别：不再两边互相写（那种结构表达不了方向，也没法反查"谁引用了我"）。
     * 同一条边重复建立会被唯一索引静默忽略；不给自己建边。
     */
    suspend fun addRelation(fromId: String, toId: String, type: String): Boolean {
        if (fromId == toId) return false
        val dao = db?.recordRelationDao() ?: return false
        if (recordDao.getRecordById(fromId) == null || recordDao.getRecordById(toId) == null) return false
        return try {
            dao.insert(memore.app.data.model.RecordRelation(fromId = fromId, toId = toId, type = type))
            true
        } catch (_: Exception) { false }
    }

    /** 解除一条有向关系（只删这一条边；反向那条各自独立）。 */
    suspend fun removeRelation(fromId: String, toId: String) {
        try { db?.recordRelationDao()?.deletePair(fromId, toId) } catch (_: Exception) {}
    }

    /** 出向：这条记录引用了/补充了/更正了谁（附目标记录，便于直接渲染）。 */
    suspend fun relationsFrom(recordId: String): List<Pair<memore.app.data.model.RecordRelation, Record>> =
        try {
            db?.recordRelationDao()?.from(recordId).orEmpty().filter { it.validTo == null }.mapNotNull { rel ->
                recordDao.getRecordById(rel.toId)?.let { rel to it }
            }
        } catch (_: Exception) { emptyList() }

    /** 入向：谁引用/补充/更正了这条记录。 */
    suspend fun relationsTo(recordId: String): List<Pair<memore.app.data.model.RecordRelation, Record>> =
        try {
            db?.recordRelationDao()?.to(recordId).orEmpty().filter { it.validTo == null }.mapNotNull { rel ->
                recordDao.getRecordById(rel.fromId)?.let { rel to it }
            }
        } catch (_: Exception) { emptyList() }

    /** 全量关系（导出用）。 */
    suspend fun allRelations(): List<memore.app.data.model.RecordRelation> =
        try { db?.recordRelationDao()?.all().orEmpty().filter { it.validTo == null } } catch (_: Exception) { emptyList() }

    /**
     * 来源的「叙事分量」（2026-11-17：wCluster 复用为叙事权重）。
     *
     * 下游三处共用它：话题/母题频次、情绪统计、洞察「近一月」取样。
     * 0 = 不算进我的叙事（例如 AI 转录）。
     */
    suspend fun originNarrativeWeights(): Map<String, Float> = runCatching {
        db?.originDao()?.all()?.associate { it.id to it.wCluster }.orEmpty()
    }.getOrDefault(emptyMap())

    /** 解析记录的 linked_ids（JSON 数组字符串）为 id 列表；失败返回空。 */
    private fun parseLinkedIds(json: String): List<String> {
        if (json.isBlank()) return emptyList()
        return try {
            GsonInstance.gson.fromJson(json, Array<String>::class.java)?.toList() ?: emptyList()
        } catch (_: Exception) { emptyList() }
    }

    /**
     * 建立/保存记录双向关联：a↔b 各自 linked_ids 互相包含对方（去重、排除自身）。
     * records 之间是双向的：A 关联 B → 两者各自记对方。
     */
    suspend fun linkRecords(recordId: String, targetId: String) {
        if (recordId == targetId) return
        val a = recordDao.getRecordById(recordId) ?: return
        val b = recordDao.getRecordById(targetId) ?: return
        val aLinked = (parseLinkedIds(a.linkedIds) + targetId).distinct()
        val bLinked = (parseLinkedIds(b.linkedIds) + recordId).distinct()
        recordDao.updateLinkedPair(
            a.id, GsonInstance.gson.toJson(aLinked),
            b.id, GsonInstance.gson.toJson(bLinked)
        )
    }

    /** 解除一条记录对 targetId 的单向关联（仅从 recordId 侧移除；如需解绑对侧可对 target 也调用）。 */
    suspend fun unlinkRecord(recordId: String, targetId: String) {
        val a = recordDao.getRecordById(recordId) ?: return
        val aLinked = parseLinkedIds(a.linkedIds).filter { it != targetId }
        recordDao.updateLinkedIds(a.id, GsonInstance.gson.toJson(aLinked))
    }

    /** 取与指定记录有双向关联的记录列表。 */
    suspend fun getLinkedRecords(recordId: String): List<Record> {
        val rec = recordDao.getRecordById(recordId) ?: return emptyList()
        return parseLinkedIds(rec.linkedIds).mapNotNull { recordDao.getRecordById(it) }
    }

    /** 目标记录解析结果：按完整 ID 或短/部分前缀匹配。 */
    sealed class IdResolveResult {
        data class Success(val record: Record) : IdResolveResult()
        object NotFound : IdResolveResult()
        data class Ambiguous(val candidates: List<Record>) : IdResolveResult()
    }

    /**
     * 解析关联输入：优先按完整 ID 精确命中；否则按短/部分前缀匹配。
     * 前缀命中多条时返回 Ambiguous（短 ID 不唯一，需更多位）。
     */
    suspend fun resolveRecordId(q: String): IdResolveResult {
        val t = q.trim()
        if (t.isBlank()) return IdResolveResult.NotFound
        recordDao.getRecordById(t)?.let { return IdResolveResult.Success(it) }
        val matches = recordDao.getRecordsByIdPrefix(t).distinctBy { it.id }
        return when {
            matches.isEmpty() -> IdResolveResult.NotFound
            matches.size == 1 -> IdResolveResult.Success(matches.first())
            else -> IdResolveResult.Ambiguous(matches)
        }
    }

    /** 2026-11-23：清掉父记录已不存在的孤儿增修（走正常彻底删除，附件/关系/标签一起清）。 */
    suspend fun purgeOrphanAmendments(): Int {
        val rows = recordDao.getOrphanAmendments()
        rows.forEach { r -> runCatching { permanentlyDeleteRecord(r.id) } }
        return rows.size
    }

    /** 一次性回填：把已有增修与其原记录补成双向 linked_ids（旧数据从单向修复为双向）。 */
    suspend fun backfillAmendmentLinks() {
        val amends = recordDao.getAllAmendments()
        amends.forEach { amd ->
            val parentId = amd.correctionOfId ?: return@forEach
            if (parentId == amd.id) return@forEach
            val parent = recordDao.getRecordById(parentId) ?: return@forEach
            val amdLinked = parseLinkedIds(amd.linkedIds)
            val parentLinked = parseLinkedIds(parent.linkedIds)
            val newAmd = (amdLinked + parentId).distinct()
            val newParent = (parentLinked + amd.id).distinct()
            if (newAmd != amdLinked || newParent != parentLinked) {
                recordDao.updateLinkedPair(amd.id, GsonInstance.gson.toJson(newAmd), parentId, GsonInstance.gson.toJson(newParent))
            }
            // 新模型：增修与原记录的关系走 record_relations（有向、带类型）。
            // 类型是 **SUPPLEMENT**：增修是在原文上追加，不是推翻它。
            // 这里顺手补齐 —— 否则会出现"只躺在老的 linked_ids 里、新界面看不见"的边
            // （这个回填有一次性标记，但恢复旧备份的设备可能重跑）。
            addRelation(amd.id, parentId, memore.app.data.model.RecordRelationType.SUPPLEMENT)
        }
    }

    /**
     * 检索：按记录 ID（短ID/完整UUID，可带 # 前缀）或 FTS5 全文（content/notes，trigram 分词器）。
     *
     * 2026-11-19：trigram 分词器**只支持 ≥3 个字符的查询**（它索引的是 3 字窗口），
     * 而中文搜索大量是 2 字（吃药、焦虑、武汉）。所以：
     * - 长度 ≥ 3 → FTS5 MATCH（有 bm25、有子串）；
     * - 长度 < 3 → 直接 LIKE 扫描（320 条量级毫秒级，够用）。
     * 有 FTS5 时的排序：ID 命中优先，其余按时间倒序。
     */
    fun searchRecordsFts(q: String): Flow<List<Record>> = flow {
        if (q.isBlank()) { emit(emptyList()); return@flow }
        val term = q.trim().removePrefix("#")
        if (term.isBlank()) { emit(emptyList()); return@flow }
        val words = term.split(Regex("\\s+")).filter { it.isNotBlank() }
        fun likeEscape(v: String) = "%" + v.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
        val allLong = words.all { it.codePointCount(0, it.length) >= 3 }
        val res = runCatching {
            if (!allLong) {
                // 有 2 字及以下的词：trigram 索引帮不上忙，逐词 LIKE（AND），中文短查询走这条路
                val clause = words.joinToString(" AND ") {
                    "(r.content LIKE ? ESCAPE '\\' OR r.notes LIKE ? ESCAPE '\\' OR r.id LIKE ? ESCAPE '\\')"
                }
                val args = ArrayList<Any>(words.size * 3 + 1)
                args.add(likeEscape(term))
                words.forEach { w ->
                    args.add(likeEscape(w)); args.add(likeEscape(w)); args.add(likeEscape(w))
                }
                recordDao.rawSearch(SimpleSQLiteQuery(
                    "SELECT r.* FROM records r WHERE r.is_deleted = 0 AND r.correction_of_id IS NULL " +
                        "AND (r.id LIKE ? ESCAPE '\\' OR ($clause)) " +
                        "ORDER BY (r.id LIKE ? ESCAPE '\\') DESC, r.created_at DESC",
                    (args + likeEscape(term)).toTypedArray()
                ))
            } else {
                // 全部 ≥3 字：FTS5 trigram，按词 OR（各自引号，防语法注入）
                val fts = words.joinToString(" OR ") { "\"" + it.replace("\"", "\"\"") + "\"" }
                recordDao.rawSearch(SimpleSQLiteQuery(
                    "SELECT r.* FROM records r WHERE r.is_deleted = 0 AND r.correction_of_id IS NULL AND (" +
                        "r.id LIKE ? OR r.rowid IN (SELECT rowid FROM records_fts5 WHERE records_fts5 MATCH ?)" +
                        ") ORDER BY (r.id LIKE ?) DESC, r.created_at DESC",
                    arrayOf(likeEscape(term), fts, likeEscape(term))
                ))
            }
        }.getOrElse {
            val t = term
            recordDao.getAllActiveRecords().filter { it.content.contains(t) || it.notes.contains(t) || it.id.contains(t) }
        }
        emit(res)
    }

    suspend fun getEmotionalProfile(): List<EmotionalProfileItem> {
        val allRecords = recordDao.getAllActiveRecords()
        // 叙事分量（2026-11-17）：来源权重 0 的记录情绪不算；其余按乘子压低
        val narr = originNarrativeWeights()
        return EmotionStats.parse(allRecords) { r -> r.originId?.let { narr[it] } ?: 1f }
            .filter { it.weight > 0f }
            // 按权重降序：weight 已内置时间衰减(半衰期7天) × 次数置信度因子(λ=0.2)，
            // 排序、展示、导出口径统一，避免低频高权重情绪虚高排名
            .sortedByDescending { it.weight }
            .map { EmotionalProfileItem(it.name, it.weight.toDouble(), it.frequency) }
    }

    suspend fun getAmendmentsForRecord(recordId: String): List<Record> {
        // v5：增修同样不能绕过记录本身的门闩
        return recordDao.getAmendmentsForRecord(recordId).map { maskLocked(it) }
    }

    /** 内部用：绕过遮挡（临时查看里的增修明文）。 */
    suspend fun getAmendmentsForRecordRaw(recordId: String): List<Record> =
        recordDao.getAmendmentsForRecord(recordId)

    suspend fun getAmendmentsForRecords(recordIds: List<String>): List<Record> {
        if (recordIds.isEmpty()) return emptyList()
        return recordDao.getAmendmentsForRecords(recordIds)
    }

    /** 全部活跃增修，含孤儿增修（整体备份用；2026-11-17 修导出漏条）。 */
    suspend fun getAllActiveAmendments(): List<Record> = recordDao.getAllActiveAmendments()

    suspend fun saveVersion(recordId: String, content: String) {
        versionDao.insertAndTrim(
            Version(recordId = recordId, content = content),
            recordId
        )
    }

    suspend fun getVersions(recordId: String): List<Version> {
        return versionDao.getByRecordId(recordId)
    }

    /** 附件总个数与总体积（导出页展示用，一次聚合查询）。 */
    suspend fun attachmentTotal(): Pair<Int, Long> = try {
        attachmentDao.countAll() to attachmentDao.totalBytes()
    } catch (_: Exception) {
        0 to 0L
    }

    suspend fun getAttachments(recordId: String): List<Attachment> {
        return attachmentDao.getForRecord(recordId)
    }

    /** 图文集分页：只取未删除、未锁定、带图片附件的记录。 */
    suspend fun getAlbumPage(limit: Int, offset: Int): List<AlbumItem> = withContext(Dispatchers.IO) {
        val records = recordDao.getRecordsWithImageAttachments(limit, offset)
        if (records.isEmpty()) return@withContext emptyList()
        val attachments = attachmentDao.getForRecords(records.map { it.id })
            .filter { it.type == "IMAGE" && !it.isEncrypted }
            .groupBy { it.recordId }
        records.map { record ->
            AlbumItem(record, attachments[record.id].orEmpty().map { it.filePath })
        }
    }

    suspend fun countImageRecords(): Int = withContext(Dispatchers.IO) {
        recordDao.countImageRecords()
    }

    /**
     * 图文集全量时间索引：(记录 id, 创建时间)，顺序与 [getAlbumPage] 一致（最新在前）。
     * 供月份选择器与"跳到某月"用 —— 不能依赖已懒加载的那几页，否则一开始只有最近两个月。
     */
    suspend fun getAlbumTimeIndex(): List<Pair<String, Long>> = withContext(Dispatchers.IO) {
        recordDao.getImageRecordTimes().map { it.id to it.createdAt }
    }

    suspend fun saveAttachment(attachment: Attachment) {
        // 去重：同 record 同 file_name 不再重复插入
        val existing = attachmentDao.findByRecordIdAndFileName(attachment.recordId, attachment.fileName)
        if (existing != null) return
        attachmentDao.insert(attachment)
        val record = recordDao.getRecordById(attachment.recordId) ?: return
        recordDao.updateRecord(record.copy(attachmentsCount = record.attachmentsCount + 1))
    }

    suspend fun deleteAttachment(id: Long) {
        val prev = attachmentDao.getById(id)
        attachmentDao.delete(id)
        if (prev != null) {
            val record = recordDao.getRecordById(prev.recordId) ?: return
            recordDao.updateRecord(record.copy(attachmentsCount = (record.attachmentsCount - 1).coerceAtLeast(0)))
            try { java.io.File(prev.filePath).delete() } catch (_: Exception) {}
        }
    }

    suspend fun getAttachmentCount(recordId: String): Int {
        return attachmentDao.countForRecord(recordId)
    }

    suspend fun deleteAllAttachments(recordId: String) {
        attachmentDao.deleteAllForRecord(recordId)
    }

    suspend fun analyzeRecord(
        recordId: String,
        apiUrl: String,
        modelName: String,
        content: String,
        apiKey: String = ""
    ): Result<AiAnalysisResult> = withContext(Dispatchers.IO) {
        val tStart = System.currentTimeMillis()
        var conn: HttpURLConnection? = null
        try {
            LogUtil.i("AI-2 recordId=$recordId, contentLength=${content.length}")

                // 截断已移除（2026-11-08）：全库最长 4952 字、仅 3 条超 4096，
                // 而按行切分对"一段到底"的记录根本无效，还会悄悄改变被分析的内容。
                val promptContent = content

            // 关联边：先取候选旧记录（向量 Top-N，按月分桶），再让 AI 从里面挑
            val candidates = runCatching { linkCandidatesProvider?.invoke(recordId) }
                .getOrNull().orEmpty()
            linkCandidatesBlockIds = candidates.map { it.id }.toSet()
            lastCandidateScores = candidates.associate { it.id to it.score }
            lastCandidateIndexToId = candidates.mapIndexed { i, c -> (i + 1).toString() to c.id }.toMap()
            lastCandidates = candidates
            lastDroppedLinks = 0
        lastDroppedEmotions = 0
        lastRecordContent = promptContent
        lastAnalysisInputHash = inputFingerprint(promptContent)
            val candidateBlock = if (candidates.isEmpty()) "" else buildString {
                append("【候选旧记录】（只能从这里选；每行：编号｜月份｜片段）\n")
                candidates.forEachIndexed { i, c ->
                    append("- ").append(i + 1).append("｜").append(c.month).append("｜").append(c.text).append('\n')
                }
                append('\n')
            }
            LogUtil.i("AI-Link 候选 ${candidates.size} 条: ${candidates.joinToString { it.month }}")
            val template = memore.app.core.ai.Prompts.getPrompt(memore.app.core.ai.Prompts.KEY_ANALYZE, memore.app.core.ai.AiProtocol.isThinkingEnabled())
            val renderedTemplate = memore.app.core.ai.Prompts.replacePrompt(
            template, mapOf("prompt_version" to memore.app.core.ai.Prompts.ANALYZE_PROMPT_VERSION)
        )
            // 自定义提示词不会自动包含新字段：缺 anchors 就补一段（不覆盖用户自己的措辞）。
            val rendered = if (renderedTemplate.contains("\"anchors\"")) renderedTemplate
                           else renderedTemplate + "\n\n" + memore.app.core.ai.Prompts.ANALYZE_FIELDS_SUPPLEMENT
            val fullPrompt = "$rendered$candidateBlock$promptContent"

            val body = memore.app.core.ai.AiProtocol.buildBody(
                apiUrl, modelName, fullPrompt,
                temperature = 0.3, numPredict = 4096, numCtx = 8192, formatJson = true
            )

            val fullUrl = memore.app.core.ai.AiProtocol.completionUrl(apiUrl)

            LogUtil.i("AI-3 请求 URL=$fullUrl, model=$modelName, bodyLength=${body.length}, bodyLength=${body.length}")
            LogUtil.i("AI-P1 Prompt 已发送")

            val url = URL(fullUrl)
            conn = url.openConnection() as HttpURLConnection
            conn!!.connectTimeout = 10000
            conn!!.readTimeout = 300000
            conn!!.requestMethod = "POST"
            conn!!.setRequestProperty("Content-Type", "application/json")
            memore.app.core.ai.AiProtocol.applyAuth(conn!!, apiKey)
            conn!!.doOutput = true
            OutputStreamWriter(conn!!.outputStream, "UTF-8").use { it.write(body) }
            val code = conn!!.responseCode

            val tOllama = System.currentTimeMillis()
            LogUtil.i("AI-PERF Ollama 响应返回耗时: ${tOllama - tStart}ms")

            if (code == 404) return@withContext Result.failure(Exception("未找到模型，请确认已下载指定模型"))
            if (code == 500) return@withContext Result.failure(Exception("AI 服务内部错误，请检查 Ollama 日志"))
            if (code == 401) return@withContext Result.failure(Exception("API Key 无效或未配置"))
            if (code == 429) return@withContext Result.failure(Exception("请求过于频繁，请稍后重试"))
            if (code != 200) return@withContext Result.failure(Exception("AI 服务返回错误 (HTTP $code)"))
            val response = BufferedReader(InputStreamReader(conn!!.inputStream, "UTF-8")).use { it.readText() }
            LogUtil.i("AI-4 HTTP Code=$code, responseLength=${response.length}, first80=${response.take(80).replace("\n"," ").take(40)}")
            LogUtil.i("AI-P1 Raw Response length: ${response.length}")

            val innerRaw = memore.app.core.ai.AiProtocol.extractText(response, apiUrl)
            val parsedRaw = parseAiResponse(innerRaw, modelName)
            // 2026-11-21：v8 提示词不再让模型输出 meta（模型名它会瞎填），这里由客户端补齐。
            val parsed = parsedRaw.copy(
                meta = parsedRaw.meta.copy(
                    model = modelName,
                    created_at = parsedRaw.meta.created_at.ifBlank {
                        java.time.Instant.now().toString()
                    },
                    analysis_version = "1.0",
                    prompt_version = memore.app.core.ai.Prompts.ANALYZE_PROMPT_VERSION
                )
            )
            val tParse = System.currentTimeMillis()
            LogUtil.i("AI-PERF 解析耗时: ${tParse - tOllama}ms")
            LogUtil.i("AI-PERF 总耗时: ${tParse - tStart}ms")
            LogUtil.i("AI-P1 Parse Success: summary=${parsed.summary.take(30)}, concepts=${parsed.memory_nodes.size}, emotion=${parsed.emotion.size}, emotionTags=${parsed.emotionTags.size}, droppedEmotion=$lastDroppedEmotions, droppedLink=$lastDroppedLinks")
            Result.success(parsed)

        } catch (e: java.net.SocketTimeoutException) {
            LogUtil.e("AI-P1", "analyzeRecord", "超时 - ${e.message}")
            Result.failure(Exception("AI 服务响应超时，请稍后重试"))
        } catch (e: UnknownHostException) {
            LogUtil.e("AI-P1", "analyzeRecord", "网络 - ${e.message}")
            Result.failure(Exception("无法连接 AI 服务，请检查网络或服务地址"))
        } catch (e: java.net.ConnectException) {
            LogUtil.e("AI-P1", "analyzeRecord", "连接 - ${e.message}")
            Result.failure(Exception("无法连接 AI 服务，请检查网络或服务地址"))
        } catch (e: javax.net.ssl.SSLException) {
            LogUtil.e("AI-P1", "analyzeRecord", "SSL - ${e.message}")
            Result.failure(Exception("SSL 错误，请检查服务地址"))
        } catch (e: Exception) {
            LogUtil.e("AI-P1", "analyzeRecord", e)
            Result.failure(Exception("AI 分析失败: ${e.message}"))
        } finally {
            conn?.disconnect()
        }
    }

    suspend fun saveAiAnalysisResult(recordId: String, result: AiAnalysisResult) {
        val record = recordDao.getRecordById(recordId) ?: return
        val rawTags = if (result.emotionTags.isNotEmpty()) {
            result.emotionTags
        } else {
            result.emotion.map { EmotionTag(EmotionNormalizer.normalize(it), null) }
        }
        val cleanedTags = EmotionStats.dedupeEmotionTags(rawTags)
        val tagsJson = GsonInstance.gson.toJson(cleanedTags)
        LogUtil.i("AI-P1 emotionalTags 写入: $tagsJson")

        // 如果这次没产出总结（比如切到"不输出总结"预设），**保留原来那一段**，不要清空
        val previousSummary = record.analysis?.summary?.takeIf { it.isNotBlank() }
            ?: record.dialogueAiContent.takeIf { it.isNotBlank() }
            ?: ""
        val newSummary = result.summary.ifBlank { previousSummary }

        val contentTruncated = (record.content.length > 4096)
        val analysisData = AnalysisData(
            summary = if (contentTruncated) newSummary + "\n\n（内容过长，仅分析最近部分）" else newSummary,
            memory_nodes = result.memory_nodes,
        emotion = cleanedTags.map { EmotionTagEntry(it.name, it.weight ?: 0f, it.trend, it.why) },
            relations = result.relations,
            meta = AnalysisMeta(
                model = result.meta.model,
                created_at = formatIsoZ(),
                analysis_version = result.meta.analysis_version,
        prompt_version = memore.app.core.ai.Prompts.ANALYZE_PROMPT_VERSION,
                content_truncated = contentTruncated
            )
        )
        val analysisJson = GsonInstance.gson.toJson(analysisData)
        LogUtil.i("AI-P1 analysis 写入: $analysisJson")

        val tx = db
        if (tx != null) {
            tx.withTransaction {
                recordDao.updateRecordWithAi(recordId, newSummary, tagsJson, analysisJson)
                persistLinks(recordId, result.links)
                saveAnchors(recordId, result.anchors)
                recordDao.updateAnalysisInputHash(recordId, lastAnalysisInputHash)
                tx.analysisVersionDao().insertThenTrim(
                    memore.app.data.model.AnalysisVersion(
                        recordId = recordId,
                        model = result.meta.model,
                        promptVersion = memore.app.core.ai.Prompts.ANALYZE_PROMPT_VERSION,
                        inputHash = lastAnalysisInputHash,
                        summary = newSummary,
                        emotionJson = tagsJson,
                        status = "OK"
                    )
                )
            }
        } else {
            recordDao.updateRecordWithAi(recordId, newSummary, tagsJson, analysisJson)
            persistLinks(recordId, result.links)
            saveAnchors(recordId, result.anchors)
            recordDao.updateAnalysisInputHash(recordId, lastAnalysisInputHash)
        }

    }

    suspend fun analyzeRecordAndSave(
        recordId: String,
        apiUrl: String,
        modelName: String,
        content: String,
        apiKey: String = ""
    ): Result<String> = withContext(Dispatchers.IO) {
        val t0 = System.currentTimeMillis()
        val analysisResult = analyzeRecord(recordId, apiUrl, modelName, content, apiKey)
        val durationMs = System.currentTimeMillis() - t0

        if (analysisResult.isSuccess) {
            val aiResult = analysisResult.getOrThrow()
            saveAiAnalysisResult(recordId, aiResult)
            logRun(recordId, content, modelName, durationMs, true, null, aiResult)
            Result.success(aiResult.summary)
        } else {
            recordDao.updateAiStatus(recordId, "FAILED")
            val err = analysisResult.exceptionOrNull() ?: Exception("分析失败")
            // 2026-11-19：失败也留一版（不覆盖上一次成功的版本），界面能回看到"这次为什么没成"。
            runCatching {
                db?.analysisVersionDao()?.insertThenTrim(
                    memore.app.data.model.AnalysisVersion(
                        recordId = recordId,
                        model = modelName,
                        promptVersion = memore.app.core.ai.Prompts.ANALYZE_PROMPT_VERSION,
                        inputHash = inputFingerprint(content),
                        status = "FAILED",
                        reason = (err.message ?: "未知错误").take(200)
                    )
                )
            }
            logRun(recordId, content, modelName, durationMs, false, err.message, null)
            Result.failure(err)
        }
    }

    /** 把这次调用写进"AI 记录"（本地 JSONL，界面可看）。失败只记原因，不影响主流程。 */
    private fun logRun(
        recordId: String,
        content: String,
        model: String,
        durationMs: Long,
        ok: Boolean,
        error: String?,
        result: AiAnalysisResult?
    ) {
        val ctx = appContext ?: return
        runCatching {
            memore.app.data.vector.AiRunLog.append(ctx, memore.app.data.vector.AiRunLog.Run(
                at = System.currentTimeMillis(),
                recordId = recordId,
                recordSnippet = content.replace(Regex("\\s+"), " ").take(60),
                ok = ok,
                error = error?.take(120),
                durationMs = durationMs,
                candidates = lastCandidates.map {
                    memore.app.data.vector.AiRunLog.Candidate(it.month, it.text.take(60), it.score)
                },
                links = result?.links?.map { l ->
                    val c = lastCandidates.firstOrNull { it.id == l.to }
                    memore.app.data.vector.AiRunLog.Link(
                        toId = l.to.take(8),
                        toMonth = c?.month ?: "",
                        why = l.why,
                        score = lastCandidateScores[l.to] ?: 0f
                    )
                } ?: emptyList(),
                dropped = lastDroppedLinks,
                summaryLen = result?.summary?.length ?: 0,
                model = model
            ))
        }
    }

    /**
     * 锚点落库（2026-11-19）：重建这条记录的锚点（先删后插，重跑分析不会叠加旧锚点）。
     *
     * 2026-11-21：落库前**再用代码过滤一遍**，不把判断全交给提示词。
     * 实测模型仍会吐「我 / 你 / 2026年7月17日 / JSON 协议 / 半句话」这类东西，
     * 它们会变成「按概念」里最显眼的锚，把真正的锚挤下去。
     */
    private suspend fun saveAnchors(recordId: String, anchors: List<AiAnalysisResult.AiAnchor>) {
        val dao = db?.recordEntityDao() ?: return
        dao.deleteFor(recordId)
        val kept = anchors
            .map { it.copy(name = it.name.trim()) }
            .filter { it.name.isNotEmpty() && !isNoiseAnchor(it.name) }
            .distinctBy { it.name.lowercase() }
        if (kept.isEmpty()) return
        val now = System.currentTimeMillis()
        val items = kept.map {
            memore.app.data.model.RecordEntity(
                recordId = recordId,
                name = it.name,
                kind = it.kind,
                quote = it.quote,
                createdAt = now
            )
        }
        dao.insertAll(items)
        LogUtil.i("AI-Anchors 写入 ${items.size} 个（原始 ${anchors.size}，过滤 ${anchors.size - items.size}）")
    }

    /**
     * 把 AI 判定出的关联边写进 record_relations。
     * 先清掉这条作为发起方的 ACTIVE 边（重建语义），REJECTED 的保留当负样本、也不再推荐。
     */
    private suspend fun persistLinks(recordId: String, links: List<AiAnalysisResult.AiLink>) {
        val dao = db?.recordRelationDao() ?: return
        if (links.isEmpty()) {
            // 有候选却一条都没挑 → 这是"这次判断没有线"，旧边应失效（不是保留）。
            // 没有候选时不清：模型根本没机会判断，清掉等于误删历史。
            if (lastCandidates.isNotEmpty()) dao.expireActiveLinksFrom(recordId, System.currentTimeMillis())
            return
        }
        val rejected = runCatching { dao.rejectedFrom(recordId).map { it.toId }.toSet() }
            .getOrDefault(emptySet())
        val now = System.currentTimeMillis()
        dao.expireActiveLinksFrom(recordId, now)
        // 2026-11-21（照 LightRAG：关系按无向处理）：反向边已存在时不再建这一条，
        // 同一对记录只留一条，关联页不会两边各显示一次。
        val reverseBusy = HashSet<String>()
        for (l in links) {
            val to = l.to
            if (to != recordId && to !in rejected && dao.reverseActiveCount(recordId, to) > 0) reverseBusy.add(to)
        }
        links.asSequence()
            .filter { it.to != recordId && it.to !in rejected && it.to !in reverseBusy }
            .distinctBy { it.to }
            .forEach { l ->
                val type = if (l.type in setOf("CORRECT", "SUPPLEMENT", "CITE")) l.type
                           else memore.app.data.model.RecordRelationType.LINK
                val score = (lastCandidateScores[l.to] ?: 0f).toDouble()
                // 唯一索引在 (from,to,type)：已失效的同一条边要"复活"，不能直接 INSERT（会被 IGNORE）。
                val revived = dao.revive(
                    fromId = recordId, toId = l.to, type = type, now = now,
                    why = l.why, quoteA = l.a, quoteB = l.b, score = score
                )
                if (revived == 0) {
                    dao.insert(
                        memore.app.data.model.RecordRelation(
                            fromId = recordId,
                            toId = l.to,
                            type = type,
                            why = l.why,
                            quoteA = l.a,
                            quoteB = l.b,
                            score = score,
                            source = "AI",
                            validFrom = now
                        )
                    )
                }
            }
        LogUtil.i("AI-Link 写入 ${links.size} 条边")
    }
    private fun parseAiResponse(response: String, modelName: String): AiAnalysisResult {
        val cleaned = JsonCleaner.clean(response)
        LogUtil.i("AI-P1 Cleaner Result length: ${cleaned.length}")

        val jsonObject = org.json.JSONObject(cleaned)

        val concepts = jsonObject.optJSONArray("memory_nodes")?.let { arr ->
            (0 until arr.length()).map { arr.getString(it) }
        } ?: emptyList()

        val emotionList = mutableListOf<String>()
        val emotionTagList = mutableListOf<EmotionTag>()
        jsonObject.optJSONArray("emotion")?.let { arr ->
            for (i in 0 until arr.length()) {
                val item = arr.opt(i)
                when (item) {
                    is String -> {
                        emotionList.add(item)
                        emotionTagList.add(EmotionTag(name = item, weight = null))
                    }
                    is org.json.JSONObject -> {
                        val name = item.optString("name", "").trim()
                        val weight = if (item.has("weight")) item.optDouble("weight", 0.0).toFloat() else null
                        val trend = item.optString("trend", null)?.takeIf { it == "up" || it == "down" || it == "stable" }
                        val why = item.optString("why", "").trim()
                        // 依据校验（2026-11-08）：why 必须能在正文里找到（允许 8 字片段命中，容忍少量改写），
                        // 找不到就丢这个标签 —— 否则模型会写成概括/同义反复。
                        val grounded = why.isNotBlank() && groundedIn(why, lastRecordContent)
                        if (name.isNotBlank() && grounded) {
                            emotionList.add(name)
                            emotionTagList.add(EmotionTag(name = name, weight = weight, trend = trend, why = why))
                        } else if (name.isNotBlank()) {
                            // 依据不扎根原文 → 丢弃并计数（以前静默丢弃，日志看不出来「这条其实残缺」）
                            lastDroppedEmotions++
                        }
                    }
                }
            }
        }
        val emotion = emotionList

        val relations = jsonObject.optJSONArray("relations")?.let { arr ->
            (0 until arr.length()).map { arr.optString(it, "") }
        } ?: emptyList()

        // 关联边：只认白名单里的 id（防幻觉），why 截断到 20 字
        val allowed = linkCandidatesBlockIds
        val byIndex = lastCandidateIndexToId
        // 引证校验（2026-11-08）：a 必须能在这条记录里找到、b 必须在模型看到的候选片段里找到，
        // 对不上就丢这条边 —— 这是唯一能真正约束模型"别瞎编"的手段（子串匹配，不靠它自觉）。
        val selfText = lastRecordContent
        val candTexts = lastCandidates.associate { it.id to it.text }
        val links = jsonObject.optJSONArray("links")?.let { arr ->
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val raw = (o.optString("to", "")).ifBlank { o.optString("id", "") }.trim()
                if (raw.isBlank()) return@mapNotNull null
                val id = byIndex[raw] ?: raw
                if (allowed.isNotEmpty() && id !in allowed) { lastDroppedLinks++; return@mapNotNull null }
                val a = o.optString("a", "").trim()
                val b = o.optString("b", "").trim()
                val rel = o.optString("rel", "").trim().take(15)
                if (a.isBlank() || b.isBlank() || rel.isBlank()) { lastDroppedLinks++; return@mapNotNull null }
                val candText = candTexts[id] ?: ""
                // 依据必须扎根原文（2026-11-08 起就是硬校验；2026-11-19 抽成函数并加测试）。
                if (!linkEvidenceGrounded(selfText, candText, a, b)) { lastDroppedLinks++; return@mapNotNull null }
                // 2026-11-19：a/b 不再塞进 why 字符串——分别入库，界面直接展示"凭什么连"。
                AiAnalysisResult.AiLink(
                    to = id, why = rel.take(20), a = a.take(40), b = b.take(40),
                    type = normalizedRelationType(o.optString("type", ""))
                )
            }
        } ?: emptyList()


        // 锚点（2026-11-19）：只认正文里明确出现、且原句能在正文找到的；最多 5 个。
        val anchors = jsonObject.optJSONArray("anchors")?.let { arr ->
            val seen = HashSet<String>()
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val name = o.optString("name", "").trim()
                val quote = o.optString("quote", "").trim()
                val kind = o.optString("kind", "OTHER").trim().uppercase()
                if (!memore.app.data.model.RecordEntity.isValidName(name)) return@mapNotNull null
                // 名字本身也必须在正文里出现过（防模型编一个"看起来像人名"的词）
                if (!selfText.lowercase().contains(name.lowercase())) return@mapNotNull null
                if (!anchorGrounded(quote, selfText)) return@mapNotNull null
                val k = if (kind in memore.app.data.model.RecordEntity.KINDS) kind else "OTHER"
                if (!seen.add(name.lowercase() + "|" + k)) return@mapNotNull null
                memore.app.data.model.AiAnalysisResult.AiAnchor(name = name.take(20), kind = k, quote = quote.take(40))
            }.take(5)
        } ?: emptyList()
        LogUtil.i("AI-Anchors 解析并通过校验: ${anchors.size} 个" +
            (if (anchors.isEmpty() && jsonObject.has("anchors")) "（模型给了 ${jsonObject.optJSONArray("anchors")?.length() ?: 0} 个，全被校验丢弃）" else ""))

        val metaObj = jsonObject.optJSONObject("meta")
        val meta = AiAnalysisResult.Meta(
            model = metaObj?.optString("model", "") ?: "",
            created_at = metaObj?.optString("created_at", "") ?: "",
            analysis_version = metaObj?.optString("analysis_version", "1.0") ?: "1.0",
            prompt_version = metaObj?.optString("prompt_version", "1") ?: "1"
        )

        return AiAnalysisResult(
            version = jsonObject.optString("version", "1.0"),
            summary = jsonObject.optString("summary", "").trim(),
            memory_nodes = concepts,
            emotion = emotion,
            emotionTags = emotionTagList,
            relations = relations,
            links = links,
            anchors = anchors,
            meta = meta
        )
    }

    /** 关联边总数（只算 ACTIVE 的 LINK）。 */
    suspend fun linkCount(): Int = runCatching {
        db?.recordRelationDao()?.all()?.count { it.type == "LINK" && it.status == "ACTIVE" && it.validTo == null } ?: 0
    }.getOrDefault(0)

    /** 全表关联边总数（AI + USER、各种状态，重算前清空用）。 */
    suspend fun relationRowCount(): Int = runCatching {
        db?.recordRelationDao()?.count() ?: 0
    }.getOrDefault(0)

    /**
     * 清空全部关联边（2026-11-21，整库重算前用）。
     * 不分来源、不分方向、一次性删干净；返回删掉的条数。
     */
    suspend fun clearAllRelations(): Int {
        val dao = db?.recordRelationDao() ?: return 0
        val before = runCatching { dao.count() }.getOrDefault(0)
        runCatching { dao.deleteAllNow() }
        return before
    }

/** 一条边的两端（给「关联」页用）。 */
    data class LinkPair(
        val from: Record,
        val to: Record,
        val why: String,
        val createdAt: Long,
        val score: Float = 0f,
        /** AI 边才有的两句原句；老边为空，界面回落显示 why。 */
        val quoteA: String = "",
        val quoteB: String = "",
        /** 关系类型：LINK / CITE / SUPPLEMENT / CORRECT。 */
        val type: String = "LINK"
    )

    /** 所有 ACTIVE 的关联边，按时间倒序；两端记录都在才返回（悬空边不显示）。 */
    suspend fun allLinksWithRecords(limit: Int = 200): List<LinkPair> {
        val dao = db?.recordRelationDao() ?: return emptyList()
        // 2026-11-21（照 LightRAG：关系按无向处理）：A→B 与 B→A 同时存在时只留一条，
        // 优先留有 why/引文的那条，其次较新的；否则同一条关系在两条记录下各出现一次。
        val rels = runCatching { dao.all() }.getOrDefault(emptyList())
            .filter { it.type == "LINK" && it.status == "ACTIVE" && it.validTo == null }
            .groupBy { if (it.fromId < it.toId) it.fromId + "|" + it.toId else it.toId + "|" + it.fromId }
            .values
            .map { candidates ->
                candidates.sortedWith(
                    compareByDescending<memore.app.data.model.RecordRelation> {
                        it.why.isNotBlank() || it.quoteA.isNotBlank()
                    }.thenByDescending { it.createdAt }
                ).first()
            }
            .sortedByDescending { it.score }   // 关联页按"像的程度"排，时间只用于同分
            .take(limit)
        if (rels.isEmpty()) return emptyList()
        val ids = (rels.map { it.fromId } + rels.map { it.toId }).distinct()
        val records = getRecordsByIds(ids).filter { !it.isDeleted && !it.isLocked }.associateBy { it.id }
        return rels.mapNotNull { r ->
            val f = records[r.fromId] ?: return@mapNotNull null
            val t = records[r.toId] ?: return@mapNotNull null
            LinkPair(f, t, r.why, r.createdAt, r.score.toFloat(), r.quoteA, r.quoteB, r.type)
        }
    }

    /** 关联边视图：把 from/to 归一成"对方记录 + 一句话 why"。 */
    suspend fun linksFor(recordId: String): List<Pair<Record, String>> {
        val dao = db?.recordRelationDao() ?: return emptyList()
        val rels = runCatching { dao.linksOf(recordId) }.getOrDefault(emptyList())
        if (rels.isEmpty()) return emptyList()
        val records = getRecordsByIds(rels.map { if (it.fromId == recordId) it.toId else it.fromId })
            .filter { !it.isDeleted && !it.isLocked }
            .associateBy { it.id }
        // 同一对记录只显示一次：A 分析过、B 后来也分析过时，镇上会同时存在 A→B 和 B→A 两条，
        // 界面上不能出现两遍。优先用「对方向着自己」的那条 why（语义更贴当前记录）。
        val seen = HashSet<String>()
        return rels.mapNotNull { r ->
            val oid = if (r.fromId == recordId) r.toId else r.fromId
            if (!seen.add(oid)) return@mapNotNull null
            records[oid]?.let { it to r.why }
        }
    }

    /** 否掉一条边（留作负样本，下次不再推荐）。 */
    suspend fun rejectLink(recordId: String, otherId: String) {
        db?.recordRelationDao()?.let { dao ->
            dao.rejectLink(recordId, otherId)
            dao.rejectLink(otherId, recordId)
        }
    }

    suspend fun FIX(): Int {
        val records = recordDao.getAllActiveRecords().filter { it.analysis != null }
        val now = formatIsoZ()
        var count = 0
        records.forEach { r ->
            val a = r.analysis ?: return@forEach
            val fixed = a.copy(meta = a.meta.copy(created_at = now))
            val json = GsonInstance.gson.toJson(fixed)
            recordDao.updateAnalysis(r.id, json)
            count++
        }
        return count
    }

    // ---- 加密锁定/解锁 ----

    /** 锁定记录及其增修：使用缓存密钥加密内容 + 附件 → 更新 DB（无需再传密码） */
    suspend fun lockRecord(id: String): Result<Unit> {
        // v5（2026-11-16）：门闩式——**只置标记，不加密**。
        // 内容保持明文，锁定只影响 UI 与检索的可见性。
        if (memore.app.utils.EncryptionManager.isV5()) {
            return try {
                if (!memore.app.utils.EncryptionManager.isConfigured()) {
                    return Result.failure(Exception("请先在「加密管理」里设置密码"))
                }
                val parent = recordDao.getRecordById(id) ?: return Result.failure(Exception("记录不存在"))
                if (parent.isLocked) return Result.failure(Exception("该记录已锁定"))
                val ids = listOf(parent.id) + recordDao.getAmendmentsForRecord(id).map { it.id }
                recordDao.markLocked(ids)
                Result.success(Unit)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
        return try {
            val key = memore.app.utils.EncryptionManager.getCachedKey()
                ?: return Result.failure(Exception("请先输入加密密码"))

            val parent = recordDao.getRecordById(id) ?: return Result.failure(Exception("记录不存在"))
            if (parent.isLocked) return Result.failure(Exception("该记录已锁定"))

            // 只锁定未锁定的目标（防御旧数据中已锁定的增修：重新打包会丢失已清空的明文）
            val targets = (listOf(parent) + recordDao.getAmendmentsForRecord(id)).filter { !it.isLocked }
            val rows = targets.map { r ->
                val payload = memore.app.utils.EncryptionManager.packRecordPayload(r)
                val result = memore.app.utils.EncryptionManager.encryptContent(payload, key)
                LockRow(r.id, result.encryptedData, result.iv, result.authTag)
            }
            recordDao.lockRecordCascade(rows)

            // 附件文件 + 文件名加密（事务外；失败则回滚已锁定行 + 已加密文件）
            var encryptedFiles = mutableListOf<Pair<Attachment, memore.app.utils.AttachmentCryptoResult>>()
            try {
                targets.forEach { r ->
                    attachmentDao.getForRecord(r.id).filter { !it.isEncrypted }.forEach { att ->
                        val file = File(att.filePath)
                        if (!file.exists()) return@forEach
                        val cr = memore.app.utils.EncryptionManager.encryptFileInPlace(file, key)
                        encryptedFiles.add(att to cr)
                        val encName = memore.app.utils.EncryptionManager.encryptFileName(att.fileName, key)
                        attachmentDao.encryptAttachment(att.id, cr.iv, cr.authTag, encName, "enc_${att.id}")
                    }
                }
            } catch (e: Exception) {
                try {
                    val rollbackRows = targets.map { r ->
                        val row = rows.first { it.id == r.id }
                        val p = memore.app.utils.EncryptionManager.unpackRecordPayload(
                            memore.app.utils.EncryptionManager.decryptContent(row.encData, row.iv, row.tag, key)
                        )
                        UnlockRow(r.id, p.content, p.notes, p.dialogueAiContent, p.dialogueAiReply, p.analysisJson, p.emotionalTags)
                    }
                    recordDao.unlockRecordCascade(rollbackRows)
                    encryptedFiles.forEach { (att, cr) ->
                        try { memore.app.utils.EncryptionManager.decryptFileInPlace(File(att.filePath), cr.iv, cr.authTag, key) } catch (_: Exception) {}
                        try { attachmentDao.decryptAttachment(att.id, att.fileName) } catch (_: Exception) {}
                    }
                } catch (_: Exception) {}
                return Result.failure(e)
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** 解锁记录及其增修：验证密钥 → 解密 → 恢复明文 + 附件 → 更新 DB */
    suspend fun unlockRecord(id: String, password: String): Result<Unit> {
        // v5（2026-11-16）：门闩式——验密码，然后清标记。正文本来就是明文。
        if (memore.app.utils.EncryptionManager.isV5()) {
            return try {
                val parent = recordDao.getRecordById(id) ?: return Result.failure(Exception("记录不存在"))
                if (!parent.isLocked) return Result.failure(Exception("该记录未锁定"))
                if (!memore.app.utils.EncryptionManager.verifyLockPassword(password)) {
                    return Result.failure(Exception("密码不对"))
                }
                val ids = listOf(parent.id) + recordDao.getAmendmentsForRecord(id).map { it.id }
                recordDao.markUnlocked(ids)
                Result.success(Unit)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
        return try {
            val keyResult = memore.app.utils.EncryptionManager.ensureKey(password)
            if (keyResult.isFailure) return Result.failure(keyResult.exceptionOrNull()!!)

            val parent = recordDao.getRecordById(id) ?: return Result.failure(Exception("记录不存在"))
            if (!parent.isLocked) return Result.failure(Exception("该记录未锁定"))

            val key = memore.app.utils.EncryptionManager.getCachedKey()!!
            val targets = listOf(parent) + recordDao.getAmendmentsForRecord(id)
            val lockedTargetIds = targets.filter { it.isLocked }.map { it.id }.toSet()
            val rows = targets.map { r ->
                if (r.isLocked) {
                    val p = payloadOfLockedRecord(r, key)
                    // 旧数据回填：升级前锁定记录的 analysis/emotional_tags 仍在明文列
                    val analysisJson = if (!p.hasAnalysis) r.analysis?.let { GsonInstance.gson.toJson(it) } else p.analysisJson
                    val emotionalTags = if (!p.hasEmotionalTags) r.emotionalTags else p.emotionalTags
                    UnlockRow(r.id, p.content, p.notes, p.dialogueAiContent, p.dialogueAiReply, analysisJson, emotionalTags)
                } else {
                    // 旧数据：该增修未锁定，直接取明文列（避免对空密文解密报错）
                    UnlockRow(r.id, r.content, r.notes, r.dialogueAiContent, r.dialogueAiReply, r.analysis?.let { GsonInstance.gson.toJson(it) }, r.emotionalTags)
                }
            }
            recordDao.unlockRecordCascade(rows)

            // 附件文件 + 文件名解密（事务外；失败则回滚已解锁行 + 已解密文件）
            var decryptedFiles = mutableListOf<Attachment>()
            try {
                targets.forEach { r ->
                    attachmentDao.getForRecord(r.id).filter { it.isEncrypted }.forEach { att ->
                        val file = File(att.filePath)
                        if (!file.exists()) return@forEach
                        val origName = memore.app.utils.EncryptionManager.decryptFileName(att.encName, key) ?: att.fileName
                        memore.app.utils.EncryptionManager.decryptFileInPlace(file, att.iv, att.authTag, key)
                        decryptedFiles.add(att)
                        attachmentDao.decryptAttachment(att.id, origName)
                    }
                }
            } catch (e: Exception) {
                try {
                    // 只回滚原本就锁定的行（避免把旧版未锁定的增修意外锁定）
                    val rollbackRows = rows.filter { it.id in lockedTargetIds }.map { row ->
                        val p = memore.app.utils.EncryptionManager.packPayload(
                            memore.app.utils.RecordPayload(
                                row.content, row.notes, row.dac, row.dialogueAiReply, row.analysis, row.emotionalTags,
                                row.analysis != null, true
                            )
                        )
                        val res = memore.app.utils.EncryptionManager.encryptContent(p, key)
                        LockRow(row.id, res.encryptedData, res.iv, res.authTag)
                    }
                    recordDao.lockRecordCascade(rollbackRows)
                    decryptedFiles.forEach { att ->
                        try {
                            val cr = memore.app.utils.EncryptionManager.encryptFileInPlace(File(att.filePath), key)
                            val encName = memore.app.utils.EncryptionManager.encryptFileName(att.fileName, key)
                            attachmentDao.encryptAttachment(att.id, cr.iv, cr.authTag, encName, "enc_${att.id}")
                        } catch (_: Exception) {}
                    }
                } catch (_: Exception) {}
                return Result.failure(e)
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 取一条**已锁定**记录的明文载荷。
     *
     * 「待真锁」（`is_locked=1` 但 `encrypted_data` 为空 —— 导入的"解密导出"曾锁定记录，尚未补锁）的明文
     * **就在明文列里**，此时**绝不能**拿空密文去解密：`decryptContent("")` 会因 AEAD 校验失败抛异常，
     * 让"解锁 / 临时查看"**整体报错** —— 用户即使密码正确也打不开。
     * （那批记录本来也还没被真正加密；`lockPendingEncryptionRecords` 会随后把它们锁上。）
     */
    private fun payloadOfLockedRecord(record: Record, key: javax.crypto.SecretKey): memore.app.utils.RecordPayload {
        if (record.encryptedData.isEmpty()) {
            val analysisJson = record.analysis?.let { GsonInstance.gson.toJson(it) }
            return memore.app.utils.RecordPayload(
                content = record.content,
                notes = record.notes,
                dialogueAiContent = record.dialogueAiContent,
                dialogueAiReply = record.dialogueAiReply,
                analysisJson = analysisJson,
                emotionalTags = record.emotionalTags,
                hasAnalysis = analysisJson != null,
                hasEmotionalTags = true
            )
        }
        return memore.app.utils.EncryptionManager.unpackRecordPayload(
            memore.app.utils.EncryptionManager.decryptContent(record.encryptedData, record.iv, record.authTag, key)
        )
    }

    /** 获取锁定记录的解密内容（不修改 DB，用于临时查看） */
    suspend fun getDecryptedPayload(id: String): Result<memore.app.utils.RecordPayload> {
        return try {
            val key = memore.app.utils.EncryptionManager.getCachedKey()
                ?: return Result.failure(Exception("请先输入加密密码"))
            val record = recordDao.getRecordById(id) ?: return Result.failure(Exception("记录不存在"))
            if (!record.isLocked) return Result.failure(Exception("该记录未锁定"))
            val p = payloadOfLockedRecord(record, key)
            // 旧数据回填，保证返回完整明文
            val analysisJson = if (!p.hasAnalysis) record.analysis?.let { GsonInstance.gson.toJson(it) } else p.analysisJson
            val emotionalTags = if (!p.hasEmotionalTags) record.emotionalTags else p.emotionalTags
            Result.success(p.copy(analysisJson = analysisJson, emotionalTags = emotionalTags, hasAnalysis = analysisJson != null, hasEmotionalTags = true))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ---- 临时查看附件辅助 ----

    /** 临时查看：解密记录附件到缓存目录（不修改 attachments 表 / 原密文文件）。key 需已缓存。 */
    suspend fun decryptAttachmentsForTempView(recordId: String, cacheRoot: File): List<AttachmentView> = withContext(Dispatchers.IO) {
        val key = memore.app.utils.EncryptionManager.getCachedKey() ?: return@withContext emptyList()
        val result = mutableListOf<AttachmentView>()
        attachmentDao.getForRecord(recordId).forEach { att ->
            try {
                if (att.isEncrypted) {
                    val origName = memore.app.utils.EncryptionManager.decryptFileName(att.encName, key) ?: att.fileName
                    val decFile = File(File(cacheRoot, recordId), origName)
                    if (!decFile.exists()) {
                        memore.app.utils.EncryptionManager.decryptFileTo(File(att.filePath), decFile, att.iv, att.authTag, key)
                    }
                    result.add(AttachmentView(att.id, origName, decFile.absolutePath, att.type, att.fileSize, att.duration))
                } else {
                    result.add(AttachmentView(att.id, att.fileName, att.filePath, att.type, att.fileSize, att.duration))
                }
            } catch (_: Exception) {}
        }
        result
    }

    /** 清理临时查看缓存目录（best-effort）。 */
    fun clearTempAttachmentCache(cacheRoot: File, recordId: String? = null) {
        try {
            if (recordId != null) File(cacheRoot, recordId).deleteRecursively()
            else cacheRoot.deleteRecursively()
        } catch (_: Exception) {}
    }

    suspend fun lockedRecordCount(): Int = recordDao.lockedRecordCount()

    /**
     * 本机是否存在**真正依赖密钥**的加密数据（有密文的锁定记录）。
     *
     * 用来判断"新设备上还能不能直接设新密码"：
     * - `false`（无真加密；可能只有导入产生的「待真锁」明文）→ 设新密码是**正确流程**，允许。
     * - `true` → 覆盖配置会毁掉既有密文的可读性，必须走 `changeEncryptionPassword`（旧密码接管）。
     */
    suspend fun hasTrulyEncryptedData(): Boolean = recordDao.trulyEncryptedRecordCount() > 0

    suspend fun getLockedRecords(): List<Record> = recordDao.getAllLockedRecords()

    /**
     * 2026-11-16（v5「清除加密」）：**把当前所有锁定记录的加密状态取消**。
     *
     * 用户视角的原话：清除加密 = 取消掉所有加密记录的加密状态。
     * v5 数据层是明文，所以这件事只是把 is_locked 全部置 0 —— 不解密、不碰密码配置。
     * 返回被解锁的条数。
     */
    suspend fun clearAllLocked(): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val ids = recordDao.getAllLockedRecords().map { it.id }
            if (ids.isEmpty()) return@runCatching 0
            recordDao.markUnlocked(ids)
            ids.size
        }
    }

    /** v5 迁移判断：还剩多少条带旧密文的记录（0 = 已经全是明文）。 */
    suspend fun cipherRecordCount(): Int = recordDao.countCipherRecords()

    /** v5 迁移：把旧的逐条加密整体改写成"明文 + 门闩"。单向，UI 需先备份并让用户输旧密码。 */
    suspend fun migrateLockToV5(password: String): Result<memore.app.utils.LockMigrationV5.Report> =
        memore.app.utils.LockMigrationV5.run(password, recordDao, attachmentDao)

    /** 导出筛选（2026-11-16）：按标签 id 取记录 id。 */
    suspend fun recordIdsByTagIds(tagIds: List<Int>): List<String> = recordDao.recordIdsByTagIds(tagIds)



    /**
     * 导出进度的**预统计**：这些记录下"会被写进包"的附件数。
     * 未勾明文解密时加密附件不进包（导出侧会跳过），所以这里用同一个判据。
     * 只用于算出进度条的分母，**不参与任何写包逻辑**，改错也只会让进度条不准。
     */
    suspend fun countAttachmentsForExport(ids: List<String>, decryptLocked: Boolean): Int {
        if (ids.isEmpty()) return 0
        return try {
            attachmentDao.getForRecords(ids).count { !(it.isEncrypted && !decryptLocked) }
        } catch (_: Exception) { 0 }
    }

    /**
     * 2026-11-16：**按当前导出范围**统计附件（个数 + 字节）。
     *
     * 导出页原来用 `attachmentTotal()`（全库），所以"区域=创作"时仍显示 62 个 · 181MB ——
     * 数字和"这次会打包 16 条"自相矛盾。这里改成只算范围内的记录。
     */
    suspend fun attachmentTotalFor(ids: List<String>): Pair<Int, Long> {
        if (ids.isEmpty()) return 0 to 0L
        return try {
            val list = attachmentDao.getForRecords(ids)
            list.size to list.sumOf { java.io.File(it.filePath).length() }
        } catch (_: Exception) { 0 to 0L }
    }

    /**
     * 全库加密附件数 —— 「整体备份」前的一段提醒：
     * 未勾「明文解密」时这些附件**不会进包**（导出侧跳过），必须明确告诉用户，不能静默。
     */
    suspend fun countEncryptedAttachments(): Int = try {
        attachmentDao.countEncrypted()
    } catch (_: Exception) { 0 }

    /**
     * 把"待真锁"记录（导入的解密导出曾锁定记录，`is_locked=1` 但内容仍明文、未加密）用给定密钥真正加密。
     *
     * ⚠️ **本轮修**：同时加密该记录的**附件**。原来只锁正文 —— 于是从"解密导出"备份导入的曾锁定记录，
     * 在新设备设密码后正文锁上了、**附件仍是明文躺在磁盘上（文件名也是明文）**，用户以为锁好了，其实没有。
     * 附件写法与 [lockRecord] 保持一致（就地加密 → 文件名加密 → 落 iv/auth_tag + 换成 token 名）。
     *
     * 返回真正加密的附件数（供日志）。单条失败只跳过该条，不影响其它记录。
     */
    suspend fun lockPendingEncryptionRecords(key: javax.crypto.SecretKey): Int {
        val pending = recordDao.getPendingLockedRecords()
        var lockedAttachments = 0
        for (rec in pending) {
            try {
                val enc = memore.app.utils.EncryptionManager.encryptContent(rec.content, key)
                recordDao.lockRecord(rec.id, enc.encryptedData, enc.iv, enc.authTag)
            } catch (e: Exception) {
                LogUtil.e("RecordRepository", "lockPending ${rec.id}", e.message ?: "")
                continue   // 正文都没锁上，别动它的附件
            }
            for (att in attachmentDao.getForRecord(rec.id).filter { !it.isEncrypted }) {
                try {
                    val file = File(att.filePath)
                    if (!file.exists()) continue
                    val cr = memore.app.utils.EncryptionManager.encryptFileInPlace(file, key)
                    val encName = memore.app.utils.EncryptionManager.encryptFileName(att.fileName, key)
                    attachmentDao.encryptAttachment(att.id, cr.iv, cr.authTag, encName, "enc_${att.id}")
                    lockedAttachments++
                } catch (e: Exception) {
                    LogUtil.e("RecordRepository", "lockPending 附件 ${att.id}", e.message ?: "")
                }
            }
        }
        return lockedAttachments
    }

    /**
     * 更改密码。
     *
     * v3 起**不需要重加密任何内容**：主密钥只被重新包裹，所有记录/附件密文保持逐字节不变 →
     * 不存在中途态、不存在被杀进程丢数据的可能。存量 v1/v2 配置会在这一步被"收编"升级
     * （内容密钥直接成为主密钥），同样一个字节都不搬。
     *
     * 返回值为**已加密记录数**（供 UI 提示"这些记录已改用新密码"），不再是"重加密了多少条"。
     */
    suspend fun changeEncryptionPassword(oldPassword: String, newPassword: String, newQuestion: String, newAnswer: String): Result<Int> {
        return try {
            memore.app.utils.EncryptionManager.changePassword(oldPassword, newPassword, newQuestion, newAnswer)
                .map {
                    // 顺手把导入留下的「待真锁」（明文 + 标记）补锁到主密钥，别让它们一直躺在明文
                    try {
                        memore.app.utils.EncryptionManager.getCachedKey()?.let { lockPendingEncryptionRecords(it) }
                    } catch (_: Exception) {}
                    recordDao.trulyEncryptedRecordCount()
                }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** 通过密保问题重置密码。同样**只重新包裹主密钥，内容零改动**（理由见 [changeEncryptionPassword]）。 */
    suspend fun resetEncryptionByAnswer(answer: String, newPassword: String): Result<Int> {
        return try {
            memore.app.utils.EncryptionManager.resetPasswordByAnswer(answer, newPassword)
                .map {
                    try {
                        memore.app.utils.EncryptionManager.getCachedKey()?.let { lockPendingEncryptionRecords(it) }
                    } catch (_: Exception) {}
                    recordDao.trulyEncryptedRecordCount()
                }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** 清除加密：解密所有锁定记录 + 附件 → 删除加密配置 → 清理缓存 */
    suspend fun clearEncryption(password: String): Result<Int> {
        return try {
            val keyResult = memore.app.utils.EncryptionManager.ensureKey(password)
            if (keyResult.isFailure) return Result.failure(keyResult.exceptionOrNull()!!)

            val key = memore.app.utils.EncryptionManager.getCachedKey()!!
            val locked = recordDao.getAllLockedRecords()
            var count = 0
            locked.forEach { record ->
                try {
                    val p = memore.app.utils.EncryptionManager.unpackRecordPayload(
                        memore.app.utils.EncryptionManager.decryptContent(record.encryptedData, record.iv, record.authTag, key)
                    )
                    val analysisJson = if (!p.hasAnalysis) record.analysis?.let { GsonInstance.gson.toJson(it) } else p.analysisJson
                    val emotionalTags = if (!p.hasEmotionalTags) record.emotionalTags else p.emotionalTags
                    recordDao.unlockRecord(record.id, p.content, p.notes, p.dialogueAiContent, p.dialogueAiReply, analysisJson, emotionalTags)
                    attachmentDao.getForRecord(record.id).filter { it.isEncrypted }.forEach { att ->
                        try {
                            val origName = memore.app.utils.EncryptionManager.decryptFileName(att.encName, key) ?: att.fileName
                            memore.app.utils.EncryptionManager.decryptFileInPlace(File(att.filePath), att.iv, att.authTag, key)
                            attachmentDao.decryptAttachment(att.id, origName)
                        } catch (_: Exception) {}
                    }
                    count++
                } catch (_: Exception) { }
            }
            memore.app.utils.EncryptionManager.clearCachedKey()
            Result.success(count)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}

/**
 * 关联边的"依据是否扎根原文"（2026-11-19 抽出，便于测试）。
 *
 * a 必须出现在发起方记录里，b 必须出现在候选片段里；任一句空或对不上，这条边不落库。
 * 这是唯一能真正约束模型"别瞎编引文"的硬门槛——不靠它自觉。
 */
internal fun linkEvidenceGrounded(selfText: String, candidateText: String, a: String, b: String): Boolean {
    if (a.isBlank() || b.isBlank()) return false
    if (!selfText.contains(a)) return false
    if (!candidateText.contains(b)) return false
    return true
}

/** 锚点依据是否扎根原文：quote 非空且原样出现在正文里。 */
internal fun anchorGrounded(quote: String, selfText: String): Boolean =
    quote.isNotBlank() && selfText.contains(quote)

/** 模型给的边类型只认三种；拿不准/写错一律回 LINK。 */
internal fun normalizedRelationType(raw: String): String = when (raw.trim().uppercase()) {
    "CORRECT", "SUPPLEMENT", "CITE" -> raw.trim().uppercase()
    else -> "LINK"
}

/**
 * 分析输入的指纹（2026-11-19）：正文 + 增修算 SHA-256。
 *
 * 用途：记录改了以后，当前内容算出来的指纹和 `records.analysis_input_hash` 对不上，
 * 就说明这条的分析已经过期（STALE），批量覆写时可以只挑过期的重跑。
 */
internal fun inputFingerprint(text: String): String {
    val md = java.security.MessageDigest.getInstance("SHA-256")
    val bytes = md.digest(text.toByteArray(Charsets.UTF_8))
    return bytes.joinToString("") { "%02x".format(it) }
}
