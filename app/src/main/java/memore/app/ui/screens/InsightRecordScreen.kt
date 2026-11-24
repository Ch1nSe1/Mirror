package memore.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
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
 * 记录洞察页（2026-11-17 按统一模板重写）。
 *
 * 与洞察档案同一张卡；差别只有页框和顶部那行"这一条"：
 *
 * ```
 * 洞察                       ← MemorePage 标题（原来是空顶栏，用户看不出这是什么页）
 * 这一条 · 2026-11-12 00:34 · 随笔
 * ─────────────────────────
 * 单条 · 11 月 12 日        ⋮
 * 一句判断句（示例）
 * 「我害怕某件事」· 2026-01-01
 * ```
 * 不再有「＋ 再读一次」——那是我在模板里多画的，代码里本来没有这个入口。
 */
@Composable
fun InsightRecordScreen(
    recordId: String,
    repository: RecordRepository,
    onBack: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val svc = remember { InsightService(context, repository) }
    var entries by remember { mutableStateOf<List<InsightService.Entry>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    var recordMeta by remember { mutableStateOf<String?>(null) }
    var deleteTarget by remember { mutableStateOf<InsightService.Entry?>(null) }
    val fmt = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }
    val dayFmt = remember { SimpleDateFormat("M 月 d 日", Locale.getDefault()) }

    fun reload() {
        entries = svc.savedForRecord(recordId)
        loaded = true
    }
    LaunchedEffect(recordId) {
        reload()
        val r = repository.getRecordById(recordId)
        val typeName = when (r?.type?.name) {
            "ESSAY" -> "随笔"
            "DIALOGUE" -> "对话"
            "TRANSCRIPT" -> "转录"
            "AI_NOTE" -> "AI 札记"
            else -> ""
        }
        // 记录身份放进页头 subtitle（12.5 muted），不再自己画一行 meta 和卡片头抢位置
        recordMeta = r?.let { fmt.format(Date(it.createdAt)) + if (typeName.isNotBlank()) " · $typeName" else "" }
    }

    MemorePage(
        title = "洞察",
        subtitle = recordMeta,
        onBack = onBack
    ) { padding ->
        when {
            !loaded -> MirrorLoading(Modifier.padding(padding))
            entries.isEmpty() -> Spacer(Modifier.fillMaxSize())
            else -> LazyColumn(Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp)) {
                items(entries, key = { it.id }) { entry ->
                    InsightCard(
                        scope = if (entry.meta.isNotBlank()) entry.meta else "单条",
                        date = dayFmt.format(Date(entry.at)),
                        claims = entry.claims,
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
