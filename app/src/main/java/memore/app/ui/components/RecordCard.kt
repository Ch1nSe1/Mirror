package memore.app.ui.components

import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.foundation.background
import memore.app.ui.components.MirrorShapes
import memore.app.ui.components.mirrorHairline
import memore.app.ui.components.MirrorDropdownMenu
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.NoteAdd
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material.icons.outlined.TextSnippet
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import memore.app.data.model.Protocol
import memore.app.data.model.Record
import memore.app.data.model.RecordType
import memore.app.data.model.Tag
import memore.app.utils.FeatureConsole
import memore.app.utils.LogUtil
import memore.app.utils.formatTimestamp

/** 临时查看模式下的解密内容（不修改 DB）。 */
data class TempDecryptedRecord(
    val content: String,
    val notes: String,
    val analysisSummary: String?,
    val dialogueAiContent: String?,
    val dialogueAiReply: String? = null
)

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun RecordCard(record: Record, tags: List<Tag> = emptyList(), protocols: List<Protocol> = emptyList(), amendments: List<Record> = emptyList(), attachmentCount: Int = 0, batchMode: Boolean = false, isBatchSelected: Boolean = false,
    onSelect: (() -> Unit)? = null, onAddAmendment: (() -> Unit)? = null, onDelete: (() -> Unit)? = null,
    onToggleAnchor: (() -> Unit)? = null, onUnanchor: (() -> Unit)? = null,
    /** 区域页用：把这条记录移出当前自定义区域（对应锚点区的「取消锚定」）。 */
    onRemoveFromZone: (() -> Unit)? = null, onAddOrigin: (() -> Unit)? = null, onAddTags: (() -> Unit)? = null,
    /** 「设置区域」需要的三样：可选区域（含锚点）、这张卡已属的分区、切换回调。
     *  三个都给才出现该入口；没给的屏保持原样（2026-11-14）。 */
    zoneOptions: List<memore.app.data.model.Zone> = emptyList(),
    zoneIdsByRecord: Map<String, Set<String>> = emptyMap(),
    onToggleZoneFor: ((Record, memore.app.data.model.Zone) -> Unit)? = null,
    onToggleAnchorFor: ((Record) -> Unit)? = null,
    onAddProtocols: (() -> Unit)? = null, onAddAttachments: (() -> Unit)? = null,
    onAiSummary: (() -> Unit)? = null, onAddNote: (() -> Unit)? = null,
    onViewAttachments: (() -> Unit)? = null, onViewSummary: (() -> Unit)? = null,
    onVoiceTranslate: (() -> Unit)? = null, voiceAvailable: Boolean = false, onOpenVoice: (() -> Unit)? = null,
    onResynthesize: (() -> Unit)? = null, voiceAudioReady: Boolean = false,
    translatingVoiceIds: Set<String> = emptySet(),
    showEncryptionOptions: Boolean = true,
    onLock: (() -> Unit)? = null, onUnlock: (() -> Unit)? = null,
    isGeneratingVoice: Set<String> = emptySet(),
    tempDecryptedRecord: TempDecryptedRecord? = null, tempDecryptedAmendments: Map<String, String>? = null,
    onTempDecryptClick: (() -> Unit)? = null, onTempLockClick: (() -> Unit)? = null,
    onOpenLinks: (() -> Unit)? = null,
    /** 点整格正文 → 进记录详情（2026-11-08 补：时间线点卡片进不去详情）。 */
    onOpen: (() -> Unit)? = null,
    /** 长按卡头（类型/时间的这一行）直接进批量选择并选中本条；正文不接管长按，保留文本复制。 */
    onLongPressSelect: (() -> Unit)? = null,
    /** 这条记录是「转录」来的（`record.sourceDraftId != null`）→ 点左边那个链环图标回那本草稿。 */
    onOpenSourceDraft: (() -> Unit)? = null,
    isGenerating: Set<String> = emptySet(),
    /** 列表最后一条：底部分隔线用单线。 */
    /** 来源名（null 或「原创」不显示）；由调用方从来源表查好传进来。 */
    originName: String? = null,
    isLast: Boolean = false,
    /** 与下一条跨周：底部分隔线用双线（同一周内用单线）。 */
    weekBreak: Boolean = false,
    modifier: Modifier = Modifier) {
    var showMenu by remember { mutableStateOf(false) }
    var showSubMenu by remember { mutableStateOf(false) }
    /** 「设置区域」的第二层：和「增添」同一个菜单换页，不是底部抽屉（2026-11-14 按用户要求改）。 */
    var showZoneMenu by remember { mutableStateOf(false) }
    var expanded by rememberSaveable(record.id) { mutableStateOf(false) }
    val dt = tags.take(3)
    val dp = protocols.take(3)
    val maxChars = 40
    val fullText = record.content
    val shouldTruncate = !expanded

    Box(modifier = modifier.fillMaxWidth()) {
        // 横格（2026-10-23）：不再用卡片，一条记录 = 纸上的一格；下沿一条贯穿左右边缘的线。
        Column(
            modifier = Modifier.fillMaxWidth()
                .then(if (batchMode && isBatchSelected) Modifier.background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)) else Modifier)
                .then(
                    if (batchMode) Modifier.clickable { onSelect?.invoke() }
                    else if (onOpen != null) Modifier.clickable { onOpen.invoke() }
                    else Modifier
                )
        ) {
            Column(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 13.dp)) {
                Box(Modifier.fillMaxWidth()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                            // 2026-11-18：标题行高度由布局常量决定，不再由“有没有 ⋮”推出来。
                            // 之前 ⋮ 隐藏后行高从 48dp 塌到约 20dp，导致同一种记录在不同页面的
                            // 上下留白不一致（区域页 创作/札记 比 锚点 紧）。
                            .heightIn(min = 48.dp)
                            .then(
                                if (!batchMode && onLongPressSelect != null)
                                    Modifier.combinedClickable(onClick = { onOpen?.invoke() }, onLongClick = { onLongPressSelect.invoke() })
                                else Modifier
                            )
                    ) {
                        TypeBadge(record.type); Spacer(Modifier.width(8.dp))
                        Text(formatTimestamp(record.createdAt), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    originName?.takeIf { it.isNotBlank() && it != "原创" && memore.app.utils.FeatureConsole.visible(memore.app.utils.FeatureConsole.SOURCE_BADGE) }?.let { nm ->
                        Text(
                            "· $nm",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
                        )
                    }
                        if (record.aiStatus != "NONE") {
                        val statusColor = when (record.aiStatus) {
                            "QUEUED" -> Color(0xFFFFA726)
                            "PROCESSING" -> Color(0xFF42A5F5)
                            "SUCCESS" -> Color(0xFF66BB6A)
                            "FAILED" -> Color(0xFFE53935)
                            "WAITING_SERVER" -> Color(0xFF9E9E9E)
                            "STALE" -> Color(0xFFA78BFA)
                            else -> Color(0xFF9E9E9E)
                        }
                        Spacer(Modifier.width(4.dp))
                        Box(Modifier.size(8.dp).clip(CircleShape).background(statusColor))
                    }
                    Spacer(Modifier.weight(1f))
                    // 转录来源：在这一行图标的**最左边**（附件 / 总结 / 语音的左边），点它回那本草稿。
                    // 颜色与同排其它图标一致（灰），不给它上主题色（用户 2026-10-11 指出过）
                    if (record.sourceDraftId != null && onOpenSourceDraft != null) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = if (batchMode) Modifier else Modifier.clickable { onOpenSourceDraft.invoke() }) {
                            Icon(IconTranscribe, "来源草稿", Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Spacer(Modifier.width(8.dp))
                    }
                    if (voiceAvailable && onOpenVoice != null) {
                        // 批量模式下也要能点：之前写成 if (batchMode) Modifier，
                        // 导致进批量后语音图标"还在但点不动"（用户 2026-11-11 报的 bug）
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { onOpenVoice() }) {
                            Icon(Icons.Outlined.RecordVoiceOver, "语音", Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Spacer(Modifier.width(6.dp))
                    }
                    val hasSummary = record.analysis?.summary?.isNotBlank() == true || record.dialogueAiContent.isNotBlank() || tempDecryptedRecord?.analysisSummary?.isNotBlank() == true
                    if (hasSummary && FeatureConsole.visible(FeatureConsole.SUMMARY_VIEW)) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = if (batchMode) Modifier else Modifier.clickable { onViewSummary?.invoke() }) {
                            Icon(Icons.Outlined.TextSnippet, "查看总结", Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Spacer(Modifier.width(6.dp))
                    }
                    if (attachmentCount > 0) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = if (batchMode) Modifier else Modifier.clickable { onViewAttachments?.invoke() }) {
                            Icon(Icons.Outlined.AttachFile, "查看附件", Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.width(2.dp))
                            Text("$attachmentCount", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Spacer(Modifier.width(8.dp))
                    }
                    // 加密记录：标题行锁按钮（点按临时解密；解密后变为重新上锁），对齐锚点区
                    if (record.isLocked && (onTempDecryptClick != null || onTempLockClick != null) && FeatureConsole.visible(FeatureConsole.ENCRYPTION_ICON)) {
                        val isTempDecrypted = tempDecryptedRecord?.content != null
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = if (batchMode) Modifier else Modifier.clickable { if (isTempDecrypted) onTempLockClick?.invoke() else onTempDecryptClick?.invoke() }) {
                            Icon(IconLock, if (isTempDecrypted) "重新上锁" else "解密", Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Spacer(Modifier.width(6.dp))
                    }
                    val additionsAllowed = FeatureConsole.visible(FeatureConsole.ADDITIONS) && !record.isLocked &&
                        (onAddOrigin != null || onAddTags != null || onAddProtocols != null || onAddAttachments != null ||
                            (record.type != RecordType.AMENDMENT && onAddAmendment != null))
                    val zoneAction = onToggleZoneFor != null || onToggleAnchorFor != null || onRemoveFromZone != null ||
                        (onToggleAnchor != null && !record.isAnchored) || (onUnanchor != null && record.isAnchored)
                    // 2026-11-18：菜单为空就不画 ⋮（AI 札记 / 自定义区域此前有一个点了没反应的按钮）
                    val canOpenMenu = !batchMode && if (record.type == RecordType.AI_NOTE) {
                        onAddAttachments != null ||
                            (voiceAudioReady && onResynthesize != null && record.id !in isGeneratingVoice) ||
                            (onLock != null && !record.isLocked && showEncryptionOptions && FeatureConsole.visible(FeatureConsole.ENCRYPTION_FUNCS)) ||
                            (onUnlock != null && record.isLocked && showEncryptionOptions && FeatureConsole.visible(FeatureConsole.ENCRYPTION_FUNCS)) ||
                            onDelete != null
                    } else {
                        additionsAllowed || zoneAction ||
                            onAiSummary != null || onVoiceTranslate != null || onAddNote != null ||
                            (onLock != null && !record.isLocked) || (onUnlock != null && record.isLocked) || onDelete != null
                    }
                    if (canOpenMenu) {
                        Box { IconButton(onClick = { showMenu = true; showSubMenu = false }) { Icon(Icons.Outlined.MoreVert, "更多", Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                            // 锁定即只读：锁定记录不提供增添/增修/总结入口
                            val hasAdditions = FeatureConsole.visible(FeatureConsole.ADDITIONS) && !record.isLocked && (onAddOrigin != null || onAddTags != null || onAddProtocols != null || onAddAttachments != null || (record.type != RecordType.AMENDMENT && onAddAmendment != null))
                            // 法条 4.1：菜单只走 MirrorMenu + MirrorMenuAction（2026-11-14 迁回）
                            val dismiss = { showMenu = false; showSubMenu = false; showZoneMenu = false }
                            if (showZoneMenu) {
                                // 「设置区域」第二层：锚点 + 自定义分区，勾选表示已在其中，点一下切换。
                                val mine = zoneIdsByRecord[record.id].orEmpty()
                                MirrorMenu(showMenu, dismiss, buildList {
                                    zoneOptions.forEach { zone ->
                                        val selected = if (zone.builtin) record.isAnchored else zone.id in mine
                                        add(
                                            MirrorMenuAction(zone.name, selected = selected) {
                                                // 选完就收菜单（2026-11-14）：不收的话弹窗一直挂着，
                                                // 而且再点一次就变成"取消"，看起来像没生效。
                                                dismiss()
                                                if (zone.builtin) onToggleAnchorFor?.invoke(record)
                                                else onToggleZoneFor?.invoke(record, zone)
                                            }
                                        )
                                    }
                                })
                            } else if (showSubMenu) {
                                MirrorMenu(showMenu, dismiss, buildList {
                                    if (onAddOrigin != null) add(MirrorMenuAction("来源") { dismiss(); onAddOrigin() })
                                    if (onAddTags != null) add(MirrorMenuAction("标签") { dismiss(); onAddTags() })
                                    if (onAddProtocols != null) add(MirrorMenuAction("协议") { dismiss(); onAddProtocols() })
                                    if (onAddAttachments != null) add(MirrorMenuAction("附件") { dismiss(); onAddAttachments() })
                                    if (record.type != RecordType.AMENDMENT && onAddAmendment != null) add(MirrorMenuAction("增修") { dismiss(); onAddAmendment() })
                                    if (voiceAudioReady && onResynthesize != null && record.id !in isGeneratingVoice) add(MirrorMenuAction("再次合成") { dismiss(); onResynthesize() })
                                })
                            } else {
                                MirrorMenu(showMenu, dismiss, buildList {
                                    // 2026-11-15：AI 札记是**完全隔离**的一类记录。
                                    // 它只有：正文 / 附件 / 加密 / 语音。标签、协议、由来、情绪、
                                    // 增修、区域、锚点、AI 总结一律不出现——接口层面也不接受。
                                    if (record.type == RecordType.AI_NOTE) {
                                        if (onAddAttachments != null) add(MirrorMenuAction("附件") { dismiss(); onAddAttachments() })
                                        if (voiceAudioReady && onResynthesize != null && record.id !in isGeneratingVoice) add(MirrorMenuAction("再次合成") { dismiss(); onResynthesize() })
                                        if (onLock != null && !record.isLocked && showEncryptionOptions && FeatureConsole.visible(FeatureConsole.ENCRYPTION_FUNCS)) add(MirrorMenuAction("加密此记录") { dismiss(); onLock() })
                                        if (onUnlock != null && record.isLocked && showEncryptionOptions && FeatureConsole.visible(FeatureConsole.ENCRYPTION_FUNCS)) add(MirrorMenuAction("解密此记录") { dismiss(); onUnlock() })
                                        if (onDelete != null) add(MirrorMenuAction("删除", danger = true) { dismiss(); onDelete() })
                                    } else {
                                    if (hasAdditions) add(MirrorMenuAction("增添") { showSubMenu = true })
                                    // 「设置区域」取代「设为锚点 / 取消锚点」（2026-11-14）：
                                    // 锚点只是区域之一，单独一条会造成"两个地方都能改锚点"。
                                    if (onToggleZoneFor != null || onToggleAnchorFor != null) {
                                        add(MirrorMenuAction("设置区域") { showZoneMenu = true })
                                    } else {
                                        if (onToggleAnchor != null && !record.isAnchored) add(MirrorMenuAction("设为锚点") { dismiss(); onToggleAnchor() })
                                        if (onUnanchor != null && record.isAnchored) add(MirrorMenuAction("取消锚定") { dismiss(); onUnanchor() })
                                    }
                                    // 2026-11-19：自定义区域里，这条记录要能自己走掉（对应锚点的「取消锚定」）。
                                    if (onRemoveFromZone != null) add(MirrorMenuAction("移出区域") { dismiss(); onRemoveFromZone() })
                                    if (!record.isLocked && onAiSummary != null && FeatureConsole.visible(FeatureConsole.GEN_SUMMARY_BTN)) {
                                        val label = when {
                                            record.aiStatus == "STALE" -> "重新分析（已过期）"
                                            (record.analysis?.summary?.isNotBlank() == true || record.dialogueAiContent.isNotBlank()) -> "重新生成总结"
                                            else -> "生成总结"
                                        }
                                        add(MirrorMenuAction(label) { dismiss(); LogUtil.i("AI-0 点击生成总结, recordId=${record.id}"); onAiSummary() })
                                    }
                                    if (!record.isLocked && onVoiceTranslate != null && record.id !in translatingVoiceIds && record.id !in isGeneratingVoice && !voiceAvailable) add(MirrorMenuAction("语音翻译") { dismiss(); onVoiceTranslate() })
                                    if (record.type == RecordType.DIALOGUE && !record.isLocked && onAddNote != null) add(MirrorMenuAction("添加备注") { dismiss(); onAddNote() })
                                    if (onLock != null && !record.isLocked && showEncryptionOptions && FeatureConsole.visible(FeatureConsole.ENCRYPTION_FUNCS)) add(MirrorMenuAction("加密此记录") { dismiss(); onLock() })
                                    if (onUnlock != null && record.isLocked && showEncryptionOptions && FeatureConsole.visible(FeatureConsole.ENCRYPTION_FUNCS)) add(MirrorMenuAction("解密此记录") { dismiss(); onUnlock() })
                                    if (onDelete != null) add(MirrorMenuAction("删除", danger = true) { dismiss(); onDelete() })
                                    }
                                })
                            }
                        }
                    }
                    }
                    if (onOpenLinks != null && FeatureConsole.visible(FeatureConsole.ID_DISPLAY)) {
                        Text(
                            "#${record.id.take(6)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            // 批量选择时不吃点击（和标题行那些图标一致），否则点短 ID 会弹关系弹层而不是选中
                            modifier = Modifier.align(Alignment.BottomStart).then(if (batchMode) Modifier else Modifier.clickable { onOpenLinks() })
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                if (record.aiStatus == "STALE") {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 4.dp)) {
                        Surface(color = Color(0xFFFFA726).copy(alpha = 0.15f), shape = MirrorShapes.row) {
                            Text("需重新分析", modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall, color = Color(0xFFFFA726))
                        }
                    }
                }
                if (record.analysis?.meta?.content_truncated == true) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 4.dp)) {
                        Surface(color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.12f), shape = MirrorShapes.row) {
                            Row(Modifier.padding(horizontal = 6.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(IconWarning, null, Modifier.size(12.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(Modifier.width(3.dp))
                                Text("内容过长", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }

                // ---- 加密锁定内容区（受控组件：状态由 TimelineScreen 管理） ----
                if (record.isLocked) {
                    // 2026-11-16（v5）：**锁定记录永远不在卡片上露正文**。
                    // 旧逻辑有一句 `?: record.content.takeIf { record.encryptedData.isEmpty() }`
                    // —— 那是给"导入的解密导出曾锁定记录"留的兼容口子；v5 之后
                    // 所有锁定记录都是"无密文 + 明文"，这个口子会把正文全泄露出来（实测已发生）。
                    // 现在只认临时解密态：没解密过就只显示锁提示。
                    val tdContent = tempDecryptedRecord?.content
                    if (tdContent != null) {
                        Column {
                            if (tdContent.length > maxChars && !expanded) {
                                // 同普通正文：截断内容不吃点击，只有「展开」可点
                                Text(tdContent, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface, maxLines = 3, overflow = TextOverflow.Clip)
                                Row(Modifier.fillMaxWidth()) { Spacer(Modifier.weight(1f))
                                    Text("展开", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f), modifier = Modifier.clickable { expanded = true })
                                }
                            } else {
                                val tdSegments = if (record.type == RecordType.DIALOGUE) dialogueSegments(tdContent, tempDecryptedRecord?.dialogueAiReply.orEmpty()) else listOf(tdContent)
                                tdSegments.forEachIndexed { index, segment ->
                                    if (index > 0) {
                                        Spacer(Modifier.height(6.dp))
                                        HorizontalDivider(thickness = 1.5.dp, color = MaterialTheme.colorScheme.outline.copy(alpha = 0.65f))
                                        Spacer(Modifier.height(6.dp))
                                    }
                                    Text(segment, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                                }
                                if (tdContent.length > maxChars) {
                                    Row(Modifier.fillMaxWidth()) { Spacer(Modifier.weight(1f))
                                        Text("收起", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f), modifier = Modifier.clickable { expanded = false })
                                    }
                                }
                            }
                            val tdSummary = tempDecryptedRecord?.analysisSummary
                            if (!tdSummary.isNullOrBlank()) {
                                Spacer(Modifier.height(6.dp))
                                Text("摘要：$tdSummary", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    } else {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = if (onTempDecryptClick != null) Modifier.clickable { onTempDecryptClick?.invoke() } else Modifier
                        ) {
                            Icon(IconLock, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.width(6.dp))
                            Text("已锁定 · 点锁键暂时查看", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                } else when (record.type) {
                    RecordType.ESSAY, RecordType.AMENDMENT, RecordType.DIALOGUE, RecordType.AI_NOTE -> {
                        if (shouldTruncate) {
                            // 最多三行：第三行右半渐隐到纸色，不放任何省略号符号（2026-10-23）。
                            var overflowed by remember(record.id, record.content) { mutableStateOf(false) }
                            // 渐隐贴在**最后一行有字的行**的文字末尾（不是物理第三行末尾）：
                            // 正文里常有空行，物理位置对不上。
                            var fadeLeftPx by remember(record.id, record.content) { mutableStateOf(0f) }
                            var fadeTopPx by remember(record.id, record.content) { mutableStateOf(0f) }
                            var fadeHeightPx by remember(record.id, record.content) { mutableStateOf(0f) }
                            // 8 个字宽（正文 16sp → 每字约 16dp）
                            val fadeW = 128.dp
                            Box(Modifier.fillMaxWidth()) {
                                CopyableText(
                                    text = androidx.compose.ui.text.AnnotatedString(fullText),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 3,
                                    overflow = TextOverflow.Clip,
                                    copyOptions = listOf(CopyOption("复制全文", fullText)),
                                    interactive = !batchMode,
                                    onTextLayout = { layout ->
                                        overflowed = layout.hasVisualOverflow
                                        // 从最后一行往回找"最后一个有字的行"：正文里常有空行/段落空行，
                                        // 物理第三行可能是空的，渐隐要贴在真正文字结束的地方。
                                        for (i in (layout.lineCount - 1) downTo 0) {
                                            val start = layout.getLineStart(i).coerceIn(0, fullText.length)
                                            val end = layout.getLineEnd(i).coerceIn(0, fullText.length)
                                            val lineText = fullText.substring(start, end)
                                            if (lineText.isNotBlank()) {
                                                fadeLeftPx = layout.getLineRight(i)
                                                fadeTopPx = layout.getLineTop(i)
                                                fadeHeightPx = layout.getLineBottom(i) - layout.getLineTop(i)
                                                break
                                            }
                                        }
                                    }
                                )
                                if (overflowed && fadeHeightPx > 0f) {
                                    val density = androidx.compose.ui.platform.LocalDensity.current
                                    val wDp = with(density) { fadeW.roundToPx() }
                                    Box(
                                        Modifier
                                            .offset(
                                                x = with(density) { (fadeLeftPx - wDp).toDp() },
                                                y = with(density) { fadeTopPx.toDp() }
                                            )
                                            .width(fadeW)
                                            .height(with(density) { fadeHeightPx.toDp() })
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
                            if (overflowed) {
                                Row(Modifier.fillMaxWidth()) { Spacer(Modifier.weight(1f))
                                    Text("展开", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f), modifier = Modifier.clickable { expanded = true })
                                }
                            }
                        } else {
                            val segments = if (record.type == RecordType.DIALOGUE) dialogueSegments(fullText, record.dialogueAiReply) else listOf(fullText)
                            segments.forEachIndexed { index, segment ->
                                if (index > 0) {
                                    Spacer(Modifier.height(6.dp))
                                    HorizontalDivider(thickness = 1.5.dp, color = MaterialTheme.colorScheme.outline.copy(alpha = 0.65f))
                                    Spacer(Modifier.height(6.dp))
                                }
                                CopyableText(
                                    text = segment,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    copyOptions = if (record.type == RecordType.DIALOGUE) listOf(
                                        CopyOption("复制本段", segment),
                                        CopyOption("复制全文", fullText)
                                    ) else null
                                )
                            }
                            if (fullText.length > maxChars) {
                                Row(Modifier.fillMaxWidth()) { Spacer(Modifier.weight(1f))
                                    Text("收起", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f), modifier = Modifier.clickable { expanded = false })
                                }
                            }
                        }
                    }
                }

                if (record.id in isGenerating) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = Color(0xFFA78BFA))
                        Spacer(Modifier.width(8.dp))
                        Text("AI 总结生成中...", style = MaterialTheme.typography.labelSmall, color = Color(0xFF9A8DA6))
                    }
                }
                if (record.id in isGeneratingVoice) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = Color(0xFF4DD0E1))
                        Spacer(Modifier.width(8.dp))
                        Text("语音生成中...", style = MaterialTheme.typography.labelSmall, color = Color(0xFF4DD0E1))
                    }
                }
                if (record.id in translatingVoiceIds) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = Color(0xFF4DD0E1))
                        Spacer(Modifier.width(8.dp))
                        Text("文本翻译中...", style = MaterialTheme.typography.labelSmall, color = Color(0xFF4DD0E1))
                    }
                }
                // 增修：折叠行前加一根小竖线（和展开后的引用线同源），展开后每条增修按"记录"的样子呈现，
                // 条与条之间只隔一条发丝线（2026-10-23）。
                if (amendments.isNotEmpty()) {
                    var amendOpen by rememberSaveable(record.id) { mutableStateOf(false) }
                    Spacer(Modifier.height(8.dp))
                    Row(
                        Modifier.fillMaxWidth().clickable { amendOpen = !amendOpen }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            Modifier.width(2.dp).height(12.dp)
                                .clip(MirrorShapes.pill)
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.5f))
                        )
                        Spacer(Modifier.width(7.dp))
                        Text(
                            "增修 ${amendments.size} 条",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.weight(1f))
                        Text(
                            if (amendOpen) "收起" else "展开",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f)
                        )
                    }
                    if (amendOpen) {
                        amendments.forEachIndexed { aIndex, amendment ->
                            var aExpanded by rememberSaveable(amendment.id) { mutableStateOf(false) }
                            var aOverflow by remember(amendment.id) { mutableStateOf(false) }
                            var aFadeLeft by remember(amendment.id) { mutableStateOf(0f) }
                            var aFadeTop by remember(amendment.id) { mutableStateOf(0f) }
                            var aFadeH by remember(amendment.id) { mutableStateOf(0f) }
                            val aFull = if (amendment.isLocked) {
                                tempDecryptedAmendments?.get(amendment.id) ?: "（增修已加密）"
                            } else amendment.content.ifBlank { amendment.notes }.ifBlank { "（空内容）" }
                            if (aIndex > 0) {
                                Spacer(Modifier.height(8.dp))
                                Box(
                                    Modifier.fillMaxWidth().height(1.dp)
                                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f))
                                )
                                Spacer(Modifier.height(8.dp))
                            } else {
                                Spacer(Modifier.height(6.dp))
                            }
                            // 增修的"头"：和记录一样是 类型 + 时间
                            Row(Modifier.padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "增修",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = recordTypeColor(RecordType.AMENDMENT)
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    formatTimestamp(amendment.createdAt),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Spacer(Modifier.height(4.dp))
                            // 增修的"身体"：与记录正文同一套（三行 + 末尾渐隐 + 展开）
                            Box(Modifier.fillMaxWidth().padding(start = 16.dp)) {
                                CopyableText(
                                    text = androidx.compose.ui.text.AnnotatedString(aFull),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.70f),
                                    maxLines = if (aExpanded) Int.MAX_VALUE else 2,
                                    overflow = TextOverflow.Clip,
                                    copyOptions = listOf(CopyOption("复制全文", aFull)),
                                    interactive = !batchMode,
                                    onTextLayout = { layout ->
                                        aOverflow = layout.hasVisualOverflow
                                        if (!aExpanded) {
                                            for (i in (layout.lineCount - 1) downTo 0) {
                                                val st = layout.getLineStart(i).coerceIn(0, aFull.length)
                                                val en = layout.getLineEnd(i).coerceIn(0, aFull.length)
                                                if (aFull.substring(st, en).isNotBlank()) {
                                                    aFadeLeft = layout.getLineRight(i)
                                                    aFadeTop = layout.getLineTop(i)
                                                    aFadeH = layout.getLineBottom(i) - layout.getLineTop(i)
                                                    break
                                                }
                                            }
                                        }
                                    }
                                )
                                if (aOverflow && !aExpanded && aFadeH > 0f) {
                                    val d = androidx.compose.ui.platform.LocalDensity.current
                                    val wDp = with(d) { 128.dp.roundToPx() }
                                    Box(
                                        Modifier
                                            .offset(
                                                x = with(d) { (aFadeLeft - wDp).toDp() },
                                                y = with(d) { aFadeTop.toDp() }
                                            )
                                            .width(128.dp)
                                            .height(with(d) { aFadeH.toDp() })
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
                            if (aExpanded || aOverflow) {
                                Row(Modifier.fillMaxWidth().padding(start = 16.dp)) { Spacer(Modifier.weight(1f))
                                    Text(
                                        if (aExpanded) "收起" else "展开",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
                                        modifier = Modifier.clickable { aExpanded = !aExpanded }
                                    )
                                }
                            }
                        }
                    }
                }
                // 情绪只放一条：权重最高的那条；权重相同按原顺序取第一条。
                // 位置按用户要求钉在标签/协议**左侧**，中间一根 1dp 竖线隔开。
                val topEmotion = remember(record.emotionalTags, record.analysis) {
                    val entries = memore.app.data.model.EmotionStats.emotionEntries(record)
                    var best: memore.app.data.model.EmotionTagEntry? = null
                    entries.forEach { e -> if (best == null || e.weight > best!!.weight) best = e }
                    best?.name
                }
                if (dt.isNotEmpty() || dp.isNotEmpty() || topEmotion != null) {
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        topEmotion?.let { emo ->
                            Text(
                                emo,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.width(7.dp))
                            Box(
                                Modifier.width(1.dp).height(10.dp)
                                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f))
                            )
                            Spacer(Modifier.width(7.dp))
                        }
                        dt.forEach { Text("#${it.name}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(end = 6.dp)) }
                        dp.forEach { Text("·${it.name}", style = MaterialTheme.typography.labelSmall, color = Color(0xFF4DD0E1), modifier = Modifier.padding(end = 6.dp)) }
                    }
                }
            }
            }
            RecordDivider(isLast = isLast, weekBreak = weekBreak)

        // Selection indicator for batch mode
        if (batchMode) {
            Box(
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 12.dp, bottom = 12.dp).size(20.dp)
                    .clip(CircleShape)
                    .background(if (isBatchSelected) MaterialTheme.colorScheme.primary else Color.Transparent)
                    .border(2.dp, if (isBatchSelected) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.5f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                if (isBatchSelected) {
                    Icon(Icons.Filled.Check, null, Modifier.size(16.dp), tint = Color.White)
                }
            }
        }
    }
}

@Composable
private fun TypeBadge(type: RecordType) {
    val (icon, label) = when (type) { RecordType.ESSAY -> IconEssay to "随笔"; RecordType.DIALOGUE -> IconDialogue to "对话录"; RecordType.AMENDMENT -> IconEssay to "✏️ 增修"; RecordType.AI_NOTE -> IconEssay to "AI 札记" }
    Row(verticalAlignment = Alignment.CenterVertically) { Icon(icon, null, Modifier.size(12.dp), tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)); Spacer(Modifier.width(3.dp)); Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)) }
}

/**
 * 记录之间的分隔线：**双线**（1dp + 3dp 间隔 + 1dp），首尾用单线。
 * 双线的做法来自横格纸/账本：两条细线夹一条空隙，比一条实线更轻、更像纸。
 */
@Composable
private fun RecordDivider(isLast: Boolean, weekBreak: Boolean) {
    val line = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f)
    val hair = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)
    when {
        // 跨周：双线（账本里"换周"的记号，一屏最多出现一次）
        !isLast && weekBreak -> Column(Modifier.fillMaxWidth()) {
            Box(Modifier.fillMaxWidth().height(1.dp).background(line))
            Spacer(Modifier.height(3.dp))
            Box(Modifier.fillMaxWidth().height(1.dp).background(hair))
        }
        else -> Box(Modifier.fillMaxWidth().height(1.dp).background(line))
    }
}
