package memore.app.di

import kotlinx.coroutines.launch
import android.content.Context
import memore.app.data.local.AppDatabase
import memore.app.data.repository.AliasMappingRepository
import memore.app.data.repository.AnchorRepository
import memore.app.data.repository.DialRepository
import memore.app.data.repository.DraftRepository
import memore.app.data.repository.ProtocolRepository
import memore.app.data.repository.RecordRepository
import memore.app.data.repository.RedactionRepository
import memore.app.data.repository.TagRepository
import memore.app.data.repository.TagGroupRepository
import memore.app.data.repository.TickRepository
import memore.app.data.voice.VoiceSettingsRepository
import memore.app.data.voice.VoiceTtsRepository

class AppContainer(context: Context) {

    private val appContext: Context = context.applicationContext
    // ── 向量检索（设计文档 docs/向量检索设计.md）───────────────
    /** 端侧嵌入；模型缺失时返回 null，整个向量功能自动降级（检索退回 FTS）。 */
    val embeddingProvider: memore.app.data.vector.EmbeddingProvider? by lazy {
        runCatching { memore.app.data.vector.LocalEmbeddingProvider.fromAssets(appContext.assets) }
            .onFailure { android.util.Log.e("Vector", "本地嵌入加载失败", it) }
            .onSuccess { android.util.Log.i("Vector", "本地嵌入就绪: ${it.model} dim=${it.dim}") }
            .getOrNull()
    }

    val vectorIndexService: memore.app.data.vector.VectorIndexService by lazy {
        memore.app.data.vector.VectorIndexService(database, recordRepository, embeddingProvider).also { svc ->
            memore.app.data.vector.VectorIndexBus.attach { id -> svc.mark(id) }
        }
    }

    val retrievalService: memore.app.data.vector.RetrievalService by lazy {
        memore.app.data.vector.RetrievalService(database, recordRepository, embeddingProvider)
    }
    /** 回看：按时间段 + 区间外相关旧记录生成一段叙述（不落库）。 */
    val reviewService: memore.app.data.vector.ReviewService by lazy {
        memore.app.data.vector.ReviewService(appContext, contextBuilder)
    }

    /** 关联边候选：AI 分析前调用（注入给 RecordRepository，避免构造期循环依赖）。 */
    val contextBuilder: memore.app.data.vector.ContextBuilder by lazy {
        memore.app.data.vector.ContextBuilder(database, recordRepository, retrievalService)
    }


    /**
     * 启动引导：开启索引消费 + 补齐；若还没有主题且向量够了，自动聚一次。
     * （主题为空时用户看到空页面会以为坏了，所以首次自动生成一次。）
     */
    fun wireLinkCandidates() {
    recordRepository.appContext = appContext
    recordRepository.linkCandidatesProvider = { id -> contextBuilder.linkCandidates(id) }
}

    /**
     * 启动时扫一次孤儿关联（2026-11-12）。
     *
     * 标签/协议的关联表没有指向 records 的外键，历史版本彻底删除记录时留下的行会永远堆着；
     * 条数统计 join 得掉它们，但表会一直长。给关联表加外键要升 DB 版本，这里用一次全表扫代替：
     * 代价小、可重复执行。
     */
    fun purgeOrphanLinks() {
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            runCatching { tagRepository.purgeOrphanLinks() }
            runCatching { protocolRepository.purgeOrphanLinks() }
        }
    }

fun bootstrapVectors() {
        vectorIndexService.ensureStarted()
    }

    val database: AppDatabase = AppDatabase.create(context.applicationContext).also { db ->
        // 2026-11-19：记录一次实际 SQLite 版本与 FTS5 可用性（自带 SQLite 生效的凭证）。
        val probe = runCatching {
            val wd = db.openHelper.writableDatabase
            val ver = wd.query("SELECT sqlite_version()").use { c -> if (c.moveToFirst()) c.getString(0) else "?" }
            // 直接建一张 FTS5 虚表来验证：这比 sqlite_compileoption_used 更硬（有些构建不暴露该函数）。
            wd.execSQL("CREATE VIRTUAL TABLE IF NOT EXISTS __fts5_probe USING fts5(x)")
            wd.execSQL("DROP TABLE IF EXISTS __fts5_probe")
            "version=" + ver + " fts5=1"
        }.recoverCatching { e ->
            "fts5-failed: " + e.message
        }.getOrThrow()
        memore.app.utils.LogUtil.i("SQLite " + probe)
    }

    val aliasMappingRepo: AliasMappingRepository by lazy { AliasMappingRepository(context) }

    /** 按锚点聚合（关联页的「按锚点」）。 */
    val anchorRepository: AnchorRepository by lazy {
        AnchorRepository(database, aliasMappingRepo, recordRepository)
    }
    val redactionRepository: RedactionRepository by lazy { RedactionRepository(context) }

    val voiceSettingsRepository by lazy { VoiceSettingsRepository(context) }

    val voiceTtsRepository by lazy { VoiceTtsRepository(context, voiceSettingsRepository) }


    val originRepository: memore.app.data.repository.OriginRepository by lazy {
        memore.app.data.repository.OriginRepository(database, recordRepository)
    }

    val recordRepository: RecordRepository by lazy {
        RecordRepository(
            recordDao = database.recordDao(),
            versionDao = database.versionDao(),
            attachmentDao = database.attachmentDao(),
            taskDao = database.aiTaskDao(),
            db = database
        )
    }

    val aiTaskDao by lazy { database.aiTaskDao() }

    /** 分区（2026-11-14）：保存下来的筛选条件。 */
    val zoneRepository: memore.app.data.repository.ZoneRepository by lazy {
        memore.app.data.repository.ZoneRepository(database)
    }

    /** 新建记录预设（2026-11-17）：类型 / 来源 / 标签 / 协议 四样。 */
    val presetRepository: memore.app.data.repository.RecordPresetRepository by lazy {
        memore.app.data.repository.RecordPresetRepository(database.recordPresetDao())
    }

    val tagRepository: TagRepository by lazy {
        TagRepository(
            tagDao = database.tagDao()
        )
    }

    val tagGroupRepository: TagGroupRepository by lazy {
        TagGroupRepository(
            groupDao = database.tagGroupDao(),
            tagDao = database.tagDao()
        )
    }

    val encryptionConfigDao by lazy { database.encryptionConfigDao() }

    val protocolRepository: ProtocolRepository by lazy {
        ProtocolRepository(
            protocolDao = database.protocolDao()
        )
    }

    val tickDao by lazy { database.tickDao() }

    val tickRepository: TickRepository by lazy {
        TickRepository(
            tickDao = database.tickDao(),
            recordDao = database.recordDao()
        )
    }

    /** 表盘 = **独立的一层**（一段时期的框，包络刻度）；刻度系统不依赖它，它也不改刻度的任何语义。 */
    val dialRepository: DialRepository by lazy {
        DialRepository(
            dialDao = database.dialDao(),
            tickDao = database.tickDao(),
            recordDao = database.recordDao(),
            themeSnapshot = {
                memore.app.data.vector.MonthlyTopicsService(context, recordRepository, database).cached()
            },
            threadList = { memore.app.data.vector.TopicThreads.cached(context)?.second ?: emptyList() }
        )
    }


    /** 真草稿（多本 + 定格块）：独立于记录，见 DraftRepository 的说明。 */
    val draftRepository: DraftRepository by lazy { DraftRepository(database.draftDao(), database.draftBlockDao()) }
}
