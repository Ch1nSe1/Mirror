package memore.app.core.ai

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 提示词**预设**必须能随备份往返。
 *
 * 背景（真机上报的 bug）：导出侧把整个 `prompt_prefs` 排除在设置块之外
 * （"提示词走独立的 prompts 块"），而 `prompt_prefs` 里其实住着**两类**东西：
 * 正文（`prompt_*`）和预设（`preset_names_*` + `preset_content_*`）。
 * `exportAll()` 原来只导正文 → 预设两边都不在 → **换设备后预设全丢**。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PromptsPresetBackupTest {

    private val key = Prompts.ALL_PROMPTS.first().key

    @Before
    fun setUp() {
        Prompts.init(ApplicationProvider.getApplicationContext())
        wipeLocalPrompts()
    }

    @Test
    fun presetSurvivesExportImportRoundTrip() {
        Prompts.savePreset(key, "简洁版", "内容-简洁")
        val exported = Prompts.exportAll()

        // 模拟"新设备"：本机什么都没有了
        wipeLocalPrompts()
        assertTrue("前置：本机预设应已清空", Prompts.getPresets(key).isEmpty())
        assertNull(Prompts.getPresetContent(key, "简洁版"))

        Prompts.importAll(exported)

        assertEquals(listOf("简洁版"), Prompts.getPresets(key))
        assertEquals("内容-简洁", Prompts.getPresetContent(key, "简洁版"))

    }

    /**
     * 没有任何预设时，导出 map 里**不得出现 `preset_*` 键** ——
     * 否则所有用户的导出字节都会变，破坏既有的字节级契约测试。
     */
    @Test
    fun exportAll_omitsPresetKeysWhenNoPresets() {
        val keys = Prompts.exportAll().keys
        assertTrue("无预设时不该多出 preset_* 键：${keys.filter { it.startsWith("preset_") }}",
            keys.none { it.startsWith("preset_") })
        assertTrue("提示词正文仍应照旧导出", keys.any { it.startsWith("prompt_") })
    }

    /** 用公开 API 清空本机提示词与预设（不直接碰 SharedPreferences，避免踩静态单例的坑）。 */
    private fun wipeLocalPrompts() {
        Prompts.ALL_PROMPTS.forEach { def ->
            val (id, _) = def
            Prompts.resetPrompt(id, false)
            Prompts.resetPrompt(id, true)
            Prompts.getPresets(id).forEach { name -> Prompts.deletePreset(id, name) }
        }
    }
}
