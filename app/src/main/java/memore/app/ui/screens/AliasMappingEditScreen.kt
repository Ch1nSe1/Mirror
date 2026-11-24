package memore.app.ui.screens

import memore.app.ui.components.MirrorStackDialog
import memore.app.ui.components.MemoreLineInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.clickable
import memore.app.ui.components.MirrorShapes
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.background
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.Icons
import android.widget.Toast
import memore.app.ui.components.MirrorAlertDialog
import androidx.compose.foundation.layout.*
import memore.app.utils.memoreToast
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import memore.app.data.model.AliasGroup
import memore.app.data.repository.AliasMappingRepository
import memore.app.ui.components.IconEmotion
import memore.app.utils.UiSoundPlayer
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AliasMappingEditScreen(
    repo: AliasMappingRepository,
    editGroup: AliasGroup?,
    onBack: () -> Unit,
    onSaved: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var standardName by remember { mutableStateOf(editGroup?.standardName ?: "") }
    var aliases by remember { mutableStateOf(editGroup?.aliases ?: emptyList()) }
    var inputText by remember { mutableStateOf("") }
    var hasChanges by remember { mutableStateOf(false) }
    var showDiscard by remember { mutableStateOf(false) }
    var showDeleteGroup by remember { mutableStateOf(false) }
    var isEmotion by remember { mutableStateOf(editGroup?.kind == "emotion") }

    val isEditing = editGroup != null
    val title = if (isEditing) "编辑别名组" else "新建别名组"

    fun addAlias(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        if (trimmed in aliases) {
            context.memoreToast("该别名已存在", Toast.LENGTH_SHORT)
            return
        }
        aliases = aliases + trimmed
        inputText = ""
        hasChanges = true
    }

    fun removeAlias(name: String) {
        aliases = aliases - name
        hasChanges = true
    }

    fun save() {
        if (standardName.isBlank()) {
            context.memoreToast("标准名称不能为空", Toast.LENGTH_SHORT)
            return
        }
        if (aliases.isEmpty()) {
            context.memoreToast("至少需要添加一个别名", Toast.LENGTH_SHORT)
            return
        }
        scope.launch {
            if (isEditing) {
                repo.updateGroup(editGroup!!.copy(standardName = standardName.trim(), aliases = aliases, kind = if (isEmotion) "emotion" else "concept"))
            } else {
                repo.addGroup(AliasGroup(standardName = standardName.trim(), aliases = aliases, kind = if (isEmotion) "emotion" else "concept"))
            }
            UiSoundPlayer.playSave(context)
            onSaved()
        }
    }

    fun handleBack() {
        if (hasChanges) showDiscard = true else onBack()
    }

    if (showDiscard) {
        MirrorStackDialog(
            title = "未保存",
            message = "有未保存的更改，确定放弃吗？",
            confirmText = "放弃",
            destructive = true,
            onDismiss = { showDiscard = false },
            onConfirm = { showDiscard = false; onBack() }
        )
    }

    // 删除整组的二次确认（防止误触直接删除）
    if (showDeleteGroup) {
        MirrorStackDialog(
            title = "删除映射组",
            message = "确定删除「${editGroup?.standardName ?: ""}」的别名映射组吗？",
            confirmText = "删除",
            destructive = true,
            onDismiss = { showDeleteGroup = false },
            onConfirm = {
                showDeleteGroup = false
                scope.launch { repo.deleteGroup(editGroup!!.id); UiSoundPlayer.playDelete(context); onSaved() }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = { IconButton(onClick = { handleBack() }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回") } },
                actions = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { isEmotion = !isEmotion; hasChanges = true }) {
                            Icon(
                                IconEmotion,
                                if (isEmotion) "保存为情绪映射" else "保存为名称映射",
                                Modifier.size(22.dp),
                                tint = if (isEmotion) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        bottomBar = {
            // 保存移到底部（一颗主键，空名称/无别名时置灰）
            Surface(color = MaterialTheme.colorScheme.background) {
                Button(
                    onClick = { save() },
                    enabled = standardName.isNotBlank() && aliases.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp).height(48.dp),
                    shape = MirrorShapes.card,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Text(
                        "保存",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                }
            }
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
            Spacer(Modifier.height(16.dp))
            Text("概念名", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            MemoreLineInput(
                value = standardName,
                onValueChange = { standardName = it; hasChanges = true },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = MirrorShapes.row,
                placeholder = { Text("输入概念名") }
            )
            Spacer(Modifier.height(20.dp))
            // 别名：先输入行（添加是文字动作），再把已添加的别名逐行列出（F1）
            Row(verticalAlignment = Alignment.CenterVertically) {
                MemoreLineInput(
                    value = inputText,
                    onValueChange = { inputText = it },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    shape = MirrorShapes.card,
                    placeholder = { Text("输入别名") },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow
                    )
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    "添加",
                    fontSize = 13.5.sp,
                    color = if (inputText.isBlank()) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    else MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clickable { addAlias(inputText) }
                        .padding(horizontal = 6.dp, vertical = 8.dp)
                )
            }
            Spacer(Modifier.height(8.dp))
            val listState = rememberScrollState()
            Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(listState)) {
                if (aliases.isEmpty()) {
                    Text(
                        "还没有别名",
                        fontSize = 11.5.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 10.dp)
                    )
                } else {
                    aliases.forEach { alias ->
                        Row(
                            Modifier.fillMaxWidth().height(44.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(alias, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                            Text(
                                "✕",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.clickable { removeAlias(alias) }.padding(horizontal = 8.dp, vertical = 6.dp)
                            )
                        }
                        Box(
                            Modifier.fillMaxWidth().height(1.dp)
                                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f))
                        )
                    }
                }
                if (isEditing) {
                    Spacer(Modifier.height(24.dp))
                    Text(
                        "删除此组",
                        fontSize = 13.5.sp,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.clickable { showDeleteGroup = true }.padding(vertical = 8.dp)
                    )
                }
            }
        }
    }
}
