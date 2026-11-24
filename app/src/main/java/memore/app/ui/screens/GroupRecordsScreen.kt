package memore.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import memore.app.data.model.Record
import memore.app.ui.components.MemorePage
import memore.app.ui.components.RelatedGroupHeader
import memore.app.ui.components.RelatedRecordRow
import memore.app.ui.theme.MemoreType
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 「某一组记录」的统一模板（2026-11-14 第三版）。
 *
 * 基准换成**情绪页那套结构**（用户原话：「新的聚合界面在情绪界面基础上改，
 * 来源系统那个界面不是给人看的」）：
 *
 *   · 行 = 类型 + 时间 + 正文（2 行，末尾渐隐）+ 分割线
 *   · 分组 = 月份锚（RelatedGroupHeader，带一条淡线）
 *   · 左右 20dp 由行组件自带，**外层不再叠加**——叠出过 43.6dp
 *   · 最后一行不画线
 *   · 可选：头部图表（情绪这类有纵向数据的）、第三行证据
 */
@Composable
fun GroupRecordsScreen(
    title: String,
    load: suspend () -> List<Record>,
    onOpenRecord: (String) -> Unit,
    onBack: () -> Unit,
    chart: (@Composable () -> Unit)? = null,
    quote: ((Record) -> String?)? = null,
    groupOf: ((Record) -> String)? = null
) {
    var records by remember(title) { mutableStateOf<List<Record>>(emptyList()) }
    var loaded by remember(title) { mutableStateOf(false) }

    LaunchedEffect(title) {
        records = runCatching { load() }.getOrDefault(emptyList())
        loaded = true
    }

    val zone = remember { ZoneId.systemDefault() }
    val monthFmt = remember { DateTimeFormatter.ofPattern("yyyy-MM") }

    // 分组：默认按月；情绪页可以传自己的分法（本周 / 更早）
    val groups = remember(records) {
        val key: (Record) -> String = groupOf ?: { r ->
            monthFmt.format(Instant.ofEpochMilli(r.createdAt).atZone(zone))
        }
        records.sortedByDescending { it.createdAt }.groupBy(key)
    }

    MemorePage(
        title = title,
        onBack = onBack,
        // 条数放标题栏右侧，和标题同一行
        actions = {
            if (loaded) {
                Text(
                    records.size.toString() + " 条",
                    style = MemoreType.meta,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            if (chart != null) {
                item(key = "chart") {
                    Box(Modifier.padding(horizontal = 20.dp)) { chart() }
                }
            }
            if (!loaded) {
                item(key = "loading") {
                    Text(
                        "读取中…",
                        style = MemoreType.meta,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp)
                    )
                }
            }
            groups.forEach { (label, list) ->
                item(key = "g_$label") { RelatedGroupHeader(label) }
                list.forEachIndexed { index, r ->
                    item(key = r.id) {
                        Column {
                            RelatedRecordRow(
                                record = r,
                                trailing = index < list.lastIndex,
                                onClick = { onOpenRecord(r.id) },
                                onViewAttachments = {},
                                onViewSummary = {}
                            )
                            val q = quote?.invoke(r)
                            if (!q.isNullOrBlank()) {
                                Text(
                                    "「$q」",
                                    style = MemoreType.meta,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 10.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
