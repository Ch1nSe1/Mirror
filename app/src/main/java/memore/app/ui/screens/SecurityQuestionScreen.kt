package memore.app.ui.screens

import memore.app.ui.components.MirrorShapes

import androidx.compose.material.icons.Icons
import memore.app.ui.components.MemoreLineInput
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import memore.app.utils.AuthManager
import memore.app.utils.UiSoundPlayer
import memore.app.utils.memoreToast

/**
 * 设置安全问题（2026-10-24 改）：与"设置 PIN / 输入主密码"统一成**横线输入**，
 * 不再是两个描边方框 + 一颗紫色大按钮。
 * 这个界面在三处被复用（设 PIN 后、应用锁重置后、加密设置向导）。
 */
@Composable
fun SecurityQuestionScreen(onComplete: () -> Unit, onBack: () -> Unit = {}) {
    val context = LocalContext.current
    var question by remember { mutableStateOf("") }
    var answer by remember { mutableStateOf("") }

    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding().padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回")
            }
        }
        Text("设置安全问题", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.height(6.dp))
        Text("忘记 PIN 时用于验证身份", fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(36.dp))

        UnderlineInput(
            value = question,
            onValueChange = { question = it },
            placeholder = "问题",
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next)
        )
        Spacer(Modifier.height(26.dp))
        UnderlineInput(
            value = answer,
            onValueChange = { answer = it },
            placeholder = "答案",
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done)
        )

        Spacer(Modifier.weight(1f))
        Button(
            onClick = {
                if (question.isNotBlank() && answer.isNotBlank()) {
                    AuthManager.setSecurityQuestion(question.trim(), answer.trim())
                    context.memoreToast("应用锁已开启", Toast.LENGTH_SHORT)
                    UiSoundPlayer.playLockOn(context)
                    onComplete()
                }
            },
            enabled = question.isNotBlank() && answer.isNotBlank(),
            modifier = Modifier.fillMaxWidth().height(48.dp),
            shape = MirrorShapes.card,
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
        ) {
            Text("完成", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onPrimary)
        }
        Spacer(Modifier.height(28.dp))
    }
}

/** 一条横线的输入框（和 PIN / 主密码同一族）。 */
@Composable
private fun UnderlineInput(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default
) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = TextStyle(
            fontSize = 15.sp,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurface
        ),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        keyboardOptions = keyboardOptions,
        modifier = Modifier.fillMaxWidth(),
        decorationBox = { inner ->
            Column(Modifier.fillMaxWidth()) {
                Box(Modifier.fillMaxWidth().height(46.dp), contentAlignment = Alignment.Center) {
                    if (value.isEmpty()) {
                        Text(
                            placeholder,
                            fontSize = 15.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
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


