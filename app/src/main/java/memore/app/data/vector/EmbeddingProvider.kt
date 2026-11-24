package memore.app.data.vector

/**
 * 嵌入提供者：把文本变成向量。
 *
 * 约定：
 *  - 返回的向量**必须已 L2 归一化**（这样余弦相似度 = 点积）。
 *  - `isQuery = true` 时由实现决定是否加检索前缀（本地 bge 需要，API 一般不需要）。
 *  - 一个 provider 实例对应一个固定模型与维度；换模型必须重建索引（见设计文档 §5.4）。
 */
interface EmbeddingProvider {
    /** 稳定标识，例如 `local:bge-small-zh-v1.5-int8` / `api:text-embedding-3-small` */
    val id: String

    /** 模型名，落库到 record_vectors.model */
    val model: String

    /** 向量维度 */
    val dim: Int


    /**
     * 批量嵌入。返回顺序与入参一致，每个向量长度 = dim 且已归一化。
     * 任一文本失败应抛异常，由调用方标 FAILED 并重试。
     */
    suspend fun embed(texts: List<String>, isQuery: Boolean = false): List<FloatArray>

    /** 用一条固定短文本自检（UI 的「测试」按钮）。 */
    suspend fun test(): Result<Unit>
}
