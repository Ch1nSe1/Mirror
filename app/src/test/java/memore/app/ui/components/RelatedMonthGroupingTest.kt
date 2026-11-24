package memore.app.ui.components

import memore.app.data.model.Record
import memore.app.data.model.RecordType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/**
 * 线索详情按月分组（2026-11-19）。
 *
 * 钉住：分组标签是「yyyy 年 M 月」、新的一月排在前、月内按时间倒序、
 * 同一月份的记录不许被拆到两组里。纯 JVM 测试。
 */
class RelatedMonthGroupingTest {

    private fun record(id: String, at: Long): Record =
        Record(id = id, type = RecordType.ESSAY, content = "内容$id", createdAt = at, updatedAt = at)

    private fun ts(year: Int, month: Int, day: Int): Long =
        Calendar.getInstance().apply {
            clear()
            set(year, month - 1, day, 12, 0, 0)
        }.timeInMillis

    @Test
    fun `按年月分组且新的一月在前`() {
        val entries = listOf(
            record("jul", ts(2026, 7, 3)),
            record("sep", ts(2026, 9, 10)),
            record("oct", ts(2026, 10, 28))
        ).map { RelatedEntry(it) }

        val groups = groupRelatedEntriesByMonth(entries)
        assertEquals(listOf("2026 年 10 月", "2026 年 9 月", "2026 年 7 月"), groups.map { it.label })
    }

    @Test
    fun `同一月份的记录进同一组`() {
        val entries = listOf(
            record("a", ts(2026, 8, 2)),
            record("b", ts(2026, 8, 20))
        ).map { RelatedEntry(it) }

        val groups = groupRelatedEntriesByMonth(entries)
        assertEquals(1, groups.size)
        assertEquals("2026 年 8 月", groups[0].label)
        assertEquals(2, groups[0].entries.size)
    }

    @Test
    fun `月内按时间倒序`() {
        val entries = listOf(
            record("early", ts(2026, 8, 2)),
            record("late", ts(2026, 8, 20))
        ).map { RelatedEntry(it) }

        val group = groupRelatedEntriesByMonth(entries).first()
        assertEquals(listOf("late", "early"), group.entries.map { it.record.id })
    }

    @Test
    fun `跨年时两个年份分开`() {
        val entries = listOf(
            record("dec", ts(2025, 12, 30)),
            record("jan", ts(2026, 1, 2))
        ).map { RelatedEntry(it) }

        val labels = groupRelatedEntriesByMonth(entries).map { it.label }
        assertEquals(listOf("2026 年 1 月", "2025 年 12 月"), labels)
        assertTrue(labels.size == 2)
    }

    @Test
    fun `空列表返回空分组`() {
        assertTrue(groupRelatedEntriesByMonth(emptyList()).isEmpty())
    }
}
