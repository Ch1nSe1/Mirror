package memore.app.ui.screens

import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.background
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Sort
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import memore.app.data.model.Record
import memore.app.data.model.EmotionStat
import memore.app.data.model.EmotionStats
import memore.app.data.repository.RecordRepository
import memore.app.ui.components.IconChart
import memore.app.ui.components.IconTrendChart
import memore.app.ui.components.IconEmotion
import memore.app.ui.components.MirrorShapes
import memore.app.ui.components.mirrorHairline
import memore.app.utils.formatDateOnly
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EmotionScreen(
    recordRepository: RecordRepository,
    onBack: () -> Unit,
    onViewEmotionRecords: (emotionTag: String) -> Unit,
    onOpenChart: () -> Unit = {},
    onOpenTrend: () -> Unit = {}
) {
    var emotions by remember { mutableStateOf<List<EmotionStat>>(emptyList()) }
    var emotionsLoaded by remember { mutableStateOf(false) }
    var sortByCount by rememberSaveable { mutableStateOf(true) }
    var loadError by remember { mutableStateOf(false) }
    val loadScope = rememberCoroutineScope()

    fun loadEmotions() {
        loadScope.launch {
            loadError = false
            val records = try { recordRepository.getRecordsWithEmotion() } catch (_: Exception) { loadError = true; emptyList() }
            emotions = EmotionStats.parse(records).let { list ->
                if (sortByCount) list.sortedByDescending { it.count } else list.sortedByDescending { it.lastOccurrence }
            }
        }
    }

    LaunchedEffect(Unit) { loadEmotions(); emotionsLoaded = true }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Row(verticalAlignment = Alignment.CenterVertically) { Icon(IconEmotion, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary); Spacer(Modifier.width(8.dp)); Text("情绪分析") } },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回") } },
                actions = {
                    // 走势（折线）放左边：它是"看趋势"，右侧那个是"看构成"，先走势后构成
                    IconButton(onClick = onOpenTrend) { Icon(IconTrendChart, "走势", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                    IconButton(onClick = onOpenChart) { Icon(IconChart, "图表", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                    TextButton(onClick = { sortByCount = !sortByCount; loadEmotions() }) {
                        Icon(Icons.Outlined.Sort, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(if (sortByCount) "次数" else "时间", style = MaterialTheme.typography.labelSmall)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        if (!emotionsLoaded && !loadError) {
            memore.app.ui.components.MirrorLoading(Modifier.padding(padding))
        } else if (loadError) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("情绪数据加载失败", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = { loadEmotions() }) { Text("重试") }
                }
            }
        } else if (emotions.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("暂无情绪标签数据", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            // 行 + 淡条（2026-10-24）：条长按次数比例，6% 主色；次数只出现一次
            val maxCount = (emotions.maxOfOrNull { it.count } ?: 1).coerceAtLeast(1)
            LazyColumn(Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp)) {
                items(emotions, key = { it.name }) { emotion ->
                    Column(Modifier.fillMaxWidth()) {
                        Row(
                            Modifier.fillMaxWidth().height(52.dp)
                                .clickable { onViewEmotionRecords(emotion.name) },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                emotion.name,
                                fontSize = 15.sp,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                modifier = Modifier.width(88.dp)
                            )
                            // 淡条：长度 = 次数 / 最大值
                            Box(
                                Modifier.weight(1f).height(6.dp)
                                    .clip(MirrorShapes.pill)
                                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.06f))
                            ) {
                                Box(
                                    Modifier
                                        .fillMaxWidth((emotion.count.toFloat() / maxCount).coerceIn(0.04f, 1f))
                                        .height(6.dp)
                                        .clip(MirrorShapes.pill)
                                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.22f))
                                )
                            }
                            Spacer(Modifier.width(10.dp))
                            Text(
                                "${emotion.count} 次 · ${formatDate(emotion.lastOccurrence)}",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1
                            )
                            Spacer(Modifier.width(8.dp))
                            // 趋势箭头（2026-11-18）：涨＝墨绿、跌＝暗红、平＝原来的灰。
                            // 之前三档都是灰，只靠透明度区分，根本看不出势头。
                            val (trendIcon, trendColor) = when (emotion.trend) {
                                "up" -> "↑" to Color(0xFF4C9A6A)
                                "down" -> "↓" to Color(0xFFC0554D)
                                else -> "→" to MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
                            }
                            Text(
                                trendIcon,
                                fontSize = 12.sp,
                                color = trendColor,
                                modifier = Modifier.width(14.dp)
                            )
                        }
                        Box(
                            Modifier.fillMaxWidth().height(1.dp)
                                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f))
                        )
                    }
                }
            }
        }
    }
}

private fun formatDate(timestamp: Long): String {
    return try {
        formatDateOnly(timestamp)
    } catch (_: Exception) { "" }
}
