package memore.app.data.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 撤回的集合差与冲突判据（2026-11-15）。
 *
 * 覆盖的正是 2026-11-15 真机踩到的那个 bug：**动作新加的标签没被摘掉**。
 * 当时的错因是"只遍历快照里有标签的记录"，空集记录压根不进循环。
 * 这里的用例把"空集也要处理"钉死。
 */
class ActionDiffsTest {

    @Test
    fun newlyAddedTagIsRemoved() {
        // 动作前没有标签，动作后加了一个
        val diff = ActionDiffs.setDiff(wanted = emptySet(), current = setOf("t1"))
        assertTrue("新加的标签必须被去掉", diff.toRemove.contains("t1"))
        assertTrue("没有任何要补的", diff.toAdd.isEmpty())
    }

    @Test
    fun removedTagIsRestored() {
        val diff = ActionDiffs.setDiff(wanted = setOf("t1", "t2"), current = setOf("t1"))
        assertTrue("动作去掉的标签要补回来", diff.toAdd.contains("t2"))
        assertTrue("已有的不动", diff.toRemove.isEmpty())
    }

    @Test
    fun identicalSetsAreNoOp() {
        val diff = ActionDiffs.setDiff(wanted = setOf("t1", "t2"), current = setOf("t2", "t1"))
        assertTrue("集合相同 → 什么都不做", diff.isEmpty)
    }

    @Test
    fun bothDirectionsInOnePass() {
        val diff = ActionDiffs.setDiff(wanted = setOf("t1", "t3"), current = setOf("t2", "t3"))
        assertEquals(setOf("t1"), diff.toAdd)
        assertEquals(setOf("t2"), diff.toRemove)
    }

    @Test
    fun relationPairDiff() {
        val wanted = setOf("a" to "b")
        val current = setOf("a" to "b", "c" to "d")
        val diff = ActionDiffs.pairDiff(wanted, current)
        assertTrue("动作新建的关联要删掉", diff.toRemove.contains("c\u0000d"))
        assertTrue("没缺的不要补", diff.toAdd.isEmpty())
    }

    @Test
    fun contentChangeAfterActionIsConflict() {
        assertTrue(
            ActionDiffs.contentConflict(
                snapshotUpdatedAt = 100L, snapshotContent = "原文",
                currentUpdatedAt = 200L, currentContent = "改过的"
            )
        )
    }

    @Test
    fun timestampOnlyChangeIsNotConflict() {
        // 打标签会刷新 updated_at，但正文没变 —— 这不是冲突
        assertFalse(
            ActionDiffs.contentConflict(
                snapshotUpdatedAt = 100L, snapshotContent = "原文",
                currentUpdatedAt = 200L, currentContent = "原文"
            )
        )
    }

    @Test
    fun untouchedRecordIsNotConflict() {
        assertFalse(
            ActionDiffs.contentConflict(
                snapshotUpdatedAt = 100L, snapshotContent = "原文",
                currentUpdatedAt = 100L, currentContent = "原文"
            )
        )
    }
}
