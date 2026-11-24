package memore.app.utils

import com.google.gson.JsonObject
import memore.app.data.model.EmotionTagEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 批量导出（时间线「批量选择 → 导出」）精简字段契约测试。
 *
 * 定位：即时向 AI 补充信息，非备份 —— 顶层只 4 个键，记录只保留语义字段。
 * 本测试锁住字段口径：以后误加回整体性块 / 应用状态字段会当场失败。
 */
class BatchExportLiteTest {

    private fun sample(): ExportRecordLite = ExportRecordLite(
        id = "r1",
        type = "DIALOGUE",
        timestamp = "2026-01-01T00:00:00Z",
        content = "正文",
        dialogue_ai_reply = "AI 回复",
        notes = null,
        tags = listOf("标签A"),
        protocols = null,
        linked_ids = listOf("r0"),
        correction_of_id = null,
        summary = "摘要",
        memory_nodes = listOf("节点A"),
        emotion = listOf(EmotionTagEntry("平静", 0.5f, "flat"))
    )

    private fun build(records: List<ExportRecordLite> = listOf(sample())): String =
        ExportEngine.buildOrderedJsonLite("2.5", "2026-01-01T00:00:00Z", records.size, records)

    private fun root(): JsonObject = GsonInstance.gson.fromJson(build(), JsonObject::class.java)

    private fun firstRecord(json: String): JsonObject =
        GsonInstance.gson.fromJson(json, JsonObject::class.java)
            .getAsJsonArray("records")[0].asJsonObject

    @Test
    fun topLevelHasOnlyFourKeys() {
        assertEquals(
            setOf("version", "export_time", "count", "records"),
            root().keySet()
        )
    }

    @Test
    fun recordKeepsOnlySemanticFieldsAndOmitsNulls() {
        // notes / protocols / correction_of_id 为 null → 不出现
        assertEquals(
            setOf(
                "id", "type", "timestamp", "content", "dialogue_ai_reply",
                "tags", "linked_ids", "summary", "memory_nodes", "emotion"
            ),
            firstRecord(build()).keySet()
        )
    }

    @Test
    fun recordLevelBlocksAreGone() {
        val json = build()
        listOf(
            "dialogue_ai_content", "emotional_tags", "analysis", "meta", "relations",
            "starred", "deleted", "ai_status", "attachment_count", "album_footnotes",
            "continuity_links", "is_locked", "was_locked", "encrypted_data", "iv", "auth_tag"
        ).forEach { assertFalse("记录字段不应出现：$it", json.contains("\"$it\"")) }
    }

    @Test
    fun topLevelBlocksAreGone() {
        val json = build()
        listOf(
            "system_instruction", "import_enabled", "encryption_config", "emotional_profile",
            "candidate_edges", "rejected_merges", "prompts", "node_hierarchy", "tag_hierarchy",
            "ticks", "tick_meta", "audio_items", "alias_groups", "concept_network",
            "graph_visualization", "profile_meta"
        ).forEach { assertFalse("顶层不应出现：$it", json.contains("\"$it\"")) }
    }

    @Test
    fun emotionKeepsNameWeightTrend() {
        val emotion = firstRecord(build()).getAsJsonArray("emotion")[0].asJsonObject
        assertEquals(setOf("name", "weight", "trend"), emotion.keySet())
        assertEquals("平静", emotion.get("name").asString)
        assertEquals(0.5f, emotion.get("weight").asFloat, 0.0001f)
    }

    @Test
    fun countMatchesRecordsSizeAndVersionIs25() {
        val two = listOf(sample(), sample().copy(id = "r2"))
        val obj = GsonInstance.gson.fromJson(build(two), JsonObject::class.java)
        assertEquals(2, obj.get("count").asInt)
        assertEquals(2, obj.getAsJsonArray("records").size())
        assertEquals("2.5", obj.get("version").asString)
    }

    @Test
    fun legacyLinksAndOwnershipStillTravel() {
        val record = firstRecord(build(listOf(sample().copy(correction_of_id = "r9"))))
        assertEquals("r0", record.getAsJsonArray("linked_ids")[0].asString)
        assertEquals("r9", record.get("correction_of_id").asString)
        // 不再重复写提醒文字
        assertFalse(record.get("content").asString.contains("system_instruction"))
        assertTrue(record.keySet().none { it == "reminder" })
    }

    /**
     * **有向带类型**的关系也要进批量导出：批量导出的定位是"即时给 AI 补充信息"，
     * 而"更正/补充/引用"正是判断"哪一版才是当前事实"的首要依据 —— 不能只留已冻结的 linked_ids。
     */
    @Test
    fun typedRelationsTravelAsRelationsOutAndIn() {
        val record = firstRecord(
            build(
                listOf(
                    sample().copy(
                        relations_out = listOf(ExportedRelationOut("r7", "CORRECT")),
                        relations_in = listOf(ExportedRelationIn("r8", "SUPPLEMENT"))
                    )
                )
            )
        )
        val out = record.getAsJsonArray("relations_out")[0].asJsonObject
        assertEquals(setOf("to_id", "type"), out.keySet())
        assertEquals("r7", out.get("to_id").asString)
        assertEquals("CORRECT", out.get("type").asString)

        val incoming = record.getAsJsonArray("relations_in")[0].asJsonObject
        assertEquals(setOf("from_id", "type"), incoming.keySet())
        assertEquals("r8", incoming.get("from_id").asString)
        assertEquals("SUPPLEMENT", incoming.get("type").asString)
    }

    /** 没有关系时不写这两个键（空字段省略，包保持小）。 */
    @Test
    fun recordsWithoutRelationsOmitThoseKeys() {
        val json = build()
        assertFalse(json.contains("\"relations_out\""))
        assertFalse(json.contains("\"relations_in\""))
    }
}
