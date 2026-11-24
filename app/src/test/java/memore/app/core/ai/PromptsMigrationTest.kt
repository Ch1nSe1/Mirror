package memore.app.core.ai

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 双槽 → 单槽迁移（2026-11-21）。
 *
 * 旧版每个功能存 thinking / non_thinking 两份；新版只存一份。
 * 迁移规则：旧「思考」那份优先；等于旧默认标记的不搬；两份都清掉；预设名单合并。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PromptsMigrationTest {

    private lateinit var prefs: android.content.SharedPreferences
    private val id = Prompts.KEY_ANALYZE

    @Before
    fun setUp() {
        val ctx: Context = ApplicationProvider.getApplicationContext()
        prefs = ctx.getSharedPreferences("prompt_prefs_mig", Context.MODE_PRIVATE)
        prefs.edit().clear().apply()
    }

    @Test
    fun `双槽功能：两份分别搬到 std 与 think`() {
        prefs.edit().putString("prompt_${id}_thinking", "旧思考内容").apply()
        prefs.edit().putString("prompt_${id}_non_thinking", "旧非思考内容").apply()
        Prompts.migrateToSingleSlot(prefs)
        assertEquals("旧非思考内容", prefs.getString("prompt_${id}_std", null))
        assertEquals("旧思考内容", prefs.getString("prompt_${id}_think", null))
        assertNull(prefs.getString("prompt_${id}_thinking", null))
    }

    @Test
    fun `单槽功能：取思考份（修正历史 bug）`() {
        val single = Prompts.KEY_REVIEW
        prefs.edit().putString("prompt_${single}_thinking", "思考份内容").apply()
        prefs.edit().putString("prompt_${single}_non_thinking", "非思考份内容").apply()
        Prompts.migrateToSingleSlot(prefs)
        assertEquals("思考份内容", prefs.getString("prompt_$single", null))
        assertNull(prefs.getString("prompt_${single}_non_thinking", null))
    }

    @Test
    fun `等于旧默认标记的不迁，回到默认`() {
        prefs.edit().putString("prompt_${id}_thinking", "3.5. **anchors**：0~5 个").apply()
        Prompts.migrateToSingleSlot(prefs)
        assertNull("旧默认副本不该被搬过来", prefs.getString("prompt_$id", null))
    }

    @Test
    fun `没有旧数据时不写入任何东西`() {
        Prompts.migrateToSingleSlot(prefs)
        assertNull(prefs.getString("prompt_$id", null))
        assertFalse(prefs.contains("prompt_${id}_thinking"))
        assertTrue(prefs.contains("prompt_slot_version"))
    }

    @Test
    fun `语音翻译的旧日语默认会被清掉、回到英语默认`() {
        prefs.edit().putString("prompt_${Prompts.KEY_VOICE_TRANSLATE}", Prompts.LEGACY_VOICE_TRANSLATE).apply()
        Prompts.migrateToSingleSlot(prefs)
        assertNull(prefs.getString("prompt_${Prompts.KEY_VOICE_TRANSLATE}", null))
    }

    @Test
    fun `语音翻译的自定义内容不会被迁移误删`() {
        prefs.edit().putString("prompt_${Prompts.KEY_VOICE_TRANSLATE}", "我自己改过的翻译规则").apply()
        Prompts.migrateToSingleSlot(prefs)
        assertEquals("我自己改过的翻译规则", prefs.getString("prompt_${Prompts.KEY_VOICE_TRANSLATE}", null))
    }
}
