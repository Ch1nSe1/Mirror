package memore.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import memore.app.data.model.EmotionSeries
import memore.app.data.model.TrendBucket
import memore.app.data.model.segments
import java.util.Calendar

/**
 * 情绪折线图。
 *
 * 三条规矩（都是和用户一起定的）：
 *  ① Y 是**密度**（0..1），所以多条线放在同一刻度上可以直接比"谁在那段时间更占分量"；
 *  ② **实线 = 这段有数据；虚线 = 跨过了没记录的日子** —— 视觉上还是一条连贯的线，
 *     但不会把"没写"误读成"情绪变了"；**没有值的桶本身不落点**（否则线会俯冲到 0）；
 *     按天档的密度是"以这天为中心的窗口"（见 `EmotionSeriesBuilder.smoothingWindowFor`），
 *     所以线拐得平缓、接近走势而不是每天的抖动；
 *  ③ 底部一条**灰色刻度条**标出哪些桶真的有记录，和虚线互为印证。
 */
@Composable
fun EmotionLineChart(
    series: List<EmotionSeries>,
    colors: List<Color>,
    bucket: TrendBucket,
    modifier: Modifier = Modifier,
    /**
     * 紧凑模式（当列表头用）：**去掉 Y 轴刻度文字**（0/0.5/1）、收窄左侧留白。
     * 虚线与底部"有记录"灰条照旧保留 —— 那两样才是这张图的信息量所在。
     */
    compact: Boolean = false,
    /** 当前点选的桶（画一条竖虚线标出来）；null = 没点 */
    focusBucketStart: Long? = null,
    /**
     * 2026-11-22：竖虚线/焦点圆点跟着**主情绪**走，不再默认 series[0]。
     * 主情绪和「显示顺序」已经拆开，如果还取第一条，会拿错颜色、把主情绪线压住。
     */
    focusName: String? = null,
    onPointClick: (bucketStart: Long) -> Unit = {}
) {
    val textMeasurer = rememberTextMeasurer()
    val gridColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)
    val axisTextColor = MaterialTheme.colorScheme.onSurfaceVariant
    val stripColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
    val labelStyle = remember { TextStyle(fontSize = 9.sp) }
    val dash = remember { PathEffect.dashPathEffect(floatArrayOf(7f, 7f)) }
    val leftPad = if (compact) 10.dp else 34.dp

    Canvas(
        modifier = modifier
            .pointerInput(series) {
                detectTapGestures { pos ->
                    val pts = series.firstOrNull()?.points ?: return@detectTapGestures
                    if (pts.isEmpty()) return@detectTapGestures
                    val left = leftPad.toPx()
                    val right = size.width - 8.dp.toPx()
                    val step = (right - left) / pts.size
                    if (step <= 0f) return@detectTapGestures
                    val idx = ((pos.x - left) / step).toInt().coerceIn(0, pts.size - 1)
                    onPointClick(pts[idx].bucketStart)
                }
            }
    ) {
        val left = leftPad.toPx()
        val right = size.width - 8.dp.toPx()
        val top = 8.dp.toPx()
        val bottom = size.height - 22.dp.toPx()      // 给底部"有记录"条 + 日期标签留位置
        val plotW = right - left
        val plotH = bottom - top
        if (plotW <= 0f || plotH <= 0f) return@Canvas

        // ---- Y 网格 + 刻度（0 / 0.5 / 1）----
        if (compact) {
            // 紧凑模式：只留两条淡网格线，不写数字（列表头不该抢戏）
            listOf(0.5f, 1f).forEach { v ->
                val y = bottom - plotH * v
                drawLine(gridColor, Offset(left, y), Offset(right, y), strokeWidth = 1f)
            }
        } else {
            listOf(0f, 0.5f, 1f).forEach { v ->
                val y = bottom - plotH * v
                drawLine(gridColor, Offset(left, y), Offset(right, y), strokeWidth = 1f)
                val label = if (v == 0f) "0" else if (v == 1f) "1" else "0.5"
                val laid = textMeasurer.measure(label, labelStyle)
                drawText(
                    textLayoutResult = laid,
                    topLeft = Offset(left - laid.size.width - 4.dp.toPx(), y - laid.size.height / 2f),
                    color = axisTextColor
                )
            }
        }

        val pts = series.firstOrNull()?.points ?: emptyList()
        if (pts.isEmpty()) return@Canvas
        val step = plotW / pts.size
        fun xOf(i: Int) = left + step * i + step / 2f
        fun yOf(density: Float) = bottom - plotH * density.coerceIn(0f, 1f)

        // ---- 底部"这段有记录"刻度条 ----
        // 注意用的是 hasRecord（这个桶**自己**有没有记录），不是 hasValue：
        // 平滑之后线会在没写的日子上照常连过去，但灰条要老实告诉你那天其实没写。
        pts.forEachIndexed { i, p ->
            if (p.hasRecord) {
                drawRect(
                    color = stripColor,
                    topLeft = Offset(left + step * i + 1f, bottom + 6.dp.toPx()),
                    size = Size((step - 2f).coerceAtLeast(1f), 3.dp.toPx())
                )
            }
        }

        // ---- 焦点那条：先铺一层面积，再画线（面积只在焦点身上）----
        val focusIndex = series.indexOfFirst { it.name == focusName }.let { if (it >= 0) it else 0 }
        val focus = series.getOrNull(focusIndex)
        if (focus != null && colors.isNotEmpty()) {
            val validIdx = focus.points.indices.filter { focus.points[it].hasValue }
            if (validIdx.size >= 2) {
                val area = Path()
                area.moveTo(xOf(validIdx.first()), bottom)
                validIdx.forEach { i -> area.lineTo(xOf(i), yOf(focus.points[i].density)) }
                area.lineTo(xOf(validIdx.last()), bottom)
                area.close()
                // 2026-11-07：去掉面积填充，只留线（列表页头不再抢戏）
                if (false) drawPath(area, color = colors[0].copy(alpha = 0.10f))
            }
        }

        // ---- 折线：只在"有记录"的桶之间连段；夹着空白的段画虚线 ----
        // 注意**空白桶不是折点**：它没有记录、密度只能是 0，若照画线会俯冲到地板
        // 再爬回来（按天看图会变成一堆 V 字）。这里用 segments() 把空白整段跳过去，
        // 由前后两个真点直接连一条虚线 —— 所以线的走向是自然的，只有虚实之分。
        series.forEachIndexed { si, s ->
            val color = colors[si % colors.size]
            s.segments().forEach { seg ->
                drawLine(
                    color = if (seg.crossesGap) color.copy(alpha = 0.55f) else color,
                    start = Offset(xOf(seg.fromIndex), yOf(s.points[seg.fromIndex].density)),
                    end = Offset(xOf(seg.toIndex), yOf(s.points[seg.toIndex].density)),
                    strokeWidth = 2.dp.toPx(),
                    pathEffect = if (seg.crossesGap) dash else null
                )
            }
            // 焦点那条的末端点：给"现在是多少"一个落点
            if (si == focusIndex) {
                val lastValid = s.points.indexOfLast { it.hasValue }
                if (lastValid >= 0) {
                    drawCircle(
                        color = color,
                        radius = 3.dp.toPx(),
                        center = Offset(xOf(lastValid), yOf(s.points[lastValid].density))
                    )
                }
            }
        }

        // ---- 点选的竖虚线 ----
        focusBucketStart?.let { fb ->
            val i = pts.indexOfFirst { it.bucketStart == fb }
            if (i >= 0) {
                val x = xOf(i)
                val focusColor = colors.getOrNull(focusIndex) ?: gridColor
                drawLine(
                    color = focusColor,
                    start = Offset(x, top),
                    end = Offset(x, bottom),
                    strokeWidth = 1.dp.toPx(),
                    pathEffect = dash
                )
                // 在焦点线的那个桶上再压一个实心点
                focus?.points?.getOrNull(i)?.let { p ->
                    if (p.hasValue) {
                        drawCircle(
                            color = focusColor,
                            radius = 4.dp.toPx(),
                            center = Offset(x, yOf(p.density))
                        )
                    }
                }
            }
        }

        // ---- X 标签：首 / 中 / 末，够用且不糊 ----
        listOf(0, pts.size / 2, pts.size - 1).distinct().forEach { i ->
            val laid = textMeasurer.measure(bucketLabel(pts[i].bucketStart, bucket), labelStyle)
            val x = (xOf(i) - laid.size.width / 2f).coerceIn(0f, size.width - laid.size.width)
            drawText(textLayoutResult = laid, topLeft = Offset(x, size.height - laid.size.height), color = axisTextColor)
        }
    }
}

/** 桶标签：月桶显示"M月"，天/周桶显示"M/D"。 */
internal fun bucketLabel(ms: Long, bucket: TrendBucket): String {
    val c = Calendar.getInstance().apply { timeInMillis = ms }
    val m = c.get(Calendar.MONTH) + 1
    val d = c.get(Calendar.DAY_OF_MONTH)
    return if (bucket == TrendBucket.MONTH) "${m}月" else "$m/$d"
}
