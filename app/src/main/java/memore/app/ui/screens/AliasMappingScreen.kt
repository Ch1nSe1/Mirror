package memore.app.ui.screens

import memore.app.ui.theme.MemoreType
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import memore.app.data.model.AliasGroup
import memore.app.data.repository.AliasMappingRepository
import memore.app.ui.components.MirrorMenu
import memore.app.ui.components.MirrorMenuAction
import memore.app.ui.components.MirrorDropdownMenu
import kotlinx.coroutines.launch

/**
 * 别名映射管理（2026-10-24 去卡片，M1）：
 * 两行制（标准名 + 别名），类型的两种用**深浅不同的灰**区分（节点深、情绪浅），
 * 节点组和情绪组之间留白 + 一条发丝线；编辑收进 ⋮。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AliasMappingScreen(
    repo: AliasMappingRepository,
    onBack: () -> Unit,
    onEditGroup: (AliasGroup?) -> Unit
) {
    val scope = rememberCoroutineScope()
    var groups by remember { mutableStateOf<List<AliasGroup>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    var menuFor by remember { mutableStateOf<String?>(null) }

    fun load() {
        scope.launch { groups = repo.load() }
    }

    LaunchedEffect(Unit) { load(); loaded = true }

    val nodes = groups.filter { it.kind != "emotion" }.sortedBy { it.standardName }
    val emotions = groups.filter { it.kind == "emotion" }.sortedBy { it.standardName }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("别名映射管理", style = MemoreType.pageTitleSans) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { onEditGroup(null) }) {
                        Icon(Icons.Filled.Add, "新建", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        if (groups.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {}
        } else {
        if (!loaded) { memore.app.ui.components.MirrorLoading(Modifier.padding(padding)) }
        else LazyColumn(Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp)) {
                nodes.forEach { group ->
                    item(key = "n_${group.id}") {
                        AliasRow(
                            group = group,
                            label = "名称",
                            labelColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
                            menuOpen = menuFor == group.id,
                            onOpenMenu = { menuFor = group.id },
                            onDismissMenu = { menuFor = null },
                            onEdit = { menuFor = null; onEditGroup(group) }
                        )
                    }
                }
                // 节点组与情绪组之间：留白 + 一条线
                if (nodes.isNotEmpty() && emotions.isNotEmpty()) {
                    item(key = "gap") {
                        Spacer(Modifier.height(18.dp))
                        Box(
                            Modifier.fillMaxWidth().height(1.dp)
                                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f))
                        )
                        Spacer(Modifier.height(14.dp))
                    }
                }
                emotions.forEach { group ->
                    item(key = "e_${group.id}") {
                        AliasRow(
                            group = group,
                            label = "情绪",
                            labelColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            menuOpen = menuFor == group.id,
                            onOpenMenu = { menuFor = group.id },
                            onDismissMenu = { menuFor = null },
                            onEdit = { menuFor = null; onEditGroup(group) }
                        )
                    }
                }
                item {
                    Text(
                        "共 ${groups.size} 组",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 14.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun AliasRow(
    group: AliasGroup,
    label: String,
    labelColor: androidx.compose.ui.graphics.Color,
    menuOpen: Boolean,
    onOpenMenu: () -> Unit,
    onDismissMenu: () -> Unit,
    onEdit: () -> Unit
) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                Modifier.weight(1f).clickable { onEdit() }
            ) {
                Text(
                    group.standardName,
                    fontSize = 15.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (group.aliases.isNotEmpty()) {
                    Spacer(Modifier.height(3.dp))
                    Text(
                        group.aliases.joinToString("、"),
                        fontSize = 11.5.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Text(label, fontSize = 10.5.sp, color = labelColor)
            Spacer(Modifier.width(2.dp))
            Box {
                IconButton(onClick = onOpenMenu, modifier = Modifier.size(30.dp)) {
                    Icon(
                        Icons.Outlined.MoreVert, "更多", Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                MirrorMenu(
                    expanded = menuOpen,
                    onDismissRequest = onDismissMenu,
                    actions = listOf(MirrorMenuAction("编辑") { onEdit() })
                )
            }
        }
        Box(
            Modifier.fillMaxWidth().height(1.dp)
                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f))
        )
    }
}
