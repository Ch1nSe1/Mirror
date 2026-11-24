package memore.app.ui.components

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import memore.app.data.repository.TagGroupRepository
import memore.app.data.repository.TagRepository
import memore.app.ui.theme.MemoreType

/**
 * 选择标签（2026-11-17 统一成 [MirrorCheckSheet]）。
 *
 * 改了什么：
 *  - 复选框 → 右侧对号（和区域/来源/协议同一种选中表现）
 *  - 「确认」默认去掉：每点一行就通过 [onSelectionChange] 立即生效（编辑页只改内存状态，
 *    保存时才落库，所以"即时"没有副作用）
 *  - 只有批量改多条时，调用方传 [onConfirm]，那颗「确认」固定在列表下方，不会再被挤出屏幕
 */
@Composable
fun TagSelectorDialog(
    tagRepository: TagRepository,
    tagGroupRepository: TagGroupRepository?,
    currentTagIds: Set<Int>,
    onDismiss: () -> Unit,
    onSelectionChange: ((Set<Int>) -> Unit)? = null,
    onConfirm: ((Set<Int>) -> Unit)? = null
) {
    val allTags by tagRepository.getAllTags().collectAsState(initial = emptyList())
    var selectedIds by remember { mutableStateOf(currentTagIds) }
    var groups by remember { mutableStateOf<List<memore.app.data.model.TagGroup>>(emptyList()) }
    var expandedGroups by remember { mutableStateOf<Set<Int>>(emptySet()) }
    LaunchedEffect(Unit) { groups = tagGroupRepository?.getGroups() ?: emptyList() }

    fun toggle(id: Int) {
        selectedIds = if (id in selectedIds) selectedIds - id else selectedIds + id
        onSelectionChange?.invoke(selectedIds)
    }

    val grouped = remember(groups, allTags) { groups.associate { g -> g.id to allTags.filter { it.groupId == g.id } } }
    val ungrouped = remember(allTags) { allTags.filter { it.groupId == null } }

    MirrorCheckSheet(
        title = "标签",
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
        if (allTags.isEmpty() && groups.isEmpty()) {
            item(key = "empty") {
                Text(
                    "还没有标签",
                    style = MemoreType.meta,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 12.dp)
                )
            }
        }
        if (groups.isNotEmpty()) {
            item(key = "groups_header") { MirrorCheckSectionLabel("组", topPadding = 8) }
        }
        groups.forEach { g ->
            val members = grouped[g.id] ?: emptyList()
            item(key = "g_${g.id}") {
                MirrorGroupRow(
                    name = g.name,
                    countLabel = "${members.size} 标签",
                    expanded = g.id in expandedGroups,
                    onClick = {
                        expandedGroups = if (g.id in expandedGroups) expandedGroups - g.id else expandedGroups + g.id
                    }
                )
            }
            if (g.id in expandedGroups) {
                members.forEach { tag ->
                    item(key = "t_${tag.id}") {
                        MirrorCheckRow(
                            label = "#${tag.name}",
                            selected = tag.id in selectedIds,
                            trailing = "${tag.usageCount} 条",
                            indent = true,
                            onClick = { toggle(tag.id) }
                        )
                    }
                }
            }
        }
        if (ungrouped.isNotEmpty()) {
            item(key = "ungrouped_header") { MirrorCheckSectionLabel("未分组", topPadding = 14) }
        }
        ungrouped.forEach { tag ->
            item(key = "u_${tag.id}") {
                MirrorCheckRow(
                    label = "#${tag.name}",
                    selected = tag.id in selectedIds,
                    trailing = "${tag.usageCount} 条",
                    onClick = { toggle(tag.id) }
                )
            }
        }
    }
}


