package memore.app.ui.components

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import memore.app.data.repository.ProtocolRepository
import memore.app.ui.theme.MemoreType

/**
 * 选择协议（2026-11-17 统一成 [MirrorCheckSheet]）。
 *
 * 和标签选择同一套行：名称 + N 条 + 右侧对号；点行即生效（[onSelectionChange]）。
 * 只有批量改多条时才传 [onConfirm]，那颗键固定在列表下方。
 */
@Composable
fun ProtocolSelectorDialog(
    protocolRepository: ProtocolRepository,
    currentProtocolIds: Set<Int>,
    onDismiss: () -> Unit,
    onSelectionChange: ((Set<Int>) -> Unit)? = null,
    onConfirm: ((Set<Int>) -> Unit)? = null
) {
    val allProtocols by protocolRepository.getAllProtocols().collectAsState(initial = emptyList())
    var selectedIds by remember { mutableStateOf(currentProtocolIds) }

    fun toggle(id: Int) {
        selectedIds = if (id in selectedIds) selectedIds - id else selectedIds + id
        onSelectionChange?.invoke(selectedIds)
    }

    MirrorCheckSheet(
        title = "协议",
        onDismiss = onDismiss,
        footer = onConfirm?.let {
            {
                Button(
                    onClick = { it(selectedIds) },
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                    shape = MirrorShapes.card
                ) { Text("确认", style = MemoreType.button, color = MaterialTheme.colorScheme.onPrimary) }
            }
        }
    ) {
        if (allProtocols.isEmpty()) {
            item(key = "empty") {
                Text(
                    "还没有协议",
                    style = MemoreType.meta,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 12.dp)
                )
            }
        }
        allProtocols.forEach { protocol ->
            item(key = protocol.id) {
                MirrorCheckRow(
                    label = protocol.name,
                    selected = protocol.id in selectedIds,
                    trailing = "${protocol.usageCount} 条",
                    onClick = { toggle(protocol.id) }
                )
            }
        }
    }
}

