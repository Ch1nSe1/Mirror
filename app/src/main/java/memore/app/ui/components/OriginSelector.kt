package memore.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import memore.app.data.model.Origin
import memore.app.ui.theme.MemoreType

/**
 * 选择来源（2026-11-12 统一容器；2026-11-17 统一成 [MirrorCheckSheet] 的对号行）。
 *
 * 来源是 0..1：点一行就选中并关闭，所以没有确认键。
 * 「不标」是**清除**，放在列表末尾、用一条更明显的线隔开 —— 它不是第六个来源。
 */
@Composable
fun OriginSelectorSheet(
    origins: List<Origin>,
    currentId: String?,
    onPick: (String?) -> Unit,
    onDismiss: () -> Unit
) {
    MirrorCheckSheet(
        title = "来源",
        onDismiss = onDismiss,
        maxListHeight = 420.dp
    ) {
        if (origins.isEmpty()) {
            item(key = "empty") {
                Text(
                    "还没有来源",
                    style = MemoreType.meta,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 12.dp)
                )
            }
        }
        origins.forEach { origin ->
            item(key = origin.id) {
                // 「AI」是 agent 独占的来源（AI 札记的默认来源），可见但不可手动选。
                MirrorCheckRow(
                    label = origin.name,
                    selected = origin.id == currentId,
                    enabled = origin.id != Origin.ID_AGENT_AI,
                    onClick = { if (origin.id != Origin.ID_AGENT_AI) onPick(origin.id) }
                )
            }
        }
        item(key = "__unassigned__") {
            Spacer(Modifier.height(8.dp))
            Box(
                Modifier.fillMaxWidth().height(1.dp)
                    .background(mirrorHairlineColor())
            )
            MirrorCheckRow(
                label = "不标",
                selected = currentId == null,
                onClick = { onPick(null) }
            )
        }
    }
}
