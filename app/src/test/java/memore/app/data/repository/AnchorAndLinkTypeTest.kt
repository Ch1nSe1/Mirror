package memore.app.data.repository

import memore.app.data.model.RecordEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 锚点与边类型的硬约束（2026-11-19）。
 *
 * 锚点是"确定性信号"的前提：名字要短、依据要在原文里。边类型错了会把
 * "相关"误标成"更正"，所以只认三种，其余一律回 LINK。
 */
class AnchorAndLinkTypeTest {

    private val text = "今天想到 TestConcept 了，她站在灯光下。晚上又听了TestArtistB的《TestSong》。"

    @Test
    fun `锚点依据必须在原文里`() {
        assertTrue(anchorGrounded("想到 TestConcept 了", text))
        assertFalse(anchorGrounded("想到 测试甲 了", text))
    }

    @Test
    fun `空依据不算锚点`() {
        assertFalse(anchorGrounded("", text))
        assertFalse(anchorGrounded("   ", text))
    }

    @Test
    fun `锚点名称长度限制`() {
        assertTrue(RecordEntity.isValidName("TestConcept"))
        assertTrue(RecordEntity.isValidName("TestArtistB"))
        assertFalse(RecordEntity.isValidName(""))
        assertFalse(RecordEntity.isValidName("这个名称明显太长了不合规名称这个名称明显太长了"))
        assertFalse(RecordEntity.isValidName("含\n换行"))
    }

    @Test
    fun `边类型只认三种_其余回落LINK`() {
        assertEquals("CORRECT", normalizedRelationType("correct"))
        assertEquals("SUPPLEMENT", normalizedRelationType(" SUPPLEMENT "))
        assertEquals("CITE", normalizedRelationType("Cite"))
        assertEquals("LINK", normalizedRelationType("RELATED"))
        assertEquals("LINK", normalizedRelationType(""))
    }
}
