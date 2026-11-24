package memore.app.data.repository

import memore.app.data.model.RecordRelation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RelationConfidenceTest {

    private fun edge(
        type: String = "LINK",
        source: String = "AI",
        quoteA: String = "",
        quoteB: String = "",
        score: Double = 0.0,
        status: String = "ACTIVE"
    ) = RecordRelation(
        fromId = "a", toId = "b", type = type, source = source,
        quoteA = quoteA, quoteB = quoteB, score = score, status = status
    )

    @Test
    fun `引文接地 + 向量 + 共享锚点的边更高`() {
        val strong = RelationConfidence.evaluate(
            edge(quoteA = "左边原句", quoteB = "右边原句", score = 0.8),
            setOf("测试甲", "TestConcept"), setOf("测试甲"), "左边原句在这里", "右边原句在这里"
        )
        val weak = RelationConfidence.evaluate(
            edge(), emptySet(), emptySet(), "x", "y"
        )
        assertTrue(strong.value > weak.value)
        assertEquals(1.0, strong.quote, 0.0)
        assertEquals(0.5, strong.anchors, 0.0)
    }

    @Test
    fun `引文不在正文里则不算接地`() {
        val parts = RelationConfidence.evaluate(
            edge(quoteA = "不存在的句子", quoteB = "右边原句"),
            emptySet(), emptySet(), "左边原句在这里", "右边原句在这里"
        )
        assertEquals(0.0, parts.quote, 0.0)
    }

    @Test
    fun `历史 USER 边没有证据时置信度低`() {
        val parts = RelationConfidence.evaluate(edge(source = "USER"), emptySet(), emptySet(), "x", "y")
        assertTrue("USER 裸边应低", parts.value < 0.2)
    }

    @Test
    fun `被拒绝的边置信度为 0`() {
        val parts = RelationConfidence.evaluate(
            edge(status = "REJECTED", quoteA = "a", quoteB = "b", score = 0.9),
            setOf("x"), setOf("x"), "a", "b"
        )
        assertEquals(0.0, parts.value, 0.0)
    }

    @Test
    fun `具体类型比泛 LINK 高`() {
        val cite = RelationConfidence.evaluate(edge(type = "CITE"), emptySet(), emptySet(), null, null)
        val link = RelationConfidence.evaluate(edge(type = "LINK"), emptySet(), emptySet(), null, null)
        assertTrue(cite.value > link.value)
    }
}
