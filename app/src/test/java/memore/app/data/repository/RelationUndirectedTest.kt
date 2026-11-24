package memore.app.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import memore.app.data.local.AppDatabase
import memore.app.data.model.Record
import memore.app.data.model.RecordRelation
import memore.app.data.model.RecordType
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 关系按无向处理（2026-11-21，照 LightRAG：`Treat all relationships as undirected`）。
 *
 * 钉住：
 * ① 同一对记录 A→B、B→A 同时存在时，关联页只出现一条；
 * ② 保留的是带 why/引文的那条（不是随便留一条）；
 * ③ 单边数据不受影响。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RelationUndirectedTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: RecordRepository

    @Before
    fun setUp() {
        val ctx: Context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).allowMainThreadQueries().build()
        repo = RecordRepository(
            recordDao = db.recordDao(),
            versionDao = db.versionDao(),
            attachmentDao = db.attachmentDao(),
            taskDao = db.aiTaskDao(),
            db = db
        )
    }

    @After
    fun tearDown() = db.close()

    private suspend fun seed(id: String) {
        db.recordDao().insertRecord(
            Record(id = id, type = RecordType.ESSAY, content = "内容$id", createdAt = 1_000L, updatedAt = 1_000L)
        )
    }

    private suspend fun edge(from: String, to: String, why: String = "", source: String = "AI") {
        db.recordRelationDao().insert(
            RecordRelation(fromId = from, toId = to, type = "LINK", why = why, source = source)
        )
    }

    @Test
    fun `双向边只出一条且保留有 why 的`() = runBlocking {
        seed("a"); seed("b")
        edge("a", "b", why = "")              // 先写无理由的
        edge("b", "a", why = "这条有理由")     // 反向有理由

        val links = repo.allLinksWithRecords()
        assertEquals("同一对只该出一条", 1, links.size)
        assertEquals("要留有 why 的那条", "这条有理由", links[0].why)
    }

    @Test
    fun `单边数据不受影响`() = runBlocking {
        seed("a"); seed("b"); seed("c")
        edge("a", "b", why = "x")
        edge("b", "c", why = "y")

        assertEquals(2, repo.allLinksWithRecords().size)
    }

    @Test
    fun `反向边存在时不再新建`() = runBlocking {
        seed("a"); seed("b")
        edge("a", "b", why = "已有")
        assertEquals(1, db.recordRelationDao().reverseActiveCount("b", "a"))
        assertEquals(0, db.recordRelationDao().reverseActiveCount("a", "b"))
    }

    @Test
    fun `同方向重复不会多出一条`() = runBlocking {
        seed("a"); seed("b")
        edge("a", "b", why = "")
        edge("a", "b", why = "第二次")
        assertTrue("同方向由唯一索引兜底", repo.allLinksWithRecords().size <= 1)
    }
}
