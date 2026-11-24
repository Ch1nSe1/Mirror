package memore.app.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 「转录来源」回到草稿时要精确落到那一块：块 id → 列表行号的换算。
 *
 * 列表形状：正文的块 → （质疑 / 讨论 / AI，各自：**一条分隔条** + 该区的块）。
 * 换算错一位就会落在别人身上，所以这里把三件事钉死：
 * ① 正文里的块 → 就是它的下标；
 * ② 分区里的块 → 要把各区**分隔条**算进去；
 * ③ 空的分区**不占行**；块不存在时返回 -1（只回本，不滚）。
 */
class DraftFocusTest {

    private val mine = listOf(1L, 2L, 3L)          // 行 0、1、2
    private val qa = listOf(10L, 11L)              // 分隔条行 3 → 块 4、5
    private val discuss = listOf(20L)              // 分隔条行 6 → 块 7
    private val ai = listOf(30L, 31L)              // 分隔条行 8 → 块 9、10

    @Test
    fun mineBlocksUseTheirOwnIndex() {
        assertEquals(0, draftBlockRowIndex(mine, qa, discuss, ai, 1L))
        assertEquals(2, draftBlockRowIndex(mine, qa, discuss, ai, 3L))
    }

    @Test
    fun sectionBlocksCountTheDividerRows() {
        assertEquals(4, draftBlockRowIndex(mine, qa, discuss, ai, 10L))
        assertEquals(5, draftBlockRowIndex(mine, qa, discuss, ai, 11L))
        assertEquals(7, draftBlockRowIndex(mine, qa, discuss, ai, 20L))
        assertEquals(9, draftBlockRowIndex(mine, qa, discuss, ai, 30L))
        assertEquals(10, draftBlockRowIndex(mine, qa, discuss, ai, 31L))
    }

    @Test
    fun emptySectionsTakeNoRow() {
        // 只有正文和 AI：正文 3 块占 0/1/2，AI 分隔条占 3 → AI 第一块落在 4
        assertEquals(4, draftBlockRowIndex(mine, emptyList(), emptyList(), ai, 30L))
        // 只有正文：就是它自己的下标
        assertEquals(1, draftBlockRowIndex(mine, emptyList(), emptyList(), emptyList(), 2L))
    }

    @Test
    fun missingBlockMeansJustOpenTheBook() {
        assertEquals(-1, draftBlockRowIndex(mine, qa, discuss, ai, 999L))
        assertEquals(-1, draftBlockRowIndex(emptyList(), emptyList(), emptyList(), emptyList(), 1L))
    }
}
