package memore.app.utils

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportImportCompatTest {

    private val gson = Gson()

    @Test
    fun albumFootnotesRoundTripThroughJson() {
        val record = ExportRecord(
            id = "r1",
            type = "ESSAY",
            content = "正文",
            tags = emptyList(),
            timestamp = "2026-01-01T00:00:00Z",
            starred = false,
            deleted = false,
            album_footnotes = listOf(
                ExportedAlbumFootnote(content = "注脚一", created_at = 111L),
                ExportedAlbumFootnote(content = "注脚二", created_at = 222L, updated_at = 333L)
            )
        )
        val json = ExportEngine.buildOrderedJson(
            ExportData(
                version = "2.3",
                export_time = "2026-01-01T00:00:00Z",
                count = 1,
                records = listOf(record)
            )
        )
        assertTrue(json.contains("album_footnotes"))
        assertTrue(json.contains("注脚一"))
        val parsed = gson.fromJson(json, ImportEngine.ImportData::class.java)
        val restored = parsed.records.single().album_footnotes
        assertNotNull(restored)
        assertEquals(2, restored!!.size)
        assertEquals("注脚一", restored[0].content)
        assertEquals(111L, restored[0].created_at)
        assertEquals(333L, restored[1].updated_at)
    }

    @Test
    fun disabledBothOmitsKeys() {
        val json = ExportEngine.buildOrderedJson(
            ExportData(
                version = "2.3",
                export_time = "2026-01-01T00:00:00Z",
                count = 0,
                records = emptyList()
            )
        )
        // 概念网络（含图谱可视化 / 候选边）2026-10-11 已删除：这三个键不该再出现
        assertFalse(json.contains("concept_network"))
        assertFalse(json.contains("graph_visualization"))
        assertFalse(json.contains("candidate_edges"))
    }

    /**
     * 本地音乐（audio_items 清单 + music/ 文件 + 播放器）2026-10-12 整体删除：
     * 出包里不该再有任何音乐相关的键。
     */
    @Test
    fun audioItemsNeverExported() {
        val json = ExportEngine.buildOrderedJson(
            ExportData(
                version = "2.5",
                export_time = "2026-01-01T00:00:00Z",
                count = 0,
                records = emptyList()
            )
        )
        assertFalse(json.contains("audio_items"))
        assertFalse(json.contains("music"))
    }

    /**
     * 表盘（一段时期的框）随包走：`dials[]` 带名字 / 起止 / 总结 / 包着的刻度 id / 定格时间；
     * 导入端能把它读回来（多父关系写在表盘这边，不在 ticks 里）。
     */
    @Test
    fun dialsRoundTripThroughJson() {
        val dials = listOf(
            ExportedDial(
                name = "今年这十一个月",
                start = "2026-01-01T00:00:00Z",
                end = null,
                summary = "这一段太重要了",
                tick_ids = listOf("t1", "t2")
            )
        )
        val json = ExportEngine.buildOrderedJson(
            ExportData(
                version = "2.5",
                export_time = "2026-01-01T00:00:00Z",
                count = 0,
                records = emptyList(),
                dials = dials
            )
        )
        assertTrue(json.contains("\"dials\""))
        assertTrue(json.contains("tick_ids"))

        val restored = gson.fromJson(json, ImportEngine.ImportData::class.java).dials
        assertNotNull(restored)
        assertEquals(1, restored!!.size)
        assertEquals("今年这十一个月", restored[0].name)
        assertNull("进行中的框没有终点", restored[0].end)
        assertEquals("这一段太重要了", restored[0].summary)
        assertEquals(listOf("t1", "t2"), restored[0].tick_ids)
    }

    @Test
    fun dialsOmittedWhenNone() {
        val json = ExportEngine.buildOrderedJson(
            ExportData(
                version = "2.5",
                export_time = "2026-01-01T00:00:00Z",
                count = 0,
                records = emptyList()
            )
        )
        assertFalse("没有表盘就不写这个键（老包字节不变）", json.contains("\"dials\""))
    }
}
