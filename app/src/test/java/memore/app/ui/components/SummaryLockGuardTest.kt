package memore.app.ui.components

import memore.app.data.model.Record
import memore.app.data.model.RecordType
import memore.app.data.model.AnalysisData
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 总结面板的锁保护（2026-11-19 修的 bug）。
 *
 * 现象：区域页等入口没有调用方保护，点「总结」就把锁定记录的摘要摊出来了。
 * 现在规则收在 [summaryTextFor]：**锁定记录只认临时解密后拿到的 override**，
 * 绝不回落到库里存的 analysis。
 */
class SummaryLockGuardTest {

    private fun record(locked: Boolean, summary: String?, dialogue: String = ""): Record =
        Record(
            id = "r1",
            type = RecordType.ESSAY,
            content = "正文",
            createdAt = 1L,
            updatedAt = 1L,
            isLocked = locked,
            analysis = summary?.let { AnalysisData(summary = it) },
            dialogueAiContent = dialogue
        )

    @Test
    fun `锁定记录不显示库里的总结`() {
        assertEquals("", summaryTextFor(record(locked = true, summary = "这是摘要"), summaryOverride = null))
    }

    @Test
    fun `锁定记录不显示对话 AI 内容`() {
        assertEquals("", summaryTextFor(record(locked = true, summary = null, dialogue = "对话摘要"), summaryOverride = null))
    }

    @Test
    fun `锁定记录临时解密后可以看`() {
        val r = record(locked = true, summary = "这是摘要")
        assertEquals("解密后的摘要", summaryTextFor(r, summaryOverride = "解密后的摘要"))
    }

    @Test
    fun `未锁定记录正常显示总结`() {
        val r = record(locked = false, summary = "这是摘要")
        assertEquals("这是摘要", summaryTextFor(r, summaryOverride = null))
    }

    @Test
    fun `未锁定记录没有总结时回落到对话内容`() {
        val r = record(locked = false, summary = null, dialogue = "对话摘要")
        assertEquals("对话摘要", summaryTextFor(r, summaryOverride = null))
    }

    @Test
    fun `override 为空串时不覆盖`() {
        val r = record(locked = false, summary = "这是摘要")
        assertEquals("这是摘要", summaryTextFor(r, summaryOverride = "  "))
    }
}
