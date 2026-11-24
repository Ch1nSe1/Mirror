package memore.app.utils

/**
 * 长任务（导出 / 导入）的进度快照。
 *
 * **纯观察用**：它不参与任何业务判断，不改变执行顺序、不改变输出字节、不改变失败语义。
 * 引擎只负责往里写，UI 只负责读；写坏了顶多是进度条不准，不会伤数据。
 *
 * @param phase 阶段名，直接给用户看（如「正在写入附件…」）
 * @param done  已完成的工作量
 * @param total 总工作量；**<= 0 表示没法定量** → UI 显示不定量（转圈式）进度条
 * @param detail 附加说明（如当前文件名），可为空
 */
data class OpProgress(
    val phase: String,
    val done: Int = 0,
    val total: Int = 0,
    val detail: String? = null
) {
    /** 没法定量时 UI 不该显示百分比，否则会出现"卡在某个数不动"的假象。 */
    val indeterminate: Boolean get() = total <= 0

    /** 0f..1f；定量时刻意**夹住上界** —— 预统计是估算，宁可提前满格也不要超过 100%。 */
    val fraction: Float
        get() = if (total <= 0) 0f else (done.toFloat() / total.toFloat()).coerceIn(0f, 1f)

    /**
     * 副标题：优先用 [detail]（如「已还原 183 MB / 267 MB」），否则退化成计数（如「120/240」）。
     * 不定量且没有 detail 时返回 null —— UI 就不显示副标题。
     */
    fun subtitle(): String? = when {
        !detail.isNullOrBlank() -> detail
        !indeterminate -> "$done/$total"
        else -> null
    }
}
