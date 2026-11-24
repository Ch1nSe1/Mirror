package memore.app.data.vector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RrfTest {

    @Test
    fun `两路都靠前的排最前`() {
        val vector = listOf("a", "b", "c")
        val fts = listOf("b", "a", "d")
        val fused = Rrf.fuse(vector, fts)
        assertEquals("a 与 b 都出现在两路，应占前二", setOf("a", "b"), fused.take(2).map { it.first }.toSet())
        assertEquals("d 只出现在一路，排最后", "d", fused.last().first)
    }

    @Test
    fun `单路结果仍然保留`() {
        val fused = Rrf.fuse(listOf("x"), emptyList())
        assertEquals(listOf("x"), fused.map { it.first })
    }

    @Test
    fun `两路同一排名时分数相同`() {
        val fused = Rrf.fuse(listOf("p"), listOf("q"))
        assertEquals(fused[0].second, fused[1].second, 1e-6f)
    }

    @Test
    fun `重复 id 不会重复出现`() {
        val fused = Rrf.fuse(listOf("a", "a"), emptyList())
        assertEquals(1, fused.count { it.first == "a" })
    }

    @Test
    fun `分数随排名单调下降`() {
        val fused = Rrf.fuse(listOf("a", "b", "c", "d"), emptyList())
        for (i in 1 until fused.size) {
            assertTrue("第 $i 项分数不应高于前一项", fused[i].second <= fused[i - 1].second)
        }
    }
}
