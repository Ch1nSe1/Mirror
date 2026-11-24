package memore.app.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * 映射表读取的容错（2026-11-21）。
 *
 * 事故：老 mappings.json 里有 3 组缺 `kind`、`aliases` 为 null；Gson 会绕开构造函数塞 null，
 * 合并时 NPE 被 catch 吞掉 → 映射表整页空白。这里锁住「坏字段也不丢整页」。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class AliasMappingLoadTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun resetFile() {
        // Robolectric 的 filesDir 在同一个测试进程里是共享的，上一条用例写的文件会留下来。
        File(ctx.filesDir, "mappings.json").delete()
    }

    @Test
    fun `缺 kind 和 aliases 的旧数据也能读出来`() = runBlocking {
        val f = File(ctx.filesDir, "mappings.json")
        f.writeText(
            """
            [
              {"standardName":"期待","aliases":["期望","盼望"],"kind":"emotion"},
              {"standardName":"期待","aliases":["期望","渴望"]},
              {"standardName":"满足","aliases":null},
              {"standardName":"焦虑"}
            ]
            """.trimIndent()
        )
        val groups = AliasMappingRepository(ctx).load()
        assertTrue("整页不该被坏字段弄空", groups.isNotEmpty())
        val qidai = groups.first { it.standardName == "期待" }
        assertEquals(listOf("期望", "盼望", "渴望"), qidai.aliases)
        assertTrue(groups.any { it.standardName == "满足" })
        assertTrue(groups.any { it.standardName == "焦虑" })
    }

    @Test
    fun `合并后同名只留一组`() = runBlocking {
        val f = File(ctx.filesDir, "mappings.json")
        f.writeText(
            """
            [
              {"standardName":"期待","aliases":["期望"],"kind":"emotion"},
              {"standardName":"期待","aliases":["渴望"],"kind":"emotion"}
            ]
            """.trimIndent()
        )
        val groups = AliasMappingRepository(ctx).load()
        assertEquals(1, groups.size)
        assertEquals(listOf("期望", "渴望"), groups[0].aliases)
    }

    @Test
    fun `mergeGroups 把 from 的写法并进 to 并删掉 from`() = runBlocking {
        val f = File(ctx.filesDir, "mappings.json")
        f.writeText(
            """
            [
              {"standardName":"TestConceptA","aliases":["testconcepta","TestArtistC"],"kind":"concept"},
              {"standardName":"TestConceptB","aliases":["testconceptb"],"kind":"concept"}
            ]
            """.trimIndent()
        )
        val repo = AliasMappingRepository(ctx)
        val merged = repo.mergeGroups("TestConceptA", "TestConceptB")
        assertEquals("TestConceptB", merged?.standardName)
        val groups = repo.load()
        assertEquals(1, groups.size)
        assertEquals(listOf("TestConceptA", "testconcepta", "TestArtistC", "testconceptb"), groups[0].aliases)
    }

    @Test
    fun `mergeGroups 自己并自己或来源不存在时拒绝`() = runBlocking {
        val f = File(ctx.filesDir, "mappings.json")
        f.writeText("""[{"standardName":"A","aliases":["a"],"kind":"concept"}]""")
        val repo = AliasMappingRepository(ctx)
        assertEquals(null, repo.mergeGroups("A", "A"))
        assertEquals(null, repo.mergeGroups("不存在", "A"))
    }
}
