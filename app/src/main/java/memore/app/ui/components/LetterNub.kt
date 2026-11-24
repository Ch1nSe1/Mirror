package memore.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import memore.app.ui.theme.MemoreType

/**
 * 左下角那封信（2026-11-14 第四版）。
 *
 * 前三版都在画"露出来半张纸"，用户三次说「不像信封」。原因终于在第四版想明白：
 * **30dp 宽的露出口承载不了任何表面细节**——折口、邮票、字行，被裁掉 60% 之后全都读不出来。
 *
 * 所以改判断：「露出一部分」是氛围，不是目的。目的是**一眼看出是一封信**。
 * 一个完整的小信封图形（44×30dp）比露半张纸可靠得多——轮廓清楚，细节都在。
 *
 * 保留的克制：
 *   · 只斜 8°，不再大角度旋转（角度一大就变成"杂物"）
 *   · 只在有待批准时出现
 *   · 红圈数字，不写"条"
 */
@Composable
fun LetterNub(
    count: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (count <= 0) return

    val w = 44.dp
    val h = 30.dp
    val paper = MaterialTheme.colorScheme.surface
    val ink = MaterialTheme.colorScheme.onSurfaceVariant

    Box(
        modifier
            .size(width = w, height = h)
            .graphicsLayer { rotationZ = -8f }
            .shadow(5.dp, MirrorShapes.row)
            .background(paper, MirrorShapes.row)
            .border(1.dp, ink.copy(alpha = 0.35f), MirrorShapes.row)
            .clickable(onClick = onClick)
    ) {
        Canvas(Modifier.matchParentSize()) {
            // 信封的封口：从左上、右上两个角向中心收的 V
            val flap = Path().apply {
                moveTo(0f, 0f)
                lineTo(size.width * 0.5f, size.height * 0.55f)
                lineTo(size.width, 0f)
            }
            // 下面两条是信封正面的斜折（从底角向中心），三线一起构成经典信封轮廓
            val lowerLeft = Path().apply {
                moveTo(0f, size.height)
                lineTo(size.width * 0.42f, size.height * 0.45f)
            }
            val lowerRight = Path().apply {
                moveTo(size.width, size.height)
                lineTo(size.width * 0.58f, size.height * 0.45f)
            }
            drawPath(flap, color = ink.copy(alpha = 0.45f), style = Stroke(width = 1.2.dp.toPx()))
            drawPath(lowerLeft, color = ink.copy(alpha = 0.22f), style = Stroke(width = 1.2.dp.toPx()))
            drawPath(lowerRight, color = ink.copy(alpha = 0.22f), style = Stroke(width = 1.2.dp.toPx()))
        }

        // 红圈数字 = 待批准条数
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .offset(x = (-6).dp, y = (-7).dp)
                .size(18.dp)
                .background(MaterialTheme.colorScheme.error, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(
                if (count > 9) "9+" else count.toString(),
                style = MemoreType.micro,
                color = MaterialTheme.colorScheme.onError,
                textAlign = TextAlign.Center
            )
        }
    }
}

/**
 * 少量（≤3 条）时的就地弹窗：靠左下，和信同侧。
 */
@Composable
fun LetterPopupCard(
    title: String,
    items: List<Pair<String, Long>>,
    onApprove: (Long) -> Unit,
    onReject: (Long) -> Unit,
    onOpenAll: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier
            .width(280.dp)
            .shadow(10.dp, MirrorShapes.card)
            .background(MaterialTheme.colorScheme.surface, MirrorShapes.card)
            .border(1.dp, mirrorHairlineColor(), MirrorShapes.card)
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        // 抬头：数量和"点外面也能关"的暗示（不写文字，靠留白和关闭动作）
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(title, style = MemoreType.micro, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                "收起",
                style = MemoreType.micro,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.clickable(onClick = onDismiss)
            )
        }
        Spacer(Modifier.height(10.dp))

        items.forEachIndexed { index, (text, id) ->
            Text(text, style = MemoreType.body, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(
                    "批准",
                    style = MemoreType.meta,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable { onApprove(id) }.padding(vertical = 4.dp)
                )
                Text(
                    "忽略",
                    style = MemoreType.meta,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.clickable { onReject(id) }.padding(vertical = 4.dp)
                )
            }
            if (index != items.lastIndex) {
                Spacer(Modifier.height(10.dp))
                Box(Modifier.fillMaxWidth().height(1.dp).background(mirrorHairlineColor()))
                Spacer(Modifier.height(10.dp))
            }
        }

        Spacer(Modifier.height(12.dp))
        Text(
            "全部查看",
            style = MemoreType.meta,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.clickable(onClick = onOpenAll).padding(vertical = 2.dp)
        )
    }
}
