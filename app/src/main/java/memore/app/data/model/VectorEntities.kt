package memore.app.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index

/**
 * 向量检索相关实体（DB v49）。
 *
 * 设计文档：docs/向量检索设计.md §4
 * 铁律：这几张表只属于「潜层 / 派生层」，**不参与任何显式标签语义**。
 */

/** 一条记录的一块向量。已 L2 归一化，float32 小端。 */
@Entity(
    tableName = "record_vectors",
    primaryKeys = ["record_id", "chunk_index"],
    indices = [Index(value = ["model"])]
)
data class RecordVector(
    @ColumnInfo(name = "record_id") val recordId: String,
    @ColumnInfo(name = "chunk_index") val chunkIndex: Int,
    @ColumnInfo(name = "model") val model: String,
    @ColumnInfo(name = "dim") val dim: Int,
    @ColumnInfo(name = "vec") val vec: ByteArray,
    @ColumnInfo(name = "text_hash") val textHash: String,
    @ColumnInfo(name = "updated_at") val updatedAt: Long
) {
    // ByteArray 的 equals/hashCode 要手写，否则 data class 默认是引用比较
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RecordVector) return false
        return recordId == other.recordId &&
            chunkIndex == other.chunkIndex &&
            model == other.model &&
            dim == other.dim &&
            textHash == other.textHash &&
            updatedAt == other.updatedAt &&
            vec.contentEquals(other.vec)
    }

    override fun hashCode(): Int {
        var result = recordId.hashCode()
        result = 31 * result + chunkIndex
        result = 31 * result + model.hashCode()
        result = 31 * result + dim
        result = 31 * result + textHash.hashCode()
        result = 31 * result + updatedAt.hashCode()
        result = 31 * result + vec.contentHashCode()
        return result
    }
}

/** 索引状态：一条记录一行，用于统计、跳过与重试。 */
@Entity(tableName = "embedding_state")
data class EmbeddingState(
    @androidx.room.PrimaryKey
    @ColumnInfo(name = "record_id") val recordId: String,
    /** OK / PENDING / SKIPPED_SHORT / SKIPPED_LOCKED / FAILED */
    @ColumnInfo(name = "status") val status: String,
    @ColumnInfo(name = "model") val model: String? = null,
    @ColumnInfo(name = "error") val error: String? = null,
    @ColumnInfo(name = "updated_at") val updatedAt: Long = System.currentTimeMillis()
) {
    val isOk: Boolean get() = status == STATUS_OK

    companion object {
        const val STATUS_OK = "OK"
        const val STATUS_PENDING = "PENDING"
        const val STATUS_SKIPPED_SHORT = "SKIPPED_SHORT"
        const val STATUS_SKIPPED_LOCKED = "SKIPPED_LOCKED"
        const val STATUS_FAILED = "FAILED"
    }
}

/** 主题簇（一次聚类快照的一簇）。 */
@Entity(tableName = "topics")
data class Topic(
    @ColumnInfo(name = "id") @androidx.room.PrimaryKey val id: String,
    @ColumnInfo(name = "snapshot_id") val snapshotId: String,
    @ColumnInfo(name = "name") val name: String,
    /** AI / USER / FALLBACK */
    @ColumnInfo(name = "name_source") val nameSource: String,
    @ColumnInfo(name = "centroid") val centroid: ByteArray,
    @ColumnInfo(name = "member_count") val memberCount: Int,
    /** c-TF-IDF 关键词（顿号连接），用于展示「为什么归在一起」 */
    @ColumnInfo(name = "keywords") val keywords: String = "",
    /** 上一级主题 id（2026-11-13 两级聚类）。null = 母题（L1）。 */
    @ColumnInfo(name = "parent_id") val parentId: String? = null,
    /** 0 = 母题，1 = 话题。 */
    @ColumnInfo(name = "level") val level: Int = 0,
    /** 代表句（2026-11-13）：离质心最近那条记录的第一句，用来替代关键词做解释。 */
    @ColumnInfo(name = "summary") val summary: String = ""
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Topic) return false
        return id == other.id && snapshotId == other.snapshotId && name == other.name &&
            nameSource == other.nameSource && memberCount == other.memberCount &&
            keywords == other.keywords && centroid.contentEquals(other.centroid)
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + snapshotId.hashCode()
        result = 31 * result + name.hashCode()
        result = 31 * result + centroid.contentHashCode()
        return result
    }
}

@Entity(
    tableName = "record_topics",
    primaryKeys = ["record_id", "topic_id"],
    indices = [Index(value = ["topic_id"])]
)
data class RecordTopic(
    @ColumnInfo(name = "record_id") val recordId: String,
    @ColumnInfo(name = "topic_id") val topicId: String,
    @ColumnInfo(name = "score") val score: Float
)

/** 主题改名沿用（生命周期待定，最小实现：质心哈希 → 名字）。 */
@Entity(tableName = "topic_overrides")
data class TopicOverride(
    @androidx.room.PrimaryKey
    @ColumnInfo(name = "centroid_hash") val centroidHash: String,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "updated_at") val updatedAt: Long
)
