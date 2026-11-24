package memore.app.data.vector

/**
 * 排序后处理：MMR 去重 + 低分判定。
 *
 * 为什么需要 MMR：一条长记录会被切成很多块，纯按分数取 Top-K 时，
 * 前 5 条经常全是同一篇（同一系列）的连续段落——信息重复。
 * MMR（Maximal Marginal Relevance）在"相关"和"不重复"之间取平衡。
 */
object Ranking {

    data class Item(val id: String, val score: Float, val vec: FloatArray?)

    const val DEFAULT_LAMBDA = 0.7f
    /** 低于这个分数就不该假装是"命中"（实测有真命中时通常 0.5+） */
    const val WEAK_SCORE = 0.40f

    /**
     * @param lambda 1 = 只看相关；0 = 只看不重复
     */
    fun mmr(candidates: List<Item>, k: Int, lambda: Float = DEFAULT_LAMBDA): List<Item> {
        if (candidates.size <= k) return candidates
        val pool = candidates.toMutableList()
        val picked = ArrayList<Item>(k)

        while (picked.size < k && pool.isNotEmpty()) {
            var best: Item? = null
            var bestValue = -Float.MAX_VALUE
            for (c in pool) {
                val redundancy = picked.maxOfOrNull { p -> similarity(c, p) } ?: 0f
                val value = lambda * c.score - (1 - lambda) * redundancy
                if (value > bestValue) { bestValue = value; best = c }
            }
            val chosen = best ?: break
            picked += chosen
            pool.remove(chosen)

            // 同一条记录的其它块也一并移出候选（避免同一篇重复出现）
            pool.removeAll { it.id == chosen.id }
        }
        return picked
    }

    private fun similarity(a: Item, b: Item): Float {
        val va = a.vec
        val vb = b.vec
        return if (va == null || vb == null || va.size != vb.size) 0f
        else VectorMath.cosine(va, vb)
    }
}