package memore.app.ui.screens

import memore.app.ui.components.collectKeptAsState
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import android.widget.Toast
import memore.app.ui.components.MirrorDropdownMenu
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material.icons.outlined.TextSnippet
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import memore.app.data.model.Protocol
import memore.app.data.model.Record
import memore.app.data.model.RecordType
import memore.app.data.model.Tag
import memore.app.data.repository.RecordRepository
import memore.app.data.voice.RecordVoiceState
import memore.app.data.voice.RecordVoiceStatus
import memore.app.data.voice.VoiceSettingsRepository
import memore.app.data.voice.VoiceTtsRepository
import memore.app.ui.components.AttachmentListViewer
import memore.app.ui.components.CopyableText
import memore.app.ui.components.CopyOption
import memore.app.ui.components.EncryptionDialogs
import memore.app.ui.components.IconAnchor
import memore.app.ui.components.RecordCard
import memore.app.ui.components.IconLock
import memore.app.ui.components.MirrorShapes
import memore.app.ui.components.mirrorHairline
import memore.app.ui.components.RelatedGroupHeader
import memore.app.ui.components.SummaryViewer
import memore.app.ui.components.VoiceRecordSheet
import memore.app.ui.components.recordTypeColor
import memore.app.ui.components.recordTypeLabel
import memore.app.ui.components.rememberEncryptionHost
import memore.app.utils.EncryptionManager
import memore.app.utils.FeatureConsole
import memore.app.utils.UiSoundPlayer
import memore.app.utils.formatTimestamp
import memore.app.utils.memoreToast
import kotlinx.coroutines.launch
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

private fun groupAnchorRecords(records: List<Record>): List<Pair<String, List<Record>>> {
    val order = listOf("今天", "昨天", "本周", "更早")
    val today = LocalDate.now()
    val map = LinkedHashMap<String, MutableList<Record>>()
    order.forEach { map[it] = mutableListOf() }
    records.sortedByDescending { it.createdAt }.forEach { record ->
        val date = Instant.ofEpochMilli(record.createdAt).atZone(ZoneId.systemDefault()).toLocalDate()
        val label = when {
            date == today -> "今天"
            date == today.minusDays(1) -> "昨天"
            date.isAfter(today.minusDays(7)) -> "本周"
            else -> "更早"
        }
        map[label]!!.add(record)
    }
    return order.mapNotNull { label -> map[label]?.takeIf { it.isNotEmpty() }?.let { label to it } }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnchorScreen(repository: RecordRepository, voiceTtsRepository: VoiceTtsRepository? = null, voiceSettingsRepository: VoiceSettingsRepository? = null, onBack: () -> Unit = {}, onOpenRecord: (String) -> Unit = {}) {
    val anchoredDetailsFlow = remember { repository.getAnchoredRecordsWithAmendments() }
    val detailsList = anchoredDetailsFlow.collectKeptAsState("AnchorScreen.detailsList")
    val records = remember(detailsList) { detailsList.mapNotNull { it.record } }
    val scope = rememberCoroutineScope(); val context = LocalContext.current
    var amendRefreshKey by remember { mutableIntStateOf(0) }
    var showAttachmentsFor by remember { mutableStateOf<Record?>(null) }
    var showSummaryFor by remember { mutableStateOf<Record?>(null) }
    var showVoiceFor by remember { mutableStateOf<Record?>(null) }
    var generatingVoiceId by remember { mutableStateOf<String?>(null) }
    var voiceStates by remember { mutableStateOf(mapOf<String, RecordVoiceState>()) }
    val detailsById = remember(detailsList) { detailsList.associateBy { it.record!!.id } }
    val encryptionHost = rememberEncryptionHost(repository, File(context.filesDir, "attachments_tmp"))
    val encryptionAvailable = EncryptionManager.isConfigured()
    val voiceEnabled = FeatureConsole.visible(FeatureConsole.VOICE_ICONS)
    LaunchedEffect(records.map { it.id }, voiceTtsRepository, voiceEnabled) {
        // 2026-11-18 性能：语音入口关掉时不必扫描整个 voice_cache 目录。
        if (voiceTtsRepository == null || !voiceEnabled) { voiceStates = emptyMap(); return@LaunchedEffect }
        voiceStates = voiceTtsRepository.loadRecordVoiceBulk(records.map { it.id })
    }
    Scaffold(topBar = { TopAppBar(title = { Text("锚点区", style = memore.app.ui.theme.MemoreType.pageTitleSans, color = MaterialTheme.colorScheme.onSurface) }, navigationIcon = { androidx.compose.material3.IconButton(onClick = onBack) { androidx.compose.material3.Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回", tint = MaterialTheme.colorScheme.onSurfaceVariant) } }, colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)) }, containerColor = MaterialTheme.colorScheme.background) { padding ->
        if (records.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding))
        } else {
            val groups = remember(records) { groupAnchorRecords(records) }
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 8.dp, bottom = padding.calculateBottomPadding() + 80.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item(key = "anchor_header") {
                    val essayCount = records.count { it.type == RecordType.ESSAY }
                    val dialogueCount = records.count { it.type == RecordType.DIALOGUE }
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f),
                            shape = CircleShape,
                            modifier = Modifier.size(56.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(IconAnchor, null, Modifier.size(30.dp), tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                        Spacer(Modifier.width(14.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("随笔", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.width(8.dp))
                                Text("$essayCount", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("对话录", style = MaterialTheme.typography.labelMedium, color = Color(0xFF4DD0E1))
                                Spacer(Modifier.width(8.dp))
                                Text("$dialogueCount", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
                            }
                        }
                    }
                }
                groups.forEach { (label, groupRecords) ->
                    item(key = "group_$label") {
                        Text(
                            label,
                            fontSize = 10.5.sp,
                            letterSpacing = 1.1.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 10.dp, bottom = 2.dp)
                        )
                    }
                    items(groupRecords, key = { it.id }) { record ->
                        val rp = detailsById[record.id]?.protocols ?: emptyList()
                        val amends = detailsById[record.id]?.amendments ?: emptyList()
                        val tags = detailsById[record.id]?.tags ?: emptyList()
                        // 锚点区复用时间线的 RecordCard（同一套横格、渐隐、情绪），
                        // 只在最左边多一条短竖条表示"这条被我夹住了"
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                            Box(
                                Modifier.padding(top = 14.dp).width(2.dp).height(22.dp)
                                    .clip(MirrorShapes.pill)
                                    .background(MaterialTheme.colorScheme.primary)
                            )
                            Spacer(Modifier.width(12.dp))
                            Box(Modifier.weight(1f)) {
                                RecordCard(
                                    record = record,
                                    onOpen = { onOpenRecord(record.id) },
                                    tags = tags,
                                    protocols = rp,
                                    amendments = amends,
                                    attachmentCount = record.attachmentsCount,
                                    voiceAvailable = voiceEnabled && (voiceStates[record.id]?.status == RecordVoiceStatus.TRANSLATED || voiceStates[record.id]?.status == RecordVoiceStatus.AUDIO_READY),
                                    onOpenVoice = if (voiceEnabled && voiceTtsRepository != null) { { showVoiceFor = record } } else null,
                                    onViewAttachments = { showAttachmentsFor = record },
                                    onViewSummary = { showSummaryFor = record },
                                    onUnanchor = { scope.launch { repository.toggleAnchor(record.id) }; UiSoundPlayer.playAnchorClear(context) },
                                    tempDecryptedRecord = if (encryptionAvailable) encryptionHost.tempDecryptedMap[record.id] else null,
                                    onTempDecryptClick = if (record.isLocked && encryptionAvailable) { { encryptionHost.onTempDecryptClick(record) } } else null,
                                    onTempLockClick = if (encryptionAvailable) { { encryptionHost.onTempLockClick(record) } } else null
                                )
                            }
                        }
                    }
                }
            }
        }
    }
    EncryptionDialogs(encryptionHost, repository, context, scope)
    showAttachmentsFor?.let { AttachmentListViewer(record = it, recordRepository = repository, amendRefreshKey = amendRefreshKey, resolvedAttachments = encryptionHost.tempAttachmentPaths[it.id], onUnlockTemp = { encryptionHost.onUnlockTemp(it) }, onDismiss = { showAttachmentsFor = null }) }
    showSummaryFor?.let { SummaryViewer(record = it, onDismiss = { showSummaryFor = null }, summaryOverride = encryptionHost.tempDecryptedMap[it.id]?.analysisSummary) }
    showVoiceFor?.let { rec ->
        if (voiceTtsRepository != null) {
            VoiceRecordSheet(
                record = rec,
                voiceTtsRepository = voiceTtsRepository,
                onDismiss = { showVoiceFor = null },
                onStateLoaded = { id, st -> voiceStates = voiceStates + (id to st) },
                lockedNotDecrypted = rec.isLocked && encryptionHost.tempDecryptedMap[rec.id] == null,
                onUnlockTemp = { encryptionHost.onTempDecryptClick(rec) },
                onStartTts = { jp ->
                    showVoiceFor = null
                    generatingVoiceId = rec.id
                    scope.launch {
                        runCatching { voiceTtsRepository.synthesizeForRecord(rec.id, jp) }
                            .onSuccess {
                                voiceStates = voiceStates + (rec.id to voiceTtsRepository.loadRecordVoice(rec.id))
                                UiSoundPlayer.playSynthComplete(context)
                            }
                            .onFailure { context.memoreToast("语音生成失败：${it.message}", Toast.LENGTH_SHORT) }
                        generatingVoiceId = null
                    }
                }
            )
        }
    }
}

