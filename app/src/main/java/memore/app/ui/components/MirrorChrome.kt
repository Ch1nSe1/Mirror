package memore.app.ui.components

import androidx.compose.runtime.setValue
import memore.app.ui.components.MemoreLineInput
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.material3.Icon
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.TextButton
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SheetState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.contentColorFor
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties

/**
 * 浮层的"过渡边"与圆角规范（2026-10-20 起统一）。
 *
 * 为什么需要它：这个 App 原来**全项目 0 处 elevation、5 处 border**，而三层表面（背景 / 卡片 / 浮层）
 * 的亮度只差 5~14 —— 于是弹窗、菜单、卡片全"贴"在背景上，看起来简陋。
 * 现在统一成：**三层色阶（背景→卡片→浮层）＋ 一层发丝描边 ＋ 中性阴影**，
 * 描边颜色用 onSurface 的低透明度，这样浅色/深色主题都不用另配一支颜色。
 *
 * 圆角也统一到 [MirrorShapes]：行 10 / 卡 14 / 输入 18 / 弹窗与浮层 22。
 */
object MirrorShapes {
    /** 列表行、选中态、小药丸底色块。 */
    val row = RoundedCornerShape(10.dp)

    /** 卡片（记录卡、刻度卡、表盘段…）。 */
    val card = RoundedCornerShape(14.dp)

    /** 输入框、内嵌容器。 */
    val field = RoundedCornerShape(18.dp)

    /**
     * 小弹窗面板（`⋮` / `＋` 的就地菜单）。
     *
     * 2026-11-12 从 22 降到 12：48dp 的项配上 22dp 圆角，边缘圆得像药丸，
     * 反而不像"一张小纸片"。**圆角要跟面板尺寸走**——面板越小，角越小。
     * 对话框仍是 22（[modal]），因为它大得多。
     */
    val menu = RoundedCornerShape(12.dp)

    /** 弹窗、底部浮层。 */
    val modal = RoundedCornerShape(22.dp)

    /** 药丸（chip / 按钮）。 */
    val pill = RoundedCornerShape(99.dp)
}

/** 发丝边颜色：浅色主题下是"比底色深一点点"，深色主题下是"比底色亮一点点"，靠 onSurface 自适应。 */
@Composable
fun mirrorHairlineColor(): Color = MaterialTheme.colorScheme.onSurface.copy(
    // 实测：0.10 在米色底上只差 5 个亮度（几乎看不见），0.18 才是一条"看得见但不抢眼"的边
    alpha = if (MaterialTheme.colorScheme.background.luminanceIsDark()) 0.22f else 0.18f
)

/** 浮层/卡片用的发丝描边。 */
@Composable
fun mirrorHairline(): BorderStroke = BorderStroke(1.dp, mirrorHairlineColor())

/** 浮层（弹窗 / 菜单 / 底部浮层）的阴影高度：中性黑，不染色。 */
val MirrorModalElevation: Dp = 12.dp

/** 卡片的阴影高度（很轻，主要靠描边闭合）。 */
val MirrorCardElevation: Dp = 2.dp

private fun Color.luminanceIsDark(): Boolean =
    (0.299f * red + 0.587f * green + 0.114f * blue) < 0.5f

/**
 * 统一替身：**带发丝描边 + 中性阴影**的 AlertDialog。
 *
 * M3 的 `AlertDialog` 没有 `border` 参数，所以全项目 70 处调用统一换成它 ——
 * 参数与 M3 完全同名同序，调用点只需要改类名；`modifier` 会被加上边框（M3 里它作用在容器 Surface 上）。
 */
@Composable
fun MirrorAlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: (@Composable () -> Unit)? = null,
    icon: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
    shape: Shape = MirrorShapes.modal,
    containerColor: Color = MaterialTheme.colorScheme.surface,
    iconContentColor: Color = MaterialTheme.colorScheme.primary,
    titleContentColor: Color = MaterialTheme.colorScheme.onSurface,
    textContentColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    tonalElevation: Dp = 0.dp,
    properties: DialogProperties = DialogProperties()
) {
    // 2026-11-14：不再套 M3 AlertDialog（左标题 + 右下小字按钮 + 水波纹），
    // 按法条 4.4 / 11.12③ 自绘：标题与说明居中、按钮平分、键盘避让。
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismissRequest, properties = properties) {
        Surface(
            modifier = modifier
                .widthIn(min = 260.dp, max = 320.dp).fillMaxWidth(0.82f)
                .imePadding()
                .border(mirrorHairline(), shape),
            shape = shape,
            color = containerColor,
            tonalElevation = tonalElevation
        ) {
            Column(Modifier.fillMaxWidth()) {
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    icon?.let {
                        it()
                        Spacer(Modifier.height(10.dp))
                    }
                    title?.let {
                        // 标题：居中；调用点传的是 Text，这里只管居中摆放
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            androidx.compose.runtime.CompositionLocalProvider(
                                androidx.compose.material3.LocalContentColor provides titleContentColor
                            ) { it() }
                        }
                        Spacer(Modifier.height(12.dp))
                    }
                    text?.let {
                        // 说明/表单：占满宽度。容器居中 → 一句话说明会居中；
                        // 表单内容自己是 fillMaxWidth，不受影响（2026-11-14）。
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            androidx.compose.runtime.CompositionLocalProvider(
                                androidx.compose.material3.LocalContentColor provides textContentColor
                            ) { it() }
                        }
                    }
                }
                Box(
                    Modifier.fillMaxWidth().height(1.dp)
                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f))
                )
                Row(Modifier.fillMaxWidth().height(44.dp)) {
                    if (dismissButton != null) {
                        Box(
                            Modifier.weight(1f).fillMaxHeight(),
                            contentAlignment = Alignment.Center
                        ) { dismissButton() }
                        Box(
                            Modifier.width(1.dp).fillMaxHeight()
                                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f))
                        )
                        Box(
                            Modifier.weight(1f).fillMaxHeight(),
                            contentAlignment = Alignment.Center
                        ) { confirmButton() }
                    } else {
                        Box(
                            Modifier.weight(1f).fillMaxHeight(),
                            contentAlignment = Alignment.Center
                        ) { confirmButton() }
                    }
                }
            }
        }
    }
}
/**
 * 浮层容器：自绘弹窗 / 底部面板用它，自带"过渡边 + 阴影 + 统一圆角"。
 * 这是字面意义上的"过渡用的小边"——把浮层和背景分开的那一根线。
 */
@Composable
fun MirrorModalSurface(
    modifier: Modifier = Modifier,
    shape: Shape = MirrorShapes.modal,
    color: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
    elevation: Dp = MirrorModalElevation,
    content: @Composable () -> Unit
) {
    Surface(
        modifier = modifier,
        shape = shape,
        color = color,
        border = mirrorHairline(),
        shadowElevation = elevation,
        content = content
    )
}

/** 卡片容器：卡片本来靠色差就够用，再补一根很淡的边让它"闭合"。 */
@Composable
fun MirrorCardSurface(
    modifier: Modifier = Modifier,
    shape: Shape = MirrorShapes.card,
    color: Color = MaterialTheme.colorScheme.surfaceContainerLow,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit
) {
    val border = mirrorHairline()
    if (onClick != null) {
        Surface(
            modifier = modifier,
            shape = shape,
            color = color,
            border = border,
            shadowElevation = MirrorCardElevation,
            onClick = onClick,
            content = content
        )
    } else {
        Surface(
            modifier = modifier,
            shape = shape,
            color = color,
            border = border,
            shadowElevation = MirrorCardElevation,
            content = content
        )
    }
}

/** 浮层标准阴影（菜单类稍轻）。 */
val MirrorMenuElevation: Dp = 8.dp

/**
 * 页面级「纯文字动作」（2026-10-20 统一）。
 *
 * 为什么不用药丸也不用 M3 默认的 TextButton：
 * - 同一行两颗药丸会被读成"分段控件"；
 * - M3 的 TextButton 只有一种强调（primary + 14sp），**主次分不出来**，而且在米色底上偏亮。
 *
 * 这里靠四件事让它"像按钮但不吵"：
 * ① 无底色，**按下才出现 8% 圆角底**（ripple）；
 * ② **字重分主次**：Primary = 600 + primary；Secondary = 500 + onSurfaceVariant；
 * ③ 点击区上下 8dp / 左右 10dp（视觉不变，热区够大）；
 * ④ 字距 +0.2sp，读起来是"标签"而不是正文。
 */
enum class MirrorActionEmphasis { Primary, Secondary }

@Composable
fun MirrorTextAction(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    emphasis: MirrorActionEmphasis = MirrorActionEmphasis.Secondary,
    enabled: Boolean = true
) {
    val base = when (emphasis) {
        MirrorActionEmphasis.Primary -> MaterialTheme.colorScheme.primary
        MirrorActionEmphasis.Secondary -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Text(
        text,
        style = MaterialTheme.typography.labelLarge.copy(letterSpacing = 0.2.sp),
        fontWeight = if (emphasis == MirrorActionEmphasis.Primary) FontWeight.SemiBold else FontWeight.Medium,
        color = if (enabled) base else base.copy(alpha = 0.45f),
        modifier = modifier
            .clip(MirrorShapes.row)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp)
    )
}

/**
 * 弹窗里的一对动作按钮：左描边灰字、右实心主色，**等宽并排**。
 * 比两个纯文字按钮有主次，也比 `MirrorDecisionDialog` 更容易塞进既有弹窗里。
 */
@Composable
fun MirrorDialogActions(
    onConfirm: () -> Unit,
    confirmText: String = "确定",
    onDismiss: () -> Unit,
    dismissText: String = "取消",
    danger: Boolean = false,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        OutlinedButton(
            onClick = onDismiss,
            shape = MirrorShapes.card,
            border = mirrorHairline()
        ) {
            Text(
                dismissText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Button(
            onClick = onConfirm,
            shape = MirrorShapes.card,
            colors = ButtonDefaults.buttonColors(
                containerColor = if (danger) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.primary
            )
        ) {
            Text(
                confirmText,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                color = if (danger) MaterialTheme.colorScheme.onError
                else MaterialTheme.colorScheme.onPrimary
            )
        }
    }
}

/**
 * 新式确认框（2026-10-20）。
 *
 * 和 M3 默认的「大标题 + 两个纯文字按钮」不同：
 * - 标题 16sp 半粗，不再是 24sp 压人；
 * - `body` 只在**有后果/有数据**时出现，不当解释句用；
 * - 主操作实心、次操作弱化成灰字，**两个按钮等宽并排**；
 * - `danger = true` 时主按钮走 error 色，且动作名写具体（"关闭"而不是"确认"）；
 * - `content` 用来放输入框（验证类弹窗），留空就是一次性确认。
 */
@Composable
fun MirrorDecisionDialog(
    onDismissRequest: () -> Unit,
    title: String,
    primaryLabel: String,
    onPrimary: () -> Unit,
    secondaryLabel: String = "取消",
    onSecondary: (() -> Unit)? = null,
    body: String? = null,
    danger: Boolean = false,
    leadingIcon: (@Composable () -> Unit)? = null,
    content: (@Composable () -> Unit)? = null
) {
    Dialog(onDismissRequest = onDismissRequest) {
        Surface(
            shape = MirrorShapes.modal,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            border = mirrorHairline(),
            shadowElevation = MirrorModalElevation
        ) {
            Column(Modifier.fillMaxWidth().padding(20.dp)) {
                if (leadingIcon != null) {
                    Box(
                        Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(
                                if (danger) MaterialTheme.colorScheme.error.copy(alpha = 0.12f)
                                else MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                            ),
                        contentAlignment = Alignment.Center
                    ) { leadingIcon() }
                    Spacer(Modifier.height(12.dp))
                }
                Text(
                    title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (body != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        body,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (content != null) {
                    Spacer(Modifier.height(14.dp))
                    content()
                }
                Spacer(Modifier.height(18.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { onSecondary?.invoke() ?: onDismissRequest() },
                        modifier = Modifier.weight(1f),
                        shape = MirrorShapes.card,
                        border = mirrorHairline()
                    ) {
                        Text(
                            secondaryLabel,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Button(
                        onClick = onPrimary,
                        modifier = Modifier.weight(1f),
                        shape = MirrorShapes.card,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (danger) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.primary
                        )
                    ) {
                        Text(
                            primaryLabel,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                            color = if (danger) MaterialTheme.colorScheme.onError
                            else MaterialTheme.colorScheme.onPrimary
                        )
                    }
                }
            }
        }
    }
}

/**
 * 统一替身：带发丝描边 + 阴影 + 统一圆角的 `DropdownMenu`。
 * 长按菜单是"浮层"最典型的样子（参考图里那张 重命名/取消置顶/多选/删除），
 * 原来它只有系统默认的一点阴影，在米色底上几乎看不出层次。
 */
@Composable
fun MirrorDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    val shape = MirrorShapes.menu
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = modifier.border(mirrorHairline(), shape),
        shape = shape,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        shadowElevation = MirrorMenuElevation,
        content = content
    )
}

/**
 * 弹出菜单的一项（法条 4.1）。
 *
 * @param label 文字。菜单是"选择"，不是内容，所以统一 14sp，不跟列表行同大
 * @param icon 4.7：**只有菜单里混着不同类别时才传**；没被要求就不画图标，危险项也不画
 * @param danger 危险项用 `error` 色文字；颜色已经说完了，不再加图标
 */
data class MirrorMenuAction(
    val label: String,
    val icon: ImageVector? = null,
    val danger: Boolean = false,
    val enabled: Boolean = true,
    /** 当前项（单选菜单用）：文字走 `primary`，和"危险"互斥时以危险为准。 */
    val selected: Boolean = false,
    /** 2026-11-17：右端数据（条数等）。null 不占位；已有调用点不受影响。 */
    val trailing: String? = null,
    /** 2026-11-17：行首 8dp 小色点，用于多选身份色。null 不画。 */
    val dotColor: Color? = null,
    val onClick: () -> Unit
)

/**
 * 数据驱动的弹出菜单：**选项之间自动加分隔线，最后一项下面不画**（4.1）。
 *
 * 为什么不用 `DropdownMenuItem` 拼：那样"项高 48 / 14sp / 分隔线"要在每个调用点
 * 手抄一遍，抄多了就长歪。这里把三类东西收成一份：容器尺寸、项、线。
 *
 * 分隔线跟着文字块走（4.6）：无图标时左端 16dp，有图标时 46dp，右端一律留 8dp。
 */
@Composable
fun MirrorMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    actions: List<MirrorMenuAction>
) {
    if (actions.isEmpty()) return
    val withIcons = actions.any { it.icon != null }
    MirrorDropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        // 宽度交给内容（2026-11-12）：写死 200dp 会让两词的菜单空出一截右壁。
        // 下限 112dp 是不许缩成窄条，上限 280dp 是长条目（"重新分析（已过期）"）不许撑满屏。
        modifier = Modifier.widthIn(min = 112.dp, max = 280.dp)
    ) {
        actions.forEachIndexed { index, action ->
            Row(
                Modifier.fillMaxWidth().height(48.dp)
                    .clickable(enabled = action.enabled) { action.onClick() }
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (action.dotColor != null) {
                    Box(
                        Modifier.size(8.dp)
                            .clip(androidx.compose.foundation.shape.CircleShape)
                            .background(action.dotColor)
                    )
                    Spacer(Modifier.width(10.dp))
                }
                if (action.icon != null) {
                    Icon(
                        action.icon,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.width(12.dp))
                }
                Text(
                    action.label,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                    color = when {
                        !action.enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                        action.danger -> MaterialTheme.colorScheme.error
                        action.selected -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.onSurface
                    }
                )
                if (action.trailing != null) {
                    Spacer(Modifier.weight(1f))
                    Text(
                        action.trailing,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                    )
                }
            }
            if (index < actions.lastIndex) {
                Box(
                    Modifier.fillMaxWidth()
                        .padding(start = if (withIcons) 46.dp else 16.dp, end = 8.dp)
                        .height(1.dp)
                        .background(mirrorHairlineColor())
                )
            }
        }
    }
}

/**
 * 药丸/徽章的"同色边"：淡色底块外面再收一道同色描边。
 *
 * 参考的样子是那颗「深度思考」chip —— 淡底 + 一支比底深一点、同色相的边。
 * 用同色而不是 onSurface，是因为药丸本身就是"带含义的色块"，用中性灰边会显脏。
 * 0.38 是让边比 0.20 的底明显，又不至于变成实心色框。
 */
fun mirrorPillBorder(color: Color): BorderStroke = BorderStroke(1.dp, color.copy(alpha = 0.38f))

/** 底部浮层的形状：只有上面两个角。 */
val MirrorSheetShape: Shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp)

/**
 * 统一替身：统一顶部圆角的 `ModalBottomSheet`。
 *
 * ⚠️ **不要给它加 `border`**。M3 在内部把我们传的 `modifier` **再套一层 `.offset { }`**
 * （面板从屏幕下方滑上来的那段位移），于是"边"画在位移前的位置、"内容"画在位移后的位置 ——
 * 结果是一根横贯全屏、落在半中腰的线（筛选面板上就出过这个事故：边横在"附件"和"加密"之间）。
 * 底部面板本来就靠**蒙层 + 色阶 + 顶角圆弧**和背景分开，不需要那根线。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MirrorSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    sheetState: SheetState = rememberModalBottomSheetState(),
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerLow,
    dragHandle: (@Composable () -> Unit)? = { BottomSheetDefaults.DragHandle() },
    content: @Composable ColumnScope.() -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        sheetState = sheetState,
        shape = MirrorSheetShape,
        containerColor = containerColor,
        contentColor = contentColorFor(containerColor),
        tonalElevation = 0.dp,
        dragHandle = dragHandle,
        content = content
    )
}
/**
 * 竖向确认框（2026-10-24，照 DS/Telegram 那种）：
 * 居中标题 +（可选）一行说明 +（可选）自定义内容 + 一颗满宽主键 + 下方"取消"文字。
 *
 * 与 `MirrorAlertDialog` 的区别：那个是 M3 的"右下两颗按钮"，这个是**动作纵向堆叠**，
 * 危险动作（删除）用红主键，普通动作用主题主键。
 */
@Composable
fun MirrorStackDialog(
    title: String,
    confirmText: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    message: String? = null,
    destructive: Boolean = false,
    content: (@Composable () -> Unit)? = null
) {
    // 2026-11-11 改版（照用户给的参考）：宽度收到 320dp，按钮改成「取消 | 确认」横排，
    // 不再用满宽紫色胶囊 + 下方取消——那样两行字也占一大块，又扁又重。
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = MirrorShapes.modal,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            border = mirrorHairline(),
            modifier = Modifier.widthIn(min = 260.dp, max = 320.dp).fillMaxWidth(0.82f)
        ) {
            Column(Modifier.fillMaxWidth()) {
                Column(
                    Modifier.fillMaxWidth().padding(
                        horizontal = 20.dp,
                        // 自适应（2026-11-14）：只有标题时上下 18，有内容时收到 14
                        vertical = if (message == null && content == null) 18.dp else 14.dp
                    ),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        title,
                        fontSize = 15.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center
                    )
                    if (message != null) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            message,
                            fontSize = 12.5.sp,
                            lineHeight = 19.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                    if (content != null) {
                        Spacer(Modifier.height(14.dp))
                        // 列表型内容封顶 38% 屏高，超了内部滚动（按钮不动）
                        val maxH = (androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp * 0.38f).dp
                        Box(Modifier.fillMaxWidth().heightIn(max = maxH).verticalScroll(rememberScrollState())) {
                            content()
                        }
                    }
                }
                Box(
                    Modifier.fillMaxWidth().height(1.dp)
                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f))
                )
                Row(Modifier.fillMaxWidth().height(46.dp)) {
                    Box(
                        Modifier.weight(1f).fillMaxHeight().clickable(onClick = onDismiss),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("取消", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Box(
                        Modifier.width(1.dp).fillMaxHeight()
                            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f))
                    )
                    Box(
                        Modifier.weight(1f).fillMaxHeight().clickable(onClick = onConfirm),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            confirmText,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (destructive) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }
    }
}
/**
 * 竖排确认框里的密码输入：一条横线 + 居中圆点（与 PIN / 安全问题同一族）。
 */
@Composable
fun MirrorPasswordField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String = "主密码",
    error: String? = null,
    numeric: Boolean = false
) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        androidx.compose.foundation.text.BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = androidx.compose.ui.text.TextStyle(
                fontSize = 20.sp,
                letterSpacing = 4.sp,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurface
            ),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
            visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                keyboardType = if (numeric) androidx.compose.ui.text.input.KeyboardType.NumberPassword
                else androidx.compose.ui.text.input.KeyboardType.Password,
                imeAction = androidx.compose.ui.text.input.ImeAction.Done
            ),
            modifier = Modifier.fillMaxWidth(),
            decorationBox = { inner ->
                Column(Modifier.fillMaxWidth()) {
                    Box(Modifier.fillMaxWidth().height(44.dp), contentAlignment = Alignment.Center) {
                        if (value.isEmpty()) {
                            Text(
                                placeholder,
                                fontSize = 15.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.40f)
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
        if (error != null) {
            Spacer(Modifier.height(8.dp))
            Text(error, fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
        }
    }
}
/**
 * 竖排确认框里的文本输入：一条横线 + 居中文字（与密码输入同一族）。
 */
@Composable
fun MirrorInputField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String = "",
    error: String? = null
) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        androidx.compose.foundation.text.BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = androidx.compose.ui.text.TextStyle(
                fontSize = 16.sp,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurface
            ),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                imeAction = androidx.compose.ui.text.input.ImeAction.Done
            ),
            modifier = Modifier.fillMaxWidth(),
            decorationBox = { inner ->
                Column(Modifier.fillMaxWidth()) {
                    Box(Modifier.fillMaxWidth().height(44.dp), contentAlignment = Alignment.Center) {
                        if (value.isEmpty()) {
                            Text(
                                placeholder,
                                fontSize = 15.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.40f)
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
        if (error != null) {
            Spacer(Modifier.height(8.dp))
            Text(error, fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
        }
    }
}
/** 选择型弹窗的一项。 */
data class ChoiceOption(
    val label: String,
    val hint: String? = null,
    val icon: androidx.compose.ui.graphics.vector.ImageVector? = null
)

/**
 * 选择型弹窗（2026-10-24 定稿 S1）：
 * 居中标题 + 若干"可点的行"（图标 + 主标签 + 下面一行小字 + ›）+ 底部"取消"文字。
 * 一次点击即生效，所以没有主键。
 */
@Composable
fun MirrorChoiceDialog(
    title: String,
    options: List<ChoiceOption>,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = MirrorShapes.modal,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            border = mirrorHairline(),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 20.dp)) {
                Text(
                    title,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                )
                Box(
                    Modifier.fillMaxWidth().height(1.dp)
                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f))
                )
                options.forEachIndexed { index, opt ->
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 54.dp).clickable { onSelect(index) },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (opt.icon != null) {
                            Icon(
                                opt.icon, null, Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.width(11.dp))
                        }
                        Column(Modifier.weight(1f)) {
                            Text(opt.label, fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurface)
                            if (opt.hint != null) {
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    opt.hint,
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Spacer(Modifier.width(8.dp))
                        Text("›", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Box(
                        Modifier.fillMaxWidth().height(1.dp)
                            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f))
                    )
                }
                Spacer(Modifier.height(4.dp))
                TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                    Text("取消", fontSize = 13.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
/** 统一的输入框配色：纸色底 + 发丝边 + 主色聚焦（替代各处零散的 OutlinedTextFieldDefaults）。 */
@Composable
fun mirrorFieldColors(): androidx.compose.material3.TextFieldColors =
    androidx.compose.material3.OutlinedTextFieldDefaults.colors(
        focusedBorderColor = MaterialTheme.colorScheme.primary,
        unfocusedBorderColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f),
        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        cursorColor = MaterialTheme.colorScheme.primary
    )
@Composable
fun MirrorLineField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String = "",
    label: String? = null,
    password: Boolean = false,
    error: String? = null,
    keyboardType: androidx.compose.ui.text.input.KeyboardType = androidx.compose.ui.text.input.KeyboardType.Text,
    trailing: (@Composable () -> Unit)? = null
) {
    var focused by remember { mutableStateOf(false) }
    // 2026-11-14：列名不画（法条 4.5 改）。label 参数留着只为兼容调用点，不再渲染。
    Column(Modifier.fillMaxWidth().padding(top = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.foundation.text.BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurface
                ),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
                visualTransformation = if (password) androidx.compose.ui.text.input.PasswordVisualTransformation()
                else androidx.compose.ui.text.input.VisualTransformation.None,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    keyboardType = if (password) androidx.compose.ui.text.input.KeyboardType.Password else keyboardType,
                    imeAction = androidx.compose.ui.text.input.ImeAction.Done
                ),
                modifier = Modifier.weight(1f).onFocusChanged { focused = it.isFocused },
                decorationBox = { inner ->
                    // 2026-11-16：列名仍然不画（法条 4.5），但**空态提示词要画回来**。
                    // 之前「空态提示一律不画」做得过头了：AI 配置的三条输入线、密保问题那几栏
                    // 全变成没有提示的空白横线，用户不知道每一行该填什么（真机反馈）。
                    Box(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                        if (value.isEmpty() && placeholder.isNotEmpty()) {
                            Text(
                                placeholder,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                            )
                        }
                        inner()
                    }
                }
            )
            trailing?.invoke()
        }
        Box(
            Modifier.fillMaxWidth().height(1.dp).background(
                if (focused) MaterialTheme.colorScheme.primary.copy(alpha = 0.40f)
                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f)
            )
        )
        if (error != null) {
            Spacer(Modifier.height(6.dp))
            Text(error, fontSize = 11.5.sp, color = MaterialTheme.colorScheme.error)
        }
    }
}

/**
 * 锚定菜单（2026-11-15）。
 *
 * ## 为什么要有它
 *
 * `DropdownMenu` 贴在**调用点那个布局节点的左缘**。所以菜单的位置不是"设置成对的位置"，
 * 而是"声明在哪个节点里"。2026-11-15 两次把菜单挂在整行 / 整屏根节点上，
 * 点的是行尾的「批准 ⌄」，菜单却从左边弹出来。
 *
 * 这个组件的唯一作用：**把触发控件和菜单强制放进同一个 Box**，
 * 让"锚点"这件事没有第二种写法。要用菜单 → 必须把触发控件交给它。
 *
 * 用法：
 * ```
 * MirrorAnchoredMenu(expanded, onDismiss, actions) {
 *     Text("批准 ⌄")      // 你点的那个东西
 * }
 * ```
 */
@Composable
fun MirrorAnchoredMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    actions: List<MirrorMenuAction>,
    content: @Composable () -> Unit
) {
    androidx.compose.foundation.layout.Box {
        content()
        MirrorMenu(expanded = expanded, onDismissRequest = onDismissRequest, actions = actions)
    }
}
