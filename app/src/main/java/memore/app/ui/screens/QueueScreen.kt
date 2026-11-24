package memore.app.ui.screens

import memore.app.ui.components.collectKeptAsState
import androidx.compose.foundation.clickable
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.background
import memore.app.ui.components.MirrorStackDialog
import android.widget.Toast
import memore.app.ui.components.MirrorAlertDialog
import memore.app.utils.memoreToast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import memore.app.data.model.AiTask
import memore.app.ui.components.MirrorSheet
import memore.app.ui.components.MirrorShapes
import memore.app.ui.components.mirrorHairline
import memore.app.data.repository.RecordRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueueSheet(
    recordRepository: RecordRepository,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showCancelConfirm by remember { mutableStateOf<AiTask?>(null) }

    // 直接观察 DB（原先靠 3 秒轮询）：任务一完成/失败，列表立刻自己更新
    // 2026-11-17：同样不做跨页缓存 —— 这是"在跑的任务"，不是内容列表；
    // 缓存会把已完成的任务留成旧快照，打开队列会先闪出并不存在的任务。
    val tasks = recordRepository.observeActiveTasks()
        .collectAsState(initial = emptyList()).value

    MirrorSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 32.dp)) {
            Text("AI 分析队列", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(12.dp))

            if (tasks.isEmpty()) {
                Box(Modifier.fillMaxWidth().padding(vertical = 48.dp), contentAlignment = Alignment.Center) {
                    Text("暂无等待中的任务", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(tasks, key = { it.id }) { task ->
                        TaskRow(task = task, recordRepository = recordRepository, onCancel = { showCancelConfirm = task })
                    }
                }
            }
        }
    }

    showCancelConfirm?.let { task ->
        MirrorStackDialog(
            title = "取消任务",
            message = "确定取消该任务吗？已生成的分析结果不会被保留。",
            confirmText = "确定",
            destructive = true,
            onDismiss = { showCancelConfirm = null },
            onConfirm = {
                scope.launch {
                    recordRepository.deleteAiTask(task.id)
                    recordRepository.updateAiStatus(task.recordId, "NONE")
                    context.memoreToast("任务已取消", Toast.LENGTH_SHORT)
                }
                showCancelConfirm = null
            }
        )
    }
}

@Composable
private fun TaskRow(
    task: AiTask,
    recordRepository: RecordRepository,
    onCancel: () -> Unit
) {
    var recordPreview by remember(task.recordId) { mutableStateOf("") }

    LaunchedEffect(task.recordId) {
        val record = recordRepository.getRecordById(task.recordId)
        recordPreview = record?.content?.take(20) ?: "记录已删除"
    }

    val statusText = when (task.status) {
        "PENDING" -> "等待中"
        "PROCESSING" -> "处理中"
        "WAITING_SERVER" -> "等待电脑上线"
        "FAILED" -> "失败"
        "STALE" -> "已过期"
        else -> task.status
    }

    val statusColor = when (task.status) {
        "PENDING" -> Color(0xFF4FC3F7)
        "PROCESSING" -> Color(0xFFFFA726)
        "WAITING_SERVER" -> Color(0xFF9E9E9E)
        "FAILED" -> Color(0xFFE53935)
        "STALE" -> Color(0xFFFFA726)
        else -> Color(0xFF9E9E9E)
    }

    // Q1：卡片改行 —— 内容摘要 + 状态数据 + 行尾文字动作
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().height(52.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    recordPreview,
                    fontSize = 13.5.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    "${formatTimeAgo(task.createdAt)} · $statusText",
                    fontSize = 10.5.sp,
                    color = if (task.status == "FAILED") MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (task.status == "PENDING" || task.status == "WAITING_SERVER") {
                Text(
                    "取消",
                    fontSize = 12.5.sp,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.clickable { onCancel() }.padding(6.dp)
                )
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)))
    }
}

private fun formatTimeAgo(timestamp: Long): String {
    val diff = System.currentTimeMillis() - timestamp
    val seconds = diff / 1000
    val minutes = seconds / 60
    val hours = minutes / 60
    return when {
        minutes < 1 -> "刚刚"
        minutes < 60 -> "${minutes}分钟前"
        hours < 24 -> "${hours}小时前"
        else -> "${hours / 24}天前"
    }
}
