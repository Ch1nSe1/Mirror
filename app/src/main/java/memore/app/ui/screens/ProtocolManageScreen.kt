package memore.app.ui.screens

import memore.app.ui.components.collectKeptAsState
import memore.app.ui.components.MemoreLineInput
import memore.app.ui.theme.MemoreType
import memore.app.ui.components.MirrorStackDialog
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import memore.app.data.repository.ProtocolRepository
import memore.app.ui.components.MirrorAlertDialog
import memore.app.ui.components.MirrorDropdownMenu
import memore.app.ui.components.MirrorMenu
import memore.app.ui.components.MirrorMenuAction
import memore.app.ui.components.MirrorShapes
import androidx.compose.material.icons.filled.Add
import memore.app.ui.components.MemorePage
import memore.app.ui.components.MirrorLineField
import memore.app.ui.components.mirrorHairlineColor
import memore.app.utils.UiSoundPlayer
import kotlinx.coroutines.launch

/**
 * 管理协议（2026-10-24 去卡片，方案 A）：
 * 行 + 计数 + ⋮（改名/删除），新建收进顶部一行就地展开。
 * 青色只属于"协议"这个数据的语义，不再用作按钮色。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProtocolManageScreen(protocolRepository: ProtocolRepository, onOpenRecords: (String) -> Unit = {}, onBack: () -> Unit) {
    val context = LocalContext.current
    val allProtocolsFlow = remember { protocolRepository.getAllProtocols() }
    val allProtocols = allProtocolsFlow.collectKeptAsState("ProtocolManageScreen.allProtocols")
    val scope = rememberCoroutineScope()

    var creating by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    var editingId by remember { mutableStateOf<Int?>(null) }
    var editName by remember { mutableStateOf("") }
    var menuFor by remember { mutableStateOf<Int?>(null) }
    var showDeleteDialog by remember { mutableStateOf<memore.app.data.model.Protocol?>(null) }

    MemorePage(
        title = "管理协议",
        onBack = onBack,
        actions = {
            // 1.4：单个入口放顶栏右端，不再占一行正文
            IconButton(onClick = { creating = true }) {
                Icon(Icons.Filled.Add, contentDescription = "新建协议", tint = MaterialTheme.colorScheme.primary)
            }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp)) {
            // 新建：一行，点开就地出输入
            if (creating) {
                MemoreLineInput(
                    value = newName,
                    onValueChange = { if (it.length <= 20) newName = it },
                    singleLine = true,
                    placeholder = { Text("新协议名") },
                    shape = MirrorShapes.card,
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow
                    )
                )
                Row(
                    Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TextButton(onClick = { creating = false; newName = "" }) { Text("取消") }
                    Spacer(Modifier.weight(1f))
                    Button(
                        onClick = {
                            val t = newName.trim()
                            if (t.isNotBlank()) {
                                scope.launch { protocolRepository.getOrCreateProtocol(t) }
                                newName = ""
                                creating = false
                                UiSoundPlayer.playProtocolCreate(context)
                            }
                        },
                        enabled = newName.isNotBlank(),
                        shape = MirrorShapes.menu,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                    ) { Text("创建") }
                }
            }

            LazyColumn(Modifier.weight(1f)) {
                items(allProtocols, key = { it.id }) { protocol ->
                    Column(Modifier.fillMaxWidth()) {
                        if (editingId == protocol.id) {
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                MemoreLineInput(
                                    value = editName,
                                    onValueChange = { if (it.length <= 20) editName = it },
                                    singleLine = true,
                                    shape = MirrorShapes.row,
                                    modifier = Modifier.weight(1f),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                                        unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
                                    )
                                )
                                TextButton(onClick = {
                                    scope.launch { protocolRepository.renameProtocol(protocol.id, editName.trim()).getOrNull() }
                                    editingId = null
                                }) { Text("确定", color = MaterialTheme.colorScheme.primary) }
                            }
                        } else {
                            Row(
                                Modifier.fillMaxWidth().height(56.dp).clickable { onOpenRecords(protocol.name) },
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    protocol.name,
                                    fontSize = 14.5.sp,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    "${protocol.usageCount} 条",
                                    style = MemoreType.meta,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(Modifier.width(4.dp))
                                Box {
                                    IconButton(onClick = { menuFor = protocol.id }, modifier = Modifier.size(48.dp)) {
                                        Icon(
                                            Icons.Outlined.MoreVert, "更多", Modifier.size(18.dp),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    MirrorMenu(
                                        expanded = menuFor == protocol.id,
                                        onDismissRequest = { menuFor = null },
                                        actions = listOf(
                                            MirrorMenuAction("改名") {
                                                menuFor = null
                                                editingId = protocol.id
                                                editName = protocol.name
                                            },
                                            MirrorMenuAction("删除", danger = true) {
                                                menuFor = null
                                                showDeleteDialog = protocol
                                            }
                                        )
                                    )
                                }
                            }
                        }
                        // 列表尾不画线（3.3）：最后一项下面是空白，不要给它句号
                        if (protocol.id != allProtocols.lastOrNull()?.id) {
                            Box(
                                Modifier.fillMaxWidth().height(1.dp)
                                    .background(mirrorHairlineColor())
                            )
                        }
                    }
                }
            }
        }
    }

    if (showDeleteDialog != null) {
        val p = showDeleteDialog!!
        MirrorStackDialog(
            title = "删除协议",
            message = "将从 ${p.usageCount} 条记录中移除该协议。",
            confirmText = "删除",
            destructive = true,
            onDismiss = { showDeleteDialog = null },
            onConfirm = {
                scope.launch { protocolRepository.deleteProtocol(p.id) }
                showDeleteDialog = null
                UiSoundPlayer.playProtocolDelete(context)
            }
        )
    }
}
