package memore.app.ui.screens

import memore.app.ui.components.MirrorInputField
import memore.app.ui.components.MemoreLineInput
import memore.app.ui.components.MirrorStackDialog
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material.icons.outlined.KeyboardArrowRight
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.background
import android.widget.Toast
import memore.app.ui.components.MirrorDropdownMenu
import memore.app.ui.components.MirrorAlertDialog
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import memore.app.core.ai.SystemInstructionStore
import memore.app.ui.components.MirrorShapes
import memore.app.ui.components.mirrorHairline
import memore.app.ui.components.MirrorMenuAction
import memore.app.ui.components.MirrorMenu
import memore.app.utils.UiSoundPlayer
import memore.app.utils.memoreToast

/**
 * 外部 AI 规则独立管理页。
 * 结构参考提示词编辑页，但只复用交互形态，不接入 Prompts 的任何代码。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SystemInstructionScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val initialPresets = SystemInstructionStore.load(context)
    val initialActive = SystemInstructionStore.activeName(context).ifBlank { initialPresets.firstOrNull()?.name ?: "" }
    var presets by remember { mutableStateOf(initialPresets) }
    var activeName by remember { mutableStateOf(initialActive) }
    var exportEnabled by remember { mutableStateOf(SystemInstructionStore.exportEnabled(context)) }
    var editingName by remember { mutableStateOf(initialActive.ifBlank { null }) }
    var draft by remember { mutableStateOf(initialPresets.firstOrNull { it.name == initialActive }?.content ?: "") }
    var lastSaved by remember { mutableStateOf(draft) }
    var showNewNameDialog by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<String?>(null) }
    var deleteTarget by remember { mutableStateOf<String?>(null) }
    var menuFor by remember { mutableStateOf<String?>(null) }
    var pendingAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    // 0 = 规则列表，1 = 编辑页（S1：列表与编辑分离）
    var page by remember { mutableIntStateOf(0) }

    fun reloadAfterChange() {
        presets = SystemInstructionStore.load(context)
        activeName = SystemInstructionStore.activeName(context).ifBlank { presets.firstOrNull()?.name ?: "" }
        editingName = activeName.ifBlank { null }
        draft = presets.firstOrNull { it.name == activeName }?.content.orEmpty()
        lastSaved = draft
    }

    fun guarded(action: () -> Unit) {
        if (draft != lastSaved) {
            pendingAction = action
        } else {
            action()
        }
    }

    fun selectPreset(name: String) {
        val action = {
            activeName = name
            editingName = name
            draft = presets.firstOrNull { it.name == name }?.content.orEmpty()
            lastSaved = draft
            SystemInstructionStore.setActive(context, name)
        }
        if (name != activeName) guarded(action)
    }

    fun startNew() {
        guarded {
            editingName = null
            draft = ""
            lastSaved = ""
        }
    }

    fun saveExisting() {
        val name = editingName ?: return
        SystemInstructionStore.savePreset(context, name, draft)
        lastSaved = draft
        reloadAfterChange()
        UiSoundPlayer.playSave(context)
    }

    fun confirmNewSave(name: String) {
        val trimmed = name.trim()
        if (trimmed.isBlank()) {
            context.memoreToast("规则名称不能为空", Toast.LENGTH_SHORT)
            return
        }
        SystemInstructionStore.savePreset(context, trimmed, draft)
        showNewNameDialog = false
        reloadAfterChange()
        UiSoundPlayer.playSave(context)
    }

    fun confirmDelete(name: String) {
        SystemInstructionStore.deletePreset(context, name)
        deleteTarget = null
        reloadAfterChange()
        UiSoundPlayer.playDelete(context)
    }

    fun confirmRename(oldName: String, newName: String) {
        val trimmed = newName.trim()
        if (SystemInstructionStore.renamePreset(context, oldName, trimmed)) {
            presets = SystemInstructionStore.load(context)
            activeName = SystemInstructionStore.activeName(context).ifBlank { presets.firstOrNull()?.name ?: "" }
            editingName = if (editingName == oldName || activeName == oldName) trimmed else editingName
            draft = presets.firstOrNull { it.name == editingName }?.content.orEmpty()
            lastSaved = draft
            renameTarget = null
            UiSoundPlayer.playSave(context)
        } else {
            context.memoreToast("重命名失败：名称为空或已存在", Toast.LENGTH_SHORT)
        }
    }

    val statusText = when {
        !exportEnabled -> "已暂停"
        presets.isEmpty() -> "暂无规则"
        activeName.isBlank() -> "未选择规则"
        else -> "$activeName · ${presets.firstOrNull { it.name == activeName }?.content?.length ?: 0} 字"
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (page == 1) ("编辑 · " + (editingName ?: "")) else "系统提示", fontSize = if (page == 1) 17.sp else 22.sp, fontWeight = if (page == 1) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = { if (page == 1) page = 0 else onBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                },
                actions = {
                    if (page == 1) {
                        Box {
                            IconButton(onClick = { menuFor = "__edit__" }) {
                                Icon(Icons.Filled.MoreVert, "更多", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            MirrorMenu(
                                expanded = menuFor == "__edit__",
                                onDismissRequest = { menuFor = null },
                                actions = listOf(
                                    MirrorMenuAction("设为当前") {
                                        menuFor = null
                                        editingName?.let { selectPreset(it) }
                                    },
                                    MirrorMenuAction("重命名") {
                                        val n = editingName; menuFor = null; renameTarget = n
                                    },
                                    MirrorMenuAction("删除", danger = true) {
                                        val n = editingName; menuFor = null; deleteTarget = n
                                    }
                                )
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        bottomBar = {
            if (page == 1) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    Button(
                        onClick = { if (editingName == null) showNewNameDialog = true else saveExisting() },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp).height(48.dp),
                        shape = MirrorShapes.card,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                    ) {
                        Text(
                            if (editingName == null) "保存为新规则" else "保存",
                            fontSize = 15.sp,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                }
            }
        },        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        if (page == 0) {
            // ===== 列表页：开关 + 规则行（名字 + 字数）=====
            Column(
                Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp)
            ) {
                Row(
                    Modifier.fillMaxWidth().height(52.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("导出时携带规则", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
                    Spacer(Modifier.weight(1f))
                    Switch(
                        checked = exportEnabled,
                        onCheckedChange = { enabled ->
                            exportEnabled = enabled
                            SystemInstructionStore.setExportEnabled(context, enabled)
                            if (enabled) UiSoundPlayer.playExportRuleOn(context) else UiSoundPlayer.playExportRuleOff(context)
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = MaterialTheme.colorScheme.primary,
                            checkedTrackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
                        )
                    )
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f)))

                if (presets.isEmpty()) {
                    Spacer(Modifier.height(16.dp))
                    Text("暂无规则", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    presets.forEach { preset ->
                        val isActive = preset.name == activeName
                        Row(
                            Modifier.fillMaxWidth().height(52.dp).clickable {
                                editingName = preset.name
                                draft = preset.content
                                lastSaved = preset.content
                                page = 1
                            },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                preset.name,
                                fontSize = 14.5.sp,
                                fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                "${preset.content.length} 字",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.width(6.dp))
                            Icon(Icons.Outlined.KeyboardArrowRight, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)))
                    }
                }
                Spacer(Modifier.height(14.dp))
                Text(
                    "＋ 新建规则",
                    fontSize = 13.5.sp,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable { startNew(); page = 1 }
                )
                Spacer(Modifier.height(24.dp))
            }
        } else {
            // ===== 编辑页：只有内容，一颗保存 =====
            Column(
                Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp)
            ) {
                MemoreLineInput(
                    value = draft,
                    onValueChange = { draft = it },
                    modifier = Modifier.fillMaxWidth().weight(1f).padding(top = 4.dp),
                    placeholder = { Text("规则内容") },
                    textStyle = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurface
                    ),
                    shape = MirrorShapes.card,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow
                    )
                )
            }
        }
    }
    if (showNewNameDialog) {
        var nameInput by remember { mutableStateOf("") }
        MirrorStackDialog(
            title = "保存为新规则",
            confirmText = "保存",
            onDismiss = { showNewNameDialog = false },
            onConfirm = { confirmNewSave(nameInput); showNewNameDialog = false }
        ) {
            MirrorInputField(value = nameInput, onValueChange = { nameInput = it }, placeholder = "规则名称")
        }
    }

    renameTarget?.let { oldName ->
        var renameInput by remember(oldName) { mutableStateOf(oldName) }
        MirrorStackDialog(
            title = "重命名规则",
            confirmText = "保存",
            onDismiss = { renameTarget = null },
            onConfirm = { confirmRename(oldName, renameInput); renameTarget = null }
        ) {
            MirrorInputField(value = renameInput, onValueChange = { renameInput = it }, placeholder = "规则名称")
        }
    }

    deleteTarget?.let { name ->
        MirrorStackDialog(
            title = "删除规则",
            message = "确定删除「$name」吗？",
            confirmText = "删除",
            destructive = true,
            onDismiss = { deleteTarget = null },
            onConfirm = { confirmDelete(name) }
        )
    }

    pendingAction?.let { action ->
        MirrorStackDialog(
            title = "放弃修改",
            message = "当前编辑尚未保存，切换后将丢失修改。",
            confirmText = "继续",
            destructive = false,
            onDismiss = { pendingAction = null },
            onConfirm = { pendingAction = null; action() }
        )
    }
}
