package memore.app.ui.screens

import memore.app.ui.theme.MemoreType
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import memore.app.MemoreApp
import memore.app.ui.components.MirrorAlertDialog
import memore.app.utils.FeatureConsole
import memore.app.utils.UiSoundPlayer
import kotlinx.coroutines.launch

/**
 * FIX 工具（2026-10-23 从弹窗改成独立页）。
 *
 * 原来是一个居中弹窗里塞四张卡片 + 三条灰色说明小字，功能控制台还寄生在一个全屏 Dialog 里。
 * 现在：三组、四行、行尾只放**数据**（不放状态词），功能控制台是本页的一个分支而不是弹窗。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FixToolsScreen(
    onOpenRedaction: () -> Unit,
    onOpenSystemInstruction: () -> Unit,
    onClearCaches: () -> Unit = {},
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // 功能控制台：作为本页的一个分支渲染（它是页面，不是弹窗）
    var showConsole by remember { mutableStateOf(false) }
    if (showConsole) {
        FeatureConsoleScreen(onBack = { showConsole = false })
        return
    }

    // 数据清算（2026-11-22）：空标签组 + 清空关联 + 清空概念，都从这里进。
    var cleanupStep by remember { mutableIntStateOf(0) } // 0=未开始 1=选择 2=确认关联 3=确认概念 4=结果
    var cleanupResult by remember { mutableStateOf("") }
    var relationCount by remember { mutableIntStateOf(0) }
    var anchorCount by remember { mutableIntStateOf(0) }

    val ruleCount = remember { memore.app.core.ai.SystemInstructionStore.load(context).size }
    val ruleEnabled = remember { memore.app.core.ai.SystemInstructionStore.exportEnabled(context) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("FIX 工具", style = MemoreType.pageTitleSans) },
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
        Column(
            Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp)
                .verticalScroll(rememberScrollState())
        ) {
            FixGroupLabel("可见性")
            FixRow("功能控制台", onBack = null) { showConsole = true }
            FixRule()

            FixGroupLabel("数据", first = false)
            FixRow(
                "数据清算",
                value = if (cleanupStep == 4) "已清理" else ""
            ) {
                cleanupStep = 1
                val container = (context.applicationContext as? MemoreApp)?.container
                scope.launch {
                    relationCount = container?.recordRepository?.relationRowCount() ?: 0
                    anchorCount = container?.anchorRepository?.groups()?.size ?: 0
                }
            }
            FixRule()
            FixRow("脱敏导出") { onOpenRedaction() }
            FixRule()

            FixGroupLabel("AI", first = false)
            FixRow(
                "系统提示",
                value = when {
                    !ruleEnabled -> "已暂停"
                    ruleCount == 0 -> ""
                    else -> "$ruleCount 条"
                },
                valueIsWarning = !ruleEnabled
            ) { onOpenSystemInstruction() }
            FixRule()

            Spacer(Modifier.height(28.dp))
            Text(
                "Mirror " + (runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: ""),
                fontSize = 10.5.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                modifier = Modifier.fillMaxWidth(),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
            Spacer(Modifier.height(24.dp))
        }
    }

    fun runCleanup(kind: String) {
        val container = (context.applicationContext as? MemoreApp)?.container
        scope.launch {
            cleanupResult = when (kind) {
                "tags" -> "清理 ${container?.tagGroupRepository?.deleteEmpty() ?: 0} 个空标签组"
                "relations" -> {
                    val n = container?.recordRepository?.clearAllRelations() ?: 0
                    onClearCaches()
                    "清空 $n 条关联"
                }
                else -> {
                    val n = container?.anchorRepository?.clearAllAnchors() ?: 0
                    onClearCaches()
                    "清空 $n 个概念"
                }
            }
            UiSoundPlayer.playCleanupDone(context)
            cleanupStep = 4
        }
    }

    // 数据清算：先选项目，关联/概念再确认一次。
    if (cleanupStep == 1) {
        MirrorAlertDialog(
            onDismissRequest = { cleanupStep = 0 },
            title = { Text("数据清算") },
            text = {
                Column {
                    Text(
                        "清理空标签组，或清空全部关联 / 概念。",
                        fontSize = 12.5.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(6.dp))
                    CleanupActionRow("清理空标签组") { cleanupStep = 0; runCleanup("tags") }
                    CleanupActionRow("清空关联（$relationCount 条）") { cleanupStep = 2 }
                    CleanupActionRow("清空概念（$anchorCount 个）", divider = false) { cleanupStep = 3 }
                }
            },
            confirmButton = { TextButton(onClick = { cleanupStep = 0 }) { Text("取消") } }
        )
    }

    if (cleanupStep == 2 || cleanupStep == 3) {
        val isLinks = cleanupStep == 2
        MirrorAlertDialog(
            onDismissRequest = { cleanupStep = 0 },
            title = { Text(if (isLinks) "清空 $relationCount 条关联？" else "清空 $anchorCount 个概念？") },
            text = { Text("清空后无法恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    cleanupStep = 0
                    runCleanup(if (isLinks) "relations" else "anchors")
                }) { Text("清空", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { cleanupStep = 0 }) { Text("取消") } }
        )
    }

    if (cleanupStep == 4) {
        MirrorAlertDialog(
            onDismissRequest = { cleanupStep = 0 },
            title = { Text("清算完成") },
            text = { Text(cleanupResult) },
            confirmButton = { TextButton(onClick = { cleanupStep = 0 }) { Text("好") } }
        )
    }
}

@Composable
private fun CleanupActionRow(label: String, divider: Boolean = true, onClick: () -> Unit) {
    Column {
        Row(
            Modifier.fillMaxWidth().height(44.dp).clickable { onClick() },
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface)
        }
        if (divider) FixRule()
    }
}

@Composable
private fun FixGroupLabel(text: String, first: Boolean = true) {
    Text(
        text,
        fontSize = 10.5.sp,
        letterSpacing = 1.1.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = if (first) 4.dp else 20.dp, bottom = 2.dp)
    )
}

/** 行尾只放数据；状态词只在"异常"时出现（如 已暂停）。 */
@Composable
private fun FixRow(
    label: String,
    value: String = "",
    valueIsWarning: Boolean = false,
    onBack: Unit? = null,
    onClick: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth().height(52.dp).clickable { onClick() },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.weight(1f))
        if (value.isNotBlank()) {
            Text(
                value,
                fontSize = 12.5.sp,
                color = if (valueIsWarning) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(6.dp))
        }
        Icon(
            Icons.Outlined.KeyboardArrowRight, null, Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun FixRule() {
    Box(
        Modifier.fillMaxWidth().height(1.dp)
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f))
    )
}
