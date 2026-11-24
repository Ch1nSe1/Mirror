package memore.app.ui.screens

import memore.app.ui.components.MirrorShapes

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import memore.app.data.model.Dial
import memore.app.data.repository.DialRepository
import memore.app.data.repository.buildDialAggregate
import memore.app.data.repository.groupRecordsByTick
import memore.app.utils.formatDateOnly

/** 汇总页看哪一类。 */
object DialStatKind {
    const val EMOTION = "EMOTION"
    const val THEME = "THEME"
}

/**
 * 表盘的**整期汇总页**（点表盘页那两格进来）：这一类东西在这一期里出现了多少次，谁最多。
 *
 * 只做整期合计：**单段的东西刻度页已经有了**（用户 2026-10-20：「单个的汇总有刻度，没必要画蛇添足」），
 * 所以这里没有段筛选、没有按段分组、也没有解释性小字 —— 就是排行。
 */
@Composable
fun DialRankScreen(
    dialId: String,
    kind: String,
    dialRepository: DialRepository,
    onBack: () -> Unit
) {
    var dial by remember { mutableStateOf<Dial?>(null) }
    var rows by remember { mutableStateOf<List<Pair<String, Int>>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }

    LaunchedEffect(dialId, kind) {
        loaded = false
        val d = dialRepository.getDial(dialId)
        dial = d
        if (d != null) {
            val sections = groupRecordsByTick(
                dialRepository.recordsOfDial(d),
                dialRepository.ticksOfDial(dialId)
            )
            val agg = buildDialAggregate(
                sections = sections,
                nodeNamesByRecord = dialRepository.themeNamesByRecord(d),
                topEmotions = 500,
                topThemes = 500
            )
            val src = if (kind == DialStatKind.EMOTION) agg.emotions else agg.themes
            // 只列前 30：长尾全是"只出现 1 次"的东西，排出来只是噪音（数值仍按全量算）
            rows = src.take(30).map { it.name to it.total }
        }
        loaded = true
    }

    val isEmotion = kind == DialStatKind.EMOTION
    // 情绪＝淡紫，主题＝淡青（2026-11-18 用户要求调淡；原色是主题紫与协议青 4DD0E1）
    val hue = if (isEmotion) MaterialTheme.colorScheme.primary else Color(0xFF4DD0E1)
    val max = rows.maxOfOrNull { it.second }?.coerceAtLeast(1) ?: 1

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回") }
            Column(Modifier.weight(1f)) {
                Text(
                    if (isEmotion) "情绪" else "主题",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                val d = dial
                if (d != null) {
                    Text(
                        "${d.name} · ${formatDateOnly(d.startMs)} – ${d.endMs?.let { formatDateOnly(it) } ?: "至今"}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }
        }

        when {
            !loaded -> Box(Modifier.fillMaxWidth().weight(1f)) {}
            rows.isEmpty() -> Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Text(
                    if (isEmotion) "这一期还没有情绪" else "这一期还没有主题",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            else -> LazyColumn(
                Modifier.weight(1f),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 24.dp)
            ) {
                items(rows, key = { it.first }) { (name, count) ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 9.dp)) {
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text(
                                name,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(
                                "$count",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        // 淡色逻辑照抄情绪分析页：同色相、低透明度（轨道 0.06 / 填充 0.22），
                        // 不另造浅色值。这样换主题时两条一起跟。
                        Box(
                            Modifier.fillMaxWidth().height(4.dp).clip(MirrorShapes.pill)
                                .background(hue.copy(alpha = 0.06f))
                        ) {
                            Box(
                                Modifier.fillMaxWidth(count.toFloat() / max).height(4.dp)
                                    .clip(MirrorShapes.pill).background(hue.copy(alpha = 0.22f))
                            )
                        }
                    }
                }
            }
        }
    }
}


