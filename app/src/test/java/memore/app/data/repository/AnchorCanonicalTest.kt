package memore.app.data.repository

import memore.app.data.model.AliasGroup
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 锚点别名归一（2026-11-19）。
 *
 * 关联页的「按锚点」要靠它把 `TestConcept` / `testconcept` / 别名归到同一个父下面；
 * 情绪组不参与（那是情绪词库，不是锚点）。
 */
class AnchorCanonicalTest {

    @Test
    fun `别名映射到标准名_大小写不敏感`() {
        val groups = listOf(
            AliasGroup(standardName = "TestConcept", aliases = listOf("testconcept", "テストス"), kind = "concept")
        )
        val map = canonicalNames(groups)
        assertEquals("TestConcept", map["testconcept"])
        assertEquals("TestConcept", map["テストス"])
        assertEquals("TestConcept", map["testconcept".lowercase()])
    }

    @Test
    fun `情绪组不参与锚点归一`() {
        val groups = listOf(
            AliasGroup(standardName = "喜悦", aliases = listOf("开心"), kind = "emotion")
        )
        val map = canonicalNames(groups)
        assertEquals(null, map["开心"])
    }

    @Test
    fun `同名多组时先出现的优先`() {
        val groups = listOf(
            AliasGroup(standardName = "TestArtistB", aliases = listOf("yorushika")),
            AliasGroup(standardName = "TestArtistBAlt", aliases = listOf("TestArtistB"))
        )
        val map = canonicalNames(groups)
        assertEquals("TestArtistB", map["yorushika"])
        assertEquals("TestArtistB", map["testartistb"])
    }
}
