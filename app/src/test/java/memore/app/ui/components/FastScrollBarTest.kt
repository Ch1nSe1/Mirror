package memore.app.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 时间线右缘那条滚动条的换算规则（纯函数）。
 *
 * 用户 2026-10-11 的原话：「给时间线最右边加个时隐时现不太明显的进度条，
 * 这样**好下滑和回到顶上**」—— 这里把两条不变量钉死：
 * ① 拖到最上必定回到顶上（第 0 条）；
 * ② 拖到最下必定停在最后一屏，不会越过末尾；
 * ③ 中间位置按比例落点。
 */
class FastScrollBarTest {

    // ---- 拇指高度：可见比例 ----

    @Test
    fun thumbFractionIsVisibleOverTotal() {
        assertEquals(0.25f, scrollThumbFraction(total = 100, visibleCount = 25), 0.0001f)
        assertEquals(1f, scrollThumbFraction(total = 10, visibleCount = 10), 0.0001f)
    }

    @Test
    fun thumbFractionIsClamped() {
        assertEquals("可见比总数还多（还没量准）→ 夹到 1", 1f, scrollThumbFraction(10, 30), 0.0001f)
        assertEquals("一条也看不见 → 0（外面会给最小像素兜底）", 0f, scrollThumbFraction(100, 0), 0.0001f)
        assertEquals("空列表 → 占满", 1f, scrollThumbFraction(0, 0), 0.0001f)
    }

    @Test
    fun thumbGetsSmallerAsListGrows() {
        val short = scrollThumbFraction(total = 20, visibleCount = 4)
        val long = scrollThumbFraction(total = 286, visibleCount = 4)
        assertTrue("列表越长，条越短", long < short)
    }

    // ---- 拖动落点 ----

    @Test
    fun dragToTopGoesBackToTheTop() {
        assertEquals("拖到最上 = 回到顶上", 0, scrollTargetIndex(0f, total = 286, visibleCount = 4))
    }

    @Test
    fun dragToBottomStopsAtLastScreen() {
        assertEquals(
            "拖到最下 = 最后一屏的第一条，不越过末尾",
            282,
            scrollTargetIndex(1f, total = 286, visibleCount = 4)
        )
    }

    @Test
    fun dragToMiddleGoesProportionally() {
        assertEquals(45, scrollTargetIndex(0.5f, total = 100, visibleCount = 10))
        assertEquals(9, scrollTargetIndex(0.5f, total = 20, visibleCount = 2))
    }

    @Test
    fun listShorterThanScreenNeverScrollsAway() {
        assertEquals("一屏就装得下 → 永远回到顶上", 0, scrollTargetIndex(0f, 5, 10))
        assertEquals(0, scrollTargetIndex(1f, 5, 10))
        assertEquals(0, scrollTargetIndex(0.5f, 5, 10))
    }

    @Test
    fun outOfRangeFractionIsClamped() {
        assertEquals(0, scrollTargetIndex(-3f, total = 100, visibleCount = 10))
        assertEquals(90, scrollTargetIndex(9f, total = 100, visibleCount = 10))
        assertEquals("空列表不炸", 0, scrollTargetIndex(0.5f, total = 0, visibleCount = 0))
    }
}
