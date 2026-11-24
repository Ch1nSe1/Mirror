package memore.app.data.vector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SeriesDetectorTest {

    @Test
    fun `连载的编号会被剥掉`() {
        assertEquals("2026-7-10——2026-7-15", SeriesDetector.seriesKey("2026-7-10——2026-7-15 第一部分"))
        assertEquals("2026-7-10——2026-7-15", SeriesDetector.seriesKey("2026-7-10——2026-7-15 第三部分"))
        assertEquals("2026-7-16——2026-7-20", SeriesDetector.seriesKey("2026-7-16——2026-7-20 第二部分：结构"))
    }

    @Test
    fun `括号编号也会被剥掉`() {
        assertEquals("板块一 项目原点", SeriesDetector.seriesKey("板块一 项目原点（2）"))
        assertEquals("板块一 项目原点", SeriesDetector.seriesKey("板块一 项目原点 (3)"))
    }

    @Test
    fun `不带编号的记录前缀是自己`() {
        val k = SeriesDetector.seriesKey("洗完澡了，话说，探讨一些羞耻、带有较明显卫生色彩的话题")
        assertTrue(k.isNotBlank())
        assertTrue(k.length <= 24)
    }

    @Test
    fun `太短的前缀不算系列`() {
        assertEquals("", SeriesDetector.seriesKey("雨"))
        assertEquals("", SeriesDetector.seriesKey(""))
    }

    @Test
    fun `同前缀取最长的一条当代表`() {
        val texts = mapOf(
            "a" to "2026-7-10——2026-7-15 第一部分 短",
            "b" to "2026-7-10——2026-7-15 第二部分 " + "很长".repeat(50),
            "c" to "完全不同的记录内容在这里"
        )
        val keepers = SeriesDetector.keepers(texts)
        assertEquals("b", keepers["a"])
        assertEquals("b", keepers["b"])
        assertTrue(keepers["c"] == null)
    }
}