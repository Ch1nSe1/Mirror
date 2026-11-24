package memore.app.data.vector

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 设备上的端到端自检：真机跑 ONNX Runtime（Android 包）+ 真模型。
 *
 * 与 JVM 版（LocalEmbeddingProviderTest）的区别：
 *  - JVM 版走桌面 ORT，验证算法与 golden 一致
 *  - 这一版走 Android ORT，验证 **native 库 / 资产读取 / 内存** 在手机上真的能跑
 *  - 顺便量一下单条耗时与批量回填速度（设计文档 §12 的预算）
 */
@RunWith(AndroidJUnit4::class)
class LocalEmbeddingProviderInstrumentedTest {

    private fun provider(): LocalEmbeddingProvider = runBlocking {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        LocalEmbeddingProvider.fromAssets(ctx.assets)
    }

    @Test
    fun assetsReadable_and_modelLoads() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val model = ctx.assets.open("models/bge-small-zh-v1.5-int8.onnx").use { it.readBytes() }
        val vocab = ctx.assets.open("models/vocab.txt").use { it.bufferedReader().readLines() }
        assertTrue("模型小于 10MB，可能下载不完整：${model.size}", model.size > 10 * 1024 * 1024)
        assertTrue("词表行数异常：${vocab.size}", vocab.size > 20000)
    }

    @Test
    fun embedding_dimAndNorm_andSemanticOrder() = runBlocking {
        val p = provider()
        try {
            val a = p.embed(listOf("今天很累")).first()
            val b = p.embed(listOf("今天特别疲倦")).first()
            val c = p.embed(listOf("无人机蜂群的饱和攻击")).first()

            assertEquals("维度", 512, a.size)
            val norm = kotlin.math.sqrt(a.sumOf { (it * it).toDouble() })
            assertEquals("L2 范数", 1.0, norm, 1e-3)

            val related = VectorMath.cosine(a, b)
            val unrelated = VectorMath.cosine(a, c)
            assertTrue("相近=$related 无关=$unrelated", related > unrelated)
        } finally {
            p.close()
        }
    }

    @Test
    fun embedding_matchesPythonGolden() = runBlocking {
        val p = provider()
        try {
            // 与 JVM 侧同一个句子（golden 由 Python onnxruntime 生成）
            val text = "今天很累"
            val vec = p.embed(listOf(text)).first()
            val expected = floatArrayOf(
                -0.010780f, 0.048150f, -0.014550f, -0.032030f, 0.059150f
            )
            for (i in expected.indices) {
                assertEquals("第 $i 维", expected[i], vec[i], 2e-3f)
            }
        } finally {
            p.close()
        }
    }

    @Test
    fun performance_singleAndBatch() = runBlocking {
        val p = provider()
        try {
            // 预热一次（首次会加载 native 与分配缓冲）
            p.embed(listOf("预热"))

            val single = measureMillis { p.embed(listOf("今天骑车回家，风有点冷。")) }
            val batchTexts = (1..10).map { "第 $it 条测试文本：关于夏天、记忆与音乐的一些碎片。" }
            val batch = measureMillis { p.embed(batchTexts) }

            println("[PERF] 单条=${single}ms  10条批量=${batch}ms  单条均摊=${batch / 10}ms")
            assertTrue("单条超过 400ms（$single ms）", single < 400)
        } finally {
            p.close()
        }
    }

    private inline fun measureMillis(block: () -> Unit): Long {
        val start = System.nanoTime()
        block()
        return (System.nanoTime() - start) / 1_000_000
    }
}
