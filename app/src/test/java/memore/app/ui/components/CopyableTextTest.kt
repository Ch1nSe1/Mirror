package memore.app.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class CopyableTextTest {

    @Test
    fun splitsByBlankLineAsFallback() {
        val segments = dialogueSegments("我：第一段\n\nAI：第二段")
        assertEquals(listOf("我：第一段", "AI：第二段"), segments)
    }

    @Test
    fun singleNewlineWithoutBlankLineStaysOneSegment() {
        assertEquals(listOf("我：第一段\nAI：第二段"), dialogueSegments("我：第一段\nAI：第二段"))
    }

    @Test
    fun neverSplitsMoreThanTwoForLegacy() {
        val segments = dialogueSegments("我：第一段\n\nAI：第二段\n继续第二段\n再一行")
        assertEquals(2, segments.size)
        assertEquals("我：第一段", segments[0])
        assertEquals("AI：第二段\n继续第二段\n再一行", segments[1])
    }

    @Test
    fun keepsSingleSegmentWhenNoSeparator() {
        assertEquals(listOf("只有一段"), dialogueSegments("只有一段"))
    }

    @Test
    fun explicitReplyUsesExactSplit() {
        val user = "第一段\n\n第二段"
        val reply = "你描述的这种状态，其实是一种保护性回避。"
        val content = "$user\n\n$reply"
        assertEquals(listOf("第一段\n\n第二段", reply), dialogueSegments(content, reply))
    }

    @Test
    fun legacyMultiParagraphUserPicksAiOpeningParagraph() {
        val content = "今天下午，我的家人来找了我。\n\n" +
            "他们是什么感觉我不清楚。\n\n" +
            "事件摘要 今日下午，家庭成员要求你规划长期事务。\n\n" +
            "---\n\n一、后续"
        val segments = dialogueSegments(content)
        assertEquals(
            listOf(
                "今天下午，我的家人来找了我。\n\n他们是什么感觉我不清楚。",
                "事件摘要 今日下午，家庭成员要求你规划长期事务。\n\n---\n\n一、后续"
            ),
            segments
        )
    }

    // ---- 转录进对话录（只填第二个框）----

    /**
     * 用户 2026-10-11：「长按某一段 → 转录 → 选对话录，**默认去到第二个框**」。
     *
     * 于是这条记录的形状是：第一个框空、第二个框（AI 那栏）有字。
     * 落库拼正文时不能留下空洞（否则读回来会切成两段、同一句话在界面上出现两遍）。
     */
    @Test
    fun transcribedDialogueKeepsOnlyTheAiBox() {
        val text = "看到1984第三部分了（196页）\n卢比扬卡也就如此了吧"
        assertEquals("第一个框空 → 正文里就只有那一段", text, dialogueRecordContent("", text))
        assertEquals("只有 AI 那一段时，读回来还是**一段**（第一个框保持空）", listOf(text), dialogueSegments(dialogueRecordContent("", text), text))
    }

    @Test
    fun normalDialogueStillJoinsBothBoxes() {
        assertEquals("我\n\nAI", dialogueRecordContent("我", "AI"))
        assertEquals("我", dialogueRecordContent("我", ""))
        assertEquals("", dialogueRecordContent("", ""))
        assertEquals(listOf("我", "AI"), dialogueSegments(dialogueRecordContent("我", "AI"), "AI"))
    }
}
