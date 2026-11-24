package memore.app.ui.theme

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * 文字令牌（2026-11-05，A 方案）：**衬线 = 叙述文本，无衬线 = 界面文本**。
 *
 * 参照 Claude 的做法：标题用衬线（weight 500，负字距），正文/元信息用无衬线。
 * 全站只允许这几档，别再随手写 fontSize —— 之前有 12 种字号、5 档"标题"。
 *
 *  页面标题 22 衬线 500   ← 时间线 / 秒针 / 设置 / 主题 …
 *  区块标题 17 衬线 500   ← 记录详情的「相似」「历史版本」
 *  阅读正文 15.5 衬线 400 ← 记录正文、刻度总结
 *  界面正文 15 无衬线
 *  元信息 12.5 无衬线     ← 时间、条数
 *  标签 11 无衬线 500     ← 情绪名、类型
 *  小标 10 无衬线 500 +1.2 字距 ← 「注脚」「相似」这种分节
 */
object MemoreType {

    /** 系统衬线；机型没有宋体时会回落到黑体（A 方案的降级是"看不出变化"，不会坏）。 */
    private val Serif = FontFamily.Serif

    val pageTitle = TextStyle(
        fontFamily = Serif,
        fontSize = 22.sp,
        lineHeight = 30.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = (-0.2).sp
    )

    /**
     * 工具页的页面标题（设置 / 秒针 / 各管理页）：用无衬线。
     * 理由见文档：**标题属于界面 chrome，衬线留给"读物"**（记录正文、刻度总结）。
     */
    val pageTitleSans = TextStyle(
        fontSize = 18.sp,
        lineHeight = 26.sp,
        fontWeight = FontWeight.SemiBold
    )

    val sectionTitle = TextStyle(
        fontFamily = Serif,
        fontSize = 17.sp,
        lineHeight = 24.sp,
        fontWeight = FontWeight.Medium
    )

    /** 阅读正文：行高 1.87，字距 0.2（宋体需要一点气口）。 */
    val reading = TextStyle(
        fontFamily = Serif,
        fontSize = 15.5.sp,
        lineHeight = 29.sp,
        letterSpacing = 0.2.sp,
        fontWeight = FontWeight.Normal
    )

    val body = TextStyle(fontSize = 15.sp, lineHeight = 27.sp)

    /** 对话框主按钮文字：15sp SemiBold。原先 4 处手写，归到这一档。 */
    val button = TextStyle(fontSize = 15.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold)
    val meta = TextStyle(fontSize = 12.5.sp, lineHeight = 18.sp)
    val label = TextStyle(fontSize = 11.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium)
    val micro = TextStyle(fontSize = 10.sp, lineHeight = 14.sp, fontWeight = FontWeight.Medium, letterSpacing = 1.2.sp)
}