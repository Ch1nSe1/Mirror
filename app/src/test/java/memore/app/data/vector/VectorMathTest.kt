package memore.app.data.vector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class VectorMathTest {

    @Test
    fun `归一化后范数为 1`() {
        val v = VectorMath.l2Normalize(floatArrayOf(3f, 4f))
        assertEquals(0.6f, v[0], 1e-6f)
        assertEquals(0.8f, v[1], 1e-6f)
        val norm = kotlin.math.sqrt(v.sumOf { (it * it).toDouble() })
        assertEquals(1.0, norm, 1e-5)
    }

    @Test
    fun `零向量不会产生 NaN`() {
        val v = VectorMath.l2Normalize(floatArrayOf(0f, 0f, 0f))
        assertTrue(v.all { it == 0f })
    }

    @Test
    fun `相同向量余弦为 1 正交为 0 反向为 -1`() {
        val a = VectorMath.l2Normalize(floatArrayOf(1f, 2f, 3f))
        val b = VectorMath.l2Normalize(floatArrayOf(2f, 4f, 6f))
        assertEquals(1f, VectorMath.cosine(a, b), 1e-5f)
        assertEquals(0f, VectorMath.cosine(floatArrayOf(1f, 0f), floatArrayOf(0f, 1f)), 1e-5f)
        assertEquals(-1f, VectorMath.cosine(floatArrayOf(1f, 0f), floatArrayOf(-1f, 0f)), 1e-5f)
    }

    @Test
    fun `CLS 池化取第 0 个 token 并归一化`() {
        val batch = arrayOf(
            arrayOf(floatArrayOf(3f, 4f), floatArrayOf(9f, 9f)),   // 第 0 个 token = (3,4)
            arrayOf(floatArrayOf(0f, 5f), floatArrayOf(1f, 1f))
        )
        val pooled = VectorMath.clsPoolBatch(batch)
        assertEquals(2, pooled.size)
        assertEquals(0.6f, pooled[0][0], 1e-6f)
        assertEquals(0.8f, pooled[0][1], 1e-6f)
        assertEquals(0f, pooled[1][0], 1e-6f)
        assertEquals(1f, pooled[1][1], 1e-6f)
    }

    @Test
    fun `维度不一致会抛错`() {
        var threw = false
        try {
            VectorMath.cosine(floatArrayOf(1f), floatArrayOf(1f, 2f))
        } catch (e: IllegalArgumentException) {
            threw = true
        }
        assertTrue("应抛 IllegalArgumentException", threw)
    }

    @Test
    fun `归一化不改变方向`() {
        val raw = floatArrayOf(0.2f, -1.3f, 4.4f, 0.01f)
        val n = VectorMath.l2Normalize(raw)
        val cos = VectorMath.cosine(raw, n)
        assertTrue("cos=${abs(cos)}", abs(cos - 1f) < 1e-5f)
    }
}
