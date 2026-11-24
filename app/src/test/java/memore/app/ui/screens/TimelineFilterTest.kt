package memore.app.ui.screens

import memore.app.data.model.Record
import memore.app.data.model.RecordType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

private const val DAY = 24L * 60 * 60 * 1000

/**
 * 时间线**附加筛选维度**（时间范围 / 附件 / 加密）的判定。
 *
 * 重点锁两件事：
 * ① 时间两端都**含当天**（选 8/1 ~ 8/31，8/31 晚上写的记录必须筛得出来）；
 * ② 多个维度是**与**的关系，且 `null` 维度不参与判断。
 */
class TimelineFilterTest {

    private fun at(dayOffset: Int, hour: Int = 12): Long {
        val c = Calendar.getInstance()
        c.set(Calendar.HOUR_OF_DAY, hour)
        c.set(Calendar.MINUTE, 0)
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        c.add(Calendar.DAY_OF_MONTH, dayOffset)
        return c.timeInMillis
    }

    private fun midnight(dayOffset: Int): Long {
        val c = Calendar.getInstance()
        c.set(Calendar.HOUR_OF_DAY, 0)
        c.set(Calendar.MINUTE, 0)
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        c.add(Calendar.DAY_OF_MONTH, dayOffset)
        return c.timeInMillis
    }

    private fun record(
        createdAt: Long,
        attachments: Int = 0,
        locked: Boolean = false
    ) = Record(
        id = "r-$createdAt-$attachments-$locked",
        type = RecordType.ESSAY,
        content = "c",
        createdAt = createdAt,
        updatedAt = createdAt,
        attachmentsCount = attachments,
        isLocked = locked
    )

    private fun match(r: Record, start: Long? = null, end: Long? = null, att: Boolean? = null, locked: Boolean? = null) =
        TimelineFilter.matches(r, start, end, att, locked)

    @Test
    fun noDimensionMeansEverythingPasses() {
        val r = record(at(0), attachments = 3, locked = true)
        assertTrue(match(r))
    }

    @Test
    fun startIsInclusive() {
        val start = midnight(-3)
        assertFalse("早于起始日：排除", match(record(at(-4)), start = start))
        assertTrue("起始日当天：包含", match(record(at(-3)), start = start))
        assertTrue("起始日零点整：包含", match(record(midnight(-3)), start = start))
        assertTrue("之后：包含", match(record(at(0)), start = start))
    }

    @Test
    fun endIncludesTheWholeDay() {
        val end = midnight(-1)          // 昨天
        assertTrue("止日早上：包含", match(record(at(-1, hour = 8)), end = end))
        assertTrue("止日 23:59：包含", match(record(end + DAY - 1), end = end))
        assertFalse("次日零点：排除", match(record(end + DAY), end = end))
        assertFalse("更晚：排除", match(record(at(0)), end = end))
    }

    @Test
    fun rangeWorksWithBothEndsAndSingleEnd() {
        val start = midnight(-5)
        val end = midnight(-1)
        assertTrue(match(record(at(-3)), start = start, end = end))
        assertFalse("范围之前", match(record(at(-9)), start = start, end = end))
        assertFalse("范围之后", match(record(at(2)), start = start, end = end))
        // 只给一端也能用
        assertTrue(match(record(at(-100)), end = end))
        assertFalse(match(record(at(0)), end = end))
    }

    @Test
    fun attachmentDimension() {
        val withAtt = record(at(0), attachments = 2)
        val without = record(at(0), attachments = 0)
        assertTrue(match(withAtt, att = true))
        assertFalse(match(without, att = true))
        assertTrue(match(without, att = false))
        assertFalse(match(withAtt, att = false))
    }

    @Test
    fun lockedDimension() {
        val locked = record(at(0), locked = true)
        val plain = record(at(0), locked = false)
        assertTrue(match(locked, locked = true))
        assertFalse(match(plain, locked = true))
        assertTrue(match(plain, locked = false))
        assertFalse(match(locked, locked = false))
    }

    @Test
    fun dimensionsCombineWithAnd() {
        val start = midnight(-10)
        val end = midnight(0)
        val target = record(at(-3), attachments = 1, locked = false)

        assertTrue(match(target, start = start, end = end, att = true, locked = false))
        assertFalse("附件维度不满足", match(target, start = start, end = end, att = false, locked = false))
        assertFalse("加密维度不满足", match(target, start = start, end = end, att = true, locked = true))
        assertFalse("时间维度不满足", match(record(at(-30), attachments = 1, locked = false), start = start, end = end, att = true, locked = false))
    }

    @Test
    fun activeDimensionCountTreatsRangeAsOne() {
        assertEquals(0, TimelineFilter.activeDimensionCount(null, null, null, null))
        assertEquals(1, TimelineFilter.activeDimensionCount(midnight(0), null, null, null))
        assertEquals(1, TimelineFilter.activeDimensionCount(null, midnight(0), null, null))
        assertEquals(1, TimelineFilter.activeDimensionCount(midnight(-1), midnight(0), null, null))
        assertEquals(3, TimelineFilter.activeDimensionCount(midnight(-1), midnight(0), true, false))
    }
}
