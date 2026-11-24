package memore.app.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 锚点名标点归一（2026-11-21）。
 *
 * 实测：模型会吐 `「TestWork」`、`TestArtistB`，同一个东西带不带标点会变成两个概念，
 * 映射表也匹配不上。这里锁住「只处理两头、不碰内部括号」的规则。
 */
class AnchorNameNormalizeTest {

    @Test
    fun `去掉包住名字的引号与括号`() {
        assertEquals("TestWork", normalizeAnchorName("「TestWork」"))
        assertEquals("TestWork", normalizeAnchorName("『TestWork』"))
        assertEquals("TestWork", normalizeAnchorName("\"TestWork\""))
        assertEquals("TestWork", normalizeAnchorName("【TestWork】"))
    }

    @Test
    fun `去掉末尾句读`() {
        assertEquals("TestConceptB", normalizeAnchorName("TestConceptB。"))
        assertEquals("TestArtistC", normalizeAnchorName("TestArtistC！"))
        assertEquals("TestArtistA", normalizeAnchorName("TestArtistA "))
    }

    @Test
    fun `内部的括号不动`() {
        assertEquals("测试词（testconcept）", normalizeAnchorName("测试词（testconcept）"))
    }

    @Test
    fun `查找键也用归一后的名字`() {
        assertTrue(entityLookupKeys("「TestWork」").contains("testwork"))
    }
}
