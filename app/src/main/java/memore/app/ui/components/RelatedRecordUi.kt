package memore.app.ui.components

import androidx.compose.runtime.*
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.TextSnippet
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import memore.app.data.model.Record
import memore.app.data.model.RecordType
import memore.app.utils.formatTimestamp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

data class RelatedEntry(val record: Record, val amendments: List<Record> = emptyList())

data class RelatedEntryGroup(val label: String, val entries: List<RelatedEntry>)

fun buildRelatedEntries(records: List<Record>): List<RelatedEntry> {
    val amends = HashMap<String, MutableList<Record>>()
    val parents = mutableListOf<Record>()
    for (r in records) {
        if (r.type == RecordType.AMENDMENT && r.correctionOfId != null) {
            amends.getOrPut(r.correctionOfId!!) { mutableListOf() }.add(r)
        } else {
            parents += r
        }
    }
    val entries = parents.sortedByDescending { it.createdAt }.map { p ->
        RelatedEntry(p, amends[p.id].orEmpty().sortedBy { it.createdAt })
    }.toMutableList()
    return entries
}

fun groupRelatedEntries(entries: List<RelatedEntry>): List<RelatedEntryGroup> {
    val order = listOf("今天", "昨天", "本周", "更早")
    val today = LocalDate.now()
    val map = LinkedHashMap<String, MutableList<RelatedEntry>>()
    order.forEach { map[it] = mutableListOf() }
    entries.sortedByDescending { it.record.createdAt }.forEach { entry ->
        val date = Instant.ofEpochMilli(entry.record.createdAt).atZone(ZoneId.systemDefault()).toLocalDate()
        val label = when {
            date == today -> "今天"
            date == today.minusDays(1) -> "昨天"
            date.isAfter(today.minusDays(7)) -> "本周"
            else -> "更早"
        }
        map[label]!!.add(entry)
    }
    return order.map { RelatedEntryGroup(it, map[it]!!) }.filter { it.entries.isNotEmpty() }
}


/**
 * 按月分组（2026-11-19）：线索详情用。
 *
 * 为什么和 [groupRelatedEntries] 分开：那套是「今天/昨天/本周/更早」，适合看「最近又提了什么」；
 * 线索是跨月的一条线，要按月份看出它持续了多久、每个月各装了什么，所以是另一个分组口径。
 * 新的一月在上，月内按时间倒序。
 */
fun groupRelatedEntriesByMonth(entries: List<RelatedEntry>): List<RelatedEntryGroup> {
    val fmt = java.time.format.DateTimeFormatter.ofPattern("yyyy 年 M 月", java.util.Locale.CHINA)
    val map = LinkedHashMap<String, MutableList<RelatedEntry>>()
    entries.sortedByDescending { it.record.createdAt }.forEach { entry ->
        val month = Instant.ofEpochMilli(entry.record.createdAt)
            .atZone(ZoneId.systemDefault()).toLocalDate().format(fmt)
        map.getOrPut(month) { mutableListOf() }.add(entry)
    }
    return map.map { (label, list) -> RelatedEntryGroup(label, list) }
}

@Composable
fun RelatedRecordsHeader(
    icon: ImageVector,
    title: String,
    overview: String,
    chips: List<Pair<String, Int>> = emptyList()
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f),
                shape = CircleShape,
                modifier = Modifier.size(56.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(icon, null, Modifier.size(30.dp), tint = MaterialTheme.colorScheme.primary)
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                Text(overview, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (chips.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                chips.forEach { (label, count) ->
                    AssistChip(
                        onClick = {},
                        label = { Text("$label $count") }
                    )
                }
            }
        }
    }
}

@Composable
fun RelatedGroupHeader(label: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 18.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            fontSize = 10.5.sp,
            letterSpacing = 0.8.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.width(8.dp))
        HorizontalDivider(Modifier.weight(1f), thickness = 1.dp, color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
    }
}

@Composable
fun RelatedRecordRow(
    record: Record,
    trailing: Boolean,
    onClick: () -> Unit,
    onViewAttachments: () -> Unit,
    onViewSummary: () -> Unit
) {
    // 只放"种类 + 时间 + 正文"（2026-10-24）：其它信息挪到记录详情页
    // 左右各留 20dp、上下各留一点气（2026-10-24：原来贴边又挤）
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                recordTypeLabel(record.type),
                fontSize = 10.5.sp,
                fontWeight = FontWeight.SemiBold,
                color = recordTypeColor(record.type)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                formatTimestamp(record.createdAt),
                fontSize = 10.5.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        // 加密记录：正文是密文、content 为空，不能显示成"（空内容）"
        val bodyText = when {
            record.isLocked -> "已加密"
            else -> record.content.ifBlank { record.notes }.ifBlank { "（空内容）" }
        }
        if (record.isLocked) {
            Text(
                bodyText,
                style = memore.app.ui.theme.MemoreType.reading.copy(fontSize = 15.5.sp, lineHeight = 26.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 5.dp, bottom = 12.dp).clickable(onClick = onClick)
            )
        } else {
            // 两行截断 + 时间线同款末尾渐隐（关联记录只渐 4 个字）
            var fadeLeft by remember(record.id) { mutableStateOf(0f) }
            var fadeTop by remember(record.id) { mutableStateOf(0f) }
            var fadeH by remember(record.id) { mutableStateOf(0f) }
            var overflowed by remember(record.id) { mutableStateOf(false) }
            Box(Modifier.fillMaxWidth().padding(top = 5.dp, bottom = 12.dp)) {
                CopyableText(
                    text = bodyText,
                    style = memore.app.ui.theme.MemoreType.reading.copy(fontSize = 15.5.sp, lineHeight = 26.sp),
                    maxLines = 2,
                    overflow = TextOverflow.Clip,
                    onClick = onClick,
                    onTextLayout = { layout ->
                        overflowed = layout.hasVisualOverflow
                        for (i in (layout.lineCount - 1) downTo 0) {
                            val st = layout.getLineStart(i).coerceIn(0, bodyText.length)
                            val en = layout.getLineEnd(i).coerceIn(0, bodyText.length)
                            if (bodyText.substring(st, en).isNotBlank()) {
                                fadeLeft = layout.getLineRight(i)
                                fadeTop = layout.getLineTop(i)
                                fadeH = layout.getLineBottom(i) - layout.getLineTop(i)
                                break
                            }
                        }
                    }
                )
                if (overflowed && fadeH > 0f) {
                    val density = androidx.compose.ui.platform.LocalDensity.current
                    val wPx = with(density) { 64.dp.roundToPx() }
                    Box(
                        Modifier
                            .offset(
                                x = with(density) { (fadeLeft - wPx).toDp() },
                                y = with(density) { fadeTop.toDp() }
                            )
                            .width(64.dp)
                            .height(with(density) { fadeH.toDp() })
                            .background(
                                androidx.compose.ui.graphics.Brush.horizontalGradient(
                                    listOf(
                                        MaterialTheme.colorScheme.background.copy(alpha = 0f),
                                        MaterialTheme.colorScheme.background.copy(alpha = 0.55f),
                                        MaterialTheme.colorScheme.background
                                    )
                                )
                            )
                    )
                }
            }
        }
        // 只有后面还有内容才画线（2026-11-14）。trailing 这个参数以前收了但从没用过，
        // 结果列表最后一条下面也画线——"线说未完，其实已完"。
        if (trailing) {
            Box(
                Modifier.fillMaxWidth().height(1.dp)
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f))
            )
        }
    }
}

@Composable
fun RelatedEntryRow(
    entry: RelatedEntry,
    trailing: Boolean,
    onClick: (Record) -> Unit,
    onViewAttachments: (Record) -> Unit,
    onViewSummary: (Record) -> Unit,
    /** 情绪标签页用：这条记录被标成该情绪时的依据原文（没有就不显示）。 */
    evidence: String? = null
) {
    Column {
        RelatedRecordRow(
            record = entry.record,
            trailing = trailing,
            onClick = { onClick(entry.record) },
            onViewAttachments = { onViewAttachments(entry.record) },
            onViewSummary = { onViewSummary(entry.record) }
        )
        evidence?.takeIf { it.isNotBlank() }?.let { why ->
            Text(
                "「$why」",
                fontSize = 12.sp,
                lineHeight = 18.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 10.dp)
            )
        }
    }
}

@Composable
private fun RelatedAmendmentRow(record: Record, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), verticalAlignment = Alignment.Top) {
        // 从原文主线向下延续的分支：顶部短横线接到增修竖线，竖线贯穿整行
        Spacer(Modifier.width(10.dp))
        Box(Modifier.width(28.dp).fillMaxHeight()) {
            Box(
                Modifier
                    .align(Alignment.TopStart)
                    .width(28.dp)
                    .height(2.dp)
                    .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
            )
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .width(2.dp)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
            )
        }
        Spacer(Modifier.width(6.dp))
        Surface(
            modifier = Modifier.weight(1f),
            shape = MirrorShapes.row,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            border = mirrorHairline()
        ) {
            Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "增修",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = recordTypeColor(RecordType.AMENDMENT)
                )
                    Spacer(Modifier.width(8.dp))
                }
                Spacer(Modifier.height(4.dp))
                CopyableText(
                    text = record.content.ifBlank { record.notes }.ifBlank { "（空内容）" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    onClick = onClick
                )
            }
        }
    }
}

internal fun recordTypeLabel(type: RecordType) = when (type) {
    RecordType.ESSAY -> "随笔"
    RecordType.DIALOGUE -> "对话录"
    RecordType.AMENDMENT -> "增修"
    RecordType.AI_NOTE -> "AI 札记"
}

@Composable
internal fun recordTypeColor(type: RecordType): Color = when (type) {
    RecordType.ESSAY -> MaterialTheme.colorScheme.primary
    RecordType.DIALOGUE -> Color(0xFF4DD0E1)
    RecordType.AMENDMENT -> MaterialTheme.colorScheme.onSurfaceVariant
    RecordType.AI_NOTE -> MaterialTheme.colorScheme.tertiary
}
