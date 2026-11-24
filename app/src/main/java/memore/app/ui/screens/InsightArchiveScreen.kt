package memore.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import memore.app.data.repository.RecordRepository
import memore.app.data.vector.InsightService
import memore.app.ui.components.InsightCard
import memore.app.ui.components.MemorePage
import memore.app.ui.components.MirrorLoading
import memore.app.ui.components.MirrorStackDialog
import memore.app.ui.theme.MemoreType
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 洞察档案（2026-11-17 按统一模板重写）。
 *
 * 模板：洞察卡（范围 · 日期 / 结论 sans / 引文 serif / 右侧记号）。
 * 这一页是**内容列表**：卡片本体点开 = 进那条记录（右侧 ›），删除走卡片的 ⋮ 菜单。
 * 页框走共享 [MemorePage]，不再手写 Scaffold + TopAppBar（铁律 2）。
 */
@Composable
fun InsightArchiveScreen(
    repository: RecordRepository,
    onBack: () -> Unit,
    onOpenRecord: (String) -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val svc = remember { InsightService(context, repository) }
    var entries by remember { mutableStateOf<List<InsightService.Entry>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<InsightService.Entry?>(null) }

    fun reload() {
        entries = svc.archive()
        loaded = true
    }
    LaunchedEffect(Unit) { reload() }

    val fmt = remember { SimpleDateFormat("M 月 d 日", Locale.getDefault()) }

    MemorePage(title = "洞察档案", onBack = onBack) { padding ->
        when {
            !loaded -> MirrorLoading(Modifier.padding(padding))
            entries.isEmpty() -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("还没有保存过洞察", style = MemoreType.meta, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            else -> LazyColumn(Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp)) {
                items(entries, key = { it.id }) { entry ->
                    val scope = when (entry.kind) {
                        "single" -> "单条"
                        "batch" -> if (entry.meta.isNotBlank()) entry.meta else "批量"
                        else -> if (entry.meta.isNotBlank()) entry.meta else "近一月"
                    }
                    InsightCard(
                        scope = scope,
                        date = fmt.format(Date(entry.at)),
                        claims = entry.claims,
                        onClick = entry.claims.firstOrNull()?.recordId?.let { rid -> { onOpenRecord(rid) } },
                        onLongClick = { deleteTarget = entry }
                    )
                }
                item { Spacer(Modifier.height(30.dp)) }
            }
        }
    }

    deleteTarget?.let { target ->
        MirrorStackDialog(
            title = "删除这条洞察",
            confirmText = "删除",
            destructive = true,
            onConfirm = {
                svc.deleteInsight(target.id)
                deleteTarget = null
                reload()
            },
            onDismiss = { deleteTarget = null }
        ) { Text("删除后无法恢复", style = MemoreType.meta, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}
