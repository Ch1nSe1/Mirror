@file:OptIn(ExperimentalMaterial3Api::class)

package memore.app.ui.screens

import memore.app.ui.components.MirrorStackDialog
import memore.app.ui.components.MemoreLineInput
import memore.app.ui.components.IconDialFrame
import memore.app.ui.components.IconBiteSecond
import androidx.compose.material3.IconButton
import androidx.compose.material3.DropdownMenuItem
import android.widget.Toast
import memore.app.ui.components.MirrorCardElevation
import memore.app.ui.components.MirrorShapes
import memore.app.ui.components.mirrorHairline
import memore.app.ui.components.mirrorPillBorder
import memore.app.ui.components.MirrorDropdownMenu
import memore.app.ui.components.MirrorAlertDialog
import memore.app.ui.components.MirrorMenuAction
import memore.app.ui.components.MirrorMenu
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import memore.app.data.model.Dial
import memore.app.data.model.Tick
import memore.app.data.repository.DialRepository
import memore.app.data.repository.TickRepository
import memore.app.data.repository.ticksOverlap
import memore.app.utils.UiSoundPlayer
import memore.app.utils.datePickerToLocalStartOfDay
import memore.app.utils.formatDateOnly
import memore.app.utils.formatTimestamp
import memore.app.utils.localMillisToDatePicker
import memore.app.utils.memoreToast
import kotlinx.coroutines.launch

/** 秒针 tab：刻度管理屏。刻度只是时间区间注解，不拥有记录。 */
private data class PendingTickEdit(val tick: Tick, val name: String, val start: Long, val end: Long?)

@Composable
fun TickScreen(
    tickRepository: TickRepository,
    onOpenTick: (Tick) -> Unit,
    /** 表盘是独立的一层；这里只借它"列一下"和"新建一个"，不碰刻度语义。 */
    dialRepository: DialRepository? = null,
    onOpenDial: (String) -> Unit = {},
    refreshKey: Int = 0,
    onNewTick: () -> Unit = {},
    onNewDial: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var ticks by remember { mutableStateOf<List<Tick>>(emptyList()) }
    var dials by remember { mutableStateOf<List<Dial>>(emptyList()) }
    var showCreate by remember { mutableStateOf(false) }
    var showCreateDial by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Tick?>(null) }
    var deleting by remember { mutableStateOf<Tick?>(null) }
    /** 已定格刻度 + 改了起止 → 待用户确认"接受覆盖定格快照"的改动。 */
    var confirmRefreeze by remember { mutableStateOf<PendingTickEdit?>(null) }
    /**
     * 首帧保护：ticks 初值是空列表、reload() 又是异步的，
     * 不加这个标记的话第一帧会把「还没有刻度」空态画出来再被列表顶掉 —— 一瞬闪。
     * （以前空态是个空白 Box，同样有这一帧但看不见；空态有内容后必须显式区分"还没加载"和"真没有"。）
     */
    var loaded by remember { mutableStateOf(false) }

    suspend fun reload() {
        ticks = tickRepository.getActive()
        dials = dialRepository?.allDials().orEmpty()
        loaded = true
    }

    /** 保存编辑：改名与改起止分开走（改起止会重新定格，故调用前可能先要一次确认）。 */
    suspend fun saveEdit(t: Tick, name: String, start: Long, end: Long?) {
        if (name != t.name) tickRepository.rename(t.id, name)
        if (start != t.startMs || end != t.endMs) tickRepository.updateBounds(t.id, start, end)
        reload()
        UiSoundPlayer.playTickCreate(context)
    }

    LaunchedEffect(refreshKey) { reload() }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            // 2026-11-14：删掉这里额外的 6dp —— TopAppBar 自己已经让出状态栏了，
            // 加上这一条会让「秒针」的页头比「设置」「回顾」低 6dp（四屏顶距不一致）。
            // 顶栏：标题 + 两个**纯文字动作**（2026-10-20 改）。
            // 药丸在这里突兀（同一行两颗药丸会被读成分段控件），裸字又太素 ——
            // 走 M3 的 text button 形制：无底色、靠**字重+颜色**分主次、按下才出现 8% 圆角底。
            // 页头：和设置页同一套 TopAppBar（标题 18 无衬线、高度 64、左内边距 16）
            TopAppBar(
                title = { LiveClock() },
                actions = {
                    var topMenu by remember { mutableStateOf(false) }
                    Box {
                        IconButton(onClick = { topMenu = true }) {
                            Icon(
                                Icons.Outlined.MoreVert, "更多", Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        MirrorMenu(
                            expanded = topMenu,
                            onDismissRequest = { topMenu = false },
                            actions = listOf(
                                MirrorMenuAction("新建表盘", icon = IconDialFrame) { topMenu = false; onNewDial() },
                                MirrorMenuAction("咬紧秒针", icon = IconBiteSecond) { topMenu = false; onNewTick() }
                            )
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
            Spacer(Modifier.height(10.dp))

            // 表盘：一段时期的框（包络刻度）。做成**一行链接**，不再用 chip ——
            // 它是容器层，不该长得像卡片内容（同形状的淡紫药丸叠在卡片上方最容易错位）。
            if (loaded && dials.isNotEmpty()) {
                Column {
                    dials.forEach { d ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(MirrorShapes.row)
                                .clickable { onOpenDial(d.id) }
                                .padding(horizontal = 6.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "表盘",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.width(8.dp))
                            if (d.endMs == null) {
                                Box(Modifier.size(5.dp).clip(CircleShape).background(Color(0xFF4DD0E1)))
                                Spacer(Modifier.width(6.dp))
                            }
                            Text(
                                d.name,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            Icon(
                                Icons.Outlined.KeyboardArrowRight,
                                null,
                                Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                            )
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
            }

            if (!loaded) {
                // 还没拿到数据：留白，不要画空态（否则会闪一下"还没有刻度"）
                Box(Modifier.fillMaxWidth().weight(1f)) {}
            } else if (ticks.isEmpty()) {
                // 没有刻度时什么都不画（2026-11-05）：空态那堆图标+解释+按钮用户嫌丑，新建入口在右上 ⋮ 里
                Box(Modifier.fillMaxWidth().weight(1f)) {}
            } else {
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(0.dp)) {
                    items(ticks, key = { it.id }) { tick ->
                        TickCard(
                            tick = tick,
                            weight = tickRepository.computeWeight(tick),
                            onOpen = { onOpenTick(tick) },
                            onEdit = { editing = tick },
                            onDelete = { deleting = tick }
                        )
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }

        if (showCreateDial) {
            DialEditDialog(
                title = "新建表盘",
                initialName = "",
                initialStart = System.currentTimeMillis(),
                initialEnd = null,
                onDismiss = { showCreateDial = false },
                onConfirm = { name, start, end ->
                    scope.launch {
                        val dial = dialRepository?.createDial(name, start, end) ?: return@launch
                        reload()
                        showCreateDial = false
                        UiSoundPlayer.playTickCreate(context)
                        onOpenDial(dial.id)
                    }
                }
            )
        }
        if (showCreate) {
            TickEditDialog(
                title = "新建刻度",
                initialName = "",
                initialStart = System.currentTimeMillis(),
                initialEnd = null,
                otherTicks = ticks,
                selfId = null,
                onDismiss = { showCreate = false },
                onConfirm = { name, start, end ->
                    scope.launch {
                        tickRepository.create(name, start, end)
                        reload(); showCreate = false
                        UiSoundPlayer.playTickCreate(context)
                    }
                }
            )
        }
        editing?.let { t ->
            TickEditDialog(
                title = "编辑刻度",
                initialName = t.name,
                initialStart = t.startMs,
                initialEnd = t.endMs,
                otherTicks = ticks,
                selfId = t.id,
                onDismiss = { editing = null },
                onConfirm = { name, start, end ->
                    val boundsChanged = start != t.startMs || end != t.endMs
                    if (boundsChanged && !t.sliceSnapshot.isNullOrBlank()) {
                        // 已定格的刻度改起止 → 会用当前数据重新定格、覆盖原快照，先确认
                        editing = null
                        confirmRefreeze = PendingTickEdit(t, name, start, end)
                    } else {
                        scope.launch { saveEdit(t, name, start, end); editing = null }
                    }
                }
            )
        }
        confirmRefreeze?.let { p ->
            MirrorStackDialog(
                title = "会覆盖定格快照",
                message = "「${p.tick.name}」已经定格。改时间范围会重新定格，原来的那份快照" +
                    (p.tick.frozenAt?.let { "（${formatTimestamp(it)}）" } ?: "") +
                    "会被覆盖，且不可恢复。",
                confirmText = "仍然修改",
                destructive = true,
                onDismiss = { confirmRefreeze = null },
                onConfirm = {
                    val q = p
                    confirmRefreeze = null
                    scope.launch { saveEdit(q.tick, q.name, q.start, q.end) }
                }
            )
        }
        deleting?.let { t ->
            MirrorStackDialog(
                title = "删除刻度「${t.name}」？",
                message = "删除后可到回收站恢复。",
                confirmText = "删除",
                destructive = true,
                onDismiss = { deleting = null },
                onConfirm = { scope.launch { tickRepository.delete(t.id); reload(); deleting = null; UiSoundPlayer.playTickDelete(context) } }
            )
        }
    }
}

/**
 * 纯文字动作（M3 text button 形制）。
 *
 * 这里不用药丸也不用按钮底：同一行两颗药丸会被读成"分段控件"；裸字又太素。
 * 做法是 **靠字重 + 颜色分主次**，**按下才出现 8% 圆角底**（ripple 自带），
 * 上下 8dp / 左右 10dp 的点击区保证热区够大而视觉不变。
 */
@Composable
private fun TickTextAction(label: String, primary: Boolean, onClick: () -> Unit) {
    Text(
        label,
        style = MaterialTheme.typography.labelLarge.copy(letterSpacing = 0.2.sp),
        fontWeight = if (primary) FontWeight.SemiBold else FontWeight.Medium,
        color = if (primary) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .clip(MirrorShapes.row)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp)
    )
}

/**
 * 秒针 Tab 顶部的一颗"表盘"chip：只有名字（起止在表盘页里看），进行中的在名字前点一个小青点。
 * 做成 chip 而不是卡片：表盘是**容器**，刻度才是这一页的内容 —— 两层不该长得一样重。
 */
@Composable
private fun DialChip(dial: Dial, onOpen: () -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    Surface(shape = MirrorShapes.pill, color = accent.copy(alpha = 0.12f), border = mirrorPillBorder(accent), onClick = onOpen) {
        Row(
            Modifier.padding(start = 12.dp, end = 14.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (dial.endMs == null) {
                Box(Modifier.size(6.dp).clip(CircleShape).background(Color(0xFF4DD0E1)))
                Spacer(Modifier.width(7.dp))
            }
            Text(
                dial.name,
                    style = androidx.compose.ui.text.TextStyle(
                        fontSize = 17.sp,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Serif,
                        letterSpacing = 0.3.sp
                    ),
                color = accent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun TickCard(
    tick: Tick,
    weight: Double,
    onOpen: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    var menu by remember { mutableStateOf(false) }
    val endText = tick.endMs?.let { formatDateOnly(it) } ?: "至今"
    val accent = if (tick.endMs == null) Color(0xFF4DD0E1) else Color(0xFFA78BFA)   // 进行中=协议青，已定格=透亮紫
    val fill = (weight.toFloat()).coerceIn(0.04f, 1f)
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                tick.name,
                fontSize = 14.5.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).clickable { onOpen() }
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "${formatDateOnly(tick.startMs)} → $endText",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                modifier = Modifier.clickable { onOpen() }
            )
            Box {
                IconButton(onClick = { menu = true }, modifier = Modifier.size(30.dp)) {
                    Icon(Icons.Outlined.MoreVert, "菜单", Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                MirrorMenu(
                    expanded = menu,
                    onDismissRequest = { menu = false },
                    actions = listOf(
                        MirrorMenuAction("编辑") { menu = false; onEdit() },
                        MirrorMenuAction("删除", danger = true) { menu = false; onDelete() }
                    )
                )
            }
        }
        val hasSummary = tick.summary.isNotBlank()
        Row(
            Modifier.fillMaxWidth().padding(bottom = 9.dp).clickable { onOpen() },
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                if (tick.endMs == null) "进行中" else "已定格",
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = accent
            )
            Spacer(Modifier.weight(1f))
            // 两列定宽 + 右对齐 + 等宽数字：不同字数/权重下都能对齐
            Text(
                "权重 " + String.format("%.2f", weight),
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.End,
                maxLines = 1,
                style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
                modifier = Modifier.width(62.dp)
            )
            Spacer(Modifier.width(12.dp))
            Text(
                if (hasSummary) "总结 ${tick.summary.length} 字" else "总结 未写",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (hasSummary) 1f else 0.65f),
                textAlign = TextAlign.End,
                maxLines = 1,
                style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
                modifier = Modifier.width(84.dp)
            )
        }
        Box(
            Modifier.fillMaxWidth().height(1.dp)
                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f))
        )
    }
}
/** 新建/编辑刻度弹层：名称 + 起点/终点日期（终点可"进行中"=null）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TickEditDialog(
    title: String,
    initialName: String,
    initialStart: Long,
    initialEnd: Long?,
    /** 现有活跃刻度（用于重叠提示）；[selfId] 是正在编辑的刻度 id，编辑时排除自己。 */
    otherTicks: List<Tick> = emptyList(),
    selfId: String? = null,
    onDismiss: () -> Unit,
    onConfirm: (name: String, startMs: Long, endMs: Long?) -> Unit
) {
    var name by remember { mutableStateOf(initialName) }
    var startMs by remember { mutableStateOf(initialStart) }
    var endMs by remember { mutableStateOf(initialEnd) }
    var ongoing by remember { mutableStateOf(initialEnd == null) }
    var picker by remember { mutableStateOf<String?>(null) }   // "START" | "END" | null
    var error by remember { mutableStateOf<String?>(null) }

    fun pick(kind: String) { picker = kind }

    // 区间校验：终点必须是真实选择且严格晚于起点（同一天 = 零长度区间，永远为空）
    val effectiveEnd: Long? = if (ongoing) null else endMs
    val rangeError: String? = when {
        ongoing -> null
        effectiveEnd == null -> "请选择终点日期，或打开「进行中」"
        effectiveEnd <= startMs -> "终点必须晚于起点（同一天是零长度区间，不会包含任何记录）"
        else -> null
    }
    // 重叠提示：重叠允许存在，但必须说清后果（App 内都算、导出只归一条）
    val overlaps: List<Tick> = if (rangeError != null) emptyList() else
        otherTicks.filter { it.id != selfId && ticksOverlap(startMs, effectiveEnd, it.startMs, it.endMs) }

    MirrorAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                MemoreLineInput(value = name, onValueChange = { name = it; error = null }, singleLine = true, label = { Text("刻度名") }, placeholder = { Text("如：大学 / 2023）") }, shape = MirrorShapes.row, modifier = Modifier.fillMaxWidth())
                ClickableDateField(label = "起点", value = formatDateOnly(startMs), onClick = { pick("START") })
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("进行中（暂无终点）", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                    Switch(checked = ongoing, onCheckedChange = { ongoing = it; error = null })
                }
                if (!ongoing) {
                    ClickableDateField(label = "终点", value = endMs?.let { formatDateOnly(it) } ?: "未选择", onClick = { pick("END") })
                }
                (error ?: rangeError)?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                }
                if (overlaps.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            "⚠ 与「${overlaps.joinToString("」「") { it.name }}」的时间区间重叠",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFFE6C15A)
                        )
                        Text(
                            "重叠是允许的，但要知道：App 里这段记录会同时算进两条刻度，" +
                                "而导出给外部 AI 时只会归给**起点更晚**的那条。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val err = when {
                    name.isBlank() -> "请输入刻度名"
                    else -> rangeError
                }
                if (err != null) { error = err; return@TextButton }
                onConfirm(name.trim(), startMs, effectiveEnd)
            }) { Text("确定") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )

    if (picker != null) {
        // key(picker)：起点/终点切换时必须重新初始化，不能沿用上一次性选择器的状态
        key(picker) {
            val isStart = picker == "START"
            val initial = if (isStart) startMs else (endMs ?: startMs)
            // 选择器按 UTC 解读初始值，必须先把本地时间戳转成"该日的 UTC 零点"
            val state = rememberDatePickerState(initialSelectedDateMillis = localMillisToDatePicker(initial))
            DatePickerDialog(
                onDismissRequest = { picker = null },
                confirmButton = {
                    TextButton(onClick = {
                        // 反过来：选择器给的是"该日的 UTC 零点"，要转成本地零点再存
                        state.selectedDateMillis?.let {
                            val local = datePickerToLocalStartOfDay(it)
                            if (isStart) startMs = local else endMs = local
                        }
                        error = null; picker = null
                    }) { Text("确定") }
                },
                dismissButton = { TextButton(onClick = { picker = null }) { Text("取消") } }
            ) {
                DatePicker(state = state)
            }
        }
    }
}

@Composable
private fun ClickableDateField(label: String, value: String, onClick: () -> Unit) {
    Surface(shape = MirrorShapes.row, color = MaterialTheme.colorScheme.surfaceContainerHigh, border = mirrorHairline(), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp).clickable { onClick() }, verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(12.dp))
            Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
        }
    }
}
/**
 * 「此刻」：秒针页左上角的时间，每秒跳一格。
 *
 * 不需要联网——用的是设备自己的时钟（系统已经 NTP 对过时）；
 * 只在 1dp 的 Text 上每秒重组一次，功耗可忽略。
 * 等宽数字（tnum），避免秒数跳动时整行左右抖。
 */
@Composable
private fun LiveClock() {
    val hm = remember { java.time.format.DateTimeFormatter.ofPattern("HH:mm") }
    val ss = remember { java.time.format.DateTimeFormatter.ofPattern("ss") }
    var now by remember { mutableStateOf(java.time.LocalTime.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = java.time.LocalTime.now()
            // 对齐到下一个整秒再刷新
            kotlinx.coroutines.delay(1000 - System.currentTimeMillis() % 1000)
        }
    }
    Row(verticalAlignment = Alignment.Bottom) {
        Text(
            now.format(hm),
            style = memore.app.ui.theme.MemoreType.meta.copy(
                fontFeatureSettings = "tnum",
                letterSpacing = 0.5.sp
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.width(7.dp))
        Text(
            now.format(ss),
            style = androidx.compose.ui.text.TextStyle(
                fontSize = 22.sp,
                lineHeight = 24.sp,
                fontWeight = FontWeight.Medium,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Serif,
                letterSpacing = 0.5.sp
            ),
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}
