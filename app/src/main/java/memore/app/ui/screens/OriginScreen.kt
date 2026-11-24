package memore.app.ui.screens

import memore.app.ui.components.MirrorShapes

import androidx.compose.material.icons.outlined.ChevronRight
import memore.app.ui.components.MemoreLineInput
import memore.app.ui.theme.MemoreType
import memore.app.ui.components.collectKeptAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import memore.app.data.model.Origin
import memore.app.data.repository.OriginRepository
import memore.app.ui.components.MemorePage
import memore.app.ui.components.MirrorStackDialog
import memore.app.ui.components.MirrorMenuAction
import memore.app.ui.components.MirrorMenu
import kotlinx.coroutines.launch

/**
 * 来源（2026-11-11，第二次改版）。
 *
 * 结构：**紧凑列表 + 独立详情页**。
 *  - 列表每行只留：名称 · 条数 · 三个权重（聚/联/统）一行小字 —— 一屏能看全
 *  - 所有调整挪进详情页：三项权重各自一条滑杆 + ±0.05 步进 + 一句"关掉会发生什么"
 *
 * 为什么不是三个开关：用户要的是"同一来源在聚类/关联/统计里可以有不同权重"，
 * 0 就等于不参与。布尔开关表达不了 0.3 这种"参与但打折"。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OriginScreen(
    repository: OriginRepository,
    onBack: () -> Unit,
    /** 仅 debug：直接落到某个来源的详情页（开发期验证用）。 */
    debugDetailId: String? = null,
    /** 仅 debug：进详情时直接把"删除确认"弹出来。 */
    debugConfirmDelete: Boolean = false,
    /** 仅 debug：把未标记录全部归回「原创」（修复误删来源用）。 */
    debugRestoreOriginal: Boolean = false,
    /** 查看某来源下的记录（跳独立页面，2026-11-13）。 */
    onOpenRecords: (String, String) -> Unit = { _, _ -> }
) {
    val origins = repository.observeAll().collectKeptAsState("OriginScreen.origins")
    var counts by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }
    var unassigned by remember { mutableStateOf(0) }
    var showNew by remember { mutableStateOf(false) }
    var classifyPreview by remember { mutableStateOf<OriginRepository.ClassificationResult?>(null) }
    var detailId by remember { mutableStateOf<String?>(debugDetailId) }
    val scope = rememberCoroutineScope()

    suspend fun refreshCounts() {
        counts = origins.associate { it.id to repository.countRecords(it.id) }
        unassigned = repository.countUnassigned()
    }
    LaunchedEffect(Unit) {
        repository.ensureBuiltins()
        // 仅 debug：误删来源后一次性把未标回填（--ez restoreOrigin true）
        if (debugRestoreOriginal) repository.assignAllUnassigned(Origin.ID_ORIGINAL)
    }
    LaunchedEffect(origins) { refreshCounts() }

    val detail = origins.firstOrNull { it.id == detailId }

    if (detail != null) {
        OriginDetail(
            origin = detail,
            count = counts[detail.id] ?: 0,
            repository = repository,
            debugConfirmDelete = debugConfirmDelete,
            onChange = { updated -> scope.launch { repository.update(updated) } },
            onOpenRecords = onOpenRecords,
            onBack = { detailId = null },
            onDelete = {
                scope.launch {
                    repository.delete(detail.id)
                    detailId = null
                }
            }
        )
    } else {
        MemorePage(
            title = "来源",
            onBack = onBack,
            actions = {
                var menu by remember { mutableStateOf(false) }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "更多") }
                    MirrorMenu(
                        expanded = menu,
                        onDismissRequest = { menu = false },
                        actions = listOf(
                            MirrorMenuAction("新建来源") { menu = false; showNew = true },
                            MirrorMenuAction("自动识别") { menu = false; scope.launch { classifyPreview = repository.classifyUnassigned() } }
                        )
                    )
                }
            }
        ) { padding ->
            LazyColumn(Modifier.fillMaxSize().padding(padding)) {
                item {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "共 ${origins.size} 个 · 未标 $unassigned 条",
                            fontSize = 10.5.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                items(origins, key = { it.id }) { o ->
                    OriginRow(
                        origin = o,
                        count = counts[o.id] ?: 0,
                        onClick = { detailId = o.id }
                    )
                }
                item { Spacer(Modifier.height(40.dp)) }
            }
        }
    }

    // 新建
    if (showNew) {
        var name by remember { mutableStateOf("") }
        MirrorStackDialog(
            title = "新建来源",
            confirmText = "创建",
            onConfirm = {
                val n = name.trim()
                if (n.isNotEmpty()) {
                    scope.launch {
                        repository.upsert(
                            Origin(
                                id = "origin_" + System.currentTimeMillis().toString(36),
                                name = n,
                                wCluster = 1f, wLink = 1f, wStats = 1f,
                                sortOrder = (origins.maxOfOrNull { it.sortOrder } ?: 0) + 1
                            )
                        )
                        showNew = false
                    }
                }
            },
            onDismiss = { showNew = false }
        ) {
            MemoreLineInput(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }

    // 自动识别预览
    classifyPreview?.let { result ->
        MirrorStackDialog(
            title = "自动识别",
            confirmText = "全部接受",
            onConfirm = {
                scope.launch {
                    repository.applyClassification(result)
                    classifyPreview = null
                    refreshCounts()
                }
            },
            onDismiss = { classifyPreview = null }
        ) {
            Column {
                // 2026-11-14：这句解释删掉（法条 4.4b）——下面的列表本身就是答案
                result.suggestions.forEach { s ->
                    val name = origins.firstOrNull { it.id == s.originId }?.name ?: s.originId
                    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                        Text(name, fontSize = 13.sp, modifier = Modifier.weight(1f))
                        Text("${s.size} 条", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

// ===== 列表行：名称 · 条数 / 四个条目（等分成格，竖线分隔） =====

/**
 * 摘要里的一格：标签左对齐、数值紧跟其后（间隔 4dp）。
 *
 * 上一版把数值推到格子最右缘——四格等分下，两字标签与右缘之间空出一大段，
 * 看起来像四个断开的仪表盘。改成"左对齐 + 紧跟"：四列标签都是两字，
 * 每列数值的起点相同，跨行仍然对齐，但不再有空洞。
 */
@Composable
private fun androidx.compose.foundation.layout.RowScope.SummaryCell(label: String, value: String) {
    Row(
        Modifier.weight(1f),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontSize = 10.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(4.dp))
        Text(
            value,
            fontSize = 10.5.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.78f)
        )
    }
}

/** 格间小竖线：10dp 高、0.12 alpha，只做分隔，不抢视线。 */
@Composable
private fun SummaryDivider() {
    Box(
        Modifier.padding(horizontal = 8.dp).width(1.dp).height(10.dp)
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f))
    )
}

@Composable
private fun OriginRow(origin: Origin, count: Int, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                origin.name,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = if (origin.summaryWeight == 0f)
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
                else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            Text(
                "$count 条",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(6.dp))
        // 2026-11-17：四个条目等分成四格，格间用 1dp 小竖线分隔。
        // 原来是一串「·」分隔的左流文字——每组数值宽度不同，行与行之间对不齐；
        // 四格等宽 + 标签左、数值右，四列的数值在跨行时自然对齐。
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            SummaryCell("叙事", fmt(origin.wCluster))
            SummaryDivider()
            SummaryCell("结构", fmt(origin.wLink))
            SummaryDivider()
            SummaryCell("准入", fmt(origin.linkThreshold))
            SummaryDivider()
            SummaryCell("统计", if (origin.wStats > 0f) "开" else "关")
        }
    }
    Box(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp).height(1.dp)
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f))
    )
}

// ===== 详情页：三项权重各一条滑杆 =====

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OriginDetail(
    origin: Origin,
    count: Int,
    repository: OriginRepository,
    debugConfirmDelete: Boolean = false,
    onChange: (Origin) -> Unit,
    onBack: () -> Unit,
    onDelete: () -> Unit,
    onOpenRecords: (String, String) -> Unit
) {
    var confirmDelete by remember { mutableStateOf(debugConfirmDelete) }
    var renaming by remember { mutableStateOf(false) }
    var name by remember(origin.id) { mutableStateOf(origin.name) }

    MemorePage(
        title = origin.name,
        onBack = onBack,
        actions = {
            var menu by remember { mutableStateOf(false) }
            Box {
                IconButton(onClick = { menu = true }) {
                    Icon(Icons.Filled.MoreVert, "更多")
                }
                MirrorMenu(
                    expanded = menu,
                    onDismissRequest = { menu = false },
                    actions = listOf(
                        MirrorMenuAction("改名") { menu = false; renaming = true },
                        MirrorMenuAction("重置权重") { menu = false; onChange(origin.copy(wCluster = 1f, wLink = 1f, wStats = 1f, linkThreshold = 0.70f)) },
                        MirrorMenuAction("删除来源", danger = true) { menu = false; confirmDelete = true }
                    )
                )
            }
        }
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
        ) {
            Row(
                Modifier.fillMaxWidth()
                    .clickable { onOpenRecords(origin.id, origin.name) }
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "查看这 $count 条记录",
                    style = MemoreType.meta,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    Icons.Outlined.ChevronRight, null, Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                )
            }
            Spacer(Modifier.height(16.dp))

            // 2026-11-17：来源系统改成「阈值 / 权重 / 开关」三类条目（不再固定三个权重）。
            WeightRow(
                label = "叙事分量",
                hint = "话题、情绪、洞察取样按它加权；0 = 不算进我的叙事",
                value = origin.wCluster,
                onChange = { onChange(origin.copy(wCluster = it)) }
            )
            Spacer(Modifier.height(16.dp))
            WeightRow(
                label = "结构分量",
                hint = "关联候选的排序与边的权重；0 = 不参与关联",
                value = origin.wLink,
                onChange = { onChange(origin.copy(wLink = it)) }
            )
            Spacer(Modifier.height(16.dp))
            WeightRow(
                label = "关联准入",
                hint = "相似度低于它不进候选；1 = 永不进",
                value = origin.linkThreshold,
                onChange = { onChange(origin.copy(linkThreshold = it)) }
            )
            Spacer(Modifier.height(16.dp))
            Row(
                Modifier.fillMaxWidth()
                    .clickable {
                        onChange(origin.copy(wStats = if (origin.wStats > 0f) 0f else 1f))
                    }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("计入统计", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface)
                }
                Switch(
                    checked = origin.wStats > 0f,
                    onCheckedChange = { on -> onChange(origin.copy(wStats = if (on) 1f else 0f)) },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                        checkedTrackColor = MaterialTheme.colorScheme.primary
                    )
                )
            }

            Spacer(Modifier.height(40.dp))
        }
    }

    if (confirmDelete) {
        MirrorStackDialog(
            title = "删除来源",
            message = "「${origin.name}」的 $count 条记录会变成「未标」，记录本身不会丢。",
            confirmText = "删除",
            destructive = true,
            onConfirm = {
                confirmDelete = false
                onDelete()
            },
            onDismiss = { confirmDelete = false }
        ) {}
    }

    if (renaming) {
        MirrorStackDialog(
            title = "改名",
            confirmText = "保存",
            onConfirm = {
                val n = name.trim()
                if (n.isNotEmpty()) {
                    onChange(origin.copy(name = n))
                    renaming = false
                }
            },
            onDismiss = { renaming = false }
        ) {
            MemoreLineInput(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun WeightRow(label: String, hint: String, value: Float, onChange: (Float) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
            Text(
                fmt(value), fontSize = 12.5.sp,
                color = if (value == 0f) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
                fontWeight = FontWeight.Medium
            )
        }
        Spacer(Modifier.height(10.dp))
        BoxWithConstraints(
            Modifier.fillMaxWidth().height(22.dp)
                .pointerInput(Unit) {
                    detectTapGestures { off -> onChange(snap(off.x / size.width.toFloat())) }
                }
                .pointerInput(Unit) {
                    detectHorizontalDragGestures { change, _ ->
                        onChange(snap(change.position.x / size.width.toFloat()))
                    }
                },
            contentAlignment = Alignment.CenterStart
        ) {
            val w = maxWidth
            // 轨道：细、淡
            Box(Modifier.fillMaxWidth().height(1.5.dp).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)))
            Box(
                Modifier.fillMaxWidth(value.coerceIn(0f, 1f)).height(1.5.dp)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.45f))
            )
            // 圆点：小
            Box(
                Modifier
                    .offset(x = (w * value.coerceIn(0f, 1f)) - 3.5.dp)
                    .size(7.dp)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.9f), androidx.compose.foundation.shape.CircleShape)
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(hint, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** 0.05 步进，避免出现 0.33333 这种值。 */
private fun snap(x: Float): Float = ((x.coerceIn(0f, 1f) * 20).toInt() / 20f)

@Composable
private fun StepBtn(text: String, onClick: () -> Unit) {
    Box(
        Modifier.size(28.dp).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f), MirrorShapesCard)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) { Text(text, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface) }
}

private val MirrorShapesCard = MirrorShapes.row

private fun fmt(v: Float): String {
    // 1 → "1"，0.7 → "0.7"：两位小数在滑杆数值上显得像仪表盘，读数反而不利
    val s = String.format(java.util.Locale.US, "%.2f", v)
    return s.trimEnd('0').trimEnd('.')
}


