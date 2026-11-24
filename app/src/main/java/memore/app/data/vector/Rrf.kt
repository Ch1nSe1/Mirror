package memore.app.data.vector

/** RRF 融合（纯函数，便于单测）：只看排名，不比较两路分值，所以不用调权重。 */
object Rrf {
    const val K = 60f

    fun fuse(vectorRanked: List<String>, ftsRanked: List<String>): List<Pair<String, Float>> {
        val fused = HashMap<String, Float>()
        vectorRanked.forEachIndexed { rank, id ->
            fused[id] = (fused[id] ?: 0f) + 1f / (K + rank)
        }
        ftsRanked.forEachIndexed { rank, id ->
            fused[id] = (fused[id] ?: 0f) + 1f / (K + rank)
        }
        return fused.entries.sortedByDescending { it.value }.map { it.key to it.value }
    }
}
