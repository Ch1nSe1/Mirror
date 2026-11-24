package memore.app.ui.components

import androidx.compose.ui.text.style.TextAlign
import memore.app.ui.components.MemoreLineInput
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.background
import android.content.Context
import memore.app.ui.components.MirrorAlertDialog
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import memore.app.data.model.AnalysisData
import memore.app.data.model.Record
import memore.app.data.repository.AttachmentView
import memore.app.data.repository.RecordRepository
import memore.app.utils.EncryptionManager
import memore.app.utils.GsonInstance
import memore.app.utils.memoreToast
import memore.app.utils.UiSoundPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.io.File
import javax.crypto.SecretKey

/** 临时解密结果：error 非空表示失败；toast 为成功后要展示的提示（可选）。 */
data class TempDecryptResult(val error: String? = null, val toast: String? = null)

/**
 * 加密交互的共享状态：临时查看 + 锁定/解锁。
 * 每个屏幕持有一个实例（`rememberEncryptionHost`），配合 `EncryptionDialogs` 使用。
 */
class EncryptionHostState(
    private val repository: RecordRepository,
    val cacheRoot: File
) {
    // —— 临时查看状态 ——
    val tempDecryptedMap = mutableStateMapOf<String, TempDecryptedRecord>()
    val tempDecryptedAmendsMap = mutableStateMapOf<String, Map<String, String>>() // parentId -> (amendmentId -> 明文)
    val tempAttachmentPaths = mutableStateMapOf<String, List<AttachmentView>>()
    var tempDecryptMode by mutableStateOf(0) // 0=无, 1=单条, 2=全部
    var tempChoiceTargetId by mutableStateOf<String?>(null)
    var tempPendingMode by mutableStateOf(1)
    var showTempViewPwDialog by mutableStateOf(false)
    var tempViewPw by mutableStateOf("")
    var tempViewErr by mutableStateOf<String?>(null)

    // —— 锁定/解锁状态 ——
    var lockTargetId by mutableStateOf<String?>(null)
    var unlockTargetId by mutableStateOf<String?>(null)
    var lockPassword by mutableStateOf("")
    var lockError by mutableStateOf<String?>(null)
    /** v5：请求"直接锁"的记录 id（由 EncryptionDialogs 的 LaunchedEffect 执行写库）。 */
    var pendingDirectLockId by mutableStateOf<String?>(null)

    fun onLockClick(record: Record) {
        // 2026-11-16（用户口径）：锁定 = 直接锁，不要密码。
        // v5 里锁定只是打标记（正文仍是明文），不存在"用密码加密内容"这一步；
        // 旧版弹密码框是因为要现算密钥把正文加密。
        // 真正的写库由 EncryptionDialogs 里的 LaunchedEffect 执行（不在这里裸开协程）。
        if (EncryptionManager.isV5()) {
            pendingDirectLockId = record.id
            return
        }
        lockTargetId = record.id; lockPassword = ""; lockError = null
    }

    fun onUnlockClick(record: Record) {
        unlockTargetId = record.id; lockPassword = ""; lockError = null
    }

    fun onTempDecryptClick(record: Record) {
        tempChoiceTargetId = record.id
    }

    /** 附件占位"临时查看"按钮：直接以单条模式进入密码框。 */
    fun onUnlockTemp(record: Record) {
        tempChoiceTargetId = record.id; tempPendingMode = 1
        tempViewPw = ""; tempViewErr = null
        showTempViewPwDialog = true
    }

    /** 退出临时查看：mode2 清全部，mode1 清单条，同时清理附件缓存目录。 */
    fun onTempLockClick(record: Record) {
        if (tempDecryptMode == 2) {
            tempDecryptedMap.clear(); tempDecryptedAmendsMap.clear(); tempAttachmentPaths.clear(); tempDecryptMode = 0
            clearCache(null)
        } else {
            tempDecryptedMap.remove(record.id); tempDecryptedAmendsMap.remove(record.id); tempAttachmentPaths.remove(record.id)
            clearCache(record.id)
        }
    }

    /** 模式 1/2 临时解密（key 由调用方在密码验证后提供）。 */
    suspend fun performTempDecrypt(key: SecretKey): TempDecryptResult {
        // 2026-11-16（v5 门闩）：库里没有密文可解。
        // 密码已经在调用方验过，这里把**明文**直接放进"临时解密态"——
        // 锁定记录的 content 本来就躺在库里，隐藏靠的是这张 map，而不是解密。
        if (EncryptionManager.isV5()) {
            return try {
                when (tempPendingMode) {
                    1 -> {
                        val targetId = tempChoiceTargetId ?: return TempDecryptResult(error = "目标记录丢失")
                        val rec = repository.getRecordByIdRaw(targetId)
                        if (rec != null && rec.isLocked) {
                            tempDecryptedMap[targetId] = buildTempRecord(
                                memore.app.utils.RecordPayload(
                                    content = rec.content,
                                    notes = rec.notes,
                                    dialogueAiContent = rec.dialogueAiContent,
                                    dialogueAiReply = rec.dialogueAiReply,
                                    analysisJson = rec.analysis?.let { GsonInstance.gson.toJson(it) },
                                    emotionalTags = rec.emotionalTags,
                                    hasAnalysis = rec.analysis != null,
                                    hasEmotionalTags = true
                                )
                            )
                            try {
                                tempDecryptedAmendsMap[targetId] =
                                    repository.getAmendmentsForRecordRaw(targetId).associate { it.id to it.content }
                            } catch (_: Exception) {}
                            tempDecryptMode = 1
                        }
                        TempDecryptResult()
                    }
                    2 -> {
                        val locked = repository.getLockedRecords()
                        locked.forEach { r ->
                            runCatching {
                                tempDecryptedMap[r.id] = buildTempRecord(
                                    memore.app.utils.RecordPayload(
                                        content = r.content,
                                        notes = r.notes,
                                        dialogueAiContent = r.dialogueAiContent,
                                        dialogueAiReply = r.dialogueAiReply,
                                        analysisJson = r.analysis?.let { GsonInstance.gson.toJson(it) },
                                        emotionalTags = r.emotionalTags,
                                        hasAnalysis = r.analysis != null,
                                        hasEmotionalTags = true
                                    )
                                )
                            }
                        }
                        tempDecryptMode = 2
                        TempDecryptResult(toast = if (locked.isNotEmpty()) "已临时解锁全部 ${locked.size} 条" else null)
                    }
                    else -> TempDecryptResult()
                }
            } catch (e: Exception) {
                TempDecryptResult(error = "临时查看失败: ${e.message}")
            }
        }
        return try {
            when (tempPendingMode) {
                1 -> {
                    val targetId = tempChoiceTargetId ?: return TempDecryptResult(error = "目标记录丢失")
                    val rec = repository.getRecordByIdRaw(targetId)
                    if (rec != null && rec.isLocked) {
                        val json = EncryptionManager.decryptContent(rec.encryptedData, rec.iv, rec.authTag, key)
                        tempDecryptedMap[targetId] = buildTempRecord(EncryptionManager.unpackRecordPayload(json))
                        try { tempAttachmentPaths[targetId] = repository.decryptAttachmentsForTempView(targetId, cacheRoot) } catch (_: Exception) {}
                        try { tempDecryptedAmendsMap[targetId] = decryptAmendsForTemp(rec, key, repository) } catch (_: Exception) {}
                        tempDecryptMode = 1
                    }
                    TempDecryptResult()
                }
                2 -> {
                    val locked = repository.getLockedRecords()
                    locked.forEach { r ->
                        try {
                            val json = EncryptionManager.decryptContent(r.encryptedData, r.iv, r.authTag, key)
                            tempDecryptedMap[r.id] = buildTempRecord(EncryptionManager.unpackRecordPayload(json))
                            try { tempAttachmentPaths[r.id] = repository.decryptAttachmentsForTempView(r.id, cacheRoot) } catch (_: Exception) {}
                            try { tempDecryptedAmendsMap[r.id] = decryptAmendsForTemp(r, key, repository) } catch (_: Exception) {}
                        } catch (_: Exception) {}
                    }
                    tempDecryptMode = 2
                    TempDecryptResult(toast = if (locked.isNotEmpty()) "已临时解密全部 ${locked.size} 条" else null)
                }
                else -> TempDecryptResult()
            }
        } catch (e: Exception) {
            TempDecryptResult(error = "解密失败: ${e.message}")
        }
    }

    private fun clearCache(recordId: String?) {
        try {
            if (recordId != null) File(cacheRoot, recordId).deleteRecursively()
            else cacheRoot.deleteRecursively()
        } catch (_: Exception) {}
    }
}

@Composable
fun rememberEncryptionHost(repository: RecordRepository, cacheRoot: File): EncryptionHostState {
    return remember(repository, cacheRoot) { EncryptionHostState(repository, cacheRoot) }
}

/** 渲染加密相关的 4 个对话框：锁定、解锁、临时查看模式选择、临时查看密码框。 */
@Composable
fun EncryptionDialogs(
    host: EncryptionHostState,
    repository: RecordRepository,
    context: Context,
    scope: CoroutineScope
) {
    // 2026-11-16（v5）：锁定=直接打标记，不弹密码框。
    androidx.compose.runtime.LaunchedEffect(host.pendingDirectLockId) {
        val id = host.pendingDirectLockId ?: return@LaunchedEffect
        repository.lockRecord(id)
            .onSuccess {
                context.memoreToast("已锁定", Toast.LENGTH_SHORT)
                UiSoundPlayer.playEncryptComplete(context)
            }
            .onFailure { context.memoreToast(it.message ?: "锁定失败", Toast.LENGTH_SHORT) }
        host.pendingDirectLockId = null
    }

    // 加密锁定对话框（2026-10-24 改：居中竖排、去掉小字、密码改横线）
    if (host.lockTargetId != null) {
        MirrorStackDialog(
            title = "加密此记录",
            confirmText = "加密",
            onDismiss = { host.lockTargetId = null },
            onConfirm = {
                scope.launch {
                    // 密码验证通过 → 缓存密钥 → 用缓存密钥锁定（不修改密码参数）
                    if (EncryptionManager.verifyUnlockPassword(host.lockPassword)) {
                        EncryptionManager.ensureKey(host.lockPassword)
                        repository.lockRecord(host.lockTargetId!!).onSuccess {
                            host.lockTargetId = null
                            context.memoreToast("已锁定", Toast.LENGTH_SHORT)
                            UiSoundPlayer.playEncryptComplete(context)
                        }.onFailure { host.lockError = it.message }
                    } else {
                        host.lockError = "密码错误"
                        UiSoundPlayer.playPasswordError(context)
                    }
                }
            }
        ) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                // 一条横线的密码输入（与 PIN / 安全问题同一族）
                androidx.compose.foundation.text.BasicTextField(
                    value = host.lockPassword,
                    onValueChange = { host.lockPassword = it; host.lockError = null },
                    singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(
                        fontSize = 20.sp,
                        letterSpacing = 4.sp,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurface
                    ),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = androidx.compose.ui.text.input.KeyboardType.Password,
                        imeAction = androidx.compose.ui.text.input.ImeAction.Done
                    ),
                    modifier = Modifier.fillMaxWidth(),
                    decorationBox = { inner ->
                        Column(Modifier.fillMaxWidth()) {
                            Box(Modifier.fillMaxWidth().height(44.dp), contentAlignment = Alignment.Center) {
                                if (host.lockPassword.isEmpty()) {
                                    Text(
                                        "主密码",
                                        fontSize = 15.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.40f)
                                    )
                                }
                                inner()
                            }
                            Box(
                                Modifier.fillMaxWidth().height(1.dp)
                                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.28f))
                            )
                        }
                    }
                )
                host.lockError?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }

    // 加密解锁对话框
    if (host.unlockTargetId != null) {
        MirrorStackDialog(
            title = "解密此记录",
            confirmText = "解密",
            onDismiss = { host.unlockTargetId = null },
            onConfirm = {
                scope.launch {
                    if (EncryptionManager.verifyUnlockPassword(host.lockPassword)) {
                        repository.unlockRecord(host.unlockTargetId!!, host.lockPassword).onSuccess {
                            host.unlockTargetId = null
                            context.memoreToast("已解锁", Toast.LENGTH_SHORT)
                            UiSoundPlayer.playDecryptComplete(context)
                        }.onFailure { host.lockError = it.message }
                    } else {
                        host.lockError = "密码错误"
                        UiSoundPlayer.playPasswordError(context)
                    }
                }
            }
        ) {
            MirrorPasswordField(
                value = host.lockPassword,
                onValueChange = { host.lockPassword = it; host.lockError = null },
                error = host.lockError
            )
        }
    }

    // 临时解密：选择框（仅此条 / 全部）
    if (host.tempChoiceTargetId != null && !host.showTempViewPwDialog) {
        MirrorChoiceDialog(
            title = "临时解密",
            options = listOf(
                ChoiceOption("仅解密此条", "锁键只锁这一条", IconLock),
                ChoiceOption("解密所有已加密记录", "任意锁键锁回全部", IconLock)
            ),
            onSelect = { index ->
                host.tempPendingMode = if (index == 0) 1 else 2
                host.tempViewPw = ""
                host.tempViewErr = null
                host.showTempViewPwDialog = true
                host.tempChoiceTargetId = host.tempChoiceTargetId
            },
            onDismiss = { host.tempChoiceTargetId = null }
        )
    }

    // 临时解密：密码验证框
    if (host.showTempViewPwDialog) {
        MirrorStackDialog(
            title = "输入主密码",
            confirmText = "解密",
            onDismiss = { host.showTempViewPwDialog = false },
            onConfirm = {
                scope.launch {
                    // 2026-11-16：v5 没有密文，密码只是门闩 —— 校验必须走 verifyLockPassword。
                    // 原来这里只有 verifyPasswordCached（v3 语义），v5 下会永远判"密码错误"，
                    // 于是"查看锁定记录"整条路是死的。
                    val ok = if (EncryptionManager.isV5()) {
                        EncryptionManager.verifyLockPassword(host.tempViewPw)
                    } else {
                        // 认证缓存：60 秒内相同密码不重跑 PBKDF2，解决卡顿
                        EncryptionManager.verifyUnlockPasswordCached(host.tempViewPw)
                    }
                    if (ok) {
                        EncryptionManager.ensureKey(host.tempViewPw)
                        val key = EncryptionManager.getCachedKey()
                        if (key == null) { host.tempViewErr = "密码验证失败"; return@launch }
                        val result = host.performTempDecrypt(key)
                        if (result.error != null) {
                            host.tempViewErr = result.error
                        } else {
                            host.showTempViewPwDialog = false
                            host.tempChoiceTargetId = null
                            result.toast?.let { context.memoreToast(it, Toast.LENGTH_SHORT) }
                            UiSoundPlayer.playEncTempDec(context)
                        }
                    } else {
                        host.tempViewErr = "密码错误"
                        UiSoundPlayer.playPasswordError(context)
                    }
                }
            }
        ) {
            MirrorPasswordField(
                value = host.tempViewPw,
                onValueChange = { host.tempViewPw = it; host.tempViewErr = null },
                error = host.tempViewErr
            )
        }
    }
}

/** 由解密后的 payload 组装临时查看的 UI 数据（解析摘要）。 */
private fun buildTempRecord(p: memore.app.utils.RecordPayload): TempDecryptedRecord {
    val summary = p.analysisJson?.let { json ->
        try { GsonInstance.gson.fromJson(json, AnalysisData::class.java)?.summary } catch (_: Exception) { null }
    }
    return TempDecryptedRecord(p.content, p.notes, summary, p.dialogueAiContent.takeIf { it.isNotBlank() }, p.dialogueAiReply.takeIf { it.isNotBlank() })
}

/** 临时查看时解密父记录的全部增修内容（id -> 明文）。 */
private suspend fun decryptAmendsForTemp(parent: Record, key: SecretKey, repo: RecordRepository): Map<String, String> {
    val result = mutableMapOf<String, String>()
    repo.getAmendmentsForRecordRaw(parent.id).forEach { a ->
        if (a.isLocked) {
            try {
                val json = EncryptionManager.decryptContent(a.encryptedData, a.iv, a.authTag, key)
                result[a.id] = EncryptionManager.unpackRecordPayload(json).content
            } catch (_: Exception) { result[a.id] = "" }
        } else {
            result[a.id] = a.content
        }
    }
    return result
}

