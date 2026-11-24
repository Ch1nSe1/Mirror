package memore.app.utils

import memore.app.data.model.RedactionGroup
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * 脱敏引擎：支持两种乱码方式。
 * - 随机：每次命中独立随机，无法通过相同乱码反推同源关系。
 * - 哈希：同组统一确定性哈希，保留可关联性（供 AI 梳理关系）。
 */
object RedactionEngine {

    const val MODE_RANDOM = "random"
    const val MODE_HASH = "hash"

    private val random = SecureRandom()

    fun redact(text: String, groups: List<RedactionGroup>, mode: String = MODE_RANDOM): String {
        if (groups.isEmpty()) return text
        return if (mode == MODE_HASH) redactHash(text, groups) else redactRandom(text, groups)
    }

    /** 哈希模式：同组所有形态统一替换成标准名派生的固定乱码。 */
    private fun redactHash(text: String, groups: List<RedactionGroup>): String {
        var result = text
        groups.sortedByDescending { group ->
            (listOf(group.standardName) + group.aliases).maxOfOrNull { it.length } ?: 0
        }.forEach { group ->
            val token = tokenFor(group.standardName)
            (listOf(group.standardName) + group.aliases)
                .distinct()
                .filter { it.isNotBlank() }
                .sortedByDescending { it.length }
                .forEach { form ->
                    result = result.replace(form, token)
                }
        }
        return result
    }

    /** 随机模式：每次命中生成独立随机码，同词多次出现也不一致。 */
    private fun redactRandom(text: String, groups: List<RedactionGroup>): String {
        var result = text
        val forms = groups.flatMap { group ->
            (listOf(group.standardName) + group.aliases)
                .distinct()
                .filter { it.isNotBlank() }
        }
        // 长词优先，避免短形态先替换破坏长形态命中；每次命中生成独立随机码。
        forms.sortedByDescending { it.length }.forEach { form ->
            result = Regex(Regex.escape(form)).replace(result) { randomToken() }
        }
        return result
    }

    private fun randomToken(): String {
        val bytes = ByteArray(6)
        random.nextBytes(bytes)
        val hex = bytes.joinToString("") { String.format("%02x", it) }
        return "0x$hex"
    }

    private fun tokenFor(term: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(term.toByteArray(Charsets.UTF_8))
        val hex = digest.take(6).joinToString("") { String.format("%02x", it) }
        return "0x$hex"
    }
}
