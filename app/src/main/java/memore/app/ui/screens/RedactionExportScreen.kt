package memore.app.ui.screens

import memore.app.ui.components.MirrorStackDialog
import memore.app.ui.components.MemoreLineInput
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.unit.sp
import memore.app.ui.components.MirrorDropdownMenu
import androidx.compose.material.icons.outlined.KeyboardArrowRight
import androidx.compose.foundation.background
import android.widget.Toast
import memore.app.ui.components.MirrorAlertDialog
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import memore.app.data.model.RedactionGroup
import memore.app.ui.components.MirrorShapes
import memore.app.ui.components.mirrorHairline
import memore.app.ui.components.MirrorMenuAction
import memore.app.ui.components.MirrorMenu
import memore.app.data.repository.RedactionRepository
import memore.app.utils.RedactionEngine
import memore.app.utils.UiSoundPlayer
import memore.app.utils.memoreToast
import kotlinx.coroutines.launch
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RedactionExportScreen(
    redactionRepository: RedactionRepository,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var groups by remember { mutableStateOf<List<RedactionGroup>>(emptyList()) }
    var redactEnabled by remember { mutableStateOf(redactionRepository.redactionEnabled()) }
    var importBlockEnabled by remember { mutableStateOf(redactionRepository.importBlockEnabled()) }
    var redactionMode by remember { mutableStateOf(redactionRepository.redactionMode()) }
    var page by remember { mutableStateOf(0) }
    var editing by remember { mutableStateOf<RedactionGroup?>(null) }

    fun load() {
        scope.launch {
            runCatching { redactionRepository.loadGroups() }
                .onSuccess { groups = it }
                .onFailure { context.memoreToast("读取失败：${it.message}", Toast.LENGTH_LONG) }
        }
    }
    LaunchedEffect(Unit) { load() }

    // 系统返回键：有内部页(映射组列表/编辑)则逐级回退，否则退出该屏（交由父导航）
    BackHandler {
        if (page > 0) page--
        else onBack()
    }

    if (page == 2) {
        RedactionGroupEditor(
            editing = editing,
            onSave = { group ->
                scope.launch {
                    val result = runCatching {
                        if (editing == null) redactionRepository.addGroup(group)
                        else redactionRepository.updateGroup(group)
                    }
                    result.onSuccess {
                        UiSoundPlayer.playSave(context)
                        page = 1
                        load()
                    }.onFailure {
                        context.memoreToast("保存失败：${it.message}", Toast.LENGTH_LONG)
                        UiSoundPlayer.playError(context)
                    }
                }
            },
            onDelete = if (editing != null) { {
                scope.launch {
                    val result = runCatching { redactionRepository.deleteGroup(editing!!.id) }
                    result.onSuccess {
                        UiSoundPlayer.playDelete(context)
                        page = 1
                        load()
                    }.onFailure {
                        context.memoreToast("删除失败：${it.message}", Toast.LENGTH_LONG)
                        UiSoundPlayer.playError(context)
                    }
                }
            } } else null,
            onCancel = { page = 1 }
        )
        return
    }

    if (page == 1) {
        MappingListPage(
            groups = groups,
            onBack = { page = 0 },
            onAdd = { editing = null; page = 2 },
            onEdit = { group -> editing = group; page = 2 }
        )
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("脱敏导出") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        // S2：分三组、行式（2026-10-24 去卡片）
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp)) {
            Text(
                "脱敏",
                fontSize = 10.5.sp,
                letterSpacing = 1.1.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 14.dp, bottom = 2.dp)
            )
            RedactionSwitchRow(
                title = "启用脱敏",
                subtitle = "命中后替换为 0x 乱码",
                checked = redactEnabled,
                onToggle = {
                    redactEnabled = it
                    redactionRepository.setRedactionEnabled(it)
                    if (it) UiSoundPlayer.playRedactionOn(context) else UiSoundPlayer.playRedactionOff(context)
                }
            )

            Text(
                "导入保护",
                fontSize = 10.5.sp,
                letterSpacing = 1.1.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 18.dp, bottom = 2.dp)
            )
            RedactionSwitchRow(
                title = "禁止导入",
                subtitle = "脱敏包不能导回来",
                checked = importBlockEnabled,
                onToggle = {
                    importBlockEnabled = it
                    redactionRepository.setImportBlockEnabled(it)
                    if (it) UiSoundPlayer.playImportBlockOn(context) else UiSoundPlayer.playImportBlockOff(context)
                }
            )

            Text(
                "映射",
                fontSize = 10.5.sp,
                letterSpacing = 1.1.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 18.dp, bottom = 2.dp)
            )
            // 乱码方式：行 + 值，点开二选一
            var modeMenu by remember { mutableStateOf(false) }
            Box {
                Row(
                    Modifier.fillMaxWidth().height(50.dp).clickable { modeMenu = true },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("乱码方式", fontSize = 14.5.sp, color = MaterialTheme.colorScheme.onSurface)
                    Spacer(Modifier.weight(1f))
                    Text(
                        if (redactionMode == "hash") "哈希" else "随机",
                        fontSize = 12.5.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.width(5.dp))
                    Icon(
                        Icons.Outlined.KeyboardArrowRight, null, Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                MirrorMenu(
                    expanded = modeMenu,
                    onDismissRequest = { modeMenu = false },
                    actions = listOf(
                        MirrorMenuAction("随机") {
                            modeMenu = false
                            redactionMode = "random"
                            redactionRepository.setRedactionMode("random")
                        },
                        MirrorMenuAction("哈希") {
                            modeMenu = false
                            redactionMode = "hash"
                            redactionRepository.setRedactionMode("hash")
                        }
                    )
                )
            }
            RedactionHairline()
            Row(
                Modifier.fillMaxWidth().height(50.dp).clickable { page = 1 },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("映射组", fontSize = 14.5.sp, color = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.weight(1f))
                Text(
                    "${groups.size} 组",
                    fontSize = 12.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(5.dp))
                Icon(
                    Icons.Outlined.KeyboardArrowRight, null, Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            RedactionHairline()
        }
    }
}

@Composable
private fun RedactionToggleCard(
    title: String,
    subtitle: String,
    checked: Boolean,
    accent: Color,
    onToggle: (Boolean) -> Unit
) {
    Card(
        shape = MirrorShapes.card,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        border = mirrorHairline()
    ) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(color = accent.copy(alpha = 0.12f), shape = MirrorShapes.pill) {
                Text(
                    title,
                    style = MaterialTheme.typography.labelSmall,
                    color = accent,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(
                checked = checked,
                onCheckedChange = onToggle,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = accent,
                    checkedTrackColor = accent.copy(alpha = 0.3f)
                )
            )
        }
    }
}

@Composable
private fun RedactionModeCard(
    mode: String,
    onSelect: (String) -> Unit
) {
    Card(
        shape = MirrorShapes.card,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        border = mirrorHairline()
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text("乱码方式", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = mode == RedactionEngine.MODE_RANDOM,
                    label = { Text("随机") },
                    onClick = { onSelect(RedactionEngine.MODE_RANDOM) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Color(0xFFA78BFA).copy(alpha = 0.16f),
                        selectedLabelColor = MaterialTheme.colorScheme.onSurface
                    )
                )
                FilterChip(
                    selected = mode == RedactionEngine.MODE_HASH,
                    label = { Text("哈希") },
                    onClick = { onSelect(RedactionEngine.MODE_HASH) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Color(0xFF4DD0E1).copy(alpha = 0.16f),
                        selectedLabelColor = MaterialTheme.colorScheme.onSurface
                    )
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MappingListPage(
    groups: List<RedactionGroup>,
    onBack: () -> Unit,
    onAdd: () -> Unit,
    onEdit: (RedactionGroup) -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("映射组") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回")
                    }
                },
                actions = {
                    IconButton(onClick = onAdd) {
                        Icon(Icons.Outlined.Add, "新建", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },

        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        // 行 + ⋮（2026-10-24 去卡片、去吸底按钮；+ 仍在顶栏）
        var menuFor by remember { mutableStateOf<String?>(null) }
        LazyColumn(Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp)) {
            items(groups.sortedBy { it.standardName }, key = { it.id }) { group ->
                Column(Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f).clickable { onEdit(group) }) {
                            Text(
                                group.standardName,
                                fontSize = 15.sp,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                            )
                            Spacer(Modifier.height(3.dp))
                            Text(
                                group.aliases.joinToString("、").ifBlank { "仅标准名称" },
                                fontSize = 11.5.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                            )
                        }
                        Text(
                            "${group.aliases.size} 个别名",
                            fontSize = 10.5.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.width(2.dp))
                        Box {
                            IconButton(onClick = { menuFor = group.id }, modifier = Modifier.size(30.dp)) {
                                Icon(
                                    Icons.Outlined.MoreVert, "更多", Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            MirrorMenu(
                                expanded = menuFor == group.id,
                                onDismissRequest = { menuFor = null },
                                actions = listOf(
                                    MirrorMenuAction("编辑") { menuFor = null; onEdit(group) }
                                )
                            )
                        }
                    }
                    Box(
                        Modifier.fillMaxWidth().height(1.dp)
                            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f))
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun RedactionGroupEditor(
    editing: RedactionGroup?,
    onSave: (RedactionGroup) -> Unit,
    onDelete: (() -> Unit)?,
    onCancel: () -> Unit
) {
    val context = LocalContext.current
    var standardName by remember { mutableStateOf(editing?.standardName ?: "") }
    var aliases by remember { mutableStateOf(editing?.aliases ?: emptyList()) }
    var input by remember { mutableStateOf("") }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    fun buildGroup() = RedactionGroup(
        id = editing?.id ?: UUID.randomUUID().toString(),
        standardName = standardName.trim(),
        aliases = aliases,
        createdAt = editing?.createdAt ?: System.currentTimeMillis()
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (editing == null) "新建映射组" else "编辑映射组") },
                navigationIcon = { IconButton(onClick = onCancel) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.background) {
                Button(
                    onClick = { onSave(buildGroup()) },
                    enabled = standardName.isNotBlank(),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp).height(48.dp),
                    shape = MirrorShapes.card,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Text("保存", fontSize = 15.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, color = MaterialTheme.colorScheme.onPrimary)
                }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp)) {
            Spacer(Modifier.height(8.dp))
            Text("标准名称", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            MemoreLineInput(
                value = standardName,
                onValueChange = { standardName = it },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = MirrorShapes.row,
                placeholder = { Text("输入标准名称") }
            )
            Spacer(Modifier.height(20.dp))
            // F1：输入行（添加是文字动作）+ 别名逐行列出
            Row(verticalAlignment = Alignment.CenterVertically) {
                MemoreLineInput(
                    value = input,
                    onValueChange = { input = it },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    shape = MirrorShapes.card,
                    placeholder = { Text("输入别名") },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow
                    )
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    "添加",
                    fontSize = 13.5.sp,
                    color = if (input.isBlank()) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable {
                        val t = input.trim()
                        if (t.isEmpty()) {
                            context.memoreToast("别名不能为空", Toast.LENGTH_SHORT)
                        } else if (t in aliases) {
                            context.memoreToast("该别名已存在", Toast.LENGTH_SHORT)
                        } else {
                            aliases = aliases + t
                            input = ""
                        }
                    }.padding(horizontal = 6.dp, vertical = 8.dp)
                )
            }
            Spacer(Modifier.height(8.dp))
            Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())) {
                if (aliases.isEmpty()) {
                    Text(
                        "还没有别名",
                        fontSize = 11.5.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 10.dp)
                    )
                } else {
                    aliases.forEach { alias ->
                        Row(
                            Modifier.fillMaxWidth().height(44.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(alias, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                            Text(
                                "✕",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.clickable { aliases = aliases - alias }.padding(horizontal = 8.dp, vertical = 6.dp)
                            )
                        }
                        Box(
                            Modifier.fillMaxWidth().height(1.dp)
                                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f))
                        )
                    }
                }
                if (editing != null) {
                    Spacer(Modifier.height(24.dp))
                    Text(
                        "删除此组",
                        fontSize = 13.5.sp,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.clickable { showDeleteConfirm = true }.padding(vertical = 8.dp)
                    )
                }
            }
        }
    }

    if (showDeleteConfirm) {
        MirrorStackDialog(
            title = "删除映射组",
            message = "确定删除「${editing?.standardName ?: ""}」吗？",
            confirmText = "删除",
            destructive = true,
            onDismiss = { showDeleteConfirm = false },
            onConfirm = { showDeleteConfirm = false; onDelete?.invoke() }
        )
    }
}
/** 开关行：标题 + 说明在同一行，说明可折行；右侧开关。 */
@Composable
private fun RedactionSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onToggle: (Boolean) -> Unit
) {
    Column {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 14.5.sp, color = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.height(2.dp))
                Text(subtitle, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(
                checked = checked,
                onCheckedChange = onToggle,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = MaterialTheme.colorScheme.primary,
                    checkedTrackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
                )
            )
        }
        RedactionHairline()
    }
}

@Composable
private fun RedactionHairline() {
    Box(
        Modifier.fillMaxWidth().height(1.dp)
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f))
    )
}
