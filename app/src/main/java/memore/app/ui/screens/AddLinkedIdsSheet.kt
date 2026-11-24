package memore.app.ui.screens

import androidx.compose.foundation.clickable
import memore.app.ui.components.MemoreLineInput
import androidx.compose.material3.Icon
import androidx.compose.material3.DropdownMenuItem
import memore.app.ui.components.MirrorDropdownMenu
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.KeyboardArrowRight
import androidx.compose.ui.unit.sp
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import memore.app.data.model.Record
import memore.app.data.model.RecordRelation
import memore.app.data.model.RecordRelationType
import memore.app.data.repository.RecordRepository
import memore.app.data.repository.RecordRepository.IdResolveResult
import memore.app.ui.components.CopyableText
import memore.app.ui.components.MirrorSheet
import memore.app.ui.components.MirrorShapes
import memore.app.ui.components.mirrorHairline
import memore.app.ui.components.recordTypeLabel
import memore.app.ui.components.MirrorMenuAction
import memore.app.ui.components.MirrorMenu
import memore.app.utils.UiSoundPlayer
import memore.app.utils.formatTimestamp
import memore.app.utils.memoreToast
import kotlinx.coroutines.launch

/**
 * 记录关系弹层。
 *
 * 关系是**有向 + 带类型**的：本条「类型」→ 目标。
 * 所以这里**分两栏**展示（用户明确要求）：
 *  - **关联**：本条指向它们（我引用了 / 补充了 / 更正了谁）
 *  - **被关联**：它们指向本条（谁引用 / 补充 / 更正了我）
 *
 * 保留"粘贴 ID"这条流：外部 AI 会给出底层记录 ID，粘贴是最快的入口。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddLinkedIdsSheet(
    recordId: String,
    recordRepository: RecordRepository,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope(); val context = LocalContext.current
    var input by remember { mutableStateOf("") }
    // 默认「引用」：三种类型里它最不具破坏性（更正会声明旧的那条不再代表当前事实，不适合当默认）
    var relType by remember { mutableStateOf(RecordRelationType.CITE) }
    var current by remember { mutableStateOf<String?>(null) }
    var outgoing by remember { mutableStateOf<List<Pair<RecordRelation, Record>>>(emptyList()) }
    var incoming by remember { mutableStateOf<List<Pair<RecordRelation, Record>>>(emptyList()) }
    var checking by remember { mutableStateOf(false) }

    suspend fun refresh() {
        current = recordRepository.getRecordById(recordId)?.id
        outgoing = recordRepository.relationsFrom(recordId)
        incoming = recordRepository.relationsTo(recordId)
    }

    LaunchedEffect(recordId) { refresh() }

    fun addRelation() {
        val t = input.trim()
        if (t.isBlank()) { context.memoreToast("请输入目标记录 ID", Toast.LENGTH_SHORT); return }
        scope.launch {
            checking = true
            when (val res = recordRepository.resolveRecordId(t)) {
                is IdResolveResult.NotFound -> context.memoreToast("未找到该记录：$t", Toast.LENGTH_SHORT)
                is IdResolveResult.Ambiguous -> context.memoreToast("短 ID 不唯一，请输入更多位", Toast.LENGTH_SHORT)
                is IdResolveResult.Success -> {
                    if (res.record.id == recordId) {
                        context.memoreToast("不能关联自己", Toast.LENGTH_SHORT)
                    } else if (recordRepository.addRelation(recordId, res.record.id, relType)) {
                        input = ""
                        refresh()
                        UiSoundPlayer.playSave(context)
                    } else {
                        context.memoreToast("这条关系已经存在", Toast.LENGTH_SHORT)
                    }
                }
            }
            checking = false
        }
    }

    /** 出向那条边：本条 → [other]。 */
    fun removeOutgoing(other: String) {
        scope.launch {
            recordRepository.removeRelation(recordId, other)
            refresh()
            UiSoundPlayer.playDelete(context)
        }
    }

    /** 入向那条边：边是 [other] → 本条，别删错方向。 */
    fun removeIncoming(other: String) {
        scope.launch {
            recordRepository.removeRelation(other, recordId)
            refresh()
            UiSoundPlayer.playDelete(context)
        }
    }

    MirrorSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(Modifier.fillMaxWidth().imePadding().padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            // 标题行：左标题，右"本记录 #短ID"（可长按复制完整 ID）
            Row(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "记录关系",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.weight(1f))
                current?.let { cur ->
                    // 只有"长按复制"这四个字是浅色，ID 保持正常灰
                    Text(
                        "长按复制 ",
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
                    )
                    CopyableText(
                        text = "#${cur.take(6)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        copyOptions = listOf(memore.app.ui.components.CopyOption("复制完整 ID", cur))
                    )
                }
            }

            // 类型：一行 + 下拉
            var typeMenu by remember { mutableStateOf(false) }
            Box {
                Row(
                    Modifier.fillMaxWidth().height(50.dp).clickable { typeMenu = true },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("类型", fontSize = 14.5.sp, color = MaterialTheme.colorScheme.onSurface)
                    Spacer(Modifier.weight(1f))
                    Text(
                        RecordRelationType.label(relType),
                        fontSize = 13.sp,
                        color = relationTypeColor(relType)
                    )
                    Spacer(Modifier.width(5.dp))
                    Icon(Icons.Outlined.KeyboardArrowRight, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                // 菜单从行内右侧向下展开，不盖住"类型"
                Box(Modifier.align(Alignment.TopEnd).padding(top = 44.dp)) {
                MirrorMenu(
                    expanded = typeMenu,
                    onDismissRequest = { typeMenu = false },
                    actions = RecordRelationType.ALL.map { t ->
                        MirrorMenuAction(RecordRelationType.label(t)) { typeMenu = false; relType = t }
                    }
                )
                }
            }
            HairlineLine()

            // 目标：横线输入 + 右侧文字动作
            Row(
                Modifier.fillMaxWidth().padding(top = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                MemoreLineInput(
                    value = input,
                    onValueChange = { input = it },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    shape = MirrorShapes.card,
                    placeholder = { Text("输入目标记录 ID", fontSize = 13.sp) },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow
                    )
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    if (checking) "…" else "关联",
                    fontSize = 13.5.sp,
                    color = if (input.isBlank()) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                    else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable { addRelation() }.padding(horizontal = 4.dp, vertical = 8.dp)
                )
            }

            Spacer(Modifier.height(18.dp))

            // 关联（出向）
            Text(
                "关联 ${outgoing.size}",
                fontSize = 10.5.sp,
                letterSpacing = 1.1.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 2.dp)
            )
            if (outgoing.isEmpty()) {
                Text("无", fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f), modifier = Modifier.padding(vertical = 6.dp))
            } else {
                Column(Modifier.fillMaxWidth().heightIn(max = 180.dp).verticalScroll(rememberScrollState())) {
                    outgoing.forEach { (rel, rec) ->
                        RelationRow(
                            rec = rec,
                            type = rel.type,
                            phrase = RecordRelationType.outgoingPhrase(rel.type),
                            onRemove = { removeOutgoing(rec.id) }
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            Text(
                "被关联 ${incoming.size}",
                fontSize = 10.5.sp,
                letterSpacing = 1.1.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 2.dp)
            )
            if (incoming.isEmpty()) {
                Text("无", fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f), modifier = Modifier.padding(vertical = 6.dp))
            } else {
                Column(Modifier.fillMaxWidth().heightIn(max = 180.dp).verticalScroll(rememberScrollState())) {
                    incoming.forEach { (rel, rec) ->
                        RelationRow(
                            rec = rec,
                            type = rel.type,
                            phrase = RecordRelationType.incomingPhrase(rel.type),
                            onRemove = { removeIncoming(rec.id) }
                        )
                    }
                }
            }

            Spacer(Modifier.height(18.dp))
            Button(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = MirrorShapes.card,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
            ) {
                Text("完成", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onPrimary)
            }
        }
    }
}

/** 一个方向的分区：标题 + 计数 + 空态提示 + 列表。 */
@Composable
private fun RelationSection(
    title: String,
    subtitle: String,
    count: Int,
    emptyHint: String,
    content: @Composable (maxHeight: androidx.compose.ui.unit.Dp) -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(6.dp))
        Text("$count", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(6.dp))
        Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.weight(1f))
    }
    Spacer(Modifier.height(6.dp))
    if (count == 0) {
        Text(emptyHint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    } else {
        // 两栏各限高，避免把整个弹层撑爆（每栏超出后可滚）
        content(200.dp)
    }
}

@Composable
private fun RelationRow(
    rec: Record,
    type: String,
    phrase: String,
    onRemove: () -> Unit
) {
    Surface(
        shape = MirrorShapes.row,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
        border = mirrorHairline()
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            // 类型徽标：一眼看清这条边是什么关系
            Surface(color = relationTypeColor(type).copy(alpha = 0.14f), shape = MirrorShapes.row) {
                Text(
                    RecordRelationType.label(type),
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = relationTypeColor(type)
                )
            }
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(recordTypeLabel(rec.type), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(8.dp))
                    Text(formatTimestamp(rec.createdAt), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(8.dp))
                    Text("#${rec.id.take(6)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(2.dp))
                Text(phrase, style = MaterialTheme.typography.labelSmall, color = relationTypeColor(type))
                Spacer(Modifier.height(4.dp))
                if (rec.isLocked) {
                    Text("（已加密，需解锁后查看内容）", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Text(
                        rec.content.ifBlank { rec.notes }.ifBlank { "（空内容）" },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = onRemove) { Text("解除", color = MaterialTheme.colorScheme.error) }
        }
    }
}

/** 类型配色：引用=主色、补充=青、更正=紫（与其它页面的用法保持一致）。 */
@Composable
private fun relationTypeColor(type: String): Color = when (type) {
    RecordRelationType.CITE -> MaterialTheme.colorScheme.primary
    RecordRelationType.SUPPLEMENT -> Color(0xFF4DD0E1)
    RecordRelationType.CORRECT -> Color(0xFFA78BFA)
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

private fun HairlineLine() {}
