package memore.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import memore.app.ui.theme.MemoreType
import kotlinx.coroutines.delay

/**
 * 两格就地浮层（2026-11-15）。
 *
 * 用途：一个按钮下面挂**两个同等权重的去处**，比如时间线的「视图」——
 * 左「图文集」右「区域」。和就地菜单（[MirrorMenu]）的区别是：
 * 菜单是竖排若干行文字，这里是**并排两格、每格图标在上名字在下**。
 *
 * ## 尺寸是算出来的，不是猜的（2026-11-15 实测修正）
 *
 * 内容只占 20dp 图标 + 6dp 缝 + 约 16dp 文字 = 42dp 高。
 * 第一版给 86dp，上下各空 22dp —— 用户原话「太大了，空白问题」，
 * 但**不许靠缩小图标和字号解决**，所以只收面板：**每格 64×60**。
 * 横向：三个汉字 12.5sp（`MemoreType.meta`）约 38dp，64dp 里两侧各 13dp。
 *
 * ## 设计法登记（11.9）
 *
 * | 名字 | 尺寸 | 圆角 | 用在哪 |
 * |---|---|---|---|
 * | 两格浮层 | 每格 64×60，总 129×60 | 12（`MirrorShapes.menu`） | "两个去处"的入口（视图切换等） |
 *
 * 圆角、底色、描边、阴影与就地菜单同一套：`MirrorShapes.menu` / `surfaceContainerHigh` /
 * 1dp α0.10 描边 / 阴影 12dp。
 */
data class MirrorPopoverCell(
    val icon: ImageVector,
    val label: String,
    val onClick: () -> Unit
)

private val CELL_WIDTH = 76.dp
private val CELL_HEIGHT = 68.dp

@Composable
fun MirrorTwoCellPopover(
    cells: List<MirrorPopoverCell>
) {
    if (cells.size != 2) return
    Surface(
        shape = MirrorShapes.menu,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shadowElevation = 12.dp,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f))
    ) {
        Row {
            cells.forEachIndexed { index, cell ->
                if (index > 0) {
                    Box(
                        Modifier.width(1.dp).height(CELL_HEIGHT)
                            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f))
                    )
                }
                Column(
                    Modifier.width(CELL_WIDTH).height(CELL_HEIGHT).clickable(onClick = cell.onClick),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        cell.icon,
                        contentDescription = cell.label,
                        modifier = Modifier.size(22.dp),
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.height(7.dp))
                    Text(
                        cell.label,
                        style = MemoreType.meta,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/**
 * 挂着两格浮层的弹出壳（2026-11-15）。
 *
 * 为什么单独做一个壳：裸 `Popup` 有两个毛病（实测）——
 *   1. **没有开合动效**：`Popup` 一创建就出现、一移除就消失，`AnimatedVisibility` 的退场
 *      根本来不及播（因为 Popup 已经没了）。
 *   2. **返回键不关**：默认不抢焦点，按返回什么都不发生，和项目里其它菜单的手感不一致。
 *
 * 这里的做法：
 *   - `expanded` 变 false 时**先播 90ms 退场动画，再真正卸载**（[keepMounted]）；
 *   - `PopupProperties(focusable = true)`，返回键与外部点击都能关；
 *   - 进场 120ms / 退场 90ms，与项目既有动效同一档（法条 11.6）。
 *
 * @param expanded 是否展开（调用方自己 toggle，就能"再点一次关掉"）
 * @param onDismissRequest 点外部或按返回
 * @param verticalOffset 浮层顶边相对按钮盒顶边的距离
 */
@Composable
fun MirrorAnchoredPopover(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    verticalOffset: Dp,
    content: @Composable () -> Unit
) {
    var keepMounted by remember { mutableStateOf(false) }
    LaunchedEffect(expanded) {
        if (expanded) keepMounted = true
        else {
            delay(90)
            keepMounted = false
        }
    }
    if (!expanded && !keepMounted) return

    val density = LocalDensity.current
    Popup(
        onDismissRequest = onDismissRequest,
        properties = PopupProperties(focusable = true),
        alignment = Alignment.TopEnd,
        offset = IntOffset(0, with(density) { verticalOffset.roundToPx() })
    ) {
        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn(tween(120)) + scaleIn(tween(120), initialScale = 0.94f),
            exit = fadeOut(tween(90)) + scaleOut(tween(90), targetScale = 0.94f)
        ) {
            content()
        }
    }
}
