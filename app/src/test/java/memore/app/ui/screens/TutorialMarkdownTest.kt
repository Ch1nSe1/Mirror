package memore.app.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TutorialMarkdownTest {

    @Test
    fun parsesHeadingsBulletsAndTables() {
        val md = """
            # 标题
            ## 第一章
            - 普通条目
              - 缩进条目
            ## 第二章
            | 名称 | 作用 |
            |---|---|
            | 时间线 | 记录 |
        """.trimIndent()
        val blocks = parseTutorialMarkdown(md)
        assertEquals(5, blocks.size)
        assertEquals(TutorialBlock.Heading(2, "第一章"), blocks[0])
        assertEquals(TutorialBlock.Bullet(false, 0, 0, "普通条目"), blocks[1])
        assertEquals(TutorialBlock.Bullet(false, 0, 1, "缩进条目"), blocks[2])
        assertEquals(TutorialBlock.Heading(2, "第二章"), blocks[3])
        val table = blocks[4] as TutorialBlock.Table
        assertEquals(listOf("名称", "作用"), table.header)
        assertEquals(listOf(listOf("时间线", "记录")), table.rows)
    }

    @Test
    fun parsesQuoteAndOrderedList() {
        val md = """
            > 引用内容
            1. 第一步
            2. 第二步
        """.trimIndent()
        val blocks = parseTutorialMarkdown(md)
        assertEquals(TutorialBlock.Quote("引用内容"), blocks[0])
        assertEquals(TutorialBlock.Bullet(true, 1, 0, "第一步"), blocks[1])
        assertEquals(TutorialBlock.Bullet(true, 2, 0, "第二步"), blocks[2])
    }

    @Test
    fun inlineFormattingKeepsTextAndRemovesMarkers() {
        val annotated = tutorialAnnotated("用 **加粗** 和 `代码` 测试")
        assertEquals("用 加粗 和 代码 测试", annotated.text)
        assertTrue(annotated.spanStyles.isNotEmpty())
    }
}
