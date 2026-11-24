package memore.app.utils

import android.util.Base64
import memore.app.data.local.dao.EncryptionConfigDao
import memore.app.data.model.EncryptionConfig
import memore.app.data.model.Record
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.concurrent.withLock

data class EncryptResult(val encryptedData: String, val iv: String, val authTag: String)

/** 附件文件加解密结果（iv/auth_tag 持久化到 attachments 表）。 */
data class AttachmentCryptoResult(val iv: String, val authTag: String)

/**
 * 一条记录的加密载荷（明文还原体）。analysisJson 为 AnalysisData 的原始 JSON 字符串；
 * hasAnalysis/hasEmotionalTags 用于识别升级前锁定的旧记录（payload 缺 a/e，DB 明文列仍可回填）。
 */
data class RecordPayload(
    val content: String,
    val notes: String,
    val dialogueAiContent: String,
    val dialogueAiReply: String,
    val analysisJson: String?,
    val emotionalTags: String,
    val hasAnalysis: Boolean,
    val hasEmotionalTags: Boolean
)

object EncryptionManager {

    /**
     * 加密方案版本：
     * 1 = 旧格式（hex ASCII 盐）；
     * 2 = Base64 随机盐，**内容密钥每次由密码现算** → 换密码必换密钥 → 必须重加密全部内容；
     * 3 = 主密钥 + 包裹（换密码只重写包裹，**内容零改动**）。
     */
    private const val ENC_VERSION = 3

    /**
     * KEK 域分离标签。
     *
     * 包裹主密钥用的 KEK **必须与库里存的任何明文校验值不是同一个派生** —— 否则拿到 DB 文件
     * 就等于拿到钥匙：v2 的 `answerHash` 与包裹内容密钥用的密钥正是同一个派生（同盐同参数），
     * 于是"密保包裹"形同虚设。v3 起两种 KEK 都带本标签，与 `passwordHash`/`answerHash` 分开。
     */
    private val KEK_LABEL = "|memory-kek-v1".toByteArray(Charsets.UTF_8)

    // 新密码默认迭代次数（移动端平衡安全与性能；本地自用、防他人看内容，5 万足够）。旧配置从 config.iterations 读取。
    private const val NEW_PASSWORD_ITERATIONS = 50_000
    private const val KEY_LENGTH_BITS = 256
    private const val GCM_IV_LENGTH_BYTES = 12
    private const val GCM_TAG_LENGTH_BITS = 128
    private const val AES_TRANSFORMATION = "AES/GCM/NoPadding"
    /** 仅 v1 旧配置使用（无 question_salt 时回退）；v2 起用随机盐。 */
    private const val QUESTION_FIXED_SALT = "encryption_question_fixed_salt"

    // ---------------------------------------------------------------- v5（门闩式，2026-11-16）

    /**
     * v5：**数据层不再加密**，只保留"锁定"这个用户层门闩。
     *
     * 与 v1~v3 的根本区别：
     *  · 没有主密钥、没有包裹、没有逐条 AES；
     *  · 配置里只留一个 verifier（salt + PBKDF2 校验值）；
     *  · 锁定记录的正文就是明文，存在 `records.content` 里；
     *  · 因此**任何人都能从数据库文件里读走锁定内容** —— 这是用户明确接受的代价，
     *    换来的是"导出完整、检索可用、换机可迁"。
     */
    const val V5_VERSION = 5

    /** v5 verifier 的迭代次数：比旧系统的 5 万高一个档（本地校验可接受 ~200ms）。 */
    private const val V5_ITERATIONS = 210_000


    private var dao: EncryptionConfigDao? = null

    @Volatile
    private var cachedKey: SecretKey? = null

    @Volatile
    private var passwordConfigured = false

    @Volatile
    private var enabled = false

    /** 当前配置版本（v5 = 门闩式）。0 = 没有配置。 */
    @Volatile
    private var lockVersion = 0

    /** 附件文件加解密并发保护：按规范化路径加锁，避免同一文件并发读写损坏。 */
    private val fileLocks = ConcurrentHashMap<String, ReentrantLock>()

    fun init(encryptionConfigDao: EncryptionConfigDao) {
        dao = encryptionConfigDao
    }

    suspend fun refreshConfigStatus() = withContext(Dispatchers.IO) {
        val config = try { d().get() } catch (_: Exception) { null }
        passwordConfigured = config != null
        enabled = config?.enabled ?: false
        lockVersion = config?.encVersion ?: 0
        if (!passwordConfigured) cachedKey = null
    }

    fun isEnabled(): Boolean = enabled
    fun isConfigured(): Boolean = dao != null && passwordConfigured
    fun hasPassword(): Boolean = passwordConfigured

    /** 当前是不是"门闩式"（v5）：是的话锁记录只置标记，不加密任何东西。 */
    fun isV5(): Boolean = lockVersion == V5_VERSION

    /** 配置版本号（0 = 未设置）。UI 用它决定要不要提示迁移。 */
    fun configVersion(): Int = lockVersion

    /**
     * 本机配置是否**尚未**升级到 v3 —— 也就是"下一次改密码 / 密保重置会顺带做一次升级"。
     *
     * 供 UI 在动手前提醒用户**先备份**：这个升级是**单向**的（`keySalt` 会被换新，
     * 旧派生路径随之作废），升级之后**无法靠退回旧版本来读数据，只能靠备份还原**。
     */
    suspend fun needsV3Upgrade(): Boolean = withContext(Dispatchers.IO) {
        val config = try { d().get() } catch (_: Exception) { null } ?: return@withContext false
        !isV3(config)
    }

    /**
     * 用"**另一台设备**"的凭据解开**那个包里的加密配置**，取出它的内容密钥。
     *
     * 这是「导入到本机已有密码的设备」这条路的钥匙：外来记录的密文只能用**旧设备的密钥**解开，
     * 而旧密钥只能由旧密码或旧密保答案得到 —— **新密码在数学上打不开它**。
     * 凭据先按该配置自己的哈希校验（校验不过直接返回失败，不去猜），再取密钥。
     */
    suspend fun resolvePackageKey(
        pkg: EncryptionConfig,
        credential: String,
        asAnswer: Boolean
    ): Result<SecretKey> = withContext(Dispatchers.IO) {
        val v = pkg.encVersion
        try {
            if (asAnswer) {
                val answerSalt = CryptoUtil.saltToBytes(pkg.answerSalt, v)
                if (CryptoUtil.deriveKeyHex(credential, answerSalt, pkg.iterations) != pkg.answerHash)
                    return@withContext Result.failure(Exception("那台设备的密保答案不对"))
                val master = if (isV3(pkg)) {
                    unwrapKey(pkg.wrappedKey, kekFromAnswer(credential, pkg.answerSalt, pkg.iterations))
                } else {
                    unwrapKey(pkg.wrappedKey, deriveKeyFromAnswer(credential, answerSalt, pkg.iterations))
                }
                Result.success(master)
            } else {
                if (CryptoUtil.deriveKeyHex(credential, CryptoUtil.saltToBytes(pkg.passwordSalt, v), pkg.iterations) != pkg.passwordHash)
                    return@withContext Result.failure(Exception("那台设备的密码不对"))
                val master = if (isV3(pkg)) {
                    unwrapKey(pkg.wrappedKeyPw, kekFromPassword(credential, pkg.keySalt, pkg.iterations))
                } else {
                    deriveKeyInternal(credential, CryptoUtil.saltToBytes(pkg.keySalt, v), pkg.iterations)
                }
                Result.success(master)
            }
        } catch (e: Exception) {
            Result.failure(Exception("无法取出那台设备的密钥：${e.message ?: "配置可能损坏"}"))
        }
    }

    suspend fun setEnabled(value: Boolean) = withContext(Dispatchers.IO) {
        try {
            val config = d().get() ?: return@withContext
            d().upsert(config.copy(enabled = value, updatedAt = System.currentTimeMillis()))
            enabled = value
        } catch (_: Exception) {}
    }

    suspend fun toggleEnabled(): Boolean = withContext(Dispatchers.IO) {
        try {
            val config = d().get() ?: return@withContext false
            val newVal = !config.enabled
            d().upsert(config.copy(enabled = newVal, updatedAt = System.currentTimeMillis()))
            enabled = newVal
            newVal
        } catch (_: Exception) { enabled }
    }

    private fun d(): EncryptionConfigDao {
        return dao ?: throw IllegalStateException("EncryptionManager not initialized")
    }

    // ---- 密码管理 (均跑在 IO 线程) ----

    /**
     * 首次设置密码：生成一把**全新的随机主密钥**，用密码与答案各包裹一份。
     *
     * v3 起内容密钥不再等于"密码派生值"，所以此后换密码只需重新包裹主密钥，内容一个字节都不用动。
     *
     * [allowOverwrite] 由调用方按**数据状态**决定：本机若存在"真正带密文"的记录（依赖旧密钥），
     * 就必须为 `false` —— 否则会覆盖配置，旧密文永久读不回、旧密码也失效。
     * 若本机只有导入产生的「待真锁」记录（明文 + 标记，不依赖旧密钥），传 `true` 是正确流程。
     */
    suspend fun setPassword(password: String, question: String, answer: String, allowOverwrite: Boolean = false): Result<Unit> = withContext(Dispatchers.IO) {
        if (d().get() != null && !allowOverwrite) {
            return@withContext Result.failure(Exception("本机已有加密配置。若密文来自旧设备，请用旧密码「修改密码」接管，不要重新设置"))
        }
        val it = NEW_PASSWORD_ITERATIONS
        val pwSalt = CryptoUtil.randomSaltB64()
        val pwHash = CryptoUtil.deriveKeyHex(password, CryptoUtil.saltToBytes(pwSalt, ENC_VERSION), it)
        val kSalt = CryptoUtil.randomSaltB64()
        // v2 起：密保问题哈希使用随机盐（不再用固定盐）
        val qSalt = CryptoUtil.randomSaltB64()
        val qHash = CryptoUtil.deriveKeyHex(question, CryptoUtil.saltToBytes(qSalt, ENC_VERSION), it)
        val aSalt = CryptoUtil.randomSaltB64()
        val aHash = CryptoUtil.deriveKeyHex(answer, CryptoUtil.saltToBytes(aSalt, ENC_VERSION), it)

        val master = randomMasterKey()

        d().upsert(EncryptionConfig(
            enabled = true, iterations = it, encVersion = ENC_VERSION,
            passwordSalt = pwSalt, passwordHash = pwHash, keySalt = kSalt,
            question = question.trim(), questionHash = qHash, questionSalt = qSalt,
            answerSalt = aSalt, answerHash = aHash,
            wrappedKeyPw = wrapKey(master, kekFromPassword(password, kSalt, it)),
            wrappedKey = wrapKey(master, kekFromAnswer(answer, aSalt, it)),
            createdAt = System.currentTimeMillis(), updatedAt = System.currentTimeMillis()
        ))
        cachedKey = master
        passwordConfigured = true
        enabled = true
        Result.success(Unit)
    }

    suspend fun verifyPassword(password: String): Boolean = withContext(Dispatchers.IO) {
        // 空密码会让 PBKDF2 抛 IllegalArgumentException("password empty") 并直接崩进程，
        // 先在入口挡掉（2026-10-24 从"查看"按钮空提交复现）。
        if (password.isEmpty()) return@withContext false
        val config = d().get() ?: return@withContext false
        runCatching {
            CryptoUtil.deriveKeyHex(password, CryptoUtil.saltToBytes(config.passwordSalt, config.encVersion), config.iterations) == config.passwordHash
        }.getOrDefault(false)
    }

    // ---------------------------------------------------------------- v5 门闩（2026-11-16）

    /**
     * 设置/重设 v5 密码（只写 verifier）。
     *
     * **不生成密钥、不包裹任何东西**：验证密码就是重算一次 PBKDF2 比对。
     * 也正因如此，重设密码不会影响任何记录内容（内容本来就是明文）。
     *
     * 2026-11-16：**密保一起存**。用户口径：设密码时必须设密保；
     * 忘记密码=用密保答案重置。v5 之前把 question/answer 清成空，找回路径就断了。
     */
    suspend fun setLockPassword(
        password: String,
        question: String = "",
        answer: String = ""
    ): Result<Unit> = withContext(Dispatchers.IO) {
        if (password.length < 4) return@withContext Result.failure(Exception("密码太短（至少 4 位）"))
        runCatching {
            val salt = CryptoUtil.randomSaltB64()
            val hash = CryptoUtil.deriveKeyHex(password, CryptoUtil.saltToBytes(salt, V5_VERSION), V5_ITERATIONS)
            val q = question.trim()
            val a = answer.trim()
            val answerSalt = if (a.isNotEmpty()) CryptoUtil.randomSaltB64() else ""
            val answerHash = if (a.isNotEmpty()) {
                CryptoUtil.deriveKeyHex(a, CryptoUtil.saltToBytes(answerSalt, V5_VERSION), V5_ITERATIONS)
            } else ""
            val now = System.currentTimeMillis()
            d().upsert(
                EncryptionConfig(
                    enabled = true,
                    iterations = V5_ITERATIONS,
                    encVersion = V5_VERSION,
                    passwordSalt = salt,
                    passwordHash = hash,
                    keySalt = "",
                    question = q,
                    questionHash = "",
                    questionSalt = "",
                    answerSalt = answerSalt,
                    answerHash = answerHash,
                    wrappedKey = "",
                    wrappedKeyPw = "",
                    createdAt = now,
                    updatedAt = now
                )
            )
            passwordConfigured = true
            enabled = true
            lockVersion = V5_VERSION
            cachedKey = null
        }
    }

    /** v5：忘记密码时要展示的密保问题（没设过就是 null）。 */
    suspend fun getLockQuestion(): String? = withContext(Dispatchers.IO) {
        runCatching { d().get() }.getOrNull()
            ?.takeIf { it.encVersion == V5_VERSION && it.question.isNotBlank() }
            ?.question
    }

    /** v5：校验密保答案（PBKDF2 + 随机盐，与密码同一套参数）。 */
    suspend fun verifyLockAnswer(answer: String): Boolean = withContext(Dispatchers.IO) {
        if (answer.isBlank()) return@withContext false
        val config = runCatching { d().get() }.getOrNull() ?: return@withContext false
        if (config.encVersion != V5_VERSION) return@withContext false
        if (config.answerHash.isBlank() || config.answerSalt.isBlank()) return@withContext false
        runCatching {
            CryptoUtil.deriveKeyHex(
                answer.trim(),
                CryptoUtil.saltToBytes(config.answerSalt, V5_VERSION),
                config.iterations
            ) == config.answerHash
        }.getOrDefault(false)
    }

    /** v5：忘记密码 → 用密保答案重设密码（密保原样保留）。 */
    suspend fun resetLockPasswordByAnswer(answer: String, newPassword: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            if (!verifyLockAnswer(answer)) return@withContext Result.failure(Exception("密保答案不对"))
            val current = runCatching { d().get() }.getOrNull()
            setLockPassword(newPassword, current?.question ?: "", answer.trim())
        }

    /** v5 校验：只比 verifier，不做任何解密。空密码直接 false（避免 PBKDF2 抛异常崩进程）。 */
    suspend fun verifyLockPassword(password: String): Boolean = withContext(Dispatchers.IO) {
        if (password.isEmpty()) return@withContext false
        val config = runCatching { d().get() }.getOrNull() ?: return@withContext false
        if (config.encVersion != V5_VERSION) return@withContext verifyPassword(password)
        runCatching {
            CryptoUtil.deriveKeyHex(
                password,
                CryptoUtil.saltToBytes(config.passwordSalt, V5_VERSION),
                config.iterations
            ) == config.passwordHash
        }.getOrDefault(false)
    }

    /**
     * 改 v5 密码：先验旧的，再写新的 verifier。内容零改动（本来就没有密文）。
     * 2026-11-16：如果同时给了新密保就一起换；没给则**保留原密保**（不能凭空重算答案哈希）。
     */
    suspend fun changeLockPassword(
        old: String,
        new: String,
        newQuestion: String = "",
        newAnswer: String = ""
    ): Result<Unit> = withContext(Dispatchers.IO) {
        if (!verifyLockPassword(old)) return@withContext Result.failure(Exception("原密码不对"))
        if (newQuestion.isNotBlank() && newAnswer.isNotBlank()) {
            setLockPassword(new, newQuestion, newAnswer)
        } else {
            // 只换密码：保留 question / answerSalt / answerHash
            runCatching {
                val config = d().get() ?: throw IllegalStateException("配置不存在")
                val salt = CryptoUtil.randomSaltB64()
                val hash = CryptoUtil.deriveKeyHex(new, CryptoUtil.saltToBytes(salt, V5_VERSION), V5_ITERATIONS)
                d().upsert(
                    config.copy(
                        passwordSalt = salt,
                        passwordHash = hash,
                        iterations = V5_ITERATIONS,
                        updatedAt = System.currentTimeMillis()
                    )
                )
                passwordConfigured = true
                lockVersion = V5_VERSION
                cachedKey = null
            }
        }
    }

    // ---- 认证缓存：60 秒内相同密码不重跑 PBKDF2，解决反复验证卡顿 ----
    private const val AUTH_CACHE_MS = 60_000L
    /** 缓存密码的 SHA-256 摘要（而非明文），防内存 dump 泄露。 */
    @Volatile private var lastAuthDigest: String? = null
    @Volatile private var lastAuthTime = 0L

    private fun sha256Hex(input: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(input.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    /**
     * 2026-11-16：**"解锁/查看"一律走这里**。
     *
     * v5 是门闩式：没有密文，密码只是 verifier → verifyLockPassword。
     * v3 才有真密钥 → verifyPassword。
     * 之前各屏直接调 verifyPassword，v5 下会永远判"密码错误"，
     * 于是"查看锁定记录 / 解锁 / 删除前解密"整条路都是死的。
     */
    suspend fun verifyUnlockPassword(password: String): Boolean =
        if (isV5()) verifyLockPassword(password) else verifyPassword(password)

    /** 带 60 秒缓存的版本（v5 直接验 verifier，本来就不贵）。 */
    suspend fun verifyUnlockPasswordCached(password: String): Boolean =
        if (isV5()) verifyLockPassword(password) else verifyPasswordCached(password)

    suspend fun verifyPasswordCached(password: String): Boolean {
        val now = System.currentTimeMillis()
        val digest = sha256Hex(password)
        if (digest == lastAuthDigest && now - lastAuthTime < AUTH_CACHE_MS) {
            return true
        }
        val ok = verifyPassword(password)
        if (ok) { lastAuthDigest = digest; lastAuthTime = now }
        else { lastAuthDigest = null; lastAuthTime = 0L }
        return ok
    }

    /**
     * 改密码。
     *
     * **v3 起这里不再重加密任何内容**：先取出主密钥，再用新密码/新答案重新包裹，**一次原子写配置**。
     * - v3 配置 → 解开 `wrappedKeyPw` 拿回主密钥；
     * - 尚是 v1/v2 的存量配置 → 把"当前的密码派生密钥"直接**收编**为主密钥
     *   （内容本来就是用它加的密 → **一个字节都不用动**）。
     *
     * 于是：不慢、无中间态、不可能丢数据 —— 旧实现"先写配置再重加密"的致命顺序问题从此不存在，
     * 连"重加密到一半被杀"那条残余的部分丢失窗口也一并消失。
     */
    suspend fun changePassword(
        oldPassword: String, newPassword: String, newQuestion: String, newAnswer: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val config = d().get() ?: return@withContext Result.failure(Exception("未设置加密密码"))
        if (CryptoUtil.deriveKeyHex(oldPassword, CryptoUtil.saltToBytes(config.passwordSalt, config.encVersion), config.iterations) != config.passwordHash)
            return@withContext Result.failure(Exception("旧密码错误"))
        val master = try {
            if (isV3(config)) unwrapKey(config.wrappedKeyPw, kekFromPassword(oldPassword, config.keySalt, config.iterations))
            // 存量 v1/v2：内容密钥就是「旧密码 + 旧 keySalt」的派生值 → 收编为主密钥
            else deriveKeyInternal(oldPassword, CryptoUtil.saltToBytes(config.keySalt, config.encVersion), config.iterations)
        } catch (e: Exception) {
            return@withContext Result.failure(Exception("无法取回主密钥：${e.message ?: "加密配置损坏"}"))
        }
        val it = NEW_PASSWORD_ITERATIONS
        val newPwSalt = CryptoUtil.randomSaltB64(); val newPwHash = CryptoUtil.deriveKeyHex(newPassword, CryptoUtil.saltToBytes(newPwSalt, ENC_VERSION), it)
        // keySalt 同时也是 KEK 的盐：改密码时**必须换新的** —— 这才是"彻底断掉旧派生路径"的时机。
        // （主密钥此刻已在手上，所以换 salt 不影响任何内容。）
        val newKeySalt = CryptoUtil.randomSaltB64()
        val newQSalt = CryptoUtil.randomSaltB64(); val newQHash = CryptoUtil.deriveKeyHex(newQuestion, CryptoUtil.saltToBytes(newQSalt, ENC_VERSION), it)
        val newASalt = CryptoUtil.randomSaltB64(); val newAHash = CryptoUtil.deriveKeyHex(newAnswer, CryptoUtil.saltToBytes(newASalt, ENC_VERSION), it)

        d().upsert(config.copy(iterations = it, encVersion = ENC_VERSION,
            passwordSalt = newPwSalt, passwordHash = newPwHash, keySalt = newKeySalt,
            question = newQuestion.trim(), questionHash = newQHash, questionSalt = newQSalt,
            answerSalt = newASalt, answerHash = newAHash,
            wrappedKeyPw = wrapKey(master, kekFromPassword(newPassword, newKeySalt, it)),
            wrappedKey = wrapKey(master, kekFromAnswer(newAnswer, newASalt, it)),
            updatedAt = System.currentTimeMillis()))
        cachedKey = master
        lastAuthDigest = null; lastAuthTime = 0L
        Result.success(Unit)
    }

    /**
     * 用密保答案重置密码。同样**只重写包裹、内容零改动**（理由见 [changePassword]）。
     *
     * 存量 v1/v2 配置下，旧密钥从"旧密保包裹"里用**旧语义的答案密钥**解出，然后收编为主密钥；
     * 同时把那个"明文 `answerHash` 就能解开的旧包裹"替换为 v3 的域分离包裹 —— **这个安全洞就此闭合**。
     */
    suspend fun resetPasswordByAnswer(answer: String, newPassword: String): Result<Unit> = withContext(Dispatchers.IO) {
        val config = d().get() ?: return@withContext Result.failure(Exception("未设置加密密码"))
        if (CryptoUtil.deriveKeyHex(answer, CryptoUtil.saltToBytes(config.answerSalt, config.encVersion), config.iterations) != config.answerHash)
            return@withContext Result.failure(Exception("答案错误"))
        val master = try {
            if (isV3(config)) unwrapKey(config.wrappedKey, kekFromAnswer(answer, config.answerSalt, config.iterations))
            else unwrapKey(config.wrappedKey, deriveKeyFromAnswer(answer, CryptoUtil.saltToBytes(config.answerSalt, config.encVersion), config.iterations))
        } catch (e: Exception) {
            return@withContext Result.failure(Exception("无法还原主密钥：${e.message ?: "密保数据损坏"}"))
        }
        val it = NEW_PASSWORD_ITERATIONS
        val newPwSalt = CryptoUtil.randomSaltB64(); val newPwHash = CryptoUtil.deriveKeyHex(newPassword, CryptoUtil.saltToBytes(newPwSalt, ENC_VERSION), it)
        val newKeySalt = CryptoUtil.randomSaltB64()
        // 密保问题/答案都没变 → 这两个盐原样保留；但 v1 的 hex 盐必须重新编码为 Base64，
        // 否则 encVersion 升到 3 之后 saltToBytes 会按 Base64 解出另一串字节，answerHash 再也对不上。
        val aSaltFinal = upgradeLegacySalt(config.answerSalt, config.encVersion)
        val qSaltFinal = upgradeLegacySalt(config.questionSalt, config.encVersion)
        // ⚠️ iterations 在这里升级到 NEW_PASSWORD_ITERATIONS，而 answerHash 是用**旧轮数**算出来的 →
        // 必须用新轮数重算，否则下次"忘记密码"时答案校验不过（存量 iterations=600000 必踩）。
        // 答案明文此刻就在手上，重算零代价。
        // questionHash 不重算：它**全项目从未被校验**（只随导出/导入搬运），而且 v1 存量配置的
        // questionSalt 可能是空串（DB 迁移默认值 ''），拿空盐去派生会被 PBEKeySpec 直接拒绝。
        val newAHash = CryptoUtil.deriveKeyHex(answer, CryptoUtil.saltToBytes(aSaltFinal, ENC_VERSION), it)

        d().upsert(config.copy(iterations = it, encVersion = ENC_VERSION,
            passwordSalt = newPwSalt, passwordHash = newPwHash, keySalt = newKeySalt,
            questionSalt = qSaltFinal, answerHash = newAHash, answerSalt = aSaltFinal,
            wrappedKeyPw = wrapKey(master, kekFromPassword(newPassword, newKeySalt, it)),
            wrappedKey = wrapKey(master, kekFromAnswer(answer, aSaltFinal, it)),
            updatedAt = System.currentTimeMillis()))
        cachedKey = master
        lastAuthDigest = null; lastAuthTime = 0L
        Result.success(Unit)
    }

    suspend fun getQuestion(): String? = withContext(Dispatchers.IO) {
        try { d().get()?.question } catch (_: Exception) { null }
    }

    suspend fun verifyAnswer(answer: String): Boolean = withContext(Dispatchers.IO) {
        val config = d().get() ?: return@withContext false
        CryptoUtil.deriveKeyHex(answer, CryptoUtil.saltToBytes(config.answerSalt, config.encVersion), config.iterations) == config.answerHash
    }

    // ---- AES-256-GCM 加解密 (纯计算，不需要 IO) ----

    fun encryptContent(content: String, key: SecretKey): EncryptResult {
        val cipher = Cipher.getInstance(AES_TRANSFORMATION)
        val iv = ByteArray(GCM_IV_LENGTH_BYTES); SecureRandom().nextBytes(iv)
        val ivSpec = GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv)
        cipher.init(Cipher.ENCRYPT_MODE, key, ivSpec)
        val ciphertext = cipher.doFinal(content.toByteArray(Charsets.UTF_8))
        val tagStart = ciphertext.size - 16
        return EncryptResult(
            encryptedData = Base64.encodeToString(ciphertext.copyOfRange(0, tagStart), Base64.NO_WRAP),
            iv = Base64.encodeToString(iv, Base64.NO_WRAP),
            authTag = Base64.encodeToString(ciphertext.copyOfRange(tagStart, ciphertext.size), Base64.NO_WRAP)
        )
    }

    fun decryptContent(encryptedData: String, iv: String, authTag: String, key: SecretKey): String {
        val combined = Base64.decode(encryptedData, Base64.NO_WRAP) + Base64.decode(authTag, Base64.NO_WRAP)
        val cipher = Cipher.getInstance(AES_TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH_BITS, Base64.decode(iv, Base64.NO_WRAP)))
        return String(cipher.doFinal(combined), Charsets.UTF_8)
    }

    // ---- 记录内容打包 ----

    fun packRecordPayload(record: Record): String {
        val obj = com.google.gson.JsonObject()
        obj.addProperty("c", record.content); obj.addProperty("n", record.notes)
        obj.addProperty("d", record.dialogueAiContent)
        obj.addProperty("r", record.dialogueAiReply)
        if (record.analysis != null) {
            obj.addProperty("a", memore.app.utils.GsonInstance.gson.toJson(record.analysis))
        }
        obj.addProperty("e", record.emotionalTags)
        return memore.app.utils.GsonInstance.gson.toJson(obj)
    }

    fun packPayload(payload: RecordPayload): String {
        val obj = com.google.gson.JsonObject()
        obj.addProperty("c", payload.content); obj.addProperty("n", payload.notes)
        obj.addProperty("d", payload.dialogueAiContent)
        obj.addProperty("r", payload.dialogueAiReply)
        if (payload.hasAnalysis && payload.analysisJson != null) {
            obj.addProperty("a", payload.analysisJson)
        }
        if (payload.hasEmotionalTags) {
            obj.addProperty("e", payload.emotionalTags)
        }
        return memore.app.utils.GsonInstance.gson.toJson(obj)
    }

    fun unpackRecordPayload(json: String): RecordPayload {
        return try {
            val obj = com.google.gson.JsonParser.parseString(json).asJsonObject
            val hasAnalysis = obj.has("a") && !obj.get("a").isJsonNull
            RecordPayload(
                content = obj.get("c")?.asString ?: "",
                notes = obj.get("n")?.asString ?: "",
                dialogueAiContent = obj.get("d")?.asString ?: "",
                dialogueAiReply = obj.get("r")?.asString ?: "",
                analysisJson = if (hasAnalysis) obj.get("a").asString else null,
                emotionalTags = obj.get("e")?.asString ?: "[]",
                hasAnalysis = hasAnalysis,
                hasEmotionalTags = obj.has("e")
            )
        } catch (_: Exception) { RecordPayload(json, "", "", "", null, "[]", false, false) }
    }

    // ---- 附件文件/文件名加解密 ----

    /** 流式加密文件内容并覆写原文件，返回 iv/auth_tag（GCM tag 是密文末 16 字节）。 */
    fun encryptFileInPlace(file: File, key: SecretKey): AttachmentCryptoResult {
        return withFileLock(file) {
            val cipher = Cipher.getInstance(AES_TRANSFORMATION)
            val iv = ByteArray(GCM_IV_LENGTH_BYTES).also { SecureRandom().nextBytes(it) }
            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
            val tmp = File(file.parentFile, file.name + ".enc.tmp")
            file.inputStream().use { ins ->
                tmp.outputStream().use { rawOut ->
                    CipherOutputStream(rawOut, cipher).use { cos -> ins.copyTo(cos) }
                }
            }
            val tag = RandomAccessFile(tmp, "r").use { raf ->
                val tagLen = GCM_TAG_LENGTH_BITS / 8
                raf.seek((raf.length() - tagLen).coerceAtLeast(0))
                ByteArray(tagLen).also { raf.readFully(it) }
            }
            if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
            AttachmentCryptoResult(
                iv = Base64.encodeToString(iv, Base64.NO_WRAP),
                authTag = Base64.encodeToString(tag, Base64.NO_WRAP)
            )
        }
    }

    /** 解密文件内容并覆写原文件（tag 校验失败抛 AEADBadTagException）。 */
    fun decryptFileInPlace(file: File, iv: String, authTag: String, key: SecretKey) {
        withFileLock(file) {
            val stale = File(file.parentFile, file.name + ".dec.tmp")
            if (stale.exists()) stale.delete()
            val tmp = File(file.parentFile, file.name + ".dec.tmp")
            try {
                // 流式解密：不整读密文进内存；GCM tag 在文件尾部由 CipherInputStream 自动校验
                streamDecryptTo(file, iv, key, tmp)
                if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
            } catch (e: Exception) {
                tmp.delete()
                throw e
            }
        }
    }

    /** 解密到指定目标文件（临时查看用，不改写原密文）。 */
    fun decryptFileTo(source: File, dest: File, iv: String, authTag: String, key: SecretKey) {
        withFileLock(source) {
            dest.parentFile?.mkdirs()
            try {
                streamDecryptTo(source, iv, key, dest)
            } catch (e: Exception) {
                dest.delete()
                throw e
            }
        }
    }

    /** 对同一文件路径串行化加解密操作，防止并发读写损坏数据。 */
    private inline fun <T> withFileLock(file: File, block: () -> T): T {
        val lock = fileLocks.getOrPut(file.absolutePath) { ReentrantLock() }
        return lock.withLock { block() }
    }

    /**
     * 流式解密：GCM 密文联在文件尾部含 16 字节认证 tag，CipherInputStream 读到末尾自动校验。
     * 与旧实现整读再 doFinal 语义等价，但内存只占一个缓冲区。
     */
    private fun streamDecryptTo(file: File, iv: String, key: SecretKey, dest: File) {
        val cipher = Cipher.getInstance(AES_TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH_BITS, Base64.decode(iv, Base64.NO_WRAP)))
        CipherInputStream(file.inputStream(), cipher).use { cis ->
            dest.outputStream().use { out -> cis.copyTo(out) }
        }
    }

    /** 加密附件显示名，返回 JSON {d,i,t}。 */
    fun encryptFileName(name: String, key: SecretKey): String {
        val r = encryptContent(name, key)
        val wrapper = com.google.gson.JsonObject()
        wrapper.addProperty("d", r.encryptedData); wrapper.addProperty("i", r.iv); wrapper.addProperty("t", r.authTag)
        return memore.app.utils.GsonInstance.gson.toJson(wrapper)
    }

    /** 解密附件显示名；失败返回 null（调用方回退到加密前的 token 名）。 */
    fun decryptFileName(encName: String, key: SecretKey): String? {
        return try {
            val wrapper = com.google.gson.JsonParser.parseString(encName).asJsonObject
            decryptContent(wrapper.get("d").asString, wrapper.get("i").asString, wrapper.get("t").asString, key)
        } catch (_: Exception) { null }
    }

    // ---- 内存密钥缓存 ----

    fun setCachedKey(key: SecretKey?) { cachedKey = key }
    fun getCachedKey(): SecretKey? = cachedKey
    fun clearCachedKey() { cachedKey = null }

    suspend fun ensureKey(password: String): Result<SecretKey> = withContext(Dispatchers.IO) {
        cachedKey?.let { return@withContext Result.success(it) }
        val config = d().get() ?: return@withContext Result.failure(Exception("未设置加密密码"))
        if (CryptoUtil.deriveKeyHex(password, CryptoUtil.saltToBytes(config.passwordSalt, config.encVersion), config.iterations) != config.passwordHash)
            return@withContext Result.failure(Exception("密码错误"))
        // v5（2026-11-16）：门闩式没有内容密钥。这里验完密码就返回一把**占位密钥**，
        // 让所有旧调用点（它们只关心"密码对不对 / 能不能解锁"）继续工作。
        // 真正的加解密路径在 v5 下已被 lockRecord/unlockRecord 的 isV5() 分支绕开，
        // 不会拿这把占位密钥去加密任何内容。
        if (config.encVersion == V5_VERSION) {
            val placeholder = javax.crypto.spec.SecretKeySpec(ByteArray(32), "AES")
            cachedKey = placeholder
            return@withContext Result.success(placeholder)
        }
        val key = try {
            // v3：主密钥只能从包裹里取回；v1/v2：仍由密码现算（改密码那一刻才收编成主密钥）
            if (isV3(config)) unwrapKey(config.wrappedKeyPw, kekFromPassword(password, config.keySalt, config.iterations))
            else deriveKeyInternal(password, CryptoUtil.saltToBytes(config.keySalt, config.encVersion), config.iterations)
        } catch (e: Exception) {
            return@withContext Result.failure(Exception("无法取回主密钥：${e.message ?: "加密配置损坏"}"))
        }
        cachedKey = key
        Result.success(key)
    }

    suspend fun deleteConfig() = withContext(Dispatchers.IO) {
        try { d().delete(); passwordConfigured = false; enabled = false } catch (_: Exception) {}
        cachedKey = null
    }

    // ---- v3 主密钥与包裹 ----

    /**
     * 是否已是 v3 语义（主密钥 + 包裹）。
     *
     * v1/v2 的存量配置会在用户**下一次改密码 / 密保重置**时自动收编升级 ——
     * 那两处同时握有旧密码（或答案）与新密码/新答案，可以一次原子写把两个包裹都补齐。
     */
    private fun isV3(config: EncryptionConfig): Boolean = config.encVersion >= ENC_VERSION

    /** 全新的 256 位随机主密钥（仅"首次设密码"用；存量配置走"收编"，不重加密内容）。 */
    private fun randomMasterKey(): SecretKey {
        val bytes = ByteArray(KEY_LENGTH_BITS / 8)
        SecureRandom().nextBytes(bytes)
        return SecretKeySpec(bytes, "AES")
    }

    /** 密码派生的 KEK：**带域分离标签**，确保它与库里明文存的 `passwordHash` 不是同一个派生。 */
    private fun kekFromPassword(password: String, kekSalt: String, iterations: Int): SecretKey =
        CryptoUtil.deriveKey(
            password.toCharArray(),
            CryptoUtil.saltToBytes(kekSalt, ENC_VERSION) + KEK_LABEL,
            iterations, KEY_LENGTH_BITS
        )

    /** 答案派生的 KEK：同样带标签。v2 的洞正是"答案 KEK 与明文 `answerHash` 同派生"，这里一并修掉。 */
    private fun kekFromAnswer(answer: String, answerSalt: String, iterations: Int): SecretKey =
        CryptoUtil.deriveKey(
            answer.toCharArray(),
            CryptoUtil.saltToBytes(answerSalt, ENC_VERSION) + KEK_LABEL,
            iterations, KEY_LENGTH_BITS
        )

    /**
     * 把 v1 的 hex 盐**原字节地**重新编码为 Base64。
     *
     * `saltToBytes(salt, encVersion)` 对 v1 取 `salt.toByteArray()`、对 v2+ 取 Base64 解码。
     * 升级到 v3 后若保留 v1 的 hex 盐字符串，会被按 Base64 解出**另一串字节**，
     * 于是 `passwordHash`/`answerHash` 再也对不上 → 等于把用户锁在门外。本函数保证字节不变。
     */
    private fun upgradeLegacySalt(salt: String, fromVersion: Int): String =
        if (fromVersion >= 2 || salt.isEmpty()) salt
        else Base64.encodeToString(salt.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)

    // ---- 密钥包裹 ----

    private fun deriveKeyFromAnswer(answer: String, answerSalt: ByteArray, iterations: Int): SecretKey {
        return CryptoUtil.deriveKey(answer.toCharArray(), answerSalt, iterations, KEY_LENGTH_BITS)
    }

    private fun wrapKey(key: SecretKey, wrappingKey: SecretKey): String {
        val result = encryptContent(Base64.encodeToString(key.encoded, Base64.NO_WRAP), wrappingKey)
        val wrapper = com.google.gson.JsonObject()
        wrapper.addProperty("d", result.encryptedData); wrapper.addProperty("i", result.iv); wrapper.addProperty("t", result.authTag)
        return memore.app.utils.GsonInstance.gson.toJson(wrapper)
    }

    private fun unwrapKey(wrappedData: String, wrappingKey: SecretKey): SecretKey {
        val wrapper = com.google.gson.JsonParser.parseString(wrappedData).asJsonObject
        val keyBase64 = decryptContent(wrapper.get("d").asString, wrapper.get("i").asString, wrapper.get("t").asString, wrappingKey)
        return SecretKeySpec(Base64.decode(keyBase64, Base64.NO_WRAP), "AES")
    }

    // ---- 内部工具 ----

    private fun deriveKeyInternal(password: String, keySalt: ByteArray, iterations: Int): SecretKey {
        return CryptoUtil.deriveKey(password.toCharArray(), keySalt, iterations, KEY_LENGTH_BITS)
    }
}
