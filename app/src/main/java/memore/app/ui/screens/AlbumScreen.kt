package memore.app.ui.screens

import memore.app.ui.components.MirrorStackDialog
import memore.app.ui.components.MemoreLineInput
import androidx.compose.foundation.text.BasicTextField
import android.content.Context
import memore.app.ui.components.MirrorDropdownMenu
import memore.app.ui.components.MirrorShapes
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.ViewDay
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt
import coil.compose.AsyncImage
import coil.request.ImageRequest
import memore.app.data.album.AlbumFootnote
import memore.app.data.album.AlbumState
import memore.app.data.album.AlbumStateStore
import memore.app.data.model.EmotionStats
import memore.app.data.model.RecordType
import memore.app.data.repository.AlbumItem
import memore.app.data.repository.RecordRepository
import memore.app.ui.components.IconAlbum
import memore.app.ui.components.IconBack
import memore.app.ui.components.IconClose
import memore.app.ui.components.MirrorSheet
import memore.app.ui.components.dialogueSegments
import memore.app.ui.components.MirrorMenuAction
import memore.app.ui.components.MirrorMenu
import memore.app.utils.ImageDecoders
import memore.app.utils.formatDateOnly
import memore.app.utils.formatTimestamp
import memore.app.utils.ThemeManager
import memore.app.utils.UiSoundPlayer
import java.io.File
import kotlinx.coroutines.launch

private const val ALBUM_PAGE_SIZE = 12
/**
 * 时间条位置的非线性压缩指数（<1）。
 * 位置 = (距最新的时间比例) ^ 该指数：近期密集区被展开、远期空档期被压短，
 * 两端仍固定在左右边缘，真实时间线性关系只是被压缩而不是丢掉。
 * 觉得压得太狠就往 1 调（1.0 = 退回完全线性）。
 */
private const val ALBUM_TIMELINE_GAMMA = 0.5f
// **只有空行才分段**。原来用 \n+ 会把作者每一个软换行都当成新段落，
// 于是每一行都被加上两字首行缩进 —— 诗的排法被拆成参差不齐的锯齿（2026-10-20 修）。
// 段落内部的换行原样保留：它们是作者自己的断句，不能合并。
private val PARAGRAPH_SPLIT = Regex("\\n[ \\t　]*\\n+")
private data class FootnoteTarget(val entryId: Long?, val content: String)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun AlbumScreen(
    recordRepository: RecordRepository,
    onBack: () -> Unit,
    onOpenRecord: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val stateStore = remember { AlbumStateStore(File(context.applicationContext.filesDir, "album_state.json")) }
    var items by remember { mutableStateOf<List<AlbumItem>>(emptyList()) }
    var page by rememberSaveable { mutableIntStateOf(0) }
    var loadedPage by remember { mutableIntStateOf(-1) }
    var viewPage by rememberSaveable { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(false) }
    var listMode by rememberSaveable { mutableStateOf(false) }
    var endReached by remember { mutableStateOf(false) }
    var totalCount by rememberSaveable { mutableIntStateOf(0) }
    var albumState by remember { mutableStateOf(AlbumState()) }
    // 全量时间索引（不受懒加载影响）：月份选择器与"跳到某月"都基于它
    var timeIndex by remember { mutableStateOf<List<Pair<String, Long>>>(emptyList()) }
    val pagerState = rememberPagerState(pageCount = { items.size })

    LaunchedEffect(Unit) {
        albumState = stateStore.load()
        totalCount = recordRepository.countImageRecords()
        timeIndex = recordRepository.getAlbumTimeIndex()
    }

    /** 跳到全量索引里的第 [index] 条：不足则先加载到那一页，再滚动过去。 */
    fun jumpTo(index: Int) {
        if (index < 0 || index >= totalCount) return
        viewPage = index
        val needPage = index / ALBUM_PAGE_SIZE
        if (needPage > loadedPage) page = needPage
        else scope.launch { pagerState.scrollToPage(index) }
    }

    LaunchedEffect(page) {
        if (endReached || page <= loadedPage) return@LaunchedEffect
        loading = true
        try {
            while (loadedPage < page && !endReached) {
                val nextPage = loadedPage + 1
                val next = recordRepository.getAlbumPage(ALBUM_PAGE_SIZE, nextPage * ALBUM_PAGE_SIZE)
                items = (items + next).distinctBy { it.record.id }
                loadedPage = nextPage
                if (next.size < ALBUM_PAGE_SIZE) endReached = true
            }
            // 跳转场景：目标条目加载出来了才滚过去（items.size 是条目数，不是页数）
            if (items.size > viewPage && pagerState.settledPage != viewPage) {
                pagerState.scrollToPage(viewPage)
            }
        } finally {
            loading = false
        }
    }

    LaunchedEffect(pagerState.settledPage) {
        viewPage = pagerState.settledPage
    }

    LaunchedEffect(pagerState.settledPage, items) {
        if (!loading && !endReached && items.isNotEmpty() && pagerState.settledPage >= items.size - 3) {
            page++
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("图文集") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(IconBack, "返回") } },
                actions = {
                    if (items.isNotEmpty()) {
                        IconButton(onClick = { listMode = !listMode }) {
                            Icon(
                                if (listMode) Icons.Filled.ViewDay else Icons.Filled.ViewList,
                                if (listMode) "单页浏览" else "列表浏览",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text(
                            "${pagerState.currentPage + 1} / $totalCount",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(end = 16.dp)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (items.isNotEmpty()) {
                // 月份胶囊取自【全量时间索引】，不是已加载的那几页：
                // 否则一进页面只有最近两个月，7月要一直往后翻页才长出来。
                val months = remember(timeIndex) {
                    timeIndex.map { formatDateOnly(it.second).take(7) }.distinct()
                }
                val currentMonth = items.getOrNull(pagerState.settledPage)
                    ?.let { formatDateOnly(it.record.createdAt).take(7) }
                Row(
                    Modifier.fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    months.forEach { month ->
                        val selected = month == currentMonth
                        Column(
                            Modifier
                                .clip(MirrorShapes.row)
                                .clickable {
                                    // 该月第一条在【全量索引】里的位置 → 加载到那一页并跳过去
                                    val index = timeIndex.indexOfFirst { formatDateOnly(it.second).take(7) == month }
                                    jumpTo(index)
                                }
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                month,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (selected) MaterialTheme.colorScheme.onSurface
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
                            )
                            Spacer(Modifier.height(4.dp))
                            Box(
                                Modifier
                                    .width(if (selected) 16.dp else 4.dp)
                                    .height(2.dp)
                                    .clip(MirrorShapes.pill)
                                    .background(
                                        if (selected) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
                                    )
                            )
                        }
                    }
                }
            }
            if (items.isNotEmpty()) {
                AlbumTimeline(
                    items = items,
                    pinned = albumState.pinned,
                    currentIndex = pagerState.settledPage.coerceIn(0, items.lastIndex),
                    onScrub = { scope.launch { pagerState.scrollToPage(it) } }
                )
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (listMode) {
                    // T2 列表模式：一条一行（缩略图 + 日期/情绪 + 两行摘要），点一下回到那一页
                    LazyColumn(Modifier.fillMaxSize()) {
                        itemsIndexed(items, key = { _, it -> it.record.id }) { index, item ->
                            AlbumListRow(
                                item = item,
                                onClick = { listMode = false; jumpTo(index) }
                            )
                        }
                        if (!endReached) {
                            item {
                                LaunchedEffect(items.size) { page++ }
                                Box(Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) {
                                    Text(
                                        "载入中…",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                } else if (items.isEmpty() && !loading) {
                    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        Icon(IconAlbum, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
                        Spacer(Modifier.height(12.dp))
                        Text("还没有照片记录", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else if (items.isNotEmpty()) {
                    HorizontalPager(
                        state = pagerState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 20.dp),
                        pageSpacing = 16.dp,
                        // 预加载相邻 1 页，避免翻页时才现加载图片导致卡顿
                        beyondViewportPageCount = 1
                    ) { index ->
                        val item = items[index]
                        AlbumPage(
                            item = item,
                            selectedImage = (albumState.selected[item.record.id] ?: 0)
                                .coerceIn(0, (item.imagePaths.size - 1).coerceAtLeast(0)),
                            footnotes = albumState.footnotes[item.record.id] ?: emptyList(),
                            onSelectImage = { imageIndex -> scope.launch { albumState = stateStore.setSelected(item.record.id, imageIndex) } },
                            onAddFootnote = { text -> scope.launch { albumState = stateStore.addFootnote(item.record.id, text) }; UiSoundPlayer.playFootnoteAdd(context) },
                            onUpdateFootnote = { entryId, text -> scope.launch { albumState = stateStore.updateFootnote(item.record.id, entryId, text) } },
                            onDeleteFootnote = { entryId -> scope.launch { albumState = stateStore.deleteFootnote(item.record.id, entryId) }; UiSoundPlayer.playFootnoteDelete(context) },
                            onOpenRecord = onOpenRecord,
                            isPinned = albumState.pinned.contains(item.record.id),
                            onTogglePinned = { scope.launch { albumState = stateStore.togglePinned(item.record.id) }; UiSoundPlayer.playAlbumStar(context) }
                        )
                    }
                }
                if (loading) {
                    Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp)) {
                        memore.app.ui.components.MirrorLoading()
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AlbumTimeline(
    items: List<AlbumItem>,
    pinned: Set<String>,
    currentIndex: Int,
    onScrub: (Int) -> Unit
) {
    if (items.isEmpty()) return
    val minT = items.minOf { it.record.createdAt }
    val maxT = items.maxOf { it.record.createdAt }
    val span = (maxT - minT).coerceAtLeast(1L)
    // 分页 items 为"最新在前"（created_at DESC），故线方向：最新=左、最旧=右，与分页一致
    // 非线性压缩：位置 = 时间比例 ^ gamma，把远期空档压短、近期密集区展开
    fun frac(ts: Long) =
        (((maxT - ts).toFloat() / span.toFloat()).coerceIn(0f, 1f)).pow(ALBUM_TIMELINE_GAMMA)
    val lineColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
    val gold = Color(0xFFE6C15A)
    var scrubIndex by remember { mutableIntStateOf(-1) }
    val shownIndex = (if (scrubIndex >= 0) scrubIndex else currentIndex).coerceIn(0, items.lastIndex)

    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .height(34.dp)
            .pointerInput(items, pinned) {
                // 单个手势处理器统一处理点击与拖动：避免两个 pointerInput 竞争，导致点击后被拖不动/回弹。
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var dragging = false
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!dragging && abs(change.position.x - down.position.x) > viewConfiguration.touchSlop) {
                            dragging = true
                        }
                        if (dragging) {
                            change.consume()
                            val f = (change.position.x / size.width).coerceIn(0f, 1f)
                            val idx = items.indices.minByOrNull { abs(frac(items[it].record.createdAt) - f) } ?: 0
                            scrubIndex = idx
                            onScrub(idx)
                        }
                        if (!change.pressed) break
                    }
                    if (!dragging) {
                        // 纯点击：定位到最接近的时间点（优先吸附到附近锚点）
                        val f = (down.position.x / size.width).coerceIn(0f, 1f)
                        val pinnedIdx = items.indices.filter { items[it].record.id in pinned }
                            .minByOrNull { abs(frac(items[it].record.createdAt) - f) }
                        val idx = if (pinnedIdx != null && abs(frac(items[pinnedIdx].record.createdAt) - f) < 0.06f) pinnedIdx
                        else items.indices.minByOrNull { abs(frac(items[it].record.createdAt) - f) } ?: 0
                        scrubIndex = idx
                        onScrub(idx)
                    }
                    // 手势结束：回到以分页 settledPage 为准，避免把圆点钉死在一次点击处
                    scrubIndex = -1
                }
            }
    ) {
        val w = maxWidth.value
        fun xDp(ts: Long) = 4f + frac(ts) * (w - 8f)
        // 横向刻迹线 + 重点标记（可重叠）
        Canvas(Modifier.fillMaxSize()) {
            val y = size.height * 0.45f
            drawLine(lineColor, Offset(6f, y), Offset(size.width - 6f, y), strokeWidth = 2.dp.toPx())
            items.forEachIndexed { i, item ->
                if (item.record.id in pinned) {
                    val x = 6f + frac(item.record.createdAt) * (size.width - 12f)
                    // 2026-11-23：星标从轴向**上**竖，短一点（原来是向下 8dp）
                    drawLine(gold, Offset(x, y), Offset(x, y - 6.dp.toPx()), strokeWidth = 2.dp.toPx())
                }
            }
        }
        // 当前记录位置圆点
        val cx = xDp(items[shownIndex].record.createdAt)
        Box(
            Modifier
                .offset(x = (cx - 4f).dp, y = (maxHeight.value * 0.55f - 4f).dp)
                .size(8.dp)
                .clip(CircleShape)
                .background(lineColor)
        )
        // 浮动时间标签：日期；若当日有 ≥2 条重点则附“· N条”，无重叠不显示
        val date = formatDateOnly(items[shownIndex].record.createdAt)
        val sameDayPinned = items.count { it.record.id in pinned && formatDateOnly(it.record.createdAt) == date }
        val label = if (sameDayPinned > 1) "$date · ${sameDayPinned}条" else date
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.align(Alignment.BottomStart).padding(start = 4.dp, bottom = 0.dp)
        )
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
private fun AlbumListRow(item: AlbumItem, onClick: () -> Unit) {
    val context = LocalContext.current
    val emotion = EmotionStats.emotionEntries(item.record).firstOrNull()?.name
    val excerpt = item.record.content
        .replace('\n', ' ')
        .trim()
        .take(60)
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            Modifier
                .size(width = 78.dp, height = 62.dp)
                .clip(MirrorShapes.row)
                .background(MaterialTheme.colorScheme.surfaceContainerLow)
        ) {
            item.imagePaths.firstOrNull()?.let { path ->
                AsyncImage(
                    model = ImageRequest.Builder(context).data(File(path)).size(220).build(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
            if (item.imagePaths.size > 1) {
                Text(
                    "${item.imagePaths.size}",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(3.dp)
                        .clip(MirrorShapes.pill)
                        .background(Color.Black.copy(alpha = 0.45f))
                        .padding(horizontal = 5.dp, vertical = 1.dp)
                )
            }
        }
        Spacer(Modifier.width(11.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    formatDateOnly(item.record.createdAt),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (emotion != null) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        emotion,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .clip(MirrorShapes.pill)
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
                            .padding(horizontal = 6.dp, vertical = 1.dp)
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                excerpt,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                lineHeight = 17.sp
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
private fun AlbumPage(
    item: AlbumItem,
    selectedImage: Int,
    footnotes: List<AlbumFootnote>,
    onSelectImage: (Int) -> Unit,
    onAddFootnote: (String) -> Unit,
    onUpdateFootnote: (Long, String) -> Unit,
    onDeleteFootnote: (Long) -> Unit,
    onOpenRecord: (String) -> Unit,
    isPinned: Boolean,
    onTogglePinned: () -> Unit
) {
    val context = LocalContext.current
    var menuOpen by remember { mutableStateOf(false) }
    var zoomPath by remember { mutableStateOf<String?>(null) }
    var editTarget by remember { mutableStateOf<FootnoteTarget?>(null) }
    val record = item.record
    val currentPath = item.imagePaths.getOrNull(selectedImage)
    val emotion = EmotionStats.emotionEntries(record).firstOrNull()?.name
    val segments = if (record.type == RecordType.DIALOGUE) dialogueSegments(record.content, record.dialogueAiReply) else emptyList()

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 32.dp)
    ) {
        Surface(
            color = paperColor(),
            shape = TornPaperShape(),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Box(Modifier.fillMaxWidth()) {
            PaperRuling(Modifier.matchParentSize())
            Column(Modifier.fillMaxWidth().padding(start = 24.dp, end = 16.dp, top = 16.dp, bottom = 16.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        formatDateOnly(record.createdAt),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.clickable { onOpenRecord(record.id) }
                    )
                    if (emotion != null) {
                        Spacer(Modifier.width(8.dp))
                        // 情绪给名字 + 淡底药丸，不再只有一个方块
                        Box(
                            Modifier
                                .clip(MirrorShapes.pill)
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
                                .padding(horizontal = 7.dp, vertical = 2.dp)
                        ) {
                            Text(
                                emotion,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    Icon(
                        if (isPinned) Icons.Filled.Star else Icons.Filled.StarBorder,
                        contentDescription = if (isPinned) "取消重点" else "设为重点",
                        tint = Color(0xFFE6C15A),
                        modifier = Modifier.size(28.dp).clickable { onTogglePinned() }
                    )
                    Spacer(Modifier.weight(1f))
                    Text("${item.imagePaths.size} 张", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                Spacer(Modifier.height(12.dp))
                Box(Modifier.fillMaxWidth()) {
                    if (currentPath != null) {
                        AsyncImage(
                            model = ImageRequest.Builder(context).data(File(currentPath)).size(800).build(),
                            contentDescription = record.content.take(20),
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(16f / 9f)
                                .clip(MirrorShapes.row)
                                .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f), MirrorShapes.row)
                                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                                .combinedClickable(
                                    onClick = { zoomPath = currentPath },
                                    onLongClick = { menuOpen = true }
                                )
                        )
                    } else {
                        Box(
                            Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(MirrorShapes.row)
                                .background(MaterialTheme.colorScheme.surfaceContainerLow),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("图片缺失", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    MirrorMenu(
                        expanded = menuOpen,
                        onDismissRequest = { menuOpen = false },
                        actions = buildList {
                            currentPath?.let { path ->
                                add(MirrorMenuAction("放大查看") { menuOpen = false; zoomPath = path })
                            }
                            item.imagePaths.forEachIndexed { index, _ ->
                                add(MirrorMenuAction(
                                    if (index == selectedImage) "第 ${index + 1} 张（当前）" else "查看第 ${index + 1} 张",
                                    selected = index == selectedImage
                                ) {
                                    menuOpen = false
                                    onSelectImage(index)
                                    item.imagePaths.getOrNull(index)?.let { zoomPath = it }
                                })
                            }
                        }
                    )
                }

                if (item.imagePaths.size > 1) {
                    Spacer(Modifier.height(10.dp))
                    FlowRow(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // 限制缩略图数量，避免同屏并发加载过多小图造成卡顿（其余见"N 张"）
                        item.imagePaths.take(8).forEachIndexed { index, path ->
                            val selected = index == selectedImage
                            AsyncImage(
                                model = ImageRequest.Builder(context).data(File(path)).size(160).build(),
                                contentDescription = "第 ${index + 1} 张",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .size(64.dp)
                                    .clip(MirrorShapes.row)
                                    .border(2.dp, if (selected) MaterialTheme.colorScheme.primary else Color.Transparent, MirrorShapes.row)
                                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
                                    .clickable { onSelectImage(index) }
                                    .alpha(if (selected) 1f else 0.72f)
                            )
                        }
                    }
                }

                Spacer(Modifier.height(20.dp))
                val body = if (record.type == RecordType.DIALOGUE && segments.isNotEmpty()) {
                    segments
                } else {
                    record.content.ifBlank { record.notes }
                        .split(PARAGRAPH_SPLIT)
                        .map { it.trim('\n', ' ', '\t', '\u3000') }
                        .filter { it.isNotBlank() }
                        .ifEmpty { listOf("") }
                }
                body.forEachIndexed { index, paragraph ->
                    if (index > 0 && record.type == RecordType.DIALOGUE) {
                        Spacer(Modifier.height(20.dp))
                        HorizontalDivider(thickness = 2.dp, color = MaterialTheme.colorScheme.outline.copy(alpha = 0.7f))
                        Spacer(Modifier.height(20.dp))
                    } else if (index > 0) {
                        Spacer(Modifier.height(12.dp))
                    }
                    // 散文/诗，两种排法：
                    //  · 一段里有多行、且每行都短（≤24 字）→ 是作者刻意的断句，**不缩进、行距收紧**，左齐排；
                    //  · 其余按中文书式，**只给段首两字缩进**，行高 1.85。
                    val lines = paragraph.split('\n').filter { it.isNotBlank() }
                    val avgLen = if (lines.isEmpty()) 0 else lines.sumOf { it.length } / lines.size
                    val isVerse = lines.size >= 2 && avgLen <= 24
                    Text(
                        paragraph,
                        style = memore.app.ui.theme.MemoreType.reading.copy(
                            lineHeight = if (isVerse) 27.sp else 30.sp,
                            textIndent = if (isVerse) TextIndent.None else TextIndent(firstLine = 31.sp),
                            letterSpacing = 0.2.sp
                        ),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                Spacer(Modifier.height(20.dp))
                DiaryDashedDivider()
                Spacer(Modifier.height(14.dp))
                Text("注脚", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                if (footnotes.isNotEmpty()) {
                    footnotes.forEach { footnote ->
                        FootnoteBlock(
                            footnote = footnote,
                            onClick = { editTarget = FootnoteTarget(footnote.createdAt, footnote.content) }
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                }
                // Z1：输入不再是常驻方框，改一行「＋ 写注脚」
                Text(
                    "＋ 写注脚",
                    fontSize = 12.5.sp,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.fillMaxWidth()
                        .clickable { editTarget = FootnoteTarget(null, "") }
                        .padding(vertical = 10.dp)
                )
            }
            // 纸纹已撤：在 matchParentSize 上逐点画圆，条目一长就上千次 draw，
            // 滚动时每帧重画 → 卡。物质感改由 TornPaperShape + 横格纸 + 纸色承担。

            }
        }
    }

    editTarget?.let { target ->
        FootnoteEditSheet(
            initial = target.content,
            isEdit = target.entryId != null,
            onDismiss = { editTarget = null },
            onSave = { text ->
                if (text.isBlank()) {
                    target.entryId?.let(onDeleteFootnote)
                } else if (target.entryId == null) {
                    onAddFootnote(text)
                } else {
                    onUpdateFootnote(target.entryId, text)
                }
                editTarget = null
            }
        )
    }

    zoomPath?.let { path ->
        ZoomableImageView(path = path, onDismiss = { zoomPath = null })
    }
}

@Composable
private fun FootnoteBlock(footnote: AlbumFootnote, onClick: () -> Unit) {
    // Z1：不再套白卡、不再画虚线 —— 注脚就是一条带左竖线的行
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 6.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            Modifier.width(2.dp).fillMaxHeight().background(MaterialTheme.colorScheme.primary.copy(alpha = 0.55f))
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                footnote.content,
                fontSize = 14.sp,
                lineHeight = 21.sp,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(3.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    formatTimestamp(footnote.createdAt),
                    fontSize = 10.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                footnote.updatedAt?.let { updatedAt ->
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "改 ${formatTimestamp(updatedAt)}",
                        fontSize = 10.5.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FootnoteEditSheet(
    initial: String,
    isEdit: Boolean,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    var draft by remember { mutableStateOf(initial) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    MirrorSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            Text(
                if (isEdit) "编辑注脚" else "写注脚",
                fontSize = 10.5.sp,
                letterSpacing = 1.1.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 2.dp)
            )
            // 书写区：上下两条线夹住，左右不出边（G1）
            Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f)))
            BasicTextField(
                value = draft,
                onValueChange = { draft = it },
                textStyle = androidx.compose.ui.text.TextStyle(
                    fontSize = 15.sp,
                    lineHeight = 27.sp,
                    color = MaterialTheme.colorScheme.onSurface
                ),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier.fillMaxWidth().heightIn(min = 108.dp).padding(vertical = 10.dp),
                decorationBox = { inner ->
                    Box {
                        if (draft.isEmpty()) {
                            Text(
                                "写点什么…",
                                fontSize = 15.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.40f)
                            )
                        }
                        inner()
                    }
                }
            )
            Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f)))

            Spacer(Modifier.height(16.dp))
            Button(
                onClick = { onSave(draft) },
                enabled = draft.isNotBlank() || !isEdit,
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = MirrorShapes.card,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
            ) {
                Text("保存", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onPrimary)
            }
            if (isEdit) {
                Text(
                    "删除注脚",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.fillMaxWidth().clickable { showDeleteConfirm = true }
                        .padding(vertical = 12.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
        }
    }

    if (showDeleteConfirm) {
        MirrorStackDialog(
            title = "删除注脚",
            message = "删除后不能恢复。",
            confirmText = "删除",
            destructive = true,
            onDismiss = { showDeleteConfirm = false },
            onConfirm = { showDeleteConfirm = false; onSave("") }
        )
    }
}

@Composable
private fun DiaryDashedDivider() {
    val color = MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)
    Canvas(Modifier.fillMaxWidth().height(8.dp)) {
        val dash = 6.dp.toPx()
        val gap = 5.dp.toPx()
        val y = size.height / 2f
        var x = 0f
        while (x < size.width) {
            drawLine(color, Offset(x, y), Offset((x + dash).coerceAtMost(size.width), y), strokeWidth = 1.dp.toPx())
            x += dash + gap
        }
    }
}

@Composable
private fun paperColor(): Color =
    MaterialTheme.colorScheme.background

@Composable
private fun memoColor(): Color =
    MaterialTheme.colorScheme.surfaceContainerHigh

@Composable
private fun PaperHoles(modifier: Modifier) {
    val holeColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.55f)
    Canvas(modifier) {
        val holeX = 13.dp.toPx()
        val holeRadius = 4.dp.toPx()
        val holeStep = 46.dp.toPx()
        var hy = 26.dp.toPx()
        while (hy <= size.height - 24.dp.toPx()) {
            drawCircle(color = holeColor, radius = holeRadius, center = Offset(holeX, hy), style = Stroke(width = 1.2.dp.toPx()))
            hy += holeStep
        }
    }
}

@Composable
private fun PaperRuling(modifier: Modifier) {
    val color = MaterialTheme.colorScheme.outline.copy(alpha = 0.20f)
    Canvas(modifier) {
        val lineHeight = 26.sp.toPx()
        val startX = 24.dp.toPx()
        val endX = size.width - 16.dp.toPx()
        val dash = 5.dp.toPx()
        val gap = 4.dp.toPx()
        val path = Path()
        var y = 16.dp.toPx()
        while (y <= size.height - 12.dp.toPx()) {
            var x = startX
            while (x < endX) {
                path.moveTo(x, y)
                path.lineTo((x + dash).coerceAtMost(endX), y)
                x += dash + gap
            }
            y += lineHeight
        }
        drawPath(path, color = color, style = Stroke(width = 1.dp.toPx()))
    }
}

private class TornPaperShape : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val path = Path()
        val w = size.width
        val h = size.height
        val jag = with(density) { 2.4.dp.toPx() }
        val step = with(density) { 7.dp.toPx() }
        path.moveTo(w, 0f)
        path.lineTo(w, h)
        var y = h
        var flip = 1f
        while (y > 0f) {
            y = (y - step).coerceAtLeast(0f)
            val x = jag * flip
            path.lineTo(x, y)
            flip *= -1f
        }
        path.lineTo(0f, 0f)
        path.close()
        return Outline.Generic(path)
    }
}

@Composable
private fun ZoomableImageView(path: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val gifLoader = remember { ImageDecoders.animatedImageLoader(context) }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black)
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        val newScale = (scale * zoom).coerceIn(1f, 5f)
                        if (newScale != scale) {
                            offset = offset * (newScale / scale)
                        }
                        offset += pan
                        scale = newScale
                        if (scale <= 1f) {
                            offset = Offset.Zero
                        } else {
                            val maxX = size.width * (scale - 1f) / 2f
                            val maxY = size.height * (scale - 1f) / 2f
                            offset = Offset(offset.x.coerceIn(-maxX, maxX), offset.y.coerceIn(-maxY, maxY))
                        }
                    }
                }
                .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { onDismiss() }
        ) {
            // 全 App 唯一开启动图的位置：GIF / 动图 WebP 在这里逐帧播放。
            // 其余位置走默认 loader（Coil 2 不注册动图解码器），天然只显示首帧。
            AsyncImage(
                model = ImageRequest.Builder(context).data(File(path)).size(1400).build(),
                // 走带动图解码器的专用 loader（请求级 decoderFactory 在 AsyncImage 这条路上会丢）
                imageLoader = gifLoader,
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offset.x
                        translationY = offset.y
                    }
            )
            IconButton(onClick = onDismiss, modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)) {
                Icon(IconClose, "关闭", tint = Color.White)
            }
        }
    }
}
