package memore.app.utils

import android.util.Base64
import java.security.SecureRandom
import java.security.spec.KeySpec
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * 加密基础工具：统一的 PBKDF2 派生与盐值编解码。
 *
 * 盐值一律以 ByteArray 参与 PBKDF2（v2 起），DB 中存 Base64 字符串。
 * 兼容 v1 旧格式（hex 字符串的 ASCII 字节）由调用方通过 [saltToBytes] 按版本解码。
 */
object CryptoUtil {

    private const val ALGO = "PBKDF2WithHmacSHA256"

    /** PBKDF2 派生 AES 密钥（256 位）。 */
    fun deriveKey(password: CharArray, salt: ByteArray, iterations: Int, keyLengthBits: Int = 256): SecretKey {
        val spec: KeySpec = PBEKeySpec(password, salt, iterations, keyLengthBits)
        val factory = SecretKeyFactory.getInstance(ALGO)
        return SecretKeySpec(factory.generateSecret(spec).encoded, "AES")
    }

    /** PBKDF2 派生密钥并以 hex 字符串返回（用于存储密码/问题/答案哈希）。 */
    fun deriveKeyHex(input: String, salt: ByteArray, iterations: Int, keyLengthBits: Int = 256): String {
        return deriveKey(input.toCharArray(), salt, iterations, keyLengthBits)
            .encoded.joinToString("") { "%02x".format(it) }
    }

    /** 生成 Base64 编码的随机盐（16 字节）。 */
    fun randomSaltB64(): String {
        val bytes = ByteArray(16); SecureRandom().nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    /**
     * 按加密方案版本把 DB 中的盐值字符串还原为字节：
     * v2+ → Base64 解码；v1（旧格式）→ hex 字符串的 ASCII 字节（保持兼容，勿改）。
     */
    fun saltToBytes(salt: String, encVersion: Int): ByteArray =
        if (encVersion >= 2) Base64.decode(salt, Base64.NO_WRAP) else salt.toByteArray()
}
