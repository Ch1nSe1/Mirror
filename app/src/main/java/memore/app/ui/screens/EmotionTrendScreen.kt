package memore.app.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import memore.app.data.model.EmotionSeries
import memore.app.data.model.EmotionSeriesBuilder
import memore.app.data.model.EmotionStat
import memore.app.data.model.EmotionStats
import memore.app.data.repository.RecordRepository
import memore.app.ui.components.EmotionLineChart
import memore.app.ui.components.IconBack
import memore.app.ui.components.IconTrendChart
import memore.app.ui.components.MirrorMenu
import memore.app.ui.components.MirrorMenuAction
import memore.app.ui.components.MirrorDropdownMenu
import memore.app.ui.components.bucketLabel
import memore.app.ui.components.MirrorShapes
import memore.app.ui.components.mirrorHairline
import memore.app.utils.formatDateOnly
import memore.app.utils.datePickerToLocalStartOfDay
import memore.app.utils.localMillisToDatePicker
import kotlinx.coroutines.launch
import kotlin.math.pow

/** 时间范围预设（自定义放最右）。 */
private enum class TrendRange(val label: String, val days: Int?) {
    D30("近30天", 30),
    D180("近半年", 180),
    ALL("全部", null),          // 从第一条记录到今天
    CUSTOM("自定义", null)       // 自己选起止（跨度不得超过一年）
}

private const val DAY_MS = 24L * 60 * 60 * 1000

/** 自定义跨度上限：一年（用户定的规则）。 */
private const val MAX_CUSTOM_DAYS = 365

/**
 * 情绪走势（折线）。
 *
 * 与「情绪分析图」的分工：那个是**排行榜**（谁最重），这个是**走势**（谁在涨、谁在落）。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun EmotionTrendScreen(
    recordRepository: RecordRepository,
    onBack: () -> Unit,
    onViewEmotionRecords: (emotionTag: String) -> Unit
) {
    val scope = rememberCoroutineScope()
    var allRecords by remember { mutableStateOf<List<memore.app.data.model.Record>>(emptyList()) }
    var stats by remember { mutableStateOf<List<EmotionStat>>(emptyList()) }
    var range by rememberSaveable(stateSaver = androidx.compose.runtime.saveable.Saver<TrendRange, String>(
        save = { it.name }, restore = { TrendRange.valueOf(it) })) { mutableStateOf(TrendRange.D180) }
    var selected by rememberSaveable(stateSaver = androidx.compose.runtime.saveable.listSaver<List<String>, String>(
        save = { it }, restore = { it })) { mutableStateOf<List<String>>(emptyList()) }
    // 2026-11-22：主情绪从 selected 的顺序里拆出来。
    // 加情绪不再换主情绪；只有删掉主情绪才回退到最近加的那个；也可以点卡片里的情绪行直接指定。
    var primary by rememberSaveable { mutableStateOf<String?>(null) }
    var tapped by remember { mutableStateOf<Long?>(null) }
    var loaded by remember { mutableStateOf(false) }
    // 自定义起止（都存"所选日期的本地零点"，与导出那边同一套约定）
    var customStart by rememberSaveable(stateSaver = androidx.compose.runtime.saveable.listSaver<Long?, Long>(
        save = { listOfNotNull(it) }, restore = { it.firstOrNull() })) { mutableStateOf<Long?>(null) }
    var customEnd by rememberSaveable(stateSaver = androidx.compose.runtime.saveable.listSaver<Long?, Long>(
        save = { listOfNotNull(it) }, restore = { it.firstOrNull() })) { mutableStateOf<Long?>(null) }
    var showStartPicker by remember { mutableStateOf(false) }
    var showEndPicker by remember { mutableStateOf(false) }
    var rangeHint by remember { mutableStateOf<String?>(null) }
    var showAllEmotions by rememberSaveable { mutableStateOf(false) }

    // 用户定的五色：血红 / 翠绿 / 蓝 / 透亮紫 / 橘黄。
    // 刻意**不用 MaterialTheme.colorScheme.primary** —— 主题色本身就是紫，
    // 和"透亮紫"撞在一起，之前就是因此出现两条紫线。
    val palette = listOf(
        Color(0xFFE53935), // 血红
        Color(0xFF10B981), // 翠绿
        Color(0xFF3B82F6), // 蓝
        Color(0xFFB388FF), // 透亮紫
        Color(0xFFFFA726)  // 橘黄
    )
    // 情绪 → 调色板下标：颜色**跟着情绪走**（会话内稳定），不跟着"选中的顺序"走 ——
    // 否则取消一条，剩下几条会整体换色，反而更难认。
    val colorIndexOf = remember { mutableStateMapOf<String, Int>() }
    fun colorOf(name: String): Color = palette[(colorIndexOf[name] ?: 0) % palette.size]

    LaunchedEffect(Unit) {
        val recs = try { recordRepository.getRecordsWithEmotion() } catch (_: Exception) { emptyList() }
        allRecords = recs
        stats = EmotionStats.parse(recs).sortedByDescending { it.weight }
        // 默认选「最近 7 天真的写过的情绪」，而不是历史权重最高的前三个。
        // 2026-11-22 真机问题：按 weight / 近 30 天取默认，会选到最近一周没出现的情绪，
        // 进入页面后最新桶密度全是 0.00，看起来像读数坏了。
        val weekAgo = System.currentTimeMillis() - 7 * 24 * 60 * 60 * 1000
        val recentCount = HashMap<String, Int>()
        recs.filter { it.createdAt >= weekAgo }.forEach { r ->
            EmotionStats.emotionEntries(r).map { it.name }.distinct()
                .forEach { n -> recentCount[n] = (recentCount[n] ?: 0) + 1 }
        }
        val first = if (recentCount.isNotEmpty()) {
            recentCount.entries.sortedByDescending { it.value }.take(3).map { it.key }
        } else {
            stats.sortedWith(
                compareByDescending<EmotionStat> { it.last30DaysCount }.thenByDescending { it.weight }
            ).take(3).map { it.name }
        }
        first.forEachIndexed { i, n -> colorIndexOf[n] = i }
        selected = first
        primary = first.firstOrNull()
        loaded = true
    }

    // 范围 → 起止（自定义时用用户选的起止；未选则先给个近30天的默认，不空图）
    val now = remember(allRecords, range) { System.currentTimeMillis() }
    val startMs = remember(allRecords, range, now, customStart) {
        when (range) {
            TrendRange.D30 -> now - 30 * DAY_MS
            TrendRange.D180 -> now - 180 * DAY_MS
            TrendRange.ALL -> allRecords.minOfOrNull { it.createdAt } ?: (now - 90 * DAY_MS)
            TrendRange.CUSTOM -> customStart ?: (now - 30 * DAY_MS)
        }
    }
    val endMs = remember(range, now, customEnd) {
        if (range == TrendRange.CUSTOM) customEnd ?: now else now
    }
    val bucket = remember(startMs, endMs) { EmotionSeriesBuilder.bucketFor(endMs - startMs) }
    // 按天时套"居中平滑窗口"（治"一天只有 1~3 条 → 密度在 0 和 1 之间跳"）；
    // 周/月桶分母已经够大 → 返回 1（不平滑）。窗口大小见 smoothingWindowFor。
    val window = remember(startMs, endMs, bucket) {
        EmotionSeriesBuilder.smoothingWindowFor(endMs - startMs, bucket)
    }
    val series: List<EmotionSeries> = remember(allRecords, selected, startMs, endMs, bucket, window) {
        EmotionSeriesBuilder.build(allRecords, selected, startMs, endMs, bucket, window)
    }
    // 2026-11-22：没点过图时，卡片默认落在最后一个有数据的桶；刚进页面也能看到卡片。
    val defaultTs: Long? = remember(series) {
        series.flatMap { it.points }.filter { it.hasValue }.maxOfOrNull { it.bucketStart }
    }

    // 自定义日期选择器：两端都做时区换算（选择器给/收的都是"该日历日的 UTC 零点"）
    if (showStartPicker) {
        val pickerState = rememberDatePickerState(initialSelectedDateMillis = localMillisToDatePicker(startMs))
        DatePickerDialog(
            onDismissRequest = { showStartPicker = false },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let { picked ->
                        val v = datePickerToLocalStartOfDay(picked)
                        // 跨度不得超过一年 → 起点不能早于"终点 - 365 天"
                        val floor = (customEnd ?: now) - MAX_CUSTOM_DAYS * DAY_MS
                        if (v < floor) {
                            customStart = floor
                            rangeHint = "自定义跨度最多一年，起点已收到一年前"
                        } else {
                            customStart = v
                            rangeHint = null
                        }
                    }
                    showStartPicker = false
                }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { showStartPicker = false }) { Text("取消") } }
        ) { DatePicker(state = pickerState) }
    }
    if (showEndPicker) {
        val pickerState = rememberDatePickerState(initialSelectedDateMillis = localMillisToDatePicker(endMs))
        DatePickerDialog(
            onDismissRequest = { showEndPicker = false },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let { picked ->
                        val v = datePickerToLocalStartOfDay(picked)
                        // 终点不能晚于"起点 + 365 天"
                        val ceil = startMs + MAX_CUSTOM_DAYS * DAY_MS
                        if (v > ceil) {
                            customEnd = ceil
                            rangeHint = "自定义跨度最多一年，终点已收到起点后一年内"
                        } else {
                            customEnd = v
                            rangeHint = null
                        }
                    }
                    showEndPicker = false
                }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { showEndPicker = false }) { Text("取消") } }
        ) { DatePicker(state = pickerState) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "情绪",
                        style = memore.app.ui.theme.MemoreType.pageTitleSans,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(IconBack, "返回", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {

            // ---- 单焦读数头（一次只读一条：当前点 + 变化 + 峰值）----
            // 2026-11-22：主情绪 = primary；读数跟随**点选的桶**（没点则用默认桶）。
            val focusName = primary ?: selected.firstOrNull()
            val focusSeries = series.firstOrNull { it.name == focusName }
            val focusPoints = focusSeries?.points.orEmpty()
            val selectedTs = tapped ?: defaultTs
            val selectedIdx = focusPoints.indexOfFirst { it.bucketStart == selectedTs }
            val selectedPt = if (selectedIdx >= 0) focusPoints[selectedIdx] else null
            val headerValue = if (selectedPt?.hasValue == true) selectedPt.density else null
            val prevPt = if (selectedIdx > 0) focusPoints.subList(0, selectedIdx).lastOrNull { it.hasValue } else null
            val delta = if (headerValue != null && prevPt != null) headerValue - prevPt.density else null
            val validPts = focusPoints.filter { it.hasValue }
            val peakPt = validPts.maxByOrNull { it.density }
            val coveredBuckets = focusPoints.count { it.hasRecord }
            val focusColor = focusName?.let { colorOf(it) } ?: MaterialTheme.colorScheme.primary

            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    Modifier.weight(1f),
                    verticalAlignment = Alignment.Bottom
                ) {
                    Text(
                        headerValue?.let { "%.2f".format(it) } ?: "—",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = focusColor
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        focusName ?: "未选情绪",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                    if (delta != null) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            (if (delta >= 0) "↑ +" else "↓ ") + "%.2f".format(kotlin.math.abs(delta)),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (delta >= 0) Color(0xFFB4635A) else Color(0xFF5B8DB8),
                            modifier = Modifier.padding(bottom = 5.dp)
                        )
                    }
                }
                // 范围：一颗下拉，收掉原来那四个药丸
                Box {
                    var rangeMenu by remember { mutableStateOf(false) }
                    Row(
                        Modifier
                            .clip(MirrorShapes.pill)
                            .clickable { rangeMenu = true }
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            range.label,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.width(3.dp))
                        Icon(
                            Icons.Filled.ExpandMore,
                            null,
                            Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    MirrorMenu(
                        expanded = rangeMenu,
                        onDismissRequest = { rangeMenu = false },
                        actions = TrendRange.entries.map { r ->
                            MirrorMenuAction(r.label, selected = r == range) {
                                range = r; rangeMenu = false; tapped = null
                            }
                        }
                    )
                }
            }
            Text(
                buildString {
                    peakPt?.let { append("峰值 ${bucketLabel(it.bucketStart, bucket)} · ${"%.2f".format(it.density)}　·　") }
                    append("有记录 $coveredBuckets ${bucket.label}　·　按${bucket.label}")
                    if (window > 1) append("（${window} ${bucket.label}平滑）")
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, top = 4.dp)
            )

            if (range == TrendRange.CUSTOM) {
                Spacer(Modifier.height(6.dp))
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TextButton(onClick = { showStartPicker = true }) { Text(formatDateOnly(startMs)) }
                    Text("~", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = { showEndPicker = true }) { Text(formatDateOnly(endMs)) }
                    Spacer(Modifier.weight(1f))
                    Text(
                        "最多一年",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                rangeHint?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            // ---- 图表：焦点那条上色 + 其余灰线 ----
            EmotionLineChart(
                series = series,
                colors = selected.map { if (it == focusName) colorOf(it) else colorOf(it).copy(alpha = 0.22f) },
                bucket = bucket,
                modifier = Modifier.fillMaxWidth().height(260.dp).padding(horizontal = 8.dp),
                focusBucketStart = tapped ?: defaultTs,
                focusName = focusName,
                onPointClick = { tapped = it }
            )

            Spacer(Modifier.height(4.dp))

            // ---- 点选详情（没点时默认显示最后一个有数据的桶）----
            (tapped ?: defaultTs)?.let { ts ->
                val idx = series.firstOrNull()?.points?.indexOfFirst { it.bucketStart == ts } ?: -1
                if (idx >= 0) {
                    Spacer(Modifier.height(10.dp))
                    Surface(
                        shape = MirrorShapes.row,
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        border = mirrorHairline(),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Text(
                                "${bucketLabel(ts, bucket)}（${bucket.label}）",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(Modifier.height(6.dp))
                            series.forEach { s ->
                                val p = s.points.getOrNull(idx) ?: return@forEach
                                val isPrimary = s.name == focusName
                                Row(
                                    Modifier.fillMaxWidth()
                                        .clip(MirrorShapes.row)
                                        .clickable { primary = s.name }
                                        .padding(vertical = 3.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    // 颜色小块 = 对应那条线（和下面是同一个取色函数，不会对错）
                                    Surface(
                                        color = colorOf(s.name),
                                        shape = MirrorShapes.pill,
                                        modifier = Modifier.size(width = 12.dp, height = 3.dp)
                                    ) {}
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        s.name,
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = if (isPrimary) FontWeight.SemiBold else FontWeight.Normal,
                                        color = if (isPrimary) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                    )
                                    Spacer(Modifier.weight(1f))
                                    Text(
                                        when {
                                            !p.hasValue -> "没写记录"
                                            p.hasRecord -> "密度 ${"%.2f".format(p.density)}"
                                            // 平滑后：这天自己没写，但窗口里有数据 → 值仍然有效，标一下免得误解
                                            else -> "密度 ${"%.2f".format(p.density)}（这天没写）"
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            val pName = focusName
                            if (pName != null) {
                                Spacer(Modifier.height(6.dp))
                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.End
                                ) {
                                    Text(
                                        "看记录 ›",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier
                                            .clip(MirrorShapes.row)
                                            .clickable { onViewEmotionRecords(pName) }
                                            .padding(horizontal = 6.dp, vertical = 4.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // ---- 选情绪：加权 chip 云（字号 = 记过的次数）----
            // 原来是 15 颗等大药丸、三列硬排：矛盾记过 73 次和羞愧 3 次长得一模一样，
            // 颜色又只在选中时出现 —— 于是既没有轻重也没有秩序。
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "选情绪",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.weight(1f))
                Text(
                    "${selected.size}/${EmotionSeriesBuilder.MAX_SERIES}",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (selected.size >= EmotionSeriesBuilder.MAX_SERIES)
                        MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(8.dp))
            if (stats.isEmpty()) {
                Text(
                    if (loaded) "还没有带情绪的记录（情绪由 AI 分析产生）" else "载入中…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            } else {
                // 频次条列表：一行一条，长度 = 你记过的**次数**（count；weight 是时间衰减权重，不是次数）
                val heaviest = stats.maxOf { it.count }.coerceAtLeast(1)
                val ordered = stats.sortedWith(
                    compareByDescending<EmotionStat> { it.name in selected }.thenByDescending { it.count }
                )
                val shownList = if (showAllEmotions) ordered else ordered.take(8)
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    shownList.forEachIndexed { rowIdx, s ->
                        val on = s.name in selected
                        val c = colorOf(s.name)
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(MirrorShapes.row)
                                .clickable {
                                    if (on) {
                                        val remaining = selected - s.name
                                        selected = remaining
                                        // 只有删掉的是主情绪才回退：回退到最近加入的那个（追加在末尾）
                                        if (primary == s.name) primary = remaining.lastOrNull()
                                    } else if (selected.size < EmotionSeriesBuilder.MAX_SERIES) {
                                        val used = selected.mapNotNull { colorIndexOf[it] }
                                        colorIndexOf[s.name] =
                                            EmotionSeriesBuilder.pickPaletteIndex(used, palette.size)
                                        // 追加到末尾：不影响当前主情绪
                                        selected = selected + s.name
                                        if (primary == null) primary = s.name
                                    }
                                    tapped = null
                                }
                                .height(32.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                Modifier
                                    .size(11.dp)
                                    .clip(CircleShape)
                                    .background(if (on) c else Color.Transparent)
                                    .border(
                                        1.4.dp,
                                        if (on) Color.Transparent else MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
                                        CircleShape
                                    )
                            )
                            Spacer(Modifier.width(9.dp))
                            Text(
                                s.name,
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (on) c else MaterialTheme.colorScheme.onSurface,
                                fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                                maxLines = 1,
                                modifier = Modifier.width(52.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Box(
                                Modifier
                                    .weight(1f)
                                    .height(3.dp)
                                    .clip(MirrorShapes.pill)
                                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f))
                            ) {
                                Box(
                                    Modifier
                                        .fillMaxWidth((s.count.toFloat() / heaviest).coerceIn(0.03f, 1f))
                                        .height(3.dp)
                                        .clip(MirrorShapes.pill)
                                        .background(
                                            if (on) c
                                            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.32f)
                                        )
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "${s.count}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = androidx.compose.ui.text.style.TextAlign.End,
                                modifier = Modifier.width(22.dp)
                            )
                        }
                        // 2026-11-22：行间分隔线从**圆圈之后**起（让开图标），右端到列表内边。
                        // 法条 3.1：带控件的行，线让开控件；这里圆圈 11 + 间距 9。
                        if (rowIdx < shownList.lastIndex) {
                            Box(
                                Modifier.fillMaxWidth().height(1.dp).padding(start = 20.dp)
                                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f))
                            )
                        }
                    }
                    if (ordered.size > 8) {
                        Text(
                            if (showAllEmotions) "收起" else "全部 ${ordered.size} 个",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .clip(MirrorShapes.row)
                                .clickable { showAllEmotions = !showAllEmotions }
                                .padding(horizontal = 8.dp, vertical = 7.dp)
                        )
                    }
                }
            }

            Spacer(Modifier.height(28.dp))
        }
    }
}
