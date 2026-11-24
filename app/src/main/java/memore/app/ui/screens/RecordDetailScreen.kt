package memore.app.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import memore.app.ui.components.MemoreLineInput
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.text.BasicTextField
import memore.app.ui.components.AttachmentListViewer
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.background
import memore.app.ui.components.MirrorMenu
import memore.app.ui.components.MirrorMenuAction
import memore.app.ui.components.MirrorAlertDialog
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.RemoveRedEye
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import memore.app.data.model.Record
import memore.app.data.model.RecordType
import memore.app.data.model.Version
import memore.app.data.repository.RecordRepository
import memore.app.ui.components.CopyOption
import memore.app.ui.components.CopyableText
import memore.app.ui.components.IconBack
import memore.app.ui.components.IconLock
import memore.app.ui.components.MirrorShapes
import memore.app.ui.components.mirrorHairline
import memore.app.ui.components.dialogueSegments
import memore.app.ui.components.recordTypeColor
import memore.app.ui.components.recordTypeLabel
import memore.app.utils.EncryptionManager
import memore.app.utils.UiSoundPlayer
import memore.app.utils.formatTimestamp
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun RecordDetailScreen(
    recordId: String,
    repository: RecordRepository,
    onBack: () -> Unit,
    showRelated: Boolean = true,
    onOpenRecord: (String) -> Unit = {},
    onOpenInsight: (String) -> Unit = {},
    loadSimilar: (suspend (String) -> List<Pair<Record, String?>>)? = null,
    loadLinks: (suspend (String) -> List<Pair<Record, String>>)? = null,
    /** 来源（2026-11-11）：显示并可点改；为空则按「未标」显示。 */
    origins: List<memore.app.data.model.Origin> = emptyList(),
    onSetOrigin: ((String?) -> Unit)? = null,
) {
    val context = LocalContext.current
    var record by remember { mutableStateOf<Record?>(null) }
    var showOriginPicker by remember { mutableStateOf(false) }
    var versions by remember { mutableStateOf<List<Version>>(emptyList()) }
    var similarRecords by remember { mutableStateOf<List<Pair<Record, String?>>>(emptyList()) }
    var linkRecords by remember { mutableStateOf<List<Pair<Record, String>>>(emptyList()) }
    var rejectTarget by remember { mutableStateOf<Record?>(null) }
    var decryptedContent by remember { mutableStateOf<String?>(null) }
    var decryptedNotes by remember { mutableStateOf<String?>(null) }
    var decryptedReply by remember { mutableStateOf("") }
    var showUnlockDialog by remember { mutableStateOf(false) }
    var unlockPassword by remember { mutableStateOf("") }
    var unlockError by remember { mutableStateOf<String?>(null) }
    var showVersions by rememberSaveable { mutableStateOf(false) }
    var showSummarySheet by remember { mutableStateOf(false) }
    var showAttachments by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    // 单条洞察（2026-11-08）
    var showInsightMenu by remember { mutableStateOf(false) }
    var showInsightDialog by remember { mutableStateOf(false) }
    var insightRunning by remember { mutableStateOf(false) }
    var insightError by remember { mutableStateOf<String?>(null) }
    var insightClaims by remember { mutableStateOf<List<memore.app.data.vector.InsightService.Claim>>(emptyList()) }
    var insightSaved by remember { mutableStateOf(false) }
    val insightScope = rememberCoroutineScope()
    var insightDialogVisible by remember { mutableStateOf(false) }
    var insightMenuOpen by remember { mutableStateOf(false) }
    var insightModeMenu by remember { mutableStateOf(false) }
    var insightRan2 by remember { mutableStateOf(false) }
    var insightMode by remember {
        mutableStateOf(
            context.getSharedPreferences("mirror_prefs", android.content.Context.MODE_PRIVATE)
                .getString("insight_mode", "隐忧") ?: "隐忧"
        )
    }
    val insightModes = listOf("隐忧", "动机", "在意", "反证")
    var insightRunning2 by remember { mutableStateOf(false) }
    var insightErr2 by remember { mutableStateOf<String?>(null) }
    var insightClaims2 by remember { mutableStateOf<List<memore.app.data.vector.InsightService.Claim>>(emptyList()) }
    val insightSvc = remember { memore.app.data.vector.InsightService(context, repository) }

    LaunchedEffect(recordId) {
        insightClaims = insightSvc.savedForRecord(recordId).firstOrNull()?.claims.orEmpty()
        insightSaved = insightClaims.isNotEmpty()
        record = repository.getRecordById(recordId)
        versions = repository.getVersions(recordId)
    if (loadLinks != null) {
            linkRecords = runCatching { loadLinks(recordId) }.getOrDefault(emptyList())
        }
        if (loadSimilar != null) {
            similarRecords = runCatching { loadSimilar(recordId) }.getOrDefault(emptyList())
        }
    val rec = record
        if (rec?.isLocked == true && EncryptionManager.getCachedKey() != null &&
            !EncryptionManager.isV5()   // v5：没有密文可解，也不自动开锁（必须用户显式输密码）
        ) {
            repository.getDecryptedPayload(recordId).onSuccess { payload ->
                decryptedContent = payload.content
                decryptedNotes = payload.notes
                decryptedReply = payload.dialogueAiReply
            }
        }
    }

    rejectTarget?.let { target ->
        memore.app.ui.components.MirrorStackDialog(
            title = "这条不算关联？",
            confirmText = "不算",
            onDismiss = { rejectTarget = null },
            onConfirm = {
                rejectTarget = null
                scope.launch {
                    runCatching { repository.rejectLink(recordId, target.id) }
                    linkRecords = runCatching { loadLinks?.invoke(recordId) }.getOrNull()
                        ?: linkRecords.filterNot { it.first.id == target.id }
                }
            }
        ) {
            Text(
                "以后不会再把它推荐成关联。",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
    if (record == null) {
        memore.app.ui.components.MirrorLoading()
        return
    }


    if (insightDialogVisible) {
        memore.app.ui.components.MirrorStackDialog(
            title = "洞察",
            confirmText = when { insightRunning2 -> "读取中…"; insightClaims2.isEmpty() -> "开始"; else -> "保存" },
            onConfirm = {
                when {
                    insightRunning2 -> Unit
                    insightClaims2.isEmpty() -> {
                        val rec = record ?: return@MirrorStackDialog
                        insightErr2 = null; insightRunning2 = true
                        insightScope.launch {
                            insightSvc.askSingle(rec, insightMode)
                                .onSuccess { insightClaims2 = it; insightRan2 = true; insightRunning2 = false }
                                .onFailure { insightErr2 = it.message; insightRunning2 = false }
                        }
                    }
                    else -> {
                        insightSvc.save("single", listOf(recordId), insightClaims2, meta = insightMode, recordId = recordId)
                        insightDialogVisible = false
                        onOpenInsight(recordId)
                    }
                }
            },
            onDismiss = { insightDialogVisible = false }
        ) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                memore.app.ui.components.InsightModeHeader(insightMode) { insightMode = it; insightClaims2 = emptyList(); insightErr2 = null }
                Spacer(Modifier.height(10.dp))
                if (insightClaims2.isEmpty() && !insightRunning2 && insightErr2 == null) {
                    Text(
                        "点「开始」",
                        style = memore.app.ui.theme.MemoreType.meta,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                }
                when {
                    insightRunning2 -> {
                        memore.app.ui.components.MirrorLoading()
                        Text("正在读这条", style = memore.app.ui.theme.MemoreType.micro,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    insightErr2 != null -> Text(insightErr2 ?: "", style = memore.app.ui.theme.MemoreType.meta,
                        color = MaterialTheme.colorScheme.error)
                    else -> memore.app.ui.components.InsightCardCompact(insightClaims2)
                }
            }
        }
    }


    Scaffold(
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = { IconButton(onClick = onBack) { Icon(IconBack, "返回") } },
                actions = {
                    // 2026-11-16（v5）：锁定且未解密时不提供任何"取正文"的动作。
                    // 之前这里无条件挂「洞察」——洞察会把 rec.content 直接喂给模型，
                    // 门闩式下正文就在 content 里，等于给了一条绕开门闩的读文通道。
                    val lockGuarded = record?.isLocked == true && decryptedContent == null
                    if (!lockGuarded) Box {
                        IconButton(onClick = { insightMenuOpen = true }) {
                            Icon(androidx.compose.material.icons.Icons.Outlined.MoreVert, "更多", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        MirrorMenu(
                            expanded = insightMenuOpen,
                            onDismissRequest = { insightMenuOpen = false },
                            actions = buildList {
                                add(MirrorMenuAction("洞察") {
                                    insightMenuOpen = false
                                    insightClaims2 = emptyList(); insightErr2 = null
                                    insightRunning2 = false
                                    insightDialogVisible = true
                                })
                            }
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            record?.let { rec ->
                if (rec.isLocked && decryptedContent == null) {
                    // 加密态：不讲排场，一行小锁 + 一行字 + 一个文字动作（2026-10-24）
                    Column(
                        Modifier.fillMaxWidth().padding(top = 48.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            IconLock, null, Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "此记录已锁定",
                            fontSize = 15.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(Modifier.height(18.dp))
                        // 密码输入：没有框，只有一条横线（横线就是输入框）
                        fun attemptUnlock() {
                            if (unlockPassword.isBlank()) { unlockError = "请输入主密码"; return }
                            scope.launch {
                                if (EncryptionManager.verifyUnlockPassword(unlockPassword)) {
                                    EncryptionManager.ensureKey(unlockPassword)
                                    repository.getDecryptedPayload(recordId).onSuccess { payload ->
                                        decryptedContent = payload.content
                                        decryptedNotes = payload.notes
                                        decryptedReply = payload.dialogueAiReply
                                        unlockPassword = ""
                                    }.onFailure { unlockError = it.message }
                                } else {
                                    unlockError = "密码错误"
                                    UiSoundPlayer.playPasswordError(context)
                                }
                            }
                        }
                        BasicTextField(
                            value = unlockPassword,
                            onValueChange = { unlockPassword = it; unlockError = null },
                            singleLine = true,
                            textStyle = androidx.compose.ui.text.TextStyle(
                                fontSize = 14.sp,
                                color = MaterialTheme.colorScheme.onSurface,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            ),
                            cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                keyboardType = androidx.compose.ui.text.input.KeyboardType.Password,
                                imeAction = androidx.compose.ui.text.input.ImeAction.Done
                            ),
                            keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { attemptUnlock() }),
                            modifier = Modifier.width(200.dp),
                            decorationBox = { inner ->
                                Column(Modifier.fillMaxWidth()) {
                                    Box(Modifier.fillMaxWidth().padding(vertical = 9.dp), contentAlignment = Alignment.Center) {
                                        if (unlockPassword.isEmpty()) {
                                            Text(
                                                "主密码",
                                                fontSize = 13.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                            )
                                        }
                                        inner()
                                    }
                                    Box(
                                        Modifier.fillMaxWidth().height(1.dp)
                                            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.28f))
                                    )
                                }
                            }
                        )
                        unlockError?.let {
                            Spacer(Modifier.height(8.dp))
                            Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                        }
                    }
                } else {
                    val content = decryptedContent ?: rec.content
                    val tcolor = recordTypeColor(rec.type)
                val emoEntry = memore.app.data.model.EmotionStats.emotionEntries(rec).maxByOrNull { it.weight }
                val emoName = emoEntry?.name
                // 页头：类型 · 时间 · 情绪（B 方案：情绪并进元信息行）
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        recordTypeLabel(rec.type),
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = tcolor
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        formatTimestamp(rec.createdAt),
                        fontSize = 10.5.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (emoName != null) {
                        Text("  ·  ", fontSize = 10.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(emoName, fontSize = 10.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        // 依据：点一下看"为什么判成这个情绪"（2026-11-08 新增 why 字段后才有）
                        emoEntry?.why?.let { why ->
                            Spacer(Modifier.width(4.dp))
                            Text(
                                "「$why」",
                                fontSize = 10.5.sp,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
                                modifier = Modifier.weight(1f, fill = false)
                            )
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                Box(
                    Modifier.fillMaxWidth().height(1.dp)
                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f))
                )
                Spacer(Modifier.height(14.dp))

                    // 正文：直接铺在纸上，无框
                    val segments = if (rec.type == RecordType.DIALOGUE) dialogueSegments(content, rec.dialogueAiReply.ifEmpty { decryptedReply }) else listOf(content)
                    segments.forEachIndexed { index, segment ->
                        if (index > 0) {
                            Spacer(Modifier.height(12.dp))
                            Box(
                                Modifier.fillMaxWidth().height(1.dp)
                                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f))
                            )
                            Spacer(Modifier.height(12.dp))
                        }
                        CopyableText(
                            text = segment,
                            style = memore.app.ui.theme.MemoreType.reading,
                            color = MaterialTheme.colorScheme.onSurface,
                            copyOptions = if (rec.type == RecordType.DIALOGUE) listOf(
                                CopyOption("复制本段", segment),
                                CopyOption("复制全文", content)
                            ) else null
                        )
                    }

                    // 元信息：总结 / 附件 / 情绪（有就显示，没有就不占位）
                    val summary = rec.analysis?.summary?.takeIf { it.isNotBlank() }
                    val emo = remember(rec.emotionalTags, rec.analysis) {
                        val entries = memore.app.data.model.EmotionStats.emotionEntries(rec)
                        var best: memore.app.data.model.EmotionTagEntry? = null
                        entries.forEach { e -> if (best == null || e.weight > best!!.weight) best = e }
                        best
                    }
                    // 洞察（2026-11-08）：进独立的记录洞察页；无总结也必须能进
                if (memore.app.utils.FeatureConsole.visible(memore.app.utils.FeatureConsole.SOURCE)) {
                    Spacer(Modifier.height(20.dp))
                    DetailRow(
                        label = "来源",
                        value = origins.firstOrNull { it.id == rec.originId }?.name ?: "未标",
                        onClick = if (onSetOrigin != null) ({ showOriginPicker = true }) else null
                    )
                }
                    Spacer(Modifier.height(20.dp))
                    DetailRow(
                        label = "洞察",
                        value = "",
                        onClick = { onOpenInsight(rec.id) }
                    )
                    if (summary != null) {
                        DetailRow(
                            label = "总结",
                            value = "${summary.length} 字",
                            onClick = { showSummarySheet = true }
                        )
                    }
                        if (rec.attachmentsCount > 0) {
                            DetailRow(
                                label = "附件",
                                value = "${rec.attachmentsCount} 个",
                                onClick = { showAttachments = true }
                            )
                        }
                    }
                }

            if (linkRecords.isNotEmpty() && memore.app.utils.FeatureConsole.visible(memore.app.utils.FeatureConsole.LINKS)) {
                Spacer(Modifier.height(26.dp))
                Text(
                    "关联",
                    fontSize = 10.5.sp,
                    letterSpacing = 1.1.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(6.dp))
                Box(
                    Modifier.fillMaxWidth().height(1.dp)
                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f))
                )
                linkRecords.forEach { (r, why) ->
                    Row(
                        Modifier.fillMaxWidth().height(androidx.compose.foundation.layout.IntrinsicSize.Min)
                            .combinedClickable(
                                onClick = { onOpenRecord(r.id) },
                                onLongClick = { rejectTarget = r }
                            )
                            .padding(vertical = 9.dp)
                    ) {
                        Box(
                            Modifier.width(2.dp).fillMaxHeight()
                                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f))
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            if (why.isNotBlank()) {
                                Text(
                                    why,
                                    fontSize = 12.sp,
                                    lineHeight = 18.sp,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Spacer(Modifier.height(2.dp))
                            }
            Spacer(Modifier.height(10.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(recordTypeLabel(r.type), fontSize = 10.sp, color = recordTypeColor(r.type))
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    formatTimestamp(r.createdAt),
                                    fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Spacer(Modifier.height(4.dp))
                            Text(
                                // 2026-11-23：关联卡不能只有标题/时间，至少给一行正文；
                                // 锁定记录仍不露正文。
                                if (r.isLocked) "已锁定 · 点开查看"
                                else r.content.replace('\n', ' ').trim().take(80),
                                fontSize = 12.sp,
                                lineHeight = 18.sp,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }

            if (similarRecords.isNotEmpty()) {
                Spacer(Modifier.height(26.dp))
                Text(
                    "相似",
                    fontSize = 10.5.sp,
                    letterSpacing = 1.1.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(6.dp))
                Box(
                    Modifier.fillMaxWidth().height(1.dp)
                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f))
                )
                similarRecords.forEach { (r, snippet) ->
            Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)))
                    Row(
                        Modifier.fillMaxWidth().height(androidx.compose.foundation.layout.IntrinsicSize.Min)
                            .clickable { onOpenRecord(r.id) }
                            .padding(vertical = 9.dp)
                    ) {
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(recordTypeLabel(r.type), fontSize = 10.sp, color = recordTypeColor(r.type))
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    formatTimestamp(r.createdAt),
                                    fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                    Spacer(Modifier.height(3.dp))
                    // 片段只作"这是哪条"的引子：小字、单行、灰色，不抢关系那一行
                    Text(
                        (snippet ?: r.content.ifBlank { r.notes }).replace(Regex("\\s+"), " ").trim(),
                        fontSize = 11.5.sp,
                        lineHeight = 17.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                        }
                    }
                }
            }

            if (versions.isNotEmpty()) {
                Spacer(Modifier.height(24.dp))
                Row(
                    Modifier.fillMaxWidth().clickable { showVersions = !showVersions },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "历史版本 ${versions.size}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.weight(1f)
                    )
                    Icon(
                        if (showVersions) Icons.Outlined.KeyboardArrowDown else Icons.Outlined.KeyboardArrowRight,
                        contentDescription = if (showVersions) "收起" else "展开",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.height(4.dp))
                HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
                if (showVersions) {
                    Spacer(Modifier.height(8.dp))
                    versions.sortedByDescending { it.createdAt }.forEach { v ->
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                            shape = MirrorShapes.row,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                            border = mirrorHairline()
                        ) {
                            Column(Modifier.padding(10.dp)) {
                                CopyableText(
                                    text = v.content,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 4,
                                    overflow = TextOverflow.Ellipsis,
                                    copyOptions = listOf(CopyOption("复制", v.content))
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(formatTimestamp(v.createdAt), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }

        }
    }

    // 总结：底部半屏（和时间线同一套手势）
    if (showOriginPicker) {
        memore.app.ui.components.OriginSelectorSheet(
            origins = origins,
            currentId = record?.originId,
            onPick = { picked -> onSetOrigin?.invoke(picked); showOriginPicker = false },
            onDismiss = { showOriginPicker = false }
        )
    }

    if (showSummarySheet) {
        val summary = record?.analysis?.summary.orEmpty()
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
        memore.app.ui.components.MirrorSheet(
            onDismissRequest = { showSummarySheet = false },
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            sheetState = sheetState
        ) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 32.dp)) {
                Text(
                    "总结",
                    fontSize = 10.5.sp,
                    letterSpacing = 1.1.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp, bottom = 12.dp)
                )
                CopyableText(
                    text = summary,
                    style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 25.sp),
                    color = MaterialTheme.colorScheme.onSurface,
                    copyOptions = listOf(CopyOption("复制总结", summary))
                )
            }
        }
    }

    if (showAttachments) {
        record?.let { rec ->
            AttachmentListViewer(
                record = rec,
                recordRepository = repository,
                onDismiss = { showAttachments = false }
            )
        }
    }
}

@Composable
private fun RelatedDetailRow(record: Record, trailing: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), verticalAlignment = Alignment.Top) {
        Box(Modifier.width(20.dp)) {
            Box(
                Modifier
                    .padding(top = 18.dp)
                    .align(Alignment.TopCenter)
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary)
            )
            if (trailing) {
                Box(
                    Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 26.dp)
                        .width(2.dp)
                        .fillMaxHeight()
                        .background(MaterialTheme.colorScheme.outlineVariant)
                )
            }
        }
        Spacer(Modifier.width(2.dp))
        Card(
            modifier = Modifier.weight(1f).clickable(onClick = onClick),
            shape = MirrorShapes.row,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            border = mirrorHairline()
        ) {
            Column(Modifier.fillMaxWidth().padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        recordTypeLabel(record.type),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = recordTypeColor(record.type)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        formatTimestamp(record.createdAt),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.weight(1f))
                    Icon(Icons.Outlined.KeyboardArrowRight, "进入", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    // 2026-11-16（v5）：关联卡片同样不能露锁定正文
                    if (record.isLocked) "已锁定 · 点开查看" else record.content.take(120),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
/** 元信息行：左标签、右值、箭头；onClick 为空时不可点（情绪这种只读）。 */
@Composable
private fun DetailRow(label: String, value: String, onClick: (() -> Unit)?) {
    Column {
        Row(
            Modifier.fillMaxWidth().height(50.dp)
                .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                label,
                fontSize = 14.5.sp,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.weight(1f))
            Text(
                value,
                fontSize = 12.5.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (onClick != null) {
                Spacer(Modifier.width(6.dp))
                Icon(
                    Icons.Outlined.KeyboardArrowRight, null, Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Box(
            Modifier.fillMaxWidth().height(1.dp)
                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f))
        )
    }
}

