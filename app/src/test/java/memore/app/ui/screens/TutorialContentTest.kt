package memore.app.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 教程正文（`app/src/main/assets/tutorial.md`）的**结构契约**。
 *
 * 为什么需要：应用里 `##` 就是顶部那排目录 chip —— 章数一多就得横着滚半天，
 * 章名一长 chip 也挤。而 `###` 只是章内小标题（主色、不占 chip）。
 * 所以这里锁住：章数固定、章名够短、每章都有内容、且**工程细节不许回流**
 * （配置键名 / 服务路径这类东西属于 `docs/工程手册-配置维护技术债.md`）。
 */
class TutorialContentTest {

    private val md: String by lazy {
        val f = File("src/main/assets/tutorial.md")
        assertTrue("找不到教程正文：${f.absolutePath}", f.exists())
        f.readText()
    }

    /** 目录 = 所有 `## ` 行；`# ` 标题与 `### ` 小标题都不算。 */
    private fun chapters(): List<String> =
        md.lineSequence().filter { it.startsWith("## ") }.map { it.removePrefix("## ").trim() }.toList()

    @Test
    fun chapterCountAndNamesAreTight() {
        val c = chapters()
        // 2026-11-22：新增「情绪」「FIX 工具」两章 → 16 章。
        assertEquals("## 的数量就是目录长度，别随手加", 16, c.size)
        assertTrue("章名不能为空", c.all { it.isNotBlank() })
        assertTrue("章名要短（chip 上要挤得下）：$c", c.all { it.length <= 8 })
    }

    /** 每章至少两行有效内容 —— 防止留空章 / 只有小标题的壳。 */
    @Test
    fun everyChapterHasContent() {
        val lines = md.lines()
        chapters().forEach { name ->
            val start = lines.indexOfFirst { it.trim() == "## $name" }
            val end = (start + 1 until lines.size).firstOrNull { lines[it].startsWith("## ") } ?: lines.size
            val body = lines.subList(start + 1, end).count { it.isNotBlank() }
            assertTrue("《$name》这一章太薄（$body 行）", body >= 2)
        }
    }

    /** 互引用必须用书名号（"见《备份》"），不要写"见第 12 节"——重排就会断。 */
    @Test
    fun crossReferencesUseBookTitleNotNumbers() {
        assertFalse("别用章节号互相引用", Regex("见第\\s*\\d+\\s*节").containsMatchIn(md))
        assertTrue("章节互引用应当写成 见《…》", md.contains("见《"))
    }

    /** 工程细节（prefs 键名、服务端路径、内网地址）不该出现在用户手册里。 */
    @Test
    fun noEngineeringDetailsLeakIntoUserManual() {
        listOf(
            "SharedPreferences", "mirror_prefs", "voice_prefs", "prompt_prefs",
            "feature_console", "tools/", "192.168.", "APP_DB_VERSION", "MIGRATION_"
        ).forEach { assertFalse("教程里不该出现工程细节：$it", md.contains(it)) }
    }
}
