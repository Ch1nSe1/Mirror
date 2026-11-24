package memore.app.data.vector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2026-11-22：主题聚类的 P0/P1 约束（PC 测试后定的参数）。
 */
class MonthlyTopicsConstraintTest {

    private fun pt(id: String, month: String, x: Float, y: Float) =
        MonthlyTopics.RecordPoint(id, month, floatArrayOf(x, y), 1f)

    @Test
    fun `k 按 n 除以 12、簇小于 4 条不进主题`() {
        // 1 月：24 条 → k=2，两团各 12 条
        val jan = ArrayList<MonthlyTopics.RecordPoint>()
        repeat(12) { jan += pt("a$it", "2026-01", 0f + it * 0.01f, 0f) }
        repeat(12) { jan += pt("b$it", "2026-01", 10f + it * 0.01f, 10f) }
        // 2 月：3 条 → k=1，但小于 4 条 → 不出主题
        val feb = listOf(pt("c0", "2026-02", 0f, 0f), pt("c1", "2026-02", 0.1f, 0f), pt("c2", "2026-02", 0.2f, 0f))

        val result = MonthlyTopics.build(jan + feb)
        val janResult = result.first { it.month == "2026-01" }
        assertEquals(2, janResult.clusters.size)
        assertTrue(janResult.clusters.all { it.recordIds.size >= MonthlyTopics.MIN_CLUSTER_SIZE })
        val febResult = result.first { it.month == "2026-02" }
        assertTrue(febResult.clusters.isEmpty())
    }

    @Test
    fun `小于 4 条的簇并进最近的大簇而不是丢掉`() {
        // 42 条：两团各 18，另有两个 3 条的小簇（会被并进最近的大簇）
        val month = "2026-03"
        val pts = ArrayList<MonthlyTopics.RecordPoint>()
        repeat(18) { pts += pt("a$it", month, 0f + it * 0.01f, 0f) }
        repeat(18) { pts += pt("b$it", month, 10f + it * 0.01f, 0f) }
        repeat(3) { pts += pt("c$it", month, 30f + it * 0.01f, 0f) }
        repeat(3) { pts += pt("d$it", month, 50f + it * 0.01f, 0f) }

        val result = MonthlyTopics.build(pts)
        val clusters = result.first().clusters
        assertEquals(2, clusters.size)
        assertEquals(42, clusters.sumOf { it.recordIds.size })
        assertTrue(clusters.all { it.recordIds.size >= MonthlyTopics.MIN_CLUSTER_SIZE })
    }

    @Test
    fun `边修正把记录改判到邻居最多的簇`() {
        val ids = listOf("a", "b", "c", "d", "e")
        val labels = intArrayOf(0, 0, 0, 1, 1)
        // a、b 都只和 e（簇 1）相连 → 应改判到 1
        val neighbors = mapOf(
            "a" to setOf("e"),
            "b" to setOf("e"),
            "c" to emptySet<String>(),
            "d" to emptySet<String>(),
            "e" to setOf("a", "b")
        )
        val out = MonthlyTopics.refineLabels(labels, ids, neighbors)
        assertEquals(1, out[0])
        assertEquals(1, out[1])
        assertEquals(0, out[2])
    }

    @Test
    fun `指纹带算法版本`() {
        val pts = listOf(pt("a", "2026-01", 0f, 0f))
        assertTrue(MonthlyTopics.fingerprint(pts).startsWith("v" + MonthlyTopics.TOPIC_ALGO_VERSION + "|"))
    }
}
