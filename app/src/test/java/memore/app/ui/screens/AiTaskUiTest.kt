package memore.app.ui.screens

import memore.app.data.model.AiTask
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 卡片转圈的取值规则。
 *
 * 回归点（2026-10-10 用户报"批量总结时所有卡片一起转圈"）：`observeActiveTasks()` 里
 * 含 `PENDING / WAITING_SERVER`（排队中、等服务器），**不能**直接当转圈集合；
 * worker 有 Mutex 串行化，至多一条 PROCESSING。
 */
class AiTaskUiTest {

    private fun task(recordId: String, status: String) = AiTask(recordId = recordId, status = status)

    @Test
    fun onlyProcessingSpins() {
        val ids = summarySpinnerIds(
            listOf(
                task("a", "PROCESSING"),
                task("b", "PENDING"),
                task("c", "WAITING_SERVER")
            )
        )
        assertEquals(setOf("a"), ids)
    }

    @Test
    fun queuedBatchDoesNotSpinEverything() {
        // 批量入队十条：一条在处理、九条排队 → 只能有一个转圈
        val tasks = listOf(task("running", "PROCESSING")) + (1..9).map { task("q$it", "PENDING") }
        assertEquals(1, summarySpinnerIds(tasks).size)
        assertEquals(setOf("running"), summarySpinnerIds(tasks))
    }

    @Test
    fun emptyOrFinishedTasksProduceNoSpinner() {
        assertTrue(summarySpinnerIds(emptyList()).isEmpty())
        assertTrue(summarySpinnerIds(listOf(task("a", "SUCCESS"), task("b", "FAILED"))).isEmpty())
    }

    /** 同一记录重复入队时去重（集合语义）。 */
    @Test
    fun duplicateIdsAreDeduped() {
        val ids = summarySpinnerIds(listOf(task("a", "PROCESSING"), task("a", "PROCESSING")))
        assertEquals(setOf("a"), ids)
    }
}
