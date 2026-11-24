package memore.app.data.vector

import kotlin.math.sqrt

/**
 * 向量数学：归一化、余弦、CLS 池化。
 *
 * 单独抽出来是为了**能在 JVM 单测里验证**（ONNX Runtime 的 Android 包在单测环境没有 native 库，
 * 所以推理链路本身只能在设备上验，但池化/归一化这些纯计算必须先有单测兜着）。
 */
object VectorMath {

    /** L2 归一化（原地返回新数组；零向量返回零向量，不产生 NaN）。 */
    fun l2Normalize(vec: FloatArray): FloatArray {
        var sum = 0.0
        for (v in vec) sum += v.toDouble() * v.toDouble()
        val norm = sqrt(sum).toFloat()
        if (norm <= 1e-12f) return FloatArray(vec.size)
        val out = FloatArray(vec.size)
        for (i in vec.indices) out[i] = (vec[i] / norm).toFloat()
        return out
    }

    /** 余弦相似度（输入已归一化时等价于点积，这里仍做完整计算以防误用）。 */
    fun cosine(a: FloatArray, b: FloatArray): Float {
        require(a.size == b.size) { "维度不一致: ${a.size} vs ${b.size}" }
        var dot = 0.0
        var na = 0.0
        var nb = 0.0
        for (i in a.indices) {
            dot += a[i].toDouble() * b[i].toDouble()
            na += a[i].toDouble() * a[i].toDouble()
            nb += b[i].toDouble() * b[i].toDouble()
        }
        if (na <= 1e-12 || nb <= 1e-12) return 0f
        return (dot / (sqrt(na) * sqrt(nb))).toFloat()
    }

    /**
     * CLS 池化：取每个样本的第 0 个 token 向量并归一化。
     * @param output 形状 [batch][seq][dim] 的扁平结果
     */
    fun clsPoolBatch(output: Array<Array<FloatArray>>): List<FloatArray> =
        output.map { l2Normalize(it[0]) }
}
