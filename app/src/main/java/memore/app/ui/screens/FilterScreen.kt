package memore.app.ui.screens

import android.app.DatePickerDialog
import memore.app.ui.components.MemoreLineInput
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import memore.app.data.repository.ProtocolRepository
import memore.app.data.repository.TagRepository
import memore.app.ui.components.MemorePage
import memore.app.ui.components.MirrorShapes
import memore.app.ui.components.mirrorHairlineColor
import kotlinx.coroutines.flow.first

/**
 * 筛选（整页）。
 *
 * 2026-11-15 重写，三件事：
 *  1. **标签与协议可以同时生效**。以前是二选一（`filterType + filterIds`），
 *     切到协议会把已选标签清掉，也做不到"标签 A 且 协议 B"。现在状态是
 *     `filterTagIds + filterProtocolIds`，页面里各占一行。
 *  2. **排序移出本页**（现在在时间线顶栏）：排序是视图属性，不是筛选条件。
 *  3. 底栏按钮 40dp→48dp、圆角 8→14、字 13.5→14；行高 68→72；`›` 文字→18dp 图标。
 *
 * 结构：本页只放**入口行**（标签 / 协议 / 时间 / 附件 / 加密），
 * 选标签和选协议各自进一个整页（搜索 + 已选 + 对勾），返回逐层退回。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilterScreen(
    state: TimelineScreenState,
    onBack: () -> Unit,
    /**
     * 应用筛选之后去哪（2026-11-15）。
     *
     * 以前「应用」直接调 onBack —— 而 onBack 是弹栈，于是从「更多」页进来的会回到「更多」页，
     * 用户还得再返回一次才看到结果。**筛选的结果在时间线上，应用完就该看到它。**
     * 默认 = onBack（从时间线直接进来的场景，行为不变）。
     */
    onApplied: () -> Unit = onBack,
    tagRepository: TagRepository? = null,
    protocolRepository: ProtocolRepository? = null
) {
    val ctx = LocalContext.current

    // 工作副本：点「应用」才写回 state，中途退出不生效
    var tagIds by rememberSaveable { mutableStateOf(state.filterTagIds) }
    var protocolIds by rememberSaveable { mutableStateOf(state.filterProtocolIds) }
    var start by rememberSaveable { mutableStateOf(state.filterStart) }
    var end by rememberSaveable { mutableStateOf(state.filterEnd) }
    var hasAttach by rememberSaveable { mutableStateOf(state.filterHasAttachment) }
    var locked by rememberSaveable { mutableStateOf(state.filterLocked) }

    /** null = 不在选择器里；否则 = 正在选哪个维度。 */
    var picking by remember { mutableStateOf<FilterType?>(null) }
    var allTags by remember { mutableStateOf<List<Pair<Int, String>>>(emptyList()) }
    var allProtocols by remember { mutableStateOf<List<Pair<Int, String>>>(emptyList()) }

    LaunchedEffect(Unit) {
        runCatching { tagRepository?.getAllTags()?.first()?.map { it.id to it.name } ?: emptyList() }
            .onSuccess { allTags = it }
        runCatching { protocolRepository?.getAllProtocols()?.first()?.map { it.id to it.name } ?: emptyList() }
            .onSuccess { allProtocols = it }
    }

    // 选标签/协议时，返回键先回筛选页
    BackHandler(enabled = picking != null) { picking = null }

    val pickingKind = picking
    if (pickingKind != null) {
        val source = if (pickingKind == FilterType.TAG) allTags else allProtocols
        PickerPage(
            title = if (pickingKind == FilterType.TAG) "标签" else "协议",
            all = source,
            selected = if (pickingKind == FilterType.TAG) tagIds else protocolIds,
            onChange = { if (pickingKind == FilterType.TAG) tagIds = it else protocolIds = it },
            onBack = { picking = null }
        )
        return
    }

    val selectedTagNames = tagIds.mapNotNull { id -> allTags.firstOrNull { it.first == id }?.second }
    val selectedProtocolNames = protocolIds.mapNotNull { id -> allProtocols.firstOrNull { it.first == id }?.second }

    MemorePage(
        title = "筛选",
        onBack = onBack,
        bottomBar = {
            Column(
                Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background)
            ) {
                HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f))
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // 清空 = 只清工作副本；应用 = 写回并返回（设计法 5.1：48dp / 圆角 14）
                    Box(
                        Modifier.weight(1f).height(48.dp)
                            .border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.20f), MirrorShapes.card)
                            .clickable {
                                tagIds = emptyList(); protocolIds = emptyList()
                                start = null; end = null; hasAttach = null; locked = null
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text("清空", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Box(
                        Modifier.weight(1f).height(48.dp)
                            .background(MaterialTheme.colorScheme.primary, MirrorShapes.card)
                            .clickable {
                                state.filterTagIds = tagIds
                                state.filterProtocolIds = protocolIds
                                state.filterStart = start
                                state.filterEnd = end
                                state.filterHasAttachment = hasAttach
                                state.filterLocked = locked
                                onApplied()
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text("应用", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                    }
                }
            }
        }
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding)
                .verticalScroll(rememberScrollState())
        ) {
            SectionTitle("对象")
            FilterRow(
                "标签",
                when {
                    tagIds.isEmpty() -> "不限"
                    selectedTagNames.size <= 2 -> selectedTagNames.joinToString("、")
                    else -> "${tagIds.size} 个"
                },
                dim = tagIds.isEmpty()
            ) { picking = FilterType.TAG }
            FilterRow(
                "协议",
                when {
                    protocolIds.isEmpty() -> "不限"
                    selectedProtocolNames.size <= 2 -> selectedProtocolNames.joinToString("、")
                    else -> "${protocolIds.size} 个"
                },
                dim = protocolIds.isEmpty()
            ) { picking = FilterType.PROTOCOL }

            SectionTitle("其他")
            // 时间：一行显示区间；点一下先选起、紧接选止（不要再拆成两行）
            FilterRow(
                "时间",
                when {
                    start == null && end == null -> "不限"
                    else -> "${start?.let { memore.app.utils.formatDateOnly(it) } ?: "最早"} → ${end?.let { memore.app.utils.formatDateOnly(it) } ?: "今天"}"
                },
                dim = start == null && end == null
            ) {
                showDatePicker(ctx, start, isEnd = false) { pickedStart ->
                    start = pickedStart
                    showDatePicker(ctx, end, isEnd = true) { end = it }
                }
            }

            FilterRow(
                "附件",
                when (hasAttach) { true -> "有附件"; false -> "无附件"; null -> "不限" },
                dim = hasAttach == null
            ) { hasAttach = when (hasAttach) { null -> true; true -> false; else -> null } }

            FilterRow(
                "加密",
                when (locked) { true -> "已加密"; false -> "未加密"; null -> "不限" },
                dim = locked == null
            ) { locked = when (locked) { null -> true; true -> false; else -> null } }

            Spacer(Modifier.height(24.dp))
        }
    }
}

/** 统一的时间选择：不给 base 就默认今天。 */
private fun showDatePicker(ctx: Context, base: Long?, isEnd: Boolean, onPick: (Long) -> Unit) {
    val cal = java.util.Calendar.getInstance()
    if (base != null) cal.timeInMillis = base
    DatePickerDialog(
        ctx,
        { _, y, m, d ->
            val c = java.util.Calendar.getInstance()
            c.set(y, m, d, if (isEnd) 23 else 0, if (isEnd) 59 else 0, if (isEnd) 59 else 0)
            c.set(java.util.Calendar.MILLISECOND, 0)
            onPick(c.timeInMillis)
        },
        cal.get(java.util.Calendar.YEAR),
        cal.get(java.util.Calendar.MONTH),
        cal.get(java.util.Calendar.DAY_OF_MONTH)
    ).show()
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        fontSize = 10.5.sp,
        letterSpacing = 1.2.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
        modifier = Modifier.padding(start = 20.dp, top = 20.dp, bottom = 6.dp)
    )
}

/** 入口行：72dp 两行式（设计法 2.1），右端 18dp 箭头（2.5）。 */
@Composable
private fun FilterRow(
    label: String,
    value: String,
    dim: Boolean = false,
    onClick: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick)
            .padding(horizontal = 20.dp).height(72.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(3.dp))
            Text(
                value,
                fontSize = 12.sp,
                color = if (dim) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
                else MaterialTheme.colorScheme.primary
            )
        }
        Icon(
            Icons.Outlined.KeyboardArrowRight,
            null,
            Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.30f)
        )
    }
    HorizontalDivider(
        modifier = Modifier.padding(start = 20.dp, end = 20.dp),
        color = mirrorHairlineColor()
    )
}

/** 选标签 / 协议：整页，搜索 + 已选 + 对勾。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PickerPage(
    title: String,
    all: List<Pair<Int, String>>,
    selected: List<Int>,
    onChange: (List<Int>) -> Unit,
    onBack: () -> Unit
) {
    var query by remember { mutableStateOf("") }
    val filtered = remember(query, all) {
        if (query.isBlank()) all else all.filter { it.second.contains(query, ignoreCase = true) }
    }

    MemorePage(
        title = title,
        onBack = onBack,
        actions = {
            Text(
                "完成",
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable(onClick = onBack).padding(horizontal = 16.dp)
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            MemoreLineInput(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                placeholder = { Text("搜索$title", fontSize = 13.sp) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                shape = MirrorShapes.row
            )
            LazyColumn(Modifier.fillMaxSize()) {
                items(filtered, key = { it.first }) { (id, name) ->
                    val on = id in selected
                    Row(
                        Modifier.fillMaxWidth()
                            .clickable {
                                onChange(if (on) selected - id else selected + id)
                            }
                            .padding(horizontal = 20.dp).height(46.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            name,
                            fontSize = 14.5.sp,
                            color = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f)
                        )
                        if (on) Icon(Icons.Outlined.Check, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                    }
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 20.dp, end = 20.dp),
                        color = mirrorHairlineColor()
                    )
                }
            }
        }
    }
}
