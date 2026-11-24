package memore.app.data.vector

/**
 * 记录写入的轻量信号（设计文档 §6）：仓库层写库后 [mark]，由 [VectorIndexService] 去抖消费。
 *
 * 用信号而不是直接依赖 service，是为了让 repository 不反向依赖向量层（避免循环依赖）。
 */
object VectorIndexBus {
    @Volatile
    private var sink: ((String) -> Unit)? = null

    fun attach(listener: (String) -> Unit) { sink = listener }

    fun mark(recordId: String) { sink?.invoke(recordId) }
}