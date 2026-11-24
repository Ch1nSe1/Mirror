package memore.app.data.repository

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 关联边的依据校验（2026-11-19）。
 *
 * 关联页之所以会出现"两条记录没什么关联"，一半是因为模型可以编引文而没人查。
 * 这条规则要求两句原话各自出现在对应记录里，对不上就不落库。
 */
class LinkEvidenceTest {

    private val self = "怎么感觉治疗吃药之后精神状况越来越差了呢，或者说只是把原来的问题换了一种表现。"
    private val cand = "治疗第二天，我想记下的核心是认知上的变化，而不是情绪本身。"

    @Test
    fun `两句都能在原文里找到才通过`() {
        assertTrue(linkEvidenceGrounded(self, cand, "精神状况越来越差", "认知上的变化"))
    }

    @Test
    fun `发起方引文对不上就丢弃`() {
        assertFalse(linkEvidenceGrounded(self, cand, "我从来没有吃过药", "认知上的变化"))
    }

    @Test
    fun `候选方引文对不上就丢弃`() {
        assertFalse(linkEvidenceGrounded(self, cand, "精神状况越来越差", "我决定停药"))
    }

    @Test
    fun `空引文丢弃`() {
        assertFalse(linkEvidenceGrounded(self, cand, "", "认知上的变化"))
        assertFalse(linkEvidenceGrounded(self, cand, "精神状况越来越差", "   "))
    }

    @Test
    fun `候选片段为空时一律丢弃`() {
        assertFalse(linkEvidenceGrounded(self, "", "精神状况越来越差", "认知上的变化"))
    }
}
