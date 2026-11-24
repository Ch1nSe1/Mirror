package memore.app.core.voice

import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceTranslationTest {

    @Test
    fun `extractPlainText strips thinking chain and wrappers`() {
        val raw = """
            {"choices":[{"message":{"content":"<think>推理过程</think>\n```\nこんにちは。\n```"}}]}
        """.trimIndent()
        assertEquals("こんにちは。", VoiceTranslation.extractPlainText(raw, "http://127.0.0.1:11434/v1"))
    }

    @Test
    fun `extractPlainText strips wrapping quotes`() {
        val raw = """{"choices":[{"message":{"content":"「おはようございます。」"}}]}"""
        assertEquals("おはようございます。", VoiceTranslation.extractPlainText(raw, "http://127.0.0.1:11434/v1"))
    }

    @Test
    fun `extractPlainText uses ollama response field`() {
        val raw = """{"response":"こんばんは。"}"""
        assertEquals("こんばんは。", VoiceTranslation.extractPlainText(raw, "http://127.0.0.1:11434"))
    }
}
