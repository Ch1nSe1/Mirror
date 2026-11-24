package memore.app.data.repository

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 送 AI 的草稿材料：跳过的问题必须消失（2026-11-20）。
 *
 * 以前「答：（跳过）」会原样进整理输入，成品里就冒出“这里我刻意跳过了”。
 */
class DraftInputSkipTest {

    @Test
    fun `跳过的问答对整对删除`() {
        val raw = "问：这篇只留一条主线，你想写哪种？\n答：（跳过）\n\n问：第4段那句你想说什么？\n答：电台太密"
        val out = stripSkippedAnswers(raw)
        assertFalse(out.contains("主线"))
        assertTrue(out.contains("电台太密"))
        assertFalse(out.contains("跳过"))
    }

    @Test
    fun `讨论的跳过同样删除`() {
        val raw = "讨论：第1段的“炼化”具体指什么？\n回应：（跳过）\n\n讨论：第2段的“实现”指什么？\n回应：带TTS的完整系统"
        val out = stripSkippedAnswers(raw)
        assertFalse(out.contains("炼化"))
        assertTrue(out.contains("TTS"))
    }

    @Test
    fun `没有跳过时原样保留`() {
        val raw = "问：A？\n答：a\n\n问：B？\n答：b"
        val out = stripSkippedAnswers(raw)
        assertTrue(out.contains("A？"))
        assertTrue(out.contains("b"))
    }

    @Test
    fun `括号写法不同也能识别`() {
        val raw = "问：A？\n答：(跳过)"
        assertFalse(stripSkippedAnswers(raw).contains("A？"))
    }
}
