package memore.app.data.repository

import memore.app.data.model.AliasGroup
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 同名映射组合并（2026-11-21）。
 *
 * 事故：内置默认情绪组（期待/满足/焦虑…）和导入的映射各留一份，
 * 列表里同一个情绪出现两行，别名列表还不一样。
 */
class AliasGroupMergeTest {

    @Test
    fun `同名同类型合并、别名取并集`() {
        val merged = mergeAliasGroups(
            listOf(
                AliasGroup(standardName = "期待", aliases = listOf("期望", "盼望", "向往", "憧憬"), kind = "emotion"),
                AliasGroup(standardName = "期待", aliases = listOf("期望", "渴望"), kind = "emotion")
            )
        )
        assertEquals(1, merged.size)
        assertEquals(listOf("期望", "盼望", "向往", "憧憬", "渴望"), merged[0].aliases)
    }

    @Test
    fun `同名但类型不同不合并`() {
        val merged = mergeAliasGroups(
            listOf(
                AliasGroup(standardName = "TestConcept", aliases = listOf("testconcept"), kind = "concept"),
                AliasGroup(standardName = "TestConcept", aliases = listOf("てすと"), kind = "emotion")
            )
        )
        assertEquals(2, merged.size)
    }

    @Test
    fun `前后空格与大小写视为同名`() {
        val merged = mergeAliasGroups(
            listOf(
                AliasGroup(standardName = " TestConcept ", aliases = listOf("testconcept"), kind = "concept"),
                AliasGroup(standardName = "testconcept", aliases = listOf("テスト"), kind = "concept")
            )
        )
        assertEquals(1, merged.size)
        assertEquals("TestConcept", merged[0].standardName)
    }
}
