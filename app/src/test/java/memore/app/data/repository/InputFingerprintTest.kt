package memore.app.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 分析输入指纹（2026-11-19）。
 *
 * 指纹的唯一用途：记录改了以后判断"这条的分析过期了没有"。所以它必须
 * 对同一段文字稳定、对不同文字不同。
 */
class InputFingerprintTest {

    @Test
    fun `同一段文字指纹稳定`() {
        val a = inputFingerprint("今天吃药了，但是还是很难受")
        val b = inputFingerprint("今天吃药了，但是还是很难受")
        assertEquals(a, b)
        assertEquals(64, a.length)
    }

    @Test
    fun `改一个字指纹就变`() {
        val a = inputFingerprint("今天吃药了，但是还是很难受")
        val b = inputFingerprint("今天吃药了，但是还是很难受 ")
        assertNotEquals(a, b)
    }

    @Test
    fun `空串也有确定指纹`() {
        assertEquals(inputFingerprint(""), inputFingerprint(""))
        assertTrue(inputFingerprint("").matches(Regex("[0-9a-f]{64}")))
    }
}
