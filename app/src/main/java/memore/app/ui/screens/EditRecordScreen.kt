package memore.app.ui.screens

import memore.app.ui.theme.MemoreType
import memore.app.ui.components.MemoreLineInput
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.BasicTextField
import android.content.Context
import memore.app.ui.components.MirrorAlertDialog
import memore.app.ui.components.MirrorDecisionDialog
import memore.app.ui.components.MirrorSheet
import memore.app.ui.components.MirrorShapes
import memore.app.ui.components.mirrorHairline
import memore.app.ui.components.mirrorHairlineColor
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import memore.app.utils.memoreToast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import memore.app.data.model.Record
import memore.app.data.model.RecordType
import memore.app.data.repository.ProtocolRepository
import memore.app.data.repository.RecordRepository
import memore.app.data.repository.TagRepository
import memore.app.data.repository.TagGroupRepository
import memore.app.ui.components.IconBack
import memore.app.ui.components.MirrorAnchoredMenu
import memore.app.ui.components.MirrorMenuAction
import memore.app.ui.components.MemorePage
import memore.app.ui.components.MirrorCheckRow
import memore.app.ui.components.MirrorCheckSheet
import memore.app.ui.components.MirrorStackDialog
import memore.app.ui.components.MirrorSheet
import androidx.compose.material.icons.filled.MoreVert
import memore.app.ui.components.ProtocolSelectorDialog
import memore.app.ui.components.TagSelectorDialog
import memore.app.ui.components.dialogueRecordContent
import memore.app.ui.components.dialogueSegments
import memore.app.ui.components.OriginSelectorSheet
import memore.app.utils.HashUtils
import memore.app.utils.UiSoundPlayer
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

data class PendingAttachment(
    val uri: Uri,
    val fileName: String,
    val type: String,
    val filePath: String,
    val fileSize: Long
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditRecordScreen(initialType: RecordType?, recordRepository: RecordRepository, tagRepository: TagRepository, tagGroupRepository: TagGroupRepository, protocolRepository: ProtocolRepository, existingRecordId: String? = null,
    /** 「转录」带进来的正文（随笔 → 第一个框；对话录 → 见 [prefillReply]）。 */
    prefillContent: String? = null,
    /** 「转录」带进来的 AI 那一段（对话录**默认落在第二个框**）。 */
    prefillReply: String? = null,
    /** 转录来源：存进记录，让时间线上那条记录能回到这本草稿。 */
    sourceDraftId: Long? = null,
    sourceBlockId: Long? = null,
    /** 来源（2026-11-11）：新建/编辑时可选；为空则走「原创」。 */
    originRepository: memore.app.data.repository.OriginRepository? = null,
    /** 2026-11-17：区域（只列手动型自定义区域，可多选）+ 新建预设。 */
    zoneRepository: memore.app.data.repository.ZoneRepository? = null,
    presetRepository: memore.app.data.repository.RecordPresetRepository? = null,
    onBack: () -> Unit) {
    val context = LocalContext.current; val prefs = context.getSharedPreferences("memore_prefs", Context.MODE_PRIVATE); val scope = rememberCoroutineScope()
    val isEditing = existingRecordId != null
    val recordId = existingRecordId ?: remember { UUID.randomUUID().toString() }
    var content by remember { mutableStateOf("") }; var aiContent by remember { mutableStateOf("") }; var notes by remember { mutableStateOf("") }
    var attachedTagIds by remember { mutableStateOf(emptySet<Int>()) }; var allTagsMap by remember { mutableStateOf(mapOf<Int, String>()) }
    var attachedProtocolIds by remember { mutableStateOf(emptySet<Int>()) }; var allProtocolsMap by remember { mutableStateOf(mapOf<Int, String>()) }
    var showDiscardDialog by remember { mutableStateOf(false) }; var isSaving by remember { mutableStateOf(false) }
    var isLockedEdit by remember { mutableStateOf(false) }
    var originId by remember { mutableStateOf<String?>(null) }
    /**
     * 用户是否**主动**选过来源。
     *
     * 没有它就分不清"没动过"和"选了不标"——两者都是 `originId == null`，
     * 于是「不标」在保存时会被默认值（原创 / 旧值）顶掉。2026-11-12 修。
     */
    var originTouched by remember { mutableStateOf(false) }
    var originList by remember { mutableStateOf<List<memore.app.data.model.Origin>>(emptyList()) }
    var showOriginPicker by remember { mutableStateOf(false) }
    var showTagSelector by remember { mutableStateOf(false) }; var showProtocolSelector by remember { mutableStateOf(false) }; var showAttachmentSheet by remember { mutableStateOf(false) }
    // 2026-11-17：区域（手动型）多选 + 新建预设
    var manualZones by remember { mutableStateOf<List<memore.app.data.model.Zone>>(emptyList()) }
    var selectedZoneIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var showZonePicker by remember { mutableStateOf(false) }
    var presets by remember { mutableStateOf<List<memore.app.data.model.RecordPreset>>(emptyList()) }
    var showPresetSheet by remember { mutableStateOf(false) }
    var showSavePresetDialog by remember { mutableStateOf(false) }
    var presetMenuFor by remember { mutableStateOf<memore.app.data.model.RecordPreset?>(null) }
    var renamePresetTarget by remember { mutableStateOf<memore.app.data.model.RecordPreset?>(null) }
    val type = if (isEditing) null else initialType; var resolvedType by remember { mutableStateOf(type) }
    val pendingAttachments = remember { mutableStateListOf<PendingAttachment>() }
    val maxAttachments = 5
    val attDir = File(context.filesDir, "attachments/$recordId")

    LaunchedEffect(Unit) {
        originRepository?.ensureBuiltins()
        originList = originRepository?.all().orEmpty()
    }

    LaunchedEffect(existingRecordId) { if (existingRecordId != null) {
        val r = recordRepository.getRecordById(existingRecordId)
        if (r != null) {
            isLockedEdit = r.isLocked
            resolvedType = r.type
            notes = r.notes
            attachedTagIds = tagRepository.getTagsForRecord(existingRecordId).map { it.id }.toSet()
            attachedProtocolIds = protocolRepository.getProtocolsForRecord(existingRecordId).map { it.id }.toSet()
            originId = r.originId
            if (r.type == RecordType.DIALOGUE) {
                if (r.dialogueAiReply.isNotBlank()) {
                    // 严格两段：正文里可能**只有** AI 那一段（转录到第二个框存出来的），
                    // 那时第一个框必须是空的 —— 否则读回来两栏会重复显示同一句话。
                    val segs = dialogueSegments(r.content, r.dialogueAiReply)
                    if (segs.size == 1) { content = ""; aiContent = segs[0] } else { content = segs[0]; aiContent = segs[1] }
                } else {
                    val legacy = dialogueSegments(r.content)
                    if (legacy.size == 2) { content = legacy[0]; aiContent = legacy[1] } else { content = r.content; aiContent = "" }
                }
            } else {
                content = r.content
                aiContent = r.dialogueAiContent
            }
        }
        recordRepository.getAttachments(existingRecordId).forEach { a -> pendingAttachments.add(PendingAttachment(Uri.fromFile(File(a.filePath)), a.fileName, a.type, a.filePath, a.fileSize)) }
    } }

    // 2026-11-17：区域候选（只列手动型自定义区域；锚点/札记/条件型不在这里）
    LaunchedEffect(Unit) {
        manualZones = runCatching {
            zoneRepository?.zones().orEmpty().filter { z ->
                !z.builtin && runCatching {
                    val c = memore.app.data.repository.ZoneCondition.of(z.condition)
                    c.manual || c.isEmpty()
                }.getOrDefault(true)
            }
        }.getOrDefault(emptyList())
    }
    // 编辑已有记录：回填它当前所在的区域
    LaunchedEffect(existingRecordId) {
        if (existingRecordId != null) {
            selectedZoneIds = runCatching { zoneRepository?.zoneIdsOf(existingRecordId) }.getOrNull().orEmpty()
        }
    }
    // 预设只在新建时出现
    LaunchedEffect(showPresetSheet, isEditing) {
        if (!isEditing) presets = runCatching { presetRepository?.all() }.getOrNull().orEmpty()
    }
    LaunchedEffect(Unit) { if (!isEditing && prefillContent == null && prefillReply == null) { val st = prefs.getString("draft_type", null); if (st == type?.name) content = prefs.getString("draft_content", "") ?: "" } }
    LaunchedEffect(Unit) { tagRepository.getAllTags().collect { allTagsMap = it.associate { it.id to it.name } } }
    LaunchedEffect(Unit) { protocolRepository.getAllProtocols().collect { allProtocolsMap = it.associate { it.id to it.name } } }
    LaunchedEffect(Unit) { UiSoundPlayer.preloadSave(context) }
    LaunchedEffect(Unit) { while (true) { delay(10_000); if (content.isNotBlank()) prefs.edit().putString("draft_type", type?.name).putString("draft_content", content).apply() } }

    fun clearDraft() { prefs.edit().remove("draft_type").remove("draft_content").apply() }

    // 「转录」带进来的内容：**压过**上次残留的编辑器草稿（残留的是别的写作，不能盖掉转录内容）
    LaunchedEffect(Unit) {
        if (isEditing) return@LaunchedEffect
        if (prefillContent == null && prefillReply == null) return@LaunchedEffect
        prefillContent?.let { content = it }
        prefillReply?.let { aiContent = it }
        clearDraft()
    }

    fun copyAttachment(uri: Uri, mimeType: String, fallbackName: String): PendingAttachment? {
        val fileName = try {
            val cursor = context.contentResolver.query(uri, null, null, null, null)
            cursor?.use { if (it.moveToFirst()) { val idx = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME); if (idx >= 0) it.getString(idx) else null } else null }
        } catch (_: Exception) { null } ?: fallbackName
        val fileType = if (mimeType.startsWith("image")) "IMAGE" else "AUDIO"
        val maxSize = if (fileType == "IMAGE") 20L * 1024 * 1024 else 50L * 1024 * 1024
        return try {
            val size = context.contentResolver.openInputStream(uri)?.use { it.available().toLong() } ?: return null
            if (size > maxSize) { context.memoreToast("文件过大", Toast.LENGTH_SHORT); return null }
            attDir.mkdirs()
            val hash = context.contentResolver.openInputStream(uri)?.use { HashUtils.sha256(it) } ?: UUID.randomUUID().toString()
            val ext = fileName.substringAfterLast('.', "bin")
            val destName = "${hash.take(12)}.$ext"
            val destFile = File(attDir, destName)
            if (!destFile.exists()) {
                context.contentResolver.openInputStream(uri)?.use { input -> destFile.outputStream().use { output -> input.copyTo(output) } } ?: return null
            }
            PendingAttachment(uri, fileName, fileType, destFile.absolutePath, size)
        } catch (_: Exception) { null }
    }

    val imageLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { copyAttachment(it, "image/*", "image.jpg")?.let { a -> if (pendingAttachments.size < maxAttachments) { pendingAttachments.add(a); UiSoundPlayer.playAttachComplete(context) } else context.memoreToast("最多添加 $maxAttachments 个附件", Toast.LENGTH_SHORT) } }
    }
    val audioLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { copyAttachment(it, "audio/*", "audio.m4a")?.let { a -> if (pendingAttachments.size < maxAttachments) { pendingAttachments.add(a); UiSoundPlayer.playAttachComplete(context) } else context.memoreToast("最多添加 $maxAttachments 个附件", Toast.LENGTH_SHORT) } }
    }
    fun handleSave() { if (isLockedEdit) { context.memoreToast("记录已加密，无法编辑", Toast.LENGTH_SHORT); return }; val t = content.trim(); val ai = aiContent.trim(); val st = if (isEditing) resolvedType else type
        // 两栏都空才拦；**只填了第二个框也算数**（转录进来的对话录就是这种形状）
        if ((t.isBlank() && ai.isBlank()) || st == null || isSaving) return
        isSaving = true; scope.launch {
        var saveSucceeded = false
        if (isEditing && existingRecordId != null) {
            val e = recordRepository.getRecordById(existingRecordId)
            if (e != null) {
                recordRepository.saveVersion(existingRecordId, e.content)
                recordRepository.updateRecord(e.copy(content = if (st == RecordType.DIALOGUE) dialogueRecordContent(t, ai) else t, dialogueAiReply = if (st == RecordType.DIALOGUE) ai else "", dialogueAiContent = if (st == RecordType.DIALOGUE) e.dialogueAiContent else "", notes = notes.trim(), updatedAt = System.currentTimeMillis(), originId = if (originTouched) originId else e.originId))
                    .onSuccess {
                        saveSucceeded = true
                        // 标签 / 协议：按**差集**写（2026-11-12 修）。
                        // 原来编辑分支完全不写这两个——勾选和取消都不生效；
                        // 写法与 TimelineScreen:1263 一致：新增补、删掉的减。
                        val oldTags = tagRepository.getTagsForRecord(existingRecordId).map { it.id }.toSet()
                        (attachedTagIds - oldTags).forEach { tagRepository.addTagToRecord(existingRecordId, it) }
                        (oldTags - attachedTagIds).forEach { tagRepository.removeTagFromRecord(existingRecordId, it) }
                        val oldProtocols = protocolRepository.getProtocolsForRecord(existingRecordId).map { it.id }.toSet()
                        (attachedProtocolIds - oldProtocols).forEach { protocolRepository.addProtocolToRecord(existingRecordId, it) }
                        (oldProtocols - attachedProtocolIds).forEach { protocolRepository.removeProtocolFromRecord(existingRecordId, it) }
                        // 2026-11-17：区域也按差集写（与标签/协议同一套写法）。
                        // 写区域放在"记录已写成功"之后：记录失败就什么都不动。
                        val oldZones = runCatching { zoneRepository?.zoneIdsOf(existingRecordId) }.getOrNull().orEmpty()
                        (selectedZoneIds - oldZones).forEach { zid -> runCatching { zoneRepository?.addToZone(zid, existingRecordId) } }
                        (oldZones - selectedZoneIds).forEach { zid -> runCatching { zoneRepository?.removeFromZone(zid, existingRecordId) } }
                        tagRepository.refreshAllCounts(); protocolRepository.refreshAllCounts()
                    }
                    .onFailure { UiSoundPlayer.playError(context) }
            }
        } else {
            val r = Record(id = recordId, type = st, content = if (st == RecordType.DIALOGUE) dialogueRecordContent(t, ai) else t, dialogueAiReply = if (st == RecordType.DIALOGUE) ai else "", dialogueAiContent = "", notes = notes.trim(), createdAt = System.currentTimeMillis(), updatedAt = System.currentTimeMillis(),
                // 转录来源：时间线上那条记录靠它回到草稿
                sourceDraftId = sourceDraftId, sourceBlockId = sourceBlockId)
                    // 来源：没主动选才落「原创」（2026-11-11）；主动选了「不标」就存 null（2026-11-12 修）
                    .copy(originId = if (originTouched) originId else memore.app.data.model.Origin.ID_ORIGINAL)
            recordRepository.addRecord(r).onSuccess {
                saveSucceeded = true
                attachedTagIds.forEach { tid -> tagRepository.addTagToRecord(r.id, tid) }
                attachedProtocolIds.forEach { pid -> protocolRepository.addProtocolToRecord(r.id, pid) }
                // 区域只能在记录落库之后写（新建时记录还没有 id）
                selectedZoneIds.forEach { zid -> runCatching { zoneRepository?.addToZone(zid, r.id) } }
                clearDraft()
            }.onFailure { UiSoundPlayer.playError(context) }
        }
        pendingAttachments.forEach { a ->
            if (!isEditing && a.filePath.startsWith(context.filesDir.absolutePath)) {
                recordRepository.saveAttachment(memore.app.data.model.Attachment(recordId = recordId, fileName = a.fileName, filePath = a.filePath, type = a.type, fileSize = a.fileSize))
            }
        }
        if (saveSucceeded) UiSoundPlayer.playSave(context)
        isSaving = false; onBack()
    } }
    fun handleBack() { if (content.isBlank() && aiContent.isBlank()) { onBack() } else showDiscardDialog = true }
    BackHandler(enabled = content.isNotBlank() || aiContent.isNotBlank()) { showDiscardDialog = true }

    if (showDiscardDialog) MirrorDecisionDialog(
        onDismissRequest = { showDiscardDialog = false },
        title = "放弃编辑？",
        body = "当前内容尚未保存",
        primaryLabel = "放弃",
        danger = true,
        onPrimary = { showDiscardDialog = false; onBack() }
    )
    if (showOriginPicker) {
        OriginSelectorSheet(
            origins = originList,
            currentId = originId,
            onPick = { picked -> originId = picked; originTouched = true; showOriginPicker = false },
            onDismiss = { showOriginPicker = false }
        )
    }

    // 2026-11-17：标签/协议改为"点行即生效"，没有确认键；关闭靠下滑/返回
    if (showTagSelector) TagSelectorDialog(tagRepository = tagRepository, tagGroupRepository = tagGroupRepository, currentTagIds = attachedTagIds, onDismiss = { showTagSelector = false }, onSelectionChange = { attachedTagIds = it })
    if (showProtocolSelector) ProtocolSelectorDialog(protocolRepository = protocolRepository, currentProtocolIds = attachedProtocolIds, onDismiss = { showProtocolSelector = false }, onSelectionChange = { attachedProtocolIds = it })

    if (showAttachmentSheet) {
        val sheetState = rememberModalBottomSheetState()
        MirrorSheet(onDismissRequest = { showAttachmentSheet = false }, containerColor = MaterialTheme.colorScheme.surfaceContainerLow, sheetState = sheetState) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
                Text(
                    "添加附件",
                    fontSize = 10.5.sp,
                    letterSpacing = 1.1.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 2.dp)
                )
                listOf(
                    Pair(Icons.Filled.Image, "从相册选择") to { showAttachmentSheet = false; imageLauncher.launch("image/*") },
                    Pair(Icons.Filled.Folder, "选择音频文件") to { showAttachmentSheet = false; audioLauncher.launch("audio/*") }
                ).forEach { (iconAndText, action) ->
                    Row(
                        Modifier.fillMaxWidth().height(54.dp).clickable(onClick = action),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(iconAndText.first, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(12.dp))
                        Text(iconAndText.second, fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurface)
                    }
                    Box(
                        Modifier.fillMaxWidth().height(1.dp)
                            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f))
                    )
                }
            }
        }
    }

    // 法 铁律 2：屏框走 MemorePage（顶栏 64dp、返回、右端动作都由它负责）。
    // 之前这里是手写 Scaffold + TopAppBar —— M3 的默认值，不是项目法条的默认值。
    MemorePage(
        title = if (isEditing) "编辑记录" else "新记录",
        onBack = { handleBack() },
        actions = {
            // 2026-11-17：预设只在"新建"时有意义（存的是新建要填的那五样）
            // 顶栏右端一个动作 → 按 1.4 用**图标**，不再写文字
            if (!isEditing) {
                IconButton(onClick = { showPresetSheet = true }) {
                    Icon(
                        memore.app.ui.components.IconPreset,
                        "预设",
                        Modifier.size(22.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        bottomBar = {
            // 保存沉到底部：内容长 / 键盘弹起时都够得着，不用滚到底
            Surface(color = MaterialTheme.colorScheme.background) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 8.dp, bottom = 12.dp)) {
                    Button(
                        onClick = { handleSave() },
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        shape = MirrorShapes.card,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                    ) { Text("保存", color = Color.White) }
                }
            }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp).verticalScroll(rememberScrollState())) {
            if (isLockedEdit) {
                Surface(color = MaterialTheme.colorScheme.error.copy(alpha = 0.12f), shape = MirrorShapes.row, border = mirrorHairline(), modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                    Text("记录已加密，无法编辑。请先解密后再修改。", modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                }
            }
            Spacer(Modifier.height(6.dp))
            // 标题与正文之间一条细线：给正文一点"被隔离"的边界
            Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)))
            Spacer(Modifier.height(12.dp))
            // ── 正文：不套框，直接铺在纸上（E1）──
            val isDialogueType = resolvedType == RecordType.DIALOGUE
            if (isDialogueType) {
                Text(
                    "我",
                    fontSize = 10.sp,
                    letterSpacing = 1.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp, bottom = 2.dp)
                )
                Row(Modifier.fillMaxWidth()) {
                    Box(Modifier.width(2.dp).heightIn(min = 40.dp).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.22f)))
                    Spacer(Modifier.width(12.dp))
                BasicTextField(
                    value = content,
                    onValueChange = { content = it },
                    textStyle = androidx.compose.ui.text.TextStyle(
                        fontSize = 15.sp,
                        lineHeight = 27.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    ),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
                    modifier = Modifier.weight(1f).heightIn(min = 110.dp),
                    decorationBox = { inner ->
                        Box {
                            if (content.isEmpty()) {
                                Text("写点什么", fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f))
                            }
                            inner()
                        }
                    }
                )
                }
                Spacer(Modifier.height(10.dp))
                Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)))
                Spacer(Modifier.height(10.dp))
                Text(
                    "回应",
                    fontSize = 10.sp,
                    letterSpacing = 1.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 2.dp)
                )
                Row(Modifier.fillMaxWidth()) {
                    Box(Modifier.width(2.dp).heightIn(min = 40.dp).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.22f)))
                    Spacer(Modifier.width(12.dp))
                    BasicTextField(
                        value = aiContent,
                        onValueChange = { aiContent = it },
                        textStyle = androidx.compose.ui.text.TextStyle(
                            fontSize = 15.sp,
                            lineHeight = 27.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        ),
                        cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
                        modifier = Modifier.weight(1f).heightIn(min = 120.dp),
                        decorationBox = { inner ->
                            Box {
                                if (aiContent.isEmpty()) {
                                }
                                inner()
                            }
                        }
                    )
                }
            } else {
                BasicTextField(
                    value = content,
                    onValueChange = { content = it },
                    textStyle = androidx.compose.ui.text.TextStyle(
                        fontSize = 15.sp,
                        lineHeight = 27.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    ),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 200.dp),
                    decorationBox = { inner ->
                        Box {
                            if (content.isEmpty()) {
                                Text("写点什么", fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f))
                            }
                            inner()
                        }
                    }
                )
            }

            Spacer(Modifier.height(12.dp))
            // ── 工具行：附件 / 标签 / 协议（都是文字动作，不再是药丸）──
            Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)))
            // 三个工具等分，中间 1dp 竖线隔开
            Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.weight(1f).fillMaxHeight().clickable(enabled = pendingAttachments.size < maxAttachments) { showAttachmentSheet = true },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "＋ 附件",
                        fontSize = 12.5.sp,
                        color = if (pendingAttachments.size < maxAttachments) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                    )
                }
                Box(Modifier.width(1.dp).height(14.dp).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)))
                Box(
                    Modifier.weight(1f).fillMaxHeight().clickable { showTagSelector = true },
                    contentAlignment = Alignment.Center
                ) { Text("＋ 标签", fontSize = 12.5.sp, color = MaterialTheme.colorScheme.primary) }
                Box(Modifier.width(1.dp).height(14.dp).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)))
                Box(
                    Modifier.weight(1f).fillMaxHeight().clickable { showProtocolSelector = true },
                    contentAlignment = Alignment.Center
                ) { Text("＋ 协议", fontSize = 12.5.sp, color = MaterialTheme.colorScheme.primary) }
        Box(Modifier.width(1.dp).height(14.dp).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f)))
        Box(
            Modifier.weight(1f).fillMaxHeight().clickable { showOriginPicker = true },
            contentAlignment = Alignment.Center
        ) {
            Text(
                originList.firstOrNull { it.id == originId }?.name ?: "＋ 来源",
                fontSize = 12.5.sp,
                color = MaterialTheme.colorScheme.primary
            )
        }
        // 2026-11-17：区域（手动型，可多选；保存后才写库）
        Box(Modifier.width(1.dp).height(14.dp).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f)))
        Box(
            Modifier.weight(1f).fillMaxHeight().clickable(enabled = manualZones.isNotEmpty()) { showZonePicker = true },
            contentAlignment = Alignment.Center
        ) {
            Text(
                when {
                    manualZones.isEmpty() -> "＋ 区域"
                    selectedZoneIds.isEmpty() -> "＋ 区域"
                    selectedZoneIds.size == 1 -> manualZones.firstOrNull { it.id in selectedZoneIds }?.name ?: "区域 1"
                    else -> "区域 ${selectedZoneIds.size}"
                },
                fontSize = 12.5.sp,
                color = if (manualZones.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                else MaterialTheme.colorScheme.primary
            )
        }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)))
            if (pendingAttachments.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(pendingAttachments.toList(), key = { it.filePath }) { att ->
                        Box(modifier = Modifier.size(80.dp)) {
                            if (att.type == "IMAGE") {
                                Box(Modifier.fillMaxSize().clip(MirrorShapes.row).background(MaterialTheme.colorScheme.surfaceContainerLow), contentAlignment = Alignment.Center) {
                                    Icon(Icons.Outlined.Image, "图片", Modifier.size(28.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            } else {
                                // 音频：一块"音轨"样子（竖条 + 播放三角），不再塞被截断的文件名
                                Box(
                                    Modifier.fillMaxSize().clip(MirrorShapes.row)
                                        .background(MaterialTheme.colorScheme.surfaceContainerLow),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        repeat(5) { index ->
                                            Box(
                                                Modifier.padding(horizontal = 1.5.dp)
                                                    .width(2.dp)
                                                    .height((10 + index % 3 * 7).dp)
                                                    .clip(MirrorShapes.pill)
                                                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.45f))
                                            )
                                        }
                                        Spacer(Modifier.width(6.dp))
                                        Icon(Icons.Filled.PlayArrow, "音频", Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f))
                                    }
                                }
                            }
                            Box(modifier = Modifier.align(Alignment.TopEnd).size(20.dp).clip(CircleShape).background(MaterialTheme.colorScheme.error.copy(alpha = 0.9f)), contentAlignment = Alignment.Center) {
                                IconButton(onClick = { pendingAttachments.remove(att) }, modifier = Modifier.size(20.dp)) { Icon(Icons.Filled.Close, "删除", Modifier.size(12.dp), tint = Color.White) }
                            }
                        }
                    }
                }
            }
            // 2026-11-17：三类选择结果合成**一行**、同一字体同一颜色（原来三行三种颜色）
            val selectedSummary = buildList {
                if (attachedTagIds.isNotEmpty()) add(attachedTagIds.mapNotNull { allTagsMap[it] }.joinToString(" ") { "#$it" })
                if (attachedProtocolIds.isNotEmpty()) add(attachedProtocolIds.mapNotNull { allProtocolsMap[it] }.joinToString("  "))
                if (selectedZoneIds.isNotEmpty()) add(selectedZoneIds.mapNotNull { id -> manualZones.firstOrNull { it.id == id }?.name }.joinToString("  "))
            }
            if (selectedSummary.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    selectedSummary.joinToString("   "),
                    style = MemoreType.meta,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    // ---- 区域多选（手动型，和标签/协议/来源同一套对号行）----
    if (showZonePicker) {
        MirrorCheckSheet(title = "区域", onDismiss = { showZonePicker = false }) {
            manualZones.forEach { zone ->
                item(key = zone.id) {
                    MirrorCheckRow(
                        label = zone.name,
                        selected = zone.id in selectedZoneIds,
                        onClick = {
                            selectedZoneIds = if (zone.id in selectedZoneIds) selectedZoneIds - zone.id
                            else selectedZoneIds + zone.id
                        }
                    )
                }
            }
        }
    }

    // ---- 预设（新建时套用/管理）----
    if (showPresetSheet) {
        MirrorSheet(onDismissRequest = { showPresetSheet = false }, containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
                Text("预设", style = MemoreType.micro, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 6.dp))
                presets.forEach { p ->
                    Row(Modifier.fillMaxWidth().height(52.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(
                            Modifier.weight(1f).fillMaxHeight().clickable {
                                // 套用：类型 / 来源 / 标签 / 协议 / 区域
                                runCatching { memore.app.data.model.RecordType.valueOf(p.type) }.getOrNull()?.let { resolvedType = it }
                                originId = p.originId
                                originTouched = true
                                attachedTagIds = runCatching {
                                    val a = org.json.JSONArray(p.tagIds); (0 until a.length()).map { a.optInt(it) }.toSet()
                                }.getOrDefault(emptySet())
                                attachedProtocolIds = runCatching {
                                    val a = org.json.JSONArray(p.protocolIds); (0 until a.length()).map { a.optInt(it) }.toSet()
                                }.getOrDefault(emptySet())
                                // 2026-11-17：区域也进预设（用户要求）
                                selectedZoneIds = runCatching {
                                    val a = org.json.JSONArray(p.zoneIds); (0 until a.length()).mapNotNull { a.optString(it, "").ifBlank { null } }.toSet()
                                }.getOrDefault(emptySet())
                                scope.launch {
                                    runCatching { presetRepository?.markUsed(p.id) }
                                    presets = runCatching { presetRepository?.all() }.getOrNull().orEmpty()
                                }
                                UiSoundPlayer.playApiPresetSwitch(context)
                                showPresetSheet = false
                            },
                            verticalArrangement = Arrangement.Center
                        ) {
                            Text(p.name, style = MemoreType.body, color = MaterialTheme.colorScheme.onSurface)
                            val summary = buildString {
                                append(if (p.type == RecordType.DIALOGUE.name) "对话" else "随笔")
                                if (!p.originId.isNullOrBlank()) append(" · ").append(originList.firstOrNull { it.id == p.originId }?.name ?: "来源")
                                val zoneCount = runCatching { org.json.JSONArray(p.zoneIds).length() }.getOrDefault(0)
                                if (zoneCount > 0) append(" · ").append("$zoneCount 区域")
                            }
                            Text(summary, style = MemoreType.meta, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        // 2026-11-17：菜单改走 MirrorAnchoredMenu（法 4.9）——
                        // 触发控件与菜单位于同一个 Box，锚点就是这颗 ⋮；
                        // 行分隔线由 MirrorMenu 按 4.6 自动画（文字左缘 16dp 起、右留 8dp、末项不画）。
                        MirrorAnchoredMenu(
                            expanded = presetMenuFor?.id == p.id,
                            onDismissRequest = { presetMenuFor = null },
                            actions = listOf(
                                MirrorMenuAction("重命名") { presetMenuFor = null; renamePresetTarget = p },
                                MirrorMenuAction("删除", danger = true) {
                                    presetMenuFor = null
                                    scope.launch {
                                        runCatching { presetRepository?.delete(p.id) }
                                        presets = runCatching { presetRepository?.all() }.getOrNull().orEmpty()
                                    }
                                }
                            )
                        ) {
                            IconButton(onClick = { presetMenuFor = p }) {
                                Icon(Icons.Filled.MoreVert, "更多", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)))
                }
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = { showSavePresetDialog = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("＋ 用当前选项存为预设", style = MemoreType.meta, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }

    // 保存为预设（名字）
    if (showSavePresetDialog) {
        var presetName by remember { mutableStateOf("") }
        MirrorStackDialog(
            title = "存为预设",
            confirmText = "保存",
            onDismiss = { showSavePresetDialog = false },
            onConfirm = {
                val name = presetName.trim()
                if (name.isNotBlank()) {
                    // 预设不存附件与正文；存 类型/来源/标签/协议/区域，标签协议区域都存 id
                    val preset = memore.app.data.model.RecordPreset(
                        name = name,
                        type = (resolvedType ?: RecordType.ESSAY).name,
                        originId = originId,
                        tagIds = org.json.JSONArray(attachedTagIds.toList()).toString(),
                        protocolIds = org.json.JSONArray(attachedProtocolIds.toList()).toString(),
                        zoneIds = org.json.JSONArray(selectedZoneIds.toList()).toString()
                    )
                    scope.launch {
                        runCatching { presetRepository?.save(preset) }
                        presets = runCatching { presetRepository?.all() }.getOrNull().orEmpty()
                    }
                    showSavePresetDialog = false
                    showPresetSheet = true
                }
            }
        ) {
            MemoreLineInput(
                value = presetName,
                onValueChange = { presetName = it },
                placeholder = { Text("预设名称") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }

    // 重命名预设
    renamePresetTarget?.let { p ->
        var newName by remember(p.id) { mutableStateOf(p.name) }
        MirrorStackDialog(
            title = "重命名预设",
            confirmText = "保存",
            onDismiss = { renamePresetTarget = null },
            onConfirm = {
                val name = newName.trim()
                if (name.isNotBlank()) {
                    scope.launch {
                        runCatching { presetRepository?.save(p.copy(name = name)) }
                        presets = runCatching { presetRepository?.all() }.getOrNull().orEmpty()
                    }
                    renamePresetTarget = null
                }
            }
        ) {
            MemoreLineInput(
                value = newName,
                onValueChange = { newName = it },
                placeholder = { Text("预设名称") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}






