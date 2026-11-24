package memore.app.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import memore.app.data.local.AppDatabase
import memore.app.data.model.Record
import memore.app.data.model.RecordType
import memore.app.data.model.Zone
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 区域里的记录顺序（2026-11-19 修的 bug）。
 *
 * 现象：进入自定义区域，第一条不是最新的那条，而是当初第一个被加进区域的成员。
 * 原因：成员分支直接按 `zone_records` 的插入顺序返回，绕过了按时间排序。
 *
 * 现在钉住：不管走哪个分支，返回列表都按 createdAt 倒序。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ZoneOrderingTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: ZoneRepository

    @Before
    fun setUp() {
        val ctx: Context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).allowMainThreadQueries().build()
        repo = ZoneRepository(db)
    }

    @After
    fun tearDown() = db.close()

    private suspend fun seed(id: String, at: Long) {
        db.recordDao().insertRecord(
            Record(id = id, type = RecordType.ESSAY, content = "内容$id", createdAt = at, updatedAt = at)
        )
    }

    @Test
    fun `手动区域按时间倒序而不是成员加入顺序`() = runBlocking {
        seed("old", 1_000L)
        seed("mid", 2_000L)
        seed("new", 3_000L)

        val zone = repo.create("创作", ZoneCondition(manual = true))
        // 故意按"旧→新"的顺序加成员：旧实现会原样返回，第一条是 old
        repo.addToZone(zone.id, "old")
        repo.addToZone(zone.id, "mid")
        repo.addToZone(zone.id, "new")

        val ids = repo.recordsFor(zone).map { it.id }
        assertEquals(listOf("new", "mid", "old"), ids)
    }

    @Test
    fun `移出区域后不再出现在列表里`() = runBlocking {
        seed("a", 1_000L)
        seed("b", 2_000L)
        val zone = repo.create("日常", ZoneCondition(manual = true))
        repo.addToZone(zone.id, "a")
        repo.addToZone(zone.id, "b")
        assertEquals(listOf("b", "a"), repo.recordsFor(zone).map { it.id })

        repo.removeFromZone(zone.id, "b")
        assertEquals(listOf("a"), repo.recordsFor(zone).map { it.id })
    }
}
