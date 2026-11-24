package memore.app.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * 列表右缘那条**时隐时现**的滚动条（用户 2026-10-11 的要求：
 * 「给时间线最右边加个时隐时现不太明显的进度条，这样好下滑和回到顶上，就像常见的」）。
 *
 * 规矩：
 * - **平常看不见**：滑动 / 拖动时淡入，停手 1 秒后淡出（不抢内容的注意力）；
 * - **能拖**：按住手指往下拖 = 按比例快速下滑，往上拖回顶 = 回到顶上（这是它存在的理由，
 *   不只是装饰）；点轨道空白处也能跳到那一段；
 * - 触摸区 26dp、可见的条只有 3dp —— 条贴在屏幕最右缘，压不到卡片上的内容。
 *
 * 位置换算用**条目数**近似（列表条目高矮不一，做不到像素精确，也不需要）：
 * 拇指高度 ＝ 可见条数 / 总条数，拇指位置 ＝ 当前滚动进度。
 */
@Composable
fun FastScrollBar(
    state: LazyListState,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.primary,
    touchWidth: Dp = 20.dp,
    thumbWidth: Dp = 3.dp
) {
    val info = state.layoutInfo
    val total = info.totalItemsCount
    val visible = info.visibleItemsInfo
    if (total <= 0 || visible.isEmpty()) return

    // 可见性：任何触摸 / 滑动都算"亮一下"，最后一次动作之后 1 秒淡出
    var poke by remember { mutableStateOf(0) }
    var hidden by remember { mutableStateOf(true) }
    LaunchedEffect(poke, state.isScrollInProgress) {
        if (poke > 0 || state.isScrollInProgress) {
            hidden = false
            delay(1000)
            hidden = true
        }
    }
    val alpha by animateFloatAsState(if (hidden) 0f else 1f, label = "scrollBarAlpha")

    BoxWithConstraints(modifier.fillMaxHeight().width(touchWidth)) {
        val density = LocalDensity.current
        val minThumbPx = with(density) { 28.dp.toPx() }
        val first = visible.first()
        val itemPx = first.size.coerceAtLeast(1).toFloat()
        // 当前滚动位置（条目为单位，含条目内的比例）
        val scrolled = first.index + (-first.offset / itemPx).coerceIn(0f, 1f)
        val pageSpan = (total - visible.size).coerceAtLeast(1)
        val thumbH = (constraints.maxHeight * scrollThumbFraction(total, visible.size))
            .coerceAtLeast(minThumbPx)
            .coerceAtMost(constraints.maxHeight.toFloat())
        val thumbHdp = with(density) { thumbH.toDp() }
        val maxTop = (constraints.maxHeight - thumbH).coerceAtLeast(0f)
        val top = if (maxTop <= 0f) 0f else (scrolled / pageSpan).coerceIn(0f, 1f) * maxTop

        // 手势回调里要读**最新**的几何值（用 SideEffect 同步，避免手势里拿到上一帧的旧值）
        val maxTopState = remember { mutableStateOf(0f) }
        val visibleCountState = remember { mutableStateOf(visible.size) }
        val thumbState = remember { mutableStateOf(0f) }
        SideEffect {
            maxTopState.value = maxTop
            visibleCountState.value = visible.size
            thumbState.value = thumbH
        }

        var dragTop by remember { mutableStateOf(0f) }
        var target by remember { mutableStateOf(-1) }
        LaunchedEffect(target) { if (target >= 0) state.scrollToItem(target) }

        fun jumpTo(topPx: Float) {
            val mt = maxTopState.value
            if (mt <= 0f) return
            target = scrollTargetIndex(topPx / mt, total, visibleCountState.value)
        }

        // 可见的条本身（Touch 区在下面的透明层上，条只是画出来）
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .offset { IntOffset(0, top.roundToInt()) }
                .width(thumbWidth)
                .height(thumbHdp)
                .alpha(alpha)
                .background(tint.copy(alpha = 0.55f), MirrorShapes.pill)
        )

        // 手势层：整条触摸区都可拖可点
        Box(
            Modifier
                .matchParentSize()
                .pointerInput(total) {
                    detectVerticalDragGestures(
                        onDragStart = { offset ->
                            poke++
                            dragTop = (offset.y - thumbState.value / 2f).coerceIn(0f, maxTopState.value)
                            jumpTo(dragTop)
                        },
                        onDragEnd = { poke++ },
                        onDragCancel = { poke++ }
                    ) { change, delta ->
                        change.consume()
                        poke++
                        dragTop = (dragTop + delta).coerceIn(0f, maxTopState.value)
                        jumpTo(dragTop)
                    }
                }
                .pointerInput(total) {
                    detectTapGestures { pos ->
                        poke++
                        jumpTo(pos.y - thumbState.value / 2f)
                    }
                }
        )
    }
}

/**
 * 拇指高度占轨道的比例 = 可见条数 / 总条数（纯函数，可测）。
 * 总数为 0 时给 1（没有列表 = 条占满，等于不显示）。
 */
fun scrollThumbFraction(total: Int, visibleCount: Int): Float {
    if (total <= 0) return 1f
    return (visibleCount.toFloat() / total).coerceIn(0f, 1f)
}

/**
 * 把轨道上的位置（0..1）换算成"该跳到第几条"（纯函数，可测）。
 *
 * 两条不变量（就是用户要的"好下滑和回到顶上"）：
 * - 拖到最上（0）→ 必定回到第 0 条 = 回到顶上；
 * - 拖到最下（1）→ 必定停在最后一屏（total - visibleCount），不是越过末尾。
 */
fun scrollTargetIndex(trackFraction: Float, total: Int, visibleCount: Int): Int {
    if (total <= 0) return 0
    val span = (total - visibleCount).coerceAtLeast(0)
    if (span == 0) return 0
    return (trackFraction.coerceIn(0f, 1f) * span).roundToInt().coerceIn(0, total - 1)
}
