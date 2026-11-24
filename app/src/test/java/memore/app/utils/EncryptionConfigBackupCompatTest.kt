package memore.app.utils

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 加密配置随备份包往返的口径。
 *
 * 这里钉住两件事，任何一件错了都会伤到用户：
 * 1. **v3 的主密钥包裹（`wrapped_key_pw`）必须随包走** —— 否则新设备导入后 `encVersion>=3`
 *    却没有包裹字段，`ensureKey` 取不回主密钥 → 直接锁死。
 * 2. **v2 包的字节输出不能变** —— 该字段可空且声明在末尾，空值被 Gson 省略。
 */
class EncryptionConfigBackupCompatTest {

    private val gson = Gson()

    private fun config(wrappedKeyPw: String?) = ExportEncryptionConfig(
        password_hash = "ph", password_salt = "ps", key_salt = "ks",
        question = "q", question_hash = "qh", answer_hash = "ah", answer_salt = "a_s",
        wrapped_key = "wk", enc_version = 3, question_salt = "q_s",
        wrapped_key_pw = wrappedKeyPw
    )

    @Test
    fun wrappedKeyPw_survivesJsonRoundTrip() {
        val json = gson.toJson(config("WK-PW"))
        assertTrue("v3 主密钥包裹必须写进包", json.contains("wrapped_key_pw"))

        val back = gson.fromJson(json, ExportEncryptionConfig::class.java)
        assertEquals("WK-PW", back.wrapped_key_pw)
    }

    @Test
    fun absentWrappedKeyPw_isOmitted_soV2BytesAreUnchanged() {
        val json = gson.toJson(config(null))
        assertFalse("空值不得出现，否则 v2 包的字节输出会变", json.contains("wrapped_key_pw"))

        val back = gson.fromJson(json, ExportEncryptionConfig::class.java)
        assertNull(back.wrapped_key_pw)
    }

    /** 老包（根本没有这个键）反序列化后必须是 null，而不是抛异常。 */
    @Test
    fun legacyPackage_withoutTheKey_parsesAsNull() {
        val legacy = """{"algorithm":"PBKDF2WithHmacSHA256","key_iterations":50000,"cipher":"AES-256-GCM",
            "password_hash":"ph","password_salt":"ps","key_salt":"ks","question":"q",
            "question_hash":"qh","answer_hash":"ah","answer_salt":"as","wrapped_key":"wk",
            "enc_version":2,"question_salt":"qs"}"""
        val back = gson.fromJson(legacy, ExportEncryptionConfig::class.java)
        assertNull(back.wrapped_key_pw)
        assertEquals(2, back.enc_version)
    }
}
