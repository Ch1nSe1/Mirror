package memore.app.utils

import com.google.gson.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 草稿进备份包（用户 2026-10-11 定：**默认关**，「整体备份」是全量语义、强制带上）。
 *
 * 钉住四条：
 * ① **默认不写 `drafts` 键** —— 不勾就等于没有，老配置的输出逐字节不变；
 * ② 勾了 / 整体备份时写出 `drafts`，块顺序与 kind 原样保留（顺序就是屏幕上的顺序）；
 * ③ 导入端：**空本不还原**、**同名 + 同创建时间跳过**（反复导入不堆重复）；
 * ④ 密钥不随包：`ai_api_key` 直接从 settings 里剔除，`api_presets` 里的密钥洗空。
 *
 * 用 Robolectric 是因为出错路径要写日志（`LogUtil` → `android.util.Log`，纯 JVM 下会抛"not mocked"）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DraftBackupTest {

    private fun dataWith(
        drafts: List<ExportedDraft>? = null,
        backupMeta: BackupMeta? = null,
        settings: Map<String, Map<String, PrefEntry>>? = null
    ) = ExportData(
        version = "2.5",
        export_time = "2026-01-01T00:00:00Z",
        count = 0,
        records = emptyList(),
        drafts = drafts,
        backup_meta = backupMeta,
        settings = settings
    )

    private fun json(data: ExportData) = ExportEngine.buildOrderedJson(data)

    private fun sampleDraft(name: String = "1984有感", createdAt: Long = 1000L, blocks: List<ExportedDraftBlock>? = null) =
        ExportedDraft(
            name = name,
            createdAt = createdAt,
            updatedAt = 2000L,
            archived = false,
            pinned = true,
            blocks = blocks ?: listOf(
                ExportedDraftBlock(kind = "MINE", content = "第一块", createdAt = 1000L),
                ExportedDraftBlock(kind = "AI", content = "AI 整理出来的", createdAt = 1500L, updatedAt = 1600L),
                ExportedDraftBlock(kind = "QA", content = "问：A？\n答：B", createdAt = 1700L)
            )
        )

    // ---- ① 默认不写 ----

    @Test
    fun draftsAreAbsentByDefault() {
        assertFalse("没勾草稿 → 包里不该有 drafts 键", json(dataWith()).contains("\"drafts\""))
    }

    @Test
    fun draftsAreWrittenWhenIncluded() {
        val json = json(dataWith(drafts = listOf(sampleDraft())))
        assertTrue(json.contains("\"drafts\""))
        val obj = GsonInstance.gson.fromJson(json, JsonObject::class.java).getAsJsonArray("drafts")
        assertEquals(1, obj.size())
        val d = obj[0].asJsonObject
        assertEquals("1984有感", d.get("name").asString)
        assertEquals(1000L, d.get("created_at").asLong)
        assertEquals(2000L, d.get("updated_at").asLong)
        assertTrue("置顶状态要带上", d.get("pinned").asBoolean)
        val blocks = d.getAsJsonArray("blocks")
        assertEquals("块顺序就是屏幕上的顺序", 3, blocks.size())
        assertEquals(listOf("MINE", "AI", "QA"), blocks.map { it.asJsonObject.get("kind").asString })
        assertEquals("第一块", blocks[0].asJsonObject.get("content").asString)
        assertEquals(1600L, blocks[1].asJsonObject.get("updated_at").asLong)
    }

    @Test
    fun backupMetaCarriesDraftCount() {
        val json = json(
            dataWith(
                drafts = listOf(sampleDraft()),
                backupMeta = BackupMeta(
                    kind = "full", app_version = "1.2.0", db_version = 43,
                    includes = listOf("records", "drafts"),
                    record_count = 10, draft_count = 3, created_at = "2026-01-01T00:00:00Z"
                )
            )
        )
        val meta = GsonInstance.gson.fromJson(json, JsonObject::class.java).getAsJsonObject("backup_meta")
        assertEquals("整体备份要自报带上草稿", 3, meta.get("draft_count").asInt)
        assertTrue(meta.getAsJsonArray("includes").map { it.asString }.contains("drafts"))
    }

    @Test
    fun draftBlockWithNullUpdatedAtStaysNullOrAbsent() {
        val json = json(dataWith(drafts = listOf(sampleDraft(blocks = listOf(ExportedDraftBlock(kind = "MINE", content = "没改过", createdAt = 5L))))))
        val block = GsonInstance.gson.fromJson(json, JsonObject::class.java)
            .getAsJsonArray("drafts")[0].asJsonObject.getAsJsonArray("blocks")[0].asJsonObject
        // Gson 默认不写 null 字段：没改过的块**不该凭空多出一个改动时间**
        assertTrue("没改过的块不许出现 updated_at", !block.has("updated_at") || block.get("updated_at").isJsonNull)
    }

    // ---- 收集（真库） ----

    /**
     * `collectDrafts` 用真 DraftRepository（内存 Room）跑：
     * 空本丢掉、归档的照带、开关关了就是 null。
     */
    @Test
    fun collectDraftsSkipsEmptyBooksAndKeepsArchivedOnes() {
        kotlinx.coroutines.runBlocking {
            val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
            val db = androidx.room.Room.inMemoryDatabaseBuilder(context, memore.app.data.local.AppDatabase::class.java)
                .allowMainThreadQueries().build()
            try {
                val repo = memore.app.data.repository.DraftRepository(db.draftDao(), db.draftBlockDao())
                val live = repo.createDraft("正在写的")
                repo.add(live, "写了一句")
                repo.add(live, "AI 整理出来的", kind = "AI")
                val archived = repo.createDraft("归档的")
                repo.add(archived, "归档里的一句话")
                repo.setDraftArchived(archived, true)
                repo.createDraft("空本")                       // 一个块都没有
                val blankOnly = repo.createDraft("只有空白")
                repo.add(blankOnly, "   ")

                val out = ExportEngine.collectDrafts(repo, include = true, full = false)!!
                assertEquals("空本与全空白本都不进包", 2, out.size)
                assertEquals(listOf("正在写的", "归档的"), out.map { it.name })
                assertTrue("归档状态要带上", out[1].archived)
                assertEquals(listOf("MINE", "AI"), out[0].blocks.map { it.kind })

                assertEquals(
                    "整体备份强制带上（不看开关）",
                    2, ExportEngine.collectDrafts(repo, include = false, full = true)!!.size
                )
                assertNull("开关关了就一个字都不写", ExportEngine.collectDrafts(repo, include = false, full = false))
            } finally {
                db.close()
            }
        }
    }

    // ---- ③ 导入端规则 ----

    @Test
    fun emptyDraftsAreNeverRestored() {
        val pkg = listOf(
            sampleDraft(name = "空的", createdAt = 1L, blocks = emptyList()),
            sampleDraft(name = "全是空白的", createdAt = 2L, blocks = listOf(ExportedDraftBlock(content = "   "))),
            sampleDraft(name = "有内容", createdAt = 3L)
        )
        val (restore, skipped) = ImportEngine.draftsToRestore(pkg, existing = emptySet())
        assertEquals("空本静默丢掉", listOf("有内容"), restore.map { it.name })
        assertTrue("空本不算「跳过重复」", skipped.isEmpty())
    }

    @Test
    fun sameNameAndCreatedAtIsSkipped() {
        val pkg = listOf(sampleDraft(name = "A", createdAt = 100L), sampleDraft(name = "A", createdAt = 200L))
        val (restore, skipped) = ImportEngine.draftsToRestore(pkg, existing = setOf("A" to 100L))
        assertEquals("同名的另一次创建该还原", listOf(200L), restore.map { it.createdAt })
        assertEquals(listOf(100L), skipped.map { it.createdAt })
    }

    @Test
    fun reimportingTheSamePackageRestoresNothingTheSecondTime() {
        val pkg = listOf(sampleDraft(name = "A", createdAt = 100L), sampleDraft(name = "B", createdAt = 200L))
        val (first, _) = ImportEngine.draftsToRestore(pkg, existing = emptySet())
        assertEquals(2, first.size)
        // 第二次导入：本机已经有这两本了
        val (second, skipped) = ImportEngine.draftsToRestore(pkg, existing = setOf("A" to 100L, "B" to 200L))
        assertTrue(second.isEmpty())
        assertEquals(2, skipped.size)
    }

    // ---- ④ 密钥不随包 ----

    @Test
    fun apiKeyIsExcludedFromSettings() {
        assertTrue(
            "ai_api_key 必须从 settings 里剔除",
            ExportEngine.BACKUP_PREFS_EXCLUDED_KEYS.contains("ai_api_key")
        )
        assertTrue(ExportEngine.BACKUP_PREFS_EXCLUDED_KEYS.contains("system_instruction"))
    }

    @Test
    fun apiPresetKeysAreBlankedButEndpointKept() {
        val raw = """[{"name":"DeepSeek","apiKey":"sk-秘密","endpoint":"https://api.deepseek.com/v1","model":"deepseek-flash"}]"""
        val out = ExportEngine.stripApiPresetKeys(raw)!!
        assertFalse("密钥不许随包", out.contains("sk-秘密"))
        assertTrue("端点要留着（换机少填一样）", out.contains("https://api.deepseek.com/v1"))
        assertTrue(out.contains("deepseek-flash"))
    }

    @Test
    fun apiPresetGarbageIsPassedThrough() {
        assertEquals("解析不动就原样带着，不要吞掉用户的预设", "不是 JSON", ExportEngine.stripApiPresetKeys("不是 JSON"))
    }
}
