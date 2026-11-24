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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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

/**
 * 信（2026-11-20：只放待批；「历史」挪到顶栏，点开是 [LetterHistoryScreen]）。
 *
 * 规格：
 *   · 横线分隔；每条：动作标题 → 小字「具体行为 · 档位」→ 「N 步 · 展开」→ 灰字「相关记录」
 *   · 批准在右下、忽略在其左侧（淡灰，和"相关记录"同色）
 */
@Composable
fun LetterScreen(
    context: Context,
    onOpenRecords: (String) -> Unit,
    onOpenHistory: () -> Unit,
    onBack: () -> Unit
) {
    val app = context.applicationContext as MemoreApp
    val service = remember { MirrorActionService(app) }
    var pending by remember { mutableStateOf<List<AgentAction>>(emptyList()) }
    var evidences by remember { mutableStateOf<Map<String, List<MirrorActionService.StepEvidence>>>(emptyMap()) }
    var tiers by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }
    var result by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var confirmApprove by remember { mutableStateOf<AgentAction?>(null) }
    val scope = rememberCoroutineScope()

    fun refresh() {
        scope.launch {
            val p = withContext(Dispatchers.IO) { runCatching { service.pendingActions() }.getOrDefault(emptyList()) }
            pending = p
            evidences = withContext(Dispatchers.IO) {
                p.associate { a -> a.id to runCatching { service.stepEvidences(a.id) }.getOrDefault(emptyList()) }
            }
            tiers = withContext(Dispatchers.IO) {
                p.associate { a -> a.id to runCatching { service.tierOfAction(a.id) }.getOrDefault(2) }
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

    MemorePage(
        title = "信",
        onBack = onBack,
        actions = {
            Text(
                "历史",
                style = MemoreType.meta,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(end = 16.dp)
                    .clickable(onClick = onOpenHistory)
            )
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Spacer(Modifier.height(4.dp))

            pending.forEach { action ->
                PendingItem(
                    action = action,
                    verbSummary = summaryOf(service, action),
                    tier = tiers[action.id] ?: 2,
                    evidences = evidences[action.id].orEmpty(),
                    // 高危动作：先弹确认，再带 confirmed=true 批准（后端也会拦一次）
                    onApprove = {
                        if (action.requiresConfirm) {
                            confirmApprove = action
                        } else {
                            run {
                                val r = service.approve(action.id)
                                r.optString("note").ifBlank { if (r.optBoolean("ok")) "已执行" else "失败" }
                            }
                        }
                    },
                    onReject = { run { service.reject(action.id); "已忽略" } },
                    onRecords = { onOpenRecords(action.id) }
                )
                Box(Modifier.fillMaxWidth().height(1.dp).background(mirrorHairlineColor()))
            }

            if (result.isNotBlank()) {
                Spacer(Modifier.height(16.dp))
                Text(result, style = MemoreType.meta, color = MaterialTheme.colorScheme.primary)
            }

            Spacer(Modifier.height(28.dp))
        }
    }

    // 高危动作的二次确认（2026-11-15）：确认后才带 confirmed=true 执行
    confirmApprove?.let { action ->
        MirrorAlertDialog(
            onDismissRequest = { confirmApprove = null },
            title = { Text("确认执行？", style = MaterialTheme.typography.headlineSmall) },
            text = {
                Text(
                    "这个动作标了不可撤销，执行后要手动撤回。",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmApprove = null
                    run {
                        val r = service.approve(action.id, confirmed = true)
                        r.optString("note").ifBlank {
                            r.optString("error").ifBlank { if (r.optBoolean("ok")) "已执行" else "失败" }
                        }
                    }
                }) {
                    Text("执行", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmApprove = null }) {
                    Text("取消", color = MaterialTheme.colorScheme.primary)
                }
            }
        )
    }
}

@Composable
private fun PendingItem(
    action: AgentAction,
    verbSummary: String,
    tier: Int,
    evidences: List<MirrorActionService.StepEvidence>,
    onApprove: () -> Unit,
    onReject: () -> Unit,
    onRecords: () -> Unit
) {
    var open by androidx.compose.runtime.saveable.rememberSaveable(action.id) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 14.dp)) {
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
        if (evidences.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text(
                if (open) "收起步骤" else "${evidences.size} 步 · 展开",
                style = MemoreType.micro,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable { open = !open }
            )
            if (open) {
                evidences.take(20).forEachIndexed { i, e ->
                    Spacer(Modifier.height(7.dp))
                    Text(
                        "${i + 1}. ${e.label}",
                        style = MemoreType.meta,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    val pair = listOf(e.fromSnippet, e.toSnippet).filter { it.isNotBlank() }.joinToString("  ↔  ")
                    if (pair.isNotBlank()) {
                        Text(
                            pair,
                            style = MemoreType.micro,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (e.why.isNotBlank()) {
                        Text(
                            e.why,
                            style = MemoreType.micro,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (e.quoteA.isNotBlank() && e.quoteB.isNotBlank()) {
                        Text(
                            "「${e.quoteA}」  「${e.quoteB}」",
                            style = MemoreType.micro,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f)
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "相关记录",
            style = MemoreType.meta,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.clickable(onClick = onRecords)
        )
        Spacer(Modifier.height(10.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "忽略",
                style = MemoreType.meta,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.clickable(onClick = onReject).padding(horizontal = 14.dp, vertical = 4.dp)
            )
            // 两个动作之间的细分隔线（2026-11-15 用户要求）：1dp 竖线，中线档 0.18
            Box(
                Modifier
                    .height(12.dp)
                    .width(1.dp)
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f))
            )
            Text(
                "批准",
                style = MemoreType.meta,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable(onClick = onApprove).padding(horizontal = 14.dp, vertical = 4.dp)
            )
        }
    }
}

/** 动词摘要：打标签 · 归来源 · 合并标签 */
private fun summaryOf(service: MirrorActionService, action: AgentAction): String = runCatching {
    service.stepsForId(action.id).map { it.label }.distinct().joinToString(" · ")
}.getOrDefault("")
