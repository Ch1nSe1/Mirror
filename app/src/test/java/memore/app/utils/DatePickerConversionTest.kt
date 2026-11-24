package memore.app.utils

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.TimeZone

/**
 * 日期选择器 ↔ 本地零点 的时区换算。
 *
 * 这个 bug 的可怕之处在于**界面上看不出来**：选择器收/给的都是"该日历日在 UTC 的零点"，
 * 直接当本地时间用，在 UTC+8 下边界就变成当天 08:00 —— 而 `formatDateOnly` 显示出来的日期还是对的。
 * 所以这里把 JVM 默认时区钉死在 Asia/Shanghai 来测（UTC 下这个 bug 不会显现）。
 */
class DatePickerConversionTest {

    private val original: TimeZone = TimeZone.getDefault()

    @After
    fun restoreZone() {
        TimeZone.setDefault(original)
    }

    private fun useShanghai() {
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"))
        assertEquals(ZoneOffset.ofHours(8), ZoneId.systemDefault().rules.getOffset(Instant.now()))
    }

    /** 2026-09-24T00:00:00Z —— 日期选择器选中「9月24日」时给的值 */
    private val pickedSep24Utc = 1790208000000L
    /** 2026-09-24T00:00:00+08:00 —— 应该存下来的本地零点 */
    private val sep24LocalMidnight = 1790179200000L

    @Test
    fun pickerValueBecomesLocalMidnightOfTheSameDate() {
        useShanghai()
        val stored = datePickerToLocalStartOfDay(pickedSep24Utc)
        assertEquals(sep24LocalMidnight, stored)
        // 显示层必须仍显示 9/24（不能变成 9/23 或 9/25）
        assertEquals("2026-09-24", formatDateOnly(stored))
    }

    @Test
    fun rawPickerValueIsEightHoursOffInShanghai() {
        useShanghai()
        val raw = pickedSep24Utc
        val fixed = datePickerToLocalStartOfDay(raw)
        // 修之前就是直接用 raw：与正确值正好差一个时区偏移（8 小时）
        assertEquals(8L * 60 * 60 * 1000, raw - fixed)
        assertNotEquals("原样存就是当天 08:00 的边界", raw, sep24LocalMidnight)
    }

    @Test
    fun reverseConversionIsInverse() {
        useShanghai()
        assertEquals(pickedSep24Utc, localMillisToDatePicker(sep24LocalMidnight))
        assertEquals(sep24LocalMidnight, datePickerToLocalStartOfDay(localMillisToDatePicker(sep24LocalMidnight)))
    }

    @Test
    fun pickerOpensOnTheSameDayEvenInEarlyMorning() {
        useShanghai()
        // 本地 2026-09-24 03:00 —— 此刻 UTC 还是 9/23，不转换的话选择器会开在前一天
        val localThreeAm = sep24LocalMidnight + 3 * 60 * 60 * 1000
        val asPicker = localMillisToDatePicker(localThreeAm)
        assertEquals(pickedSep24Utc, asPicker)
        assertEquals("2026-09-24", formatDateOnly(datePickerToLocalStartOfDay(asPicker)))
    }

    @Test
    fun wholePickedDayFallsInsideTheBoundary() {
        useShanghai()
        val dayStart = datePickerToLocalStartOfDay(pickedSep24Utc)
        val dayEndExclusive = dayStart + 24L * 60 * 60 * 1000
        // 当天 20:00 写的记录必须落在 [零点, 次日零点) 内
        val evening = dayStart + 20 * 60 * 60 * 1000
        assertTrue(evening >= dayStart && evening < dayEndExclusive)
        // 反例：修之前存的是 UTC 零点（当天 08:00），当天 08:00 之后的记录会被切在外面
        assertTrue(evening > pickedSep24Utc)
    }

    @Test
    fun conversionIsIdempotentOnLocalMidnight() {
        useShanghai()
        val once = datePickerToLocalStartOfDay(pickedSep24Utc)
        val twice = datePickerToLocalStartOfDay(localMillisToDatePicker(once))
        assertEquals(once, twice)
    }
}
