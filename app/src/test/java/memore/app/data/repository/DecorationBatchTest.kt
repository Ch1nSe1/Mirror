package memore.app.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import memore.app.data.local.AppDatabase
import memore.app.data.model.Record
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
 * 批量补装饰（2026-11-19 修的 bug 的后端一半）。
 *
 * 现象：筛选/搜索时，靠下的记录不显示标签/协议——因为它们是全库查询出来的，
 * 不在时间线分页的联合查询里，装饰 map 里查不到。
 *
 * 这里钉住批量查询本身：按记录分组、没有记录的不出现在结果里、空入参返回空。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DecorationBatchTest {

    private lateinit var db: AppDatabase
    private lateinit var tagRepo: TagRepository
    private lateinit var protocolRepo: ProtocolRepository

    @Before
    fun setUp() {
        val ctx: Context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).allowMainThreadQueries().build()
        tagRepo = TagRepository(db.tagDao())
        protocolRepo = ProtocolRepository(db.protocolDao())
    }

    @After
    fun tearDown() = db.close()

    private suspend fun seedRecord(id: String) {
        db.recordDao().insertRecord(
            Record(id = id, type = RecordType.ESSAY, content = "内容$id", createdAt = 1L, updatedAt = 1L)
        )
    }

    @Test
    fun `批量取标签按记录分组`() = runBlocking {
        seedRecord("r1"); seedRecord("r2")
        val a = tagRepo.getOrCreateTag("关系")
        val b = tagRepo.getOrCreateTag("工作")
        tagRepo.addTagToRecord("r1", a.id)
        tagRepo.addTagToRecord("r1", b.id)
        tagRepo.addTagToRecord("r2", b.id)

        val map = tagRepo.getTagsForRecords(listOf("r1", "r2"))
        assertEquals(listOf("关系", "工作").sorted(), map.getValue("r1").map { it.name }.sorted())
        assertEquals(listOf("工作"), map.getValue("r2").map { it.name })
    }

    @Test
    fun `批量取协议按记录分组`() = runBlocking {
        seedRecord("r1"); seedRecord("r2")
        val p = protocolRepo.getOrCreateProtocol("日记体")
        protocolRepo.addProtocolToRecord("r1", p.id)

        val map = protocolRepo.getProtocolsForRecords(listOf("r1", "r2"))
        assertEquals(listOf("日记体"), map.getValue("r1").map { it.name })
        assertTrue("r2 没有协议，不该出现在映射里", "r2" !in map)
    }

    @Test
    fun `空入参返回空`() = runBlocking {
        assertTrue(tagRepo.getTagsForRecords(emptyList()).isEmpty())
        assertTrue(protocolRepo.getProtocolsForRecords(emptyList()).isEmpty())
    }
}
