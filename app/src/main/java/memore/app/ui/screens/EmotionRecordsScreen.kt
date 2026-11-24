package memore.app.ui.screens

import androidx.compose.ui.unit.sp
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import memore.app.data.model.EmotionSeriesBuilder
import memore.app.data.model.EmotionStats
import memore.app.data.model.Record
import memore.app.data.repository.RecordRepository
import memore.app.ui.components.AttachmentListViewer
import memore.app.ui.components.EmotionLineChart
import memore.app.ui.components.IconEmotion
import memore.app.ui.components.RelatedEntryRow
import memore.app.ui.components.RelatedGroupHeader
import memore.app.ui.components.RelatedRecordsHeader
import memore.app.ui.components.SummaryViewer
import memore.app.ui.components.buildRelatedEntries
import memore.app.ui.components.groupRelatedEntries
import memore.app.utils.memoreToast

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EmotionRecordsScreen(
    recordRepository: RecordRepository,
    emotionTag: String,
    onBack: () -> Unit,
    onOpenRecord: (String) -> Unit
) {
    val context = LocalContext.current
    var records by remember { mutableStateOf<List<Record>?>(null) }
    /** 有情绪的全部记录 —— 折线要用它当分母（与走势页同一口径，见下）。 */
    var allEmotionRecords by remember { mutableStateOf<List<Record>>(emptyList()) }
    var loadError by remember { mutableStateOf(false) }
    var showAttachmentsFor by remember { mutableStateOf<Record?>(null) }
    var showSummaryFor by remember { mutableStateOf<Record?>(null) }
    LaunchedEffect(emotionTag) {
        val all = try { recordRepository.getRecordsWithEmotion() } catch (_: Exception) { loadError = true; emptyList() }
        allEmotionRecords = all
        records = EmotionStats.recordsWithEmotionTag(all, emotionTag)
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(emotionTag, fontSize = 20.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Serif, color = MaterialTheme.colorScheme.onSurface) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回", tint = MaterialTheme.colorScheme.onSurfaceVariant) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        val list = records
        when {
            list == null -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
            }
            loadError -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("情绪数据加载失败", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            list.isEmpty() -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("暂无该情绪标签的记录", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            else -> {
                val l = list!!
                val entries = remember(l) { buildRelatedEntries(l) }
                val groups = remember(entries) { groupRelatedEntries(entries) }
                // 折线：**与走势页同一口径**（密度 = 该情绪权重和 ÷ 该时段**全部**记录数），
                // 所以同一个情绪在两个页面上的数字是对得上的。
                // 范围取"有情绪的全部记录"的跨度到此刻；粒度按跨度自动。
                val nowMs = remember { System.currentTimeMillis() }
                val spanStart = remember(allEmotionRecords, nowMs) {
                    allEmotionRecords.minOfOrNull { it.createdAt } ?: nowMs
                }
                val bucket = remember(spanStart, nowMs) { EmotionSeriesBuilder.bucketFor(nowMs - spanStart) }
                // 平滑窗口也跟着走势页一起算（同一个函数）—— 否则跨度短的别名上两页会一个滑一个不滑
                val window = remember(spanStart, nowMs, bucket) {
                    EmotionSeriesBuilder.smoothingWindowFor(nowMs - spanStart, bucket)
                }
                val series = remember(allEmotionRecords, emotionTag, spanStart, nowMs, bucket, window) {
                    EmotionSeriesBuilder.build(allEmotionRecords, listOf(emotionTag), spanStart, nowMs, bucket, window)
                }
                LazyColumn(
                    // 左右 20dp 显式给：模板 A 的边距基准，别再靠行组件自带
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentPadding = PaddingValues(bottom = 28.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    item(key = "header") {
                        Column {
                    // 标题已在顶栏，删掉重复的图标+标题块
                            // 紧凑折线：和走势页同一套语义，但去掉 Y 轴刻度、矮一点，当列表头不抢戏
                            EmotionLineChart(
                                series = series,
                                colors = listOf(MaterialTheme.colorScheme.primary),
                                bucket = bucket,
                                compact = true,
                                modifier = Modifier.fillMaxWidth().height(118.dp).padding(horizontal = 8.dp)
                            )
                        }
                    }
                    groups.forEach { group ->
                        item(key = "g_${group.label}") { RelatedGroupHeader(group.label) }
                        itemsIndexed(group.entries, key = { _, entry -> entry.record.id }) { index, entry ->
                            // 该记录被判成"当前这个情绪"时的依据原文（2026-11-09 加）
                            val evidence = remember(entry.record.id, emotionTag) {
                                memore.app.data.model.EmotionStats.emotionEntries(entry.record)
                                    .firstOrNull { it.name == emotionTag }?.why
                            }
                            RelatedEntryRow(
                                entry = entry,
                                trailing = index < group.entries.lastIndex,
                                evidence = evidence,
                                onClick = { onOpenRecord(it.id) },
                                onViewAttachments = {
                                    if (it.isLocked) context.memoreToast("记录已加密，需先解密", Toast.LENGTH_SHORT)
                                    else showAttachmentsFor = it
                                },
                                onViewSummary = {
                                    if (it.isLocked) context.memoreToast("记录已加密，需先解密", Toast.LENGTH_SHORT)
                                    else showSummaryFor = it
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    showAttachmentsFor?.let { record ->
        AttachmentListViewer(record = record, recordRepository = recordRepository, onDismiss = { showAttachmentsFor = null })
    }
    showSummaryFor?.let { record ->
        SummaryViewer(record = record, onDismiss = { showSummaryFor = null })
    }
}
