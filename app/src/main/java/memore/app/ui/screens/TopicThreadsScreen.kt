package memore.app.ui.screens

import memore.app.ui.components.MirrorShapes

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import memore.app.R
import memore.app.data.local.AppDatabase
import memore.app.data.repository.RecordRepository
import memore.app.data.vector.MonthlyTopicsService
import memore.app.data.vector.TopicThreads
import memore.app.ui.components.BusyDialog
import memore.app.ui.components.MemorePage
import memore.app.ui.components.MirrorAlertDialog
import memore.app.ui.components.MirrorAnchoredMenu
import memore.app.ui.components.MirrorLoading
import memore.app.ui.components.MirrorMenuAction
import memore.app.ui.theme.MemoreType
import memore.app.utils.memoreToast
import android.widget.Toast
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import kotlinx.coroutines.launch

/**
 * 线索页（2026-11-17，方案 3+2+A）。
 *
 * 入口：主题页顶栏右端、刷新图标左侧。
 * 河流：月份自上而下，每月一条 44dp 横带；段宽 = 当月占比。
 * 选择：右上 ✓ 锚定菜单（≤3，带总数与身份色点），不压暗、不整屏遮。
 * 卡片：名称 + 「详情 ›」+ 大值 + 逐月条数；详情进该线索的记录列表。
 */
@Composable
fun TopicThreadsScreen(
    repository: RecordRepository?,
    db: AppDatabase,
    onBack: () -> Unit,
    onOpenThread: (String, List<String>) -> Unit = { _, _ -> }
) {
    var months by remember { mutableStateOf<List<MonthlyTopicsService.NamedMonth>>(emptyList()) }
    var threads by remember { mutableStateOf<List<TopicThreads.Thread>?>(null) }
    var selection by rememberSaveable(stateSaver = androidx.compose.runtime.saveable.listSaver<Set<String>, String>(
        save = { it.toList() }, restore = { it.toSet() })) { mutableStateOf<Set<String>>(emptySet()) }
    var loading by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf("") }
    var confirmAlign by remember { mutableStateOf(false) }
    var pickerOpen by rememberSaveable { mutableStateOf(false) }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()

    fun totalOf(t: TopicThreads.Thread, ms: List<MonthlyTopicsService.NamedMonth>): Int =
        ms.sumOf { m -> t.members.sumOf { name -> m.clusters.filter { it.name == name }.sumOf { it.count } } }

    LaunchedEffect(Unit) {
        val snap = runCatching { MonthlyTopicsService(ctx, repository ?: return@LaunchedEffect, db) { _, _ -> emptyList() }.cached() }.getOrNull()
        months = snap?.months.orEmpty()
        val cached = snap?.let { TopicThreads.cachedIfFresh(ctx, it.months) }
        threads = cached
        // 默认选中总条数最多的一条：打开页面就有卡片，不会空着一大片
        if (cached != null && cached.isNotEmpty()) {
            selection = setOf(cached.maxByOrNull { totalOf(it, months) }!!.name)
        }
        loading = false
    }

    fun doAlign() {
        if (months.isEmpty()) return
        busy = "整理线索…"
        scope.launch {
            val result = TopicThreads.align(ctx, months)
            val list = result.threads
            if (list != null) {
                threads = list
                if (list.isNotEmpty()) selection = setOf(list.maxByOrNull { totalOf(it, months) }!!.name)
            } else {
                ctx.memoreToast(result.error ?: "整理失败", Toast.LENGTH_SHORT)
            }
            busy = ""
        }
    }

    val ordered = months.sortedBy { it.month }
    val rows = (threads ?: emptyList()).sortedByDescending { totalOf(it, ordered) }
    val slotColors = listOf(
        MaterialTheme.colorScheme.primary,
        Color(0xFF3B82F6),
        Color(0xFFFFA726)
    )
    val contextColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)
    val onPrimary = MaterialTheme.colorScheme.onPrimary
    val onSurface = MaterialTheme.colorScheme.onSurface

    fun toggle(name: String) {
        selection = when {
            name in selection -> selection - name
            selection.size >= 3 -> selection
            else -> selection + name
        }
    }

    fun colorOf(name: String): Color = when {
        selection.isEmpty() -> slotColors[0].copy(alpha = 0.40f)
        name in selection -> {
            val idx = rows.indexOfFirst { it.name == name }
            slotColors[if (idx >= 0) idx % slotColors.size else 0]
        }
        else -> contextColor
    }

    fun countIn(t: TopicThreads.Thread, m: MonthlyTopicsService.NamedMonth): Int =
        t.members.sumOf { name -> m.clusters.filter { it.name == name }.sumOf { it.count } }

    /** 槽位 3 是橙，白字对比不够，改深字。 */
    fun textColorFor(name: String): Color =
        if (selection.isNotEmpty() && selection.toList().indexOf(name) == 2) onSurface else onPrimary

    MemorePage(
        title = "线索",
        onBack = onBack,
        actions = {
            if (threads != null) {
                MirrorAnchoredMenu(
                    expanded = pickerOpen,
                    onDismissRequest = { pickerOpen = false },
                    actions = rows.map { t ->
                        val on = t.name in selection
                        MirrorMenuAction(
                            label = t.name,
                            selected = on,
                            enabled = on || selection.size < 3,
                            dotColor = if (on) colorOf(t.name)
                            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.25f),
                            trailing = "${totalOf(t, ordered)}",
                            onClick = { toggle(t.name) }
                        )
                    }
                ) {
                    IconButton(onClick = { pickerOpen = true }) {
                        Icon(Icons.Outlined.Checklist, "选择线索", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            IconButton(onClick = { confirmAlign = true }) {
                Icon(
                    painterResource(R.drawable.ic_topic_refresh), "整理线索",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp)
                .verticalScroll(rememberScrollState())
        ) {
            when {
                loading -> MirrorLoading()
                months.isEmpty() -> Text(
                    "还没有月度主题：先去主题页刷新",
                    style = MemoreType.meta,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 18.dp)
                )
                // 2026-11-19：这里原来还有一个「整理线索」行，和右上角按钮重复触发同一件事，
                // 用户要求只留右上角那一个，所以空态这里不再放第二入口。
                threads == null -> Unit
                else -> {
                    val firstNum = monthNum(ordered.first().month)
                    val lastNum = monthNum(ordered.last().month)
                    val year = ordered.first().month.substring(0, 4)
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                        Box(Modifier.size(7.dp).background(MaterialTheme.colorScheme.primary, androidx.compose.foundation.shape.CircleShape))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "${rows.size} 条线索",
                            style = MemoreType.meta,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.weight(1f))
                        Text(
                            "$year 年 $firstNum 月 → $lastNum 月",
                            style = MemoreType.meta,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.height(18.dp))

                    // 纵向河流：月份自上而下；左侧一条时间轴（点 + 竖线），带 44dp
                    ordered.forEach { m ->
                        val total = m.clusters.sumOf { it.count }
                        val num = monthNum(m.month)
                        Row(Modifier.height(54.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.width(16.dp).fillMaxHeight()) {
                                Box(
                                    Modifier.align(Alignment.CenterStart)
                                        .padding(start = 6.dp).width(1.dp).fillMaxHeight()
                                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f))
                                )
                                Box(
                                    Modifier.align(Alignment.TopStart)
                                        .padding(start = 3.dp, top = 11.dp).size(7.dp)
                                        .background(
                                            MaterialTheme.colorScheme.primary,
                                            androidx.compose.foundation.shape.CircleShape
                                        )
                                )
                            }
                            Column(Modifier.width(50.dp)) {
                                Text(
                                    "$num 月",
                                    style = MemoreType.meta,
                                    color = onSurface
                                )
                                Text(
                                    "$total 条",
                                    style = MemoreType.meta.copy(fontFeatureSettings = "tnum"),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Row(Modifier.weight(1f).height(48.dp)) {
                                rows.forEach { t ->
                                    val c = countIn(t, m)
                                    if (c > 0 && total > 0) {
                                        val frac = c.toFloat() / total
                                        Box(
                                            Modifier.weight(c.toFloat()).fillMaxHeight()
                                                .padding(horizontal = 1.dp)
                                                .background(colorOf(t.name), MirrorShapes.pill)
                                                .clickable { toggle(t.name) }
                                                .semantics { contentDescription = "${t.name} ${c} 条" },
                                            contentAlignment = Alignment.Center
                                        ) {
                                            if (frac >= 0.20f) {
                                                Text(
                                                    t.name,
                                                    fontSize = 11.sp,
                                                    color = if (t.name in selection) textColorFor(t.name)
                                                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                                                    maxLines = 1
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                    }

                    Spacer(Modifier.height(18.dp))
                    Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)))
                    Spacer(Modifier.height(18.dp))

                    // 选中卡片：名称 + 详情 + 大值 + 逐月条数
                    val selected = rows.filter { it.name in selection }
                    if (selected.isEmpty()) {
                        Text(
                            "点一条带子上的段",
                            style = MemoreType.meta,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 12.dp)
                        )
                    } else {
                        selected.forEachIndexed { cardIndex, t ->
                            if (cardIndex > 0) {
                                Spacer(Modifier.height(16.dp))
                                Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)))
                                Spacer(Modifier.height(16.dp))
                            }
                            val counts = ordered.map { countIn(t, it) }
                            val first = counts.indexOfFirst { it > 0 }.coerceAtLeast(0)
                            val last = counts.indexOfLast { it > 0 }.coerceAtLeast(0)
                            Column(Modifier.fillMaxWidth()) {
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        t.name,
                                        style = MemoreType.sectionTitle,
                                        color = onSurface,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Box(
                                        Modifier.height(44.dp).clickable { onOpenThread(t.name, t.members) }
                                            .padding(start = 16.dp),
                                        contentAlignment = Alignment.CenterEnd
                                    ) {
                                        Text(
                                            "详情 ›",
                                            style = MemoreType.meta,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                        )
                                    }
                                }
                                Row(verticalAlignment = Alignment.Bottom) {
                                    Text(
                                        "${counts.sum()}",
                                        style = MemoreType.pageTitle,
                                        fontWeight = FontWeight.SemiBold,
                                        color = onSurface
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        "条 · " + ordered[first].month.substring(5) + " → " + ordered[last].month.substring(5),
                                        style = MemoreType.meta,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(bottom = 3.dp)
                                    )
                                }
                                Spacer(Modifier.height(10.dp))
                                Row(Modifier.fillMaxWidth()) {
                                    ordered.forEachIndexed { i, mm ->
                                        Column(Modifier.weight(1f)) {
                                            Text(
                                                "${monthNum(mm.month)} 月",
                                                style = MemoreType.meta,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                            )
                                            Text(
                                                if (counts[i] > 0) "${counts[i]}" else "",
                                                style = MemoreType.meta.copy(fontFeatureSettings = "tnum"),
                                                color = if (counts[i] > 0) colorOf(t.name) else Color.Transparent
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }

    if (busy.isNotEmpty()) BusyDialog(busy)

    if (confirmAlign) {
        MirrorAlertDialog(
            onDismissRequest = { confirmAlign = false },
            title = { Text("整理线索", style = MaterialTheme.typography.headlineSmall) },
            text = { Text("调用 1 次 AI，发送每月的主题名、条数与一句代表句，不发送整篇正文。") },
            confirmButton = {
                TextButton(onClick = { confirmAlign = false; doAlign() }) {
                    Text("开始", color = MaterialTheme.colorScheme.primary)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmAlign = false }) {
                    Text("取消", color = MaterialTheme.colorScheme.primary)
                }
            }
        )
    }
}

/** 从 yyyy-MM 取月号；格式异常时不崩，原文返回。 */
private fun monthNum(month: String): String {
    val part = month.substringAfter('-', month)
    return part.trimStart('0').ifBlank { "0" }
}

