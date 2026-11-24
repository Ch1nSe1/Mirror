package memore.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import memore.app.data.model.Record
import memore.app.ui.components.mirrorHairlineColor
import memore.app.ui.theme.MemoreType
import memore.app.utils.formatTimestamp

/**
 * 模板 A：记录行的唯一实现（2026-11-14）。
 *
 * 来源、标签、协议、情绪、概念 —— 五个入口全用这一行。
 * 之前它们各写各的（有的带 ⋮、有的带引用缩进、有的行距不同），用户原话：
 * 「我是让你统一界面，也就是只留一个」。
 *
 * 节奏 45dp；引用行（情绪页的「为什么判成这个情绪」）**与正文同左缘**，
 * 只靠灰度和括号区分，不再缩进——缩进会把左缘从 2 档变成 4 档。
 */
@Composable
fun RecordLine(
    record: Record,
    isLast: Boolean,
    onClick: () -> Unit,
    quote: String? = null,
) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(top = 18.dp, bottom = 18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(record.type.label, style = MemoreType.label, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(8.dp))
            Text(
                formatTimestamp(record.createdAt),
                style = MemoreType.label,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (record.isLocked) {
                Spacer(Modifier.width(8.dp))
                Text("加密", style = MemoreType.label, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            record.content.replace(Regex("\\s+"), " ").trim(),
            style = MemoreType.body,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis
        )
        if (!quote.isNullOrBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(
                "「$quote」",
                style = MemoreType.meta,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
    if (!isLast) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(mirrorHairlineColor()))
    }
}
