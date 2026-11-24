package memore.app.ui.screens

import memore.app.ui.components.collectKeptAsState
import memore.app.ui.components.MemoreLineInput
import memore.app.ui.theme.MemoreType
import android.content.Context
import memore.app.ui.components.MirrorDropdownMenu
import memore.app.ui.components.MirrorShapes
import memore.app.ui.components.mirrorHairline
import memore.app.ui.components.MirrorMenuAction
import memore.app.ui.components.MirrorMenu
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import memore.app.data.model.Draft
import memore.app.data.model.DraftAskItem
import memore.app.data.model.DraftBlock
import memore.app.data.model.DraftKind
import memore.app.data.model.RecordType
import memore.app.data.repository.DraftRepository
import memore.app.data.repository.pickDraftId
import memore.app.data.repository.shouldWaitForWanted
import memore.app.utils.UiSoundPlayer
import memore.app.utils.formatTimestamp
import memore.app.utils.memoreToast
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import java.util.Calendar

/** 协议青（与协议标签同一个色）—— 只有 AI 按钮用它，其余按钮用主题色（紫）。 */
private val ProtocolCyan = Color(0xFF4DD0E1)

/**
 * 真草稿页（多本）。
 *
 * 形态照用户认可的 DeepSeek：**正文页 + 左侧抽屉**（☰ 打开）。
 * 抽屉里是草稿列表：搜索框、按时间分组的**纯文字行**（不做卡片 —— 那正是它舒服的原因）、
 * 每行长按或点 ⋯ 出「重命名 / 置顶 / 归档 / 删除」（长按有振动 + 这一行留底色，
 * 免得"不知道按的谁"）。切换草稿**在抽屉里就地完成**，不回跳页面。
 *
 * 正文页底部一排：`[质疑] [讨论] [AI] ……… [保存]`（只有 AI 是协议青）。
 * 块的四类见 [DraftKind]；**长按一块**→ 贴着这一块弹小菜单（时间线长按记录同一种）：
 * 复制 / 编辑 / 删除，**正文块还有「讨论」**；删除再走 [DsDialog] 二次确认。
 * 正文块的「讨论」送过去的**只有这一本草稿的块**（[DraftRepository.blocksOf]，
 * 用户原话「送过去的内容只有这一份草稿，精确一下」）。
 *
 * 弹窗只有两处（重命名 / 删除确认），都是 [DsDialog] / [DsRenameDialog] 那套
 * （居中卡片＋通栏动作），**不再从底部升起** —— 用户 2026-10-11 指明照抄 DeepSeek 的两个弹窗，
 * 并说「底部框实在不美观」。弹窗里**不显示块的正文片段**（用户：「部分显示草稿里的文字，很丑」）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DraftScreen(
    /** ≤0 / null = 上层没指定（页面自己按"当前 → 最近一本 → 新建"解析）；>0 = 就开这一本。 */
    draftId: Long?,
    draftRepository: DraftRepository,
    /** 从记录那边点"来源草稿"进来时带的那一块：进来就滚到它（块可能已被删 → 只回本）。 */
    focusBlockId: Long? = null,
    /** 把"现在开着哪一本"报给上层：离开时清空本、跳作答页都要用对 id。 */
    onCurrentDraftChanged: (Long) -> Unit = {},
    /** 从作答页回来：滚到刚写完的那一块（否则回到顶部，新内容在底部看不见）。 */
    scrollToNew: Boolean = false,
    onScrollToNewHandled: () -> Unit = {},
    /** 把这一块**转录**成一条记录（随笔 / 对话录），由上层打开编辑器并记住来源。 */
    onTranscribe: (content: String, target: RecordType, draftId: Long, blockId: Long) -> Unit = { _, _, _, _ -> },
    onOpenAsk: (draftId: Long, kind: String, items: List<DraftAskItem>) -> Unit = { _, _, _ -> }
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(DrawerValue.Closed)

    // ⚠️ initial = null 而不是 emptyList()：Flow 的首帧还没到，那时列表是"未知"不是"空"——
    // 把"未知"当"空"会在每次进页面时误建一本「未命名」（用户 2026-10-10 报的 bug）。
    val loadedDrafts by draftRepository.observeDrafts().collectAsState(initial = null)
    val drafts = loadedDrafts.orEmpty()
    val archivedDrafts = draftRepository.observeArchivedDrafts().collectKeptAsState("DraftScreen.archivedDrafts")

    var currentId by rememberSaveable(stateSaver = androidx.compose.runtime.saveable.listSaver<Long?, Long>(
        save = { listOfNotNull(it) }, restore = { it.firstOrNull() })) { mutableStateOf(draftId?.takeIf { it > 0 }) }
    // 「指定的那一本」是不是已经认下来了。没认下来之前**不许掉进"挑列表第一本"的兜底**：
    // 新建一本时它是先插库、Flow 才刷新，中间那一帧列表里还没有它 —— 兜底会挑第一本，
    // 而 current 一旦被设上，"当前优先"就永远赢了，人再也回不到刚建的新本上 ✗
    //（用户 2026-10-14 报的「进入之后进到第一个草稿」就是这个兜底）。
    var wantedResolved by remember(draftId) { mutableStateOf(false) }
    // 解析"当前这本"：指定了就用它；否则挑最近一本；确实一本都没有才新建。
    // ⚠️ 必须把**归档的那几本**一起算进来：否则"在归档草稿里做完问答回来"会被拽到最新一本 ✗
    // （用户 2026-10-11 报的导航 bug）
    LaunchedEffect(loadedDrafts, archivedDrafts, draftId) {
        val loaded = loadedDrafts ?: return@LaunchedEffect   // 还没读到数据 → 什么都不做
        val wanted = draftId?.takeIf { it > 0 }
        if (wanted != null && !wantedResolved) {
            val inList = loaded.any { it.id == wanted } || archivedDrafts.any { it.id == wanted }
            // 列表里没有它时，只有**库里也确实没有**才允许兜底；库里还在就等下一帧
            val existsInDb = inList || draftRepository.getDraft(wanted) != null
            if (shouldWaitForWanted(wanted, inList, existsInDb, wantedResolved)) return@LaunchedEffect
            wantedResolved = true
            if (inList) { currentId = wanted; return@LaunchedEffect }
        }
        currentId = pickDraftId(draftId, loaded, currentId, archivedDrafts)
            ?: draftRepository.createDraft("")
    }
    val id = currentId
    // 把"现在开着哪一本"报上去（上层离开时要清掉空本、作答页要带对 id）
    LaunchedEffect(id) { id?.let { onCurrentDraftChanged(it) } }
    // 抽屉开着时，返回键先关抽屉 —— 否则一按就把整个草稿页退掉了（导航问题之一）
    BackHandler(enabled = drawerState.isOpen) { scope.launch { drawerState.close() } }
    // 进页面就把"空的草稿"收掉（保留当前这本）—— 直接杀进程留下的空本也能在下次收掉
    LaunchedEffect(id) { id?.let { draftRepository.pruneEmptyDrafts(it) } }
    val blocksFlow = remember(id) { if (id != null) draftRepository.observeBlocks(id) else flowOf(emptyList<DraftBlock>()) }
    val blocks = blocksFlow.collectKeptAsState("DraftScreen.blocks")
    val mine = remember(blocks) { blocks.filter { it.kind == DraftKind.MINE } }
    val qaBlocks = remember(blocks) { blocks.filter { it.kind == DraftKind.QA } }
    val discussBlocks = remember(blocks) { blocks.filter { it.kind == DraftKind.DISCUSS } }
    val aiBlocks = remember(blocks) { blocks.filter { it.kind == DraftKind.AI } }
    val rawBlocks = remember(blocks) { blocks.filter { it.kind == DraftKind.RAW } }
    // 标题上的名字：归档的那几本不在活动列表里，要一并找（否则归档草稿的标题会变成「草稿」✗）
    val currentName = remember(drafts, archivedDrafts, id) {
        (drafts.firstOrNull { it.id == id } ?: archivedDrafts.firstOrNull { it.id == id })?.name.orEmpty()
    }
    val listState = rememberLazyListState()
    val purpleTint = MaterialTheme.colorScheme.primary

    var input by rememberSaveable { mutableStateOf("") }
    var transcribeBlock by remember { mutableStateOf<DraftBlock?>(null) }   // 转录：先选随笔还是对话录
    var editingId by remember { mutableStateOf<Long?>(null) }
    var editingText by remember { mutableStateOf("") }
    var pendingDelete by remember { mutableStateOf<DraftBlock?>(null) }    // 块的删除确认
    var renameTarget by remember { mutableStateOf<Draft?>(null) }          // 改名
    var deleteDraftTarget by remember { mutableStateOf<Draft?>(null) }     // 删本确认
    var scrollToEnd by remember { mutableStateOf(false) }
    var tidying by remember { mutableStateOf(false) }
    var askLoadingKind by remember { mutableStateOf<String?>(null) }
    val askLoading = askLoadingKind != null
    val busy = tidying || askLoading
    // 跑 AI 时的秒表：没有真进度，但"还在跑"必须看得见（用户 2026-10-11：等待要有反馈）
    var busySeconds by remember { mutableStateOf(0) }
    LaunchedEffect(tidying, askLoadingKind) {
        if (tidying || askLoadingKind != null) {
            busySeconds = 0
            while (true) {
                delay(1000)
                busySeconds++
            }
        }
    }

    fun aiConfig(): Triple<String, String, String> {
        val prefs = context.getSharedPreferences("mirror_prefs", Context.MODE_PRIVATE)
        return Triple(
            prefs.getString("ai_endpoint", "") ?: "",
            prefs.getString("ai_model", "") ?: "",
            prefs.getString("ai_api_key", "") ?: ""
        )
    }

    fun startAsk(kind: String) {
        val draftKey = id ?: return
        val (endpoint, model, key) = aiConfig()
        if (endpoint.isBlank()) {
            context.memoreToast("先去设置里配置 AI", Toast.LENGTH_SHORT)
            return
        }
        askLoadingKind = kind
        scope.launch {
            val res = if (kind == DraftKind.QA) draftRepository.probe(blocks, endpoint, model, key)
            else draftRepository.discuss(blocks, endpoint, model, key)
            askLoadingKind = null
            res.fold(
                onSuccess = { items -> onOpenAsk(draftKey, kind, items) },
                onFailure = { e ->
                    UiSoundPlayer.playError(context)
                    context.memoreToast("没问出来：${e.message}", Toast.LENGTH_LONG)
                }
            )
        }
    }

    /**
     * 讨论：**只对指定的这一本**（正文块长按进来的就是当前这本）。
     *
     * 送过去的**只有这一本的块**（按 draftId 现取，绝不掺别的草稿）——
     * 用户原话「送过去的内容只有这一份草稿，精确一下」。
     */
    fun startDiscussFor(draftId: Long) {
        val (endpoint, model, key) = aiConfig()
        if (endpoint.isBlank()) {
            context.memoreToast("先去设置里配置 AI", Toast.LENGTH_SHORT)
            return
        }
        askLoadingKind = DraftKind.DISCUSS
        scope.launch {
            val target = draftRepository.blocksOf(draftId)
            val res = draftRepository.discuss(target, endpoint, model, key)
            askLoadingKind = null
            res.fold(
                onSuccess = { items -> onOpenAsk(draftId, DraftKind.DISCUSS, items) },
                onFailure = { e ->
                    UiSoundPlayer.playError(context)
                    context.memoreToast("没问出来：${e.message}", Toast.LENGTH_LONG)
                }
            )
        }
    }

    // 从作答页回来：滚到刚提交的那一块（它排在最后）
    LaunchedEffect(scrollToNew, blocks.size) {
        if (scrollToNew && blocks.isNotEmpty()) {
            listState.scrollToItem(blocks.lastIndex)
            onScrollToNewHandled()
        }
    }

    // 从记录那边点"来源草稿"进来：滚到转录出去的那一块（它可能已被删 → 就只看本）
    LaunchedEffect(focusBlockId, blocks.size) {
        val fid = focusBlockId ?: return@LaunchedEffect
        if (blocks.isEmpty()) return@LaunchedEffect
        val row = draftBlockRowIndex(
            mine = mine.map { it.id },
            qa = qaBlocks.map { it.id },
            discuss = discussBlocks.map { it.id },
            ai = aiBlocks.map { it.id },
            blockId = fid
        )
        if (row >= 0) listState.scrollToItem(row)
    }

    LaunchedEffect(blocks.size, scrollToEnd) {
        if (scrollToEnd && blocks.isNotEmpty()) {
            listState.animateScrollToItem(blocks.lastIndex)
            scrollToEnd = false
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(
                drawerContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                drawerShape = MirrorShapes.modal,
                modifier = Modifier.width(300.dp)
            ) {
                DraftDrawer(
                    drafts = drafts,
                    archived = archivedDrafts,
                    currentId = id,
                    onPick = { picked ->
                        val leaving = currentId
                        currentId = picked
                        scope.launch {
                            // 离开的那本要是没写过东西，就地删掉（防空草稿堆包）
                            if (leaving != null && leaving != picked) draftRepository.deleteIfEmpty(leaving)
                            drawerState.close()
                        }
                    },
                    onNew = {
                        scope.launch {
                            val leaving = currentId
                            currentId = draftRepository.createDraft("")
                            if (leaving != null && leaving != currentId) draftRepository.deleteIfEmpty(leaving)
                            drawerState.close()
                        }
                    },
                    onRename = { d -> renameTarget = d },
                    onTogglePin = { d -> scope.launch { draftRepository.setDraftPinned(d.id, !d.pinned) } },
                    onArchive = { d -> scope.launch { draftRepository.setDraftArchived(d.id, !d.archived) } },
                    onDelete = { d -> deleteDraftTarget = d }
                )
            }
        }
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(currentName.ifBlank { "草稿" }, style = MemoreType.pageTitleSans, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Filled.Menu, "草稿列表", tint = MaterialTheme.colorScheme.onSurface)
                        }
                    },
                    actions = {
                        // ⚠️ 不许按 blocks.isNotEmpty() 禁用（用户 2026-10-14 报的 bug：
                        // 进到一本空草稿时 ＋ 是灰的 → 「点加号无法创建新草稿」，
                        // 必须在抽屉里切到一本有内容的才又能按）。新建草稿任何时候都该能按，
                        // 只有 AI 正在跑的时候临时锁住（那会儿底部一整排也锁着，状态要一致）。
                        // 抽屉打开时由抽屉顶部的＋负责新建，避免两个加号同时在屏上
                        if (!drawerState.isOpen) {
                            IconButton(onClick = {
                                scope.launch {
                                    val leaving = currentId
                                    currentId = draftRepository.createDraft("")
                                    // 没写过东西的那本就地收掉，别让空草稿越堆越多
                                    if (leaving != null && leaving != currentId) draftRepository.deleteIfEmpty(leaving)
                                    drawerState.close()
                                }
                            }, enabled = !busy) { Icon(Icons.Filled.Add, "新建草稿", tint = MaterialTheme.colorScheme.onSurface) }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
                )
            },
            containerColor = MaterialTheme.colorScheme.background
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                // 三个区：正文 / 制作 / 成品（2026-11-19）。等分页签，中间短竖线不接上下横线。
                var pane by rememberSaveable { mutableStateOf(0) }
                Box(
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp).height(1.dp)
                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.28f))
                )
                Row(Modifier.fillMaxWidth().height(40.dp), verticalAlignment = Alignment.CenterVertically) {
                    val tabDefs = listOf(
                        "正文" to mine.size,
                        "制作" to (qaBlocks.size + discussBlocks.size),
                        "成品" to (aiBlocks.size + rawBlocks.size)
                    )
                    tabDefs.forEachIndexed { i, (label, count) ->
                        if (i > 0) {
                            Box(
                                Modifier.width(1.dp).height(18.dp)
                                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.28f))
                            )
                        }
                        Box(
                            Modifier.weight(1f).clickable { pane = i },
                            contentAlignment = Alignment.Center
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    label,
                                    fontSize = 13.sp,
                                    color = if (pane == i) MaterialTheme.colorScheme.onSurface
                                            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                )
                                Spacer(Modifier.width(5.dp))
                                Text("$count", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
                Box(
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp).height(1.dp)
                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.28f))
                )
                LaunchedEffect(pane) { runCatching { listState.scrollToItem(0) } }
                val renderBlock: @Composable (DraftBlock, Boolean) -> Unit = { block, article ->
                    DraftBlockRow(
                        number = null,
                        gutter = false,
                        showBar = !article,
                        article = article,
                        block = block,
                        editing = editingId == block.id,
                        editingText = editingText,
                        onEditingTextChange = { editingText = it },
                        onCancelEdit = { editingId = null },
                        onSaveEdit = {
                            val text = editingText
                            editingId = null
                            if (id != null) scope.launch { draftRepository.edit(id, block.id, text); UiSoundPlayer.playSave(context) }
                        },
                        onCopy = {
                            clipboard.setText(AnnotatedString(block.content))
                            context.memoreToast("已复制", Toast.LENGTH_SHORT)
                        },
                        onEdit = { editingId = block.id; editingText = block.content },
                        onDiscuss = if (block.kind == DraftKind.MINE) {
                            { id?.let { startDiscussFor(it) } }
                        } else null,
                        onTranscribe = { b -> transcribeBlock = b },
                        onDelete = { pendingDelete = block }
                    )
                }

                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp)
                ) {
                    when (pane) {
                        0 -> {
                            itemsIndexed(mine, key = { _, b -> b.id }) { _, block -> renderBlock(block, false) }
                        }
                        1 -> {
                            val sections = listOf("质疑" to qaBlocks, "讨论" to discussBlocks).filter { it.second.isNotEmpty() }
                            sections.forEachIndexed { sectionIndex, (label, list) ->
                                if (sectionIndex > 0) {
                                    item(key = "sep_$label") {
                                        Spacer(Modifier.height(6.dp))
                                        Box(
                                            Modifier.fillMaxWidth().height(1.dp)
                                                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.28f))
                                        )
                                        Spacer(Modifier.height(6.dp))
                                    }
                                }
                                item(key = "h_$label") {
                                    Text(
                                        label,
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(top = 4.dp)
                                    )
                                }
                                itemsIndexed(list, key = { _, b -> b.id }) { i, b ->
                                    Column(Modifier.fillMaxWidth()) {
                                        renderBlock(b, false)
                                        if (i < list.lastIndex) {
                                            Spacer(Modifier.height(10.dp))
                                            Box(
                                                Modifier.fillMaxWidth().height(1.dp)
                                                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.28f))
                                            )
                                            Spacer(Modifier.height(10.dp))
                                        }
                                    }
                                }
                            }
                        }
                        else -> {
                            val doneBlocks = (aiBlocks + rawBlocks).sortedBy { it.id }
                            itemsIndexed(doneBlocks, key = { _, b -> b.id }) { i, b ->
                                Column(Modifier.fillMaxWidth()) {
                                    renderBlock(b, true)
                                    if (i < doneBlocks.lastIndex) {
                                        Spacer(Modifier.height(14.dp))
                                        Box(
                                            Modifier.fillMaxWidth().height(1.dp)
                                                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.28f))
                                        )
                                        Spacer(Modifier.height(14.dp))
                                    }
                                }
                            }
                        }
                    }
                }

                // 底部按钮区：保存 / 质疑 / 讨论 ……… AI（只有 AI 是协议青）
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
                    border = mirrorHairline(),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    // 底部照 DeepSeek 的输入区：一个无边框的软底输入框，动作是里面的小胶囊，主按钮在右下
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            MemoreLineInput(
                                value = input,
                                onValueChange = { input = it },
                                readOnly = busy,
                                modifier = Modifier.weight(1f),
                                shape = MirrorShapes.field
                            )
                            Spacer(Modifier.width(10.dp))
                            val canSend = input.isNotBlank() && !busy
                            Box(
                                Modifier.size(30.dp)
                                    .clip(androidx.compose.foundation.shape.CircleShape)
                                    .background(
                                        if (canSend) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
                                    )
                                    .clickable(enabled = canSend) {
                                        val text = input
                                        val draftKey = id ?: return@clickable
                                        input = ""
                                        scope.launch {
                                            draftRepository.add(draftKey, text)
                                            scrollToEnd = true
                                            UiSoundPlayer.playSave(context)
                                        }
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Text("↑", color = MaterialTheme.colorScheme.onPrimary, fontSize = 15.sp)
                            }
                        }
                        // 正在跑 AI：一条来回走的进度条 + 正在做什么 + 秒数（不假装有百分比）
                        if (busy) {
                            val (busyLabel, busyTint) = when {
                                tidying -> "正在整理" to ProtocolCyan
                                askLoadingKind == DraftKind.QA -> "正在提问" to purpleTint
                                else -> "正在讨论" to purpleTint
                            }
                            Spacer(Modifier.height(12.dp))
                            LinearProgressIndicator(
                                modifier = Modifier.fillMaxWidth().height(3.dp),
                                color = busyTint,
                                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "$busyLabel… $busySeconds″",
                                style = MaterialTheme.typography.labelMedium,
                                color = busyTint
                            )
                        }
                        Spacer(Modifier.height(10.dp))
                        val canAsk = mine.isNotEmpty() && !busy
                        val canTidy = mine.isNotEmpty() && !busy
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            TextButton(onClick = { startAsk(DraftKind.QA) }, enabled = canAsk,
                                contentPadding = PaddingValues(0.dp)) {
                                Text("质疑", fontSize = 13.sp, color = purpleTint)
                            }
                            TextButton(onClick = { startAsk(DraftKind.DISCUSS) }, enabled = canAsk,
                                contentPadding = PaddingValues(0.dp)) {
                                Text("讨论", fontSize = 13.sp, color = purpleTint)
                            }
                            TextButton(onClick = {
                                val draftKey = id ?: return@TextButton
                                if (tidying) return@TextButton
                                val (endpoint, model, key) = aiConfig()
                                if (endpoint.isBlank()) {
                                    context.memoreToast("先去设置里配置 AI", Toast.LENGTH_SHORT)
                                    return@TextButton
                                }
                                tidying = true
                                scope.launch {
                                    val res = draftRepository.tidy(blocks, endpoint, model, key)
                                    res.fold(
                                        onSuccess = { text ->
                                            draftRepository.add(draftKey, text, kind = DraftKind.AI)
                                            pane = 2
                                            UiSoundPlayer.playAiCleanDone(context)
                                        },
                                        onFailure = { e ->
                                            UiSoundPlayer.playError(context)
                                            context.memoreToast("整理失败：${e.message}", Toast.LENGTH_LONG)
                                        }
                                    )
                                    tidying = false
                                }
                            }, enabled = canTidy, contentPadding = PaddingValues(0.dp)) {
                                Text("AI 成文", fontSize = 13.sp, color = ProtocolCyan)
                            }
                            TextButton(onClick = {
                                val draftKey = id ?: return@TextButton
                                val text = mine.joinToString("\n\n") { it.content.trim() }.trim()
                                if (text.isBlank()) {
                                    context.memoreToast("正文为空", Toast.LENGTH_SHORT)
                                    return@TextButton
                                }
                                scope.launch {
                                    draftRepository.add(draftKey, text, kind = DraftKind.RAW)
                                    pane = 2
                                    UiSoundPlayer.playSave(context)
                                }
                            }, enabled = mine.isNotEmpty() && !busy, contentPadding = PaddingValues(0.dp)) {
                                Text("直接拼接", fontSize = 13.sp, color = purpleTint)
                            }
                        }
                    }
                }
            }
        }
    }

    // 转录：小弹窗选随笔还是对话录（对话录默认落在第二个框 = AI 那栏）
    transcribeBlock?.let { block ->
        DsChoiceDialog(
            title = "转录成",
            options = listOf("随笔" to RecordType.ESSAY, "对话录" to RecordType.DIALOGUE),
            onDismiss = { transcribeBlock = null }
        ) { target ->
            val draftKey = id
            transcribeBlock = null
            if (draftKey != null) onTranscribe(block.content, target, draftKey, block.id)
        }
    }

    renameTarget?.let { draft ->
        DsRenameDialog(
            title = "重命名",
            initial = draft.name,
            onDismiss = { renameTarget = null },
            onConfirm = { v ->
                renameTarget = null
                scope.launch { draftRepository.renameDraft(draft.id, v) }
            }
        )
    }

    pendingDelete?.let { block ->
        DsDialog(
            title = "删除后不可恢复",
            onDismiss = { pendingDelete = null },
            confirmLabel = "删除这一块",
            danger = true,
            onConfirm = {
                pendingDelete = null
                scope.launch {
                    draftRepository.remove(block.id)
                    UiSoundPlayer.playDelete(context)
                    context.memoreToast("已删除", Toast.LENGTH_SHORT)
                }
            }
        )
    }

    deleteDraftTarget?.let { draft ->
        DsDialog(
            title = "删除后不可恢复",
            message = "「${draft.name.ifBlank { "未命名" }}」和里面所有块都会一起删掉",
            onDismiss = { deleteDraftTarget = null },
            confirmLabel = "删除这本草稿",
            danger = true,
            onConfirm = {
                deleteDraftTarget = null
                scope.launch {
                    draftRepository.deleteDraft(draft.id)
                    UiSoundPlayer.playDelete(context)
                    context.memoreToast("已删除", Toast.LENGTH_SHORT)
                }
            }
        )
    }
}

/**
 * 抽屉里的草稿列表（照 DeepSeek 那个侧栏）：搜索框在最上、按时间分组的**纯文字行**
 * （不做卡片 —— 那是它舒服的原因），底部可展开归档。
 */
@Composable
private fun DraftDrawer(
    drafts: List<Draft>,
    archived: List<Draft>,
    currentId: Long?,
    onPick: (Long) -> Unit,
    onNew: () -> Unit,
    onRename: (Draft) -> Unit,
    onTogglePin: (Draft) -> Unit,
    onArchive: (Draft) -> Unit,
    onDelete: (Draft) -> Unit
) {
    var search by remember { mutableStateOf("") }
    var showArchived by remember { mutableStateOf(false) }
    // 当前这本要是归档的，自动把「归档」展开 —— 否则他看不出自己正待在哪一本里
    LaunchedEffect(currentId, archived) {
        if (currentId != null && archived.any { it.id == currentId }) showArchived = true
    }
    val filtered = remember(drafts, search) {
        if (search.isBlank()) drafts else drafts.filter { it.name.contains(search, ignoreCase = true) }
    }
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 6.dp, top = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            MemoreLineInput(
                value = search,
                onValueChange = { search = it },
                placeholder = { Text("搜索草稿") },
                leadingIcon = { Icon(Icons.Filled.Search, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                singleLine = true,
                shape = MirrorShapes.modal,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onNew) {
                Icon(Icons.Filled.Add, "新建草稿", tint = MaterialTheme.colorScheme.onSurface)
            }
        }
        Box(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp).height(1.dp)
                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.28f))
        )

        LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
            // 置顶的只在「置顶」分组里出现一次（之前没排除 → 置顶后同一本出现两遍 ✗）
            val grouped = filtered.filterNot { it.pinned }.groupBy { timeGroupOf(it.updatedAt) }
            val pinnedDrafts = filtered.filter { it.pinned }
            if (pinnedDrafts.isNotEmpty()) {
                item(key = "h_pinned") {
                    Text(
                        "置顶",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 20.dp, top = 14.dp, bottom = 4.dp)
                    )
                }
                itemsIndexed(pinnedDrafts, key = { _, it -> "p_${it.id}" }) { i, draft ->
                    DrawerRow(
                        title = draft.name.ifBlank { "未命名" },
                        time = drawerTime(draft.updatedAt),
                        pinned = draft.pinned,
                        archived = draft.archived,
                        selected = draft.id == currentId,
                        dimmed = false,
                        showDivider = i < pinnedDrafts.lastIndex,
                        onOpen = { onPick(draft.id) },
                        onRename = { onRename(draft) },
                        onTogglePin = { onTogglePin(draft) },
                        onArchive = { onArchive(draft) },
                        onDelete = { onDelete(draft) }
                    )
                }
            }
            listOf("今天", "昨天", "7 天内", "更早").forEach { group ->
                val groupDrafts = grouped[group].orEmpty()
                if (groupDrafts.isNotEmpty()) {
                    item(key = "h_$group") {
                        Text(
                            group,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 20.dp, top = 14.dp, bottom = 4.dp)
                        )
                    }
                    itemsIndexed(groupDrafts, key = { _, it -> "s_${it.id}" }) { i, draft ->
                        DrawerRow(
                            title = draft.name.ifBlank { "未命名" },
                            time = drawerTime(draft.updatedAt),
                            pinned = draft.pinned,
                            archived = draft.archived,
                            selected = draft.id == currentId,
                            dimmed = false,
                            showDivider = i < groupDrafts.lastIndex,
                            onOpen = { onPick(draft.id) },
                            onRename = { onRename(draft) },
                            onTogglePin = { onTogglePin(draft) },
                            onArchive = { onArchive(draft) },
                            onDelete = { onDelete(draft) }
                        )
                    }
                }
            }
            if (filtered.isEmpty()) {
                item {
                    Text(
                        if (search.isBlank()) "还没有草稿" else "没有匹配的草稿",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 20.dp, top = 12.dp)
                    )
                }
            }
            if (archived.isNotEmpty() && search.isBlank()) {
                item(key = "arch_sep") {
                    Box(
                        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp).height(1.dp)
                            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f))
                    )
                }
                item(key = "arch_toggle") {
                    Row(
                        Modifier.fillMaxWidth().clickable { showArchived = !showArchived }
                            .padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "归档 · ${archived.size}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            if (showArchived) "−" else "+",
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                if (showArchived) {
                    itemsIndexed(archived, key = { _, it -> "a_${it.id}" }) { i, draft ->
                        DrawerRow(
                            title = draft.name.ifBlank { "未命名" },
                            time = drawerTime(draft.updatedAt),
                            pinned = draft.pinned,
                            archived = draft.archived,
                            selected = draft.id == currentId,
                            dimmed = true,
                            showDivider = i < archived.lastIndex,
                            onOpen = { onPick(draft.id) },
                            onRename = { onRename(draft) },
                            onTogglePin = { onTogglePin(draft) },
                            onArchive = { onArchive(draft) },
                            onDelete = { onDelete(draft) }
                        )
                    }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

/**
 * 抽屉里的一行：纯文字，没有卡片边框；当前这本用主题色标出来。
 *
 * **长按有反馈**（振动 + 这一行留底色）：用户原话「长按某一个草稿的时候记得有反馈，
 * 不然不知道按的谁」—— 菜单是贴在行上弹的，行本身必须同时"亮"着。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DrawerRow(
    title: String,
    time: String,
    pinned: Boolean,
    archived: Boolean,
    selected: Boolean,
    dimmed: Boolean,
    showDivider: Boolean,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onTogglePin: () -> Unit,
    onArchive: () -> Unit,
    onDelete: () -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp)
                .background(
                    if (menuOpen) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent,
                    MirrorShapes.row
                )
                .combinedClickable(
                    onClick = onOpen,
                    onLongClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        menuOpen = true
                    }
                )
                .padding(start = 8.dp, end = 12.dp, top = 9.dp, bottom = 9.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 当前这本：左侧 2dp 竖条，不再把整行标题染成主色
            Box(
                Modifier.width(2.dp).height(16.dp)
                    .background(
                        if (selected) MaterialTheme.colorScheme.primary else Color.Transparent
                    )
            )
            Spacer(Modifier.width(10.dp))
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (dimmed) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Text(
                time,
                fontSize = 10.5.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (dimmed) 0.5f else 0.8f)
            )
            // 菜单贴着这一行弹；行尾不常驻「⋯」，长按唤起（2026-11-19）
            Box {
                MirrorMenu(
                    expanded = menuOpen,
                    onDismissRequest = { menuOpen = false },
                    actions = listOf(
                        MirrorMenuAction("重命名", icon = Icons.Outlined.DriveFileRenameOutline) { menuOpen = false; onRename() },
                        MirrorMenuAction(if (pinned) "取消置顶" else "置顶", icon = Icons.Outlined.PushPin) { menuOpen = false; onTogglePin() },
                        MirrorMenuAction(if (archived) "取消归档" else "归档", icon = Icons.Outlined.Inventory2) { menuOpen = false; onArchive() },
                        MirrorMenuAction("删除", icon = Icons.Outlined.Delete, danger = true) { menuOpen = false; onDelete() }
                    )
                )
            }
        }
        // 行分隔：从文字左缘起（8+2+10=20dp 起点其实和组标题对齐），右留 20dp
        if (showDivider) {
            Box(
                Modifier.fillMaxWidth().padding(start = 32.dp, end = 20.dp).height(1.dp)
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f))
            )
        }
    }
}

/** 抽屉行右侧的时间：今天只显示时分，其余显示月-日。 */
private fun drawerTime(ms: Long): String {
    val now = java.util.Calendar.getInstance()
    val t = java.util.Calendar.getInstance().apply { timeInMillis = ms }
    val sameDay = now.get(java.util.Calendar.YEAR) == t.get(java.util.Calendar.YEAR) &&
        now.get(java.util.Calendar.DAY_OF_YEAR) == t.get(java.util.Calendar.DAY_OF_YEAR)
    val fmt = java.text.SimpleDateFormat(
        if (sameDay) "HH:mm" else "MM-dd", java.util.Locale.getDefault()
    )
    return fmt.format(java.util.Date(ms))
}

/**
 * DeepSeek 式删除确认弹窗（用户 2026-10-11 指明照抄那张删除图）：
 *
 * - 居中卡片、圆角，**标题居中**（后面可选一行灰字）；
 * - 主要动作是**通栏实心圆角按钮**（危险动作＝红），不是右下角两个小字按钮；
 * - 次要动作「取消」是按钮**下面一行的纯文字**。
 *
 * 为什么不用底部弹层：用户原话「底部框实在不美观」—— 草稿这套交互不再从底部升起。
 * 弹窗里**不放块的正文片段**（用户：「部分显示草稿里的文字，很丑」）。
 *
 * 注意：长按一块的**动作菜单**不走这里 —— 那是贴着内容弹的小菜单（见 [DraftBlockRow]），
 * 与时间线长按记录同一种；这里只管"删除要二次确认"。
 */
@Composable
private fun DsDialog(
    title: String,
    onDismiss: () -> Unit,
    message: String? = null,
    confirmLabel: String,
    danger: Boolean = false,
    onConfirm: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        // 浮层要有一条"过渡用的小边"＋一点阴影：这个主题里弹窗底与背景只差几个亮度单位，
        // 没有描边就完全看不出"浮在上面"（全项目原本 0 处 elevation、5 处 border）。
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = MirrorShapes.modal,
            border = androidx.compose.foundation.BorderStroke(
                1.dp,
                MaterialTheme.colorScheme.outline.copy(alpha = 0.16f)
            ),
            shadowElevation = 12.dp,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 40.dp).widthIn(max = 400.dp)
        ) {
            Column(Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(top = 22.dp, bottom = 18.dp)) {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                    message?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            it,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
                Button(
                    onClick = onConfirm,
                    shape = MirrorShapes.card,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                        contentColor = if (danger) MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.onPrimary
                    ),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).height(48.dp)
                ) { Text(confirmLabel, style = MaterialTheme.typography.titleSmall) }
                Text(
                    "取消",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().clickable { onDismiss() }.padding(vertical = 16.dp)
                )
            }
        }
    }
}

/**
 * 重命名弹窗（照第二张图）：标题居中、**软底圆角输入框**（无边框、不留 placeholder），
 * 下面一道细分割线，再下面 **取消 | 确认** 两栏等宽，中间一道竖分割线。
 */
@Composable
private fun DsRenameDialog(
    title: String,
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var text by remember { mutableStateOf(initial) }
    val line = MaterialTheme.colorScheme.outline.copy(alpha = 0.18f)
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        // 浮层要有一条"过渡用的小边"＋一点阴影：这个主题里弹窗底与背景只差几个亮度单位，
        // 没有描边就完全看不出"浮在上面"（全项目原本 0 处 elevation、5 处 border）。
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = MirrorShapes.modal,
            border = androidx.compose.foundation.BorderStroke(
                1.dp,
                MaterialTheme.colorScheme.outline.copy(alpha = 0.16f)
            ),
            shadowElevation = 12.dp,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 40.dp).widthIn(max = 400.dp)
        ) {
            Column(Modifier.fillMaxWidth()) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = 22.dp, bottom = 16.dp)
                )
                MemoreLineInput(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    shape = MirrorShapes.row,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color.Transparent,
                        unfocusedBorderColor = Color.Transparent,
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        cursorColor = MaterialTheme.colorScheme.primary
                    ),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp)
                )
                Spacer(Modifier.height(18.dp))
                HorizontalDivider(color = line)
                Row(Modifier.fillMaxWidth().height(54.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.weight(1f).fillMaxHeight().clickable { onDismiss() },
                        contentAlignment = Alignment.Center
                    ) {
                        Text("取消", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Box(Modifier.width(1.dp).fillMaxHeight().background(line))
                    Box(
                        Modifier.weight(1f).fillMaxHeight().clickable(enabled = text.isNotBlank()) { onConfirm(text) },
                        contentAlignment = Alignment.Center
                    ) {
                        Text("确认", style = MaterialTheme.typography.bodyLarge, color = purple(text.isNotBlank()))
                    }
                }
            }
        }
    }
}

/**
 * 只选一个的小弹窗：居中卡片 + 标题 + 若干整行等宽选项（同一套 DeepSeek 形制）。
 * 目前给「转录」用：选随笔还是对话录；点空白处即关，不设多余的「取消」。
 */
@Composable
private fun <T> DsChoiceDialog(
    title: String,
    options: List<Pair<String, T>>,
    onDismiss: () -> Unit,
    onPick: (T) -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        // 浮层要有一条"过渡用的小边"＋一点阴影：这个主题里弹窗底与背景只差几个亮度单位，
        // 没有描边就完全看不出"浮在上面"（全项目原本 0 处 elevation、5 处 border）。
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = MirrorShapes.modal,
            border = androidx.compose.foundation.BorderStroke(
                1.dp,
                MaterialTheme.colorScheme.outline.copy(alpha = 0.16f)
            ),
            shadowElevation = 12.dp,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 40.dp).widthIn(max = 400.dp)
        ) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(top = 22.dp, bottom = 20.dp)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(18.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    options.forEach { (label, value) ->
                        Surface(
                            shape = MirrorShapes.card,
                            color = MaterialTheme.colorScheme.surfaceContainerHighest,
                            border = mirrorHairline(),
                            modifier = Modifier.fillMaxWidth().clickable { onPick(value) }
                        ) {
                            Text(
                                label,
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 块 id → 草稿列表里的**行号**（纯函数，可测）。
 *
 * 列表的顺序是：正文的块 → （质疑 / 讨论 / AI 各自：**一条分隔条** + 该区的块）。
 * 从记录卡点"来源草稿"回来时要精确滚到转录出去的那一块，就靠这个换算；
 * 块已被删（或不在这一本里）时返回 -1 = 只回本、不滚。
 */
internal fun draftBlockRowIndex(
    mine: List<Long>,
    qa: List<Long>,
    discuss: List<Long>,
    ai: List<Long>,
    blockId: Long
): Int {
    var row = 0
    mine.forEach { if (it == blockId) return row else row++ }
    listOf(qa, discuss, ai).forEach { section ->
        if (section.isNotEmpty()) {
            row++                                   // 分隔条自己占一行
            section.forEach { if (it == blockId) return row else row++ }
        }
    }
    return -1
}

/** 主题色（紫）；禁用时只是淡一点，不褪成灰（用户说过灰字突兀）。 */
@Composable
private fun purple(enabled: Boolean): Color =
    if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)

/** 一个分区：一条带标签的分隔线 + 该区所有的块（都不编号）。 */
private fun androidx.compose.foundation.lazy.LazyListScope.draftSection(
    label: String,
    blocks: List<DraftBlock>,
    tint: Color,
    row: @Composable (DraftBlock) -> Unit
) {
    if (blocks.isEmpty()) return
    item(key = "divider_$label") {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
            Text(label, style = MaterialTheme.typography.labelLarge, color = tint, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(6.dp))
            Text("${blocks.size}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(10.dp))
            HorizontalDivider(Modifier.weight(1f), color = tint.copy(alpha = 0.35f))
        }
    }
    items(blocks, key = { it.id }) { row(it) }
}

/**
 * 一块：左边序号（AI / 质疑 / 讨论三类为 null = 不编号）+ 一道淡竖线 + 正文。
 *
 * 单击不做任何事；**长按（有振动反馈）就在这一块上弹出小菜单** ——
 * 与时间线长按记录的那种提示同一种（贴着内容弹的 [DropdownMenu]，最后一项「取消」）。
 * 因此不需要标题写着"这一块"：菜单位置本身就是"谁"（用户 2026-10-11：
 * 「"这一块"何意味，你直接弄时间线长按记录的那种提示不行吗」）。
 *
 * **「讨论」只有正文块有**（[onDiscuss] 为 null 的区不显示这一项）。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DraftBlockRow(
    number: Int?,
    boxed: Boolean = false,
    /** 是否预留最左侧 22dp 的编号槽（正文/成品不需要）。 */
    gutter: Boolean = true,
    /** 是否画 2dp 主色竖条。 */
    showBar: Boolean = true,
    /** 成品阅读样式：更大字号与行距。 */
    article: Boolean = false,
    block: DraftBlock,
    editing: Boolean,
    editingText: String,
    onEditingTextChange: (String) -> Unit,
    onCancelEdit: () -> Unit,
    onSaveEdit: () -> Unit,
    onCopy: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onDiscuss: (() -> Unit)? = null,
    onTranscribe: (DraftBlock) -> Unit = {}
) {
    val haptics = LocalHapticFeedback.current
    Row(
        Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .then(
                if (boxed) Modifier.background(MaterialTheme.colorScheme.surfaceContainerLow, MirrorShapes.card)
                else Modifier
            )
            .padding(if (boxed) 12.dp else 0.dp)
    ) {
        if (number != null) {
            Text(
                "$number",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.width(22.dp).padding(top = 2.dp)
            )
        } else if (gutter) {
            Spacer(Modifier.width(22.dp))
        }
        // 淡底卡片里不再画竖线（视觉上已经隔开了）；「正文」保留竖线 + 编号
        if (!boxed && showBar) {
            Box(
                Modifier
                    .width(2.dp)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.35f))
            )
            Spacer(Modifier.width(10.dp))
        }
        if (editing) {
            Column(Modifier.weight(1f)) {
                MemoreLineInput(
                    value = editingText,
                    onValueChange = onEditingTextChange,
                    // 2026-11-19 修：这里以前没关 singleLine，整块多行正文被压成一行，
                    // 编辑时只能看到第一行、光标横着跑，看起来像"编辑框是空的"。
                    singleLine = false,
                    maxLines = Int.MAX_VALUE,
                    textStyle = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp),
                    shape = MirrorShapes.menu
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onSaveEdit, enabled = editingText.isNotBlank()) { Text("保存") }
                    TextButton(onClick = onCancelEdit) { Text("取消", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
        } else {
            var menuOpen by remember { mutableStateOf(false) }
            Box(Modifier.weight(1f)) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .combinedClickable(
                            // 单击不做任何事（编辑改由"长按 → 编辑"进入）
                            onClick = {},
                            onLongClick = {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                menuOpen = true
                            }
                        )
                        .padding(vertical = 2.dp)
                ) {
                    if (article) {
                        Text(
                            block.content,
                            fontSize = 15.5.sp,
                            lineHeight = 28.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    } else {
                        Text(block.content, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                    }
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            formatTimestamp(block.createdAt),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        block.updatedAt?.let { updated ->
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "改 ${formatTimestamp(updated)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                MirrorMenu(
                    expanded = menuOpen,
                    onDismissRequest = { menuOpen = false },
                    actions = buildList {
                        add(MirrorMenuAction("复制") { menuOpen = false; onCopy() })
                        add(MirrorMenuAction("编辑") { menuOpen = false; onEdit() })
                        // 讨论：只有正文块有这一项
                        onDiscuss?.let { discuss ->
                            add(MirrorMenuAction("讨论") { menuOpen = false; discuss() })
                        }
                        // 转录：把这一段直接做成一条记录（随笔 / 对话录，弹窗里选）
                        add(MirrorMenuAction("转录") { menuOpen = false; onTranscribe(block) })
                        add(MirrorMenuAction("删除", danger = true) { menuOpen = false; onDelete() })
                        // 不放「取消」：点空白/返回键就关了，菜单里再来一个"取消"是多余的（用户 2026-10-11）
                    }
                )
            }
        }
    }
}

/** 时间分组：今天 / 昨天 / 7 天内 / 更早。 */
private fun timeGroupOf(ms: Long): String {
    val now = Calendar.getInstance()
    val then = Calendar.getInstance().apply { timeInMillis = ms }
    val sameDay = now.get(Calendar.YEAR) == then.get(Calendar.YEAR) && now.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR)
    if (sameDay) return "今天"
    val yesterday = Calendar.getInstance().apply { add(Calendar.DAY_OF_MONTH, -1) }
    val isYesterday = yesterday.get(Calendar.YEAR) == then.get(Calendar.YEAR) && yesterday.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR)
    if (isYesterday) return "昨天"
    val within7 = ms >= now.timeInMillis - 7L * 24 * 60 * 60 * 1000
    return if (within7) "7 天内" else "更早"
}

/** 输入区里的小胶囊动作（照 DeepSeek：软底、无边框、彩色文字）。 */
@Composable
private fun Pill(
    label: String,
    enabled: Boolean,
    tint: Color,
    onClick: () -> Unit
) {
    Surface(
        shape = MirrorShapes.pill,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        modifier = Modifier.clickable(enabled = enabled) { onClick() }
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = if (enabled) tint else tint.copy(alpha = 0.4f),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 9.dp)
        )
    }
}