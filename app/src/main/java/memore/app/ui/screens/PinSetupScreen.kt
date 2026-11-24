package memore.app.ui.screens

import androidx.compose.foundation.background
import memore.app.ui.components.MemoreLineInput
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
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

/**
 * 设置 PIN（2026-10-24 改 P4）：不再用 240dp 大圆圈，
 * 改成和"输入主密码"同一族的一条横线 + 系统数字键盘。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PinSetupScreen(onComplete: () -> Unit, onBack: () -> Unit) {
    var step by remember { mutableStateOf(1) }
    var pin1 by remember { mutableStateOf("") }
    var pin2 by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val focus = remember { FocusRequester() }
    val current = if (step == 1) pin1 else pin2

    fun onValue(raw: String) {
        val f = raw.filter { it.isDigit() }.take(4)
        if (step == 1) pin1 = f else pin2 = f
        if (f.length == 4) {
            if (step == 1) {
                pin1 = f; step = 2; error = null; pin2 = ""
            } else if (f == pin1) {
                AuthManager.setPin(f); onComplete()
            } else {
                error = "PIN 不一致，请重新输入"; step = 1; pin1 = ""; pin2 = ""
            }
        } else {
            error = null
        }
    }

    LaunchedEffect(step) { runCatching { focus.requestFocus() } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(48.dp))
            Text(
                if (step == 1) "设置 PIN" else "再输一次",
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(6.dp))
            Text(
                if (step == 1) "4 位数字" else "和刚才那次保持一致",
                fontSize = 11.5.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(36.dp))

            // 横线输入（PIN 直接显示，看得见才不容易错）
            BasicTextField(
                value = current,
                onValueChange = { onValue(it) },
                singleLine = true,
                textStyle = TextStyle(
                    fontSize = 22.sp,
                    letterSpacing = 10.sp,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurface
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                visualTransformation = VisualTransformation.None,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.NumberPassword,
                    imeAction = ImeAction.Done
                ),
                modifier = Modifier.width(200.dp).focusRequester(focus),
                decorationBox = { inner ->
                    Column(Modifier.fillMaxWidth()) {
                        Box(
                            Modifier.fillMaxWidth().height(52.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            if (current.isEmpty()) {
                                Text(
                                    "••••",
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

            error?.let {
                Spacer(Modifier.height(12.dp))
                Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
            }

            Spacer(Modifier.weight(1f))
            Text(
                "第 $step 步 / 共 2 步",
                fontSize = 10.5.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 32.dp)
            )
        }
    }
}
