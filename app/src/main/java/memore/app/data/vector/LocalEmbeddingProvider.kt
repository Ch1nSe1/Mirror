package memore.app.data.vector

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.LongBuffer

/**
 * 本地嵌入（ONNX Runtime）：bge-small-zh-v1.5 int8，512 维，CLS 池化。
 *
 * 资源：`assets/models/bge-small-zh-v1.5-int8.onnx` + `assets/models/vocab.txt`。
 *
 * 检索前缀：bge 官方要求 **query 加指令前缀**、文档不加，否则相似度整体偏移
 * （见 BAAI/bge 的 README；这里固化成常量）。
 *
 * 线程：模型很小（24M），intra-op 1 线程足够且省电；批量回填时由调用方串行调度。
 */
class LocalEmbeddingProvider private constructor(
    private val env: OrtEnvironment,
    private val session: OrtSession,
    private val tokenizer: BertWordPieceTokenizer,
    private val hasTokenTypeIds: Boolean,
    override val dim: Int
) : EmbeddingProvider {

    override val id: String = "local:$MODEL_NAME"
    override val model: String = MODEL_NAME

    override suspend fun embed(texts: List<String>, isQuery: Boolean): List<FloatArray> =
        withContext(Dispatchers.Default) {
            require(texts.isNotEmpty()) { "texts 为空" }
            val prepared = texts.map { if (isQuery) QUERY_PREFIX + it else it }
            val (ids, mask) = tokenizer.encodePair(prepared)
            val batch = ids.size.toLong()
            val seq = ids[0].size.toLong()

            val flatIds = LongArray(ids.size * ids[0].size)
            val flatMask = LongArray(mask.size * mask[0].size)
            for (i in ids.indices) {
                ids[i].copyInto(flatIds, i * ids[i].size)
                mask[i].copyInto(flatMask, i * mask[i].size)
            }

            val inputs = HashMap<String, OnnxTensor>()
            try {
                inputs["input_ids"] = OnnxTensor.createTensor(env, LongBuffer.wrap(flatIds), longArrayOf(batch, seq))
                inputs["attention_mask"] = OnnxTensor.createTensor(env, LongBuffer.wrap(flatMask), longArrayOf(batch, seq))
                if (hasTokenTypeIds) {
                    val types = LongArray(flatIds.size) // 全 0：单句对，BERT 约定
                    inputs["token_type_ids"] = OnnxTensor.createTensor(env, LongBuffer.wrap(types), longArrayOf(batch, seq))
                }

                session.run(inputs).use { result ->
                    val out = result.get(0) as OnnxTensor
                    val shape = out.info.shape // [batch, seq, dim]
                    require(shape.size == 3) { "模型输出应为 3 维，实际 ${shape.toList()}" }
                    val seqLen = shape[1].toInt()
                    val hidden = shape[2].toInt()
                    require(hidden == dim) { "模型维度 ${hidden} 与声明 $dim 不一致" }

                    val flat = FloatArray((shape[0] * shape[1] * shape[2]).toInt())
                    val fb = out.floatBuffer
                    fb.get(flat)

                    // 每条的 CLS 向量（seq=0 那一段）→ 归一化
                    return@withContext (0 until batch.toInt()).map { b ->
                        val start = b * seqLen * hidden
                        val cls = FloatArray(hidden) { d -> flat[start + d] }
                        VectorMath.l2Normalize(cls)
                    }
                }
            } finally {
                inputs.values.forEach { runCatching { it.close() } }
            }
        }

    override suspend fun test(): Result<Unit> = runCatching {
        val v = embed(listOf("测试"), isQuery = false).first()
        require(v.size == dim) { "维度异常" }
    }

    /** 释放 native 资源（换模型或退出时调用）。 */
    fun close() {
        runCatching { session.close() }
        runCatching { env.close() }
    }

    companion object {
        const val MODEL_NAME = "bge-small-zh-v1.5-int8"
        private const val MODEL_FILE = "bge-small-zh-v1.5-int8.onnx"
        const val DIM = 512

        /** bge 中文检索的官方 query 指令前缀。文档侧**不要**加。 */
        const val QUERY_PREFIX = "为这个句子生成表示以用于检索相关文章："

        /**
         * @param modelBytes ONNX 模型字节
         * @param vocabLines vocab.txt 的每一行
         */
        /** Android 侧入口：从 assets/models/ 读模型与词表。 */
        fun fromAssets(assets: android.content.res.AssetManager): LocalEmbeddingProvider {
            val modelBytes = assets.open("models/$MODEL_FILE").use { it.readBytes() }
            val vocabLines = assets.open("models/vocab.txt").use { it.bufferedReader().readLines() }
            return create(modelBytes, vocabLines)
        }

        fun create(modelBytes: ByteArray, vocabLines: List<String>): LocalEmbeddingProvider {
            val env = OrtEnvironment.getEnvironment()
            val opts = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(1)
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            }
            val session = env.createSession(modelBytes, opts)
            val hasTokenType = session.inputNames.contains("token_type_ids")
            val tokenizer = BertWordPieceTokenizer.fromLines(vocabLines, maxLength = 512)
            val dim = DIM
            return LocalEmbeddingProvider(env, session, tokenizer, hasTokenType, dim)
        }
    }
}
