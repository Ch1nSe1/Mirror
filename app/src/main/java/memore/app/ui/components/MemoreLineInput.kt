package memore.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import memore.app.ui.theme.MemoreType

/**
 * 项目自己的输入框（2026-11-14）。
 *
 * 法条 4.5 / 11.12② 规定：**输入只有一种——横线**，无底色、无圆角、无四边框。
 * 所以这里不再用 M3 的 `OutlinedTextField` / `TextField`，而是保留同名参数、
 * 换掉外观：列名（可选）+ 输入区 + 1dp 底线。
 *
 * 参数集合按项目里实际用到的收：value / onValueChange / label / placeholder /
 * leadingIcon / visualTransformation / keyboardOptions / singleLine / enabled / readOnly / isError。
 * 传进来的 `shape`、`colors` 一律忽略——那两样正是要废掉的东西。
 */
@Composable
fun MemoreLineInput(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    @Suppress("UNUSED_PARAMETER") label: (@Composable () -> Unit)? = null,
    placeholder: (@Composable () -> Unit)? = null,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    singleLine: Boolean = true,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    isError: Boolean = false,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    textStyle: TextStyle = MemoreType.body,
    shape: Shape? = null,
    colors: Any? = null
) {
    // 行高最小 48dp：文字底边贴着那条线，中间不留空白（2026-11-14）
    // 2026-11-16：height → heightIn(min)。写死 height 会把调用方传进来的 .weight(1f) 压成 48dp，
    // 于是"多行编辑器"这种用法会变成一条 48dp 的细线，剩下的空间全空着（提示词管理真机上就是这样）。
    Box(
        modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().align(Alignment.BottomStart).padding(bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            leadingIcon?.let {
                Box(Modifier.padding(end = 8.dp)) { it() }
            }
            Box(Modifier.weight(1f)) {
                // 2026-11-16：`placeholder` 这个参数一直存在、调用方也在传，但这个组件**从来没渲染过它**
                // （注释写着「空态提示一律不画」）。于是设置加密的密保、锁定面板、AI 配置那些
                // 只有一条横线、看不出该填什么 —— 真机截图里就是三条空白线。
                // 现在：空值且给了 placeholder 就画出来，其余布局不变。
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    enabled = enabled,
                    readOnly = readOnly,
                    singleLine = singleLine,
                    maxLines = maxLines,
                    textStyle = textStyle.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    visualTransformation = visualTransformation,
                    keyboardOptions = keyboardOptions,
                    modifier = Modifier.fillMaxWidth(),
                    decorationBox = { inner ->
                        Box(Modifier.fillMaxWidth()) {
                            if (value.isEmpty() && placeholder != null) {
                                // 2026-11-16：提示必须是灰的（老组件口径 onSurfaceVariant α0.35~0.40）。
                                // 上一版直接 invoke → Text 吃到 onSurface，提示和输入同色，像"已填好"。
                                androidx.compose.runtime.CompositionLocalProvider(
                                    androidx.compose.material3.LocalContentColor provides
                                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.40f)
                                ) {
                                    Box(Modifier.align(Alignment.CenterStart)) { placeholder.invoke() }
                                }
                            }
                            inner()
                        }
                    }
                )
            }
            trailingIcon?.let {
                Box(Modifier.padding(start = 8.dp)) { it() }
            }
        }
        // 底线：与文字同左缘，右端就是控件右缘
        Box(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .height(1.dp)
                .background(
                    if (isError) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f)
                )
        )
    }
}

