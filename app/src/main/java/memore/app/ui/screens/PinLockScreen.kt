package memore.app.ui.screens

import androidx.compose.foundation.background
import memore.app.ui.components.MemoreLineInput
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import memore.app.utils.AuthManager
import kotlinx.coroutines.delay

/**
 * 解锁（2026-10-24 改 P4）：和"设置 PIN"、"输入主密码"同一族——一条横线 + 系统数字键盘。
 * PIN 直接显示（4 位数字不怕看），密保答案则遮蔽。
 */
@Composable
fun PinLockScreen(onUnlock: () -> Unit) {
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var forgotStep by remember { mutableStateOf(false) }
    var forgotAnswer by remember { mutableStateOf("") }
    val lockTimer = remember { mutableStateOf(0L) }
    val focus = remember { FocusRequester() }

    LaunchedEffect(lockTimer.value) {
        if (lockTimer.value > 0) { delay(1000); lockTimer.value = AuthManager.getRemainingLockMs() }
    }
    LaunchedEffect(forgotStep) { runCatching { focus.requestFocus() } }

    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        if (forgotStep) {
            Text("安全问题验证", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(8.dp))
            Text(
                AuthManager.getQuestion() ?: "未设置",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(28.dp))
            UnderlineField(
                value = forgotAnswer,
                onValueChange = { forgotAnswer = it; error = null },
                masked = true,
                placeholder = "答案",
                focus = focus
            )
            Spacer(Modifier.height(20.dp))
            Text(
                "验证",
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable {
                    if (AuthManager.verifyAnswer(forgotAnswer.trim())) { AuthManager.clear(); onUnlock() }
                    else error = "答案错误，请重试"
                }.padding(horizontal = 16.dp, vertical = 8.dp)
            )
            error?.let {
                Spacer(Modifier.height(10.dp))
                Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.height(24.dp))
            Text(
                "返回",
                fontSize = 12.5.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.clickable { forgotStep = false; error = null }.padding(8.dp)
            )
        } else {
            Text("输入 PIN", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(8.dp))
            Text("4 位数字", fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(28.dp))

            if (AuthManager.isLockedOut()) {
                Text(
                    "尝试次数过多，请 ${(AuthManager.getRemainingLockMs() + 999) / 1000} 秒后重试",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.error
                )
                Spacer(Modifier.height(14.dp))
            }

            UnderlineField(
                value = pin,
                onValueChange = { raw ->
                    val f = raw.filter { it.isDigit() }.take(4)
                    pin = f
                    if (f.length == 4) {
                        if (AuthManager.isLockedOut()) {
                            error = "已锁定，请稍后重试"; pin = ""; lockTimer.value = AuthManager.getRemainingLockMs()
                        } else if (AuthManager.verifyPin(f)) {
                            onUnlock()
                        } else {
                            error = "PIN 错误，请重试"; pin = ""
                        }
                    } else error = null
                },
                masked = false,
                placeholder = "••••",
                focus = focus
            )

            error?.let {
                Spacer(Modifier.height(12.dp))
                Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
            }

            if (AuthManager.hasPin()) {
                Spacer(Modifier.height(28.dp))
                Text(
                    "忘记 PIN？",
                    fontSize = 12.5.sp,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable { forgotStep = true; error = null }.padding(8.dp)
                )
            }
        }
    }
}

/** 一条横线的输入框（和加密密码、设置 PIN 同一套）。 */
@Composable
private fun UnderlineField(
    value: String,
    onValueChange: (String) -> Unit,
    masked: Boolean,
    placeholder: String,
    focus: FocusRequester
) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = TextStyle(
            fontSize = 22.sp,
            letterSpacing = if (masked) 2.sp else 10.sp,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurface
        ),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        visualTransformation = if (masked) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
        modifier = Modifier.width(200.dp).focusRequester(focus),
        decorationBox = { inner ->
            Column(Modifier.fillMaxWidth()) {
                Box(Modifier.fillMaxWidth().height(52.dp), contentAlignment = Alignment.Center) {
                    if (value.isEmpty()) {
                        Text(
                            placeholder,
                            fontSize = 22.sp,
                            letterSpacing = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.25f)
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
}
