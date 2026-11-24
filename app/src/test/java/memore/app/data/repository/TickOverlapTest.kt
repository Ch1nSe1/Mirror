package memore.app.data.repository

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 刻度区间重叠判定。
 *
 * 为什么要测它：重叠刻度会让**口径分裂** —— App 内两条刻度都会展示重叠区的记录，
 * 导出给外部 AI 时却只归给起点更晚的那条。判定逻辑一旦写歪，建刻度时的警告就会漏报或误报。
 * 区间语义同记录归属：`start <= t < end`，end 为 null 表示"至今"。
 */
class TickOverlapTest {

    private val d = 86_400_000L  // 一天

    @Test
    fun disjointIntervalsDoNotOverlap() {
        assertFalse(ticksOverlap(0L, 10L, 10L, 20L))   // 端点相接不算重叠（左闭右开）
        assertFalse(ticksOverlap(10L, 20L, 0L, 10L))
        assertFalse(ticksOverlap(0L, 10L, 20L, 30L))
    }

    @Test
    fun partialOverlapIsDetected() {
        assertTrue(ticksOverlap(0L, 10L, 5L, 15L))
        assertTrue(ticksOverlap(5L, 15L, 0L, 10L))
    }

    @Test
    fun nestingIsOverlap() {
        assertTrue(ticksOverlap(0L, 100L, 10L, 20L))   // 完全包含
        assertTrue(ticksOverlap(10L, 20L, 0L, 100L))
    }

    @Test
    fun identicalIntervalsOverlap() {
        assertTrue(ticksOverlap(0L, 10L, 0L, 10L))
    }

    @Test
    fun ongoingTickExtendsToInfinity() {
        // 进行中（end=null）= 至今：与任何开始于其起点之后的区间都重叠
        assertTrue(ticksOverlap(0L, null, 5L, 10L))
        assertTrue(ticksOverlap(5L, 10L, 0L, null))
        assertTrue(ticksOverlap(0L, null, 100L, 200L))
        // 都进行中 → 必然重叠
        assertTrue(ticksOverlap(0L, null, 5L, null))
    }

    @Test
    fun zeroLengthIntervalNeverOverlaps() {
        // 零长度区间（end == start）是空集，怎么都不该算重叠 —— 这正是校验要拦掉的东西
        assertFalse(ticksOverlap(5L, 5L, 0L, 10L))
        assertFalse(ticksOverlap(0L, 10L, 5L, 5L))
        assertFalse(ticksOverlap(5L, 5L, 5L, 5L))
    }

    @Test
    fun realWorldAdjacentMonthsDoNotOverlap() {
        // 相邻两段（如 8月 / 9月）首尾相接，不该报警
        val aug = 1_700_000_000_000L
        val sep = aug + 31 * d
        val oct = sep + 30 * d
        assertFalse(ticksOverlap(aug, sep, sep, oct))
        assertFalse(ticksOverlap(sep, oct, aug, sep))
    }
}
