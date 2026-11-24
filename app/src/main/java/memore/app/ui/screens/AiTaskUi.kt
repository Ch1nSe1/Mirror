package memore.app.ui.screens

import memore.app.data.model.AiTask

/**
 * AI 任务在**卡片上**的表现（与队列页无关）。
 *
 * ⚠️ 这里踩过一次坑（2026-10-10 用户报）：卡片转圈原先直接用了
 * `observeActiveTasks()` 的结果 —— 那个集合是 `PENDING / PROCESSING / WAITING_SERVER`，
 * 也就是**"排队中"的也算**，于是批量入队十条 → 十张卡片一起转圈，
 * 看着像"全都同时在跑"，而不是"总结到哪条哪条转"。
 *
 * worker 有 Mutex 串行化（`MemoreApp.aiWorkerLock`），任何时刻**至多一条 PROCESSING**，
 * 所以转圈只该给正在处理的那条。
 */
fun summarySpinnerIds(tasks: List<AiTask>): Set<String> =
    tasks.filter { it.status == "PROCESSING" }.map { it.recordId }.toSet()
