package memore.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import memore.app.data.vector.AiRunLog
import memore.app.ui.theme.MemoreType

/**
 * AI 记录（2026-11-05）：一个页面看清"每次分析到底产出了什么"。
 *
 * 一行 = 一次调用：时间、记录摘要、候选条数与月份分布、边数、总结长度、耗时。
 * 点开 = 候选清单 + 选中的边 + 被丢弃的数量。失败的行直接显示原因。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiRunLogScreen(runs: List<AiRunLog.Run>, onBack: () -> Unit) {
    var expanded by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("AI 记录", style = MemoreType.pageTitleSans) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        if (runs.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("还没有调用", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(padding)) {
                items(runs, key = { it.at.toString() }) { run ->
                    val open = expanded == run.at.toString()
                    Column {
                        Column(
                            Modifier.fillMaxWidth().clickable {
                                expanded = if (open) null else run.at.toString()
                            }.padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 10.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    run.atText,
                                    fontSize = 10.5.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(Modifier.width(8.dp))
                                if (!run.ok) {
                                    Text("失败", fontSize = 10.5.sp, color = MaterialTheme.colorScheme.error)
                                }
                            }
                            Spacer(Modifier.height(3.dp))
                            Text(
                                run.recordSnippet,
                                fontSize = 13.5.sp,
                                lineHeight = 20.sp,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(Modifier.height(3.dp))
                            Text(
                                buildString {
                                    if (run.ok) {
                                        append("候选 ").append(run.candidates.size)
                                        if (run.candidates.isNotEmpty()) {
                                            val months = run.candidates.groupingBy { it.month }.eachCount()
                                            append("（")
                                            append(months.entries.joinToString(" ") { "${it.key.substringAfter('-')}月×${it.value}" })
                                            append("）")
                                        }
                                        append(" · 边 ").append(run.links.size)
                                        if (run.dropped > 0) append(" · 丢弃 ").append(run.dropped)
                                        append(" · ")
                                        append(if (run.summaryLen > 0) "总结 ${run.summaryLen} 字" else "无总结")
                                        append(" · ").append(run.durationMs / 1000.0).append("s")
                                    } else {
                                        append(run.error ?: "未知错误")
                                    }
                                },
                                fontSize = 10.5.sp,
                                color = if (run.ok) MaterialTheme.colorScheme.onSurfaceVariant
                                else MaterialTheme.colorScheme.error
                            )
                        }
                        if (open) {
                            Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 12.dp)) {
                                run.links.forEach { l ->
                                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                                        Box(
                                            Modifier.width(2.dp).height(16.dp)
                                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.30f))
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Text(
                                            buildString {
                                                append(l.why.ifBlank { "（无解释）" })
                                                append("  ")
                                                append(l.toMonth.substringAfter('-'))
                                                append("月 · ")
                                                append(l.toId)
                                            },
                                            fontSize = 11.5.sp,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                    }
                                }
                                if (run.candidates.isNotEmpty()) {
                                    Spacer(Modifier.height(6.dp))
                                    Text("候选", style = MemoreType.micro, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Spacer(Modifier.height(4.dp))
                                    run.candidates.forEach { c ->
                                        Text(
                                            "${c.month}  ${c.text}",
                                            fontSize = 10.5.sp,
                                            lineHeight = 16.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                        }
                        Box(
                            Modifier.fillMaxWidth().padding(horizontal = 20.dp).height(1.dp)
                                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f))
                        )
                    }
                }
            }
        }
    }
}