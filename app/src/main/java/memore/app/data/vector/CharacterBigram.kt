package memore.app.data.vector

/**
 * 字符 bigram 词法匹配（2026-11-06）。
 *
 * 为什么需要它：FTS4 的默认分词器不认中文，一整句中文会被当成**一个 token**，
 * 所以 `MATCH` 只有“整句原样出现”才命中——实测「孤独」在 14 条记录里出现，FTS 命中 0 条。
 * 也就是说「向量 + FTS 融合」里的 FTS 一路从来没贡献过分。
 *
 * 换成字符 bigram：查询「跟AI定规矩那会儿」→ 跟A / AI / I定 / 定规 / 规矩 / 矩那 / …，
 * 只要记录里出现「规矩」，就能得分。实测（29 条真实查询）模糊型 R@1 从 0.55 提到 0.72。
 */
object CharacterBigram {

    /** 把文本切成 2 字片段集合（去掉空白）；单字文本返回自身。 */
    fun bigrams(text: String): Set<String> {
        val s = text.filterNot { it.isWhitespace() }
        if (s.isEmpty()) return emptySet()
        if (s.length == 1) return setOf(s)
        val out = HashSet<String>(s.length)
        for (i in 0 until s.length - 1) out += s.substring(i, i + 2)
        return out
    }

    /** 查询的 bigram 有多少比例出现在文档里（0..1）。 */
    fun score(queryBigrams: Set<String>, doc: String): Float {
        if (queryBigrams.isEmpty()) return 0f
        // 单字查询：bigram 集合只有一个单字，用包含判断（否则「雨」在「下雨了」里永远命不中）
        if (queryBigrams.size == 1) {
            val only = queryBigrams.first()
            if (only.length == 1) return if (doc.contains(only)) 1f else 0f
        }
        val docBigrams = bigrams(doc)
        if (docBigrams.isEmpty()) return 0f
        var hit = 0
        for (g in queryBigrams) if (g in docBigrams) hit++
        return hit.toFloat() / queryBigrams.size
    }

    fun score(query: String, doc: String): Float = score(bigrams(query), doc)

    /** 词法信号的权重：实测 0.20 最好（0.1 偏弱、0.35 开始压过语义）。 */
    const val WEIGHT = 0.20f
}
