package memore.app.ui.screens

import memore.app.ui.components.collectKeptAsState
import memore.app.ui.components.MemoreLineInput
import memore.app.ui.theme.MemoreType
import memore.app.ui.components.MirrorInputField
import memore.app.ui.components.MirrorStackDialog
import androidx.compose.ui.text.font.FontWeight
import android.widget.Toast
import memore.app.ui.components.MirrorDropdownMenu
import memore.app.ui.components.MirrorAlertDialog
import memore.app.utils.memoreToast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import memore.app.data.model.Tag
import memore.app.data.model.TagGroup
import memore.app.data.repository.TagGroupRepository
import memore.app.data.repository.TagRepository
import memore.app.ui.components.IconBack
import memore.app.ui.theme.*
import memore.app.utils.UiSoundPlayer
import kotlinx.coroutines.launch
import memore.app.ui.components.MirrorShapes
import memore.app.ui.components.mirrorHairline
import memore.app.ui.components.MemorePage
import memore.app.ui.components.MirrorLineField
import memore.app.ui.components.MirrorMenu
import memore.app.ui.components.MirrorMenuAction
import memore.app.ui.components.mirrorHairlineColor
import memore.app.ui.components.mirrorPillBorder

/** 标签组管理：组=组织壳（青），成员=标签；未分组标签单列。记录仍挂标签上，组仅用于分类。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TagManageScreen(tagRepository: TagRepository, tagGroupRepository: TagGroupRepository, onOpenRecords: (String) -> Unit = {}, onBack: () -> Unit) {
    val allTagsFlow = remember { tagRepository.getAllTags() }
    val allTags = allTagsFlow.collectKeptAsState("TagManageScreen.allTags")
    var groups by remember { mutableStateOf<List<TagGroup>>(emptyList()) }
    var newTagName by rememberSaveable { mutableStateOf("") }
    var editingTagId by remember { mutableStateOf<Int?>(null) }; var editName by remember { mutableStateOf("") }
    var deleteConfirmTagId by remember { mutableStateOf<Int?>(null) }
    var showCreateGroup by remember { mutableStateOf(false) }; var createGroupText by remember { mutableStateOf("") }
    var detailGroupId by rememberSaveable(stateSaver = androidx.compose.runtime.saveable.listSaver<Int?, Int>(
        save = { listOfNotNull(it) }, restore = { it.firstOrNull() })) { mutableStateOf<Int?>(null) }
    var refreshKey by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope(); val context = LocalContext.current
    LaunchedEffect(Unit) { tagRepository.refreshAllCounts() }
    LaunchedEffect(refreshKey) {
        groups = tagGroupRepository.getGroups()
        tagRepository.refreshAllCounts()
    }

    val ungrouped = allTags.filter { it.groupId == null }
    val groupUsage: (TagGroup) -> Int = { g -> allTags.filter { it.groupId == g.id }.sumOf { it.usageCount } }

    var menuForTag by remember { mutableStateOf<Int?>(null) }
    var creatingTag by remember { mutableStateOf(false) }
    var creatingGroup by remember { mutableStateOf(false) }

    var showNewMenu by remember { mutableStateOf(false) }
    MemorePage(
        title = "管理标签组",
        onBack = onBack,
        actions = {
            // 1.4：≥2 个动作收进一个 ＋；4.1/4.6：两项菜单，无图标 → 分隔线缩 16
            IconButton(onClick = { showNewMenu = true }) {
                Icon(Icons.Filled.Add, contentDescription = "新建", tint = MaterialTheme.colorScheme.primary)
            }
            MirrorMenu(
                expanded = showNewMenu,
                onDismissRequest = { showNewMenu = false },
                actions = listOf(
                    MirrorMenuAction("新建标签") {
                        showNewMenu = false; creatingTag = true; creatingGroup = false; newTagName = ""
                    },
                    MirrorMenuAction("新建组") {
                        showNewMenu = false; creatingGroup = true; creatingTag = false; createGroupText = ""
                    }
                )
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp)) {
            // 新建标签（4.5：横线输入，取消/创建仍在下方一行）
            if (creatingTag) {
                MirrorLineField(
                    value = newTagName,
                    onValueChange = { if (it.length <= 20) newTagName = it },
                    placeholder = "新标签名（≤20字）",
                    label = "标签"
                )
                Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { creatingTag = false; newTagName = "" }) { Text("取消") }
                    Spacer(Modifier.weight(1f))
                    Button(
                        onClick = {
                            if (newTagName.isNotBlank()) {
                                scope.launch { tagRepository.getOrCreateTag(newTagName.trim()) }
                                newTagName = ""; creatingTag = false
                                UiSoundPlayer.playTagCreate(context)
                            }
                        },
                        enabled = newTagName.isNotBlank(),
                        shape = MirrorShapes.menu,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                    ) { Text("创建") }
                }
            }

            // 新建组（同一套表单）
            if (creatingGroup) {
                MirrorLineField(
                    value = createGroupText,
                    onValueChange = { if (it.length <= 20) createGroupText = it },
                    placeholder = "组名（≤20字）",
                    label = "组"
                )
                Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { creatingGroup = false; createGroupText = "" }) { Text("取消") }
                    Spacer(Modifier.weight(1f))
                    Button(
                        onClick = {
                            if (createGroupText.isNotBlank()) {
                                scope.launch { tagGroupRepository.create(createGroupText.trim()); UiSoundPlayer.playSave(context) }
                                createGroupText = ""; creatingGroup = false
                                refreshKey++
                            }
                        },
                        enabled = createGroupText.isNotBlank(),
                        shape = MirrorShapes.menu,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                    ) { Text("创建") }
                }
            }

            if (allTags.isEmpty() && groups.isEmpty()) {
                Text("暂无标签", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                LazyColumn {
                    if (groups.isNotEmpty()) {
                        item(key = "groups_title") {
                            Text(
                                "组",
                                style = MemoreType.micro,
                                letterSpacing = 1.1.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 10.dp, bottom = 4.dp)
                            )
                        }
                    }
                    items(groups, key = { "g_${it.id}" }) { g ->
                        Column(Modifier.fillMaxWidth()) {
                            Row(
                                Modifier.fillMaxWidth().height(56.dp).clickable { detailGroupId = g.id },
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    g.name,
                                    fontSize = 15.sp,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    "${groupUsage(g)} 条 · ${allTags.count { it.groupId == g.id }} 标签",
                                    style = MemoreType.meta,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(Modifier.width(6.dp))
                                Icon(
                                    Icons.Outlined.ChevronRight, null, Modifier.size(18.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                )
                            }
                            // 列表尾不画线（3.3）：最后一项下面是空白
                            if (g.id != groups.lastOrNull()?.id) {
                                Box(
                                    Modifier.fillMaxWidth().height(1.dp)
                                        .background(mirrorHairlineColor())
                                )
                            }
                        }
                    }
                    if (ungrouped.isNotEmpty()) {
                        item(key = "ungrouped_title") {
                            Text(
                                "未分组",
                                style = MemoreType.micro,
                                letterSpacing = 1.1.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 18.dp, bottom = 4.dp)
                            )
                        }
                    }
                    items(ungrouped, key = { it.id }) { tag ->
                        Column(Modifier.fillMaxWidth()) {
                            if (editingTagId == tag.id) {
                                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
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
                                        if (editName.isNotBlank()) {
                                            scope.launch { tagRepository.renameTag(tag.id, editName.trim()) }
                                            editingTagId = null
                                            UiSoundPlayer.playSave(context)
                                        }
                                    }) { Text("确定", color = MaterialTheme.colorScheme.primary) }
                                }
                            } else {
                                Row(
                                    Modifier.fillMaxWidth().height(56.dp).clickable { onOpenRecords(tag.name) },
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        "#${tag.name}",
                                        fontSize = 14.5.sp,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Text(
                                        "${tag.usageCount} 条",
                                        style = MemoreType.meta,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Spacer(Modifier.width(4.dp))
                                    Box {
                                        IconButton(onClick = { menuForTag = tag.id }, modifier = Modifier.size(48.dp)) {
                                            Icon(Icons.Outlined.MoreVert, "更多", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                        MirrorMenu(
                                            expanded = menuForTag == tag.id,
                                            onDismissRequest = { menuForTag = null },
                                            actions = listOf(
                                                MirrorMenuAction("改名") { menuForTag = null; editingTagId = tag.id; editName = tag.name },
                                                MirrorMenuAction("删除", danger = true) { menuForTag = null; deleteConfirmTagId = tag.id }
                                            )
                                        )
                                    }
                                }
                            }
                            // 列表尾不画线（3.3）：最后一项下面是空白
                            if (tag.id != ungrouped.lastOrNull()?.id) {
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

    deleteConfirmTagId?.let { id ->
        val t = allTags.find { it.id == id }
        MirrorStackDialog(
            title = "删除标签",
            message = "将从 ${t?.usageCount ?: 0} 条记录中移除该标签",
            confirmText = "删除",
            destructive = true,
            onDismiss = { deleteConfirmTagId = null },
            onConfirm = {
                scope.launch {
                    tagRepository.deleteTag(id)
                    deleteConfirmTagId = null
                    UiSoundPlayer.playTagDelete(context)
                }
            }
        )
    }

    detailGroupId?.let { gid ->
        Dialog(onDismissRequest = { detailGroupId = null }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            TagGroupDetailScreen(
                groupId = gid,
                tagGroupRepository = tagGroupRepository,
                tagRepository = tagRepository,
                onBack = { detailGroupId = null; refreshKey++ }
            )
        }
    }
}
}

/** 标签组详情：成员标签、增加/移除成员、重命名/删除组（删组→标签回未分组）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TagGroupDetailScreen(groupId: Int, tagGroupRepository: TagGroupRepository, tagRepository: TagRepository, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var group by remember { mutableStateOf<TagGroup?>(null) }
    var members by remember { mutableStateOf<List<Tag>>(emptyList()) }
    var refreshKey by remember { mutableIntStateOf(0) }
    var showAdd by remember { mutableStateOf(false) }
    var showRename by remember { mutableStateOf(false) }; var renameText by remember { mutableStateOf("") }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    suspend fun reload() {
        group = tagGroupRepository.getById(groupId)
        members = tagGroupRepository.getMembers(groupId)
    }
    LaunchedEffect(groupId, refreshKey) { reload() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(group?.name ?: "标签组", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回") } },
                actions = {
                    IconButton(onClick = { showRename = true; renameText = group?.name ?: "" }) { Text("改名", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    IconButton(onClick = { showDeleteConfirm = true }) { Text("删除", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error) }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("成员（${members.size}）", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                Button(onClick = { showAdd = true }, shape = MirrorShapes.row) {
                    Icon(Icons.Filled.Add, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("增加标签")
                }
            }
            if (members.isEmpty()) {
                Box(Modifier.weight(1f).fillMaxWidth()) {}
            } else {
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(members, key = { it.id }) { tag ->
                        var menu by remember { mutableStateOf(false) }
                        Surface(shape = MirrorShapes.row, color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth(), border = mirrorHairline()) {
                            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("#${tag.name}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                                    Text("${tag.usageCount} 条记录", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Box {
                                    IconButton(onClick = { menu = true }, modifier = Modifier.size(28.dp)) { Icon(Icons.Outlined.MoreVert, "更多", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                                    MirrorMenu(
                                        expanded = menu,
                                        onDismissRequest = { menu = false },
                                        actions = listOf(
                                            MirrorMenuAction("移出分组") { menu = false; scope.launch { tagGroupRepository.removeMember(tag.id); refreshKey++ } }
                                        )
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showAdd) {
        AddTagDialog(
            groupId = groupId,
            tagGroupRepository = tagGroupRepository,
            tagRepository = tagRepository,
            onDismiss = { showAdd = false },
            onDone = { showAdd = false; refreshKey++ }
        )
    }
    if (showRename) {
        MirrorStackDialog(
            title = "重命名标签组",
            confirmText = "确定",
            onDismiss = { showRename = false },
            onConfirm = {
                scope.launch { if (renameText.isNotBlank()) { tagGroupRepository.rename(groupId, renameText); refreshKey++ } }
                showRename = false
            }
        ) {
            MirrorInputField(value = renameText, onValueChange = { if (it.length <= 20) renameText = it }, placeholder = "标签组名")
        }
    }
    if (showDeleteConfirm) {
        MirrorStackDialog(
            title = "删除标签组「${group?.name}」？",
            message = "其下标签将变为未分组，分组本身被删除。",
            confirmText = "删除",
            destructive = true,
            onDismiss = { showDeleteConfirm = false },
            onConfirm = { scope.launch { tagGroupRepository.delete(groupId); UiSoundPlayer.playDelete(context) }; onBack() }
        )
    }
}

/** 增加成员：从未分组标签里挑一个入组，或新建标签并入组。 */
@Composable
private fun AddTagDialog(groupId: Int, tagGroupRepository: TagGroupRepository, tagRepository: TagRepository, onDismiss: () -> Unit, onDone: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var ungrouped by remember { mutableStateOf<List<Tag>>(emptyList()) }
    var newName by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { ungrouped = tagGroupRepository.getUngroupedTags() }

    MirrorAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("增加标签") },
        text = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                MemoreLineInput(value = newName, onValueChange = { newName = it }, singleLine = true, label = { Text("新标签名") }, shape = MirrorShapes.row, modifier = Modifier.fillMaxWidth())
                if (ungrouped.isNotEmpty()) {
                    Text("未分组标签", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    LazyColumn(Modifier.heightIn(max = 200.dp)) {
                        items(ungrouped, key = { it.id }) { t ->
                            Row(Modifier.fillMaxWidth().clickable { scope.launch { tagGroupRepository.addMember(groupId, t.id); onDone() } }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("#${t.name}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
                                Spacer(Modifier.weight(1f)); Text("${t.usageCount}条", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                scope.launch {
                    if (newName.isNotBlank()) {
                        val created = tagRepository.getOrCreateTag(newName.trim())
                        tagGroupRepository.addMember(groupId, created.id)
                        UiSoundPlayer.playSave(context)
                        onDone()
                    } else context.memoreToast("输入标签名或选一个未分组标签", Toast.LENGTH_SHORT)
                }
            }) { Text("确定") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}
