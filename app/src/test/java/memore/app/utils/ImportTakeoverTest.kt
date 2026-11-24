package memore.app.utils

import android.content.Context
import android.net.Uri
import android.util.Base64
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import memore.app.data.local.AppDatabase
import memore.app.data.model.EncryptionConfig
import memore.app.data.repository.RecordRepository
import memore.app.data.repository.TagRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import javax.crypto.SecretKey

/**
 * 「导入到**已有密码**的设备」—— 把外来密文接管过来、最终用**本机密码**读。
 *
 * 背景（绕不过去的约束）：包里那些锁定记录是用**另一台设备的密钥**加密的，本机密码在数学上打不开。
 * 所以接管必须由用户提供"那台设备的密码或密保答案"。拿到之后：
 *   外来密文 → 用旧密钥**解开** → 按「待真锁」导入（明文 + 锁标记、密文留空）
 *   → 本机密钥补锁（`lockPendingEncryptionRecords`）→ 这批记录就用本机密码读了。
 *
 * 这条链路上顺带修掉一个既有坑：`unlockRecord` 原来对「待真锁」记录会拿**空密文**去解密，
 * `decryptContent("")` 抛 AEAD 异常 → **整条解锁报错**，用户密码正确也打不开。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ImportTakeoverTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var tempFile: File

    private val localPw = "本机密码-1"
    private val alienPw = "那台设备的密码"
    private val alienAnswer = "那台设备的答案"
    private val alienSecret = "外来的秘密"

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        EncryptionManager.init(db.encryptionConfigDao())
        EncryptionManager.clearCachedKey()
        runBlocking { EncryptionManager.refreshConfigStatus() }
        tempFile = File.createTempFile("memore-takeover-test", ".json")
    }

    @After
    fun tearDown() {
        db.close()
        tempFile.delete()
    }

    /** 接管成功后：记录被本机密钥锁住，且**用本机密钥能解出那台设备的原文**。 */
    @Test
    fun conflictedImport_withAlienPassword_adoptsAndRelocksWithLocalKey() = runBlocking {
        val (pkg, pkgKey) = installLocalPasswordAndBuildPackage()
        tempFile.writeText(packageJsonWithLockedCipher(pkg, pkgKey))

        val result = runImport(alienKey = pkgKey)
        assertTrue("导入应当成功：${result.exceptionOrNull()?.message}", result.isSuccess)
        assertEquals("外来锁定记录应当被接管，而不是跳过", 1, result.getOrThrow().imported)
        assertEquals(0, result.getOrThrow().skippedLockedCount)

        val rec = db.recordDao().getRecordById("test-record-1")!!
        assertTrue("接管后仍是锁定状态", rec.isLocked)
        assertEquals("明文列必须被清空（已真正锁上）", "", rec.content)
        assertTrue("应当有本机密文", rec.encryptedData.isNotEmpty())

        // ★ 核心：用【本机密码】解出来的就是那台设备的原文
        val localKey = EncryptionManager.ensureKey(localPw).getOrThrow()
        val payload = EncryptionManager.unpackRecordPayload(
            EncryptionManager.decryptContent(rec.encryptedData, rec.iv, rec.authTag, localKey)
        )
        assertEquals(alienSecret, payload.content)
    }

    /** 凭据用**密保答案**也能接管（用户不必记着是哪个）。 */
    @Test
    fun conflictedImport_withAlienAnswer_alsoWorks() = runBlocking {
        val (pkg, _) = installLocalPasswordAndBuildPackage()
        val pkgKey = EncryptionManager.resolvePackageKey(pkg, alienAnswer, asAnswer = true).getOrThrow()
        tempFile.writeText(packageJsonWithLockedCipher(pkg, pkgKey))

        val result = runImport(alienKey = pkgKey)
        assertTrue(result.isSuccess)
        assertEquals(1, result.getOrThrow().imported)

        val rec = db.recordDao().getRecordById("test-record-1")!!
        assertEquals("", rec.content)
        val localKey = EncryptionManager.ensureKey(localPw).getOrThrow()
        assertEquals(
            alienSecret,
            EncryptionManager.unpackRecordPayload(
                EncryptionManager.decryptContent(rec.encryptedData, rec.iv, rec.authTag, localKey)
            ).content
        )
    }

    /** 不给凭据 → 退回旧行为：跳过那批记录并明确提示（绝不导成打不开的假数据）。 */
    @Test
    fun conflictedImport_withoutAlienKey_skipsAndWarns() = runBlocking {
        val (pkg, pkgKey) = installLocalPasswordAndBuildPackage()
        tempFile.writeText(packageJsonWithLockedCipher(pkg, pkgKey))

        val result = runImport(alienKey = null)
        assertTrue(result.isSuccess)
        val ir = result.getOrThrow()
        assertEquals("不该导入那条外来锁定记录", 0, ir.imported)
        assertEquals(1, ir.skippedLockedCount)
        assertTrue(ir.hasEncryptionConflict)
    }

    /**
     * 接管了、但本机密钥当时**没缓存**（用户没在本机输过密码）：
     * 记录停在「待真锁」（明文 + 锁标记）。此时**解锁必须仍然可用** ——
     * 旧代码会拿空密文去解密、抛 AEAD 异常，让用户密码正确也打不开。
     */
    @Test
    fun adoptedRecords_leftAsPendingLock_canStillBeUnlocked() = runBlocking {
        val (pkg, pkgKey) = installLocalPasswordAndBuildPackage()
        tempFile.writeText(packageJsonWithLockedCipher(pkg, pkgKey))

        // 模拟"本机密钥未缓存"：导入前的补锁那一步就取不到钥匙
        EncryptionManager.clearCachedKey()
        val result = runImport(alienKey = pkgKey)
        assertTrue(result.isSuccess)

        val pending = db.recordDao().getRecordById("test-record-1")!!
        assertTrue(pending.isLocked)
        assertEquals("还未补锁：明文暂时在明文列里", alienSecret, pending.content)
        assertEquals("", pending.encryptedData)

        // 用本机密码解锁 —— 这条路以前是坏的（对空密文解密）
        EncryptionManager.clearCachedKey()
        val unlock = newRecordRepo().unlockRecord("test-record-1", localPw)
        assertTrue("待真锁记录必须能被正常解锁：${unlock.exceptionOrNull()?.message}", unlock.isSuccess)

        val unlocked = db.recordDao().getRecordById("test-record-1")!!
        assertFalse(unlocked.isLocked)
        assertEquals(alienSecret, unlocked.content)
    }

    // ---- 辅助 ----

    /** 本机先有自己的密码；再造一个"那台设备"的配置 + 用它的密钥加密一条锁定记录。 */
    private suspend fun installLocalPasswordAndBuildPackage(): Pair<EncryptionConfig, SecretKey> {
        assertTrue(
            EncryptionManager.setPassword(localPw, "本机的问题", "本机的答案", allowOverwrite = true).isSuccess
        )

        val iter = 1000
        val pwSalt = CryptoUtil.randomSaltB64()
        val keySalt = CryptoUtil.randomSaltB64()
        val ansSalt = CryptoUtil.randomSaltB64()
        val pkgKey = deriveB64(keySalt, iter, alienPw)
        val pkgConfig = EncryptionConfig(
            enabled = true, iterations = iter, encVersion = 2,
            passwordSalt = pwSalt,
            passwordHash = CryptoUtil.deriveKeyHex(alienPw, Base64.decode(pwSalt, Base64.NO_WRAP), iter),
            keySalt = keySalt,
            question = "那台的问题",
            questionHash = "x", questionSalt = ansSalt,
            answerSalt = ansSalt,
            answerHash = CryptoUtil.deriveKeyHex(alienAnswer, Base64.decode(ansSalt, Base64.NO_WRAP), iter),
            wrappedKey = wrapJson(pkgKey, deriveB64(ansSalt, iter, alienAnswer)),
            wrappedKeyPw = ""
        )
        return pkgConfig to pkgKey
    }

    private suspend fun runImport(alienKey: SecretKey?): Result<ImportResult> {
        return ImportEngine.execute(
            context = context,
            uri = Uri.fromFile(tempFile),
            recordRepo = newRecordRepo(),
            tagRepo = TagRepository(db.tagDao()),
            encryptionConfigDao = db.encryptionConfigDao(),
            alienKey = alienKey
        )
    }

    private fun newRecordRepo(): RecordRepository {
        return RecordRepository(
            recordDao = db.recordDao(),
            versionDao = db.versionDao(),
            attachmentDao = db.attachmentDao(),
            taskDao = db.aiTaskDao(),
            db = db
        )
    }

    private fun deriveB64(salt: String, iterations: Int, input: String): SecretKey =
        CryptoUtil.deriveKey(input.toCharArray(), Base64.decode(salt, Base64.NO_WRAP), iterations, 256)

    /** 复刻 `EncryptionManager.wrapKey`（private，测试里等价实现）。 */
    private fun wrapJson(key: SecretKey, wrappingKey: SecretKey): String {
        val r = EncryptionManager.encryptContent(Base64.encodeToString(key.encoded, Base64.NO_WRAP), wrappingKey)
        return "{\"d\":\"${r.encryptedData}\",\"i\":\"${r.iv}\",\"t\":\"${r.authTag}\"}"
    }

    /** 一个最小包：一条**用那台设备密钥加密**的锁定记录 + 那台设备的加密配置。 */
    private fun packageJsonWithLockedCipher(pkg: EncryptionConfig, pkgKey: SecretKey): String {
        val payload = EncryptionManager.packPayload(
            RecordPayload(
                content = alienSecret, notes = "", dialogueAiContent = "", dialogueAiReply = "",
                analysisJson = null, emotionalTags = "[]", hasAnalysis = false, hasEmotionalTags = true
            )
        )
        val cipher = EncryptionManager.encryptContent(payload, pkgKey)
        return """
        {
          "version": "2.5",
          "export_time": "2026-01-01T00:00:00Z",
          "import_enabled": true,
          "count": 1,
          "records": [
            {
              "id": "test-record-1",
              "type": "ESSAY",
              "content": "",
              "tags": [],
              "timestamp": "2026-01-01T00:00:00Z",
              "starred": false,
              "deleted": false,
              "is_locked": true,
              "encrypted_data": "${cipher.encryptedData}",
              "iv": "${cipher.iv}",
              "auth_tag": "${cipher.authTag}"
            }
          ],
          "encryption_config": {
            "algorithm": "PBKDF2WithHmacSHA256",
            "key_iterations": ${pkg.iterations},
            "cipher": "AES-256-GCM",
            "password_hash": "${pkg.passwordHash}",
            "password_salt": "${pkg.passwordSalt}",
            "key_salt": "${pkg.keySalt}",
            "question": "${pkg.question}",
            "question_hash": "${pkg.questionHash}",
            "question_salt": "${pkg.questionSalt}",
            "answer_hash": "${pkg.answerHash}",
            "answer_salt": "${pkg.answerSalt}",
            "wrapped_key": "${pkg.wrappedKey.replace("\"", "\\\"")}",
            "enc_version": ${pkg.encVersion}
          }
        }
        """.trimIndent()
    }
}
