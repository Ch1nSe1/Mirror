package memore.app.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import memore.app.data.model.BuiltinZones
import memore.app.data.model.Protocol
import memore.app.data.model.Record
import memore.app.data.model.Tag
import memore.app.data.model.Zone
import memore.app.data.repository.ZoneCondition
import memore.app.di.AppContainer
import memore.app.ui.components.AttachmentListViewer
import memore.app.ui.components.IconAnchor
import memore.app.ui.components.IconZoneCustom
import memore.app.ui.components.IconZoneNote
import memore.app.ui.components.MemoreLineInput
import memore.app.ui.components.MemorePage
import memore.app.ui.components.MirrorLineField
import memore.app.ui.components.MirrorMenu
import memore.app.ui.components.MirrorAlertDialog
import memore.app.ui.components.MirrorMenuAction
import memore.app.ui.components.MirrorShapes
import memore.app.ui.components.MirrorSheet
import memore.app.ui.components.MirrorStackDialog
import memore.app.ui.components.RecordCard
import memore.app.ui.components.SummaryViewer
import memore.app.ui.components.mirrorHairlineColor
import memore.app.ui.theme.MemoreType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import android.widget.Toast
import memore.app.utils.memoreToast

/**
 * 分区（2026-11-14 第三版，重写）。
 *
 * 这一版只解决两件事：
 *   1. **动效卡**：原来用 `AnimatedContent` 包列表，切区会销毁并重建 42 张卡片，
 *      淡入的 120ms 全在重组里。现在改成动画 `graphicsLayer.alpha` —— 只重绘，不重组。
 *   2. **加载堵主线程**：原来每条记录查三次库（42×3=126 次挂起），且仓库里有 `runBlocking`。
 *      现在三次批量查询 + 全走 IO。
 */
@Composable
fun ZoneScreen(
    container: AppContainer,
    onBack: () -> Unit,
    onOpenRecord: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    val repo = container.zoneRepository

    var zones by remember { mutableStateOf<List<Zone>>(emptyList()) }
    var selectedId by remember { mutableStateOf(BuiltinZones.ANCHOR) }
    var transientName by remember { mutableStateOf<String?>(null) }
    var transientCond by remember { mutableStateOf<ZoneCondition?>(null) }
    var records by remember { mutableStateOf<List<Record>>(emptyList()) }
    // 2026-11-19：列表状态**跟着区域重建**。
    // 之前用单一 rememberLazyListState + 数据加载后 scrollToItem(0)：数据刚赋值时
    // LazyColumn 还没用新数据布局，滚动等于没发生，于是切进自定义区域仍停在上一个区域的
    // 滚动位置（看到最旧的记录，最新的要往上滚）。现在换区域就是新的 state，从 0 开始。
    val listState = androidx.compose.runtime.remember(selectedId) {
        androidx.compose.foundation.lazy.LazyListState()
    }
    var tagsMap by remember { mutableStateOf<Map<String, List<Tag>>>(emptyMap()) }
    var protMap by remember { mutableStateOf<Map<String, List<Protocol>>>(emptyMap()) }
    var amendMap by remember { mutableStateOf<Map<String, List<Record>>>(emptyMap()) }
    var menuOpen by remember { mutableStateOf(false) }
    var showAllZones by remember { mutableStateOf(false) }
    var showNew by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    var showAttachmentsFor by remember { mutableStateOf<Record?>(null) }
    var showSummaryFor by remember { mutableStateOf<Record?>(null) }
    /** 「设置区域」：可选区域（含锚点，不含札记）与每张卡已属的分区（2026-11-14）。 */
    var zoneOptions by remember { mutableStateOf<List<memore.app.data.model.Zone>>(emptyList()) }
    var zoneIdsByRecord by remember { mutableStateOf<Map<String, Set<String>>>(emptyMap()) }
    /** 长按分区名的编辑菜单 / 改名 / 删除（2026-11-14）。 */
    var menuZone by remember { mutableStateOf<Zone?>(null) }
    var renameZone by remember { mutableStateOf<Zone?>(null) }
    var renameText by remember { mutableStateOf("") }
    var deleteZone by remember { mutableStateOf<Zone?>(null) }
    // 取消锚点后要重查：老锚点页是 Flow 驱动会自动刷新，这页是一次性加载（2026-11-14）
    var refreshKey by remember { mutableStateOf(0) }
    val context = androidx.compose.ui.platform.LocalContext.current

    LaunchedEffect(Unit) {
        zones = runCatching { repo.zones() }.getOrDefault(emptyList())
    }

    // 「设置区域」的可选区域 + 每张卡已属分区（2026-11-14）
    LaunchedEffect(zones, refreshKey) {
        zoneOptions = zones.filter { it.id != memore.app.data.model.BuiltinZones.AI_NOTE }
        zoneIdsByRecord = runCatching { repo.membershipsByRecord() }.getOrDefault(emptyMap())
    }

    LaunchedEffect(selectedId, transientCond, zones, refreshKey) {
        // 2026-11-19 根因：换区域时如果继续渲染旧区域的数据，新的 LazyListState 会先按
        // **旧列表**布一次局，记住"第一可见项 key"（例如 g_2026-10）；等新数据到达、
        // 2026-11 组插到最前面，Compose 会按 key 把位置保持在 g_2026-10 → 实际落在 index 3，
        // 于是进来看到的是 10 月，最新的在上面要往上滚。
        // 修法：数据没换好之前**不渲染旧数据**。先清空，让新 state 从空列表开始，
        // 没有旧 key 可锚定，新数据到达后自然停在顶部。
        records = emptyList()
        tagsMap = emptyMap()
        protMap = emptyMap()
        amendMap = emptyMap()
        val cond = transientCond
        val list = withContext(Dispatchers.IO) {
            when {
                cond != null -> runCatching {
                    repo.recordsFor(Zone(id = "tmp", name = "tmp", condition = cond.toJson()))
                }.getOrDefault(emptyList())
                else -> {
                    val zone = zones.firstOrNull { it.id == selectedId }
                    if (zone != null) runCatching { repo.recordsFor(zone) }.getOrDefault(emptyList())
                    else emptyList()
                }
            }
        }
        records = list

        val ids = list.map { it.id }
        if (ids.isNotEmpty()) {
            withContext(Dispatchers.IO) {
                val db = container.database
                val tags = runCatching { db.tagDao().getTagsForRecords(ids) }.getOrDefault(emptyList())
                val prots = runCatching { db.protocolDao().getProtocolsForRecords(ids) }.getOrDefault(emptyList())
                val amends = runCatching { db.recordDao().getAmendmentsForRecords(ids) }.getOrDefault(emptyList())
                tagsMap = tags.groupBy { it.recordId }.mapValues { (_, v) ->
                    v.map { Tag(id = it.id, name = it.name, createdAt = it.createdAt) }
                }
                protMap = prots.groupBy { it.recordId }.mapValues { (_, v) ->
                    v.map { Protocol(id = it.id, name = it.name, createdAt = it.createdAt) }
                }
                amendMap = amends.filter { it.correctionOfId != null }.groupBy { it.correctionOfId!! }
            }
        }
    }

    val title = transientName ?: zones.firstOrNull { it.id == selectedId }?.name ?: ""

    // 绘制阶段的淡入：内容不重建，只重绘
    val alpha = remember { Animatable(1f) }
    LaunchedEffect(selectedId, transientCond) {
        alpha.snapTo(0.15f)
        alpha.animateTo(1f, tween(120))
    }

    MemorePage(
        // 2026-11-15：统一叫「区域」（入口在时间线 ⋮ → 区域）。原来这里叫「分区」，
        // 入口叫「锚点区」，一个东西三个名字。
        title = "区域",
        onBack = onBack,
        actions = {
            Box {
                Text(
                    "⋯",
                    style = MemoreType.pageTitleSans,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.clickable { menuOpen = true }.padding(horizontal = 8.dp, vertical = 2.dp)
                )
                MirrorMenu(
                    expanded = menuOpen,
                    onDismissRequest = { menuOpen = false },
                    actions = listOf(
                        MirrorMenuAction("新建区域") { menuOpen = false; newName = ""; showNew = true },
                        MirrorMenuAction("全部区") { menuOpen = false; showAllZones = true }
                    )
                )
            }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            // 顶栏下沿一条发丝线（2026-11-14：用户要求"顶栏上下加横线隔开"）
            Box(Modifier.fillMaxWidth().height(1.dp).background(mirrorHairlineColor()))
            ZoneSlots(
                zones = zones,
                selectedId = if (transientCond != null) "" else selectedId,
                onSelect = { id -> transientName = null; transientCond = null; selectedId = id },
                menuZone = menuZone,
                onLongPress = { menuZone = it },
                onDismissMenu = { menuZone = null },
                onRename = { menuZone = null; renameZone = it; renameText = it.name },
                onDelete = { menuZone = null; deleteZone = it },
                onMoveFront = { zone ->
                    menuZone = null
                    scope.launch {
                        repo.moveToFront(zone.id)
                        zones = runCatching { repo.zones() }.getOrDefault(emptyList())
                    }
                }
            )
            // 标签行下沿再一条，把"页头"和"内容"彻底隔开
            Box(Modifier.fillMaxWidth().height(1.dp).background(mirrorHairlineColor()))

            Column(Modifier.fillMaxSize().graphicsLayer { this.alpha = alpha.value }) {
                ZoneHeader(
                    zoneId = if (transientCond != null) "" else selectedId,
                    records = records,
                    title = title
                )



                val currentZone = remember(zones, selectedId) { zones.firstOrNull { it.id == selectedId } }
                val fmt = remember { DateTimeFormatter.ofPattern("yyyy-MM") }
                val sysZone = remember { ZoneId.systemDefault() }
                val groups = remember(records) {
                    records.groupBy { r -> fmt.format(Instant.ofEpochMilli(r.createdAt).atZone(sysZone)) }
                }

                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(bottom = 28.dp)
                ) {
                    groups.forEach { (label, list) ->
                        item(key = "g_$label", contentType = "month") {
                            Text(
                                label,
                                style = MemoreType.micro,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 2.dp)
                            )
                        }
                        items(list.size, key = { i -> list[i].id }, contentType = { "record" }) { i ->
                            val r = list[i]
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                                verticalAlignment = Alignment.Top
                            ) {
                                Box(
                                    Modifier.padding(top = 14.dp).width(2.dp).height(22.dp)
                                        .clip(MirrorShapes.row)
                                        .background(MaterialTheme.colorScheme.primary)
                                )
                                Spacer(Modifier.width(12.dp))
                                Box(Modifier.weight(1f)) {
                                    RecordCard(
                                        record = r,
                                        onOpen = { onOpenRecord(r.id) },
                                        tags = tagsMap[r.id].orEmpty(),
                                        protocols = protMap[r.id].orEmpty(),
                                        amendments = amendMap[r.id].orEmpty(),
                                        attachmentCount = r.attachmentsCount,
                                        // 2026-11-19：锁定记录在区域页也不许看总结/附件（和情绪页、线索页一个规矩）
                                        onViewAttachments = {
                                            if (r.isLocked) context.memoreToast("记录已加密，需先解密", Toast.LENGTH_SHORT)
                                            else showAttachmentsFor = r
                                        },
                                        onViewSummary = {
                                            if (r.isLocked) context.memoreToast("记录已加密，需先解密", Toast.LENGTH_SHORT)
                                            else showSummaryFor = r
                                        },
                                        onUnanchor = if (r.isAnchored && selectedId == memore.app.data.model.BuiltinZones.ANCHOR) {
                                            {
                                                scope.launch {
                                                    container.recordRepository.toggleAnchor(r.id)
                                                    // 这一步是关键：不刷新的话卡片还在，看着像"没反应"
                                                    refreshKey++
                                                }
                                                memore.app.utils.UiSoundPlayer.playAnchorClear(context)
                                            }
                                        } else null,
                                        onRemoveFromZone = if (currentZone != null && !currentZone.builtin) {
                                            {
                                                scope.launch {
                                                    repo.removeFromZone(currentZone.id, r.id)
                                                    refreshKey++
                                                }
                                                memore.app.utils.UiSoundPlayer.playAnchorClear(context)
                                            }
                                        } else null,
                                        // 2026-11-19：自定义区域也要有 ⋮ —— 里面只有一条「移出区域」，
                                        // 否则进了区域就出不去。锚点区仍只保留「取消锚定」。
                                        zoneOptions = emptyList(),
                                        zoneIdsByRecord = emptyMap(),
                                        onToggleAnchorFor = null,
                                        onToggleZoneFor = null
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showNew) {
        MirrorStackDialog(
            title = "新建区域",
            confirmText = "创建",
            onConfirm = {
                val name = newName.trim()
                if (name.isNotBlank()) {
                    scope.launch {
                        runCatching { repo.create(name, ZoneCondition(manual = true)) }
                        zones = runCatching { repo.zones() }.getOrDefault(emptyList())
                    }
                    newName = ""
                    showNew = false
                }
            },
            onDismiss = { showNew = false }
        ) {
            MemoreLineInput(
                value = newName,
                onValueChange = { newName = it },
                placeholder = { Text("区域名") }
            )
        }
    }

    if (showAllZones) {
        AllZonesSheet(
            container = container,
            onDismiss = { showAllZones = false },
            onPick = { name, cond ->
                showAllZones = false
                transientName = name
                transientCond = cond
            }
        )
    }

    // 改名（2026-11-14）：和新建同一个输入规矩——只有下划线，不画标签与占位
    renameZone?.let { zone ->
        MirrorStackDialog(
            title = "改名",
            confirmText = "保存",
            onConfirm = {
                val name = renameText.trim()
                if (name.isNotBlank()) {
                    scope.launch {
                        runCatching { repo.rename(zone.id, name) }
                        zones = runCatching { repo.zones() }.getOrDefault(emptyList())
                    }
                    renameZone = null
                }
            },
            onDismiss = { renameZone = null }
        ) {
            MemoreLineInput(value = renameText, onValueChange = { renameText = it })
        }
    }

    // 删除（2026-11-14）：法条 5.4 —— 破坏性动作必走对话框，并把成员一起清掉
    deleteZone?.let { zone ->
        MirrorAlertDialog(
            onDismissRequest = { deleteZone = null },
            title = { Text("删除「${zone.name}」") },
            text = { Text("区域里的记录不会被删除，只是不再属于这个区域。") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        runCatching { repo.delete(zone.id) }
                        zones = runCatching { repo.zones() }.getOrDefault(emptyList())
                        if (selectedId == zone.id) selectedId = memore.app.data.model.BuiltinZones.ANCHOR
                    }
                    deleteZone = null
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { deleteZone = null }) { Text("取消") }
            }
        )
    }

    showAttachmentsFor?.let { rec ->
        AttachmentListViewer(
            record = rec,
            recordRepository = container.recordRepository,
            onDismiss = { showAttachmentsFor = null }
        )
    }
    showSummaryFor?.let { rec ->
        SummaryViewer(record = rec, onDismiss = { showSummaryFor = null })
    }
}

/** 四等分、可横滑的区标签行。 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun ZoneSlots(
    zones: List<Zone>,
    selectedId: String,
    onSelect: (String) -> Unit,
    menuZone: Zone?,
    onLongPress: (Zone) -> Unit,
    onDismissMenu: () -> Unit,
    onRename: (Zone) -> Unit,
    onDelete: (Zone) -> Unit,
    onMoveFront: (Zone) -> Unit
) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val slotW = maxWidth / 4
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp)
        ) {
            zones.forEachIndexed { index, z ->
                Box {
                    Column(
                        Modifier
                            .width(slotW)
                            // 长按进入编辑（2026-11-14）：点击仍是切换，长按弹菜单
                            .combinedClickable(
                                onClick = { onSelect(z.id) },
                                onLongClick = { onLongPress(z) }
                            )
                            .padding(vertical = 9.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            z.name,
                            style = MemoreType.meta,
                            color = if (z.id == selectedId) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(Modifier.height(6.dp))
                        Box(
                            Modifier
                                .height(2.dp)
                                .fillMaxWidth(if (z.id == selectedId) 0.55f else 0f)
                                .background(MaterialTheme.colorScheme.primary)
                        )
                    }
                    // 内置分区（锚点、札记）：条目照给，但全是灰的 —— 让人看见"这里能改，只是不给改"
                    MirrorMenu(
                        expanded = menuZone?.id == z.id,
                        onDismissRequest = onDismissMenu,
                        actions = listOf(
                            MirrorMenuAction("改名", enabled = !z.builtin) { onDismissMenu(); onRename(z) },
                            MirrorMenuAction("前移", enabled = !z.builtin) { onDismissMenu(); onMoveFront(z) },
                            MirrorMenuAction("删除", danger = true, enabled = !z.builtin) { onDismissMenu(); onDelete(z) },
                        )
                    )
                }
                if (index != zones.lastIndex) {
                    Box(
                        Modifier
                            .width(1.dp)
                            .height(16.dp)
                            .align(Alignment.CenterVertically)
                            .background(mirrorHairlineColor())
                    )
                }
            }
        }
    }
}

/** 头部：矢量图标 + 类型计数。 */
@Composable
private fun ZoneHeader(zoneId: String, records: List<Record>, title: String) {
    val counts = records.groupingBy { it.type.label }.eachCount().entries.sortedByDescending { it.value }
    Row(
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(56.dp).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                when (zoneId) {
                    BuiltinZones.ANCHOR -> IconAnchor
                    BuiltinZones.AI_NOTE -> IconZoneNote
                    else -> IconZoneCustom
                },
                contentDescription = null,
                modifier = Modifier.size(28.dp),
                tint = MaterialTheme.colorScheme.primary
            )
        }
        Spacer(Modifier.width(14.dp))
        Column {
            if (counts.isEmpty()) {
                Text(title, style = MemoreType.meta, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                counts.take(3).forEach { (label, n) ->
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(label, style = MemoreType.micro, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(6.dp))
                        // 数字用衬线、降一号：它是"读数"，不该是全屏最响的东西（2026-11-14）
                        Text(
                            n.toString(),
                            style = MemoreType.reading.copy(fontSize = 18.sp, lineHeight = 22.sp),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }
    }
}

/** 「全部区」：维度 → 值，两步，各带绘制阶段淡入。 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun AllZonesSheet(
    container: AppContainer,
    onDismiss: () -> Unit,
    onPick: (String, ZoneCondition) -> Unit
) {
    var dim by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    // 来源要留着 id：条件里用 id，界面上显示名字（2026-11-14 修：之前传名字查不到）
    var origins by remember { mutableStateOf<List<Triple<String, String, Int>>>(emptyList()) }
    var tags by remember { mutableStateOf<List<Pair<String, Int>>>(emptyList()) }
    var protocols by remember { mutableStateOf<List<Pair<String, Int>>>(emptyList()) }
    var emotions by remember { mutableStateOf<List<Pair<String, Int>>>(emptyList()) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            origins = runCatching {
                val counts = container.database.recordDao().originCounts().associate { it.originId to it.n }
                container.database.originDao().all()
                    .map { o -> Triple(o.id, o.name, counts[o.id] ?: 0) }
                    .sortedByDescending { it.third }
            }.getOrDefault(emptyList())
            tags = runCatching {
                container.database.tagDao().tagCounts().map { it.name to it.n }
            }.getOrDefault(emptyList())
            protocols = runCatching {
                container.database.protocolDao().protocolCounts().map { it.name to it.n }
            }.getOrDefault(emptyList())
            emotions = runCatching {
                val counts = HashMap<String, Int>()
                container.database.recordDao().idAndEmotions().forEach { row ->
                    runCatching {
                        val arr = org.json.JSONArray(row.emotionalTags)
                        for (i in 0 until arr.length()) {
                            val name = when (val v = arr.opt(i)) {
                                is org.json.JSONObject -> v.optString("name")
                                is String -> v
                                else -> ""
                            }
                            if (name.isNotBlank()) counts[name] = (counts[name] ?: 0) + 1
                        }
                    }
                }
                counts.entries.sortedByDescending { it.value }.map { it.key to it.value }
            }.getOrDefault(emptyList())
        }
    }

    val alpha = remember { Animatable(1f) }
    LaunchedEffect(dim) {
        alpha.snapTo(0.15f)
        alpha.animateTo(1f, tween(120))
    }

    MirrorSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surface) {
        Column(
            Modifier.fillMaxWidth().graphicsLayer { this.alpha = alpha.value }
                .padding(horizontal = 20.dp).padding(bottom = 16.dp)
        ) {
            if (dim == null) {
                Text(
                    "全部区",
                    style = MemoreType.pageTitleSans,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(top = 4.dp, bottom = 8.dp)
                )
                EntryRow("来源", origins.size.toString(), last = false) { dim = "来源" }
                EntryRow("标签", tags.size.toString(), last = false) { dim = "标签" }
                EntryRow("协议", protocols.size.toString(), last = false) { dim = "协议" }
                EntryRow("情绪", emotions.size.toString(), last = true) { dim = "情绪" }
            } else {
                Row(
                    Modifier.fillMaxWidth().height(48.dp).clickable { dim = null; query = "" },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.AutoMirrored.Outlined.ArrowBack,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(dim ?: "", style = MemoreType.pageTitleSans, color = MaterialTheme.colorScheme.onSurface)
                }
                MemoreLineInput(value = query, onValueChange = { query = it }, placeholder = { Text("搜索") })
                Spacer(Modifier.height(6.dp))

                val q = query.trim()
                fun hit(name: String) = q.isBlank() || name.contains(q, ignoreCase = true)
                // 来源带 id，其它维度只有名字；统一成三元组（id, 名字, 条数）
                val values: List<Triple<String, String, Int>> = when (dim) {
                    "来源" -> origins.filter { hit(it.second) }
                    "标签" -> tags.filter { hit(it.first) }.map { Triple(it.first, it.first, it.second) }
                    "协议" -> protocols.filter { hit(it.first) }.map { Triple(it.first, it.first, it.second) }
                    else -> emotions.filter { hit(it.first) }.map { Triple(it.first, it.first, it.second) }
                }

                LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false)) {
                    items(values.size, key = { i -> values[i].first }, contentType = { "value" }) { i ->
                        val (idOrName, display, n) = values[i]
                        PickRow(display, n.toString()) {
                            val cond = when (dim) {
                                "来源" -> ZoneCondition(originIds = listOf(idOrName))
                                "标签" -> ZoneCondition(tags = listOf(display))
                                "协议" -> ZoneCondition(protocols = listOf(display))
                                else -> ZoneCondition(emotions = listOf(display))
                            }
                            onPick(display, cond)
                            onDismiss()
                        }
                    }
                }
            }
        }
    }
}

/** 入口行：数值 + `›`，50dp。 */
@Composable
private fun EntryRow(name: String, count: String, last: Boolean = false, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().height(50.dp).clickable(onClick = onClick),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(name, style = MemoreType.body, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.weight(1f))
            if (count.isNotBlank()) {
                Text(count, style = MemoreType.meta, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.width(6.dp))
            Icon(
                Icons.Outlined.KeyboardArrowRight,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (!last) {
            Box(Modifier.fillMaxWidth().padding(end = 20.dp).height(1.dp).background(mirrorHairlineColor()))
        }
    }
}

/** 值行：44dp。 */
@Composable
private fun PickRow(name: String, count: String, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().height(44.dp).clickable(onClick = onClick),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                name,
                style = MemoreType.body,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (count.isNotBlank()) {
                Text(count, style = MemoreType.meta, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.width(6.dp))
            Icon(
                Icons.Outlined.KeyboardArrowRight,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Box(Modifier.fillMaxWidth().padding(end = 20.dp).height(1.dp).background(mirrorHairlineColor()))
    }
}
