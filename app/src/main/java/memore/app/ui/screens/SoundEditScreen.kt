package memore.app.ui.screens

import memore.app.ui.theme.MemoreType
import android.widget.Toast
import memore.app.ui.components.MirrorAlertDialog
import memore.app.utils.memoreToast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.material3.LocalContentColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.sp
import memore.app.utils.SoundConfigStore
import memore.app.utils.SoundEvent
import memore.app.utils.UiSoundPlayer
import kotlinx.coroutines.launch
import memore.app.ui.components.MirrorShapes
import memore.app.ui.components.mirrorHairline

/**
 * 提示音编辑（音效中心）。
 *
 * 2026-10-23 重设计：
 *  - 列表改成**分组音板**（三列方块）：点方块编辑，点方块里的 ♪ 直接试听；
 *  - 编辑从弹窗改成**整页**：音源行内 ♪ 试听 / ✕ 删除，添加是分段按钮，危险动作单独成组；
 *  - **去掉「清空(不响)」**：提示音就是要能响，留「重置为默认」即可。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SoundEditScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { SoundConfigStore(context) }
    var refreshKey by remember { mutableIntStateOf(0) }
    var editing by remember { mutableStateOf<SoundEvent?>(null) }
    var showPicker by remember { mutableStateOf(false) }
    var editingAssets by remember { mutableStateOf<List<String>>(emptyList()) }

    /** 立即落盘（不再有「确定」）：每次增删都写一次，返回即生效。 */
    fun persist(event: SoundEvent, assets: List<String>) {
        store.saveOverride(event.name, assets)
        store.cleanupImportOrphans()
        refreshKey++
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val event = editing
            if (event != null) {
                scope.launch {
                    val name = contentName(context, uri)
                    val path = store.importAudio(uri, name)
                    if (path != null) {
                        editingAssets = editingAssets + path
                        persist(event, editingAssets)
                        UiSoundPlayer.playSoundEditImport(context)
                        context.memoreToast("已导入音频", Toast.LENGTH_SHORT)
                    } else {
                        context.memoreToast("导入失败", Toast.LENGTH_SHORT)
                    }
                }
            }
        }
    }



    LaunchedEffect(Unit) { UiSoundPlayer.init(context) }

    // ===== 编辑页（整页，不再是弹窗）=====
    val current = editing
    if (current != null) {
        BackHandler(enabled = true) { editing = null }
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(current.label, style = MemoreType.pageTitleSans) },
                    navigationIcon = {
                        IconButton(onClick = { editing = null }) {
                            Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
                )
            },
            containerColor = MaterialTheme.colorScheme.background
        ) { padding ->
            Column(
                Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                SoundSectionLabel("音源 ${editingAssets.size}")
                if (editingAssets.isEmpty()) {
                    Text(
                        "还没有音源",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 10.dp)
                    )
                } else {
                    editingAssets.forEach { asset ->
                        Row(
                            Modifier.fillMaxWidth().height(54.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                assetName(asset),
                                fontSize = 12.5.sp,
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f),
                                maxLines = 1
                            )
                            // ♪ 试听：淡紫圆底图标键
                            Box(
                                Modifier.size(32.dp).clip(MirrorShapes.pill)
                                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
                                    .clickable {
                                        UiSoundPlayer.init(context)
                                        UiSoundPlayer.play(context, asset)
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.AutoMirrored.Outlined.VolumeUp, "试听",
                                    Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                            Spacer(Modifier.width(4.dp))
                            // ✕ 移除：灰，面积小
                            Box(
                                Modifier.size(32.dp).clip(MirrorShapes.pill)
                                    .clickable {
                                        editingAssets = editingAssets - asset
                                        persist(current, editingAssets)
                                        UiSoundPlayer.playSoundEditDelete(context)
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Outlined.DeleteOutline, "移除",
                                    Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                )
                            }
                        }
                        SoundRule()
                    }
                }

                SoundSectionLabel("添加")
                Row(
                    Modifier.fillMaxWidth().height(44.dp).clip(MirrorShapes.card)
                        .background(MaterialTheme.colorScheme.surface),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        Modifier.weight(1f).fillMaxHeight().clickable { showPicker = true },
                        contentAlignment = Alignment.Center
                    ) {
                        Text("从内置挑选", fontSize = 13.5.sp, color = MaterialTheme.colorScheme.onSurface)
                    }
                    Box(Modifier.width(1.dp).height(22.dp).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)))
                    Box(
                        Modifier.weight(1f).fillMaxHeight().clickable { importLauncher.launch(arrayOf("audio/*")) },
                        contentAlignment = Alignment.Center
                    ) {
                        Text("导入音频", fontSize = 13.5.sp, color = MaterialTheme.colorScheme.onSurface)
                    }
                }

                SoundSectionLabel("危险")
                Box(
                    Modifier.fillMaxWidth().height(44.dp).clip(MirrorShapes.card)
                        .background(MaterialTheme.colorScheme.error.copy(alpha = 0.08f))
                        .clickable {
                            editingAssets = store.availableDefaultAssets(current)
                            store.reset(current)
                            store.cleanupImportOrphans()
                            refreshKey++
                            UiSoundPlayer.playSoundEditReset(context)
                            context.memoreToast("已恢复默认", Toast.LENGTH_SHORT)
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text("重置为默认", fontSize = 13.5.sp, color = MaterialTheme.colorScheme.error)
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    } else {
        // ===== 分组行列表（2026-10-23 按 V1 改回；已自定义在前、数量在其右）=====
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("提示音", style = MemoreType.pageTitleSans) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
                )
            },
            containerColor = MaterialTheme.colorScheme.background
        ) { padding ->
            val groups = remember(refreshKey) {
                SoundEvent.entries.groupBy { it.label.substringBefore("·").trim().ifBlank { "其他" } }
            }
            LazyColumn(
                Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)
            ) {
                groups.forEach { (groupName, events) ->
                    item(key = "h_$groupName") { SoundSectionLabel(groupName) }
                    items(events, key = { it.name }) { event ->
                        val resolved = store.resolveAssets(event)
                        val overridden = store.isOverridden(event.name)
                        Row(
                            Modifier.fillMaxWidth().height(46.dp)
                                .clickable {
                                    editing = event
                                    editingAssets = store.resolveAssets(event)
                                },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                event.label.substringAfter("·").trim().ifBlank { event.label },
                                fontSize = 14.sp,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1
                            )
                            Spacer(Modifier.weight(1f))
                            if (overridden) {
                                Text(
                                    "已自定义",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Spacer(Modifier.width(7.dp))
                            }
                            Text(
                                "${resolved.size}",
                                fontSize = 11.5.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.width(6.dp))
                            Icon(
                                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                null, Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        SoundRule()
                    }
                }
                item { Spacer(Modifier.height(20.dp)) }
            }
        }
    }
    // 内置音源挑选（保持弹窗：它只是一次性选择，不改数据）
    if (showPicker) {
        val event = editing
        if (event != null) {
            val builtin = store.availableDefaultAssets(event)
            MirrorAlertDialog(
                onDismissRequest = { showPicker = false },
                title = { Text("从内置音源添加") },
                text = {
                    LazyColumn(Modifier.heightIn(max = 300.dp)) {
                        items(builtin, key = { it }) { asset ->
                            Row(
                                Modifier.fillMaxWidth().clickable {
                                    UiSoundPlayer.init(context); UiSoundPlayer.play(context, asset)
                                }.padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    assetName(asset),
                                    fontSize = 12.5.sp,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.weight(1f),
                                    maxLines = 1
                                )
                                Text(
                                    "添加",
                                    fontSize = 12.5.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.clickable {
                                        editingAssets = (editingAssets + asset).distinct()
                                        persist(event, editingAssets)
                                    }
                                )
                            }
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { showPicker = false }) { Text("完成") } }
            )
        }
    }
}

/** 音板方块：点方块编辑，点方块里的 ♪ 试听。 */
@Composable
private fun SoundTile(
    title: String,
    count: Int,
    overridden: Boolean,
    onEdit: () -> Unit,
    onPreview: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier
            .height(66.dp)
            .clip(MirrorShapes.card)
            .background(MaterialTheme.colorScheme.surface)
            .clickable { onEdit() }
            .padding(start = 10.dp, end = 6.dp, top = 8.dp, bottom = 6.dp)
    ) {
        Text(
            title,
            fontSize = 12.5.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            lineHeight = 15.sp
        )
        Spacer(Modifier.weight(1f))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(20.dp).clip(MirrorShapes.pill)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
                    .clickable { onPreview() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.AutoMirrored.Outlined.VolumeUp, "试听",
                    Modifier.size(12.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            Spacer(Modifier.width(5.dp))
            Text("$count", fontSize = 10.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.weight(1f))
            if (overridden) {
                Text("已改", fontSize = 10.sp, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
private fun SoundSectionLabel(text: String) {
    Text(
        text,
        fontSize = 10.5.sp,
        letterSpacing = 1.1.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 16.dp, bottom = 6.dp)
    )
}

@Composable
private fun SoundRule() {
    Box(
        Modifier.fillMaxWidth().height(1.dp)
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f))
    )
}

private fun assetName(path: String): String {
    val name = if (path.startsWith("/") || path.startsWith("file://")) {
        (if (path.startsWith("file://")) android.net.Uri.parse(path).path else path)?.substringAfterLast('/') ?: path
    } else path.removePrefix("sounds/")
    return name
}

private fun contentName(context: android.content.Context, uri: android.net.Uri): String? {
    return try {
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) c.getString(idx) else null
            } else null
        }
    } catch (e: Exception) { null }
}
