package memore.app.data.vector

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * 端到端嵌入自检（JVM 单测用桌面版 ORT；设备上同一份代码走 Android 包）。
 *
 * 依赖两件事：
 *  - `app/src/main/assets/models/bge-small-zh-v1.5-int8.onnx` + `vocab.txt` 存在
 *  - `app/src/test/resources/golden_embedding_case0.json`（由 Python onnxruntime 生成的期望向量）
 *
 * 这条测试的作用：**把 ONNX 前向、CLS 池化、归一化、分词四段串起来钉死**，
 * 只要其中一段被改坏（比如误用 mean pooling），这里会立刻红。
 */
class LocalEmbeddingProviderTest {

    private fun assets(): Triple<File, File, File> {
        val model = File("src/main/assets/models/bge-small-zh-v1.5-int8.onnx")
        val vocab = File("src/main/assets/models/vocab.txt")
        val goldenEmb = File("src/test/resources/golden_embedding_case0.json")
        assumeTrue("缺少模型文件，跳过", model.exists() && vocab.exists())
        assumeTrue("缺少 golden 向量，跳过", goldenEmb.exists())
        return Triple(model, vocab, goldenEmb)
    }

    @Test
    fun `输出与 Python onnxruntime 的 golden 一致`() = runBlocking {
        val (model, vocab, goldenFile) = assets()
        val goldenTokens = JSONArray(File("src/test/resources/golden_tokens.json").readText())
        val text = goldenTokens.getJSONObject(0).getString("text")

        val provider = LocalEmbeddingProvider.create(
            modelBytes = model.readBytes(),
            vocabLines = vocab.readLines()
        )
        try {
            val vec = provider.embed(listOf(text)).first()
            val expected = JSONArray(goldenFile.readText()).let { arr ->
                FloatArray(arr.length()) { arr.getDouble(it).toFloat() }
            }

            assertEquals("维度", 512, vec.size)
            val norm = kotlin.math.sqrt(vec.sumOf { (it * it).toDouble() })
            assertEquals("L2 范数", 1.0, norm, 1e-4)

            var maxDiff = 0f
            for (i in vec.indices) {
                val d = kotlin.math.abs(vec[i] - expected[i])
                if (d > maxDiff) maxDiff = d
            }
            assertTrue("最大逐元素误差 $maxDiff 过大（可能池化方式或分词不一致）", maxDiff < 2e-3f)
        } finally {
            provider.close()
        }
    }

    @Test
    fun `语义相近的文本相似度高于无关文本`() = runBlocking {
        val (model, vocab, _) = assets()
        val provider = LocalEmbeddingProvider.create(model.readBytes(), vocab.readLines())
        try {
            val a = provider.embed(listOf("今天很累")).first()
            val b = provider.embed(listOf("今天特别疲倦")).first()
            val c = provider.embed(listOf("无人机蜂群的饱和攻击")).first()
            val related = VectorMath.cosine(a, b)
            val unrelated = VectorMath.cosine(a, c)
            assertTrue("相近=$related 无关=$unrelated", related > unrelated + 0.05f)
        } finally {
            provider.close()
        }
    }

    @Test
    fun `查询前缀会改变向量但维度不变`() = runBlocking {
        val (model, vocab, _) = assets()
        val provider = LocalEmbeddingProvider.create(model.readBytes(), vocab.readLines())
        try {
            val doc = provider.embed(listOf("关于夏天的记忆"), isQuery = false).first()
            val query = provider.embed(listOf("关于夏天的记忆"), isQuery = true).first()
            assertEquals(512, query.size)
            assertTrue("query 与 doc 不应完全相同", VectorMath.cosine(doc, query) < 0.9999f)
        } finally {
            provider.close()
        }
    }
}
