package memore.app.utils

import android.util.Base64
import com.google.gson.JsonParser
import memore.app.data.local.dao.EncryptionConfigDao
import memore.app.data.model.EncryptionConfig
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/**
 * 加密 v3（主密钥 + 包裹）核心行为回归。
 *
 * 全部跑在 JVM（Robolectric 提供 `android.util.Base64`），**不依赖真机、不碰真实数据库**。
 * 这些断言覆盖的正是"改密码会不会毁掉数据"这件事，所以每条都必须过。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])   // Robolectric 4.13 最高支持 34；app 的 targetSdk=35
class EncryptionV3Test {

    /** 内存版配置表（`EncryptionConfigDao` 只有 get/upsert/delete 三个方法）。 */
    private class FakeDao : EncryptionConfigDao {
        var current: EncryptionConfig? = null
        var upsertCount = 0
        override suspend fun get(): EncryptionConfig? = current
        override suspend fun upsert(config: EncryptionConfig) { current = config; upsertCount++ }
        override suspend fun delete() { current = null }
    }

    private lateinit var dao: FakeDao

    private val pw = "old-password-1"
    private val newPw = "new-password-2"
    private val question = "最喜欢的乐队？"
    private val answer = "yorushika"
    private val newQuestion = "最喜欢的颜色？"
    private val newAnswer = "blue"

    @Before
    fun setUp() {
        dao = FakeDao()
        EncryptionManager.init(dao)
        EncryptionManager.clearCachedKey()
        // 归一化 EncryptionManager 的进程内状态（object 单例跨用例共享）：空库 → 未配置。
        runBlocking { EncryptionManager.refreshConfigStatus() }
    }

    // ---- 首次设置密码：主密钥是真随机的，且两个包裹都能取回同一把 ----

    @Test
    fun setPassword_masterKeyIsRecoverableByPassword() = runBlocking {
        assertTrue(EncryptionManager.setPassword(pw, question, answer).isSuccess)
        val cfg = dao.current!!
        assertEquals(3, cfg.encVersion)
        assertTrue("v3 必须写出 wrappedKeyPw", cfg.wrappedKeyPw.isNotEmpty())

        val direct = EncryptionManager.getCachedKey()!!            // setPassword 自己缓存的那把
        EncryptionManager.clearCachedKey()
        val viaEnsure = EncryptionManager.ensureKey(pw).getOrThrow()
        assertArrayEquals(direct.encoded, viaEnsure.encoded)
    }

    @Test
    fun setPassword_masterKeyIsRandom_notDerivedFromPassword() = runBlocking {
        assertTrue(EncryptionManager.setPassword(pw, question, answer).isSuccess)
        val k1 = EncryptionManager.getCachedKey()!!.encoded

        // 同一密码、同样流程走第二遍（新配置）→ 主密钥必须不同。
        // 若哪天有人把"随机主密钥"改回"密码派生"，这条会立刻变红。
        dao.current = null
        EncryptionManager.clearCachedKey()
        assertTrue(EncryptionManager.setPassword(pw, question, answer).isSuccess)
        val k2 = EncryptionManager.getCachedKey()!!.encoded

        assertFalse("主密钥必须与密码无关", k1.contentEquals(k2))
    }

    // ---- 改密码：内容零改动（v3 的核心断言）----

    @Test
    fun changePassword_keepsMasterKeyIdentical_soCiphertextStaysReadable() = runBlocking {
        assertTrue(EncryptionManager.setPassword(pw, question, answer).isSuccess)
        val masterBefore = EncryptionManager.getCachedKey()!!.encoded

        // 用主密钥加密一段内容，记住密文（这就是"用户的记录"）
        val blob = EncryptionManager.encryptContent("被锁住的正文", EncryptionManager.getCachedKey()!!)

        assertTrue(EncryptionManager.changePassword(pw, newPw, newQuestion, newAnswer).isSuccess)

        // ① 主密钥逐字节没变 —— 这才是"内容零改动"的根因
        val masterAfter = EncryptionManager.ensureKey(newPw).getOrThrow()
        assertArrayEquals("改密码绝不能换掉主密钥", masterBefore, masterAfter.encoded)

        // ② 改密码之前产生的密文，用新密码照样解得开，且明文一致
        assertEquals(
            "被锁住的正文",
            EncryptionManager.decryptContent(blob.encryptedData, blob.iv, blob.authTag, masterAfter)
        )
    }

    @Test
    fun changePassword_oldPasswordStopsWorking_newOneWorks() = runBlocking {
        assertTrue(EncryptionManager.setPassword(pw, question, answer).isSuccess)
        assertTrue(EncryptionManager.changePassword(pw, newPw, newQuestion, newAnswer).isSuccess)
        EncryptionManager.clearCachedKey()

        assertTrue(EncryptionManager.ensureKey(newPw).isSuccess)
        EncryptionManager.clearCachedKey()
        assertTrue(EncryptionManager.verifyPassword(pw).not())
        assertTrue(EncryptionManager.ensureKey(pw).isFailure)
    }

    @Test
    fun changePassword_withWrongOldPassword_changesNothing() = runBlocking {
        assertTrue(EncryptionManager.setPassword(pw, question, answer).isSuccess)
        val before = dao.current!!
        val masterBefore = EncryptionManager.getCachedKey()!!.encoded

        val r = EncryptionManager.changePassword("wrong-password", newPw, newQuestion, newAnswer)
        assertTrue(r.isFailure)

        assertEquals("失败时配置必须原样不动", before, dao.current)
        EncryptionManager.clearCachedKey()
        assertArrayEquals(masterBefore, EncryptionManager.ensureKey(pw).getOrThrow().encoded)
    }

    // ---- 密保重置 ----

    @Test
    fun resetPasswordByAnswer_newPasswordWorks_identityOfMasterKept() = runBlocking {
        assertTrue(EncryptionManager.setPassword(pw, question, answer).isSuccess)
        val masterBefore = EncryptionManager.getCachedKey()!!.encoded

        assertTrue(EncryptionManager.resetPasswordByAnswer(answer, newPw).isSuccess)
        EncryptionManager.clearCachedKey()

        assertArrayEquals(masterBefore, EncryptionManager.ensureKey(newPw).getOrThrow().encoded)
        assertTrue(EncryptionManager.verifyAnswer(answer))
    }

    @Test
    fun resetPasswordByAnswer_withWrongAnswer_changesNothing() = runBlocking {
        assertTrue(EncryptionManager.setPassword(pw, question, answer).isSuccess)
        val before = dao.current!!
        assertTrue(EncryptionManager.resetPasswordByAnswer("wrong-answer", newPw).isFailure)
        assertEquals(before, dao.current)
    }

    // ---- 存量 v2 配置的"收编式"升级：不重加密任何内容 ----

    @Test
    fun legacyV2_byChangePassword_adoptsExistingKey_withoutReEncrypting() = runBlocking {
        val legacyKey = installLegacyV2Config()
        // v2 时代用"密码派生密钥"加密的内容
        val blob = EncryptionManager.encryptContent("v2 时代锁住的正文", legacyKey)

        assertTrue(EncryptionManager.changePassword(pw, newPw, newQuestion, newAnswer).isSuccess)

        val cfg = dao.current!!
        assertEquals(3, cfg.encVersion)
        assertTrue(cfg.wrappedKeyPw.isNotEmpty())

        EncryptionManager.clearCachedKey()
        val after = EncryptionManager.ensureKey(newPw).getOrThrow()
        assertArrayEquals("升级必须收编原密钥，否则旧密文全废", legacyKey.encoded, after.encoded)
        assertEquals(
            "v2 时代锁住的正文",
            EncryptionManager.decryptContent(blob.encryptedData, blob.iv, blob.authTag, after)
        )
    }

    @Test
    fun legacyV2_byAnswer_adoptsExistingKey_fromLegacyWrappedKey() = runBlocking {
        val legacyKey = installLegacyV2Config()
        val blob = EncryptionManager.encryptContent("v2 时代锁住的正文", legacyKey)

        // 用户忘了密码，只能走密保 —— 这条路必须也能收编成功
        assertTrue(EncryptionManager.resetPasswordByAnswer(answer, newPw).isSuccess)

        EncryptionManager.clearCachedKey()
        val after = EncryptionManager.ensureKey(newPw).getOrThrow()
        assertArrayEquals(legacyKey.encoded, after.encoded)
        assertEquals(
            "v2 时代锁住的正文",
            EncryptionManager.decryptContent(blob.encryptedData, blob.iv, blob.authTag, after)
        )
    }

    @Test
    fun legacyV2_upgradeRotatesKeySalt_soOldDerivationPathDies() = runBlocking {
        installLegacyV2Config()
        val oldKeySalt = dao.current!!.keySalt
        assertTrue(EncryptionManager.changePassword(pw, newPw, newQuestion, newAnswer).isSuccess)
        assertFalse("keySalt 必须换新，否则旧密码+旧盐仍能派生出主密钥", dao.current!!.keySalt == oldKeySalt)
    }

    /**
     * v1（hex ASCII 盐）升级的回归守卫。
     *
     * `saltToBytes` 对 v1 取 `toByteArray()`、对 v2+ 取 Base64 解码。升级到 v3 后若**原样保留**
     * v1 的 hex 盐字符串，就会被按 Base64 解出另一串字节 → `answerHash` 再也对不上 → 用户被锁在门外。
     * 密保重置是唯一会把旧盐保留下来的路径，所以专门测它。
     */
    @Test
    fun legacyV1_byAnswer_keepsAnswerVerifiable() = runBlocking {
        val hexPwSalt = "00112233445566778899aabbccddeeff"
        val hexKeySalt = "ffeeddccbbaa99887766554433221100"
        val hexAnsSalt = "0123456789abcdef0123456789abcdef"
        val it = 1000
        val key = derive(hexKeySalt, it, pw)
        dao.current = EncryptionConfig(
            enabled = true, iterations = it, encVersion = 1,
            passwordSalt = hexPwSalt,
            passwordHash = CryptoUtil.deriveKeyHex(pw, hexPwSalt.toByteArray(), it),
            keySalt = hexKeySalt,
            question = question,
            questionHash = CryptoUtil.deriveKeyHex(question, question.toByteArray(), it),
            questionSalt = "",
            answerSalt = hexAnsSalt,
            answerHash = CryptoUtil.deriveKeyHex(answer, hexAnsSalt.toByteArray(), it),
            wrappedKey = wrapLikeLegacy(key, derive(hexAnsSalt, it, answer))
        )
        val blob = EncryptionManager.encryptContent("v1 时代锁住的正文", key)

        assertTrue(EncryptionManager.resetPasswordByAnswer(answer, newPw).isSuccess)

        // 这条最容易漏：升级后密保答案必须还能被验证通过
        assertTrue("v1 的 hex 盐必须原字节重新编码，否则 answerHash 对不上", EncryptionManager.verifyAnswer(answer))
        EncryptionManager.clearCachedKey()
        val after = EncryptionManager.ensureKey(newPw).getOrThrow()
        assertArrayEquals(key.encoded, after.encoded)
        assertEquals("v1 时代锁住的正文", EncryptionManager.decryptContent(blob.encryptedData, blob.iv, blob.authTag, after))
    }

    // ---- 安全：明文哈希不得等于"解开包裹的密钥" ----

    @Test
    fun v3_wrappedCopies_cannotBeOpenedByStoredPlaintextHashes() = runBlocking {
        assertTrue(EncryptionManager.setPassword(pw, question, answer).isSuccess)
        val cfg = dao.current!!

        assertFalse(
            "passwordHash 是明文存库的，绝不能用它解开主密钥包裹",
            canOpen(cfg.wrappedKeyPw, hexKey(cfg.passwordHash))
        )
        assertFalse(
            "answerHash 是明文存库的，绝不能用它解开主密钥包裹",
            canOpen(cfg.wrappedKey, hexKey(cfg.answerHash))
        )
    }

    /** 记录 v2 的原始缺陷：那时的"密保包裹"确实能被明文 `answerHash` 解开（v3 起不再如此）。 */
    @Test
    fun legacyV2Wrap_wasOpenableByStoredAnswerHash() = runBlocking {
        val legacyKey = installLegacyV2Config()
        val cfg = dao.current!!
        assertTrue(
            "v2 的洞本来就在：answerHash 与解开包裹的密钥同派生",
            canOpen(cfg.wrappedKey, hexKey(cfg.answerHash))
        )
        assertNotNull(legacyKey)
    }

    // ---- 状态缓存：写入方必须显式刷新（否则新设备导入后走进死路） ----

    @Test
    fun hasPassword_doesNotFollowTheDatabase_soWritersMustRefresh() = runBlocking {
        // 钉住的是"状态缓存"这个机制本身，而不是某个功能：
        // `hasPassword()` 读内存缓存 `passwordConfigured`，它只在 App onCreate 刷过一次。
        // 新设备上"导入加密备份"是**运行时**写配置的 —— 如果那条路径忘了刷新缓存，
        // 设置页会显示「设置密码」，而 setPassword 又会被守卫拒绝（库里确实有配置），
        // 唯一正确的「修改密码」入口够不着 → 用户卡死。所以 ImportEngine 里那次
        // refreshConfigStatus() 不能删，SettingsScreen 入口处的刷新也不能删。
        assertFalse(EncryptionManager.hasPassword())

        installLegacyV2Config()   // 相当于"导入在运行时把配置直接写进了库"

        assertFalse("缓存不会自己跟着数据库变 —— 这正是那个死路的成因", EncryptionManager.hasPassword())
        EncryptionManager.refreshConfigStatus()
        assertTrue("刷新之后才正确", EncryptionManager.hasPassword())
    }

    // ---- 辅助 ----

    /** 按 v2 语义装一份存量配置（内容密钥 = 密码派生值），返回那把内容密钥。 */
    private fun installLegacyV2Config(): SecretKey {
        val it = 1000
        val pwSalt = CryptoUtil.randomSaltB64()
        val keySalt = CryptoUtil.randomSaltB64()
        val ansSalt = CryptoUtil.randomSaltB64()
        val key = derive(keySalt, it, pw)
        dao.current = EncryptionConfig(
            enabled = true, iterations = it, encVersion = 2,
            passwordSalt = pwSalt,
            passwordHash = CryptoUtil.deriveKeyHex(pw, Base64.decode(pwSalt, Base64.NO_WRAP), it),
            keySalt = keySalt,
            question = question,
            questionHash = CryptoUtil.deriveKeyHex(question, Base64.decode(pwSalt, Base64.NO_WRAP), it),
            questionSalt = pwSalt,
            answerSalt = ansSalt,
            answerHash = CryptoUtil.deriveKeyHex(answer, Base64.decode(ansSalt, Base64.NO_WRAP), it),
            wrappedKey = wrapLikeLegacy(key, derive(ansSalt, it, answer))
        )
        return key
    }

    /** 复刻 v2 的包裹写法：`wrapKey(key, wrappingKey)`（private，测试里等价实现）。 */
    private fun wrapLikeLegacy(key: SecretKey, wrappingKey: SecretKey): String {
        val r = EncryptionManager.encryptContent(Base64.encodeToString(key.encoded, Base64.NO_WRAP), wrappingKey)
        return "{\"d\":\"${r.encryptedData}\",\"i\":\"${r.iv}\",\"t\":\"${r.authTag}\"}"
    }

    /** 测试用派生：v1 走 `toByteArray()`，其余走 Base64 解码（与 `saltToBytes` 同义）。 */
    private fun derive(salt: String, iterations: Int, input: String): SecretKey {
        val bytes = if (salt.length == 32 && salt.all { it in "0123456789abcdefABCDEF" }) salt.toByteArray()
        else Base64.decode(salt, Base64.NO_WRAP)
        return CryptoUtil.deriveKey(input.toCharArray(), bytes, iterations, 256)
    }

    private fun hexKey(hex: String): SecretKey =
        SecretKeySpec(ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }, "AES")

    /** 能否用给定密钥解开一个包裹 JSON。GCM 认证标签保证"解错"必然抛异常。 */
    private fun canOpen(wrapped: String, key: SecretKey): Boolean = try {
        val o = JsonParser.parseString(wrapped).asJsonObject
        EncryptionManager.decryptContent(o.get("d").asString, o.get("i").asString, o.get("t").asString, key)
        true
    } catch (_: Exception) { false }
}
