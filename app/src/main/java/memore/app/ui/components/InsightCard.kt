package memore.app.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import memore.app.data.vector.InsightService
import memore.app.ui.theme.MemoreType

/**
 * 洞察卡（2026-11-17 第二版：按原版观感，不再挂 ⋮）。
 *
 * ```
 * 单条 · 11 月 12 日                  ← 类型用主色（原版靠它分辨），日期 muted
 * 一句判断句（示例）                 ← 判断句：宋体（原版就是衬线；见 MemoreType.reading）
 * 「我害怕某件事」· 2026-01-01        ← 引文：宋体 + muted
 * ```
 *
 * **右侧没有记号**：第一版我给每张卡挂了 ⋮，而菜单里只有"删除"一项——
 * 一个常驻图标为单一动作存在就是装饰，还把阅读页变成了操作列表。
 * 删除回到原版行为：**长按 → 确认框**；点卡片本体 = 进记录。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun InsightCard(
    scope: String,
    date: String,
    claims: List<InsightService.Claim>,
    modifier: Modifier = Modifier,
    /** 点卡片本体（进记录）；null = 不可点。 */
    onClick: (() -> Unit)? = null,
    /** 长按删除（原版行为）；null = 不响应长按。 */
    onLongClick: (() -> Unit)? = null,
    /** 2026-11-23：回顾页自行渲染日期/展开按钮时，隐藏卡内组头。 */
    showHeader: Boolean = true
) {
    Column(
        modifier.fillMaxWidth()
            .combinedClickable(
                onClick = { onClick?.invoke() },
                onLongClick = onLongClick
            )
    ) {
        if (showHeader) {
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                // 视角/范围：label 11 主色（原版就是这样分辨类型）；日期 micro 10 muted
                Text(scope, style = MemoreType.label, color = MaterialTheme.colorScheme.primary)
                Text(" · ", style = MemoreType.micro, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
                Text(date, style = MemoreType.micro, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        claims.forEachIndexed { index, c ->
            if (index > 0) Spacer(Modifier.height(20.dp))
            // 2026-11-22（方案 A）：结论先行，引文紧跟，依据退成注脚（竖线编组）。
            Text(
                c.text,
                style = MemoreType.reading.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Medium),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = if (index == 0) 10.dp else 0.dp)
            )
            val quoteLine = buildString {
                append("「").append(c.quote).append("」")
                append(" · ").append(c.date)
                c.quoteTo?.let { q -> append("\n→「").append(q).append("」").append(" · ").append(c.dateTo ?: "") }
            }
            // 引文：比结论轻一档（micro 10 宋）
            Text(
                quoteLine,
                style = MemoreType.micro.copy(fontFamily = FontFamily.Serif, letterSpacing = 0.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                modifier = Modifier.padding(top = 5.dp)
            )
            c.see?.let {
                Row(
                    Modifier.fillMaxWidth().padding(start = 12.dp, top = 9.dp).height(IntrinsicSize.Min)
                ) {
                    Box(
                        Modifier.width(2.dp).fillMaxHeight()
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f))
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        it,
                        style = MemoreType.meta,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)))
    }
}

/** 弹窗里的紧凑版：不画分隔线、不挂记号，靠 footer 的保存/取消。 */
@Composable
fun InsightCardCompact(claims: List<InsightService.Claim>) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        claims.forEachIndexed { index, c ->
            if (index > 0) Spacer(Modifier.height(16.dp))
            // 与列表卡同一套层级：结论 → 引文 → 依据（注脚竖线）
            Text(
                c.text,
                style = MemoreType.reading.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Medium),
                color = MaterialTheme.colorScheme.onSurface
            )
            val quoteLine = buildString {
                append("「").append(c.quote).append("」")
                append(" · ").append(c.date)
                c.quoteTo?.let { q -> append("\n→「").append(q).append("」").append(" · ").append(c.dateTo ?: "") }
            }
            Text(
                quoteLine,
                style = MemoreType.micro.copy(fontFamily = FontFamily.Serif, letterSpacing = 0.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                modifier = Modifier.padding(top = 5.dp)
            )
            c.see?.let {
                Row(
                    Modifier.fillMaxWidth().padding(start = 10.dp, top = 8.dp).height(IntrinsicSize.Min)
                ) {
                    Box(
                        Modifier.width(2.dp).fillMaxHeight()
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f))
                    )
                    Spacer(Modifier.width(9.dp))
                    Text(
                        it,
                        style = MemoreType.meta,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

