package memore.app.ui.screens

import androidx.compose.material.icons.outlined.Dataset
import memore.app.ui.components.MemoreLineInput
import androidx.compose.material.icons.outlined.Flip
import androidx.compose.material.icons.outlined.FormatQuote
import memore.app.ui.components.mirrorFieldColors
import memore.app.ui.components.MirrorPasswordField
import memore.app.ui.components.MemorePage
import memore.app.ui.theme.MemoreType
import memore.app.ui.components.MirrorStackDialog
import android.widget.Toast
import memore.app.ui.components.MirrorAlertDialog
import memore.app.utils.memoreToast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import memore.app.ui.components.MirrorTextAction
import memore.app.ui.components.MirrorActionEmphasis
import memore.app.ui.components.MirrorShapes
import memore.app.ui.components.MirrorSheet
import memore.app.ui.components.mirrorHairline
import memore.app.ui.components.mirrorHairlineColor
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import memore.app.MemoreApp
import memore.app.data.repository.AliasMappingRepository
import memore.app.data.repository.ProtocolRepository
import memore.app.data.repository.RecordRepository
import memore.app.data.repository.RedactionRepository
import memore.app.data.repository.TickRepository
import memore.app.data.repository.TagRepository
import memore.app.utils.AuthManager
import memore.app.utils.EncryptionManager
import memore.app.utils.FeatureConsole
import memore.app.utils.UiSoundPlayer
import memore.app.utils.ExportDelivery
import memore.app.utils.ExportFormat
import memore.app.utils.ExportVerifier
import memore.app.utils.ExportEngine
import memore.app.utils.ImportEngine
import memore.app.utils.ThemeManager
import memore.app.utils.formatDateOnly
import memore.app.utils.datePickerToLocalStartOfDay
import memore.app.utils.localMillisToDatePicker
import memore.app.ui.components.IconBack
import memore.app.ui.components.IconCollapseUp
import memore.app.ui.components.IconExpandDown
import memore.app.ui.components.IconTutorial
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun SettingsScreen(recordRepository: RecordRepository? = null, tagRepository: TagRepository? = null, protocolRepository: ProtocolRepository? = null, aliasMappingRepo: AliasMappingRepository? = null, redactionRepository: RedactionRepository? = null, tickRepository: TickRepository? = null, onOpenEmotion: () -> Unit = {}, onOpenAliasMapping: () -> Unit = {}, onOpenPromptManage: () -> Unit = {}, onOpenVoiceLab: () -> Unit = {},
    onOpenAiConfig: () -> Unit = {}, onOpenVectorIndex: () -> Unit = {}, onOpenInsightArchive: () -> Unit = {}, onOpenTopics: () -> Unit = {}, onOpenFixTools: () -> Unit = {}, onOpenPinSetup: () -> Unit = {}, onOpenTutorial: () -> Unit = {}, onOpenRedactionExport: () -> Unit = {}, onOpenSystemInstruction: () -> Unit = {}, onOpenAgentLink: () -> Unit = {},
    /** 2026-11-16：加密管理/加密设置改成真正的路由屏（不再在本页叠覆盖层）。 */
    onOpenEncryptionManage: () -> Unit = {}, onOpenEncryptionSetup: () -> Unit = {}) {
    val context = LocalContext.current; val scope = rememberCoroutineScope()
    var refreshKey by remember { mutableStateOf(0) }; var pinEnabled by remember { mutableStateOf(AuthManager.isLockEnabled()) }
    var showPinSetup by remember { mutableStateOf(false) }; var showSecurityQuestion by remember { mutableStateOf(false) }
    var showDisableConfirm by remember { mutableStateOf(false) }; var showLockMenu by remember { mutableStateOf(false) }; var showPinVerifyBeforeDisable by remember { mutableStateOf(false) }
    // ===== 加密保护 =====
    var showEncryptionSetup by remember { mutableStateOf(false) }
    var showEncryptionQuestion by remember { mutableStateOf(false) }
    var encSetupPassword by remember { mutableStateOf("") }
    var showEncryptionManage by remember { mutableStateOf(false) }
    var encManageStep by remember { mutableIntStateOf(0) } // 0=密码门, 1=操作面板
    var encToggleEnabled by remember { mutableStateOf(EncryptionManager.isEnabled()) }
    var encManagePassword by remember { mutableStateOf("") }
    var encManageError by remember { mutableStateOf<String?>(null) }
    var encLockedCount by remember { mutableStateOf(0) }
    var encConfigured by remember { mutableStateOf(false) }
    var showEncChangePassword by remember { mutableStateOf(false) }
    var encChangeOldPw by remember { mutableStateOf("") }
    var encChangeNewPw by remember { mutableStateOf("") }
    var encChangeNewQ by remember { mutableStateOf("") }
    var encChangeNewA by remember { mutableStateOf("") }
    var showEncForgotPw by remember { mutableStateOf(false) }
    var encForgotAnswer by remember { mutableStateOf("") }
    var encForgotNewPw by remember { mutableStateOf("") }
    /** 本机是否尚未升级到 v3 —— 决定改密码/密保重置对话框里要不要显示"先备份"的提醒。 */
    var encWillUpgrade by remember { mutableStateOf(false) }
    var showImportConfirm by remember { mutableStateOf(false) }; var importResult by remember { mutableStateOf<String?>(null) }; var importPendingUri by remember { mutableStateOf<android.net.Uri?>(null) }
    var importEncWarning by remember { mutableStateOf<String?>(null) }
    /** 导入预览（含"包里有多少条外来密文"，用来决定要不要问"那台设备的凭据"）。 */
    var importPreview by remember { mutableStateOf<memore.app.utils.ImportPreview?>(null) }
    /** 接管外来加密记录：那台设备的密码**或**密保答案（两者都试，用户不用挑）。 */
    var importAlienCred by remember { mutableStateOf("") }
    /** 本机密钥未缓存时才需要：用它把接管的记录补锁到本机。 */
    var importLocalPw by remember { mutableStateOf("") }

    // 长任务进度（引擎在任务结束时会把值清回 null，所以不需要自己管"何时关闭"）
    val exportProgress by ExportEngine.progress.collectAsState()
    val importProgress by ImportEngine.progress.collectAsState()
    var showExportDialog by remember { mutableStateOf(false) }
    // 2026-11-16：格式从三种并成两种——
    //   结构化（可回导；含附件 → .zip，不含 → .json）+ Markdown（给 AI 读）
    // 原来 "JSON" 和 "完整数据包" 是两个格式，其实只差"要不要附件"，合并后少一层认知。
    var exportFormat by remember { mutableStateOf(ExportFormat.JSON_ONLY) }
    /** 结构化导出是否包含附件（图片/音频）；决定产出 .json 还是 .zip。 */
    var exportWithAttachments by remember { mutableStateOf(true) }
    var exportProtocolIds by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var allExportProtocols by remember { mutableStateOf<List<memore.app.data.model.Protocol>>(emptyList()) }
    var exportTickIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var allExportTicks by remember { mutableStateOf<List<memore.app.data.model.Tick>>(emptyList()) }
    var exportProtocolExpanded by remember { mutableStateOf(false) }   // 协议默认折叠
    var exportTickExpanded by remember { mutableStateOf(false) }      // 刻度默认折叠
    // 2026-11-16：范围筛选补齐（标签 / 区域 / 来源）
    var exportTagIds by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var exportZoneIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var exportOriginIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var allExportTags by remember { mutableStateOf<List<memore.app.data.model.Tag>>(emptyList()) }
    var allExportZones by remember { mutableStateOf<List<memore.app.data.model.Zone>>(emptyList()) }
    var allExportOrigins by remember { mutableStateOf<List<memore.app.data.model.Origin>>(emptyList()) }
    var exportTagExpanded by remember { mutableStateOf(false) }
    var exportZoneExpanded by remember { mutableStateOf(false) }
    var exportOriginExpanded by remember { mutableStateOf(false) }
    // ===== 导出功能选择 =====
    var showExportOptions by remember { mutableStateOf(false) }
    var exportAutoInsert by remember { mutableStateOf(false) }
    var exportIncludeEmotion by remember { mutableStateOf(true) }
    var exportIncludeAlias by remember { mutableStateOf(false) }
    var exportIncludePrompts by remember { mutableStateOf(false) }
    var exportIncludeVoice by remember { mutableStateOf(false) }
    var exportIncludeSlices by remember { mutableStateOf(false) }
    /** JSON 紧凑输出：默认开（省 24% 体积，导入端无差别）。 */
    var exportCompactJson by remember { mutableStateOf(true) }
    /** 真草稿：**默认关**（用户 2026-10-11：「这个东西默认关闭，特殊的是全量」）。 */
    var exportIncludeDrafts by remember { mutableStateOf(false) }
    /** 整体备份 + 未开明文解密 + 真有加密附件 → 弹这个数（0 = 不弹）。 */
    var lockedAttachWarn by remember { mutableStateOf(0) }
    /** v5（2026-11-16）：锁定记录是否以明文导出（默认否；整体备份强制是）。
     *  旧的「明文解密」开关在 v5 下没有意义（库里没有密文），已合并到这里。 */
    var exportIncludeLocked by remember { mutableStateOf(false) }
    /** 整体备份里是否带 API 密钥：用户自己勾，**整体备份不强制**（带密钥的包是泄密面）。 */
    var exportIncludeApiKey by remember { mutableStateOf(false) }
    /** 整体备份：额外写入版本快照/回收站/图文集状态/提示音/设置，数据块强制拉满、强制不脱敏 */
    var exportFullBackup by remember { mutableStateOf(false) }
    var showExportDecryptPwDialog by remember { mutableStateOf(false) }
    var exportDecryptPw by remember { mutableStateOf("") }
    var exportDecryptPwErr by remember { mutableStateOf<String?>(null) }
    // ===== 按时间导出 =====
    var exportTimeOption by remember { mutableStateOf(0) } // 0=全部, 1=最近7天, 2=最近30天, 3=自定义
    var exportCustomStart by remember { mutableStateOf<Long?>(null) }
    var exportCustomEnd by remember { mutableStateOf<Long?>(null) }
    var showStartDatePicker by remember { mutableStateOf(false) }
    var showEndDatePicker by remember { mutableStateOf(false) }
    var showThemePicker by remember { mutableStateOf(false) }
    var aiEndpoint by remember { mutableStateOf(context.getSharedPreferences("mirror_prefs", android.content.Context.MODE_PRIVATE).getString("ai_endpoint", "") ?: "") }
    var aiModel by remember { mutableStateOf(context.getSharedPreferences("mirror_prefs", android.content.Context.MODE_PRIVATE).getString("ai_model", "") ?: "") }
    var aiThinking by remember { mutableStateOf(context.getSharedPreferences("mirror_prefs", android.content.Context.MODE_PRIVATE).getBoolean("ai_thinking", true)) }
    var aiConfigExpanded by remember { mutableStateOf(context.getSharedPreferences("mirror_prefs", android.content.Context.MODE_PRIVATE).getBoolean("ai_config_expanded", false)) }
    // ===== AI 连接模式（本地 | API）=====
    var aiMode by remember { mutableStateOf(context.getSharedPreferences("mirror_prefs", android.content.Context.MODE_PRIVATE).getString("ai_mode", "local") ?: "local") }
    var aiApiKey by remember { mutableStateOf(context.getSharedPreferences("mirror_prefs", android.content.Context.MODE_PRIVATE).getString("ai_api_key", "") ?: "") }
    var showApiKey by remember { mutableStateOf(false) }
    var aiThinkingStrength by remember { mutableStateOf(context.getSharedPreferences("mirror_prefs", android.content.Context.MODE_PRIVATE).getString("ai_thinking_strength", "high") ?: "high") }
    // ===== API 预设管理 =====
    var presets by remember { mutableStateOf(memore.app.core.ai.ApiPresetStore.load(context)) }
    var selectedPresetName by remember { mutableStateOf(context.getSharedPreferences("mirror_prefs", android.content.Context.MODE_PRIVATE).getString("ai_selected_preset", "") ?: "") }
    var deleteMode by remember { mutableStateOf(false) }
    var presetToDelete by remember { mutableStateOf<memore.app.core.ai.ApiPreset?>(null) }
    var showCustomPresetDialog by remember { mutableStateOf(false) }
    // 切换模式：把当前输入值存进"离开模式"槽位，再加载"进入模式"保存值到共享 ai_endpoint/ai_model
    val switchAiMode: (String) -> Unit = { target ->
        val prefs = context.getSharedPreferences("mirror_prefs", android.content.Context.MODE_PRIVATE)
        if (aiMode != target) {
            // 保存当前模式的值到该模式槽位
            if (aiMode == "local") {
                prefs.edit().putString("ai_local_endpoint", aiEndpoint).putString("ai_local_model", aiModel).apply()
            } else {
                prefs.edit().putString("ai_api_endpoint", aiEndpoint).putString("ai_api_model", aiModel).apply()
            }
            // 加载目标模式的值，并【写回共享 pref】——AI 调用读共享 pref，不同步会导致本地被 API 预设污染
            if (target == "local") {
                aiEndpoint = prefs.getString("ai_local_endpoint", "") ?: ""
                aiModel = prefs.getString("ai_local_model", "") ?: ""
                selectedPresetName = ""
                prefs.edit()
                    .putString("ai_endpoint", aiEndpoint).putString("ai_model", aiModel)
                            .apply()
            } else {
                aiEndpoint = prefs.getString("ai_api_endpoint", "") ?: ""
                aiModel = prefs.getString("ai_api_model", "") ?: ""
                prefs.edit().putString("ai_endpoint", aiEndpoint).putString("ai_model", aiModel).apply()
            }
            aiMode = target
            prefs.edit().putString("ai_mode", target).apply()
        }
    }
    // 云端思考强度选择（高/最高 → reasoning_effort high/max）
    val selectStrength: (String) -> Unit = { s ->
        aiThinkingStrength = s
        context.getSharedPreferences("mirror_prefs", android.content.Context.MODE_PRIVATE).edit().putString("ai_thinking_strength", s).apply()
        memore.app.core.ai.AiProtocol.setThinkingStrength(s)
    }
    // ===== 预设操作 =====
    val prefsRef = context.getSharedPreferences("mirror_prefs", android.content.Context.MODE_PRIVATE)
    /** 交付方式：改一次就记住（存 mirror_prefs），下次沿用，不用每次切。 */
    var exportDelivery by remember { mutableStateOf(ExportDelivery.read(prefsRef)) }
    /** 「另存为」要把"是否解密"带过系统选择器这一趟。 */
    var pendingExportDecrypt by remember { mutableStateOf(false) }
    /** 「导出后」折叠态（默认折叠，与上面两个筛选一致）。 */
    var exportDeliveryExpanded by remember { mutableStateOf(false) }
    // 应用预设：载入端点/模型/key + 高级思考参数到共享状态（AI 调用读共享 pref）
    val applyPreset: (memore.app.core.ai.ApiPreset) -> Unit = { p ->
        aiEndpoint = p.endpoint
        aiModel = p.model
        aiApiKey = p.apiKey
        aiThinkingStrength = p.reasoningEffort
        selectedPresetName = p.name
        prefsRef.edit()
            .putString("ai_endpoint", p.endpoint).putString("ai_model", p.model).putString("ai_api_key", p.apiKey)
            .putString("ai_selected_preset", p.name).putString("ai_thinking_strength", p.reasoningEffort)
            .putString("ai_thinking_control", p.thinkingControl)
            .apply()
        memore.app.core.ai.AiProtocol.setThinkingStrength(p.reasoningEffort)
        memore.app.core.ai.AiProtocol.setThinkingControl(p.thinkingControl)
        UiSoundPlayer.playApiPresetSwitch(context)
        deleteMode = false
    }
    // 编辑字段时同步更新当前选中预设的存储值（仅 API 模式）
    val updateActivePreset: ((memore.app.core.ai.ApiPreset) -> memore.app.core.ai.ApiPreset) -> Unit = { transform ->
        val idx = presets.indexOfFirst { it.name == selectedPresetName }
        if (aiMode == "api" && idx >= 0) {
            presets = presets.toMutableList().also { it[idx] = transform(it[idx]) }
            memore.app.core.ai.ApiPresetStore.save(context, presets)
        }
    }
    val onEndpointChange: (String) -> Unit = { v -> aiEndpoint = v; prefsRef.edit().putString("ai_endpoint", v).apply(); updateActivePreset { it.copy(endpoint = v) } }
    val onModelChange: (String) -> Unit = { v -> aiModel = v; prefsRef.edit().putString("ai_model", v).apply(); updateActivePreset { it.copy(model = v) } }
    val onApiKeyChange: (String) -> Unit = { v -> aiApiKey = v; prefsRef.edit().putString("ai_api_key", v).apply(); updateActivePreset { it.copy(apiKey = v) } }


    // 执行导出（decrypt=true 时用已缓存的密钥解密锁定记录，仅导出解密内容、不改应用内状态）
    val performExport: suspend (Boolean, java.io.OutputStream?) -> Boolean = { decrypt, output ->
        val now = System.currentTimeMillis()
        val dayMs = 24L * 60 * 60 * 1000
        val (startTs, endTs) = when (exportTimeOption) {
            1 -> (now - 7 * dayMs) to null
            2 -> (now - 30 * dayMs) to null
            // 自定义截止日存的是"该日本地零点"，而引擎按 createdAt <= endTs 判定，
            // 所以这里要补到当天最后一毫秒，否则"选到 9/24"会漏掉 9/24 全天的记录。
            3 -> exportCustomStart to exportCustomEnd?.let { it + 24L * 60 * 60 * 1000 - 1 }
            else -> null to null
        }
        val encDao = (context.applicationContext as? MemoreApp)?.container?.encryptionConfigDao
        val redactionRepo = redactionRepository
        val redactOn = redactionRepo?.redactionEnabled() == true
        // 映射组无条件载入：脱敏导出要用它洗内容；整体备份要把它自己随包（整体备份强制不脱敏，不会自破）
        val redactionGroups = redactionRepo?.loadGroups() ?: emptyList()
        val importEn = !(redactionRepo?.importBlockEnabled() == true)
        ExportEngine.export(context, recordRepository!!, tagRepository!!, when {
            exportFormat == ExportFormat.AI_MARKDOWN -> ExportFormat.AI_MARKDOWN
            exportWithAttachments || exportFullBackup -> ExportFormat.FULL_PACKAGE
            else -> ExportFormat.JSON_ONLY
        }, protocolRepository, exportProtocolIds.ifEmpty { null }, aliasMappingRepo, systemInstruction = memore.app.core.ai.SystemInstructionStore.current(context), autoInsert = exportAutoInsert, startTs = startTs, endTs = endTs, tickIds = exportTickIds.ifEmpty { null },
            includeEmotionalProfile = exportIncludeEmotion, includeAliasGroups = exportIncludeAlias, includePrompts = exportIncludePrompts, includeVoiceCache = exportIncludeVoice, includeSlices = exportIncludeSlices, compactJson = exportCompactJson, encryptionConfigDao = encDao, decryptLocked = false, database = (context.applicationContext as? MemoreApp)?.container?.database,
            // v5（2026-11-16）：普通导出默认排除锁定记录；勾选后才以明文包含。整体备份强制全量（引擎内判断）。
            includeLocked = exportIncludeLocked || exportFullBackup,
            // 草稿默认关；「整体备份」时引擎强制包含
            includeDrafts = exportIncludeDrafts,
            draftRepository = (context.applicationContext as? MemoreApp)?.container?.draftRepository,
            redact = redactOn, redactionGroups = redactionGroups, redactionMode = redactionRepo?.redactionMode() ?: memore.app.utils.RedactionEngine.MODE_RANDOM, importEnabled = importEn,
            tickRepo = tickRepository,
            dialRepository = (context.applicationContext as? MemoreApp)?.container?.dialRepository,
            tagGroupRepository = (context.applicationContext as? MemoreApp)?.container?.tagGroupRepository,
            // 2026-11-16：范围筛选（标签 / 区域 / 来源）
            tagIds = exportTagIds,
            zoneIds = exportZoneIds,
            originIds = exportOriginIds,
            fullBackup = exportFullBackup,
            includeApiKey = exportIncludeApiKey,
            // 2026-11-16 v2：区域/来源/概念分类（以前"全量导出"漏掉的三块）
            zoneDao = (context.applicationContext as? MemoreApp)?.container?.database?.zoneDao(),
            originDao = (context.applicationContext as? MemoreApp)?.container?.database?.originDao(),
            // output != null = 「另存为」：直接写进 SAF 给的目标流，不落 cacheDir / 不写 Download / 不弹分享
            output = output,
            deliverToDownloads = true,
            deliverToShare = exportDelivery != ExportDelivery.DOWNLOADS)
            .onFailure { context.memoreToast(it.message ?: "导出失败", Toast.LENGTH_LONG); UiSoundPlayer.playError(context) }
            // 「另存为」的"成功"由回读校验决定，这里不抢先报成功
            .onSuccess { if (output == null) { context.memoreToast("导出成功", Toast.LENGTH_SHORT); UiSoundPlayer.playExportDone(context) } }
            .isSuccess
    }

    // 「另存为…」：SAF 选位置 → 直接写进目标流 → 立刻回读校验（层级①：只读开头，不碰附件）
    // 校验不过就把目标文件删掉 —— 不给"坏包还以为是真的"留活口（ArmsDex 只提示不删，这步我们补上）。
    // 2026-11-16：格式合并——结构化（附件可选）+ Markdown。
    // JSON_ONLY / FULL_PACKAGE 不再由用户选，而是由"是否含附件"推导。
    val exportIsMarkdown = exportFormat == ExportFormat.AI_MARKDOWN
    val exportIsZip = !exportIsMarkdown && (exportWithAttachments || exportFullBackup)
    val exportEffectiveFormat = when {
        exportIsMarkdown -> ExportFormat.AI_MARKDOWN
        exportIsZip -> ExportFormat.FULL_PACKAGE
        else -> ExportFormat.JSON_ONLY
    }
    val exportMime = if (exportIsMarkdown) ExportDelivery.markdownMime() else ExportDelivery.mimeOf(exportIsZip)

    // ---- 内容 / 附加处理：单一真源（2026-11-16）----
    // 桌面列表和底部 sheet 都从这两张表渲染。之前是两份手写列表，已经分叉过一次：
    // 「锁定记录」「刻度切片内容」在 sheet 里会被整体备份强制勾上，桌面列表却没强制/没置灰。
    val exportContentSpecs = listOf(
        ExportRowSpec("情绪分析数据", exportIncludeEmotion || exportFullBackup,
            enabled = !exportFullBackup && !exportIsMarkdown) { exportIncludeEmotion = it },
        ExportRowSpec("别名映射", exportIncludeAlias || exportFullBackup,
            enabled = !exportFullBackup && !exportIsMarkdown) { exportIncludeAlias = it },
        ExportRowSpec("提示词", exportIncludePrompts || exportFullBackup,
            enabled = !exportFullBackup && !exportIsMarkdown) { exportIncludePrompts = it },
        ExportRowSpec("语音缓存", exportIncludeVoice || exportFullBackup,
            enabled = !exportFullBackup && !exportIsMarkdown) { exportIncludeVoice = it },
        ExportRowSpec("草稿", exportIncludeDrafts || exportFullBackup,
            enabled = !exportFullBackup && !exportIsMarkdown) { exportIncludeDrafts = it }
    )
    val exportProcessSpecs = listOf(
        ExportRowSpec("自动插入重复指令", exportAutoInsert,
            trailing = if (exportAutoInsert) "前 3 条" else null) { exportAutoInsert = it },
        // 整体备份 = 原样搬回去：锁定内容与切片内容必须一起进包，且不允许用户关掉
        ExportRowSpec("锁定记录（明文导出）", exportIncludeLocked || exportFullBackup,
            enabled = !exportFullBackup) { exportIncludeLocked = it },
        ExportRowSpec("刻度切片内容", exportIncludeSlices || exportFullBackup,
            enabled = !exportFullBackup) { exportIncludeSlices = it },
        // 2026-11-16：这一项**不受整体备份约束** —— 强制开启会悄悄造出一个带密钥的备份文件。
        ExportRowSpec("包含 API 密钥", exportIncludeApiKey,
            trailing = if (exportIncludeApiKey) "带密钥" else null) { exportIncludeApiKey = it }
    )

    val saveAsContract = remember(exportMime) { ActivityResultContracts.CreateDocument(exportMime) }
    val saveAsLauncher = rememberLauncherForActivityResult(saveAsContract) { uri ->
        if (uri == null) {
            // 取消就是取消：不偷偷 fallback 去分享
            context.memoreToast("已取消", Toast.LENGTH_SHORT)
        } else {
            scope.launch {
                val ok = try {
                    val out = context.contentResolver.openOutputStream(uri)
                    if (out == null) { context.memoreToast("无法打开目标文件", Toast.LENGTH_LONG); false }
                    else out.use { performExport(pendingExportDecrypt, it) }
                } catch (e: Exception) {
                    context.memoreToast("导出失败：${e.message ?: e.javaClass.simpleName}", Toast.LENGTH_LONG); false
                }
                if (!ok) { UiSoundPlayer.playError(context); return@launch }
                // Markdown 是只读交付，不走"记忆包结构校验"——按正文非空验收即可
                if (exportIsMarkdown) {
                    val text = try {
                        context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() } ?: ""
                    } catch (e: Exception) { "" }
                    if (text.isNotBlank() && text.contains("# ")) {
                        context.memoreToast("已保存：${text.length} 字符", Toast.LENGTH_LONG)
                        UiSoundPlayer.playExportDone(context)
                    } else {
                        ExportVerifier.deleteQuietly(context, uri)
                        context.memoreToast("文件为空或写入失败，已删除", Toast.LENGTH_LONG)
                        UiSoundPlayer.playError(context)
                    }
                    return@launch
                }
                val verify = try {
                    val input = context.contentResolver.openInputStream(uri)
                    if (input == null) ExportVerifier.Result.Failed("读不回刚保存的文件")
                    else input.use { if (exportIsZip) ExportVerifier.verifyZip(it) else ExportVerifier.verifyJson(it) }
                } catch (e: Exception) {
                    ExportVerifier.Result.Failed(e.javaClass.simpleName)
                }
                when (verify) {
                    is ExportVerifier.Result.Ok -> {
                        context.memoreToast("已保存并校验通过：${verify.count} 条记录（v${verify.version ?: "?"}）", Toast.LENGTH_LONG)
                        UiSoundPlayer.playExportDone(context)
                    }
                    is ExportVerifier.Result.Failed -> {
                        ExportVerifier.deleteQuietly(context, uri)
                        context.memoreToast("校验失败，已删除目标文件：${verify.reason}", Toast.LENGTH_LONG)
                        UiSoundPlayer.playError(context)
                    }
                }
            }
        }
    }

    /** 「导出」真正开跑：另存为走系统选择器，其余走旧交付路径。 */
    val reallyStartExport: (Boolean) -> Unit = { decrypt ->
        if (exportDelivery == ExportDelivery.SAVE_AS) {
            pendingExportDecrypt = decrypt
            saveAsLauncher.launch(
                if (exportIsMarkdown) ExportDelivery.markdownName(ExportDelivery.stamp()) else ExportDelivery.suggestedName(exportIsZip)
            )
        } else {
            scope.launch { performExport(decrypt, null) }
        }
    }

    /**
     * 「导出」统一入口：整体备份 + 没开「明文解密」+ 本机真有加密附件时，
     * **先明确告诉用户这些附件不会进包**，再让他决定（不静默跳过 —— 用户 2026-10-11 定的）。
     */
    val startExport: (Boolean) -> Unit = { decrypt ->
        if (exportFullBackup && !decrypt) {
            scope.launch {
                val n = try { recordRepository?.countEncryptedAttachments() ?: 0 } catch (_: Exception) { 0 }
                if (n > 0) lockedAttachWarn = n else reallyStartExport(false)
            }
        } else {
            reallyStartExport(decrypt)
        }
    }

    // 「加密管理」入口（2026-10-23 从 FIX 连点转正）：无密码进设置向导，已有密码/密文进管理面板
    // 2026-11-16（声音）：HIDDEN_ENCRYPT = 「加密·隐藏入口」，只在**点进加密管理**时响一次。
    // 设置加密不是"隐藏入口"，所以不响。面板入口/密码验证没有专用事件，就不硬塞别的声音
    // （上一版错用了 LOCK_ON「应用锁·开启」）。
    fun openEncryptionEntry() {
        scope.launch {
            // 以**数据库为准**重新判定，不信内存缓存：导入/恢复是在运行时写配置的，
            // 若那条路径没刷新缓存，这里读缓存会得出"本机没有密码"的错误结论 →
            // 把用户送进「设置密码」这条走不通的路（点了会被守卫拒绝，而正确的「修改密码」够不着）。
            EncryptionManager.refreshConfigStatus()
            val hasPw = EncryptionManager.hasPassword()
            // 还有密文也算"已经加密过"：世上存在"有密文、但配置丢了"的坏状态（导入失败 / 配置被清），
            // 此时若送进「设置密码」，用户一点就会造出新钥匙、把旧密文全废掉。宁可让他进加密管理看到真实情况。
            val hasCiphertext = try { recordRepository?.hasTrulyEncryptedData() == true } catch (_: Exception) { false }
            // 2026-11-16：加密管理/加密设置都改成**独立路由屏**（MainActivity 里的屏号）。
            // 本页只负责判断该去哪一个，不再自己渲染覆盖层 —— 那层覆盖层是卡顿的根因。
            if (hasPw || hasCiphertext || EncryptionManager.isV5()) {
                // "加密·隐藏入口"那声只属于进入加密管理
                UiSoundPlayer.playHiddenEncrypt(context)
                onOpenEncryptionManage()
            } else {
                onOpenEncryptionSetup()
            }
        }
    }

    val importFileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) { importPendingUri = uri; scope.launch {
            ImportEngine.preview(context, uri).onSuccess { preview ->
                val base = "总条数：${preview.totalCount}\n时间范围：${preview.timeRange.first} ~ ${preview.timeRange.second}\n预计新增：${preview.newCount} 条" +
                    // 草稿：本机没有"同名 + 同创建时间"那本才会还原；空本一律不还原
                    if (preview.packageDraftCount > 0) "\n草稿：${preview.packageDraftCount} 本（${preview.packageDraftBlockCount} 块），已有同名同时间的会跳过" else ""
                // 整体备份包：附上包说明与 DB 版本校验提示
                importResult = preview.backupNote?.let { "$base\n\n$it" } ?: base
                importPreview = preview
                importAlienCred = ""; importLocalPw = ""
                showImportConfirm = true
            }
                .onFailure { context.memoreToast(it.message ?: "\u5BFC\u5165\u5931\u8D25", Toast.LENGTH_LONG) }
        } }
    }


    // 加密设置已搬到独立路由屏（SCREEN_ENCRYPTION_SETUP），本页不再内嵌。

    if (showLockMenu) {
        val ss = rememberModalBottomSheetState()
        MirrorSheet(onDismissRequest = { showLockMenu = false }, containerColor = MaterialTheme.colorScheme.surfaceContainerLow, sheetState = ss) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("\u5E94\u7528\u9501\u8BBE\u7F6E", style = MaterialTheme.typography.titleMedium); Spacer(Modifier.height(4.dp))
                Column(Modifier.fillMaxWidth()) {
                    Text("重置密码", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.fillMaxWidth().clickable { showLockMenu = false; showDisableConfirm = true }.padding(vertical = 14.dp))
                    Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)))
                }
                Column(Modifier.fillMaxWidth()) {
                    Text("关闭功能", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.fillMaxWidth().clickable { showLockMenu = false; showPinVerifyBeforeDisable = true }.padding(vertical = 14.dp))
                    Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)))
                }
            }
        }
    }

    if (showDisableConfirm) {
        MirrorStackDialog(
            title = "重置密码",
            message = "将清除当前 PIN 和安全问题。",
            confirmText = "清除",
            destructive = true,
            onDismiss = { showDisableConfirm = false },
            onConfirm = {
                AuthManager.clear()
                pinEnabled = false
                showDisableConfirm = false
                context.memoreToast("已清除", Toast.LENGTH_SHORT)
                UiSoundPlayer.playLockReset(context)
            }
        )
    }

    if (showPinVerifyBeforeDisable) {
        var verifyPin by remember { mutableStateOf("") }; var verifyError by remember { mutableStateOf<String?>(null) }
        MirrorStackDialog(
            title = "验证 PIN",
            message = "输入当前 PIN 以关闭应用锁",
            confirmText = "确认",
            onDismiss = { showPinVerifyBeforeDisable = false },
            onConfirm = {
                if (AuthManager.verifyPin(verifyPin)) {
                    AuthManager.disableLock(); pinEnabled = false; showPinVerifyBeforeDisable = false
                    context.memoreToast("应用锁已关闭", Toast.LENGTH_SHORT); UiSoundPlayer.playLockOff(context)
                } else {
                    verifyError = "PIN 错误"; verifyPin = ""
                }
            }
        ) {
            MirrorPasswordField(
                value = verifyPin,
                onValueChange = { verifyPin = it.filter { c -> c.isDigit() }.take(4); verifyError = null },
                placeholder = "PIN",
                error = verifyError,
                numeric = true
            )
        }
    }

    if (showExportDialog) {
        LaunchedEffect(Unit) {
            allExportTicks = tickRepository?.getActive() ?: emptyList()
            // 2026-11-16：范围筛选的三张候选表
            // ⚠️ 必须放在 collect 之前：下面 getActiveProtocols().collect{} 永不返回，
            // 写在它后面的代码一次都不会执行（第一版就是这么空的）。
            val c = (context.applicationContext as? MemoreApp)?.container
            allExportTags = runCatching { tagRepository?.getAllTags()?.first() ?: emptyList() }.getOrDefault(emptyList())
            allExportZones = runCatching { c?.database?.zoneDao()?.all() ?: emptyList() }.getOrDefault(emptyList())
            allExportOrigins = runCatching { c?.originRepository?.all() ?: emptyList() }.getOrDefault(emptyList())
            protocolRepository?.getAllProtocols()?.collect { allExportProtocols = it }
        }
        // P1 面单的大数字：按当前筛选实时算，口径与导出引擎一致（选刻度时忽略时间窗）。
        var exportCount by remember { mutableStateOf<Int?>(null) }
        var exportAttachmentText by remember { mutableStateOf<String?>(null) }
        var exportFormatExpanded by remember { mutableStateOf(false) }
        var exportTimeExpanded by remember { mutableStateOf(false) }
        LaunchedEffect(exportFormat, exportFullBackup, exportProtocolIds, exportTickIds, exportTimeOption, exportCustomStart, exportCustomEnd,
            exportTagIds, exportZoneIds, exportOriginIds, exportIncludeLocked, exportWithAttachments) {
            exportCount = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching {
                    val repo = recordRepository ?: return@runCatching null
                    val all = repo.getAllActiveRecords()
                    val byProtocol = if (!exportFullBackup && exportProtocolIds.isNotEmpty() && protocolRepository != null) {
                        val ids = exportProtocolIds.flatMap { pid -> protocolRepository!!.getRecordsByProtocolId(pid) }.map { it.id }.toSet()
                        all.filter { it.id in ids }
                    } else all
                    val byTick = if (!exportFullBackup && exportTickIds.isNotEmpty() && tickRepository != null) {
                        val ranges = tickRepository!!.getActive().filter { it.id in exportTickIds }.map { it.startMs to it.endMs }
                        byProtocol.filter { r -> ranges.any { (s, e) -> r.createdAt >= s && (e == null || r.createdAt < e) } }
                    } else byProtocol
                    // 2026-11-16：标签 / 区域 / 来源（与引擎同一个判据）
                    val byTag = if (!exportFullBackup && exportTagIds.isNotEmpty()) {
                        val ids = runCatching { repo.recordIdsByTagIds(exportTagIds.toList()).toSet() }.getOrDefault(emptySet())
                        byTick.filter { it.id in ids }
                    } else byTick
                    val byZone = if (!exportFullBackup && exportZoneIds.isNotEmpty()) {
                        val members = runCatching {
                            (context.applicationContext as? MemoreApp)?.container?.database?.zoneDao()
                                ?.allMembers()?.filter { it.zoneId in exportZoneIds }?.map { it.recordId }?.toSet() ?: emptySet()
                        }.getOrDefault(emptySet())
                        byTag.filter { it.id in members }
                    } else byTag
                    val byOrigin = if (!exportFullBackup && exportOriginIds.isNotEmpty()) {
                        byZone.filter { it.originId in exportOriginIds }
                    } else byZone
                    // v5：普通导出默认排除锁定记录；整体备份强制全量
                    val visible = if (!exportFullBackup && !exportIncludeLocked) byOrigin.filter { !it.isLocked } else byOrigin
                    // 引擎出口是"选中记录 + 它们的修正"，所以计数也要把修正算进来，
                    // 否则大数字会比实际导出的少一截（实测少 61 条）。
                    suspend fun withAmends(list: List<memore.app.data.model.Record>): Int {
                        val amends = runCatching { repo.getAmendmentsForRecords(list.map { it.id }) }.getOrDefault(emptyList())
                        return (list + amends).distinctBy { it.id }.size
                    }
                    suspend fun scoped(list: List<memore.app.data.model.Record>): List<memore.app.data.model.Record> {
                        val amends = runCatching { repo.getAmendmentsForRecords(list.map { it.id }) }.getOrDefault(emptyList())
                        return (list + amends).distinctBy { it.id }
                    }
                    val finalList = if (!exportFullBackup && exportTickIds.isNotEmpty()) {
                        scoped(visible)
                    } else {
                        val dayMs = 24L * 60 * 60 * 1000
                        val now = System.currentTimeMillis()
                        val (start, end) = if (exportFullBackup) null to null else when (exportTimeOption) {
                            1 -> (now - 7 * dayMs) to null
                            2 -> (now - 30 * dayMs) to null
                            3 -> exportCustomStart to exportCustomEnd?.let { it + dayMs - 1 }
                            else -> null to null
                        }
                        scoped(visible.filter { r -> (start == null || r.createdAt >= start) && (end == null || r.createdAt <= end) })
                    }
                    // 2026-11-16：附件那一行也要跟着范围走（原来用的是全库总数，跟大数字自相矛盾）
                    exportAttachmentText = if (exportIsMarkdown || (!exportWithAttachments && !exportFullBackup)) {
                        null
                    } else runCatching {
                        val (cnt, bytes) = repo.attachmentTotalFor(finalList.map { it.id })
                        if (cnt == 0) null else "$cnt 个 · ${bytes / 1024 / 1024} MB"
                    }.getOrNull()
                    finalList.size
                }.getOrNull()
            }
        }
        // 导出 = 独立页面。版面严格按规范表：顶栏 56；页边 20；行 52 / 选项行 48；
        // 发丝线 18%（大分组）与 10%（行间）；主按钮不吸底，跟在内容后面。
        BackHandler(enabled = true) { showExportDialog = false }
        var exportContentExpanded by remember { mutableStateOf(false) }
        // 附件那行由上面 exportCount 的同一个 LaunchedEffect 计算（必须跟着范围走），这里不再单独读全库。
        // 2026-11-16：这一屏原来是设置内容区里的一层 Surface（zIndex 2f）——
        // 底部 tab 栏始终盖在上面，展开列表时内容会被导航条切掉。
        // 现在放进全屏 Dialog（自己的窗口）：盖住导航、独立处理返回键，也不再和设置页抢滚动。
        Dialog(
            onDismissRequest = { showExportDialog = false },
            properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)
        ) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .windowInsetsPadding(androidx.compose.foundation.layout.WindowInsets.systemBars)
                ) {
                Row(
                    Modifier.fillMaxWidth().height(56.dp).padding(start = 8.dp, end = 20.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { showExportDialog = false }, modifier = Modifier.size(40.dp)) {
                        Icon(IconBack, "返回", Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurface)
                    }
                    Spacer(Modifier.width(4.dp))
                    Text(
                        "导出",
                style = memore.app.ui.theme.MemoreType.pageTitleSans,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
                    Spacer(Modifier.height(4.dp))
                    Text("这次会打包", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(2.dp))
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            exportCount?.toString() ?: "—",
                            fontSize = 38.sp, // design-law: allow（导出统计的主数字，令牌表最接近的是 22）
                            lineHeight = 40.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = (-0.5).sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "条记录",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 4.dp)
                        )
                    }
                    Spacer(Modifier.height(18.dp))
                    ExportRule(strong = true)
                    ExportGroupLabel("产物")

                    // 格式（2026-11-16 合并）：结构化（可回导）+ Markdown（给 AI 读）
                    // 原来 JSON / 完整数据包是两个格式，其实只差"要不要附件"，合并后由附件开关推导。
                    val formatNow = when {
                        exportFormat == ExportFormat.AI_MARKDOWN -> "给 AI 读（Markdown）"
                        exportIsZip -> "结构化数据 + 附件（ZIP）"
                        else -> "结构化数据（JSON，无附件）"
                    }
                    ExportNavRow("格式", formatNow, exportFormatExpanded, enabled = !exportFullBackup, first = true) {
                        exportFormatExpanded = !exportFormatExpanded
                    }
                    if (exportFormatExpanded) {
                        ExportOptionCard {
                            ExportOptionRow("结构化数据（可回导）", exportFormat != ExportFormat.AI_MARKDOWN, enabled = !exportFullBackup) {
                                exportFormat = ExportFormat.JSON_ONLY
                            }
                            ExportOptionRow("Markdown（给 AI 读，不可导入）", exportFormat == ExportFormat.AI_MARKDOWN, dividerAbove = true) {
                                exportFormat = ExportFormat.AI_MARKDOWN
                            }
                            // 附件开关：决定结构化导出产出 .json 还是 .zip
                            if (exportFormat != ExportFormat.AI_MARKDOWN) {
                                ExportOptionDivider()
                                ExportToggleRow("包含附件（图片 / 音频）", exportWithAttachments || exportFullBackup, enabled = !exportFullBackup) {
                                    exportWithAttachments = it
                                }
                            }
                        }
                    }

                    // ===== 范围 =====
                    ExportRule(strong = true)
                    ExportGroupLabel("范围")
                    ExportNavRow("协议", if (exportProtocolIds.isEmpty()) "全部" else "${exportProtocolIds.size} 个", exportProtocolExpanded, enabled = !exportFullBackup, first = true) {
                        exportProtocolExpanded = !exportProtocolExpanded
                    }
                    if (exportProtocolExpanded) {
                        if (allExportProtocols.isEmpty()) {
                            ExportOptionCard {
                                Text("暂无协议", style = MemoreType.meta, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(12.dp))
                            }
                        } else {
                            ExportOptionCard {
                                Column(Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState())) {
                                    allExportProtocols.forEachIndexed { i, protocol ->
                                        ExportOptionRow(protocol.name, protocol.id in exportProtocolIds, enabled = !exportFullBackup, dividerAbove = i > 0) {
                                            exportProtocolIds = if (protocol.id in exportProtocolIds) exportProtocolIds - protocol.id else exportProtocolIds + protocol.id
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // 刻度
                    ExportNavRow("刻度", if (exportTickIds.isEmpty()) "全部" else "${exportTickIds.size} 个", exportTickExpanded, enabled = !exportFullBackup) {
                        exportTickExpanded = !exportTickExpanded
                    }
                    if (exportTickExpanded) {
                        if (allExportTicks.isEmpty()) {
                            ExportOptionCard {
                                Text("暂无刻度", style = MemoreType.meta, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(12.dp))
                            }
                        } else {
                            ExportOptionCard {
                                Column(Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState())) {
                                    allExportTicks.forEachIndexed { i, tick ->
                                        ExportOptionRow(tick.name, tick.id in exportTickIds, enabled = !exportFullBackup, dividerAbove = i > 0) {
                                            exportTickIds = if (tick.id in exportTickIds) exportTickIds - tick.id else exportTickIds + tick.id
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // 时间
                    val timeNow = when (exportTimeOption) {
                        1 -> "最近 7 天"
                        2 -> "最近 30 天"
                        3 -> "自定义"
                        else -> "全部"
                    }
                    ExportNavRow("时间", timeNow, exportTimeExpanded, enabled = !exportFullBackup) {
                        exportTimeExpanded = !exportTimeExpanded
                    }
                    if (exportTimeExpanded) {
                        ExportOptionCard {
                            listOf(0 to "全部", 1 to "最近 7 天", 2 to "最近 30 天", 3 to "自定义").forEachIndexed { i, (opt, label) ->
                                ExportOptionRow(label, exportTimeOption == opt, enabled = !exportFullBackup, dividerAbove = i > 0) {
                                    exportTimeOption = opt
                                }
                            }
                            if (exportTimeOption == 3) {
                                ExportOptionRow(
                                    exportCustomStart?.let { "起始 · ${formatDateOnly(it)}" } ?: "起始日期",
                                    exportCustomStart != null,
                                    dividerAbove = true
                                ) { showStartDatePicker = true }
                                ExportOptionRow(
                                    exportCustomEnd?.let { "截止 · ${formatDateOnly(it)}" } ?: "截止日期",
                                    exportCustomEnd != null,
                                    dividerAbove = true
                                ) { showEndDatePicker = true }
                            }
                        }
                    }

                    // 2026-11-16：范围筛选补齐——标签 / 区域 / 来源
                    ExportNavRow("标签", if (exportTagIds.isEmpty()) "全部" else "${exportTagIds.size} 个", exportTagExpanded, enabled = !exportFullBackup) {
                        exportTagExpanded = !exportTagExpanded
                    }
                    if (exportTagExpanded) {
                        ExportOptionCard {
                            if (allExportTags.isEmpty()) {
                                Text("暂无标签", style = MemoreType.meta, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(12.dp))
                            } else {
                                Column(Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState())) {
                                    allExportTags.forEachIndexed { i, tag ->
                                        ExportOptionRow(tag.name, tag.id in exportTagIds, enabled = !exportFullBackup, dividerAbove = i > 0) {
                                            exportTagIds = if (tag.id in exportTagIds) exportTagIds - tag.id else exportTagIds + tag.id
                                        }
                                    }
                                }
                            }
                        }
                    }
                    ExportNavRow("区域", if (exportZoneIds.isEmpty()) "全部" else "${exportZoneIds.size} 个", exportZoneExpanded, enabled = !exportFullBackup) {
                        exportZoneExpanded = !exportZoneExpanded
                    }
                    if (exportZoneExpanded) {
                        ExportOptionCard {
                            if (allExportZones.isEmpty()) {
                                Text("暂无区域", style = MemoreType.meta, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(12.dp))
                            } else {
                                Column(Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState())) {
                                    allExportZones.forEachIndexed { i, zone ->
                                        ExportOptionRow(zone.name, zone.id in exportZoneIds, enabled = !exportFullBackup, dividerAbove = i > 0) {
                                            exportZoneIds = if (zone.id in exportZoneIds) exportZoneIds - zone.id else exportZoneIds + zone.id
                                        }
                                    }
                                }
                            }
                        }
                    }
                    ExportNavRow("来源", if (exportOriginIds.isEmpty()) "全部" else "${exportOriginIds.size} 个", exportOriginExpanded, enabled = !exportFullBackup) {
                        exportOriginExpanded = !exportOriginExpanded
                    }
                    if (exportOriginExpanded) {
                        // 2026-11-16：来源可能有十几个，展开时限制高度（和协议一致），
                        // 否则一展开就把"内容 / 交付 / 导出"整段挤出屏幕。
                        ExportOptionCard {
                            Column(Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState())) {
                                allExportOrigins.forEachIndexed { i, origin ->
                                    ExportOptionRow(origin.name, origin.id in exportOriginIds, enabled = !exportFullBackup, dividerAbove = i > 0) {
                                        exportOriginIds = if (origin.id in exportOriginIds) exportOriginIds - origin.id else exportOriginIds + origin.id
                                    }
                                }
                            }
                        }
                    }

                    // ===== 内容 =====
                    ExportRule(strong = true)

                    // 2026-11-16：选项收敛成一张规格表（单一真源），桌面列表与底部 sheet 都从它渲染。
                    // 之前是两份手写列表，已经分叉过一次（整体备份时"锁定记录/刻度切片"没被强制勾上）。
                    val contentSummary = if (exportFullBackup) "整体备份" else {
                        val allOn = listOf(
                            exportIncludeEmotion,
                            exportIncludeAlias, exportIncludePrompts,
                            exportIncludeVoice, exportIncludeDrafts
                        ).all { it }
                        if (allOn) "全部" else "自定义"
                    }
                    ExportNavRow("内容", contentSummary, exportContentExpanded) {
                        exportContentExpanded = !exportContentExpanded
                    }
                    if (exportContentExpanded) {
                        ExportOptionCard {
                            ExportToggleRow("整体备份", exportFullBackup) { on ->
                                exportFullBackup = on
                                if (on) {
                                    exportFormat = ExportFormat.FULL_PACKAGE
                                    exportProtocolIds = emptySet()
                                    exportTickIds = emptySet()
                                    exportTimeOption = 0
                                    exportCustomStart = null
                                    exportCustomEnd = null
                                }
                            }
                            ExportOptionDivider()
                            ExportToggleRow("紧凑 JSON（体积小约 24%）", exportCompactJson) { exportCompactJson = it }
                            exportContentSpecs.forEach { spec ->
                                ExportOptionDivider()
                                ExportToggleRow(spec.label, spec.checked, enabled = spec.enabled) { v -> spec.onChange(v) }
                            }
                            Text(
                                "附加处理",
                                style = MemoreType.label,
                                letterSpacing = 1.1.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 12.dp, top = 12.dp, bottom = 2.dp)
                            )
                            exportProcessSpecs.forEach { spec ->
                                ExportOptionDivider()
                                ExportToggleRow(spec.label, spec.checked, enabled = spec.enabled) { v -> spec.onChange(v) }
                            }
                        }
                    }

                    // ===== 交付 =====
                    ExportRule(strong = true)
                    val deliveryNow = when (exportDelivery) {
                        ExportDelivery.DOWNLOADS -> "保存到 Download"
                        ExportDelivery.SHARE -> "保存 + 分享"
                        else -> "另存为"
                    }
                    ExportNavRow("交付", deliveryNow, exportDeliveryExpanded) {
                        exportDeliveryExpanded = !exportDeliveryExpanded
                    }
                    if (exportDeliveryExpanded) {
                        ExportOptionCard {
                            ExportOptionRow("另存为…（自己挑位置）", exportDelivery == ExportDelivery.SAVE_AS) {
                                exportDelivery = ExportDelivery.SAVE_AS; ExportDelivery.write(prefsRef, ExportDelivery.SAVE_AS)
                            }
                            ExportOptionRow("保存到 Download", exportDelivery == ExportDelivery.DOWNLOADS, dividerAbove = true) {
                                exportDelivery = ExportDelivery.DOWNLOADS; ExportDelivery.write(prefsRef, ExportDelivery.DOWNLOADS)
                            }
                            ExportOptionRow("保存 + 分享", exportDelivery == ExportDelivery.SHARE, dividerAbove = true) {
                                exportDelivery = ExportDelivery.SHARE; ExportDelivery.write(prefsRef, ExportDelivery.SHARE)
                            }
                        }
                    }

                    // 附件（只有结构化包才带上，显示的是当前范围内的真实体积）
                    // 2026-11-16：条件从 exportFormat == FULL_PACKAGE 改成 exportIsZip ——
                    // 格式合并后"含附件"是开关推出来的，exportFormat 可能还停在 JSON_ONLY，
                    // 原来那条件会让这一行凭空消失（真机上复现过）。
                    if (exportIsZip) {
                        ExportRule(strong = true)
                        Row(Modifier.fillMaxWidth().height(44.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("附件", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.weight(1f))
                            Text(
                                exportAttachmentText ?: "—",
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }

                    Spacer(Modifier.height(24.dp))
                    Button(
                        onClick = {
                            showExportDialog = false
                            // 2026-11-16（v5）：不再有"输密码解密后导出"这一步——
                            // 锁定记录要么排除，要么以明文导出（见「锁定记录」开关）。
                            startExport(false)
                        },
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        shape = MirrorShapes.card,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                    ) {
                        Text("导出", style = MemoreType.button, color = MaterialTheme.colorScheme.onPrimary)
                    }
                    Spacer(Modifier.height(24.dp))
                }
            }
            }
        }
    }

    // 整体备份 + 没开「明文解密」+ 真有加密附件：先说清楚"这些附件不会进包"，不静默
    if (lockedAttachWarn > 0) {
        MirrorAlertDialog(
            onDismissRequest = { lockedAttachWarn = 0 },
            title = { Text("有 ${lockedAttachWarn} 个加密附件不会进包") },
            text = {
                Text(
                    "锁定记录的附件只能在勾选「明文解密」并输入主密码之后才导出。现在导出的话，" +
                        "这 ${lockedAttachWarn} 个附件的文件本体不会写进备份包（记录本身不受影响）。"
                )
            },
            confirmButton = { TextButton(onClick = { lockedAttachWarn = 0; reallyStartExport(false) }) { Text("仍然导出") } },
            dismissButton = { TextButton(onClick = { lockedAttachWarn = 0 }) { Text("取消") } }
        )
    }

    // 导出明文解密：密码验证框
    if (showExportDecryptPwDialog) {
        // A 版（2026-11-06）：居中标题、无说明、横线输入、整宽确认、下方灰取消
        memore.app.ui.components.MirrorStackDialog(
            title = "输入主密码",
            confirmText = "确认",
            onDismiss = { showExportDecryptPwDialog = false },
            onConfirm = {
                scope.launch {
                    if (EncryptionManager.verifyPasswordCached(exportDecryptPw)) {
                        EncryptionManager.ensureKey(exportDecryptPw)
                        showExportDecryptPwDialog = false
                        startExport(true)
                    } else {
                        exportDecryptPwErr = "密码错误"
                        UiSoundPlayer.playPasswordError(context)
                    }
                }
            }
        ) {
            memore.app.ui.components.MirrorLineField(
                value = exportDecryptPw,
                onValueChange = { exportDecryptPw = it; exportDecryptPwErr = null },
                placeholder = "主密码",
                password = true,
                error = exportDecryptPwErr
            )
        }
    }

    // ===== 自定义日期选择器 =====
    // 两端都要做时区转换：选择器给/收的都是"该日历日的 UTC 零点"，
    // 直接当本地时间用会偏 8 小时（UTC+8），而界面上只看得出日期，看不出偏移。
    if (showStartDatePicker) {
        val pickerState = rememberDatePickerState(initialSelectedDateMillis = localMillisToDatePicker(exportCustomStart ?: System.currentTimeMillis()))
        DatePickerDialog(
            onDismissRequest = { showStartDatePicker = false },
            confirmButton = { TextButton(onClick = {
                pickerState.selectedDateMillis?.let { exportCustomStart = datePickerToLocalStartOfDay(it) }
                showStartDatePicker = false
            }) { Text("确定") } },
            dismissButton = { TextButton(onClick = { showStartDatePicker = false }) { Text("取消") } }
        ) {
            DatePicker(state = pickerState)
        }
    }
    if (showEndDatePicker) {
        val pickerState = rememberDatePickerState(initialSelectedDateMillis = localMillisToDatePicker(exportCustomEnd ?: System.currentTimeMillis()))
        DatePickerDialog(
            onDismissRequest = { showEndDatePicker = false },
            confirmButton = { TextButton(onClick = {
                // 存"所选日期的本地零点"（显示层要按这个显示日期，不能加一天）
                pickerState.selectedDateMillis?.let { exportCustomEnd = datePickerToLocalStartOfDay(it) }
                showEndDatePicker = false
            }) { Text("确定") } },
            dismissButton = { TextButton(onClick = { showEndDatePicker = false }) { Text("取消") } }
        ) {
            DatePicker(state = pickerState)
        }
    }


    if (showThemePicker) {
        val ss = rememberModalBottomSheetState()
        MirrorSheet(onDismissRequest = { showThemePicker = false }, containerColor = MaterialTheme.colorScheme.surfaceContainerLow, sheetState = ss) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
                Text(
                    "选择主题配色",
                    style = MemoreType.micro,
                    letterSpacing = 1.1.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 6.dp)
                )
                ThemeManager.presets.forEach { preset ->
                    val selected = preset.name == ThemeManager.currentTheme
                    Column(Modifier.fillMaxWidth()) {
                        Row(
                            Modifier.fillMaxWidth()
                                .clickable {
                                    ThemeManager.apply(preset.name)
                                    UiSoundPlayer.playThemeVoice(context, preset.name)
                                    showThemePicker = false
                                }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // 缩略"页面"：用主题自己的纸/墨/主色画三行字 + 一颗按钮（不是截图）
                            Box(
                                Modifier
                                    .size(width = 62.dp, height = 40.dp)
                                    .clip(MirrorShapes.row)
                                    .background(preset.surface)
                                    .border(
                                        if (selected) 2.dp else 1.dp,
                                        if (selected) preset.primary else preset.border,
                                        MirrorShapes.row
                                    )
                            ) {
                                Box(Modifier.padding(start = 7.dp, top = 9.dp).width(34.dp).height(3.dp).clip(MirrorShapes.pill).background(preset.textPrimary))
                                Box(Modifier.padding(start = 7.dp, top = 16.dp).width(46.dp).height(3.dp).clip(MirrorShapes.pill).background(preset.textPrimary.copy(alpha = 0.55f)))
                                Box(Modifier.padding(start = 7.dp, top = 23.dp).width(26.dp).height(3.dp).clip(MirrorShapes.pill).background(preset.textPrimary.copy(alpha = 0.35f)))
                                Box(
                                    Modifier.align(Alignment.BottomStart).padding(start = 7.dp, bottom = 6.dp)
                                        .width(20.dp).height(5.dp).clip(MirrorShapes.pill).background(preset.primary)
                                )
                            }
                            Spacer(Modifier.width(12.dp))
                            Text(
                                preset.name,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f)
                            )
                            if (selected) {
                                Text("✓", style = MemoreType.meta, color = MaterialTheme.colorScheme.primary)
                            }
                        }
                        if (preset.name != ThemeManager.presets.last().name) {
                            Box(
                                Modifier.fillMaxWidth().height(1.dp)
                                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f))
                            )
                        }
                    }
                }
            }
        }
    }

    if (showImportConfirm) {
        val pkgCfg = importPreview?.packageEncryptionConfig
        val pkgLocked = importPreview?.packageLockedCount ?: 0
        // 包里带真密文的锁定记录 + 本机已有密码 → 需要一个"那台设备的凭据"才能把它们接管过来
        val canAdopt = pkgLocked > 0 && pkgCfg != null && EncryptionManager.hasPassword()
        MirrorAlertDialog(
            onDismissRequest = { showImportConfirm = false }, title = { Text("\u5BFC\u5165\u9884\u89C8") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(importResult ?: "")
                    if (canAdopt) {
                        Text(
                            "这个包里有 $pkgLocked 条记录是用「另一台设备」的密码加密的，本机密码打不开它们。" +
                                "填上那台设备的密码或密保答案，就能把这批记录转成本机密码；留空则跳过它们。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        MemoreLineInput(colors = mirrorFieldColors(), 
                            value = importAlienCred, onValueChange = { importAlienCred = it },
                            singleLine = true, shape = MirrorShapes.row, modifier = Modifier.fillMaxWidth()
                        )
                        if (EncryptionManager.getCachedKey() == null) {
                            MemoreLineInput(colors = mirrorFieldColors(), 
                                value = importLocalPw, onValueChange = { importLocalPw = it },
                                visualTransformation = PasswordVisualTransformation(),
                                singleLine = true, shape = MirrorShapes.row, modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showImportConfirm = false; val uri = importPendingUri; if (uri != null && recordRepository != null && tagRepository != null && true) { scope.launch {
                // 先把"那台设备的密钥"解出来。用户不用挑是密码还是答案 —— 两种都试一遍。
                var alienKey: javax.crypto.SecretKey? = null
                if (canAdopt && importAlienCred.isNotBlank() && pkgCfg != null) {
                    val cred = importAlienCred.trim()
                    val byPw = EncryptionManager.resolvePackageKey(pkgCfg, cred, asAnswer = false)
                    val resolved = if (byPw.isSuccess) byPw else EncryptionManager.resolvePackageKey(pkgCfg, cred, asAnswer = true)
                    val k = resolved.getOrNull()
                    if (k == null) {
                        importEncWarning = resolved.exceptionOrNull()?.message ?: "那台设备的凭据不对"
                        return@launch
                    }
                    // 本机密钥没缓存就没法补锁 → 用本机密码取一次（否则那批记录会停在「待真锁」）
                    if (EncryptionManager.getCachedKey() == null) {
                        if (importLocalPw.isBlank()) { importEncWarning = "请填本机密码：没有它，接管的记录无法补锁到本机"; return@launch }
                        val lk = EncryptionManager.ensureKey(importLocalPw)
                        if (lk.isFailure) { importEncWarning = lk.exceptionOrNull()?.message ?: "本机密码不对"; return@launch }
                    }
                    alienKey = k
                }
                val encDao = (context.applicationContext as? MemoreApp)?.container?.encryptionConfigDao
                val tagGroupRepo = (context.applicationContext as? MemoreApp)?.container?.tagGroupRepository
                ImportEngine.execute(context, uri, recordRepository, tagRepository, protocolRepository, aliasMappingRepo, encDao, tickRepository, tagGroupRepo, redactionRepository, draftRepository = (context.applicationContext as? MemoreApp)?.container?.draftRepository, dialRepository = (context.applicationContext as? MemoreApp)?.container?.dialRepository,
                    // 2026-11-16 v2：还原区域/来源/概念分类（老包没有这些键 → 引擎跳过）
                    zoneDao = (context.applicationContext as? MemoreApp)?.container?.database?.zoneDao(),
                    originDao = (context.applicationContext as? MemoreApp)?.container?.database?.originDao(),
                    alienKey = alienKey, database = (context.applicationContext as? MemoreApp)?.container?.database).onSuccess { result ->
                    result.systemInstruction?.let { instruction ->
                        memore.app.core.ai.SystemInstructionStore.applyImported(context, instruction)
                    }
                    val draftNote = if (result.draftsImported > 0 || result.draftsSkipped > 0)
                        "；草稿 ${result.draftsImported} 本" + (if (result.draftsSkipped > 0) "（跳过 ${result.draftsSkipped} 本重复）" else "") else ""
                    context.memoreToast("\u6210\u529F\u5BFC\u5165 ${result.imported} \u6761\uFF0C\u8DF3\u8FC7 ${result.skipped} \u6761\u91CD\u590D" + draftNote, Toast.LENGTH_LONG)
                    UiSoundPlayer.playImportDone(context)
                    // \u52A0\u5BC6\u914D\u7F6E\u51B2\u7A81 / \u635F\u574F\u8BB0\u5F55\u8B66\u544A
                    val warnings = mutableListOf<String>()
                    if (result.hasEncryptionConflict) warnings.add("\u6587\u4EF6\u5305\u542B\u52A0\u5BC6\u914D\u7F6E\uFF0C\u4F46\u672C\u5730\u5DF2\u6709\u5BC6\u7801\uFF0C\u672A\u8986\u76D6")
                    if (result.damagedLockedCount > 0) warnings.add("${result.damagedLockedCount} \u6761\u9501\u5B9A\u8BB0\u5F55\u7F3A\u5C11\u52A0\u5BC6\u914D\u7F6E\uFF0C\u5C06\u65E0\u6CD5\u89E3\u9501")
                    if (result.skippedLockedCount > 0) warnings.add("${result.skippedLockedCount} \u6761\u9501\u5B9A\u8BB0\u5F55\u56E0\u5BC6\u7801\u4E0D\u540C\u672A\u5BFC\u5165")
                    if (warnings.isNotEmpty()) importEncWarning = warnings.joinToString("\n")
                }
                    .onFailure { context.memoreToast(it.message ?: "\u5BFC\u5165\u5931\u8D25", Toast.LENGTH_LONG); UiSoundPlayer.playError(context) }
            } } }) { Text("\u786E\u8BA4\u5BFC\u5165", color = MaterialTheme.colorScheme.primary) } },
            dismissButton = { TextButton(onClick = { showImportConfirm = false }) { Text("\u53D6\u6D88") } }
        )
    }
    if (importEncWarning != null) {
        MirrorStackDialog(
            title = "导入警告",
            message = importEncWarning,
            confirmText = "知道了",
            destructive = false,
            onDismiss = { importEncWarning = null },
            onConfirm = { importEncWarning = null }
        )
    }
    refreshKey.let { }

    // ===== 加密：独立页面（2026-10-23 从弹窗改页）=====

    // 长任务进度对话框（模态、不可取消；任务结束自动消失）
    memore.app.ui.components.OpProgressDialog("正在导出", exportProgress)
    memore.app.ui.components.OpProgressDialog("正在导入", importProgress)

    // 旧方案（两步法第二步独立接口）已随二步法删除而移除


    // 根屏（无返回）；教程入口按 1.3 从标题左侧移到右端动作区
    MemorePage(title = "设置", actions = {
        if (FeatureConsole.visible(FeatureConsole.TUTORIAL)) {
            IconButton(onClick = { UiSoundPlayer.playTutorialOpen(context); onOpenTutorial() }) {
                Icon(IconTutorial, contentDescription = "使用教程", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding(), start = 16.dp, end = 16.dp).verticalScroll(rememberScrollState())) {
            Spacer(Modifier.height(4.dp))
            SettingsGroupTitle("应用")
            SettingsItem(Icons.Outlined.Palette, "\u66F4\u6362\u4E3B\u9898\u914D\u8272", ThemeManager.currentTheme) { showThemePicker = true }
            SettingsRowDivider()
            if (FeatureConsole.visible(FeatureConsole.APP_LOCK)) {
                SettingsToggleRow(Icons.Outlined.Fingerprint, "\u5E94\u7528\u9501", pinEnabled) { e ->
                    if (e) {
                        if (AuthManager.hasPin()) { AuthManager.enableLock(); pinEnabled = true; UiSoundPlayer.playLockOn(context) } else onOpenPinSetup()
                    } else showLockMenu = true
                }
                SettingsRowDivider()
            }
            // 加密管理：从 FIX 连点转正（2026-10-23），与应用锁同属「保护」一组
            if (FeatureConsole.visible(FeatureConsole.ENCRYPTION_MANAGE)) {
                SettingsItem(Icons.Filled.Lock, "加密管理") { openEncryptionEntry() }
                SettingsRowDivider()
            }
            Spacer(Modifier.height(2.dp))
            // AI 配置：搬到独立页（2026-10-23）。设置页只留一行摘要。
            if (FeatureConsole.visible(FeatureConsole.AI_CONFIG)) {
                val aiModeLabel = context.getSharedPreferences("mirror_prefs", android.content.Context.MODE_PRIVATE)
                    .let { p ->
                        val m = p.getString("ai_mode", "local") ?: "local"
                        val preset = p.getString("ai_selected_preset", "") ?: ""
                        if (m == "local") "本地" else if (preset.isNotBlank()) "$preset · API" else "API"
                    }
                SettingsItem(Icons.Outlined.Psychology, "AI 配置", aiModeLabel) { onOpenAiConfig() }
                SettingsRowDivider()
                if (FeatureConsole.visible(FeatureConsole.AGENT_LINK)) {
                    SettingsItem(Icons.Outlined.SwapHoriz, "Agent 连接") { onOpenAgentLink() }
                    SettingsRowDivider()
                }
                // 语音实验室：从隐藏入口转正（2026-10-23），归「应用」一组（2026-11-13）
                if (FeatureConsole.visible(FeatureConsole.VOICE_LAB)) {
                    SettingsItem(Icons.Outlined.Mic, "\u8BED\u97F3\u5B9E\u9A8C\u5BA4") { onOpenVoiceLab() }
                }
            }
            Spacer(Modifier.height(20.dp))
            SettingsGroupTitle("内容")
            if (FeatureConsole.visible(FeatureConsole.AI_CONFIG)) {
                // 洞察档案：批量与近一月共用（2026-11-08 从时间线 ⋮ 搬来）
                if (FeatureConsole.visible(FeatureConsole.INSIGHT_ARCHIVE)) {
                    SettingsItem(Icons.Outlined.Dataset, "洞察档案") { onOpenInsightArchive() }
                    SettingsRowDivider()
                }
                if (FeatureConsole.visible(FeatureConsole.TOPICS)) {
                    SettingsItem(Icons.Outlined.Hub, "主题") { onOpenTopics() }
                }
                SettingsRowDivider()
                if (FeatureConsole.visible(FeatureConsole.VECTOR_INDEX)) {
                    SettingsItem(Icons.Outlined.Storage, "向量索引", "") { onOpenVectorIndex() }
                }
                SettingsRowDivider()
            }
            if (FeatureConsole.visible(FeatureConsole.PROMPT_MANAGE)) {
                SettingsItem(Icons.Outlined.Description, "提示词管理") { onOpenPromptManage() }
                SettingsRowDivider()
            }
            if (FeatureConsole.visible(FeatureConsole.EMOTION)) {
                SettingsItem(Icons.Outlined.Insights, "\u60C5\u7EEA\u5206\u6790") { onOpenEmotion() }
                SettingsRowDivider()
            }
            if (FeatureConsole.visible(FeatureConsole.ALIAS_MANAGE)) {
                SettingsItem(Icons.Outlined.Flip, "\u522B\u540D\u6620\u5C04\u7BA1\u7406") { onOpenAliasMapping() }
            }
            Spacer(Modifier.height(20.dp))
            SettingsItem(Icons.Outlined.FileUpload, "\u5BFC\u51FA\u8BB0\u5FC6\u5305") {
                if (recordRepository != null && tagRepository != null && true) { showExportDialog = true }
                else context.memoreToast("\u5BFC\u51FA\u8BB0\u5FC6\u5305", Toast.LENGTH_SHORT)
            }
            SettingsRowDivider()
            SettingsItem(Icons.Outlined.FileDownload, "\u5BFC\u5165\u8BB0\u5FC6\u5305") { importFileLauncher.launch(arrayOf("application/json", "application/zip")) }
            Spacer(Modifier.height(28.dp))
            TextButton(onClick = { onOpenFixTools() }, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("FIX", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Spacer(Modifier.height(10.dp))
            Text(
                "Mirror " + runCatching {
                    context.packageManager.getPackageInfo(context.packageName, 0).versionName
                }.getOrNull().orEmpty(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )
            Spacer(Modifier.height(padding.calculateBottomPadding() + 40.dp))
        }
    }
}

/**
 * 设置项：**一行**，不是一张带解释的卡。
 *
 * 形制抄自参考图：左图标（20dp，outlined）+ 标题 + 尾部当前值（13sp，弱化）+ ›。
 * 三处刻意的取舍：
 * ① **没有副标题** —— 那个位置放的是"当前值"（现在是什么主题 / 走本地还是 API），不是解释句；
 * ② 行高固定 52dp，比原来"标题 + 解释"两张行叠起来矮一半，一屏能多看两项；
 * ③ 尾部值只在真有值的时候出现，没有值就只留箭头，不编造。
 */
@Composable
internal fun SettingsItem(
    icon: ImageVector,
    title: String,
    value: String? = null,
    onClick: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(MirrorShapes.row)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp)
            .height(50.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(15.dp))
        Text(
            title,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
            maxLines = 1
        )
        if (value != null) {
            Text(
                value,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
            Spacer(Modifier.width(10.dp))
        }
        Icon(
            Icons.Outlined.KeyboardArrowRight,
            null,
            Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        )
    }
}

/** 行间细分隔线：从图标右侧起画，不切到左边。 */
@Composable
/** 分组标题（2026-11-13）：两组之间不加解释，只给名字。 */
private fun SettingsGroupTitle(text: String) {
    Text(
        text,
        style = MemoreType.micro,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, top = 2.dp, bottom = 8.dp)
    )
}

@Composable
private fun SettingsRowDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(start = 41.dp, end = 8.dp),
        color = mirrorHairlineColor()
    )
}

/** 带开关的设置行（无卡片，与 SettingsItem 同一套骨架）。 */
@Composable
private fun SettingsToggleRow(
    icon: ImageVector,
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp).height(50.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(15.dp))
        Text(
            title,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
            maxLines = 1
        )
        Box(Modifier.size(width = 48.dp, height = 28.dp), contentAlignment = Alignment.Center) {
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                modifier = Modifier.scale(0.85f),
                colors = SwitchDefaults.colors(
                    checkedThumbColor = MaterialTheme.colorScheme.primary,
                    checkedTrackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
                )
            )
        }
    }
}

/** 面板里的组标题：小一号、弱一档，靠它分节而不是靠横线。 */
@Composable
private fun SheetSect(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 22.dp, top = 14.dp, bottom = 6.dp)
    )
}

/** 面板里的组卡：和页面同档圆角 + 同一根发丝边。 */
@Composable
private fun SheetGroupCard(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.padding(horizontal = 10.dp).fillMaxWidth()
            .clip(MirrorShapes.card)
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f), MirrorShapes.card),
        content = content
    )
}

/** 行间细线：左右内缩，不顶到卡的圆角上。 */
@Composable
private fun SheetDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 11.dp),
        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.16f)
    )
}

/**
 * 一行一个开关：标题 +（可选）尾部值 + 开关，**没有解释小字**。
 *
 * - `trailing` 只在真有值时出现（前 3 条 / 开 / 整机），没有值就只留标题；
 * - `accent` 用于"整体备份"这种带后果的项：金色文字 + 左侧一根金条，而不是把标题染成链接色；
 * - 开关缩到 0.78 倍（M3 默认 52×32 在这个面板里偏大，会把行撑高、显得笨）。
 */
@Composable
private fun SheetToggleRow(
    title: String,
    checked: Boolean,
    enabled: Boolean = true,
    trailing: String? = null,
    accent: Boolean = false,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 42.dp).padding(start = 11.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (accent) {
            Box(Modifier.width(2.5.dp).height(18.dp).clip(MirrorShapes.pill).background(Color(0xFFE6C15A)))
            Spacer(Modifier.width(8.dp))
        }
        Text(
            title,
            style = MaterialTheme.typography.bodyMedium,
            color = when {
                !enabled -> MaterialTheme.colorScheme.onSurfaceVariant
                accent -> Color(0xFFE6C15A) // design-law: allow（导出完成的仪式金）
                else -> MaterialTheme.colorScheme.onSurface
            },
            fontWeight = if (accent) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            modifier = Modifier.weight(1f)
        )
        if (trailing != null) {
            Text(trailing, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(6.dp))
        }
        Box(Modifier.size(width = 44.dp, height = 26.dp), contentAlignment = Alignment.Center) {
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                enabled = enabled,
                modifier = Modifier.scale(0.78f),
                colors = SwitchDefaults.colors(
                    checkedThumbColor = MaterialTheme.colorScheme.primary,
                    checkedTrackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f),
                    uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    uncheckedTrackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f),
                    uncheckedBorderColor = Color.Transparent
                )
            )
        }
    }
}

// ===== 导出页的行与原语（版面规范见 docs）=====
/** 分组发丝线：strong = 18%（大分组之间），否则 10%（行间）。 */
@Composable
private fun ExportRule(strong: Boolean) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(
                MaterialTheme.colorScheme.onSurface.copy(alpha = if (strong) 0.18f else 0.10f)
            )
    )
}

/**
 * 分组小标题（2026-11-16，模板 A）：11sp、加字距，只写"这一段是什么"，不加说明句。
 * 组与组之间再画一条 18% 强线 —— 让这一屏从"九行同构的表格"变回"四段设置"。
 */
@Composable
private fun ExportGroupLabel(text: String) {
    Box(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 2.dp)) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            letterSpacing = 1.1.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * 展开项卡片（2026-11-16，模板 A）：父行的展开内容装进一张浅色卡片。
 *
 * 父子关系靠"同一张卡"表达，而不是靠缩进或一个圆点 ——
 * 之前展开项既没有容器、项与项之间也没有割线，看起来和上面的行混成一团。
 */
@Composable
private fun ExportOptionCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        shape = MirrorShapes.row,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), content = content)
    }
}

/** 卡片内的选项间割线：10%、左侧内缩，和卡片的圆角对齐。 */
@Composable
private fun ExportOptionDivider() {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp)
            .height(1.dp)
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f))
    )
}

/**
 * 导航行：52 高，标签 15/500，值 13 muted，箭头 16。
 *
 * **分割线画在这一行的上面，而不是下面**（首行 first = true 时没有）。
 * 这样展开的内容紧贴自己的标题，线自然落到"下一个功能"之前；
 * 若把线画在行下方，展开后线会跑到标题与选项之间，选项又会和下一项粘在一起。
 */
@Composable
private fun ExportNavRow(
    label: String,
    value: String,
    expanded: Boolean,
    enabled: Boolean = true,
    first: Boolean = false,
    onClick: () -> Unit
) {
    Column {
        if (!first) ExportRule(strong = false)
        Row(
            Modifier.fillMaxWidth().height(52.dp).clickable(enabled = enabled, onClick = onClick),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                label,
                style = MemoreType.body,
                fontWeight = FontWeight.Medium,
                color = if (enabled) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
            )
            Spacer(Modifier.weight(1f))
            Text(
                value,
                style = MemoreType.meta,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
            Spacer(Modifier.width(6.dp))
            Icon(
                if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                null,
                Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f)
            )
        }
    }
}

/** 选项行：44 高；选中＝主色 + 左侧 2dp 竖条 + 尾部「已选」。 */
@Composable
private fun ExportOptionRow(
    label: String,
    selected: Boolean,
    enabled: Boolean = true,
    dividerAbove: Boolean = false,
    onClick: () -> Unit
) {
    Column {
        if (dividerAbove) ExportOptionDivider()
        Row(
            Modifier.fillMaxWidth().height(44.dp).clickable(enabled = enabled, onClick = onClick),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 选中竖条：让"选中的是哪一个"在卡片里一眼可见（原先只有文字变色）
            Box(
                Modifier
                    .padding(start = 12.dp, end = 8.dp)
                    .width(2.dp)
                    .height(18.dp)
                    .background(
                        if (selected) MaterialTheme.colorScheme.primary
                        else androidx.compose.ui.graphics.Color.Transparent
                    )
            )
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = when {
                    !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
                    selected -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.onSurface
                }
            )
            Spacer(Modifier.weight(1f))
            if (selected) {
                // 勾号代替「已选」二字（2026-11-18）：和新建记录选标签同一个记号。
                Text(
                    "✓",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(end = 12.dp)
                )
            }
        }
    }
}

/**
 * 导出一个开关的**规格**（2026-11-16）。
 *
 * 桌面列表与底部 sheet 共用同一份规格表，避免"两处手写、改一处漏一处"——
 * 之前就漏过一次：整体备份时「锁定记录」「刻度切片内容」在 sheet 被强制勾上，桌面列表没有。
 */
private data class ExportRowSpec(
    val label: String,
    val checked: Boolean,
    val enabled: Boolean = true,
    val trailing: String? = null,
    val onChange: (Boolean) -> Unit
)

/** 开关行：48 高，开关 0.85 缩放（38×21 视觉）。 */
@Composable
private fun ExportToggleRow(
    label: String,
    checked: Boolean,
    enabled: Boolean = true,
    onChange: (Boolean) -> Unit
) {
    Row(
        Modifier.fillMaxWidth().height(48.dp).clickable(enabled = enabled) { onChange(!checked) },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (enabled) MaterialTheme.colorScheme.onSurface
            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
        )
        Spacer(Modifier.weight(1f))
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            enabled = enabled,
            modifier = Modifier.scale(0.85f)
        )
    }
}












