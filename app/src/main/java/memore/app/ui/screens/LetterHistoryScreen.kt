package memore.app.ui.screens

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import memore.app.MemoreApp
import memore.app.data.agent.MirrorActionService
import memore.app.data.model.AgentAction
import memore.app.ui.components.MemorePage
import memore.app.ui.components.MirrorAlertDialog
import memore.app.ui.components.mirrorHairlineColor
import memore.app.ui.theme.MemoreType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 信 · 历史（2026-11-20）：从「信」右上角进来，只放已执行的动作。
 *
 * 规格：
 *   · 按时间分组（大分隔），每次 10 条，到底后居中「展开更多」（主色）
 *   · 每条：标题 → 小字「具体行为 · 档位」→ 灰字「相关记录」；撤回在**右侧**（淡灰）
 *   · 撤回需二次确认
 */
@Composable
fun LetterHistoryScreen(
    context: Context,
    onOpenRecords: (String) -> Unit,
    onBack: () -> Unit
) {
    val app = context.applicationContext as MemoreApp
    val service = remember { MirrorActionService(app) }
    var done by remember { mutableStateOf<List<AgentAction>>(emptyList()) }
    var tiers by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }
    var shown by rememberSaveable { mutableIntStateOf(10) }
    var result by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var confirmUndo by remember { mutableStateOf<AgentAction?>(null) }
    val scope = rememberCoroutineScope()

    fun refresh() {
        scope.launch {
            val d = withContext(Dispatchers.IO) {
                runCatching { service.recentActions(60) }.getOrDefault(emptyList())
            }
            done = d
            tiers = withContext(Dispatchers.IO) {
                d.associate { a -> a.id to runCatching { service.tierOfAction(a.id) }.getOrDefault(2) }
            }
        }
    }
    LaunchedEffect(Unit) { refresh() }

    fun run(block: () -> String) {
        if (busy) return
        busy = true
        scope.launch {
            val note = withContext(Dispatchers.IO) { runCatching(block).getOrElse { "失败" } }
            result = note
            busy = false
            refresh()
        }
    }

    MemorePage(title = "历史", onBack = onBack) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Spacer(Modifier.height(4.dp))

            if (done.isNotEmpty()) {
                val visible = done.take(shown)
                var lastDay = ""
                visible.forEach { action ->
                    val day = dayOf(action.executedAt.takeIf { it > 0 } ?: action.createdAt)
                    if (day != lastDay) {
                        // 时间分组：大分隔 + 日期
                        Spacer(Modifier.height(if (lastDay.isBlank()) 18.dp else 32.dp))
                        Text(day, style = MemoreType.micro, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(6.dp))
                        lastDay = day
                    }
                    DoneItem(
                        action = action,
                        verbSummary = summaryOf(service, action),
                        tier = tiers[action.id] ?: 2,
                        onRecords = { onOpenRecords(action.id) },
                        onUndo = { confirmUndo = action }
                    )
                    Box(Modifier.fillMaxWidth().height(1.dp).background(mirrorHairlineColor()))
                }

                if (done.size > visible.size) {
                    Spacer(Modifier.height(20.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                        Text(
                            "展开更多",
                            style = MemoreType.meta,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.clickable { shown += 10 }.padding(vertical = 6.dp)
                        )
                    }
                }
            }

            if (result.isNotBlank()) {
                Spacer(Modifier.height(16.dp))
                Text(result, style = MemoreType.meta, color = MaterialTheme.colorScheme.primary)
            }

            Spacer(Modifier.height(28.dp))
        }
    }

    confirmUndo?.let { action ->
        MirrorAlertDialog(
            onDismissRequest = { confirmUndo = null },
            title = { Text("撤回这次改动？", style = MaterialTheme.typography.headlineSmall) },
            text = {
                Text(
                    "会按动作前的快照整批回滚；之后被改过的记录会拒绝回滚。",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmUndo = null
                    run {
                        val r = service.undo(action.id)
                        r.optString("error").ifBlank { r.optString("note").ifBlank { "已撤回" } }
                    }
                }) {
                    Text("撤回", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmUndo = null }) {
                    Text("取消", color = MaterialTheme.colorScheme.primary)
                }
            }
        )
    }
}

@Composable
private fun DoneItem(
    action: AgentAction,
    verbSummary: String,
    tier: Int,
    onRecords: () -> Unit,
    onUndo: () -> Unit
) {
    Column(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 12.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(
                    action.title.ifBlank { "未命名的动作" },
                    style = MemoreType.body,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (verbSummary.isNotBlank()) {
                    Spacer(Modifier.height(5.dp))
                    Text(
                        verbSummary + (if (tier == 1) " · 一级" else " · 二级"),
                        style = MemoreType.micro,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "相关记录",
                    style = MemoreType.meta,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.clickable(onClick = onRecords)
                )
            }
            // 撤回在右侧（不是右下），淡灰，和"相关记录"同色
            Text(
                "撤回",
                style = MemoreType.meta,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.clickable(onClick = onUndo).padding(horizontal = 4.dp, vertical = 4.dp)
            )
        }
    }
}

/** 动词摘要：打标签 · 归来源 · 合并标签 */
private fun summaryOf(service: MirrorActionService, action: AgentAction): String = runCatching {
    service.stepsForId(action.id).map { it.label }.distinct().joinToString(" · ")
}.getOrDefault("")

private fun dayOf(ms: Long): String =
    SimpleDateFormat("M 月 d 日", Locale.getDefault()).format(Date(ms))
