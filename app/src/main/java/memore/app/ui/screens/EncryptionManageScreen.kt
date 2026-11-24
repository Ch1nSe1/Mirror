package memore.app.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.KeyboardArrowRight
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import memore.app.data.repository.RecordRepository
import memore.app.ui.components.IconBack
import memore.app.ui.components.MemoreLineInput
import memore.app.ui.components.MemorePage
import memore.app.ui.components.MirrorLineField
import memore.app.ui.components.MirrorPasswordField
import memore.app.ui.components.MirrorShapes
import memore.app.ui.components.mirrorFieldColors
import memore.app.ui.theme.MemoreType
import memore.app.utils.EncryptionManager
import memore.app.utils.UiSoundPlayer
import memore.app.utils.memoreToast
import kotlinx.coroutines.launch

/**
 * 加密管理（2026-11-16 抽成真正的路由屏）。
 *
 * 以前它是 SettingsScreen 里的一层 if(...) 覆盖层：每输入一个字符都会让
 * 那棵 2000 行 / 100 个 state 的设置树重新组合 —— 这就是"卡"的根因。
 * 现在它有自己的屏号（SCREEN_ENCRYPTION_MANAGE），宿主不再参与重组。
 *
 * 四个阶段（同一屏内切换，不再嵌套 Dialog）：
 *   0 密码门
 *   1 操作面板（状态 / 修改密码 / 重置密码 / 清除加密）
 *   2 修改密码（身份 + 新凭证 + 密保）
 *   3 重置密码（密保答案 + 新密码）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EncryptionManageScreen(
    recordRepository: RecordRepository?,
    onBack: () -> Unit,
    onNeedSetup: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var step by remember { mutableIntStateOf(0) }
    var gatePw by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var lockedCount by remember { mutableIntStateOf(0) }
    var toggleEnabled by remember { mutableStateOf(EncryptionManager.isEnabled()) }
    var willUpgrade by remember { mutableStateOf(false) }

    // 修改密码
    var chOld by remember { mutableStateOf("") }
    var chNew by remember { mutableStateOf("") }
    var chQ by remember { mutableStateOf("") }
    var chA by remember { mutableStateOf("") }

    // 重置密码
    var forgotAnswer by remember { mutableStateOf("") }
    var forgotNew by remember { mutableStateOf("") }
    var question by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        // 声音不在这里放：HIDDEN_ENCRYPT =「加密·隐藏入口」，属于"点入口"这个动作，
        // 由 SettingsScreen 的门入口播一次。放在这里会在每次回到本屏时重复响。
        EncryptionManager.refreshConfigStatus()
        val hasPw = EncryptionManager.hasPassword()
        val hasCipher = runCatching { recordRepository?.hasTrulyEncryptedData() == true }.getOrDefault(false)
        if (!hasPw && !hasCipher) {
            onNeedSetup()
            return@LaunchedEffect
        }
        lockedCount = recordRepository?.lockedRecordCount() ?: 0
        toggleEnabled = EncryptionManager.isEnabled()
        willUpgrade = runCatching { EncryptionManager.needsV3Upgrade() }.getOrDefault(false)
    }

    /**
     * 返回模型（2026-11-16 统一）：
     *  0 密码门   → 离开本屏
     *  1 面板     → 离开本屏（不是回密码门！之前那样点退出会退到输密码页）
     *  2 修改密码 → 回面板
     *  3 重置密码 → 回密码门（它只能从密码门进）
     * 顶栏与系统返回键共用这一份，避免两套逻辑。
     */
    fun goBack() {
        when (step) {
            0, 1 -> onBack()
            2 -> { step = 1; error = null }
            else -> { step = 0; gatePw = ""; forgotAnswer = ""; forgotNew = ""; error = null }
        }
    }
    androidx.activity.compose.BackHandler(enabled = true) { goBack() }

    val title = when (step) {
        2 -> "修改密码"
        3 -> "重置密码"
        else -> "加密管理"
    }

    // 法 铁律 2 / 1.1：页面不手写顶栏，走共享的 MemorePage（64dp、返回、标题、inset 由它做对）。
    // 之前这里自绘过 56dp 的一行 —— 那是我在"M3 默认值"和"项目法条"之间又拿了默认值。
    MemorePage(title = title, onBack = { goBack() }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding)
                .verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)
        ) {
            when (step) {
                // ---------- 密码门 ----------
                0 -> {
                    Spacer(Modifier.height(28.dp))
                    MirrorPasswordField(
                        value = gatePw,
                        onValueChange = { gatePw = it; error = null },
                        placeholder = "主密码",
                        error = error
                    )
                    Spacer(Modifier.height(28.dp))
                    Button(
                        onClick = {
                            scope.launch {
                                val ok = if (EncryptionManager.isV5()) {
                                    EncryptionManager.verifyLockPassword(gatePw)
                                } else {
                                    val v = EncryptionManager.verifyPassword(gatePw)
                                    if (v) EncryptionManager.ensureKey(gatePw)
                                    v
                                }
                                if (ok) {
                                    lockedCount = recordRepository?.lockedRecordCount() ?: 0
                                    // 这里不放 LOCK_ON：那是「应用锁·开启」的声音，不属于加密管理
                                    // （加密侧有专用事件：HIDDEN_ENCRYPT / ENCRYPT / DECRYPT / ENC_TEMP_*，
                                    //   "验过密码进面板"没有对应事件，宁可不响也不要错放别人的声音）
                                    step = 1; error = null
                                } else {
                                    error = "密码错误"; UiSoundPlayer.playPasswordError(context)
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        shape = MirrorShapes.card,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                    ) { Text("验证", style = MemoreType.button, color = MaterialTheme.colorScheme.onPrimary) }
                    TextButton(
                        onClick = {
                            scope.launch {
                                question = (if (EncryptionManager.isV5()) EncryptionManager.getLockQuestion()
                                            else EncryptionManager.getQuestion()) ?: ""
                                if (question.isBlank()) {
                                    error = "没有设置密保问题"
                                } else {
                                    forgotAnswer = ""; forgotNew = ""; error = null; step = 3
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
                    ) { Text("忘记密码", style = MemoreType.meta, color = MaterialTheme.colorScheme.primary) }
                }

                // ---------- 操作面板 ----------
                1 -> {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        if (EncryptionManager.isV5()) "$lockedCount 条记录已加密"
                        else "$lockedCount 条记录已加密",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 10.dp)
                    )
                    // 加密保护（v3 概念：新记录要不要自动加密）；v5 下语义待定，先不显示
                    if (!EncryptionManager.isV5()) {
                        Row(Modifier.fillMaxWidth().height(52.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("加密保护", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                            Spacer(Modifier.weight(1f))
                            Switch(
                                checked = toggleEnabled,
                                onCheckedChange = { value ->
                                    scope.launch { EncryptionManager.setEnabled(value); toggleEnabled = value }
                                },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = MaterialTheme.colorScheme.primary,
                                    checkedTrackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
                                )
                            )
                        }
                    }
                    Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f)))
                    Text(
                        "操作",
                        style = MemoreType.micro,
                        letterSpacing = 1.1.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 14.dp, bottom = 2.dp)
                    )
                    Row(
                        Modifier.fillMaxWidth().height(52.dp).clickable {
                            chOld = gatePw; chNew = ""; chQ = ""; chA = ""; error = null; step = 2
                        },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("修改密码", style = MemoreType.body, color = MaterialTheme.colorScheme.onSurface)
                        Spacer(Modifier.weight(1f))
                        Icon(Icons.Outlined.KeyboardArrowRight, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }

                    Text(
                        "危险",
                        style = MemoreType.micro,
                        letterSpacing = 1.1.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 20.dp, bottom = 6.dp)
                    )
                    Box(
                        Modifier.fillMaxWidth().height(46.dp)
                            .clip(MirrorShapes.card)
                            .background(MaterialTheme.colorScheme.error.copy(alpha = 0.08f))
                            .clickable {
                                scope.launch {
                                    if (EncryptionManager.isV5()) {
                                        recordRepository?.clearAllLocked()
                                            ?.onSuccess { n ->
                                                lockedCount = recordRepository?.lockedRecordCount() ?: 0
                                                context.memoreToast("已取消 $n 条记录的加密状态", Toast.LENGTH_SHORT)
                                                UiSoundPlayer.playDelete(context)
                                            }
                                            ?.onFailure { error = it.message }
                                    } else {
                                        val r = recordRepository?.clearEncryption(gatePw)
                                        if (r != null && r.isSuccess) {
                                            EncryptionManager.deleteConfig()
                                            context.memoreToast("加密已清除", Toast.LENGTH_SHORT)
                                            UiSoundPlayer.playDelete(context)
                                            onBack()
                                        } else {
                                            error = r?.exceptionOrNull()?.message ?: "清除失败"
                                        }
                                    }
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) { Text("清除加密", style = MemoreType.meta, color = MaterialTheme.colorScheme.error) }
                    error?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(it, style = MemoreType.meta, color = MaterialTheme.colorScheme.error)
                    }
                }

                // ---------- 修改密码 ----------
                2 -> {
                    val chRules = EncryptionRules.of(chNew)
                    Text("身份", style = MemoreType.micro, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
                    MirrorLineField(value = chOld, onValueChange = { chOld = it; error = null }, placeholder = "当前密码", password = true)
                    Text("新凭证", style = MemoreType.micro, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 22.dp))
                    MirrorLineField(value = chNew, onValueChange = { chNew = it; error = null }, placeholder = "新密码", password = true)
                    // 与「设置密码」同一套强度条与规则点（用户：忘记密码按设置页的设计；修改密码同理）
                    EncryptionStrengthBar(chRules.passed)
                    EncryptionRuleDots(chRules)
                    Text("密保", style = MemoreType.micro, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 22.dp))
                    MirrorLineField(value = chQ, onValueChange = { chQ = it; error = null }, placeholder = "新密保问题")
                    MirrorLineField(value = chA, onValueChange = { chA = it; error = null }, placeholder = "新密保答案")
                    if (willUpgrade) {
                        Text(
                            "这台设备的加密还没升级；本次操作会顺带做一次不可逆升级，之后只能靠备份还原。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 12.dp)
                        )
                    }
                    error?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp))
                    }
                    Spacer(Modifier.height(16.dp))
                    Button(
                        onClick = {
                            if (EncryptionManager.isV5()) {
                                if (chRules.passed < 4) { error = "还差 ${4 - chRules.passed} 条规则"; return@Button }
                                scope.launch {
                                    EncryptionManager.changeLockPassword(chOld, chNew, chQ, chA)
                                        .onSuccess {
                                            UiSoundPlayer.playSave(context)
                                            context.memoreToast("密码已更新", Toast.LENGTH_SHORT)
                                            gatePw = chNew; step = 1
                                        }
                                        .onFailure { error = it.message }
                                }
                                return@Button
                            }
                            if (chNew.length < 8) { error = "新密码长度不足 8 位"; return@Button }
                            scope.launch {
                                val r = recordRepository?.changeEncryptionPassword(chOld, chNew, chQ, chA)
                                if (r != null && r.isSuccess) {
                                    UiSoundPlayer.playSave(context)
                                    context.memoreToast("密码已更新", Toast.LENGTH_SHORT)
                                    gatePw = chNew; step = 1
                                } else {
                                    error = r?.exceptionOrNull()?.message ?: "操作失败"
                                }
                            }
                        },
                        enabled = if (EncryptionManager.isV5()) chRules.passed >= 4 else chNew.length >= 8,
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        shape = MirrorShapes.card,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                    ) { Text("确认修改", style = MemoreType.button, color = MaterialTheme.colorScheme.onPrimary) }
                    Spacer(Modifier.height(24.dp))
                }

                // ---------- 重置密码（密保答案 + 新密码；按「设置密码」那页的设计） ----------
                else -> {
                    // 密保问题（原样展示，不可改）
                    Text(
                        "密保",
                        style = MemoreType.micro,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 10.dp, bottom = 4.dp)
                    )
                    Text(
                        question,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )
                    MemoreLineInput(
                        value = forgotAnswer,
                        onValueChange = { forgotAnswer = it; error = null },
                        placeholder = { Text("密保答案") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(Modifier.height(18.dp))
                    // 新密码 + 与设置页完全相同的强度条与规则点
                    MemoreLineInput(
                        value = forgotNew,
                        onValueChange = { forgotNew = it; error = null },
                        placeholder = { Text("新密码") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    val rules = EncryptionRules.of(forgotNew)
                    EncryptionStrengthBar(rules.passed)
                    EncryptionRuleDots(rules)

                    error?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(it, style = MemoreType.meta, color = MaterialTheme.colorScheme.error)
                    }
                    Spacer(Modifier.height(16.dp))
                    Button(
                        onClick = {
                            if (forgotAnswer.isBlank()) { error = "密保答案不能为空"; return@Button }
                            if (EncryptionManager.isV5() && rules.passed < 4) {
                                error = "还差 ${4 - rules.passed} 条规则"; return@Button
                            }
                            scope.launch {
                                val r = if (EncryptionManager.isV5()) {
                                    EncryptionManager.resetLockPasswordByAnswer(forgotAnswer.trim(), forgotNew).map { 0 }
                                } else {
                                    recordRepository?.resetEncryptionByAnswer(forgotAnswer.trim(), forgotNew)
                                        ?: Result.failure(Exception("操作失败"))
                                }
                                if (r.isSuccess) {
                                    UiSoundPlayer.playSave(context)
                                    context.memoreToast("密码已重置", Toast.LENGTH_SHORT)
                                    gatePw = forgotNew; step = 1
                                } else {
                                    error = r.exceptionOrNull()?.message ?: "操作失败"
                                }
                            }
                        },
                        enabled = forgotAnswer.isNotBlank() &&
                            (!EncryptionManager.isV5() || EncryptionRules.of(forgotNew).passed >= 4),
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        shape = MirrorShapes.card,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                    ) { Text("确认重置", style = MemoreType.button, color = MaterialTheme.colorScheme.onPrimary) }
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }
}


