package memore.app.ui.screens

import androidx.compose.ui.unit.sp
import memore.app.ui.components.MemoreLineInput
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import memore.app.data.model.Record
import memore.app.data.model.SliceEntry
import memore.app.data.model.Tick
import memore.app.data.model.TickSlice
import memore.app.data.repository.TickRepository
import memore.app.ui.components.MirrorShapes
import memore.app.ui.components.IconEye
import memore.app.ui.components.recordTypeLabel
import memore.app.utils.formatTimestamp
import kotlinx.coroutines.launch

/**
 * 某刻度区间内的记录列表（按各自 created_at 归属），并按 记录/情绪/节点 分段切片。
 * 切片数据：终结刻度读定格快照（不再随 now 重算），进行中刻度实时算；内部衰减基准为该刻度自身终点/now。
 */
@Composable
fun TickRecordsScreen(
    tickRepository: TickRepository,
    tickId: String,
    reviewService: memore.app.data.vector.ReviewService? = null,
    onBack: () -> Unit,
    onOpenRecord: (String) -> Unit
) {
    var tick by remember { mutableStateOf<Tick?>(null) }
    var records by remember { mutableStateOf<List<Record>>(emptyList()) }
    var slice by remember { mutableStateOf<TickSlice?>(null) }
    var tab by remember { mutableIntStateOf(0) }
    // 2026-11-22：回看改成「确认 → 加载弹窗 → 结果弹窗（保存/取消）」，保存进 tick.review。
    var reviewConfirm by remember { mutableStateOf(false) }
    var reviewRunning by remember { mutableStateOf(false) }
    var reviewCandidate by remember { mutableStateOf("") }
    var reviewShowResult by remember { mutableStateOf(false) }
    var reviewError by remember { mutableStateOf<String?>(null) }
    var reviewDeleteConfirm by remember { mutableStateOf(false) }
    // 2026-11-22：总结的「编写/编辑」移到页签下面那条栏；回看挪到真右上角的眼睛图标。
    var editRequest by remember { mutableIntStateOf(0) }
    var summaryEditing by remember { mutableStateOf(false) }
    /** 首帧保护：tick 初值 null、加载是异步的，不区分"还没加载"和"真没有"会闪假状态。 */
    var loaded by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(tickId) {
        loaded = false
        tick = tickRepository.getById(tickId)
        tick?.let {
            records = tickRepository.recordsInRange(it.startMs, it.endMs)
            slice = tickRepository.loadSlice(it)
        }
        loaded = true
        tab = 0
    }

    val cyan = Color(0xFF4DD0E1)
    val purple = Color(0xFFA78BFA)
    val accent = if (tick?.endMs == null) cyan else purple
    val statusText = if (tick?.endMs == null) "进行中" else "已定格"

    // 2026-11-16：这一屏是自己手写的顶栏（不是 Scaffold/TopAppBar），没有任何 inset。
    // 真机上标题直接顶到状态栏、被时间和信号图标压住。加 statusBarsPadding 即可。
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回") }
            Text(tick?.name ?: "刻度", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.width(8.dp))
            Box(Modifier.size(8.dp).clip(CircleShape).background(accent))
            Spacer(Modifier.width(4.dp))
            Text(statusText, style = MaterialTheme.typography.labelSmall, color = accent)
            Spacer(Modifier.weight(1f))
            // 回看：右上角眼睛图标；先弹确认，不直接跑 AI。
            IconButton(
                onClick = {
                    tab = 2
                    reviewError = null
                    reviewConfirm = true
                },
                enabled = !reviewRunning && reviewService != null
            ) {
                Icon(
                    IconEye,
                    contentDescription = "回看",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        // 页签（2026-11-22）：与「关联 / 情绪分布」同一套：上下 0.28 发丝线 + 1dp×18dp 竖线，无下划线。
        Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.28f)))
        Row(Modifier.fillMaxWidth().height(40.dp), verticalAlignment = Alignment.CenterVertically) {
            listOf("记录", "情绪", "总结").forEachIndexed { i, label ->
                if (i > 0) {
                    Box(
                        Modifier.width(1.dp).height(18.dp)
                            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.28f))
                    )
                }
                Box(
                    Modifier.weight(1f).fillMaxHeight().clickable { tab = i },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        label,
                        fontSize = 13.sp,
                        color = if (tab == i) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.28f)))
        Spacer(Modifier.height(6.dp))
        if (!loaded) {
            // 还没读到刻度：留白，别闪出"还没有总结"/"此区间暂无记录"
            memore.app.ui.components.MirrorLoading()
        } else when (tab) {
            0 -> RecordList(records, onOpenRecord)
            1 -> EmotionSlice(slice?.emotion ?: emptyList())
            2 -> Column(Modifier.fillMaxSize()) {
                val summary = tick?.summary.orEmpty()
                Row(
                    Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (summary.isNotBlank() && !summaryEditing) {
                        Text("${summary.length} 字", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.weight(1f))
                    if (!summaryEditing) {
                        Text(
                            if (summary.isBlank()) "编写" else "编辑",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.clickable { editRequest++ }
                                .padding(horizontal = 8.dp, vertical = 6.dp)
                        )
                    }
                }
                Box(Modifier.weight(1f)) {
                    TickSummary(
                        summary = summary,
                        review = tick?.review.orEmpty(),
                        summaryPrev = tick?.summaryPrev.orEmpty(),
                        editRequest = editRequest,
                        onEditingChanged = { summaryEditing = it },
                        onDeleteReview = { reviewDeleteConfirm = true },
                        onSave = { text ->
                            scope.launch {
                                tickRepository.updateSummary(tickId, text)
                                tick = tickRepository.getById(tickId)
                            }
                        },
                        onRestorePrev = {
                            scope.launch {
                                tickRepository.swapSummary(tickId)
                                tick = tickRepository.getById(tickId)
                            }
                        }
                    )
                }
            }
        }
    }

    // 回看：确认 → 加载 → 结果（保存 / 取消）。
    if (reviewConfirm) {
        memore.app.ui.components.MirrorStackDialog(
            title = "回看",
            confirmText = "开始",
            onConfirm = {
                reviewConfirm = false
                val t = tick
                if (t == null || reviewService == null) return@MirrorStackDialog
                reviewRunning = true
                reviewError = null
                scope.launch {
                    val r = reviewService.review(t.startMs, t.endMs ?: System.currentTimeMillis(), t.name)
                    reviewRunning = false
                    r.onSuccess { reviewCandidate = it; reviewShowResult = true }
                        .onFailure { reviewError = it.message ?: "生成失败"; reviewShowResult = true }
                }
            },
            onDismiss = { reviewConfirm = false }
        )
    }

    if (reviewRunning) {
        memore.app.ui.components.MirrorStackDialog(
            title = "回看",
            confirmText = "读取中…",
            onConfirm = {},
            onDismiss = {}
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                memore.app.ui.components.MirrorLoading()
                Spacer(Modifier.height(10.dp))
                Text(
                    "正在回看这段时间",
                    style = memore.app.ui.theme.MemoreType.micro,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    if (reviewShowResult) {
        memore.app.ui.components.MirrorStackDialog(
            title = "回看",
            confirmText = if (reviewError != null) "好" else "保存",
            onConfirm = {
                val text = reviewCandidate
                reviewShowResult = false
                reviewCandidate = ""
                if (reviewError == null && text.isNotBlank()) {
                    scope.launch {
                        tickRepository.updateReview(tickId, text)
                        tick = tickRepository.getById(tickId)
                    }
                }
                reviewError = null
            },
            onDismiss = {
                reviewShowResult = false
                reviewCandidate = ""
                reviewError = null
            }
        ) {
            Column(
                Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState())
            ) {
                val err = reviewError
                if (err != null) {
                    Text(err, style = memore.app.ui.theme.MemoreType.meta, color = MaterialTheme.colorScheme.error)
                } else {
                    Text(
                        reviewCandidate,
                        style = memore.app.ui.theme.MemoreType.reading,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }

    // 长按回看 → 删除确认。
    if (reviewDeleteConfirm) {
        memore.app.ui.components.MirrorStackDialog(
            title = "删除回看？",
            confirmText = "删除",
            destructive = true,
            onConfirm = {
                reviewDeleteConfirm = false
                scope.launch {
                    tickRepository.updateReview(tickId, "")
                    tick = tickRepository.getById(tickId)
                }
            },
            onDismiss = { reviewDeleteConfirm = false }
        )
    }
}

/**
 * 刻度总结（后记）：分**浏览态**与**编辑态**两个状态。
 *
 * - 浏览态：整段文字可滚动阅读；入口在页签下面那条栏的右上角（编写 / 编辑）。
 * - 编辑态：占满内容区的长文输入 + 保存/取消；保存走定点 UPDATE，
 *   **不碰起止、不触发重新定格** —— 那份定格快照（"当时的自我"）永远不动。
 * - 空总结就是空白，不再写「还没有总结」。
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun TickSummary(
    summary: String,
    review: String,
    summaryPrev: String,
    editRequest: Int,
    onEditingChanged: (Boolean) -> Unit,
    onDeleteReview: () -> Unit,
    onSave: (String) -> Unit,
    onRestorePrev: () -> Unit
) {
    var editing by remember { mutableStateOf(false) }
    var draft by remember(summary) { mutableStateOf(summary) }

    LaunchedEffect(editRequest) {
        if (editRequest > 0) {
            draft = summary
            editing = true
        }
    }
    LaunchedEffect(editing) { onEditingChanged(editing) }

    if (editing) {
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            // 2026-11-22：长文编辑不能用 MemoreLineInput（它是贴底的单行控件，会把正文挤到底部）。
            androidx.compose.foundation.text.BasicTextField(
                value = draft,
                onValueChange = { draft = it },
                textStyle = memore.app.ui.theme.MemoreType.reading.copy(
                    color = MaterialTheme.colorScheme.onSurface
                ),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
                maxLines = Int.MAX_VALUE,
                modifier = Modifier.fillMaxWidth().weight(1f),
                decorationBox = { inner ->
                    Box(Modifier.fillMaxSize()) {
                        if (draft.isEmpty()) {
                            Text(
                                "写给这一段。想写多长写多长，随时可以回来改。",
                                style = memore.app.ui.theme.MemoreType.reading,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                            )
                        }
                        inner()
                    }
                }
            )
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("${draft.length} 字", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.weight(1f))
                // 上一版留档：只在"确实有不同的一版"时才出现，避免噪音
                if (summaryPrev.isNotBlank() && summaryPrev != summary) {
                    TextButton(onClick = { draft = summaryPrev; editing = false; onRestorePrev() }) { Text("恢复上一版") }
                }
                TextButton(onClick = { draft = summary; editing = false }) { Text("取消") }
                TextButton(onClick = { onSave(draft); editing = false }) { Text("保存") }
            }
            Spacer(Modifier.height(4.dp))
        }
        return
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp).padding(bottom = 24.dp)
    ) {
        if (summary.isNotBlank()) {
            Text(summary, style = memore.app.ui.theme.MemoreType.reading, color = MaterialTheme.colorScheme.onSurface)
        }
        if (review.isNotBlank()) {
            if (summary.isNotBlank()) {
                Spacer(Modifier.height(12.dp))
                Box(
                    Modifier.fillMaxWidth().padding(end = 4.dp).height(1.dp)
                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f))
                )
                Spacer(Modifier.height(14.dp))
            } else {
                Spacer(Modifier.height(24.dp))
            }
            Text(
                review,
                style = memore.app.ui.theme.MemoreType.reading,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.combinedClickable(
                    onClick = {},
                    onLongClick = onDeleteReview
                )
            )
        }
    }
}

@Composable
private fun RecordList(records: List<Record>, onOpenRecord: (String) -> Unit) {
    if (records.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("此区间暂无记录", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    // T1：按天分组 —— 天是章节，天与天之间 18% 横线，天内 10% 发丝线
    val dayFmt = remember { java.text.SimpleDateFormat("MM-dd EEE", java.util.Locale.CHINA) }
    val grouped = records.groupBy { dayFmt.format(java.util.Date(it.createdAt)) }
    LazyColumn(Modifier.fillMaxSize()) {
        grouped.forEach { (day, dayRecords) ->
            item(key = "day_$day") {
                Row(
                    Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        day,
                        fontSize = 10.sp,
                        letterSpacing = 1.1.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        "${dayRecords.size} 条",
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Box(
                    Modifier.fillMaxWidth().padding(start = 16.dp, end = 20.dp).height(1.dp)
                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f))
                )
            }
            items(dayRecords, key = { it.id }) { r ->
                Column(
                    Modifier.fillMaxWidth().clickable { onOpenRecord(r.id) }
                        .padding(start = 16.dp, end = 16.dp, top = 9.dp, bottom = 9.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            recordTypeLabel(r.type),
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            formatTimestamp(r.createdAt).substringAfter(' ').ifEmpty { formatTimestamp(r.createdAt) },
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.height(3.dp))
                    Text(
                        r.content.ifBlank { r.notes }.ifBlank { "（空内容）" },
                        fontSize = 13.sp,
                        lineHeight = 20.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                }
                Box(
                    Modifier.fillMaxWidth().padding(start = 16.dp, end = 20.dp).height(1.dp)
                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f))
                )
            }
        }
    }
}

/** 情绪切片：频率（次数）与强度（衰减加权均值）都可看，默认按频率，可切换到强度排序。 */
@Composable
private fun EmotionSlice(entries: List<SliceEntry>) {
    if (entries.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("此区间暂无情绪", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        return
    }
    var byIntensity by remember { mutableStateOf(false) }
    val totalCount = entries.sumOf { it.count }
    val totalWeight = entries.sumOf { it.weight }
    val sorted = if (byIntensity) entries.sortedByDescending { it.weight } else entries.sortedByDescending { it.count }
    Column(Modifier.fillMaxSize()) {
        // 顶部一行：汇总在左，频率/强度是纯文字（选中加粗），不用胶囊
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "共 ${totalCount} 次 · ${entries.size} 种",
                fontSize = 10.5.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.weight(1f))
            listOf(false to "频率", true to "强度").forEachIndexed { i, (isInt, label) ->
                if (i > 0) {
                    Box(
                        Modifier.width(1.dp).height(18.dp)
                            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.28f))
                    )
                }
                Text(
                    label,
                    style = memore.app.ui.theme.MemoreType.meta,
                    fontWeight = if (byIntensity == isInt) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (byIntensity == isInt) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.clickable { byIntensity = isInt }.padding(horizontal = 8.dp, vertical = 2.dp)
                )
            }
        }
        Box(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 20.dp).height(1.dp)
                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f))
        )
        LazyColumn(Modifier.fillMaxSize()) {
            items(sorted, key = { "${it.name}_${it.count}_${it.weight}" }) { e ->
                // 百分比跟随当前排序口径：频率→次数占比；强度→强度占比。
                val pct = if (byIntensity) {
                    if (totalWeight > 0) String.format("%.1f%%", e.weight / totalWeight * 100) else "0%"
                } else {
                    if (totalCount > 0) String.format("%.1f%%", e.count.toDouble() / totalCount * 100) else "0%"
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(e.name, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                    Text("${e.count}次", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(10.dp))
                    Text(pct, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                }
                Box(
                    Modifier.fillMaxWidth().padding(start = 16.dp, end = 20.dp).height(1.dp)
                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f))
                )
            }
        }
    }
}

/** 通用切片列表：名称 + 出现次数 + 权重占比。 */
@Composable
private fun SliceList(entries: List<SliceEntry>, emptyText: String) {
    if (entries.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(emptyText, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    } else {
        val total = entries.sumOf { it.weight }
        LazyColumn(Modifier.fillMaxSize()) {
            items(entries, key = { "${it.name}_${it.count}" }) { e ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(e.name, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                    if (e.count > 0) {
                        Text("${e.count}次", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(10.dp))
                    }
                    Text(if (total > 0) String.format("%.1f%%", e.weight / total * 100) else "0%", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                }
                Box(
                    Modifier.fillMaxWidth().padding(start = 16.dp, end = 20.dp).height(1.dp)
                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f))
                )
            }
        }
    }
}

