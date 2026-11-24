package memore.app.data.vector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 来源系统「叙事分量」的后端口径（2026-11-17）。
 *
 * 这是纯计算测试，不需要设备/Room：
 *  - 权重 0 的点在聚类里不占分量（服务层会先把它过滤掉，这里测加权均值本身）
 *  - 权重 1 与 0.5 的两个点，质心必须偏向权重高的那个
 *  - 权重全为 1 时与旧算法结果一致（不改变既有行为）
 */
class MonthlyTopicsWeightTest {

    private fun vec(vararg v: Float) = v

    @Test
    fun weightedMean_pullsTowardHeavierPoint() {
        val a = vec(1f, 0f)
        val b = vec(0f, 1f)
        val m = MonthlyTopics.mean(listOf(a, b), listOf(1f, 0.5f))
        // 权重 1 : 0.5 → 合成向量 (0.667, 0.333)，x : y = 2 : 1
        assertTrue("x 应大于 y：${m.toList()}", m[0] > m[1])
        assertEquals(2f, m[0] / m[1], 0.001f)
    }

    @Test
    fun weightedMean_defaultIsPlainAverage() {
        val a = vec(1f, 0f)
        val b = vec(0f, 1f)
        val m = MonthlyTopics.mean(listOf(a, b))
        assertEquals(0.5f, m[0], 0.0001f)
        assertEquals(0.5f, m[1], 0.0001f)
    }

    @Test
    fun kmeans_weightDoesNotChangeClusterAssignment() {
        // 两组点：权重不同但位置分离，聚类结果必须仍然是 2 组，且成员不变
        val points = listOf(
            vec(1f, 0f), vec(0.98f, 0.02f),
            vec(0f, 1f), vec(0.02f, 0.98f)
        )
        val weights = floatArrayOf(1f, 0.5f, 1f, 0.5f)
        val (labels, _) = MonthlyTopics.kmeans(points, 2, seed = 7L, weights = weights)
        assertEquals(2, labels.toSet().size)
        assertEquals(labels[0], labels[1])
        assertEquals(labels[2], labels[3])
        assertTrue(labels[0] != labels[2])
    }

    @Test
    fun build_keepsSameClustersWhenWeightsAreAllOne() {
        val records = listOf(
            MonthlyTopics.RecordPoint("a", "2026-11", vec(1f, 0f), 1f),
            MonthlyTopics.RecordPoint("b", "2026-11", vec(0.99f, 0.01f), 1f),
            MonthlyTopics.RecordPoint("c", "2026-11", vec(0f, 1f), 1f),
            MonthlyTopics.RecordPoint("d", "2026-11", vec(0.01f, 0.99f), 1f)
        )
        val built = MonthlyTopics.build(records)
        assertEquals(1, built.size)
        val ids = built[0].clusters.flatMap { it.recordIds }.sorted()
        assertEquals(listOf("a", "b", "c", "d"), ids)
    }
}
