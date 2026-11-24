package memore.app.ui.screens

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「加载更多」的可见性（2026-11-19 修的 bug）。
 *
 * 现象 A：筛选/搜索时记录早就全加载完了，底部却还有「加载更多」；
 *         点它不加载新记录，只是把时间线分页补上，靠下的卡片才"长出"标签和协议。
 * 现象 B：滚到底时按钮又不见了——旧判断用「已加载行数 ≥ displayLimit」，
 *         分页里混着增修，父记录数常常小于 displayLimit，判断不出来。
 *
 * 现在的规则：只在**时间线、非筛选、非搜索、且已加载父记录数 < 全库可分析总数**时出现。
 * 纯 JVM 测试。
 */
class LoadMoreVisibilityTest {

    private fun show(
        sortAsc: Boolean = false,
        searchActive: Boolean = false,
        filtersOn: Boolean = false,
        loaded: Int = 60,
        total: Int = 321
    ) = shouldShowLoadMore(sortAsc, searchActive, filtersOn, loaded, total)

    @Test
    fun `筛选时不显示加载更多`() {
        assertFalse(show(filtersOn = true))
    }

    @Test
    fun `搜索时不显示加载更多`() {
        assertFalse(show(searchActive = true))
    }

    @Test
    fun `筛选加搜索同时生效时也不显示`() {
        assertFalse(show(searchActive = true, filtersOn = true))
    }

    @Test
    fun `还有没加载的记录时显示`() {
        assertTrue(show(loaded = 60, total = 321))
    }

    @Test
    fun `全部加载完就不显示`() {
        assertFalse(show(loaded = 321, total = 321))
    }

    @Test
    fun `分页里混了增修导致父记录数偏小时仍然显示`() {
        // 旧判断的坑：loaded(55) < displayLimit(60) 会被判成"没有更多"，滚到底按钮消失。
        // 现在看总数：55 < 321 → 还有没加载的，按钮该在。
        assertTrue(show(loaded = 55, total = 321))
    }

    @Test
    fun `正序时不显示`() {
        assertFalse(show(sortAsc = true))
    }

    @Test
    fun `总数为零时不显示`() {
        assertFalse(show(loaded = 0, total = 0))
    }
}
