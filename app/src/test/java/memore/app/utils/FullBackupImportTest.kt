package memore.app.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 整体备份「导出 → 导入解析」往返契约测试。
 *
 * 这是导出侧与导入侧的接缝：两边的字段名一旦写歪（`updated_at` vs `updatedAt` 之类），
 * 真机上只会表现为"某块静默没还原"，很难查。这里把接缝钉死。
 */
class FullBackupImportTest {

    private fun fullJson(): String = ExportEngine.buildOrderedJson(
        ExportData(
            version = "2.5",
            export_time = "2026-01-01T00:00:00Z",
            count = 1,
            records = listOf(
                ExportRecord(
                    id = "r1", type = "ESSAY", content = "正文", tags = listOf("标签A"),
                    timestamp = "2026-01-01T00:00:00Z", starred = false, deleted = false,
                    updated_at = "2026-01-03T00:00:00Z", enc_version = 2
                )
            ),
            backup_meta = BackupMeta(
                kind = "full", app_version = "1.2.0",
                db_version = memore.app.data.local.APP_DB_VERSION,
                includes = listOf("records"), record_count = 1, trash_count = 1,
                version_count = 1, created_at = "2026-01-01T00:00:00Z"
            ),
            versions = listOf(ExportedVersion("r1", "改前正文", 100L)),
            trash = listOf(
                ExportRecord(
                    id = "r9", type = "ESSAY", content = "误删的", tags = emptyList(),
                    timestamp = "2025-12-01T00:00:00Z", starred = false, deleted = true,
                    updated_at = "2025-12-02T00:00:00Z"
                )
            ),
            album_state = ExportedAlbumState(selected = mapOf("r1" to 2), pinned = listOf("r1")),
            sounds = mapOf("保存" to listOf("sounds/save.wav"), "删除" to emptyList()),
            settings = mapOf(
                "mirror_prefs" to mapOf(
                    "ai_thinking" to PrefEntry("boolean", true),
                    "concept_min_usage" to PrefEntry("int", 3),
                    "ai_api_key" to PrefEntry("string", "sk-x"),
                    "emotion_defaults_seeded" to PrefEntry("boolean", true)
                )
            ),
            attachments_index = mapOf(
                "r1" to listOf(ExportedAttachment("a.png", "IMAGE", 10L, null, 170L))
            )
        )
    )

    private fun parse(json: String) =
        GsonInstance.gson.fromJson(json, ImportEngine.ImportData::class.java)

    @Test
    fun allSevenBlocksSurviveIntoImportModel() {
        val data = parse(fullJson())
        assertNotNull(data.backup_meta)
        assertNotNull(data.versions)
        assertNotNull(data.trash)
        assertNotNull(data.album_state)
        assertNotNull(data.sounds)
        assertNotNull(data.settings)
        assertNotNull(data.attachments_index)
    }

    @Test
    fun backupMetaFieldsMatch() {
        val meta = parse(fullJson()).backup_meta!!
        assertEquals("full", meta.kind)
        assertEquals("1.2.0", meta.app_version)
        assertEquals(memore.app.data.local.APP_DB_VERSION, meta.db_version)
        assertEquals(1, meta.trash_count)
        assertEquals(1, meta.version_count)
    }

    @Test
    fun trashEntriesReadAsRecords() {
        val trash = parse(fullJson()).trash!!
        assertEquals(1, trash.size)
        assertEquals("r9", trash[0].id)
        assertEquals("误删的", trash[0].content)
        assertTrue(trash[0].deleted)
        assertEquals("2025-12-02T00:00:00Z", trash[0].updated_at)
    }

    @Test
    fun perRecordBackupFieldsSurvive() {
        val r = parse(fullJson()).records.single()
        assertEquals("2026-01-03T00:00:00Z", r.updated_at)
        assertEquals(2, r.enc_version)
    }

    @Test
    fun versionsAndAlbumStateSurvive() {
        val data = parse(fullJson())
        val v = data.versions!!.single()
        assertEquals("r1", v.record_id)
        assertEquals("改前正文", v.content)
        assertEquals(100L, v.created_at)
        assertEquals(2, data.album_state!!.selected["r1"])
        assertEquals(listOf("r1"), data.album_state!!.pinned)
    }

    @Test
    fun settingsKeepTypeTagsAndExplicitSilence() {
        val data = parse(fullJson())
        val prefs = data.settings!!["mirror_prefs"]!!
        assertEquals("boolean", prefs["ai_thinking"]!!.type)
        assertEquals("int", prefs["concept_min_usage"]!!.type)
        // 空列表 = 显式静音，必须作为 key 保留下来
        assertTrue(data.sounds!!.containsKey("删除"))
        assertTrue(data.sounds!!["删除"]!!.isEmpty())
    }

    @Test
    fun attachmentsIndexSurvives() {
        val a = parse(fullJson()).attachments_index!!["r1"]!!.single()
        assertEquals("a.png", a.file_name)
        assertEquals("IMAGE", a.type)
        assertEquals(170L, a.created_at)
    }

    @Test
    fun oldPackageLeavesAllNewBlocksNull() {
        // 旧包 / 普通包：没有这些字段 → 全部 null，导入端整段跳过（向后兼容）
        val oldJson = """{"version":"2.5","count":1,"records":[{"id":"r1","type":"ESSAY","content":"x","tags":[],"timestamp":"2026-01-01T00:00:00Z","starred":false,"deleted":false}]}"""
        val data = parse(oldJson)
        assertNull(data.backup_meta)
        assertNull(data.versions)
        assertNull(data.trash)
        assertNull(data.album_state)
        assertNull(data.sounds)
        assertNull(data.settings)
        assertNull(data.attachments_index)
        assertNull(data.records.single().updated_at)
        assertNull(data.records.single().enc_version)
    }

    @Test
    fun backupNoteIsSilentForPlainPackages() {
        assertNull(ImportEngine.backupNote(ImportEngine.ImportData()))
    }

    @Test
    fun backupNoteWarnsOnSchemaMismatch() {
        val local = memore.app.data.local.APP_DB_VERSION
        val newer = ImportEngine.ImportData(
            backup_meta = BackupMeta(kind = "full", db_version = local + 1, app_version = "9.9")
        )
        val older = ImportEngine.ImportData(
            backup_meta = BackupMeta(kind = "full", db_version = local - 1, app_version = "1.0")
        )
        val same = ImportEngine.ImportData(
            backup_meta = BackupMeta(kind = "full", db_version = local, app_version = "1.2.0")
        )
        assertTrue(ImportEngine.backupNote(newer)!!.contains("更新的数据库版本"))
        assertTrue(ImportEngine.backupNote(older)!!.contains("更旧的数据库版本"))
        val ok = ImportEngine.backupNote(same)!!
        assertFalse(ok.contains("⚠"))
        assertTrue(ok.contains("整体备份包"))
    }

    @Test
    fun redactionGroupsTravelAndReadBack() {
        val group = memore.app.data.model.RedactionGroup(
            id = "g1", standardName = "标准名", aliases = listOf("别名甲", "别名乙"), createdAt = 1L, updatedAt = 2L
        )
        val json = ExportEngine.buildOrderedJson(
            ExportData(
                version = "2.5", export_time = "t", count = 0, records = emptyList(),
                redaction_groups = listOf(group)
            )
        )
        // 只在整体备份里出现；普通导出不带映射表
        assertTrue(json.contains("redaction_groups"))
        val plain = ExportEngine.buildOrderedJson(
            ExportData(version = "2.5", export_time = "t", count = 0, records = emptyList())
        )
        assertFalse(plain.contains("redaction_groups"))

        val back = parse(json).redaction_groups!!.single()
        assertEquals("g1", back.id)
        assertEquals("标准名", back.standardName)
        assertEquals(listOf("别名甲", "别名乙"), back.aliases)
    }

    @Test
    fun migrationMarkersAndSecretsAreSpecialCased() {
        assertTrue(ImportEngine.isMigrationMarkerKey("emotion_defaults_seeded"))
        assertTrue(ImportEngine.isMigrationMarkerKey("emotion_migration_v1_done"))
        assertFalse(ImportEngine.isMigrationMarkerKey("ai_thinking"))
        // 单键密钥 + 复合预设键（密钥藏在 api_presets 的 JSON 里）都算凭据
        assertTrue(ImportEngine.isCredentialPrefKey("ai_api_key"))
        assertTrue(ImportEngine.isCredentialPrefKey("api_presets"))
        assertFalse(ImportEngine.isCredentialPrefKey("ai_endpoint"))
        assertFalse(ImportEngine.isCredentialPrefKey("api_presets_backup"))
    }

    @Test
    fun apiPresetsCredentialDetection() {
        // 新机的种子预设：有端点有模型，但密钥全空 → 允许整体覆盖（换新手机要能恢复）
        val seeded = """[
            {"name":"DeepSeek","endpoint":"https://api.deepseek.com/v1","model":"x","apiKey":""},
            {"name":"千问","endpoint":"https://dashscope.aliyuncs.com/v1","model":"y","apiKey":""}
        ]"""
        assertFalse(ImportEngine.apiPresetsHaveKey(seeded))
        // 只要任一条预设带了密钥 → 本机已有凭据，不许被覆盖
        val configured = """[{"name":"DeepSeek","endpoint":"e","model":"m","apiKey":"sk-xxx"}]"""
        assertTrue(ImportEngine.apiPresetsHaveKey(configured))
        // 空 / 缺失
        assertFalse(ImportEngine.apiPresetsHaveKey(null))
        assertFalse(ImportEngine.apiPresetsHaveKey(""))
        assertFalse(ImportEngine.apiPresetsHaveKey("[]"))
        // 格式异常 → 宁可不动本机配置
        assertTrue(ImportEngine.apiPresetsHaveKey("{不是数组}"))
    }
}
