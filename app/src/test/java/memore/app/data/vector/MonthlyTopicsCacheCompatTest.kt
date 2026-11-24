package memore.app.data.vector

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import memore.app.data.local.AppDatabase
import memore.app.data.repository.RecordRepository
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * ② 的落地端：月度主题缓存多了 `summary` / `keywords` 两个字段。
 *
 * 钉住两件事：
 * ① 新缓存写出来能读回代表句与关键词；
 * ② **老缓存（没有这两个字段）必须照常读**——升级时用户手里的文件还是旧格式，
 *    读不出来就等于主题页被打空。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MonthlyTopicsCacheCompatTest {

    private lateinit var ctx: Context
    private lateinit var db: AppDatabase
    private lateinit var svc: MonthlyTopicsService
    private lateinit var cacheFile: File

    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).allowMainThreadQueries().build()
        val repo = RecordRepository(
            recordDao = db.recordDao(),
            versionDao = db.versionDao(),
            attachmentDao = db.attachmentDao(),
            taskDao = db.aiTaskDao(),
            db = db
        )
        svc = MonthlyTopicsService(ctx, repo, db)
        cacheFile = File(ctx.filesDir, "monthly_topics.json")
        cacheFile.delete()
    }

    @After
    fun tearDown() {
        cacheFile.delete()
        db.close()
    }

    @Test
    fun `新格式缓存能读回代表句和关键词`() {
        cacheFile.writeText(
            """
            {"fingerprint":"fp","months":[
              {"month":"2026-07","clusters":[
                {"name":"关系结构动力","count":2,"ids":["r1","r2"],
                 "summary":"我发现我总在关系里先把对方推远。","keywords":"关系/依附"}
              ]}
            ]}
            """.trimIndent()
        )
        val snap = svc.cached()
        assertNotNull(snap)
        val cluster = snap!!.months[0].clusters[0]
        assertEquals("关系结构动力", cluster.name)
        assertEquals("我发现我总在关系里先把对方推远。", cluster.summary)
        assertEquals("关系/依附", cluster.keywords)
    }

    @Test
    fun `老格式缓存照常读且新字段为空`() {
        cacheFile.writeText(
            """
            {"fingerprint":"old","months":[
              {"month":"2026-07","clusters":[{"name":"关系结构动力","count":2,"ids":["r1","r2"]}]}
            ]}
            """.trimIndent()
        )
        val snap = svc.cached()
        assertNotNull("老缓存必须还能读", snap)
        val cluster = snap!!.months[0].clusters[0]
        assertEquals("关系结构动力", cluster.name)
        assertTrue(cluster.summary.isEmpty())
        assertTrue(cluster.keywords.isEmpty())
    }

    @Test
    fun `老缓存里的同月重名在读取时被合并`() {
        cacheFile.writeText(
            """
            {"fingerprint":"dup","months":[
              {"month":"2026-10","clusters":[
                {"name":"自我迭代","count":3,"ids":["r1","r2","r3"]},
                {"name":"阅读与解构","count":2,"ids":["r9"]},
                {"name":"自我迭代","count":2,"ids":["r3","r4"]}
              ]}
            ]}
            """.trimIndent()
        )
        val month = svc.cached()!!.months[0]
        assertEquals("重名合并后两条", 2, month.clusters.size)
        val self = month.clusters.first { it.name == "自我迭代" }
        assertEquals(5, self.count)
        assertEquals(listOf("r1", "r2", "r3", "r4"), self.recordIds)
    }
}
