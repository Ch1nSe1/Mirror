package memore.app.data.vector

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 向量索引重建的进度（2026-11-08）。
 *
 * 为什么要有它：重建跑在页面之外的 scope 里，用户离开页面不会中断；
 * 页面重新进来时从这里读当前进度，而不是"卡着不动、退出后不知道建到哪"。
 */
object VectorRebuildState {
    /** 首次建立（静默，顶栏细条） / 手动重建（模态弹窗）。 */
    enum class Phase { INITIAL, REBUILD }

    data class State(
        val running: Boolean = false,
        val phase: Phase = Phase.REBUILD,
        val done: Int = 0,
        val total: Int = 0,
        val startedAt: Long = 0L,
        val finishedAt: Long = 0L,
        val lastError: String? = null
    ) {
        val percent: Int get() = if (total <= 0) 0 else (done * 100 / total).coerceIn(0, 100)
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    internal fun start(total: Int, phase: Phase = Phase.REBUILD) {
        _state.value = State(running = true, phase = phase, done = 0, total = total, startedAt = System.currentTimeMillis())
    }

    internal fun progress(done: Int, total: Int) {
        _state.value = _state.value.copy(done = done, total = total)
    }

    internal fun finish(error: String? = null) {
        _state.value = _state.value.copy(
            running = false,
            finishedAt = System.currentTimeMillis(),
            lastError = error
        )
    }
}
