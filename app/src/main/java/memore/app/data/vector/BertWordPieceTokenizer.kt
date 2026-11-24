package memore.app.data.vector

/**
 * BERT WordPiece 分词（bge-small-zh-v1.5 用这一套）。
 *
 * 与 Python `transformers.BertTokenizer` 对齐的部分：
 *  - `do_lower_case = false`（bge 的 tokenizer_config 明确是 false，**不要**toLowerCase，
 *    否则 "Example" 和 "example" 会被并成一个词）
 *  - `tokenize_chinese_chars = true`：CJK 按**单字**切开
 *  - 标点单独成词；控制字符丢弃；空白只用于断词
 *  - WordPiece：贪心最长匹配，续接片段加 `##` 前缀，整词匹配失败 → `[UNK]`
 *  - 上限 512（含首尾的 [CLS]/[SEP]）
 *
 * 纯 Kotlin、无依赖，便于单测。
 */
class BertWordPieceTokenizer(
    private val vocab: Map<String, Int>,
    private val maxLength: Int = 512,
    private val doLowerCase: Boolean = false
) {
    private val clsId: Int = vocab["[CLS]"] ?: 101
    private val sepId: Int = vocab["[SEP]"] ?: 102
    private val unkId: Int = vocab["[UNK]"] ?: 100
    private val padId: Int = vocab["[PAD]"] ?: 0

    /** 只编码，不补齐。返回包含 [CLS] … [SEP] 的 id 序列（长度 ≤ maxLength）。 */
    fun encode(text: String): LongArray {
        val ids = mutableListOf<Long>()
        ids.add(clsId.toLong())
        outer@ for (token in basicTokenize(text)) {
            for (pieceId in wordPiece(token)) {
                if (ids.size >= maxLength - 1) break@outer
                ids.add(pieceId.toLong())
            }
        }
        ids.add(sepId.toLong())
        return ids.toLongArray()
    }

    /** 编码并补齐到 batch 内最长长度，同时给出 attention mask（1=真 token，0=padding）。 */
    fun encodePair(texts: List<String>): Pair<Array<LongArray>, Array<LongArray>> {
        val encoded = texts.map { encode(it) }
        val maxLen = (encoded.maxOfOrNull { it.size } ?: 2).coerceAtMost(maxLength)
        val ids = Array(encoded.size) { i ->
            val row = LongArray(maxLen) { padId.toLong() }
            encoded[i].copyInto(row)
            row
        }
        val mask = Array(encoded.size) { i ->
            LongArray(maxLen) { j -> if (j < encoded[i].size) 1L else 0L }
        }
        return ids to mask
    }

    // ── 基础切分 ────────────────────────────────────────────────

    private fun basicTokenize(text: String): List<String> {
        val out = mutableListOf<String>()
        val current = StringBuilder()

        fun flush() {
            if (current.isNotEmpty()) { out.add(current.toString()); current.clear() }
        }

        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            val charCount = Character.charCount(cp)
            i += charCount

            when {
                cp == 0 || cp == 0xFFFD || Character.isISOControl(cp) -> Unit // 丢弃
                isWhitespace(cp) -> flush()
                isChineseChar(cp) || isPunctuation(cp) -> { flush(); out.add(String(Character.toChars(cp))) }
                else -> current.appendCodePoint(cp)
            }
        }
        flush()

        return if (doLowerCase) out.map { it.lowercase() } else out
    }

    private fun wordPiece(word: String): List<Int> {
        if (word.length > MAX_CHARS_PER_WORD) return listOf(unkId)
        val ids = mutableListOf<Int>()
        var start = 0
        while (start < word.length) {
            var end = word.length
            var matched: Int? = null
            while (start < end) {
                val sub = word.substring(start, end)
                val candidate = if (start > 0) "##$sub" else sub
                val id = vocab[candidate]
                if (id != null) { matched = id; break }
                end--
            }
            if (matched == null) return listOf(unkId)
            ids.add(matched)
            start = end
        }
        return ids
    }

    // ── 字符判定 ────────────────────────────────────────────────

    private fun isWhitespace(cp: Int): Boolean =
        cp == 0x20 || cp == 0x09 || cp == 0x0A || cp == 0x0D || Character.isWhitespace(cp)

    private fun isChineseChar(cp: Int): Boolean =
        (cp in 0x4E00..0x9FFF) || (cp in 0x3400..0x4DBF) ||
            (cp in 0xF900..0xFAFF) || (cp in 0x20000..0x2A6DF)

    private fun isPunctuation(cp: Int): Boolean {
        // ASCII 标点区
        if ((cp in 33..47) || (cp in 58..64) || (cp in 91..96) || (cp in 123..126)) return true
        // 中日韩常用标点与全角符号
        if (cp in 0x3000..0x303F || cp in 0xFF01..0xFF0F || cp in 0xFF1A..0xFF20 || cp in 0xFF3B..0xFF40) return true
        // 常见中文标点（不在上面区段里的）
        if (cp in 0x2010..0x2027 || cp == 0x2026 || cp == 0x2030 || cp in 0x2032..0x2037) return true
        // 其余按 Unicode 类别
        val type = Character.getType(cp)
        return type == Character.OTHER_PUNCTUATION.toInt() ||
            type == Character.DASH_PUNCTUATION.toInt() ||
            type == Character.START_PUNCTUATION.toInt() ||
            type == Character.END_PUNCTUATION.toInt() ||
            type == Character.INITIAL_QUOTE_PUNCTUATION.toInt() ||
            type == Character.FINAL_QUOTE_PUNCTUATION.toInt()
    }

    companion object {
        const val UNK = "[UNK]"
        private const val MAX_CHARS_PER_WORD = 100

        /** 从 vocab.txt（每行一个 token，行号即 id）构建。 */
        fun fromLines(lines: List<String>, maxLength: Int = 512): BertWordPieceTokenizer {
            val map = HashMap<String, Int>(lines.size * 2)
            lines.forEachIndexed { index, line ->
                val token = line.trimEnd('\r', '\n')
                if (token.isNotEmpty()) map[token] = index
            }
            return BertWordPieceTokenizer(map, maxLength)
        }
    }
}
