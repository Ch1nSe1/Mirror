package memore.app.utils

import android.content.Context

object AuthManager {
    private const val PREFS = "memore_auth"
    private const val KEY_PIN_HASH = "pin_hash"
    private const val KEY_PIN_SALT = "pin_salt"
    private const val KEY_QUESTION = "question"
    private const val KEY_QUESTION_HASH = "question_hash"
    private const val KEY_ANSWER_HASH = "answer_hash"
    private const val KEY_ANSWER_SALT = "answer_salt"
    private const val KEY_LOCK_ENABLED = "lock_enabled"
    private const val KEY_FAIL_COUNT = "fail_count"
    private const val KEY_LOCK_UNTIL = "lock_until"

    private const val MAX_FAIL_ATTEMPTS = 5
    private const val LOCK_DURATION_MS = 60_000L
    private const val PBKDF2_ITERATIONS = 40_000

    private var prefs: android.content.SharedPreferences? = null

    /**
     * PIN/答案哈希：复用 CryptoUtil 的 PBKDF2，但保持旧版 hex-ASCII 盐编码
     * （AuthManager 无版本字段，存量 PIN 哈希依赖 ASCII 字节盐，不能切换 Base64）。
     */
    private fun pbkdf2(input: String, salt: String): String =
        CryptoUtil.deriveKeyHex(input, salt.toByteArray(), PBKDF2_ITERATIONS)

    private fun randomSalt(): String {
        val bytes = ByteArray(16)
        java.security.SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    private fun p(): android.content.SharedPreferences {
        return prefs ?: throw IllegalStateException("AuthManager not initialized")
    }

    fun isLockEnabled(): Boolean = p().getBoolean(KEY_LOCK_ENABLED, false)
    fun hasPin(): Boolean = p().getString(KEY_PIN_HASH, null) != null
    fun enableLock() { p().edit().putBoolean(KEY_LOCK_ENABLED, true).apply() }
    fun disableLock() { p().edit().putBoolean(KEY_LOCK_ENABLED, false).apply() }

    fun isLockedOut(): Boolean {
        val lockUntil = p().getLong(KEY_LOCK_UNTIL, 0L)
        if (lockUntil == 0L) return false
        if (System.currentTimeMillis() >= lockUntil) {
            p().edit().remove(KEY_LOCK_UNTIL).remove(KEY_FAIL_COUNT).apply()
            return false
        }
        return true
    }

    fun setPin(pin: String) {
        val salt = randomSalt()
        p().edit()
            .putString(KEY_PIN_SALT, salt)
            .putString(KEY_PIN_HASH, pbkdf2(pin, salt))
            .apply()
    }

    fun verifyPin(pin: String): Boolean {
        val salt = p().getString(KEY_PIN_SALT, null) ?: return false
        val hash = p().getString(KEY_PIN_HASH, null) ?: return false
        val match = pbkdf2(pin, salt) == hash
        if (!match) {
            val count = p().getInt(KEY_FAIL_COUNT, 0) + 1
            p().edit().putInt(KEY_FAIL_COUNT, count).apply()
            if (count >= MAX_FAIL_ATTEMPTS) {
                p().edit().putLong(KEY_LOCK_UNTIL, System.currentTimeMillis() + LOCK_DURATION_MS).apply()
            }
        } else {
            p().edit().remove(KEY_FAIL_COUNT).remove(KEY_LOCK_UNTIL).apply()
        }
        return match
    }

    fun getRemainingLockMs(): Long {
        val lockUntil = p().getLong(KEY_LOCK_UNTIL, 0L)
        val remaining = lockUntil - System.currentTimeMillis()
        return if (remaining > 0) remaining else 0L
    }

    fun setSecurityQuestion(question: String, answer: String) {
        val salt = randomSalt()
        p().edit()
            .putString(KEY_QUESTION, question)
            .putString(KEY_QUESTION_HASH, pbkdf2(question, "question_fixed_salt"))
            .putString(KEY_ANSWER_SALT, salt)
            .putString(KEY_ANSWER_HASH, pbkdf2(answer, salt))
            .putBoolean(KEY_LOCK_ENABLED, true)
            .apply()
    }

    fun getQuestion(): String? = p().getString(KEY_QUESTION, null)

    fun verifyAnswer(answer: String): Boolean {
        val salt = p().getString(KEY_ANSWER_SALT, null) ?: return false
        val hash = p().getString(KEY_ANSWER_HASH, null) ?: return false
        return pbkdf2(answer, salt) == hash
    }

    fun resetPin(newPin: String) {
        setPin(newPin)
    }

    fun clear() {
        p().edit().clear().apply()
    }
}
