package memore.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 引导页"是否会再弹一次"的回归测试（2026-11-16）。
 *
 * 背景：`onSkip` 曾经只改内存里的 tutorialSeen、不写 prefs，导致
 * **点跳过之后冷启动又弹一遍**（真机踩到）。当时没有用例能拦住它。
 *
 * 现在把落盘抽成 [markTutorialDone]，这里直接断言三件事：
 *   1. 调用前是 false（会弹）
 *   2. 调用后是 true（不再弹）
 *   3. 写的是 MainActivity 读的那个键（KEY_TUTORIAL_DONE）——防"写错键"
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class TutorialOnboardingTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val prefs get() = context.getSharedPreferences("memore_prefs", Context.MODE_PRIVATE)

    @Test
    fun `markTutorialDone 写入后 prefs 立刻为 true`() {
        prefs.edit().clear().commit()
        assertFalse("初始状态应是未看过（否则引导不会出现）", prefs.getBoolean(KEY_TUTORIAL_DONE, false))

        markTutorialDone(prefs)

        assertTrue(
            "markTutorialDone 之后必须是 true，否则冷启动会再弹一次引导",
            prefs.getBoolean(KEY_TUTORIAL_DONE, false)
        )
    }

    @Test
    fun `写的是 MainActivity 读的那个键`() {
        prefs.edit().clear().commit()
        markTutorialDone(prefs)

        // 逐字比对：键名一旦改成 tutorial_done / tutorial_v2_done，
        // 读取端会永远拿到 false —— 这个用例就是拦这个
        assertEquals("tutorial_v1_done", KEY_TUTORIAL_DONE)
        assertTrue(prefs.all.containsKey("tutorial_v1_done"))
    }

    @Test
    fun `重复标记是幂等的（跳过后再点开始使用不会报错）`() {
        prefs.edit().clear().commit()
        markTutorialDone(prefs)
        markTutorialDone(prefs)
        assertTrue(prefs.getBoolean(KEY_TUTORIAL_DONE, false))
    }
}
