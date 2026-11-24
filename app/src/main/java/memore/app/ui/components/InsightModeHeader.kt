package memore.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import memore.app.ui.theme.MemoreType

/** 洞察四视角；三种触发共用同一套提示词。 */
val INSIGHT_MODES = listOf("隐忧", "动机", "在意", "反证")

/**
 * 洞察弹窗的视角控件（2026-11-17 第二版）。
 *
 * 位置：**右端**，与标题同一行高度——不是正文上方居中。
 * 原因：居中放在标题下时，它和「洞察」形成两个同等重量的标题；
 * 而且菜单从中间往下弹会盖住正文和按钮（上一版真机图里取消/保存都被盖住）。
 *
 * 记号：meta 大小的「隐忧 ⌄」文字控件；点它出菜单，菜单锚在它身上（4.9）。
 */
@Composable
fun InsightModeHeader(
    mode: String,
    onModeChange: (String) -> Unit
) {
    val ctx = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        MirrorAnchoredMenu(
            expanded = menu,
            onDismissRequest = { menu = false },
            actions = INSIGHT_MODES.map { m ->
                MirrorMenuAction(m, selected = m == mode) {
                    menu = false
                    if (m != mode) {
                        ctx.getSharedPreferences("mirror_prefs", android.content.Context.MODE_PRIVATE)
                            .edit().putString("insight_mode", m).apply()
                        onModeChange(m)
                    }
                }
            }
        ) {
            // 2026-11-22：字居中（和弹窗标题一条中线），箭头仍钉在右端原位；两处都可点开菜单。
            Box(Modifier.fillMaxWidth()) {
                Box(
                    Modifier.align(Alignment.Center).clickable { menu = true }
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(mode, style = MemoreType.meta, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Box(
                    Modifier.align(Alignment.CenterEnd).clickable { menu = true }
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        "⌄",
                        style = MemoreType.micro,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }
            }
        }
    }
}

/** 读上次用的视角。 */
@Composable
fun rememberInsightMode(): MutableState<String> {
    val ctx = LocalContext.current
    return remember {
        mutableStateOf(
            ctx.getSharedPreferences("mirror_prefs", android.content.Context.MODE_PRIVATE)
                .getString("insight_mode", "隐忧") ?: "隐忧"
        )
    }
}
