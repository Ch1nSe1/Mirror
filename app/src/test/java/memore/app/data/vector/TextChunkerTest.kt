package memore.app.data.vector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextChunkerTest {

    @Test
    fun `空文本不产生块`() {
        assertTrue(TextChunker.chunk("").isEmpty())
        assertTrue(TextChunker.chunk("   \n\n  ").isEmpty())
    }

    @Test
    fun `短文本就是一块`() {
        val chunks = TextChunker.chunk("今天很累")
        assertEquals(1, chunks.size)
        assertEquals("今天很累", chunks[0])
    }

    @Test
    fun `每块不超过上限`() {
        val text = buildString {
            repeat(20) { append("这是第 $it 段，用来测试切块是否遵守上限。\n") }
        }
        val chunks = TextChunker.chunk(text, maxChars = 80, overlap = 10)
        assertTrue("块数应大于 1", chunks.size > 1)
        chunks.forEach { assertTrue("块长 ${it.length} 超过 80", it.length <= 80) }
    }

    @Test
    fun `超长单段会被硬切且保留重叠`() {
        val text = "啊".repeat(700)
        val chunks = TextChunker.chunk(text, maxChars = 300, overlap = 40)
        assertTrue(chunks.size >= 3)
        chunks.forEach { assertTrue(it.length <= 300) }
        // 重叠：第二块开头应出现在第一块结尾
        val tail = chunks[0].takeLast(20)
        assertTrue("重叠丢失", chunks[1].contains(tail.take(10)))
    }

    @Test
    fun `段落优先，不会把两段硬拼进一块`() {
        val a = "甲".repeat(50)
        val b = "乙".repeat(50)
        val chunks = TextChunker.chunk("$a\n\n$b", maxChars = 300)
        assertEquals(1, chunks.size) // 能装下就装一块
        assertTrue(chunks[0].contains("甲") && chunks[0].contains("乙"))
    }

    @Test
    fun `hash 对同一文本稳定，对模型敏感`() {
        val h1 = TextChunker.hash("m1", "内容")
        val h2 = TextChunker.hash("m1", "内容")
        val h3 = TextChunker.hash("m2", "内容")
        val h4 = TextChunker.hash("m1", "内容 ")
        assertEquals(h1, h2)
        assertNotEquals(h1, h3)
        assertNotEquals(h1, h4)
        assertEquals(40, h1.length) // sha1 hex
    }

    @Test
    fun `空白段落不会产生空块`() {
        val text = "第一段\n\n\n\n第二段\n   \n第三段"
        val chunks = TextChunker.chunk(text)
        assertTrue(chunks.all { it.isNotBlank() })
    }
}
