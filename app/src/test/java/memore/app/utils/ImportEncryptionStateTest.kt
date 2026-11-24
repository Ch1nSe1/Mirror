package memore.app.utils

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import memore.app.data.local.AppDatabase
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

/**
 * 真跑一遍导入流程，钉住两件在真机上出过事的事：
 *
 * **① 导入加密配置后必须刷新内存状态。**
 * `hasPassword()` 读的是内存缓存 `passwordConfigured`，只在 App `onCreate` 刷过一次；
 * 而导入是在**运行时**写配置的。不刷新 → 设置页认定"本机没有密码" → 给用户看「设置密码」→
 * 提交时守卫查真库发现确实有配置 → 拒绝，而唯一正确的「加密管理 → 修改密码」够不着（死路）。
 *
 * **② 老包缺少后加的键时，加密配置不能整块导入失败。**
 * Gson 用 Unsafe 绕过 Kotlin 构造器，JSON 里缺键时字段是 `null`（哪怕声明成非空 `String`）。
 * `question_salt` 是后加的字段，早期导出的包没有它 → 传给非空参数当场 NPE → 异常被 catch 吞掉 →
 * **配置没导进来且悄无声息** → 新设备上"有密文却没有配置"。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ImportEncryptionStateTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var tempFile: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        EncryptionManager.init(db.encryptionConfigDao())
        EncryptionManager.clearCachedKey()
        // 模拟 App 启动那一刻：库里还没有配置 → 缓存 = 未配置
        runBlocking { EncryptionManager.refreshConfigStatus() }
        tempFile = File.createTempFile("memore-import-test", ".json")
    }

    @After
    fun tearDown() {
        db.close()
        tempFile.delete()
    }

    /** 老包形状：加密配置块里**没有** `question_salt` 键（该字段是后来才加的）。 */
    @Test
    fun importLegacyPackageWithoutQuestionSalt_stillImportsEncryptionConfig() = runBlocking {
        assertFalse("前置条件：库里还没有配置", EncryptionManager.hasPassword())
        tempFile.writeText(packageJson(includeQuestionSalt = false))

        val result = runImport()
        assertTrue("导入应当成功：${result.exceptionOrNull()?.message}", result.isSuccess)

        // 这一条是老包回归守卫：缺 question_salt 时若不做空值兜底，配置会整块导入失败
        assertEquals("老包（缺 question_salt）的配置也必须落库", 1, db.encryptionConfigDao().get()?.encVersion)

        // ★ 核心断言：导入在运行时写了配置，内存状态必须同步刷新
        assertTrue(
            "导入加密配置后 hasPassword() 必须立刻为 true —— 否则新设备上会走进「设置密码」死路",
            EncryptionManager.hasPassword()
        )
    }

    /** 正常包形状：加密配置块齐全（含 `question_salt`、含 v3 的主密钥包裹）。 */
    @Test
    fun importCompletePackage_importsConfigAndRefreshesState() = runBlocking {
        assertFalse(EncryptionManager.hasPassword())
        tempFile.writeText(packageJson(includeQuestionSalt = true, wrappedKeyPw = "WK-PW", encVersion = 3))

        val result = runImport()
        assertTrue("导入应当成功：${result.exceptionOrNull()?.message}", result.isSuccess)

        val cfg = db.encryptionConfigDao().get()
        assertEquals(3, cfg?.encVersion)
        assertEquals("v3 的主密钥包裹必须随包还原", "WK-PW", cfg?.wrappedKeyPw)
        assertTrue(EncryptionManager.hasPassword())
    }

    /**
     * 缺键容错：记录里**只有 `id`**（`type`/`content`/`tags`/`timestamp`/`starred`/`deleted` 全缺）
     * 也必须能导进来。
     *
     * Gson 绕过构造器，缺键的字段是 null 而**不是** Kotlin 默认值 —— 若不兜底，
     * 记录循环的 catch 会**回滚全部并报"导入失败"**（不丢数据，但白折腾一趟）。
     */
    @Test
    fun importPackageWithMinimalRecord_stillSucceeds() = runBlocking {
        tempFile.writeText(packageJson(includeQuestionSalt = true, minimalRecord = true))

        val result = runImport()
        assertTrue("缺键不该让整包失败：${result.exceptionOrNull()?.message}", result.isSuccess)
        assertEquals(1, result.getOrThrow().imported)
        assertNotNull("只有 id 的记录也要真的落库", db.recordDao().getRecordById("test-record-1"))
    }

    /** 先单独验证测试底座本身（内存库 + 这个 dao 能读能写），避免把"底座坏了"误判成"被测代码坏了"。 */
    @Test
    fun harnessSanity_encryptionConfigDaoCanRoundTrip() = runBlocking {
        db.encryptionConfigDao().upsert(
            memore.app.data.model.EncryptionConfig(
                enabled = true, iterations = 1000, encVersion = 1,
                passwordSalt = "ps", passwordHash = "ph", keySalt = "ks",
                question = "q", questionHash = "qh",
                answerSalt = "as", answerHash = "ah", wrappedKey = "wk"
            )
        )
        val back = db.encryptionConfigDao().get()
        assertNotNull("诊断：加密配置 dao 本身应能读回写入的行", back)
        assertEquals(1, back?.encVersion)
    }

    // ---- 辅助 ----

    private suspend fun runImport(): Result<ImportResult> {
        val recordRepo = RecordRepository(
            recordDao = db.recordDao(),
            versionDao = db.versionDao(),
            attachmentDao = db.attachmentDao(),
            taskDao = db.aiTaskDao(),
            db = db
        )
        return ImportEngine.execute(
            context = context,
            uri = Uri.fromFile(tempFile),
            recordRepo = recordRepo,
            tagRepo = TagRepository(db.tagDao()),
            encryptionConfigDao = db.encryptionConfigDao()
        )
    }

    /**
     * 最小可导入包：**必须至少有一条记录** —— `ImportEngine.execute` 在"文件中没有记录"时会提前返回，
     * 而加密配置块的导入排在记录循环之后，空包根本走不到那里。
     */
    private fun packageJson(
        includeQuestionSalt: Boolean,
        wrappedKeyPw: String? = null,
        encVersion: Int = 1,
        minimalRecord: Boolean = false
    ): String {
        // 极简记录：只留 id，其余键全缺 —— 用来验证"Gson 缺键留 null"不会让整包失败回滚
        val record = if (minimalRecord) "{\"id\": \"test-record-1\"}"
        else """
            {
              "id": "test-record-1",
              "type": "ESSAY",
              "content": "测试记录",
              "tags": [],
              "timestamp": "2026-01-01T00:00:00Z",
              "starred": false,
              "deleted": false
            }
        """.trimIndent()
        val questionSalt = if (includeQuestionSalt) ",\n            \"question_salt\": \"qs\"" else ""
        val wrappedPw = if (wrappedKeyPw != null) ",\n            \"wrapped_key_pw\": \"$wrappedKeyPw\"" else ""
        return """
        {
          "version": "2.5",
          "export_time": "2026-01-01T00:00:00Z",
          "import_enabled": true,
          "count": 1,
          "records": [
            $record
          ],
          "encryption_config": {
            "algorithm": "PBKDF2WithHmacSHA256",
            "key_iterations": 1000,
            "cipher": "AES-256-GCM",
            "password_hash": "aa",
            "password_salt": "test-salt",
            "key_salt": "test-key-salt",
            "question": "q",
            "question_hash": "bb",
            "answer_hash": "cc",
            "answer_salt": "0123456789abcdef0123456789abcdef",
            "wrapped_key": "",
            "enc_version": $encVersion$questionSalt$wrappedPw
          }
        }
        """.trimIndent()
    }
}
