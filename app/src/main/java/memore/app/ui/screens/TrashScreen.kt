package memore.app.ui.screens

import memore.app.ui.components.collectKeptAsState
import androidx.compose.foundation.background
import androidx.compose.ui.unit.sp
import memore.app.ui.components.MirrorStackDialog
import android.widget.Toast
import memore.app.ui.components.MirrorDropdownMenu
import memore.app.ui.components.MirrorAlertDialog
import memore.app.utils.memoreToast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import memore.app.data.model.Tick
import memore.app.data.repository.ProtocolRepository
import memore.app.data.repository.RecordRepository
import memore.app.data.repository.TagRepository
import memore.app.data.repository.TickRepository
import memore.app.data.voice.VoiceTtsRepository
import memore.app.ui.components.CopyableText
import memore.app.ui.theme.*
import memore.app.utils.UiSoundPlayer
import memore.app.utils.formatTimestamp
import kotlinx.coroutines.launch
import memore.app.ui.components.MirrorShapes
import memore.app.ui.components.mirrorHairline
import memore.app.ui.components.MirrorMenuAction
import memore.app.ui.components.MirrorMenu

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrashScreen(repository: RecordRepository, tagRepository: TagRepository? = null, protocolRepository: ProtocolRepository? = null, voiceTtsRepository: VoiceTtsRepository? = null, tickRepository: TickRepository? = null, onBack: () -> Unit = {}) {
    val recordsFlow = remember { repository.getDeletedRecords() }
    val records = recordsFlow.collectKeptAsState("TrashScreen.records")
    val scope = rememberCoroutineScope(); val context = LocalContext.current
    var showClearConfirm by remember { mutableStateOf(false) }
    var deleteTargetId by remember { mutableStateOf<String?>(null) }
    var deletedTicks by remember { mutableStateOf<List<Tick>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }

    suspend fun reloadTicks() {
        deletedTicks = tickRepository?.getDeleted() ?: emptyList()
    }

    LaunchedEffect(Unit) { reloadTicks(); loaded = true }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("回收站", style = memore.app.ui.theme.MemoreType.pageTitleSans, color = MaterialTheme.colorScheme.onSurface) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回") } },
                actions = {
                    if (records.isNotEmpty() || deletedTicks.isNotEmpty()) {
                        Text("清空全部", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.clickable { showClearConfirm = true }.padding(horizontal = 12.dp, vertical = 4.dp))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        if (!loaded) { memore.app.ui.components.MirrorLoading(Modifier.padding(padding)) }
        else if (records.isEmpty() && deletedTicks.isEmpty()) { Box(Modifier.fillMaxSize().padding(padding)) }
        else { LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 8.dp, bottom = padding.calculateBottomPadding() + 80.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (deletedTicks.isNotEmpty()) {
                item(key = "__tick_header") {
                    Text("已删除刻度", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                items(deletedTicks, key = { "tick_${it.id}" }) { tick ->
                    var showTickMenu by remember { mutableStateOf(false) }
                    Column(Modifier.fillMaxWidth()) {
                        Row(Modifier.fillMaxWidth().height(50.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(tick.name, fontSize = 13.5.sp, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f), maxLines = 1)
                            Text(
                                (tick.endMs?.let { formatTimestamp(it) } ?: "至今"),
                                fontSize = 10.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                                Box { IconButton(onClick = { showTickMenu = true }) { Icon(Icons.Outlined.MoreVert, "更多", Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                                    MirrorMenu(
                                        expanded = showTickMenu,
                                        onDismissRequest = { showTickMenu = false },
                                        actions = listOf(
                                            MirrorMenuAction("恢复") { showTickMenu = false; scope.launch { tickRepository?.restore(tick.id); reloadTicks() }; UiSoundPlayer.playRestoreDone(context) },
                                            MirrorMenuAction("永久删除", danger = true) { showTickMenu = false; scope.launch { tickRepository?.permanentlyDelete(tick.id); reloadTicks() }; UiSoundPlayer.playDelete(context) }
                                        )
                                    )
                                }
                        }
                        Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)))
                    }
                }
            }
            // 「已删除记录」标题原先被嵌在上面的 if (deletedTicks.isNotEmpty()) 块里，
            // 导致只有同时存在已删除刻度时才显示；这里提到外面，两个分组各自独立。
            if (records.isNotEmpty()) {
                item(key = "__rec_header") { Text("已删除记录", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            items(records, key = { it.id }) { record ->
            var showMenu by remember { mutableStateOf(false) }
            Column(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth().height(50.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        record.content.take(24).ifBlank { if (record.isLocked) "已加密" else "无内容" },
                        fontSize = 13.5.sp, color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Text(formatTimestamp(record.createdAt), fontSize = 10.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Box { IconButton(onClick = { showMenu = true }) { Icon(Icons.Outlined.MoreVert, "\u66F4\u591A", Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                            MirrorMenu(
                                expanded = showMenu,
                                onDismissRequest = { showMenu = false },
                                actions = listOf(
                                    MirrorMenuAction("\u6062\u590D") { showMenu = false; scope.launch { repository.restoreRecord(record.id); tagRepository?.refreshAllCounts(); protocolRepository?.refreshAllCounts() }; UiSoundPlayer.playRestoreDone(context) },
                                    MirrorMenuAction("\u6C38\u4E45\u5220\u9664", danger = true) { showMenu = false; deleteTargetId = record.id }
                                )
                            )
                        }
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)))
            }
        } } }
    }

    if (showClearConfirm) {
        MirrorStackDialog(
            title = "清空回收站",
            message = "永久删除 ${records.size} 条记录、${deletedTicks.size} 个刻度，无法恢复。",
            confirmText = "清空",
            destructive = true,
            onDismiss = { showClearConfirm = false },
            onConfirm = {
                scope.launch {
                    records.forEach { voiceTtsRepository?.deleteRecordVoice(it.id) }
                    repository.clearTrash()
                    tickRepository?.clearAllDeleted()
                    reloadTicks()
                    tagRepository?.refreshAllCounts()
                    protocolRepository?.refreshAllCounts()
                }
                showClearConfirm = false
                UiSoundPlayer.playClearTrash(context)
            }
        )
    }

    if (deleteTargetId != null) {
        MirrorStackDialog(
            title = "永久删除",
            message = "此操作将永久删除该记录，无法恢复。",
            confirmText = "删除",
            destructive = true,
            onDismiss = { deleteTargetId = null },
            onConfirm = {
                val id = deleteTargetId
                deleteTargetId = null
                scope.launch {
                    id?.let { voiceTtsRepository?.deleteRecordVoice(it); repository.permanentlyDeleteRecord(it) }
                    tagRepository?.refreshAllCounts()
                    protocolRepository?.refreshAllCounts()
                }
                UiSoundPlayer.playDelete(context)
            }
        )
    }
}
