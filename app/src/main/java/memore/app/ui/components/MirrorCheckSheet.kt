package memore.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import memore.app.ui.theme.MemoreType

/**
 * 统一的"选择层"（2026-11-17）。
 *
 * 之前标签、协议、区域、来源是四份实现：复选框 / 文字变主色 / 对号三种选中表现，
 * 标签和协议还各有一颗「确认」键（而且那颗键会被列表挤出可视区，得再滑一次才看得到）。
 *
 * 现在统一成一种：
 *
 *   标题（名词：标签 / 协议 / 区域 / 来源）
 *   ────────────────────────────
 *   名称            12 条      ✓      ← 选中只有右侧一个对号
 *   ────────────────────────────
 *
 * 点行就切换。多选不自动关；单选（来源）点完由调用方关闭。
 * 默认**没有确认键**；只有批量改多条的场景才传 [footer]（那颗键固定在列表下方，不再被挤出屏幕）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MirrorCheckSheet(
    title: String,
    onDismiss: () -> Unit,
    footer: (@Composable () -> Unit)? = null,
    maxListHeight: androidx.compose.ui.unit.Dp = 440.dp,
    content: LazyListScope.() -> Unit
) {
    MirrorSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        sheetState = rememberModalBottomSheetState()
    ) {
        // footer 存在时：Column 给一个**有界高度**（0.85 屏），列表用 weight(1f) 吃掉中间，
        // 那颗「确认」就永远贴底可见 —— 之前按钮排在列表后面、被挤出可视区，
        // 用户得再滑一次才看得到（2026-11-17 用户报的现象）。
        Column(
            Modifier.fillMaxWidth()
                .then(if (footer != null) Modifier.fillMaxHeight(0.85f) else Modifier)
                .padding(horizontal = 20.dp).padding(bottom = 24.dp)
        ) {
            Text(
                title,
                style = MemoreType.micro,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 2.dp)
            )
            if (footer != null) {
                LazyColumn(Modifier.fillMaxWidth().weight(1f)) { content() }
                Spacer(Modifier.height(14.dp))
                footer()
            } else {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = maxListHeight)) { content() }
            }
        }
    }
}

/** 一条可选行：名称 +（可选）条数 +（选中时）右侧对号。点击即切换。 */
@Composable
fun MirrorCheckRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    trailing: String? = null,
    indent: Boolean = false,
    /** false = 看得见但点不动（内置来源「AI」这种由 agent 独占的项）。 */
    enabled: Boolean = true
) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().height(48.dp).clickable(enabled = enabled, onClick = onClick)
                .padding(start = if (indent) 20.dp else 0.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                label,
                style = MemoreType.body,
                color = when {
                    !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
                    selected -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.onSurface
                }
            )
            Spacer(Modifier.weight(1f))
            if (trailing != null) {
                Text(trailing, style = MemoreType.meta, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(10.dp))
            }
            // 未选中也占同一格，选中与否不会让名称左右跳
            Text(
                if (selected) "✓" else "",
                style = MemoreType.body,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.width(16.dp)
            )
        }
        Box(
            Modifier.fillMaxWidth().padding(start = if (indent) 20.dp else 0.dp).height(1.dp)
                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f))
        )
    }
}

/** 「组」折叠行（只有标签选择里有）：右侧 N 标签 + 折叠箭头，点击展开/收起。 */
@Composable
fun MirrorGroupRow(
    name: String,
    countLabel: String,
    expanded: Boolean,
    onClick: () -> Unit
) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().height(48.dp).clickable(onClick = onClick),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(name, style = MemoreType.body, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.weight(1f))
            Text(countLabel, style = MemoreType.meta, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(6.dp))
            Icon(
                if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)))
    }
}

/** 小分组标题（"组" / "未分组"）。 */
@Composable
fun MirrorCheckSectionLabel(text: String, topPadding: Int = 12) {
    Text(
        text,
        style = MemoreType.micro,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = topPadding.dp, bottom = 2.dp)
    )
}
