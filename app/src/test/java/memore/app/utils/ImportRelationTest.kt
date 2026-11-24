package memore.app.utils

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import memore.app.data.local.AppDatabase
import memore.app.data.model.RecordRelationType
import memore.app.data.repository.RecordRepository
import memore.app.data.repository.TagRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * 关系必须能随备份**往返**（不然整体备份会静默丢关系 —— 就是刚修过两次的那类坑）。
 *
 * 导出格式是**内联**的：一条边同时出现在两端的记录里（A 的 `relations_out` 与 B 的 `relations_in`）。
 * 所以导入端必须：
 *  ① 建出这条边；② 同一条边出现两次（两端各一次）**只留一条**；③ 端点不存在的边**跳过**（不留悬空边）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ImportRelationTest {

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
        tempFile = File.createTempFile("memore-relation-import", ".json")
    }

    @After
    fun tearDown() {
        db.close()
        tempFile.delete()
    }

    /** 内联格式：B 的 out 与 A 的 in 是**同一条边**，导入后只能有一条。 */
    @Test
    fun inlineRelations_roundTripIntoOneEdge() = runBlocking {
        tempFile.writeText(
            packageJson(
                """{"id":"a","content":"老的","relations_in":[{"from_id":"b","type":"CORRECT"}]}""",
                """{"id":"b","content":"新的","relations_out":[{"to_id":"a","type":"CORRECT"}]}"""
            )
        )

        val result = runImport()
        assertTrue("导入应当成功：${result.exceptionOrNull()?.message}", result.isSuccess)

        val rels = db.recordRelationDao().all()
        assertEquals("两端各写一次，但只该有一条边", 1, rels.size)
        assertEquals("b", rels[0].fromId)
        assertEquals("a", rels[0].toId)
        assertEquals(RecordRelationType.CORRECT, rels[0].type)

        // 反查也对得上：b 更正了 a
        assertEquals(1, newRepo().relationsFrom("b").size)
        assertEquals(1, newRepo().relationsTo("a").size)
    }

    /** 端点不在包里的边必须跳过 —— 不能留下指向不存在记录的悬空边。 */
    @Test
    fun danglingRelation_isSkipped() = runBlocking {
        tempFile.writeText(
            packageJson(
                """{"id":"a","content":"就一条","relations_out":[{"to_id":"ghost","type":"CITE"}]}"""
            )
        )

        val result = runImport()
        assertTrue(result.isSuccess)
        assertEquals("端点不存在 → 这条边不该建", 0, db.recordRelationDao().count())
    }

    // ---- 辅助 ----

    private suspend fun runImport(): Result<ImportResult> {
        return ImportEngine.execute(
            context = context,
            uri = Uri.fromFile(tempFile),
            recordRepo = newRepo(),
            tagRepo = TagRepository(db.tagDao()),
            encryptionConfigDao = db.encryptionConfigDao()
        )
    }

    private fun newRepo(): RecordRepository {
        return RecordRepository(
            recordDao = db.recordDao(),
            versionDao = db.versionDao(),
            attachmentDao = db.attachmentDao(),
            taskDao = db.aiTaskDao(),
            db = db
        )
    }

    /** 最小包：每条记录单独传进来（嵌套对象原样保留，不做字符串切割）。 */
    private fun packageJson(vararg records: String) = """
        {
          "version": "2.5",
          "export_time": "2026-01-01T00:00:00Z",
          "import_enabled": true,
          "count": ${records.size},
          "records": [
            ${records.joinToString(",\n            ")}
          ]
        }
    """.trimIndent()
}
