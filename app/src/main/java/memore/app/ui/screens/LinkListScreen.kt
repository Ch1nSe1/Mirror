package memore.app.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.sp
import memore.app.data.model.RecordRelationType
import memore.app.data.model.RecordType
import memore.app.data.repository.AnchorRepository
import memore.app.data.repository.RecordRepository
import memore.app.ui.components.MirrorStackDialog
import memore.app.ui.components.recordTypeColor
import memore.app.ui.components.recordTypeLabel
import memore.app.ui.theme.MemoreType
import memore.app.utils.formatTimestamp
import memore.app.utils.localMillisToDatePicker
import memore.app.utils.datePickerToLocalStartOfDay
import kotlinx.coroutines.launch

/**
 * 关联页的跨屏状态（2026-11-19）。
 *
 * 为什么放在外面：这个 App 的导航会销毁整棵组合树，`remember` 的数据一返回就没了。
 * 页面切到记录详情再回来，数据不该重新加载；tab/展开/滚动由 rememberSaveable 兜住。
 */
class LinkListUiState {
    // 2026-11-21：必须是 Compose 状态。原来用普通 var，LaunchedEffect 加载完写进去
    // 不会触发重组 —— 表现就是「第一次进一直转圈，退出去再进就好了」（第二次组合读到已加载）。
    var links by mutableStateOf<List<RecordRepository.LinkPair>>(emptyList())
    var linksLoaded by mutableStateOf(false)
    var anchors by mutableStateOf<List<AnchorRepository.AnchorGroup>>(emptyList())
    var anchorsLoaded by mutableStateOf(false)
}

/**
 * 关联（2026-11-19 统一行语法）。
 *
 * 两个视图只差"父"：按记录父是起点记录，按概念父是人/作品/地点。
 * 行语法共用：
 *   记录行 = 类型 + 时间 + 一行内容
 *   边行   = ↳ + why + 对方日期/内容 + 引文 + 行尾类型字（普通关联不出字）
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun LinkListScreen(
    state: LinkListUiState,
    loadLinks: suspend () -> List<RecordRepository.LinkPair>,
    loadAnchors: suspend () -> List<AnchorRepository.AnchorGroup>,
    onOpenRecord: (String) -> Unit,
    onReject: suspend (String, String) -> Unit,
    /** 2026-11-21：概念长按菜单里的「改名」跳转到别名映射管理。 */
    onOpenAliasMapping: () -> Unit = {},
    /** 2026-11-21：删除一个概念（只删锚点行），返回删掉的行数。 */
    onDeleteConcept: suspend (String) -> Int = { 0 },
    onBack: () -> Unit
) {
    // 2026-11-21：不要 rememberSaveable —— 上次停在「按概念」会被存进 saved state，
    // 下次进来直接就是概念页（用户报的 bug）。每次进入默认「按记录」。
    var byAnchor by remember { mutableStateOf(false) }
    var expanded by rememberSaveable(
        stateSaver = listSaver<Set<String>, String>(
            save = { it.toList() },
            restore = { it.toSet() }
        )
    ) { mutableStateOf(emptySet<String>()) }
    var rejectTarget by remember { mutableStateOf<RecordRepository.LinkPair?>(null) }
    var rejectAnchorEdge by remember { mutableStateOf<AnchorRepository.AnchorEdge?>(null) }
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    // 2026-11-22：时间筛选。先选起始，再选结束；范围是 [start, end+1day)。
    var filterStart by rememberSaveable { mutableStateOf<Long?>(null) }
    var filterEnd by rememberSaveable { mutableStateOf<Long?>(null) }
    var pickerStep by remember { mutableStateOf(0) } // 0=关 1=选起 2=选止

    LaunchedEffect(Unit) {
        if (state.linksLoaded) return@LaunchedEffect
        state.links = runCatching { loadLinks() }.getOrDefault(emptyList())
        state.linksLoaded = true
    }
    LaunchedEffect(byAnchor) {
        if (!byAnchor) return@LaunchedEffect
        // 2026-11-21：每次进「按概念」都重算一次。
        // 以前命中缓存就不再加载 —— 在别名映射里新加的归并永远不生效（用户实测：
        // 加了 TestConceptA 的组合，回到这里还是好几个同名变体）。重算只是几十行数据。
        state.anchors = runCatching { loadAnchors() }.getOrDefault(emptyList())
        state.anchorsLoaded = true
    }

    val visibleLinks = remember(state.links, filterStart, filterEnd) {
        val st = filterStart
        val en = filterEnd
        if (st == null || en == null) state.links else state.links.filter { it.from.createdAt in st until en }
    }
    val visibleAnchors = remember(state.anchors, filterStart, filterEnd) {
        val st = filterStart
        val en = filterEnd
        if (st == null || en == null) state.anchors else filterAnchorGroups(state.anchors, st, en)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("关联", style = MemoreType.pageTitleSans) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回") }
                },
                actions = {
                    // 2026-11-22：原来这里是「N 个概念 · N 条记录」的统计，算法一变就不准。
                    // 换成时间筛选入口（时钟）。
                    IconButton(
                        onClick = { pickerStep = 1 },
                        modifier = Modifier.semantics { contentDescription = "时间筛选" }
                    ) {
                        ClockIcon(MaterialTheme.colorScheme.onSurface)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            // 结构线 0.28；上下留 20dp；中间竖线只占 tab 行内部高度，不接上下线。
            Hairline(alpha = 0.28f)
            Row(Modifier.fillMaxWidth().height(40.dp), verticalAlignment = Alignment.CenterVertically) {
                ModeTab("按记录", !byAnchor, Modifier.weight(1f)) { byAnchor = false }
                Box(
                    Modifier.width(1.dp).height(18.dp)
                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.28f))
                )
                ModeTab("按概念", byAnchor, Modifier.weight(1f)) { byAnchor = true }
            }
            Hairline(alpha = 0.28f)
            if (filterStart != null && filterEnd != null) {
                val fmt = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.CHINA)
                Row(
                    Modifier.fillMaxWidth()
                        .padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        fmt.format(java.util.Date(filterStart!!)) + " → " +
                            fmt.format(java.util.Date(filterEnd!! - 86_400_000L)),
                        fontSize = 10.5.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        "×",
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.clickable {
                            filterStart = null
                            filterEnd = null
                        }
                    )
                }
                Hairline(alpha = 0.10f)
            }
            if (!byAnchor) {
                RecordsBody(visibleLinks, state.linksLoaded, onOpenRecord) { rejectTarget = it }
            } else {
                AnchorsBody(
                    anchors = visibleAnchors,
                    loaded = state.anchorsLoaded,
                    expanded = expanded,
                    onToggle = { name ->
                        expanded = if (name in expanded) expanded - name else expanded + name
                    },
                    onOpenRecord = onOpenRecord,
                    onLongPressEdge = {
                        rejectAnchorEdge = it
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    },
                    onOpenAliasMapping = onOpenAliasMapping,
                    onDeleteConcept = onDeleteConcept,
                    reloadAnchors = {
                        state.anchors = runCatching { loadAnchors() }.getOrDefault(emptyList())
                        state.anchorsLoaded = true
                    }
                )
            }
        }
    }

    if (pickerStep != 0) {
        val initial = if (pickerStep == 2) filterStart else filterStart
        val datePickerState = rememberDatePickerState(
            initialSelectedDateMillis = localMillisToDatePicker(initial ?: System.currentTimeMillis())
        )
        DatePickerDialog(
            onDismissRequest = { pickerStep = 0 },
            confirmButton = {
                TextButton(onClick = {
                    val ms = datePickerState.selectedDateMillis
                    if (ms != null) {
                        val dayStart = datePickerToLocalStartOfDay(ms)
                        if (pickerStep == 1) {
                            filterStart = dayStart
                            pickerStep = 2
                        } else {
                            val lo = minOf(filterStart ?: dayStart, dayStart)
                            val hi = maxOf(filterStart ?: dayStart, dayStart) + 86_400_000L
                            filterStart = lo
                            filterEnd = hi
                            pickerStep = 0
                        }
                    }
                }) { Text("确定", color = MaterialTheme.colorScheme.primary) }
            },
            dismissButton = {
                TextButton(onClick = { pickerStep = 0 }) {
                    Text("取消", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        ) { DatePicker(state = datePickerState) }
    }

    rejectTarget?.let { pair ->
        MirrorStackDialog(
            title = "这条不算关联？",
            confirmText = "不算",
            onDismiss = { rejectTarget = null },
            onConfirm = {
                rejectTarget = null
                scope.launch {
                    runCatching { onReject(pair.from.id, pair.to.id) }
                    state.links = runCatching { loadLinks() }.getOrDefault(state.links); state.linksLoaded = true
                }
            }
        ) {
            Text("以后不会再把它推荐成关联。", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    rejectAnchorEdge?.let { edge ->
        MirrorStackDialog(
            title = "这条边不算？",
            confirmText = "不算",
            onDismiss = { rejectAnchorEdge = null },
            onConfirm = {
                rejectAnchorEdge = null
                scope.launch {
                    runCatching { onReject(edge.fromId, edge.toId) }
                    state.anchors = runCatching { loadAnchors() }.getOrDefault(state.anchors); state.anchorsLoaded = true
                }
            }
        ) {
            Text("以后不会再把它推荐成关联。", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ===== 共用行 =====

@Composable
private fun ModeTab(text: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(modifier.clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Text(
            text,
            fontSize = 13.sp,
            color = if (selected) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
        )
    }
}

/** 记录行：类型 + 时间 + 一行内容。两页共用。 */
@Composable
private fun RecordRow(
    type: RecordType,
    time: String,
    content: String,
    indent: Dp = 20.dp,
    onClick: () -> Unit
) {
    Column(
        Modifier.fillMaxWidth().clickable(onClick = onClick)
            .padding(start = indent, end = 20.dp, top = 12.dp, bottom = 4.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(recordTypeLabel(type), fontSize = 10.sp, color = recordTypeColor(type))
            Spacer(Modifier.width(6.dp))
            Text(time, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(3.dp))
        Text(
            content.ifBlank { "（空内容）" },
            fontSize = 13.5.sp,
            lineHeight = 20.sp,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * 边行：↳ + why + 日期行 + 引文 + 行尾类型字。
 *
 * 普通关联（LINK）不出字——页面本身就叫「关联」，再标一个"关"是废话。
 * 只有 引用/补充/更正 才有行尾字。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EdgeRow(
    why: String,
    dateLine: String,
    content: String?,
    quoteA: String,
    quoteB: String,
    type: String,
    /** 2026-11-22：两端共享的概念。空 = 跨概念/无线索。 */
    shared: List<String> = emptyList(),
    onClick: () -> Unit,
    onLongPress: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongPress)
            .padding(start = 20.dp, end = 20.dp, top = 6.dp, bottom = 6.dp)
    ) {
        Text(
            "↳", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 1.dp)
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            if (why.isNotBlank()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        why,
                        fontSize = 12.sp,
                        lineHeight = 18.sp,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    TypeMark(type)
                }
                Spacer(Modifier.height(3.dp))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(dateLine, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (!content.isNullOrBlank()) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        content,
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            if (quoteA.isNotBlank() || quoteB.isNotBlank()) {
                Spacer(Modifier.height(3.dp))
                Text(
                    "「" + quoteA + "」 ↔ 「" + quoteB + "」",
                    fontSize = 10.5.sp,
                    lineHeight = 15.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            // 2026-11-22：边不归某一个概念所有；把共享的概念列出来，避免“全是测试甲”的错觉。
            if (shared.isNotEmpty()) {
                Spacer(Modifier.height(3.dp))
                Text(
                    "共享：" + shared.joinToString(" · "),
                    fontSize = 9.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.9f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/**
 * 关系类型字（2026-11-19）：普通关联不出字；颜色是纸上的墨，不是荧光色。
 */
@Composable
private fun TypeMark(type: String) {
    val (ch, color) = when (type) {
        RecordRelationType.CITE -> "引" to Color(0xFF5C6B8A)
        RecordRelationType.SUPPLEMENT -> "补" to Color(0xFF5E7D6B)
        RecordRelationType.CORRECT -> "纠" to Color(0xFF8A5C52)
        else -> return
    }
    Text(ch, fontSize = 10.sp, color = color, modifier = Modifier.padding(start = 8.dp))
}

@Composable
private fun Hairline(alpha: Float = 0.18f, start: Dp = 20.dp) {
    Box(
        Modifier.fillMaxWidth().padding(horizontal = start).height(1.dp)
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = alpha))
    )
}

// ===== 两个视图 =====

@Composable
private fun RecordsBody(
    links: List<RecordRepository.LinkPair>,
    loaded: Boolean,
    onOpenRecord: (String) -> Unit,
    onLongPress: (RecordRepository.LinkPair) -> Unit
) {
    if (!loaded) {
        memore.app.ui.components.MirrorLoading()
        return
    }
    if (links.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("还没有关联", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    val groups = links.groupBy { it.from.id }
    LazyColumn(Modifier.fillMaxSize()) {
        items(groups.entries.toList(), key = { it.key }) { (_, edges) ->
            val from = edges.first().from
            Column(Modifier.fillMaxWidth()) {
                RecordRow(from.type, formatTimestamp(from.createdAt), from.content.ifBlank { from.notes }) {
                    onOpenRecord(from.id)
                }
                edges.forEach { pair ->
                    EdgeRow(
                        why = pair.why,
                        dateLine = formatTimestamp(pair.to.createdAt),
                        content = pair.to.content.ifBlank { pair.to.notes },
                        quoteA = pair.quoteA,
                        quoteB = pair.quoteB,
                        type = pair.type,
                        onClick = { onOpenRecord(pair.to.id) },
                        onLongPress = { onLongPress(pair) }
                    )
                }
                Spacer(Modifier.height(10.dp))
                Hairline()
                Spacer(Modifier.height(4.dp))
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AnchorsBody(
    anchors: List<AnchorRepository.AnchorGroup>,
    loaded: Boolean,
    expanded: Set<String>,
    onToggle: (String) -> Unit,
    onOpenRecord: (String) -> Unit,
    onLongPressEdge: (AnchorRepository.AnchorEdge) -> Unit,
    onOpenAliasMapping: () -> Unit,
    onDeleteConcept: suspend (String) -> Int,
    reloadAnchors: suspend () -> Unit
) {
    // 2026-11-21：概念长按菜单（锚定在行上，不是居中弹窗）。
    // 菜单状态放在 ConceptHeader 里，主列表和「偶发」区共用同一套行为。
    var renameNoticeFor by remember { mutableStateOf<String?>(null) }
    var deleteFor by remember { mutableStateOf<AnchorRepository.AnchorGroup?>(null) }
    var deleteBusy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    if (!loaded) {
        memore.app.ui.components.MirrorLoading()
        return
    }
    if (anchors.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("还没有概念", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    // 2026-11-21（用户定 B+C）：
    // 主列表只留「有边」或「≥2 条记录」的概念；1 条记录 + 0 边的杂概念收进「偶发」折叠区。
    // 只做内存里的分组，不碰库、不碰提示词。
    val mainConcepts = anchors.filter { it.edges.isNotEmpty() || it.records.size >= 2 }
    val rareConcepts = anchors.filter { it.edges.isEmpty() && it.records.size < 2 }
    val rareKey = "#rare"
    LazyColumn(Modifier.fillMaxSize()) {
        // 2026-11-21：key 只按概念名（同名不同类已合并），不会重复。
        items(mainConcepts, key = { it.key }) { group ->
            val open = group.key in expanded
            Column(Modifier.fillMaxWidth()) {
                ConceptHeader(
                    group = group,
                    open = open,
                    topPadding = 14.dp,
                    showEdges = true,
                    onToggle = { onToggle(group.key) },
                    onRename = { renameNoticeFor = group.name },
                    onDelete = { deleteFor = group }
                )
                if (open) {
                    // 2026-11-21：按概念页只列"下面有线可画"的记录。
                    // 注意是**出边**（edge.fromId == rec.id）：边行只挂在来源记录下，
                    // 如果按"参与过边"保留，目标记录会出现成一行没有线的记录（用户实测的"没排除完"）。
                    // 目标记录仍可从那条边点进去。
                    val byId = group.records.associateBy { it.id }
                    // 2026-11-22：共享两个概念的边不再平铺（一个概念会被撑爆），
                    // 收进「概念A × 概念B」分组；只共享当前概念的边留在记录下面。
                    val soleEdges = group.edges.filter { it.sharedConcepts.size <= 1 }
                    val pairGroups = group.edges.filter { it.sharedConcepts.size >= 2 }
                        .groupBy { it.sharedConcepts.sorted().joinToString(" × ") }
                        .toList()
                        .sortedByDescending { it.second.size }
                    val withEdges = group.records.filter { rec -> soleEdges.any { it.fromId == rec.id } }
                    withEdges.forEachIndexed { index, rec ->
                        RecordRow(rec.type, rec.date, rec.snippet) { onOpenRecord(rec.id) }
                        soleEdges.filter { it.fromId == rec.id }.forEach { edge ->
                            val other = byId[edge.toId]
                            EdgeRow(
                                why = edge.why,
                                dateLine = other?.date ?: edge.toDate,
                                content = other?.snippet,
                                quoteA = edge.quoteA,
                                quoteB = edge.quoteB,
                                type = edge.type,
                                shared = edge.sharedConcepts,
                                onClick = { onOpenRecord(edge.toId) },
                                onLongPress = { onLongPressEdge(edge) }
                            )
                        }
                        // 成员记录之间也要分开：用比组间线更浅的 0.10 发丝线。
                        if (index < withEdges.lastIndex) {
                            Spacer(Modifier.height(6.dp))
                            Hairline(alpha = 0.10f)
                            Spacer(Modifier.height(6.dp))
                        }
                    }
                    pairGroups.forEach { (pairName, edges) ->
                        Spacer(Modifier.height(8.dp))
                        Row(
                            Modifier.fillMaxWidth()
                                .padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                pairName,
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                "${edges.size} 条边",
                                fontSize = 10.5.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        // 2026-11-22：组内也按「来源记录 + 它的边」展示，否则看不出边连着哪两条记录。
                        val bySource = edges.groupBy { it.fromId }
                            .toList()
                            .sortedByDescending { (_, es) -> es.maxOfOrNull { it.fromDate } ?: "" }
                        bySource.forEachIndexed { si, (srcId, srcEdges) ->
                            val src = byId[srcId]
                            if (src != null) {
                                RecordRow(src.type, src.date, src.snippet) { onOpenRecord(src.id) }
                            }
                            srcEdges.forEach { edge ->
                                val other = byId[edge.toId]
                                EdgeRow(
                                    why = edge.why,
                                    dateLine = other?.date ?: edge.toDate,
                                    content = other?.snippet,
                                    quoteA = edge.quoteA,
                                    quoteB = edge.quoteB,
                                    type = edge.type,
                                    shared = edge.sharedConcepts,
                                    onClick = { onOpenRecord(edge.toId) },
                                    onLongPress = { onLongPressEdge(edge) }
                                )
                            }
                            if (si < bySource.lastIndex) {
                                Spacer(Modifier.height(6.dp))
                                Hairline(alpha = 0.10f)
                                Spacer(Modifier.height(6.dp))
                            }
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                }
                Hairline()
                Spacer(Modifier.height(4.dp))
            }
        }
        // 偶发概念：1 条记录 + 0 条边。默认折叠，展开后与主列表同一套行语法。
        if (rareConcepts.isNotEmpty()) {
            item(key = rareKey) {
                val open = rareKey in expanded
                Row(
                    Modifier.fillMaxWidth().clickable { onToggle(rareKey) }
                        .padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "偶发 ${rareConcepts.size}",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        if (open) "−" else "+",
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (rareKey in expanded) {
                items(rareConcepts, key = { it.key }) { group ->
                    val open = group.key in expanded
                    Column(Modifier.fillMaxWidth()) {
                        ConceptHeader(
                            group = group,
                            open = open,
                            topPadding = 12.dp,
                            showEdges = false,
                            onToggle = { onToggle(group.key) },
                            onRename = { renameNoticeFor = group.name },
                            onDelete = { deleteFor = group }
                        )
                        if (open) {
                            group.records.forEach { rec ->
                                RecordRow(rec.type, rec.date, rec.snippet) { onOpenRecord(rec.id) }
                            }
                            Spacer(Modifier.height(10.dp))
                        }
                        Hairline(alpha = 0.10f)
                    }
                }
            }
        }
    }

    // 改名：不在这里改，指向别名映射管理（那一页才是真身）。
    renameNoticeFor?.let {
        memore.app.ui.components.MirrorAlertDialog(
            onDismissRequest = { renameNoticeFor = null },
            title = { Text("改名", style = MaterialTheme.typography.titleMedium) },
            text = {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "建议前往",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "别名映射管理",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable {
                            renameNoticeFor = null
                            onOpenAliasMapping()
                        }
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { renameNoticeFor = null }) {
                    Text("好", color = MaterialTheme.colorScheme.primary)
                }
            }
        )
    }

    // 删除概念：只删锚点行，记录/关联不受影响。
    deleteFor?.let { group ->
        memore.app.ui.components.MirrorAlertDialog(
            onDismissRequest = { if (!deleteBusy) deleteFor = null },
            title = { Text("删除概念「${group.name}」？", style = MaterialTheme.typography.titleMedium) },
            text = {
                Text(
                    "将移除 ${group.records.size} 条记录上的锚点；记录与关联不受影响。",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            confirmButton = {
                TextButton(
                    enabled = !deleteBusy,
                    onClick = {
                        deleteBusy = true
                        scope.launch {
                            onDeleteConcept(group.name)
                            deleteBusy = false
                            deleteFor = null
                            reloadAnchors()
                        }
                    }
                ) {
                    Text(if (deleteBusy) "删除中…" else "删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { if (!deleteBusy) deleteFor = null }) {
                    Text("取消", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        )
    }
}

/**
 * 概念行头（主列表与「偶发」区共用）。
 *
 * 2026-11-21：长按 → 锚定菜单（改名 / 删除）。之前只有主列表绑了长按，
 * 偶发区的行是另一段复制出来的 UI，所以"偶发里长按没反应"。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ConceptHeader(
    group: AnchorRepository.AnchorGroup,
    open: Boolean,
    topPadding: androidx.compose.ui.unit.Dp,
    showEdges: Boolean,
    onToggle: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit
) {
    var menu by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current
    memore.app.ui.components.MirrorAnchoredMenu(
        expanded = menu,
        onDismissRequest = { menu = false },
        actions = listOf(
            memore.app.ui.components.MirrorMenuAction("改名") {
                menu = false
                onRename()
            },
            memore.app.ui.components.MirrorMenuAction("删除", danger = true) {
                menu = false
                onDelete()
            }
        )
    ) {
        Row(
            Modifier.fillMaxWidth()
                .combinedClickable(
                    onClick = onToggle,
                    onLongClick = {
                        menu = true
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    }
                )
                .padding(start = 20.dp, end = 20.dp, top = topPadding, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                group.name,
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Text(
                "${group.records.size} 条记录" +
                    if (showEdges && group.edges.isNotEmpty()) " · ${group.edges.size} 条边" else "",
                fontSize = 10.5.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.End,
                style = androidx.compose.ui.text.TextStyle(fontFeatureSettings = "tnum"),
                maxLines = 1,
                modifier = Modifier.width(132.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                if (open) "−" else "+",
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** 时钟图标：24dp，线宽 1.6，跟顶栏其它图标同色。 */
@Composable
private fun ClockIcon(tint: androidx.compose.ui.graphics.Color) {
    androidx.compose.foundation.Canvas(Modifier.size(20.dp)) {
        val stroke = 1.6.dp.toPx()
        val r = size.minDimension / 2f - stroke / 2f
        drawCircle(color = tint, radius = r, style = androidx.compose.ui.graphics.drawscope.Stroke(stroke))
        val c = center
        drawLine(tint, c, androidx.compose.ui.geometry.Offset(c.x, c.y - r * 0.55f), stroke, androidx.compose.ui.graphics.StrokeCap.Round)
        drawLine(tint, c, androidx.compose.ui.geometry.Offset(c.x + r * 0.42f, c.y), stroke, androidx.compose.ui.graphics.StrokeCap.Round)
    }
}

/** 按时间过滤概念组：只保留区间内的记录，以及两端都还在区间内的边。 */
private fun filterAnchorGroups(
    groups: List<AnchorRepository.AnchorGroup>,
    start: Long,
    end: Long
): List<AnchorRepository.AnchorGroup> {
    val fmt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.CHINA)
    fun inRange(date: String): Boolean {
        val t = runCatching { fmt.parse(date)?.time }.getOrNull() ?: return false
        return t in start until end
    }
    return groups.mapNotNull { g ->
        val records = g.records.filter { inRange(it.date) }
        if (records.isEmpty()) return@mapNotNull null
        val ids = records.map { it.id }.toSet()
        g.copy(records = records, edges = g.edges.filter { it.fromId in ids && it.toId in ids })
    }
}
