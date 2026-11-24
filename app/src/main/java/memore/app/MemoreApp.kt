package memore.app

import android.app.Application
import android.content.SharedPreferences
import memore.app.data.repository.AliasMappingRepository
import memore.app.data.worker.AiAnalysisWorker
import memore.app.di.AppContainer
import memore.app.utils.EncryptionManager
import memore.app.utils.FeatureConsole
import memore.app.utils.EmotionNormalizer
import memore.app.utils.UiSoundPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import memore.app.utils.LogUtil
import java.io.File

class MemoreApp : Application() {

    lateinit var container: AppContainer
        private set

    lateinit var preferences: SharedPreferences
        private set

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** 串行化 AI worker：入队时踢一次 + 后台轮询可能同时到达，避免两个 runOnce 抢同一个队首任务。 */
    private val aiWorkerLock = Mutex()

    /**
     * 入队一次 AI 总结，并立刻踢一次 worker。
     *
     * **关键：跑在应用级 scope 上，不绑页面。**
     * 原先由 Composable 的 `rememberCoroutineScope()` 里调 `AiAnalysisWorker.runOnce` ——
     * 一离开时间线页面协程就被取消：worker 刚把任务标成 PROCESSING 就被掐断，
     * 总结写不进库，转圈也跟着消失。放到这里之后导航不再影响任务。
     */
    fun enqueueAiSummary(recordId: String) {
        appScope.launch {
            try {
                container.recordRepository.enqueueTask(recordId)
                runAiWorkerLocked()
            } catch (e: Exception) {
                LogUtil.e("AI-Enqueue", "enqueueAiSummary", e)
            }
        }
    }

    /** 只踢一次 worker（队列里已有别的任务时也要踢；worker 自己取队首，多踢不会重跑）。 */
    fun kickAiWorker() {
        appScope.launch { runAiWorkerLocked() }
    }

    private suspend fun runAiWorkerLocked() {
        aiWorkerLock.withLock {
            try {
                AiAnalysisWorker.runOnce(this@MemoreApp)
            } catch (e: Exception) {
                LogUtil.e("AI-Kick", "runOnce", e)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        EncryptionManager.init(container.encryptionConfigDao)
        appScope.launch {
            kotlinx.coroutines.withContext(Dispatchers.IO) {
                EncryptionManager.refreshConfigStatus()
            }
        }
        // 启动自愈：上一条进程留下的 PROCESSING 分析任务接回来（否则那张卡永远转圈、任务永远不再被拾起）
        appScope.launch {
            try {
                val resumed = container.recordRepository.resumeInterruptedTasks()
                if (resumed > 0) {
                    LogUtil.i("AI-Resume 启动接回 $resumed 条被中断的分析任务")
                    runAiWorkerLocked()
                }
            } catch (e: Exception) {
                LogUtil.e("AI-Resume", "startup resume", e)
            }
        }
        preferences = getSharedPreferences("memore_prefs", MODE_PRIVATE)
        UiSoundPlayer.setEnabled(getSharedPreferences("voice_prefs", MODE_PRIVATE).getBoolean("sounds_enabled", true))
        UiSoundPlayer.init(this)
        FeatureConsole.init(this)
        // 初始化提示词管理
        memore.app.core.ai.Prompts.init(this)
        // Agent 服务默认开（2026-11-18）：第一次装完不用知道有开关这回事。
        // 只有用户自己关过一次（写了 agent_should_run=false）才尊重它；
        // 没点过允许的电脑连不进来，所以默认开是安全的。
        run {
            val prefs = getSharedPreferences("memore_prefs", MODE_PRIVATE)
            val neverTouched = !prefs.contains("agent_should_run")
            if (neverTouched || prefs.getBoolean("agent_should_run", false)) {
                if (neverTouched) prefs.edit().putBoolean("agent_should_run", true).apply()
                runCatching { memore.app.service.AgentServerService.start(this) }
            }
        }
        // 同步 AI 思考开关（设置页修改后无需重启 App 即可在下次请求生效）
        memore.app.core.ai.AiProtocol.setEnableThinking(
            getSharedPreferences("mirror_prefs", MODE_PRIVATE).getBoolean("ai_thinking", true)
        )
        // 同步云端思考强度（reasoning_effort: high/max）
        memore.app.core.ai.AiProtocol.setThinkingStrength(
            getSharedPreferences("mirror_prefs", MODE_PRIVATE).getString("ai_thinking_strength", "high") ?: "high"
        )
        // 同步云端思考参数格式（由预设选中时写入）
        memore.app.core.ai.AiProtocol.setThinkingControl(
            getSharedPreferences("mirror_prefs", MODE_PRIVATE).getString("ai_thinking_control", "auto") ?: "auto"
        )
        // 2026-11-21：云端不再发 max_tokens，这个旧设置的残留键一并清掉。
        getSharedPreferences("mirror_prefs", MODE_PRIVATE).edit().remove("ai_cloud_max_tokens_cap").apply()
        cleanupStaleTasks()
        cleanupTempAttachments()
        cleanupExportCache()
        cleanupOrphanAttachments()
        initAliasMapping()
        // 2026-11-21：补内置来源「AI」，并把存量 AI 札记的来源回填过去（幂等，后台跑）。
        appScope.launch {
            // 2026-11-23：先补五条内置来源，再补「AI」；顺序反了会让 ensureBuiltins 误判表已初始化。
            runCatching { container.originRepository.ensureBuiltins() }
            runCatching { container.originRepository.ensureAgentAiOrigin() }
            runCatching { container.originRepository.backfillAiNoteOrigins() }
        }
        backfillAmendmentLinks()
        purgeOrphanAmendments()
        backfillTickSlices()
        startAnalysisLoop()
    }

    /**
     * 清理孤儿附件（2026-10-21）。
     *
     * `files/attachments/<recordId>/` 这层目录名就是记录 id。两种情况下它会变成孤儿：
     * ① 编辑页里选了附件但没保存就退出（文件在**选择那一刻**就已经拷进来了）；
     * ② 以前清回收站只删 DB 行、不删文件。
     * 记录彻底不存在（连回收站里也没有）时，整个目录删掉。
     */
    private fun cleanupOrphanAttachments() {
        appScope.launch {
            kotlinx.coroutines.withContext(Dispatchers.IO) {
                try {
                    val root = File(filesDir, "attachments")
                    var freed = 0L
                    root.listFiles()?.forEach { dir ->
                        if (!dir.isDirectory) return@forEach
                        if (!container.recordRepository.recordExistsIncludingTrash(dir.name)) {
                            freed += dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
                            dir.deleteRecursively()
                        }
                    }
                    if (freed > 0) LogUtil.i("Cache-Clean 清理孤儿附件 ${freed / 1024 / 1024} MB")
                } catch (e: Exception) {
                    LogUtil.e("Cache-Clean", "cleanupOrphanAttachments", e)
                }
            }
        }
    }

    /** 清理临时查看附件缓存目录（崩溃残留）。 */
    private fun cleanupTempAttachments() {
        appScope.launch {
            kotlinx.coroutines.withContext(Dispatchers.IO) {
                try { File(filesDir, "attachments_tmp").deleteRecursively() } catch (_: Exception) {}
            }
        }
    }

    /**
     * 清理导出残留（2026-10-21 修的一个真 bug）。
     *
     * 导出/导入是把整包**先写进 cacheDir**（`memory_*.zip` / `memory_*.json`）再交付的，
     * 但交付完从来不删 —— 一份完整备份 100~300MB，堆几次就是 1.4GB（实测用户机上这么多）。
     * 这里在启动时清掉 6 小时前的残留；最近 6 小时的留着，防止"刚分享出去、对方还没读完"。
     */
    private fun cleanupExportCache() {
        appScope.launch {
            kotlinx.coroutines.withContext(Dispatchers.IO) {
                try {
                    val cutoff = System.currentTimeMillis() - 6 * 3600_000L
                    var freed = 0L
                    cacheDir.listFiles()?.forEach { f ->
                        if (!f.isFile) return@forEach
                        val n = f.name
                        val isExportTemp = n.startsWith("memory_") &&
                            (n.endsWith(".zip") || n.endsWith(".json"))
                        if (isExportTemp && f.lastModified() < cutoff) {
                            freed += f.length()
                            f.delete()
                        }
                    }
                    if (freed > 0) LogUtil.i("Cache-Clean 清理导出残留 ${freed / 1024 / 1024} MB")
                } catch (e: Exception) {
                    LogUtil.e("Cache-Clean", "cleanupExportCache", e)
                }
            }
        }
    }

    private fun cleanupStaleTasks() {
        appScope.launch {
            kotlinx.coroutines.withContext(Dispatchers.IO) {
                val tasks = container.aiTaskDao.getProcessingTasks()
                tasks.forEach { container.aiTaskDao.updateTask(it.copy(status = "PENDING")) }
            }
        }
    }

    private fun initAliasMapping() {
        appScope.launch {
            kotlinx.coroutines.withContext(Dispatchers.IO) {
                val repo = AliasMappingRepository(this@MemoreApp)
                repo.ensureDefaultEmotionMappings()
                val groups = repo.load()
                EmotionNormalizer.rebuildCache(groups.filter { it.kind == "emotion" })
                if (!preferences.getBoolean("emotion_migration_v1_done", false)) {
                    val migrated = container.recordRepository.migrateEmotionTagsToNormalized()
                    preferences.edit().putBoolean("emotion_migration_v1_done", true).apply()
                    LogUtil.i("AI-Migration 首次情绪底层迁移: $migrated 条")
                }
            }
        }
    }

    /** 一次性回填：把已有增修与其原记录补成双向 linked_ids（旧数据单向→双向）。 */
    private fun backfillAmendmentLinks() {
        appScope.launch(Dispatchers.IO) {
            if (preferences.getBoolean("amendment_link_backfill_v1_done", false)) return@launch
            try {
                container.recordRepository.backfillAmendmentLinks()
                preferences.edit().putBoolean("amendment_link_backfill_v1_done", true).apply()
                LogUtil.i("AI-Migration 增修双向关联回填完成")
            } catch (e: Exception) {
                LogUtil.e("AI-Migration", "增修双向回填失败", e)
            }
        }
    }

    /** 2026-11-23：一次性清掉孤儿增修（父记录已彻底不存在；旧版删父记录没级联的遗留）。 */
    private fun purgeOrphanAmendments() {
        appScope.launch(Dispatchers.IO) {
            if (preferences.getBoolean("orphan_amendments_purged_v1", false)) return@launch
            try {
                val n = container.recordRepository.purgeOrphanAmendments()
                preferences.edit().putBoolean("orphan_amendments_purged_v1", true).apply()
                LogUtil.i("AI-Migration 孤儿增修清理: $n 条")
            } catch (e: Exception) {
                LogUtil.e("AI-Migration", "孤儿增修清理失败", e)
            }
        }
    }

    /** 一次性回填：把存量"已终结但无快照"的刻度定格（保证升级后都有切片快照）。 */
    private fun backfillTickSlices() {
        appScope.launch(Dispatchers.IO) {
            if (preferences.getBoolean("tick_slice_backfill_v3_done", false)) return@launch
            try {
                container.tickRepository.backfillSlices()
                preferences.edit().putBoolean("tick_slice_backfill_v3_done", true).apply()
                LogUtil.i("AI-Migration 刻度切片定格回填完成")
            } catch (e: Exception) {
                LogUtil.e("AI-Migration", "刻度切片定格回填失败", e)
            }
        }
    }

    override fun onTerminate() {
        super.onTerminate()
        appScope.cancel()
    }

    private fun startAnalysisLoop() {
        appScope.launch {
            while (true) {
                val hasPending = try {
                    container.recordRepository.getPendingTaskCount() > 0
                } catch (e: Exception) {
                    LogUtil.e("AI-Loop", "查询待处理任务失败", e)
                    false
                }
                // 有任务 3 秒一查（少等），无任务 30 秒且不跑 runOnce（不空转写库、不触发主界面重绘）
                delay(if (hasPending) 3_000L else 30_000L)
                if (hasPending) {
                    try {
                        aiWorkerLock.withLock { AiAnalysisWorker.runOnce(this@MemoreApp) }
                    } catch (e: Exception) {
                        LogUtil.e("AI-Loop", "轮询异常", e)
                    }
                }
            }
        }
    }
}
