package memore.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import memore.app.data.model.Record
import memore.app.data.repository.OriginRepository
import memore.app.ui.components.MemorePage
import memore.app.ui.components.mirrorHairlineColor
import memore.app.ui.theme.MemoreType
import memore.app.utils.formatTimestamp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 某个来源下的记录（2026-11-13）。
 *
 * 为什么单独一页：来源详情页是"调参数"的地方（三个权重 + 改名 + 删除），
 * 再塞一张几百条的列表，两件事互相干扰。
 *
 * 两处按设计法补的（同日）：
 *  - **按月分组**：170 条一路排下去只能盲滑，月标给一个锚；
 *  - **类型徽标**：来源横跨随笔 / 对话录 / 增修，不标就分不出来。
 */
@Composable
fun OriginRecordsScreen(
    originId: String,
    originName: String,
    repository: memore.app.data.repository.OriginRepository,
    onOpenRecord: (String) -> Unit,
    onBack: () -> Unit
) {
    // 2026-11-14：这份实现和「标签/协议」那套一模一样的，合并成同一个模板。
    GroupRecordsScreen(
        title = originName,
        load = { repository.recordsOf(originId) },
        onOpenRecord = onOpenRecord,
        onBack = onBack
    )
}

@Composable
private fun RecordRow(record: Record, isLast: Boolean, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                record.type.label,
                style = MemoreType.label,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.width(8.dp))
            Text(
                formatTimestamp(record.createdAt),
                style = MemoreType.label,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (record.isLocked) {
                Spacer(Modifier.width(8.dp))
                Text(
                    "加密",
                    style = MemoreType.label,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(Modifier.height(5.dp))
        Text(
            record.content.replace(Regex("\\s+"), " ").trim(),
            style = MemoreType.body,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
    // 列表尾不画线（3.3）：最后一条下面是空白
    if (!isLast) {
        Box(
            Modifier.fillMaxWidth().height(1.dp)
                .background(mirrorHairlineColor())
        )
    }
}
