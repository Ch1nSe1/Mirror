package memore.app.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 加载指示器（2026-11-05，核心通用组件）。
 *
 * 形态：**两簇竖条**（左 4 右 3，中间留一道空隙），高度按次序起伏，像一段安静的波形。
 * 为什么不是转圈：转圈和这套纸面语言不搭；条状和"正在写字"更接近。
 *
 * 所有"需要等数据"的界面都应该用它，**替代该页面的空白状态**——先出波形，再出内容，
 * 而不是先空白一下再突然冒出内容。
 *
 * 要改样式只改 [LoadingStyle]：数量、宽高、节奏、颜色都在那里。
 */
object LoadingStyle {
    /** 左簇条数 */
    val leftBars = 4
    /** 右簇条数 */
    val rightBars = 3
    /** 条宽 */
    val barWidth = 3.5.dp
    /** 条间距 */
    val barGap = 5.dp
    /** 两簇之间的空隙 */
    val clusterGap = 12.dp
    /** 最矮 / 最高 */
    val minHeight = 8.dp
    val maxHeight = 20.dp
    /** 单条起伏一次的时间 */
    val durationMs = 620
    /** 相邻条之间的相位差 */
    val staggerMs = 110
    /** 条的颜色透明度上限 */
    const val barAlpha = 0.55f
    /** 上下留白 */
    val verticalPadding = 34.dp
}

@Composable
fun MirrorLoading(
    modifier: Modifier = Modifier,
    dotSize: Dp = 0.dp // 旧参数，保留避免调用点全改；已不使用
) {
    val transition = rememberInfiniteTransition(label = "loading-bars")
    val bars = LoadingStyle.leftBars + LoadingStyle.rightBars
    // 波形：中间高、两边低（左簇呈 ↗，右簇呈 ↘）
    val shape = List(bars) { i ->
        val center = (bars - 1) / 2f
        val d = kotlin.math.abs(i - center) / center
        (1f - d * 0.55f).coerceIn(0.55f, 1f)
    }

    Row(
        modifier.fillMaxWidth().padding(vertical = LoadingStyle.verticalPadding),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(bars) { i ->
            if (i == LoadingStyle.leftBars) Spacer(Modifier.width(LoadingStyle.clusterGap))
            else if (i > 0) Spacer(Modifier.width(LoadingStyle.barGap))

            val phase by transition.animateFloat(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(LoadingStyle.durationMs, delayMillis = i * LoadingStyle.staggerMs, easing = LinearEasing),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "bar$i"
            )
            val minH = LoadingStyle.minHeight.value
            val maxH = LoadingStyle.maxHeight.value * shape[i]
            val h = minH + (maxH - minH) * phase
            Box(
                Modifier
                    .width(LoadingStyle.barWidth)
                    .height(h.dp)
                    .clip(RoundedCornerShape(LoadingStyle.barWidth / 2))
                    .background(
                        MaterialTheme.colorScheme.primary.copy(
                            alpha = (LoadingStyle.barAlpha * (0.45f + 0.55f * phase)).coerceIn(0.15f, LoadingStyle.barAlpha)
                        )
                    )
            )
        }
    }
}
