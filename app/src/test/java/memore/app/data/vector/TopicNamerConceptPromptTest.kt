package memore.app.data.vector

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 2026-11-22：命名提示词带概念 + 具体对象规则。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class TopicNamerConceptPromptTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `有概念时追加概念行和具体对象规则`() {
        val namer = TopicNamer(ctx)
        val seeds = listOf(
            TopicNamer.Seed(0, listOf("加班"), listOf("今天又在加班"), listOf("工作", "公司")),
            TopicNamer.Seed(1, listOf("乐队"), listOf("去看了演出"), emptyList())
        )
        val prompt = namer.buildPrompt(seeds)
        assertTrue(prompt.contains("概念 工作、公司"))
        assertTrue(prompt.contains("明确的共同对象"))
    }

    @Test
    fun `没有概念时不加对象规则`() {
        val namer = TopicNamer(ctx)
        val seeds = listOf(TopicNamer.Seed(0, listOf("加班"), listOf("今天又在加班")))
        val prompt = namer.buildPrompt(seeds)
        assertFalse(prompt.contains("明确的共同对象"))
    }
}
