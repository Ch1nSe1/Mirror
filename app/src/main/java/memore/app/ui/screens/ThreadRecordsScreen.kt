package memore.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import android.widget.Toast
import memore.app.data.local.AppDatabase
import memore.app.data.model.Record
import memore.app.data.model.RecordType
import memore.app.data.repository.RecordRepository
import memore.app.data.vector.MonthlyTopicsService
import memore.app.ui.components.AttachmentListViewer
import memore.app.ui.components.IconConcept
import memore.app.ui.components.MemorePage
import memore.app.ui.components.MirrorLoading
import memore.app.ui.components.RelatedEntryRow
import memore.app.ui.components.RelatedGroupHeader
import memore.app.ui.components.RelatedRecordsHeader
import memore.app.ui.components.SummaryViewer
import memore.app.ui.components.buildRelatedEntries
import memore.app.ui.components.groupRelatedEntriesByMonth
import memore.app.utils.memoreToast

/**
 * 一条线索的记录列表（2026-11-17）。
 *
 * 入口：线索页卡片右上「详情 ›」。按「线索名 + 成员主题名」从月度主题缓存里取记录 id，
 * 再逐条读数据库；版式复用关联记录那套（RelatedRecordsHeader + 分组 + 行）。
 */
@Composable
fun ThreadRecordsScreen(
    repository: RecordRepository,
    db: AppDatabase,
    threadName: String,
    members: List<String>,
    onBack: () -> Unit,
    onOpenRecord: (String) -> Unit
) {
    val context = LocalContext.current
    var records by remember { mutableStateOf<List<Record>?>(null) }
    var showAttachmentsFor by remember { mutableStateOf<Record?>(null) }
    var showSummaryFor by remember { mutableStateOf<Record?>(null) }
    var months by remember { mutableStateOf<List<MonthlyTopicsService.NamedMonth>>(emptyList()) }

    LaunchedEffect(threadName, members) {
        val snap = runCatching {
            MonthlyTopicsService(context, repository, db) { _, _ -> emptyList() }.cached()
        }.getOrNull()
        months = snap?.months.orEmpty()
        val ids = months.flatMap { m ->
            m.clusters.filter { it.name in members }.flatMap { it.recordIds }
        }.distinct()
        records = ids.mapNotNull { runCatching { repository.getRecordById(it) }.getOrNull() }
            .sortedByDescending { it.createdAt }
    }

    MemorePage(title = threadName, onBack = onBack) { padding ->
        val list = records
        when {
            list == null -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                MirrorLoading()
            }
            list.isEmpty() -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("没有记录", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            else -> {
                val entries = remember(list) { buildRelatedEntries(list) }
                val groups = remember(entries) { groupRelatedEntriesByMonth(entries) }
                val essay = list.count { it.type == RecordType.ESSAY }
                val dialogue = list.count { it.type == RecordType.DIALOGUE }
                val fmt = java.text.SimpleDateFormat("yyyy-MM", java.util.Locale.getDefault())
                val nums = list.map { it.createdAt }.sorted().map { fmt.format(java.util.Date(it)) }
                val first = nums.firstOrNull()
                val last = nums.lastOrNull()
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentPadding = PaddingValues(bottom = 28.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    item(key = "header") {
                        RelatedRecordsHeader(
                            icon = IconConcept,
                            title = threadName,
                            overview = "共 ${list.size} 条记录 · " + (first ?: "") + " → " + (last ?: ""),
                            chips = listOf("随笔" to essay, "对话" to dialogue)
                        )
                    }
                    groups.forEach { group ->
                        item(key = "g_${group.label}") { RelatedGroupHeader(group.label) }
                        itemsIndexed(group.entries, key = { _, entry -> entry.record.id }) { index, entry ->
                            RelatedEntryRow(
                                entry = entry,
                                trailing = index < group.entries.lastIndex,
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
        AttachmentListViewer(record = record, recordRepository = repository, onDismiss = { showAttachmentsFor = null })
    }
    showSummaryFor?.let { record ->
        SummaryViewer(record = record, onDismiss = { showSummaryFor = null })
    }
}
