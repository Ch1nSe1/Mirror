package memore.app.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 分区（2026-11-14）。
 *
 * 一个分区 = 一个**保存下来的条件**。锚点、AI 札记也是分区，
 * 只是它们的条件写死在代码里（不进这张表），并且不能改名、不能删。
 *
 * 为什么只存自定义的：来源/标签/情绪/协议/概念 这些维度有几百个值，
 * 全存成表行会爆炸，而且永远同步不了——它们是**查询时动态生成**的视图。
 */
@Entity(tableName = "zones")
data class Zone(
    @PrimaryKey val id: String,
    val name: String,
    /** 条件 JSON，见 [ZoneCondition]。内置分区不用它（留空）。 */
    val condition: String = "",
    val sort: Int = 0,
    /** 内置分区（锚点 / AI 札记）：不能删、不能改名。 */
    val builtin: Boolean = false,
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis()
)

/** 内置分区的 id。 */
object BuiltinZones {
    const val ANCHOR = "zone_anchor"
    const val AI_NOTE = "zone_ai_note"

    val all = listOf(
        Zone(id = ANCHOR, name = "锚点", sort = 0, builtin = true),
        Zone(id = AI_NOTE, name = "札记", sort = 1, builtin = true)
    )
}

/**
 * 分区成员（2026-11-14）。
 *
 * 手动型分区靠这张表装记录：一条记录可以被放进多个分区。
 * 锚点不在这张表里——它仍然用 `records.is_anchored`，因为取消锚点、锚点音效、
 * 老的锚点页都依赖那个布尔值，搬进成员表只会造出两份真值。
 */
@Entity(tableName = "zone_records", primaryKeys = ["zone_id", "record_id"])
data class ZoneRecord(
    @ColumnInfo(name = "zone_id") val zoneId: String,
    @ColumnInfo(name = "record_id") val recordId: String,
    @ColumnInfo(name = "added_at") val addedAt: Long = System.currentTimeMillis()
)
