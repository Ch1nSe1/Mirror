package memore.app.data.repository

import memore.app.data.model.AliasGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 锚点抽取的两道闸（2026-11-21）。
 *
 * 背景：实测模型会把「我 / 你 / 2026年7月17日 / JSON 协议 / 半句话」当锚抽出来，
 * 变成「按概念」里最显眼的几块；`テスト（testconcept）` 这种写法又因为不在别名组里，
 * 跟 `TestConcept` 分成两个锚。
 *
 * 这里钉住：
 * ① `isNoiseAnchor` 该丢的丢、该留的留；
 * ② `entityLookupKeys` / `canonicalNames` 能把括号写法归到同一个标准名。
 */
class AnchorNoiseTest {

    // ---- ① 噪音过滤 ----

    @Test
    fun `代词不当锚`() {
        listOf("我", "你", "您", "我们", "大家", "自己", "他", "他们").forEach {
            assertTrue("$it 应被丢弃", isNoiseAnchor(it))
        }
    }

    @Test
    fun `日期和纯数字不当锚`() {
        listOf("2026年7月17日", "2026-07-17", "2026/7", "2026.07.17", "20261121").forEach {
            assertTrue("$it 应被丢弃", isNoiseAnchor(it))
        }
    }

    @Test
    fun `整句带标点的长文本不当锚`() {
        assertTrue(isNoiseAnchor("我想说的是这句话其实还没有说完呢，对吧"))
    }

    @Test
    fun `真实的名字要留着`() {
        listOf("TestConcept", "TestArtistA", "TestArtistB", "TestArtistC", "TestLive", "Mirror", "testartistd").forEach {
            assertFalse("$it 应保留", isNoiseAnchor(it))
        }
    }

    @Test
    fun `空白与超长丢掉`() {
        assertTrue(isNoiseAnchor("   "))
        assertTrue(isNoiseAnchor("一二三四五六七八九十一二三四五六七八九十多"))
    }

    // ---- ② 写法归一 ----

    @Test
    fun `括号写法能拆出候选键`() {
        val keys = entityLookupKeys("テスト（testconcept）")
        assertTrue(keys.contains("テスト（testconcept）"))
        assertTrue(keys.contains("テスト"))
        assertTrue(keys.contains("testconcept"))
    }

    @Test
    fun `括号写法归到同一个标准名`() {
        val groups = listOf(
            AliasGroup(standardName = "TestConcept", aliases = listOf("testconcept", "测试词")),
            AliasGroup(standardName = "情绪组", aliases = listOf("开心"), kind = "emotion")
        )
        val canon = canonicalNames(groups)
        assertEquals("TestConcept", canon["testconcept"])
        assertEquals("TestConcept", canon["测试词"])
        // 括号写法：外层是 テスト（不在组里），但里面是 testconcept → 命中
        val hit = entityLookupKeys("テスト（testconcept）").firstNotNullOfOrNull { canon[it] }
        assertEquals("TestConcept", hit)
    }

    @Test
    fun `情绪组不参与锚点归一`() {
        val groups = listOf(AliasGroup(standardName = "喜悦", aliases = listOf("开心"), kind = "emotion"))
        val canon = canonicalNames(groups)
        assertTrue(canon.isEmpty())
    }

    @Test
    fun `同一个写法出现在两个组时先出现的优先`() {
        val groups = listOf(
            AliasGroup(standardName = "TestConcept", aliases = listOf("测试词")),
            AliasGroup(standardName = "测试词", aliases = listOf("测试词子"))
        )
        val canon = canonicalNames(groups)
        assertEquals("TestConcept", canon["测试词"])
    }

    // ---- 同名合并（2026-11-21 改）：类型不再参与分组 ----

    @Test
    fun `同名字不同 kind 也合并`() {
        val canon = emptyMap<String, String>()
        assertEquals(anchorGroupKey("テスト", canon), anchorGroupKey("テスト", canon))
    }

    @Test
    fun `别名归一后同名合并`() {
        val canon = mapOf("testconcept" to "TestConcept")
        assertEquals(anchorGroupKey("testconcept", canon), anchorGroupKey("TestConcept", canon))
    }
}
