package memore.app.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MergeSelectionStateTest {

    @Test
    fun browseIgnoresNodeTap() {
        val s = MergeSelectionState()
        assertEquals(s, s.onNodeTap(1, "A"))
    }

    @Test
    fun toggleEntersAndExitsMergeMode() {
        val s = MergeSelectionState().toggleMergeMode()
        assertEquals(MergeSelectionPhase.PICK_SOURCE, s.phase)
        assertEquals(MergeSelectionState(), s.toggleMergeMode())
    }

    @Test
    fun picksSourceThenTarget() {
        val s = MergeSelectionState()
            .toggleMergeMode()
            .onNodeTap(1, "A")
        assertEquals(MergeSelectionPhase.PICK_TARGET, s.phase)
        assertEquals(1L, s.sourceId)

        val ready = s.onNodeTap(2, "B")
        assertTrue(ready.ready)
        assertEquals(2L, ready.targetId)
    }

    @Test
    fun tappingSourceAgainDoesNotSelfMerge() {
        val s = MergeSelectionState()
            .toggleMergeMode()
            .onNodeTap(1, "A")
            .onNodeTap(1, "A")
        assertFalse(s.ready)
        assertEquals(MergeSelectionPhase.PICK_TARGET, s.phase)
    }

    @Test
    fun cancelTargetReturnsToPickSource() {
        val s = MergeSelectionState()
            .toggleMergeMode()
            .onNodeTap(1, "A")
            .onNodeTap(2, "B")
            .cancelTarget()
        assertEquals(MergeSelectionPhase.PICK_SOURCE, s.phase)
        assertEquals(1L, s.sourceId)
        assertEquals(null, s.targetId)
    }
}
