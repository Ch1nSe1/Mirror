package memore.app

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 屏 / Tab id **唯一性**契约。
 *
 * 为什么需要：这些 id 只是普通 `Int` 常量，**撞值编译器不报错**；
 * 而 `when (currentScreenId)` 只会匹配到**第一个**分支 —— 于是
 * "点 A 打开 B"这种 bug 能一路装上真机。
 *
 * 2026-09-30 真事：`SCREEN_EMOTION_TREND` 与 `SCREEN_TUTORIAL` 都是 19，
 * 点"教程"打开的是情绪走势（走势那支在 when 里靠前）。
 *
 * 注意 SCREEN_ 与 TAB_ 是**两个命名空间**（各自从 0 开始），所以分开校验。
 */
class ScreenIdUniquenessTest {

    private val source: String by lazy {
        // 2026-11-17：包名改过一次，路径会变；按文件名找，别写死包路径。
        val root = File("src/main/java")
        val f = root.walkTopDown().firstOrNull { it.name == "MainActivity.kt" }
            ?: File("src/main/java/memore/app/MainActivity.kt")
        assertTrue("找不到 MainActivity：${f.absolutePath}", f.exists())
        f.readText()
    }

    private fun idsWithPrefix(prefix: String): List<Pair<String, Int>> =
        Regex("""const val (${prefix}_[A-Z_]+)\s*=\s*(\d+)""")
            .findAll(source)
            .map { it.groupValues[1] to it.groupValues[2].toInt() }
            .toList()

    private fun assertUnique(prefix: String, atLeast: Int) {
        val found = idsWithPrefix(prefix)
        assertTrue("应当能扫到 $prefix 常量（扫到 ${found.size} 个）", found.size >= atLeast)
        val dup = found.groupBy { it.second }.filterValues { it.size > 1 }
        assertTrue(
            "$prefix id 撞车 —— when 只会匹配第一个分支，会打开错的页面：$dup",
            dup.isEmpty()
        )
    }

    @Test
    fun screenIdsAreUnique() = assertUnique("SCREEN", 20)

    @Test
    fun tabIdsAreUnique() = assertUnique("TAB", 4)
}
