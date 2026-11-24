package memore.app.ui.screens

import memore.app.ui.components.MirrorStackDialog
import memore.app.ui.components.MemoreLineInput
import androidx.compose.foundation.background
import memore.app.ui.components.MirrorDropdownMenu
import memore.app.ui.components.MirrorAlertDialog
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import memore.app.data.model.Dial
import memore.app.data.model.Record
import memore.app.data.model.Tick
import memore.app.data.repository.DialAggregate
import memore.app.data.repository.DialRepository
import memore.app.data.repository.DialSection
import memore.app.data.repository.buildDialAggregate
import memore.app.data.repository.groupRecordsByTick
import memore.app.ui.components.MirrorShapes
import memore.app.ui.components.mirrorHairline
import memore.app.ui.components.recordTypeLabel
import memore.app.ui.components.MirrorMenuAction
import memore.app.ui.components.MirrorMenu
import memore.app.utils.UiSoundPlayer
import memore.app.utils.datePickerToLocalStartOfDay
import memore.app.utils.formatDateOnly
import memore.app.utils.formatTimestamp
import memore.app.utils.localMillisToDatePicker
import kotlinx.coroutines.launch

/**
 * 表盘页（2026-10-20 定版：「T3 的抬头 + 两格汇总」＋「T1 的时间卷」）。
 *
 * 一句话定位：**刻度回答"这一段里是什么"，表盘只回答"这一段时期由哪几段组成、它们各自什么情绪"**。
 * 版式：
 * 1. 抬头（期名 / 起止 · 条数 · 段数）；
 * 2. 两格汇总（主导情绪 / 主导主题）—— 整期各出现多少次；
 * 3. **时间卷**：一条竖轴，每段一个节点（进行中实心青点 / 已定格空心紫点），
 *    每段三段信息：名字 + 条数 / 起止 / 「主导 · 情绪 N」+「后记 · 写了多少」；
 * 4. 轴尾一颗虚线节点 = 「未归属」（没被任何段框住的记录，点开展开）；
 * 5. 期的后记。
 *
 * 刻意没有的东西：热力网格、数字矩阵、情绪/节点 tab、定格。段内部的东西一律在段页看。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DialScreen(
    dialId: String,
    dialRepository: DialRepository,
    onBack: () -> Unit,
    onOpenRecord: (String) -> Unit,
    /** 点某一段 → 那一段自己的页面。 */
    onOpenTick: (String) -> Unit = {},
    /** 点「主导情绪 / 主导主题」→ 整期汇总页（DialStatKind.EMOTION / THEME）。 */
    onOpenStat: (String) -> Unit = {}
) {
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    var dial by remember { mutableStateOf<Dial?>(null) }
    var ticks by remember { mutableStateOf<List<Tick>>(emptyList()) }
    var allTicks by remember { mutableStateOf<List<Tick>>(emptyList()) }
    var records by remember { mutableStateOf<List<Record>>(emptyList()) }
    var nodeNames by remember { mutableStateOf<Map<String, List<String>>>(emptyMap()) }
    var loaded by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var showAddTicks by remember { mutableStateOf(false) }
    var showEdit by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var editingSummary by remember { mutableStateOf(false) }
    var expandUnassigned by remember { mutableStateOf(false) }

    suspend fun reload() {
        val d = dialRepository.getDial(dialId)
        dial = d
        ticks = dialRepository.ticksOfDial(dialId)
        allTicks = dialRepository.allActiveTicks()
        if (d != null) {
            records = dialRepository.recordsOfDial(d)
            nodeNames = dialRepository.themeNamesByRecord(d)
        }
        loaded = true
    }

    LaunchedEffect(dialId) { loaded = false; reload() }

    val sections: List<DialSection> = remember(records, ticks) {
        groupRecordsByTick(records, ticks, includeEmpty = true)
    }
    val unassigned = sections.firstOrNull { it.tick == null }
    val segSections = sections.filter { it.tick != null }
    val aggregate = remember(sections, nodeNames) { buildDialAggregate(sections, nodeNames) }
    // 只用 App 自己的两个强调色：情绪＝主题紫，主题＝协议青（4DD0E1，全 App 88 处都在用它）
    val violet = MaterialTheme.colorScheme.primary.copy(alpha = 0.62f)
    val cyan = Color(0xFF4DD0E1).copy(alpha = 0.62f)

    if (editingSummary) {
        DialSummaryEditor(
            summary = dial?.summary.orEmpty(),
            summaryPrev = dial?.summaryPrev.orEmpty(),
            onCancel = { editingSummary = false },
            onSave = { text ->
                scope.launch {
                    dialRepository.updateDialSummary(dialId, text)
                    editingSummary = false
                    reload()
                }
            },
            onRestorePrev = {
                scope.launch {
                    dialRepository.swapDialSummary(dialId)
                    reload()
                }
            }
        )
        return
    }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        // ===== 抬头 =====
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回") }
            Column(Modifier.weight(1f)) {
                Text(
                    dial?.name ?: "表盘",
                    fontSize = 17.sp,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Serif,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    lineHeight = 22.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                val d = dial
                if (d != null) {
                    Text(
                        "${formatDateOnly(d.startMs)} – ${d.endMs?.let { formatDateOnly(it) } ?: "至今"} · ${records.size} 条 · ${ticks.size} 段",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }
            Box {
                IconButton(onClick = { menu = true }) {
                    Icon(Icons.Outlined.MoreVert, "菜单", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                MirrorMenu(
                    expanded = menu,
                    onDismissRequest = { menu = false },
                    actions = listOf(
                        MirrorMenuAction("加刻度") { menu = false; showAddTicks = true },
                        MirrorMenuAction("编辑起止") { menu = false; showEdit = true },
                        MirrorMenuAction("删除表盘", danger = true) { menu = false; confirmDelete = true }
                    )
                )
            }
        }

        if (!loaded) {
            Box(Modifier.fillMaxWidth().weight(1f)) {}
        } else if (records.isEmpty() && segSections.isEmpty()) {
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Text("这一段还没有记录", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 24.dp)) {
                // ===== 两格汇总 =====
                val topEmotion = aggregate.emotions.firstOrNull()
                val topTheme = aggregate.themes.firstOrNull()
                if (topEmotion != null || topTheme != null) {
                    item(key = "summary_cards") {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            topEmotion?.let {
                                DialStatCard("主导情绪", it.name, it.total, violet, Modifier.weight(1f)) {
                                    onOpenStat(DialStatKind.EMOTION)
                                }
                            }
                            topTheme?.let {
                                DialStatCard("主导主题", it.name, it.total, cyan, Modifier.weight(1f)) {
                                    onOpenStat(DialStatKind.THEME)
                                }
                            }
                        }
                    }
                }

                // ===== 时间卷 =====
                item(key = "timeline") {
                    DialTimeline(
                        segSections = segSections,
                        dominant = aggregate.dominantEmotions,
                        onOpenTick = onOpenTick
                    )
                }

                // ===== 未归属（轴尾的虚线节点） =====
                if (unassigned != null) {
                    item(key = "un_head") {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                                .clickable { expandUnassigned = !expandUnassigned },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(Modifier.width(22.dp), contentAlignment = Alignment.TopCenter) {
                                Box(
                                    Modifier.padding(top = 3.dp).size(10.dp).clip(CircleShape)
                                        .border(
                                            1.5.dp,
                                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.28f),
                                            CircleShape
                                        )
                                )
                            }
                            Text(
                                "未归属",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                "${unassigned.records.size} 条 ${if (expandUnassigned) "收起" else "›"}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    if (expandUnassigned) {
                        items(unassigned.records, key = { "un_${it.id}" }) { r ->
                            Box(Modifier.padding(start = 38.dp, end = 16.dp)) {
                                DialRecordRow(r, onOpen = { onOpenRecord(r.id) })
                            }
                        }
                    }
                }

                // ===== 期的后记 =====
                item(key = "summary") {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                        HorizontalDivider(
                            thickness = 0.5.dp,
                            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f),
                            modifier = Modifier.padding(top = 16.dp, bottom = 6.dp)
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "后记",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(onClick = {
                                // 2026-11-23：先把 dial 最新 summary 拉回来，再进编辑器，
                                // 避免用旧 state 打开成空草稿。
                                scope.launch { reload(); editingSummary = true }
                            }) {
                                Text(
                                    if (dial?.summary.isNullOrBlank()) "写" else "编辑",
                                    color = violet,
                                    style = MaterialTheme.typography.labelLarge
                                )
                            }
                        }
                        val text = dial?.summary.orEmpty()
                        if (text.isNotBlank()) {
                    Text(
                        text,
                        style = memore.app.ui.theme.MemoreType.reading,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(bottom = 8.dp)
                            )
                        }
                    }
                }
            }
        }
    }

    if (showAddTicks) {
        val candidates = allTicks.filter { t -> ticks.none { it.id == t.id } }
        AddTicksDialog(
            candidates = candidates,
            onDismiss = { showAddTicks = false },
            onPick = { tick ->
                scope.launch { dialRepository.linkTick(dialId, tick.id); reload() }
            }
        )
    }
    if (showEdit) {
        dial?.let { cur ->
            DialEditDialog(
                title = "编辑表盘",
                initialName = cur.name,
                initialStart = cur.startMs,
                initialEnd = cur.endMs,
                onDismiss = { showEdit = false },
                onConfirm = { name, start, end ->
                    scope.launch {
                        dialRepository.renameDial(cur.id, name)
                        if (start != cur.startMs || end != cur.endMs) dialRepository.updateDialBounds(cur.id, start, end)
                        showEdit = false
                        reload()
                    }
                }
            )
        }
    }
    val deleting = dial
    if (confirmDelete && deleting != null) {
        MirrorStackDialog(
            title = "删除表盘「${deleting.name}」？",
            message = "只删这个框：里面的刻度一个不动，记录也不动。",
            confirmText = "删除",
            destructive = true,
            onDismiss = { confirmDelete = false },
            onConfirm = {
                scope.launch {
                    dialRepository.deleteDial(deleting.id)
                    confirmDelete = false
                    UiSoundPlayer.playTickDelete(context)
                    onBack()
                }
            }
        )
    }
}

/** 两格汇总里的一格：标签 + 主导项 + 次数。 */
@Composable
private fun DialStatCard(
    label: String,
    value: String,
    count: Int,
    hue: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Column(
        modifier
            .clip(MirrorShapes.card)
            .background(hue.copy(alpha = 0.10f))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(top = 4.dp)) {
            Text(
                value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                "$count",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.weight(1f))
            Text(
                "›",
                style = MaterialTheme.typography.bodyLarge,
                color = hue
            )
        }
    }
}

/**
 * 时间卷：一条竖轴，每段一个节点。
 *
 * 节点 = 进行中实心青点 / 已定格空心紫点（空心填页面底色，正好把轴遮断，看起来像"钉在轴上"）。
 * 每段三段信息：**名字 + 条数** / **起止** / **主导情绪 + 后记状态**。
 */
@Composable
private fun DialTimeline(
    segSections: List<DialSection>,
    dominant: List<String>,
    onOpenTick: (String) -> Unit
) {
    if (segSections.isEmpty()) {
        Text(
            "还没有刻度",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )
        return
    }
    // 只用 App 自己的两个强调色：情绪＝主题紫，主题＝协议青（4DD0E1，全 App 88 处都在用它）
    val violet = MaterialTheme.colorScheme.primary.copy(alpha = 0.62f)
    val cyan = Color(0xFF4DD0E1).copy(alpha = 0.62f)
    // 竖轴：用 onSurface 的低透明度（outline 太浅，实测在米色底上几乎看不见）
    val hair = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.16f)
    val bg = MaterialTheme.colorScheme.background
    val axisX = 10.25.dp
    val axisTop = 9.dp
    val axisBottom = 6.dp

    Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Column(
            Modifier.drawBehind {
                val x = axisX.toPx()
                drawLine(
                    color = hair,
                    start = Offset(x, axisTop.toPx()),
                    end = Offset(x, size.height - axisBottom.toPx()),
                    strokeWidth = 2.dp.toPx()
                )
            }
        ) {
            segSections.forEachIndexed { i, section ->
                val tick = section.tick ?: return@forEachIndexed
                val ongoing = tick.endMs == null
                val accent = if (ongoing) cyan else violet
                val dom = dominant.getOrNull(i).orEmpty()
                Row(Modifier.fillMaxWidth().clickable { onOpenTick(tick.id) }) {
                    Box(Modifier.width(22.dp), contentAlignment = Alignment.TopCenter) {
                        Box(
                            Modifier.padding(top = 4.dp).size(10.dp).clip(CircleShape).then(
                                if (ongoing) Modifier.background(accent)
                                else Modifier.background(bg).border(1.5.dp, accent, CircleShape)
                            )
                        )
                    }
                    Column(Modifier.weight(1f).padding(bottom = 17.dp)) {
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text(
                                tick.name,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                "${section.records.size}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text(
                            "${formatDateOnly(tick.startMs)} – ${tick.endMs?.let { formatDateOnly(it) } ?: "至今"}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(top = 3.dp)
                        ) {
                            Text(
                                if (dom.isNotBlank()) "主导 · $dom" else "",
                                style = MaterialTheme.typography.labelMedium,
                                color = violet,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                if (tick.summary.isNotBlank()) "后记 · ${tick.summary.length} 字" else "后记 · 未写",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (tick.summary.isNotBlank()) violet
                                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DialRecordRow(r: Record, onOpen: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onOpen() }.padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(recordTypeLabel(r.type), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text(formatTimestamp(r.createdAt), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(3.dp))
            Text(
                r.content.ifBlank { r.notes }.ifBlank { "（空内容）" },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2
            )
        }
    }
    HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
}

/** 后记编辑：整页一个输入框（长文，不用弹窗）。 */
@Composable
private fun DialSummaryEditor(
    summary: String,
    summaryPrev: String,
    onCancel: () -> Unit,
    onSave: (String) -> Unit,
    onRestorePrev: () -> Unit
) {
    var draft by remember(summary) { mutableStateOf(summary) }
    // 2026-11-23：和刻度总结一样，编辑器可能在 dial 还没加载完时就组合，
    // remember 拿到空串后就再也不更新。等 summary 到达时把草稿补上。
    LaunchedEffect(summary) { if (draft.isBlank()) draft = summary }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("后记", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { onRestorePrev() }, enabled = summaryPrev.isNotBlank() && summaryPrev != summary) {
                Text("恢复上一版")
            }
            TextButton(onClick = onCancel) { Text("取消") }
            TextButton(onClick = { onSave(draft) }) { Text("保存") }
        }
        // 2026-11-23：长文不能用 MemoreLineInput —— 它是贴底的单行控件，
        // 515 字的正文会整段被挤到看不见。和刻度总结一样用满高 BasicTextField。
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
                            "写给这一段时期。想写多长写多长。",
                            style = memore.app.ui.theme.MemoreType.reading,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                        )
                    }
                    inner()
                }
            }
        )
        Text(
            "${draft.length} 字",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 6.dp)
        )
    }
}

/** 把还没进这个框的刻度加进来（多父：已经在别的框里也能加）。 */
@Composable
private fun AddTicksDialog(
    candidates: List<Tick>,
    onDismiss: () -> Unit,
    onPick: (Tick) -> Unit
) {
    MirrorAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("加刻度") },
        text = {
            if (candidates.isEmpty()) {
                Text("没有可加的刻度了", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(candidates, key = { it.id }) { t ->
                        Column(
                            Modifier.fillMaxWidth().clickable { onPick(t) }.padding(vertical = 10.dp)
                        ) {
                            Text(t.name, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                            Text(
                                "${formatDateOnly(t.startMs)} – ${t.endMs?.let { formatDateOnly(it) } ?: "至今"}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } }
    )
}

/** 新建 / 编辑表盘：名字 + 起止（终点可"进行中"）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DialEditDialog(
    title: String,
    initialName: String,
    initialStart: Long,
    initialEnd: Long?,
    onDismiss: () -> Unit,
    onConfirm: (name: String, startMs: Long, endMs: Long?) -> Unit
) {
    var name by remember { mutableStateOf(initialName) }
    var startMs by remember { mutableStateOf(initialStart) }
    var endMs by remember { mutableStateOf(initialEnd) }
    var ongoing by remember { mutableStateOf(initialEnd == null) }
    var picker by remember { mutableStateOf<String?>(null) }

    val effectiveEnd: Long? = if (ongoing) null else endMs
    val rangeError: String? = when {
        ongoing -> null
        effectiveEnd == null -> "请选择终点日期，或打开「进行中」"
        effectiveEnd <= startMs -> "终点必须晚于起点"
        else -> null
    }

    MirrorAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                MemoreLineInput(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    placeholder = { Text("如：2026 / 这十一个月") },
                    shape = MirrorShapes.row,
                    modifier = Modifier.fillMaxWidth()
                )
                DialDateField(label = "起点", value = formatDateOnly(startMs), onClick = { picker = "START" })
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "进行中（这一段还没结束）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    Switch(checked = ongoing, onCheckedChange = { ongoing = it })
                }
                if (!ongoing) {
                    DialDateField(
                        label = "终点",
                        value = endMs?.let { formatDateOnly(it) } ?: "未选择",
                        onClick = { picker = "END" }
                    )
                }
                rangeError?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name, startMs, effectiveEnd) }, enabled = rangeError == null) { Text("确定") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )

    picker?.let { kind ->
        val initial = if (kind == "START") startMs else (endMs ?: startMs)
        val pickerState = rememberDatePickerState(initialSelectedDateMillis = localMillisToDatePicker(initial))
        DatePickerDialog(
            onDismissRequest = { picker = null },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let { ms ->
                        val local = datePickerToLocalStartOfDay(ms)
                        if (kind == "START") startMs = local else endMs = local
                    }
                    picker = null
                }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { picker = null }) { Text("取消") } }
        ) { DatePicker(state = pickerState) }
    }
}

/** 日期那一行（表盘自己的，不去借刻度页的私有控件）。 */
@Composable
private fun DialDateField(label: String, value: String, onClick: () -> Unit) {
    Surface(
        shape = MirrorShapes.row,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
        border = mirrorHairline(),
        onClick = onClick
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.weight(1f))
            Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
        }
    }
}

