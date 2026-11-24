package memore.app.data.vector

import java.security.MessageDigest

/**
 * 文本切块（纯函数，便于单测）。
 *
 * 规则（设计文档 §6）：
 *  - 目标块 ≤ [maxChars]（默认 300 字，给 512 token 留余量）
 *  - 优先在段落（空行）与换行处切；实在没有分隔就硬切
 *  - 相邻块重叠 [overlap] 字，避免边界语义被切断
 *  - 空块丢弃
 */
object TextChunker {

    const val DEFAULT_MAX_CHARS = 300
    const val DEFAULT_OVERLAP = 40

    fun chunk(
        text: String,
        maxChars: Int = DEFAULT_MAX_CHARS,
        overlap: Int = DEFAULT_OVERLAP
    ): List<String> {
        require(maxChars > overlap) { "maxChars 必须大于 overlap" }
        val normalized = text.replace("\r\n", "\n").trim()
        if (normalized.isEmpty()) return emptyList()

        // 先按段落/换行切成"自然段"，再装箱
        val paragraphs = normalized.split(Regex("\n+"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        val chunks = mutableListOf<String>()
        val current = StringBuilder()

        fun flush() {
            val s = current.toString().trim()
            if (s.isNotEmpty()) chunks += s
            current.clear()
        }

        for (p in paragraphs) {
            if (p.length > maxChars) {
                flush()
                chunks += hardSplit(p, maxChars, overlap)
                continue
            }
            if (current.length + p.length + 1 > maxChars) {
                flush()
                // 重叠：把上一块尾部接到新块开头
                if (chunks.isNotEmpty() && overlap > 0) {
                    val tail = chunks.last().takeLast(overlap)
                    if (tail.isNotBlank()) current.append(tail).append('\n')
                }
            }
            if (current.isNotEmpty()) current.append('\n')
            current.append(p)
        }
        flush()
        return chunks
    }

    private fun hardSplit(text: String, maxChars: Int, overlap: Int): List<String> {
        val out = mutableListOf<String>()
        var start = 0
        while (start < text.length) {
            val end = (start + maxChars).coerceAtMost(text.length)
            val piece = text.substring(start, end).trim()
            if (piece.isNotEmpty()) out += piece
            if (end >= text.length) break
            start = (end - overlap).coerceAtLeast(start + 1)
        }
        return out
    }

    /** 文本指纹：模型名 + 文本，换模型必须重算（设计文档 §5.4）。 */
    fun hash(model: String, text: String): String {
        val md = MessageDigest.getInstance("SHA-1")
        md.update(model.toByteArray(Charsets.UTF_8))
        md.update(0)
        md.update(text.toByteArray(Charsets.UTF_8))
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
