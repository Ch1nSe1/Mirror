package memore.app.utils

import com.google.gson.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 整体备份（ZIP · 可整机还原）字段契约测试。
 *
 * 两条红线：
 * 1. 整体备份打开时，7 个新块必须都写出来（否则"可整机还原"是假的）；
 * 2. 整体备份关闭时，新块一个都不许出现 —— 纯 JSON 与普通 ZIP 的输出必须与旧版逐字节一致。
 */
class FullBackupExportTest {

    private fun baseData(
        backupMeta: BackupMeta? = null,
        versions: List<ExportedVersion>? = null,
        trash: List<ExportRecord>? = null,
        albumState: ExportedAlbumState? = null,
        sounds: Map<String, List<String>>? = null,
        settings: Map<String, Map<String, PrefEntry>>? = null,
        attachmentsIndex: Map<String, List<ExportedAttachment>>? = null,
        records: List<ExportRecord> = emptyList()
    ) = ExportData(
        version = "2.5",
        export_time = "2026-01-01T00:00:00Z",
        count = records.size,
        records = records,
        backup_meta = backupMeta,
        versions = versions,
        trash = trash,
        album_state = albumState,
        sounds = sounds,
        settings = settings,
        attachments_index = attachmentsIndex
    )

    private val newBlockKeys = listOf(
        "backup_meta", "versions", "trash", "album_state",
        "sounds", "settings", "attachments_index"
    )

    private fun fullData() = baseData(
        backupMeta = BackupMeta(
            kind = "full",
            app_version = "1.2.0",
            db_version = 31,
            includes = listOf("records", "settings"),
            record_count = 1,
            trash_count = 1,
            version_count = 2,
            created_at = "2026-01-01T00:00:00Z"
        ),
        versions = listOf(
            ExportedVersion(record_id = "r1", content = "旧正文", created_at = 111L),
            ExportedVersion(record_id = "r1", content = "更旧的正文", created_at = 100L)
        ),
        trash = listOf(
            ExportRecord(
                id = "r9", type = "ESSAY", content = "误删的正文", tags = emptyList(),
                timestamp = "2025-12-01T00:00:00Z", starred = false, deleted = true
            )
        ),
        albumState = ExportedAlbumState(selected = mapOf("r1" to 2), pinned = listOf("r1", "r2")),
        sounds = mapOf("保存" to listOf("sounds/save.wav"), "删除" to emptyList()),
        settings = mapOf(
            "mirror_prefs" to mapOf(
                "ai_thinking" to PrefEntry("boolean", true),
                "concept_min_usage" to PrefEntry("int", 3),
                "ai_cloud_max_tokens_cap" to PrefEntry("long", 4096L),
                "temperature" to PrefEntry("float", 0.7f),
                "ai_model" to PrefEntry("string", "qwen"),
                "feature_console" to PrefEntry("string_set", setOf("A", "B"))
            )
        ),
        attachmentsIndex = mapOf(
            "r1" to listOf(ExportedAttachment("a.png", "IMAGE", 1234L, null, 1700000000000L))
        ),
        records = listOf(
            ExportRecord(
                id = "r1", type = "ESSAY", content = "正文", tags = emptyList(),
                timestamp = "2026-01-01T00:00:00Z", starred = false, deleted = false,
                updated_at = "2026-01-02T00:00:00Z", enc_version = 1
            )
        )
    )

    @Test
    fun fullBackupWritesAllNewBlocks() {
        val json = ExportEngine.buildOrderedJson(fullData())
        val obj = GsonInstance.gson.fromJson(json, JsonObject::class.java)
        newBlockKeys.forEach { assertTrue("整体备份应写出：$it", obj.has(it)) }
    }

    @Test
    fun normalExportWritesNoNewBlocks() {
        val json = ExportEngine.buildOrderedJson(baseData())
        newBlockKeys.forEach { assertFalse("普通导出不应出现：$it", json.contains("\"$it\"")) }
        // 记录级的整体备份专用字段也不许出现
        assertFalse(json.contains("updated_at"))
        assertFalse(json.contains("enc_version"))
    }

    @Test
    fun backupMetaCarriesDbVersionAndCounts() {
        val meta = GsonInstance.gson.fromJson(
            ExportEngine.buildOrderedJson(fullData()), JsonObject::class.java
        ).getAsJsonObject("backup_meta")
        assertEquals("full", meta.get("kind").asString)
        assertEquals(31, meta.get("db_version").asInt)
        assertEquals(1, meta.get("record_count").asInt)
        assertEquals(1, meta.get("trash_count").asInt)
        assertEquals(2, meta.get("version_count").asInt)
    }

    @Test
    fun trashRecordsKeepFullShape() {
        val trash = GsonInstance.gson.fromJson(
            ExportEngine.buildOrderedJson(fullData()), JsonObject::class.java
        ).getAsJsonArray("trash")
        assertEquals(1, trash.size())
        val t = trash[0].asJsonObject
        assertEquals("r9", t.get("id").asString)
        assertEquals("误删的正文", t.get("content").asString)
        assertTrue(t.get("deleted").asBoolean)
    }

    @Test
    fun renamedAndSecretPrefsAreTypedForRoundTrip() {
        // 模拟导入端：Gson 把整个 settings 读回来（数字一律变 Double），再按 type 还原
        val json = ExportEngine.buildOrderedJson(fullData())
        val parsed = GsonInstance.gson.fromJson(json, ImportSettingsProbe::class.java).settings
        assertNotNull(parsed)
        val prefs = parsed!!["mirror_prefs"]!!
        assertEquals(true, ExportEngine.decodePrefValue(prefs["ai_thinking"]!!.type, prefs["ai_thinking"]!!.value))
        assertEquals(3, ExportEngine.decodePrefValue(prefs["concept_min_usage"]!!.type, prefs["concept_min_usage"]!!.value))
        assertEquals(4096L, ExportEngine.decodePrefValue(prefs["ai_cloud_max_tokens_cap"]!!.type, prefs["ai_cloud_max_tokens_cap"]!!.value))
        assertEquals(0.7f, ExportEngine.decodePrefValue(prefs["temperature"]!!.type, prefs["temperature"]!!.value) as Float, 0.0001f)
        assertEquals("qwen", ExportEngine.decodePrefValue(prefs["ai_model"]!!.type, prefs["ai_model"]!!.value))
        assertEquals(
            setOf("A", "B"),
            ExportEngine.decodePrefValue(prefs["feature_console"]!!.type, prefs["feature_console"]!!.value)
        )
    }

    @Test
    fun prefTypeOfCoversAllSharedPreferencesValueKinds() {
        assertEquals("boolean", ExportEngine.prefTypeOf(true))
        assertEquals("int", ExportEngine.prefTypeOf(1))
        assertEquals("long", ExportEngine.prefTypeOf(1L))
        assertEquals("float", ExportEngine.prefTypeOf(1f))
        assertEquals("string", ExportEngine.prefTypeOf("x"))
        assertEquals("string_set", ExportEngine.prefTypeOf(setOf("x")))
    }

    @Test
    fun decodePrefValueSurvivesGsonDoubleCoercion() {
        // Gson 读 Any? 会把 5 读成 5.0；不记类型就写不回 Int
        assertEquals(5, ExportEngine.decodePrefValue("int", 5.0))
        assertEquals(5L, ExportEngine.decodePrefValue("long", 5.0))
        assertEquals(5f, ExportEngine.decodePrefValue("float", 5.0) as Float, 0.0001f)
    }

    @Test
    fun soundsKeepExplicitSilence() {
        val sounds = GsonInstance.gson.fromJson(
            ExportEngine.buildOrderedJson(fullData()), ImportSoundsProbe::class.java
        ).sounds
        assertNotNull(sounds)
        // 空列表 = 显式静音，必须保留这个"存在但为空"的语义
        assertTrue(sounds!!.containsKey("删除"))
        assertEquals(emptyList<String>(), sounds["删除"])
    }

    @Test
    fun albumStateKeepsSelectionAndPinning() {
        val state = GsonInstance.gson.fromJson(
            ExportEngine.buildOrderedJson(fullData()), ImportAlbumStateProbe::class.java
        ).album_state
        assertNotNull(state)
        assertEquals(2, state!!.selected["r1"])
        assertEquals(listOf("r1", "r2"), state.pinned)
    }

    @Test
    fun attachmentsIndexKeepsMetadata() {
        val idx = GsonInstance.gson.fromJson(
            ExportEngine.buildOrderedJson(fullData()), ImportAttachmentsProbe::class.java
        ).attachments_index
        assertNotNull(idx)
        val a = idx!!["r1"]!!.single()
        assertEquals("a.png", a.file_name)
        assertEquals("IMAGE", a.type)
        assertEquals(1234L, a.file_size)
        assertEquals(1700000000000L, a.created_at)
    }
}

// ===== 最小"导入端视图"探针：只声明关心的字段，模拟 ImportEngine 的读法 =====

private data class ImportSettingsProbe(
    val settings: Map<String, Map<String, PrefEntry>>? = null
)

private data class ImportSoundsProbe(
    val sounds: Map<String, List<String>>? = null
)

private data class ImportAlbumStateProbe(
    val album_state: ExportedAlbumState? = null
)

private data class ImportAttachmentsProbe(
    val attachments_index: Map<String, List<ExportedAttachment>>? = null
)
