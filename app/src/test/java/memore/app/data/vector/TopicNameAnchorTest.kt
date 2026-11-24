package memore.app.data.vector

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ③ 命名加锚（2026-11-19）。
 *
 * 钉住：命名提示词里**必须出现已有线索名**，并明确要求"是这条就沿用、不是才起新名"；
 * 没有锚时不得出现这段指令（否则等于给模型空参考）。
 *
 * Robolectric：只是为了拿到 Context 构造 TopicNamer，提示词本身是纯字符串。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TopicNameAnchorTest {

    private fun namer(): TopicNamer = TopicNamer(ApplicationProvider.getApplicationContext<Context>())

    private fun seeds() = listOf(
        TopicNamer.Seed(index = 0, keywords = listOf("关系", "依附"), samples = listOf("我在关系里反复试探边界。")),
        TopicNamer.Seed(index = 1, keywords = listOf("追星", "认同"), samples = listOf("舞台上的她让我想起自己想要什么。"))
    )

    @Test
    fun `有锚时提示词带上已有线索名`() {
        val prompt = namer().buildPrompt(seeds(), listOf("关系演变", "追星与梦"))
        assertTrue(prompt.contains("关系演变"))
        assertTrue(prompt.contains("追星与梦"))
    }

    @Test
    fun `有锚时要求沿用而不是换新词`() {
        val prompt = namer().buildPrompt(seeds(), listOf("关系演变"))
        assertTrue(prompt.contains("直接沿用那个名字"))
        assertTrue(prompt.contains("只有确实不属于任何一条时才起新名"))
    }

    @Test
    fun `没有锚时不出现锚指令`() {
        val prompt = namer().buildPrompt(seeds(), emptyList())
        assertFalse("没锚时不该出现锚名单", prompt.contains("已有的跨月线索名："))
        assertFalse("没锚时不该出现锚块", prompt.contains("如果这一簇讲的就是其中某条线索"))
    }

    @Test
    fun `提示词本身仍然保留原有的命名约束`() {
        val prompt = namer().buildPrompt(seeds(), listOf("关系演变"))
        assertTrue("仍要 2 到 8 个字", prompt.contains("2 到 8 个字"))
        assertTrue("仍要求只输出 JSON", prompt.contains("只输出 JSON"))
        assertTrue("仍要求顺序一致", prompt.contains("顺序与编号一致"))
    }

    @Test
    fun `要求同一次输出里名字不重复`() {
        val prompt = namer().buildPrompt(seeds(), listOf("关系演变"))
        assertTrue(prompt.contains("不能重复"))
    }
}
