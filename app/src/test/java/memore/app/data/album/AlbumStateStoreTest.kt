package memore.app.data.album

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AlbumStateStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun store(): AlbumStateStore = AlbumStateStore(File(tmp.root, "album_state.json"))

    @Test
    fun concurrentSetSelectedKeepsAllEntries() = runBlocking {
        val s = store()
        (1..20).map { i ->
            async { s.setSelected("record-$i", i) }
        }.awaitAll()
        val state = s.load()
        assertEquals(20, state.selected.size)
        assertEquals(20, state.selected["record-20"])
        assertEquals(3, state.selected["record-3"])
    }

    @Test
    fun footnoteLifecycle() = runBlocking {
        val s = store()
        val added = s.addFootnote("r1", "第一条")
        val entryId = added.footnotes.getValue("r1").single().createdAt
        assertEquals("第一条", added.footnotes.getValue("r1").single().content)

        val updated = s.updateFootnote("r1", entryId, "改过的")
        assertEquals("改过的", updated.footnotes.getValue("r1").single().content)
        assertTrue(updated.footnotes.getValue("r1").single().updatedAt != null)

        val deleted = s.deleteFootnote("r1", entryId)
        assertTrue(deleted.footnotes["r1"].isNullOrEmpty())
        val reloaded = s.load()
        assertTrue(reloaded.footnotes["r1"].isNullOrEmpty())
    }

    @Test
    fun corruptFileIsBackedUpAndLoadsEmpty() = runBlocking {
        val file = File(tmp.root, "album_state.json")
        file.writeText("{broken json")
        val s = AlbumStateStore(file)
        val state = s.load()
        assertTrue(state.selected.isEmpty())
        assertTrue(state.footnotes.isEmpty())
        val backups = tmp.root.listFiles { f -> f.name.startsWith("album_state.json.bak-") }
        assertTrue("损坏文件应被备份", backups != null && backups.isNotEmpty())
    }

    @Test
    fun blankFileLoadsEmpty() = runBlocking {
        val file = File(tmp.root, "album_state.json")
        file.writeText("")
        val state = AlbumStateStore(file).load()
        assertTrue(state.selected.isEmpty())
    }

    @Test
    fun importFootnotesMergesAndDeduplicates() = runBlocking {
        val s = store()
        val imported = mapOf(
            "r1" to listOf(
                AlbumFootnote(content = "注脚一", createdAt = 111L),
                AlbumFootnote(content = "注脚二", createdAt = 222L, updatedAt = 333L)
            )
        )
        s.importFootnotes(imported)
        // 重复导入同一备份：createdAt 相同应去重，不产生重复条目
        val next = s.importFootnotes(imported)
        val r1 = next.footnotes.getValue("r1")
        assertEquals(2, r1.size)
        assertEquals(listOf("注脚一", "注脚二"), r1.map { it.content })
        assertEquals(333L, r1.first { it.content == "注脚二" }.updatedAt)
        // 合并到其他记录
        val merged = s.importFootnotes(mapOf("r2" to listOf(AlbumFootnote(content = "新", createdAt = 444L))))
        assertEquals(2, merged.footnotes.getValue("r1").size)
        assertEquals(1, merged.footnotes.getValue("r2").size)
        val reloaded = s.load()
        assertEquals(2, reloaded.footnotes.getValue("r1").size)
    }

    @Test
    fun atomicWriteLeavesNoTempFile() = runBlocking {
        val s = store()
        s.addFootnote("r1", "内容")
        assertFalse(File(tmp.root, "album_state.json.tmp").exists())
        assertTrue(File(tmp.root, "album_state.json").exists())
    }
}
