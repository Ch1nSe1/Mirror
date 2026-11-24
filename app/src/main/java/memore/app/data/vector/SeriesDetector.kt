package memore.app.data.vector

import java.util.regex.Pattern

/**
 * 系列检测（2026-11-05）：把「同一件事被拆成好几条」的记录认出来。
 *
 * 背景：实测你库里有 21 条记录两两相似度 ≥0.92，其中一组 21 条——
 * 它们是「2026-7-10——2026-7-15 第一部分/第二部分/…」这种**连载**，不是重复。
 * 语义去重会把它们错误合并，所以改用**标题前缀**识别：同前缀视为一个系列。
 *
 * 用途：搜索结果、关联候选里同一系列只留一条（否则一次查询会返回同一天的 5 截）。
 */
object SeriesDetector {

    /** 第一节「第N部分 / 第N篇」这类序号。 */
    private val PART = Pattern.compile("第[一二三四五六七八九十百0-9]+\\s*(部分|篇|章|段|回)")
    /** 括号编号：（2） / (3)。 */
    private val PAREN_NO = Pattern.compile("[（(]\\s*\\d+\\s*[)）]")
    /** markdown 分隔线（3 个以上短横）——只认这个，不碰「——」中文破折号。 */
    private val DASH_SEP = Pattern.compile("\\s*-{3,}")
    private val SPACES = Pattern.compile("\\s+")
    private val TRAIL_PUNCT = Pattern.compile("[\\s：:·、，,。.\\-—–]+$")

    /**
     * 取标题区的「系列名」：**在第一个序号/分隔符处切断**，后面的都不算。
     *
     * 为什么要切断而不是删掉：删掉「第二部分」后，后面的副标题仍会留在前缀里，
     * 于是「…第二部分：结构」和「…第三部分：转折」又会分成两个系列。
     */
    fun seriesKey(text: String, headChars: Int = 60): String {
        val flat = SPACES.matcher(text.trim()).replaceAll(" ")
        if (flat.isEmpty()) return ""
        val head = if (flat.length > headChars) flat.substring(0, headChars) else flat

        val cut = listOf(PART, PAREN_NO, DASH_SEP)
            .mapNotNull { p -> p.matcher(head).let { if (it.find()) it.start() else null } }
            .minOrNull() ?: head.length

        var key = head.substring(0, cut)
        key = TRAIL_PUNCT.matcher(key).replaceAll("").trim()
        return if (key.length < 4) "" else key.take(24)
    }

    /**
     * 给一批记录分组：key 为空的不参与分组（各自独立）。
     * 返回 recordId → 组代表（组里最长的那条）；只有一条的组不返回。
     */
    fun keepers(texts: Map<String, String>): Map<String, String> {
        val groups = LinkedHashMap<String, MutableList<String>>()
        for ((id, t) in texts) {
            val k = seriesKey(t)
            if (k.isEmpty()) continue
            groups.getOrPut(k) { mutableListOf() }.add(id)
        }
        val out = HashMap<String, String>(texts.size)
        for ((_, members) in groups) {
            if (members.size <= 1) continue
            val keep = members.maxByOrNull { (texts[it] ?: "").length } ?: members.first()
            for (m in members) out[m] = keep
        }
        return out
    }
}