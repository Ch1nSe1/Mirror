package memore.app.ui.screens

import memore.app.ui.components.MirrorShapes

import memore.app.ui.theme.MemoreType
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
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
import memore.app.data.model.EmotionStat
import memore.app.data.model.EmotionStats
import memore.app.data.repository.RecordRepository
import memore.app.ui.components.IconChart
import memore.app.utils.formatDateOnly
import kotlinx.coroutines.launch

private enum class ChartSortMode(val label: String) {
    BY_COUNT("次数"),
    BY_FREQUENCY("频率"),
    BY_WEIGHT("强度"),
    BY_TIME("时间")
}

/**
 * 情绪分布（2026-10-24 重做，P2 + P1）：
 *  - 顶部一条 100% 堆叠条：前 5 种 + "其余 N 种"，把长尾说清楚
 *  - 下面点线图 + 平均值虚线：46 种情绪的占比都挤在 3%–5%，条形图看不出差别，
 *    点线图配一条均值线才能读出"谁高于平均"
 *  - 默认只列前 15 条，其余折叠
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EmotionChartScreen(
    recordRepository: RecordRepository,
    onBack: () -> Unit,
    onViewEmotionRecords: (emotionTag: String) -> Unit
) {
    val scope = rememberCoroutineScope()
    var emotions by remember { mutableStateOf<List<EmotionStat>>(emptyList()) }
    var sortMode by rememberSaveable(stateSaver = androidx.compose.runtime.saveable.Saver<ChartSortMode, String>(
        save = { it.name }, restore = { ChartSortMode.valueOf(it) })) { mutableStateOf(ChartSortMode.BY_COUNT) }
    var loadError by remember { mutableStateOf(false) }
    var showAll by rememberSaveable { mutableStateOf(false) }

    fun computeAndSort(mode: ChartSortMode) {
        scope.launch {
            loadError = false
            val records = try { recordRepository.getRecordsWithEmotion() } catch (_: Exception) { loadError = true; emptyList() }
            emotions = EmotionStats.parse(records).let { list ->
                when (mode) {
                    ChartSortMode.BY_COUNT -> list.sortedByDescending { it.count }
                    ChartSortMode.BY_TIME -> list.sortedByDescending { it.lastOccurrence }
                    ChartSortMode.BY_FREQUENCY -> list.sortedByDescending { it.frequency }
                    ChartSortMode.BY_WEIGHT -> list.sortedByDescending { it.weight }
                }
            }
        }
    }

    LaunchedEffect(Unit) { computeAndSort(ChartSortMode.BY_COUNT) }

    fun metric(e: EmotionStat): Float = when (sortMode) {
        ChartSortMode.BY_COUNT -> e.count.toFloat()
        ChartSortMode.BY_FREQUENCY -> e.frequency.toFloat()
        ChartSortMode.BY_WEIGHT -> e.weight.toFloat()
        ChartSortMode.BY_TIME -> e.count.toFloat() // 时间模式用条长表示不了，退化成次数
    }

    fun label(e: EmotionStat): String = when (sortMode) {
        ChartSortMode.BY_COUNT -> "${e.count} 次"
        ChartSortMode.BY_FREQUENCY -> "%.1f%%".format(e.frequency * 100)
        ChartSortMode.BY_WEIGHT -> "%.2f".format(e.weight)
        ChartSortMode.BY_TIME -> formatDate(e.lastOccurrence)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("情绪分布", style = MemoreType.pageTitleSans) },
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
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (loadError) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("情绪数据加载失败", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(8.dp))
                        TextButton(onClick = { computeAndSort(sortMode) }) { Text("重试") }
                    }
                }
            } else if (emotions.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(IconChart, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
                        Spacer(Modifier.height(12.dp))
                        Text("暂无情绪数据", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            } else {
                // ---- 页签（2026-11-22）：与「关联」「AI 配置」同一套 ----
                // 上下各一条 0.28 全宽发丝线，中间 1dp×18dp 竖线，不用下划线。
                Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.28f)))
                Row(Modifier.fillMaxWidth().height(40.dp), verticalAlignment = Alignment.CenterVertically) {
                    ChartSortMode.entries.forEachIndexed { index, mode ->
                        if (index > 0) {
                            Box(
                                Modifier.width(1.dp).height(18.dp)
                                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.28f))
                            )
                        }
                        val on = sortMode == mode
                        Box(
                            Modifier.weight(1f).fillMaxHeight()
                                .clickable { sortMode = mode; showAll = false; computeAndSort(mode) },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                mode.label,
                                fontSize = 13.sp,
                                color = if (on) MaterialTheme.colorScheme.onSurface
                                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                            )
                        }
                    }
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.28f)))

                val total = emotions.sumOf { it.count }
                val avg = emotions.map { metric(it) }.average().toFloat()
                val maxMetric = emotions.maxOfOrNull { metric(it) }?.coerceAtLeast(0.0001f) ?: 1f
                val top = emotions.take(5)
                val rest = emotions.drop(5)
                val restShare = rest.sumOf { it.count.toDouble() }

                LazyColumn(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
                    // ---- 统计行 ----
                    item {
                        Text(
                            "共 $total 次 · ${emotions.size} 种" +
                                if (sortMode == ChartSortMode.BY_FREQUENCY) " · 平均 " + "%.1f%%".format(avg * 100) else "",
                            fontSize = 11.5.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 10.dp, bottom = 10.dp)
                        )
                    }
                    // ---- P2 堆叠分布条 ----
                    item {
                        Row(
                            Modifier.fillMaxWidth().height(14.dp).clip(MirrorShapes.row),
                            horizontalArrangement = Arrangement.spacedBy(1.dp)
                        ) {
                            top.forEachIndexed { index, e ->
                                val w = (e.count.toFloat() / total.coerceAtLeast(1)) * 100f
                                Box(
                                    Modifier.weight(w).fillMaxHeight()
                                        .background(
                                            MaterialTheme.colorScheme.primary.copy(alpha = (0.95f - index * 0.1f).coerceAtLeast(0.45f))
                                        )
                                )
                            }
                            if (restShare > 0) {
                                val w = ((restShare / total.coerceAtLeast(1)) * 100f).toFloat()
                                Box(
                                    Modifier.weight(w.coerceAtLeast(0.5f)).fillMaxHeight()
                                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f))
                                )
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            top.joinToString(" · ") { "${it.name} ${"%.1f".format(it.count.toFloat() / total.coerceAtLeast(1) * 100)}%" } +
                                if (rest.isNotEmpty()) " · 其余 ${rest.size} 种 ${"%.0f".format(restShare / total.coerceAtLeast(1) * 100)}%" else "",
                            fontSize = 10.5.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            lineHeight = 16.sp
                        )
                        Spacer(Modifier.height(14.dp))
                        Box(
                            Modifier.fillMaxWidth().height(1.dp)
                                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f))
                        )
                    }
                    // ---- P1 点线图 ----
                    val visible = if (showAll) emotions else emotions.take(15)
                    items(visible.size) { index ->
                        val e = visible[index]
                        val frac = (metric(e) / maxMetric).coerceIn(0.02f, 1f)
                        val meanFrac = (avg / maxMetric).coerceIn(0f, 1f)
                        Row(
                            Modifier.fillMaxWidth().height(44.dp)
                                .clickable { onViewEmotionRecords(e.name) },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                e.name,
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                modifier = Modifier.width(64.dp)
                            )
                            BoxWithConstraints(Modifier.weight(1f).height(14.dp)) {
                                val trackW = maxWidth
                                // 基线（很淡的一条）
                                Box(
                                    Modifier.fillMaxWidth().height(1.dp)
                                        .align(Alignment.CenterStart)
                                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
                                )
                                // 均值线：真正的 1dp 竖线，位置 = 平均值
                                Box(
                                    Modifier
                                        .offset(x = trackW * meanFrac)
                                        .width(1.dp)
                                        .fillMaxHeight()
                                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.30f))
                                )
                                // 圆点到基线的细线
                                Box(
                                    Modifier.fillMaxWidth(frac).height(1.dp)
                                        .align(Alignment.CenterStart)
                                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.45f))
                                )
                                // 圆点
                                Box(
                                    Modifier.fillMaxWidth(frac).height(14.dp),
                                    contentAlignment = Alignment.CenterEnd
                                ) {
                                    Box(
                                        Modifier.size(7.dp).clip(MirrorShapes.pill)
                                            .background(MaterialTheme.colorScheme.primary)
                                    )
                                }
                            }
                            Spacer(Modifier.width(10.dp))
                            Text(
                                label(e),
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.width(48.dp),
                                textAlign = androidx.compose.ui.text.style.TextAlign.End
                            )
                        }
                        Box(
                            Modifier.fillMaxWidth().height(1.dp)
                                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f))
                        )
                    }
                    // ---- 展开其余 ----
                    if (emotions.size > 15) {
                        item {
                            Text(
                                if (showAll) "收起" else "展开其余 ${emotions.size - 15} 种",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { showAll = !showAll }
                                    .padding(vertical = 12.dp)
                            )
                        }
                    }
                    item { Spacer(Modifier.height(24.dp)) }
                }
            }
        }
    }
}

private fun formatDate(timestamp: Long): String = try {
    formatDateOnly(timestamp)
} catch (_: Exception) {
    ""
}
