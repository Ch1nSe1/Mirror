package memore.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.TextSnippet
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import memore.app.data.model.Record

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SummaryViewer(
    record: Record,
    onDismiss: () -> Unit,
    summaryOverride: String? = null
) {
    val summarySheetState = rememberModalBottomSheetState()
    MirrorSheet(onDismissRequest = onDismiss, sheetState = summarySheetState, containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 32.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.TextSnippet, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(8.dp))
                Text("\u603B\u7ED3", style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.height(16.dp))
            // 2026-11-19：锁定记录**不许露总结**。以前这里直接读 record.analysis / dialogueAiContent，
            // 区域页这类没有调用方保护的地方，点一下总结就把锁定内容的摘要摊出来了。
            // 现在只认临时解密态（override 非空）。
            val summaryText = summaryTextFor(record, summaryOverride)
            Box(modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface, MirrorShapes.row).padding(12.dp)) {
                CopyableText(
                    summaryText.ifBlank { "已锁定 · 先解锁再查看" },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            Spacer(Modifier.height(16.dp))
            OutlinedButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("\u5173\u95ED") }
        }
    }
}

/**
 * 总结正文该显示什么（2026-11-19 抽出，便于测试）。
 *
 * 规则：锁定记录只认「临时解密后拿到的 override」，绝不回落到库里存的 analysis。
 * 没锁：override > analysis.summary > dialogueAiContent。
 */
internal fun summaryTextFor(record: Record, summaryOverride: String?): String {
    val override = summaryOverride?.takeIf { it.isNotBlank() }
    if (record.isLocked) return override.orEmpty()
    return override
        ?: record.analysis?.summary?.takeIf { it.isNotBlank() }
        ?: record.dialogueAiContent.orEmpty()
}
