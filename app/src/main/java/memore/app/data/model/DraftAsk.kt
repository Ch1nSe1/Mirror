package memore.app.data.model

import memore.app.utils.JsonCleaner
import org.json.JSONArray

/**
 * AI 提出的一个问题 / 给出的建议 —— 两者结构一样：一句话 + 2~4 个选项。
 * 讨论功能的"建议"就是同一张卡，只是选项偏向表态（采纳 / 不采纳 / 换个方向…）。
 */
data class DraftAskItem(
    val text: String,
    val options: List<String>
)

/**
 * 解析 AI 返回的 JSON 数组：
 * `[{"question":"…","options":["…","…"]},{"advice":"…","options":[…]}]`
 *
 * 容错：question / advice 两种键名都认（质疑用前者、讨论用后者）；选项过滤空白；
 * 少于 1 个选项的条目**丢掉**（没选项的卡片没法点 ✗）。
 * 解析失败返回空列表 —— 上层按"这次没问出东西"处理，不落库。
 */
fun parseDraftAskItems(raw: String): List<DraftAskItem> {
    if (raw.isBlank()) return emptyList()
    val cleaned = JsonCleaner.clean(raw)
    val array = try {
        JSONArray(cleaned)
    } catch (_: Exception) {
        // 偶尔会包一层对象或前后带话 —— 退一步：从第一个 '[' 到最后一个 ']' 里再试一次
        val start = cleaned.indexOf('[')
        val end = cleaned.lastIndexOf(']')
        if (start < 0 || end <= start) return emptyList()
        try {
            JSONArray(cleaned.substring(start, end + 1))
        } catch (_: Exception) {
            return emptyList()
        }
    }
    val out = ArrayList<DraftAskItem>()
    for (i in 0 until array.length()) {
        val obj = array.optJSONObject(i) ?: continue
        val text = (obj.optString("question").takeIf { it.isNotBlank() }
            ?: obj.optString("advice").takeIf { it.isNotBlank() }
            ?: continue).trim()
        val opts = ArrayList<String>()
        val arr = obj.optJSONArray("options")
        if (arr != null) {
            for (j in 0 until arr.length()) {
                val o = arr.optString(j).trim()
                if (o.isNotEmpty()) opts.add(o)
            }
        }
        if (opts.isEmpty()) continue
        out.add(DraftAskItem(text, opts.take(4)))
    }
    // 用户定的是 2~5 个问题；模型偶尔会多给，这里按上限收口（少于 1 个就等于没问出来）
    return out.take(5)
}

/** 一条问答/讨论在草稿里存成什么样（成文格式，送进 AI 时也照这个读）。 */
fun formatAskBlock(item: DraftAskItem, answer: String?): String {
    val head = if (answer.isNullOrBlank()) "（跳过）" else answer.trim()
    return "问：${item.text}\n答：$head"
}

/** 讨论块：建议 + 作者的回应。 */
fun formatSuggestBlock(item: DraftAskItem, response: String?): String {
    val head = if (response.isNullOrBlank()) "（跳过）" else response.trim()
    return "讨论：${item.text}\n回应：$head"
}
