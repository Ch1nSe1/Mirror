package memore.app.ui.screens

import androidx.compose.material.icons.outlined.Add
import memore.app.ui.components.MemoreLineInput
import androidx.compose.foundation.horizontalScroll
import memore.app.ui.components.MirrorInputField
import memore.app.ui.components.MirrorStackDialog
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.foundation.background
import memore.app.ui.components.MirrorDropdownMenu
import memore.app.ui.components.MirrorAlertDialog
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import memore.app.ui.theme.MemoreType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import memore.app.R
import memore.app.core.ai.Prompts
import memore.app.ui.components.MirrorShapes
import memore.app.ui.components.MirrorMenuAction
import memore.app.ui.components.MirrorMenu
import memore.app.utils.UiSoundPlayer
import memore.app.utils.memoreToast

/**
 * 提示词管理器（阶段二）。
 *
 * 结构：
 *  - TopAppBar: 标题"提示词" + 右上角"选择"按钮
 *  - 模式条：全宽两等分，左"非思考"(透亮紫) 右"思考"(协议青)
 *  - 编辑区：等宽字体 dark 背景，显示当前选中功能的当前模式提示词
 *  - 底部：[重置为默认] [保存]
 *
 * 选择功能后立即显示当前生效的提示词（SP 自定义 > 默认）。
 * 重置为默认 = 删除该槽位的 SP 自定义，恢复默认值。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun PromptManagerScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current

    // 类型列表页（A1）：先选类型，再进编辑
    var showTypeList by remember { mutableStateOf(true) }
    var showResetConfirm by remember { mutableStateOf(false) }
    // 预设页签长按菜单
    var presetMenuFor by remember { mutableStateOf<String?>(null) }
    var presetMenuAnchor by remember { mutableStateOf(false) }
    // 当前选中的功能 key（null = 未选择）
    var selectedKey by rememberSaveable(stateSaver = androidx.compose.runtime.saveable.listSaver<String?, String>(
        save = { listOfNotNull(it) }, restore = { it.firstOrNull() })) { mutableStateOf<String?>(Prompts.KEY_ANALYZE) }
    // 2026-11-21：只有「AI 总结 / 草稿」这类跟随全局开关的功能才有两份（非思考/思考）；
    // 其余功能只有一份，界面不显示模式条。
    var thinkingMode by rememberSaveable { mutableStateOf(false) }
    val editKey: String? = selectedKey
    val editMode: Boolean = if (selectedKey?.let { Prompts.getDef(it)?.dualMode } == true) thinkingMode else false
    // 编辑草稿（保存前不落盘）
    var draft by rememberSaveable { mutableStateOf("") }
    // 已加载的槽位（key|mode）。返回本屏时槽位没变就不重新加载，否则会把未保存的草稿冲掉。
    var loadedSlot by rememberSaveable { mutableStateOf<String?>(null) }
    // 选择弹窗是否显示
    var showSelector by remember { mutableStateOf(false) }
    // 当前编辑的预设名（null = 不在编辑预设）
    var currentPresetName by remember { mutableStateOf<String?>(null) }
    // 预设管理弹窗
    var showPresetSheet by remember { mutableStateOf(false) }
    // 保存新预设的命名对话框
    var showSavePresetDialog by remember { mutableStateOf(false) }
    // 重命名对话框状态（目标预设名）
    var renameTarget by remember { mutableStateOf<String?>(null) }
    // 预设项 ⋮ 菜单（哪个预设打开了菜单）
    var menuForPreset by remember { mutableStateOf<String?>(null) }
    // 待删除确认的预设名
    var presetToDelete by remember { mutableStateOf<String?>(null) }
    // 切换保护确认弹窗（缓存的切换操作）
    var pendingSwitch by remember { mutableStateOf<(() -> Unit)?>(null) }
    // 切换功能/模式时标记是否需要保护
    var lastSavedDraft by remember { mutableStateOf("") }

    // 切换功能/子槽位时加载对应提示词
    LaunchedEffect(selectedKey, thinkingMode) {
        val key = editKey ?: return@LaunchedEffect
        val slot = key + "|" + editMode
        if (loadedSlot == slot) return@LaunchedEffect
        loadedSlot = slot
        draft = Prompts.getPrompt(key, editMode)
        lastSavedDraft = draft
        // 若当前提示词恰好是某个预设的内容，则归属到该预设（保存时覆盖而非新建预设名）
        currentPresetName = Prompts.getPresets(key).firstOrNull { name ->
            Prompts.getPresetContent(key, name) == draft
        }
    }
    // 类型列表页（A1）：分组列出全部提示词类型
    if (showTypeList) {
        PromptTypeList(
            currentKey = selectedKey,
            onPick = { key -> selectedKey = key; showTypeList = false },
            onBack = onBack
        )
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(Prompts.getDef(selectedKey ?: "")?.displayName ?: "提示词") },
                navigationIcon = {
                    IconButton(onClick = { showTypeList = true }) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            draft = ""; lastSavedDraft = ""; currentPresetName = null
                            showSavePresetDialog = true
                        },
                        enabled = Prompts.getPresets(editKey ?: Prompts.KEY_ANALYZE).size < Prompts.PRESET_LIMIT
                    ) {
                        Icon(Icons.Outlined.Add, "新建预设", tint = MaterialTheme.colorScheme.primary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(top = padding.calculateTopPadding())
                .padding(horizontal = 16.dp)
        ) {
            // ==== 模式条（只有真的有两份的功能才显示）====
            val isDual = selectedKey?.let { Prompts.getDef(it)?.dualMode } == true
            if (isDual) {
                Row(Modifier.fillMaxWidth().padding(top = 10.dp)) {
                    listOf(false to "非思考", true to "思考").forEach { (think, label) ->
                        val on = thinkingMode == think
                        Column(
                            Modifier.weight(1f).clickable {
                                if (draft != lastSavedDraft) pendingSwitch = { thinkingMode = think }
                                else thinkingMode = think
                            },
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                label,
                                style = MemoreType.meta,
                                fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(6.dp))
                            Box(
                                Modifier.width(if (on) 26.dp else 0.dp).height(2.dp)
                                    .clip(MirrorShapes.pill)
                                    .background(MaterialTheme.colorScheme.primary)
                            )
                        }
                    }
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f)))
            }

            // ==== 预设页签（α2）====
            if (selectedKey != null) {
                val presetNames = Prompts.getPresets(editKey!!)
                // 2026-11-21：没有预设时整块不画（否则上下两条发丝线之间会留一条空带）。
                if (presetNames.isNotEmpty()) {
                // 预设行上下各一条发丝线，条目之间竖线分隔（设计法条：0.10 / 1dp）。
                Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)))
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    presetNames.forEachIndexed { index, name ->
                        if (index > 0) {
                            Box(
                                Modifier.width(1.dp).height(14.dp)
                                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f))
                            )
                        }
                        val on = name == currentPresetName
                        // 长按就地弹三点菜单（2026-11-06：不要居中的选择弹窗，太重）
                        Box {
                            Column(
                                Modifier.combinedClickable(
                                    onClick = {
                                        val content = Prompts.getPresetContent(editKey!!, name)
                                        if (content != null) {
                                            draft = content
                                            currentPresetName = name
                                            lastSavedDraft = content
                                            Prompts.setPrompt(editKey!!, content, editMode)
                                        }
                                    },
                                    onLongClick = {
                                        presetMenuFor = name
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    }
                                )
                            ) {
                            Text(
                                name,
                                maxLines = 1,
                                softWrap = false,
                                style = MemoreType.meta,
                                fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(5.dp))
                            Box(
                                Modifier.width(if (on) 16.dp else 0.dp).height(2.dp)
                                    .clip(MirrorShapes.pill)
                                    .background(MaterialTheme.colorScheme.primary)
                            )
                        }   // Column
                            MirrorMenu(
                                expanded = presetMenuFor == name,
                                onDismissRequest = { presetMenuFor = null },
                                actions = listOf(
                                    MirrorMenuAction("重命名") { presetMenuFor = null; renameTarget = name },
                                    MirrorMenuAction("删除", danger = true) { presetMenuFor = null; presetToDelete = name }
                                )
                            )
                        }   // Box
                    }   // forEach
                }   // Row
                Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)))
                }
            }   // if (selectedKey != null)
            Spacer(Modifier.height(12.dp))

            // ==== 编辑区 ====
            if (selectedKey == null) {
                // 未选择功能：留白，不显示提示文字
                Spacer(Modifier.weight(1f))
            } else {
                val def = Prompts.getDef(editKey!!)
                val isAbandoned = def?.isAbandoned == true

                // 功能信息 + 废弃标记
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(def?.displayName ?: editKey!!, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground)
                    if (isAbandoned) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "（废弃）",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), MirrorShapes.pill)
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    Text(
                        "重置",
                        style = MemoreType.meta,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .clip(MirrorShapes.row)
                            .clickable { showResetConfirm = true }
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
                Spacer(Modifier.height(8.dp))

                // 编辑器（2026-11-16 修）：
                // 原来这里用 MemoreLineInput —— 那是一个"单行、固定 48dp"的输入组件。
                // 套上 weight(1f) 之后，组件内部的 .height(48.dp) 仍然生效：
                //   · 编辑框只有 48dp 高、且 singleLine=true，长提示词只能横向滚动
                //   · weight 分到的剩余高度整块空着 —— 这就是"内容之间空白非常大"
                // 现在换成真正的多行编辑器：自己撑满、自己滚动、行高来自 token。
                androidx.compose.foundation.text.BasicTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    singleLine = false,
                    textStyle = MemoreType.meta.copy(
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurface
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .clip(MirrorShapes.card)
                        .background(MaterialTheme.colorScheme.surfaceContainerLow)
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                )

                Spacer(Modifier.height(12.dp))

                // 底部只留一颗主色「保存」：重置是低频且危险的动，已移到标题行右侧
                Button(
                    onClick = {
                        editKey?.let { key ->
                            if (currentPresetName != null) {
                                val name = currentPresetName!!
                                Prompts.savePreset(key, name, draft)
                                Prompts.setPrompt(key, draft, editMode)
                                lastSavedDraft = draft
                                context.memoreToast("已保存到预设「$name」", android.widget.Toast.LENGTH_SHORT)
                                UiSoundPlayer.playPresetSave(context)
                            } else {
                                showSavePresetDialog = true
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = MirrorShapes.card,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Text(
                        "保存",
                        style = MemoreType.body,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    // 重置确认：克制版——一句话、两个动作，不解释
    if (showResetConfirm) {
        MirrorStackDialog(
            title = "重置",
            message = "恢复出厂提示词？",
            confirmText = "重置",
            destructive = true,
            onDismiss = { showResetConfirm = false },
            onConfirm = {
                editKey?.let { key ->
                    Prompts.resetPrompt(key, editMode)
                    draft = Prompts.getPrompt(key)
                    lastSavedDraft = draft
                    currentPresetName = null
                    context.memoreToast("已重置", android.widget.Toast.LENGTH_SHORT)
                    UiSoundPlayer.playSave(context)
                }
                showResetConfirm = false
            }
        )
    }
    // ==== 预设管理弹窗 ====
    if (showPresetSheet) {
        val key = editKey
        if (key != null) {
            val presets = Prompts.getPresets(key)
            val def = Prompts.getDef(key)
            MirrorAlertDialog(
                onDismissRequest = { showPresetSheet = false },
                title = { Text("${def?.displayName ?: key} · 预设") },
                text = {
                    Column {
                        if (presets.isEmpty()) {
                            Text("暂无预设", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 12.dp))
                        } else {
                            presets.forEach { name ->
                                Row(
                                    Modifier.fillMaxWidth().clickable {
                                        // 加载预设到编辑器
                                        Prompts.getPresetContent(key, name)?.let { content ->
                                            draft = content
                                            lastSavedDraft = content
                                            currentPresetName = name
                                        }
                                        showPresetSheet = false
                                    }.padding(vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                                    Box {
                                        IconButton(onClick = { menuForPreset = name }, modifier = Modifier.size(28.dp)) {
                                            Icon(Icons.Filled.MoreVert, "预设操作", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                                        }
                                        MirrorMenu(
                                            expanded = menuForPreset == name,
                                            onDismissRequest = { menuForPreset = null },
                                            actions = listOf(
                                                MirrorMenuAction("删除", danger = true) { menuForPreset = null; presetToDelete = name },
                                                MirrorMenuAction("编辑名称") { menuForPreset = null; renameTarget = name }
                                            )
                                        )
                                    }
                                }
                            }
                        }
                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                        // 底部：+ 按钮（左）+ 计数器（极右纯数字）
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            IconButton(
                                onClick = {
                                    // 新建预设：清空编辑器，退出预设编辑状态
                                    draft = ""
                                    lastSavedDraft = ""
                                    currentPresetName = null
                                    showPresetSheet = false
                                },
                                enabled = presets.size < Prompts.PRESET_LIMIT
                            ) {
                                Text("+", style = MaterialTheme.typography.titleLarge, color = if (presets.size < Prompts.PRESET_LIMIT) Color(0xFF4DD0E1) else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
                            }
                            Spacer(Modifier.weight(1f))
                            Text("${presets.size}", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                },
                confirmButton = {},
                dismissButton = {
                    TextButton(onClick = { showPresetSheet = false }) { Text("关闭") }
                }
            )
        }
    }

    // ==== 保存为新预设的命名弹窗 ====
    if (showSavePresetDialog) {
        var nameInput by remember { mutableStateOf("") }
        val key = editKey
        MirrorStackDialog(
            title = "保存为新预设",
            message = if (key != null) "已用 ${Prompts.getPresets(key).size} / ${Prompts.PRESET_LIMIT}" else null,
            confirmText = "保存",
            onDismiss = { showSavePresetDialog = false },
            onConfirm = {
                if (key != null && nameInput.isNotBlank()) {
                    Prompts.savePreset(key, nameInput.trim(), draft)
                    currentPresetName = nameInput.trim()
                    lastSavedDraft = draft
                    context.memoreToast("已保存", android.widget.Toast.LENGTH_SHORT)
                    UiSoundPlayer.playSave(context)
                }
                showSavePresetDialog = false
            }
        ) {
            MirrorInputField(value = nameInput, onValueChange = { nameInput = it }, placeholder = "预设名称")
        }
    }

    // ==== 重命名预设弹窗 ====
    if (renameTarget != null) {
        var newName by remember(renameTarget) { mutableStateOf(renameTarget ?: "") }
        val key = editKey
        MirrorStackDialog(
            title = "编辑预设名称",
            confirmText = "保存",
            onDismiss = { renameTarget = null },
            onConfirm = {
                val key2 = editKey
                if (key2 != null) {
                    Prompts.renamePreset(key2, renameTarget ?: "", newName.trim())
                }
                renameTarget = null
            }
        ) {
            MirrorInputField(value = newName, onValueChange = { newName = it }, placeholder = "新名称")
        }
    }

    // ==== 删除预设确认弹窗 ====
    val deleteTarget = presetToDelete
    if (deleteTarget != null) {
        val key = editKey
        MirrorStackDialog(
            title = "删除预设",
            message = "确定删除预设「$deleteTarget」吗？",
            confirmText = "删除",
            destructive = true,
            onDismiss = { presetToDelete = null },
            onConfirm = {
                if (key != null) {
                    Prompts.deletePreset(key, deleteTarget)
                    if (currentPresetName == deleteTarget) currentPresetName = null
                    context.memoreToast("已删除", android.widget.Toast.LENGTH_SHORT)
                    UiSoundPlayer.playDelete(context)
                }
                presetToDelete = null
            }
        )
    }

    // ==== 切换保护确认弹窗 ====
    val switchAction = pendingSwitch
    if (switchAction != null) {
        MirrorStackDialog(
            title = "未保存的修改",
            message = "当前预设的修改尚未保存，切换将丢失修改。",
            confirmText = "继续",
            destructive = false,
            onDismiss = { pendingSwitch = null },
            onConfirm = { pendingSwitch = null; switchAction() }
        )
    }
}
/**
 * 提示词类型列表（A1）：按用途分组，废弃项收进自己的组。
 * 点一行进编辑页；不再用下拉菜单盖住半个屏幕。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PromptTypeList(
    currentKey: String?,
    onPick: (String) -> Unit,
    onBack: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("提示词") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            // 2026-11-21：列表从注册表生成，不再硬编码（否则新增提示词永远没有入口）。
            val groups: List<Pair<String, List<Prompts.PromptDef>>> = Prompts.ALL_PROMPTS
                .filter { !it.isAbandoned }
                .groupBy { it.group }
                .toList()
            groups.forEach { (groupName, defs) ->
                Text(
                    groupName,
                    style = MemoreType.label,
                    letterSpacing = 1.1.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 16.dp, bottom = 4.dp)
                )
                defs.forEach { def ->
                    val (key, _) = def
                    val on = key == currentKey
                    Row(
                        Modifier.fillMaxWidth().height(46.dp).clickable { onPick(key) },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            def.displayName,
                            style = MemoreType.body,
                            fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(Modifier.weight(1f))
                        Text("›", style = MemoreType.meta, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Box(
                        Modifier.fillMaxWidth().height(1.dp)
                            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f))
                    )
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}





