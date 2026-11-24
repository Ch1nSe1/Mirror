package memore.app.data.vector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RankingTest {

    private fun vec(vararg v: Float) = VectorMath.l2Normalize(floatArrayOf(*v))

    @Test
    fun `候选不足 k 时原样返回`() {
        val items = listOf(
            Ranking.Item("a", 0.9f, vec(1f, 0f)),
            Ranking.Item("b", 0.8f, vec(0f, 1f))
        )
        assertEquals(items, Ranking.mmr(items, 5))
    }

    @Test
    fun `同一条记录只出现一次`() {
        val items = listOf(
            Ranking.Item("a", 0.90f, vec(1f, 0f)),
            Ranking.Item("a", 0.89f, vec(0.99f, 0.01f)),
            Ranking.Item("b", 0.80f, vec(0f, 1f))
        )
        val picked = Ranking.mmr(items, 2)
        assertEquals(2, picked.size)
        assertEquals(1, picked.count { it.id == "a" })
    }

    @Test
    fun `高度重复的第二条会被更不相似的候选顶掉`() {
        val items = listOf(
            Ranking.Item("a", 1.00f, vec(1f, 0f)),
            Ranking.Item("a2", 0.99f, vec(1f, 0.01f)),   // 与 a 几乎一样
            Ranking.Item("b", 0.85f, vec(0f, 1f))        // 相关低一点但不重复
        )
        val picked = Ranking.mmr(items, 2, lambda = 0.5f)
        assertTrue("应选 a 和 b，实际 ${picked.map { it.id }}", picked.map { it.id }.containsAll(listOf("a", "b")))
    }

    @Test
    fun `lambda 为 1 时退化为纯按分数`() {
        val items = listOf(
            Ranking.Item("a", 0.90f, vec(1f, 0f)),
            Ranking.Item("b", 0.80f, vec(0f, 1f)),
            Ranking.Item("c", 0.70f, vec(0f, 0f, 1f))
        )
        val picked = Ranking.mmr(items, 2, lambda = 1f)
        assertEquals(listOf("a", "b"), picked.map { it.id })
    }

    @Test
    fun `没有向量时也能工作（视为完全不重复）`() {
        val items = listOf(
            Ranking.Item("a", 0.9f, null),
            Ranking.Item("b", 0.8f, null),
            Ranking.Item("c", 0.7f, null)
        )
        val picked = Ranking.mmr(items, 2)
        assertEquals(listOf("a", "b"), picked.map { it.id })
    }

    @Test
    fun `低分阈值是有意义的正数`() {
        assertTrue(Ranking.WEAK_SCORE > 0f)
        assertTrue(Ranking.WEAK_SCORE < 0.6f)
    }
}
