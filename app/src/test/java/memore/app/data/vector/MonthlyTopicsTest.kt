package memore.app.data.vector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MonthlyTopicsTest {

    @Test
    fun `月份键按月切分`() {
        // 2026-07-10 与 2026-07-31 同月
        assertEquals("2026-07", MonthlyTopics.monthKey(1783000000000L))
        assertEquals(MonthlyTopics.monthKey(1783000000000L), MonthlyTopics.monthKey(1785000000000L))
    }

    @Test
    fun `两条明显不同的记录会分成两簇`() {
        val mk = { id: String, a: Float, b: Float ->
            MonthlyTopics.RecordPoint(id, "2026-07", floatArrayOf(a, b, 0f, 0f))
        }
        val pts = listOf(mk("a1", 1f, 0f), mk("a2", 0.9f, 0.1f), mk("b1", 0f, 1f), mk("b2", 0.1f, 0.9f))
        val labels = MonthlyTopics.kmeans(MonthlyTopics.center(pts), 2, seed = 1).first
        assertEquals(4, labels.size)
        assertEquals(2, labels.toSet().size)
        assertTrue(labels[0] == labels[1] || labels[0] == labels[2])
    }

    @Test
    fun `去均值后中心向量和为 0`() {
        val pts = listOf(
            MonthlyTopics.RecordPoint("a", "2026-07", floatArrayOf(1f, 0f)),
            MonthlyTopics.RecordPoint("b", "2026-07", floatArrayOf(0f, 1f))
        )
        val c = MonthlyTopics.center(pts)
        val m = MonthlyTopics.mean(c)
        assertEquals(0f, m[0], 0.001f)
        assertEquals(0f, m[1], 0.001f)
    }

    @Test
    fun `build 每月都产出至少一个簇`() {
        val pts = (0 until 12).map {
            MonthlyTopics.RecordPoint("r$it", if (it < 6) "2026-07" else "2026-08",
                floatArrayOf(if (it % 2 == 0) 1f else 0f, if (it % 2 == 0) 0f else 1f))
        }
        val res = MonthlyTopics.build(pts)
        assertEquals(2, res.size)
        assertTrue(res.all { it.clusters.isNotEmpty() })
        assertEquals(12, res.sumOf { it.clusters.sumOf { c -> c.recordIds.size } })
    }
}