package memore.app.ui.screens

enum class MergeSelectionPhase { BROWSE, PICK_SOURCE, PICK_TARGET }

data class MergeSelectionState(
    val phase: MergeSelectionPhase = MergeSelectionPhase.BROWSE,
    val sourceId: Long? = null,
    val sourceName: String? = null,
    val targetId: Long? = null,
    val targetName: String? = null
) {

    fun toggleMergeMode(): MergeSelectionState =
        if (phase == MergeSelectionPhase.BROWSE) {
            copy(phase = MergeSelectionPhase.PICK_SOURCE)
        } else {
            MergeSelectionState()
        }

    fun onNodeTap(id: Long, name: String): MergeSelectionState = when (phase) {
        MergeSelectionPhase.BROWSE -> this
        MergeSelectionPhase.PICK_SOURCE ->
            copy(phase = MergeSelectionPhase.PICK_TARGET, sourceId = id, sourceName = name)
        MergeSelectionPhase.PICK_TARGET ->
            if (id == sourceId) this else copy(targetId = id, targetName = name)
    }

    fun cancelTarget(): MergeSelectionState =
        if (phase == MergeSelectionPhase.PICK_TARGET) {
            copy(phase = MergeSelectionPhase.PICK_SOURCE, targetId = null, targetName = null)
        } else {
            this
        }

    val ready: Boolean
        get() = sourceId != null && targetId != null
}
