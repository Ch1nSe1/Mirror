package memore.app.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import memore.app.data.repository.RecordRepository
import memore.app.ui.components.IconBack
import memore.app.ui.components.MemoreLineInput
import memore.app.ui.components.MemorePage
import memore.app.ui.components.MirrorShapes
import memore.app.ui.theme.MemoreType
import memore.app.utils.EncryptionManager
import memore.app.utils.memoreToast
import kotlinx.coroutines.launch

/**
 * 设置加密（2026-10-23 由三步向导合并成一页；2026-11-16 抽成独立路由屏）。
 *
 * 2026-11-16 两处改动：
 *  1. 去掉 Scaffold + TopAppBar，改成普通 Column + 自绘 56dp 顶栏。
 *     真机上"点输入框、键盘弹出"明显慢 —— Scaffold 在 IME 弹起时要重算 insets/measure
 *     整页，这一页是长表单，代价最明显。
 *  2. 强度条/规则点抽成 [EncryptionStrengthBar] / [EncryptionRuleDots]，
 *     忘记密码页复用同一套设计（用户要求：忘记密码按设置密码的设计做）。
 */
@Composable
fun EncryptionSetupScreen(
    recordRepository: RecordRepository?,
    onComplete: () -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var password by remember { mutableStateOf("") }
    var question by remember { mutableStateOf("") }
    var answer by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var working by remember { mutableStateOf(false) }

    val rules = EncryptionRules.of(password)
    val strongEnough = rules.passed >= 4   // 用户口径：五条满足四条即可，五条更好
    val canSubmit = strongEnough && question.isNotBlank() && answer.isNotBlank() && !working

    // 法 铁律 2：屏框走共享 MemorePage（64dp 顶栏，返回/标题/inset 一次做对）。
    // 上一版自绘 56dp 顶栏——那是在"键盘弹出慢"猜错方向时顺手抄的默认值。
    MemorePage(title = "设置加密", onBack = onBack) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding)
                .verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)
        ) {
            Spacer(Modifier.height(4.dp))

            // ---- 主密码 ----
            MemoreLineInput(
                value = password,
                onValueChange = { password = it; error = null },
                singleLine = true,
                visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Next
                ),
                placeholder = { Text("主密码") },
                trailingIcon = {
                    Text(
                        if (visible) "隐藏" else "显示",
                        style = MemoreType.meta,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .clip(MirrorShapes.row)
                            .clickable { visible = !visible }
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                },
                modifier = Modifier.fillMaxWidth()
            )

            EncryptionStrengthBar(rules.passed)
            EncryptionRuleDots(rules)

            // ---- 密保 ----
            Spacer(Modifier.height(20.dp))
            Text(
                "密保",
                style = MemoreType.micro,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 6.dp)
            )
            MemoreLineInput(
                value = question,
                onValueChange = { question = it; error = null },
                singleLine = true,
                placeholder = { Text("密保问题") },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(10.dp))
            MemoreLineInput(
                value = answer,
                onValueChange = { answer = it; error = null },
                singleLine = true,
                placeholder = { Text("密保答案") },
                modifier = Modifier.fillMaxWidth()
            )

            error?.let {
                Spacer(Modifier.height(10.dp))
                Text(it, style = MemoreType.meta, color = MaterialTheme.colorScheme.error)
            }

            Spacer(Modifier.height(20.dp))
            Button(
                onClick = {
                    if (!strongEnough) { error = "还差 ${4 - rules.passed} 条规则"; return@Button }
                    if (question.isBlank() || answer.isBlank()) { error = "密保问题和答案都要填"; return@Button }
                    working = true
                    scope.launch {
                        try {
                            // 本机若存在真正依赖密钥的密文，绝不允许重设密码（会造新钥匙废掉旧密文）
                            val trulyEncrypted = try {
                                recordRepository?.hasTrulyEncryptedData() == true
                            } catch (_: Exception) { true }
                            if (trulyEncrypted) {
                                context.memoreToast("本机已有加密记录，不能重新设置密码。请用原密码走「修改密码」，或先「清除加密」", Toast.LENGTH_LONG)
                                working = false
                                return@launch
                            }
                            // 2026-11-16（v5 门闩式）：写 v5 verifier（密码 + 密保一起存）
                            val setRes = EncryptionManager.setLockPassword(password, question.trim(), answer.trim())
                            if (setRes.isFailure) {
                                context.memoreToast(setRes.exceptionOrNull()?.message ?: "设置失败", Toast.LENGTH_LONG)
                                working = false
                                return@launch
                            }
                            context.memoreToast("加密保护已开启", Toast.LENGTH_SHORT)
                            onComplete()
                        } catch (e: Exception) {
                            context.memoreToast("设置失败: ${e.message}", Toast.LENGTH_SHORT)
                            working = false
                        }
                    }
                },
                enabled = canSubmit,
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = MirrorShapes.card,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
            ) {
                Text(
                    if (working) "设置中…" else "启用加密",
                    style = MemoreType.button,
                    color = MaterialTheme.colorScheme.onPrimary
                )
            }
            Spacer(Modifier.height(28.dp))
        }
    }
}

/** 密码规则判定（设置页与忘记密码页共用一份，避免两边标准漂移）。 */
internal data class EncryptionRules(
    val len: Boolean,
    val upper: Boolean,
    val lower: Boolean,
    val digit: Boolean,
    val special: Boolean
) {
    val passed: Int get() = listOf(len, upper, lower, digit, special).count { it }
    companion object {
        fun of(password: String) = EncryptionRules(
            len = password.length >= 8,
            upper = password.any { it.isUpperCase() },
            lower = password.any { it.isLowerCase() },
            digit = password.any { it.isDigit() },
            special = password.any { !it.isLetterOrDigit() }
        )
    }
}

/** 五格强度条（按已通过规则数点亮：≤2 红、3~4 黄、5 绿）。 */
@Composable
internal fun EncryptionStrengthBar(passed: Int) {
    Spacer(Modifier.height(8.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        repeat(5) { i ->
            Box(
                Modifier.weight(1f).height(3.dp).clip(MirrorShapes.pill).background(
                    when {
                        i >= passed -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.09f)
                        passed <= 2 -> Color(0xFFB4635A)
                        passed <= 4 -> Color(0xFFC9A227)
                        else -> Color(0xFF6FAE8F)
                    }
                )
            )
        }
    }
}

/** 五条规则的圆点行。 */
@Composable
internal fun EncryptionRuleDots(rules: EncryptionRules) {
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        RuleDot("8 位以上", rules.len)
        RuleDot("大写", rules.upper)
        RuleDot("小写", rules.lower)
        RuleDot("数字", rules.digit)
        RuleDot("符号", rules.special)
    }
}

/** 一条密码规则：满足点亮，未满足灰着。 */
@Composable
private fun RuleDot(label: String, ok: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            if (ok) "●" else "○",
            fontSize = 8.sp,
            color = if (ok) Color(0xFF6FAE8F) else MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)
        )
        Spacer(Modifier.width(4.dp))
        Text(
            label,
            style = MemoreType.meta,
            color = if (ok) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}


