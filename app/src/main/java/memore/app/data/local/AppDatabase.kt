package memore.app.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import memore.app.data.local.dao.AiTaskDao
import memore.app.data.local.dao.AttachmentDao
import memore.app.data.local.dao.DialDao
import memore.app.data.local.dao.RecordDao
import memore.app.data.local.dao.TagDao
import memore.app.data.local.dao.TagGroupDao
import memore.app.data.local.dao.VersionDao
import memore.app.data.model.AiTask
import memore.app.data.model.Attachment
import memore.app.data.local.dao.ProtocolDao
import memore.app.data.local.dao.TickDao
import memore.app.data.model.Protocol
import memore.app.data.model.Record
import memore.app.data.model.RecordProtocol
import memore.app.data.model.RecordRelation
import memore.app.data.model.RecordEntity
import memore.app.data.model.AnalysisVersion
import memore.app.data.local.dao.AnalysisVersionDao
import memore.app.data.local.dao.RecordEntityDao
import memore.app.data.model.RecordTagCrossRef
import memore.app.data.model.EncryptionConfig
import memore.app.data.model.Tag
import memore.app.data.model.TagGroup
import memore.app.data.model.Tick
import memore.app.data.model.Version
import memore.app.data.model.DraftBlock
import memore.app.data.model.Dial
import memore.app.data.model.DialTick
import memore.app.data.model.Draft

/**
 * 当前数据库 schema 版本。
 * 单一事实源：既给 @Database 用，也写进整体备份包的 `backup_meta.db_version`，
 * 供导入端校验"这个包来自哪一版 schema"。改 schema 时只改这里。
 */
internal const val APP_DB_VERSION = 71

@Database(
    entities = [Record::class, Version::class, Tag::class, RecordTagCrossRef::class,
                Attachment::class, AiTask::class,
                
                Protocol::class, RecordProtocol::class, EncryptionConfig::class, Tick::class, 
                TagGroup::class, RecordRelation::class, RecordEntity::class, AnalysisVersion::class, DraftBlock::class, Draft::class,
                Dial::class, DialTick::class,
                memore.app.data.model.RecordVector::class, memore.app.data.model.EmbeddingState::class,
                memore.app.data.model.Topic::class, memore.app.data.model.RecordTopic::class,
                memore.app.data.model.TopicOverride::class, memore.app.data.model.Origin::class,
                memore.app.data.model.Zone::class,
                memore.app.data.model.ZoneRecord::class,
                memore.app.data.model.AgentCommand::class,
                memore.app.data.model.AgentAction::class,
                memore.app.data.model.RecordPreset::class],
    version = APP_DB_VERSION,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun recordDao(): RecordDao
    abstract fun agentCommandDao(): memore.app.data.local.dao.AgentCommandDao
    abstract fun agentActionDao(): memore.app.data.local.dao.AgentActionDao

    /** 2026-11-17：新建记录预设。 */
    abstract fun recordPresetDao(): memore.app.data.local.dao.RecordPresetDao
    abstract fun originDao(): memore.app.data.local.dao.OriginDao
    abstract fun zoneDao(): memore.app.data.local.dao.ZoneDao
    abstract fun recordRelationDao(): memore.app.data.local.dao.RecordRelationDao

    abstract fun recordEntityDao(): RecordEntityDao

    abstract fun analysisVersionDao(): AnalysisVersionDao
    abstract fun versionDao(): VersionDao
    abstract fun tagDao(): TagDao
    abstract fun attachmentDao(): AttachmentDao
    abstract fun aiTaskDao(): AiTaskDao

    abstract fun protocolDao(): ProtocolDao
    abstract fun encryptionConfigDao(): memore.app.data.local.dao.EncryptionConfigDao

    abstract fun tickDao(): TickDao


    abstract fun tagGroupDao(): TagGroupDao

    abstract fun draftBlockDao(): memore.app.data.local.dao.DraftBlockDao

    abstract fun draftDao(): memore.app.data.local.dao.DraftDao

    abstract fun dialDao(): DialDao

    abstract fun vectorDao(): memore.app.data.local.dao.VectorDao

    abstract fun topicDao(): memore.app.data.local.dao.TopicDao

    companion object {
        private const val DB_NAME = "memore.db"

        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE records ADD COLUMN ai_status TEXT NOT NULL DEFAULT 'NONE'")
                db.execSQL("ALTER TABLE records ADD COLUMN emotional_tags TEXT NOT NULL DEFAULT '[]'")
                db.execSQL("CREATE TABLE IF NOT EXISTS concepts (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, name TEXT NOT NULL, created_at INTEGER NOT NULL)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_concepts_name ON concepts (name)")
                db.execSQL("DROP TABLE IF EXISTS record_concepts")
                db.execSQL("CREATE TABLE record_concepts (record_id TEXT NOT NULL, concept_id INTEGER NOT NULL, PRIMARY KEY(record_id, concept_id), FOREIGN KEY(record_id) REFERENCES records(id) ON DELETE CASCADE, FOREIGN KEY(concept_id) REFERENCES concepts(id) ON DELETE CASCADE)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_record_concepts_record_id ON record_concepts (record_id)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_record_concepts_concept_id ON record_concepts (concept_id)")
            }
        }

        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP TABLE IF EXISTS ai_tasks")
                db.execSQL("CREATE TABLE ai_tasks (created_at INTEGER NOT NULL, error_message TEXT, id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, last_attempt_at INTEGER, max_retries INTEGER NOT NULL, record_id TEXT NOT NULL, retry_count INTEGER NOT NULL, status TEXT NOT NULL, task_type TEXT NOT NULL, FOREIGN KEY (record_id) REFERENCES records(id) ON DELETE CASCADE)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_ai_tasks_status ON ai_tasks (status)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_ai_tasks_record_id ON ai_tasks (record_id)")
            }
        }

        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP TABLE IF EXISTS rejected_merges")
                db.execSQL("CREATE TABLE rejected_merges (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, rejected_at INTEGER NOT NULL, remove TEXT NOT NULL, keep TEXT NOT NULL)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_rejected_merges_keep ON rejected_merges (keep)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_rejected_merges_rejected_at ON rejected_merges (rejected_at)")
            }
        }

        val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE memory_nodes (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, name TEXT NOT NULL, node_type TEXT NOT NULL DEFAULT 'CONCEPT', created_at INTEGER NOT NULL)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_memory_nodes_name ON memory_nodes (name)")
                db.execSQL("INSERT INTO memory_nodes (id, name, created_at) SELECT id, name, created_at FROM concepts")
                db.execSQL("DROP TABLE IF EXISTS concepts")

                db.execSQL("CREATE TABLE record_memory_nodes (record_id TEXT NOT NULL, concept_id INTEGER NOT NULL, PRIMARY KEY(record_id, concept_id), FOREIGN KEY(record_id) REFERENCES records(id) ON DELETE CASCADE, FOREIGN KEY(concept_id) REFERENCES memory_nodes(id) ON DELETE CASCADE)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_record_memory_nodes_record_id ON record_memory_nodes (record_id)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_record_memory_nodes_concept_id ON record_memory_nodes (concept_id)")
                db.execSQL("INSERT INTO record_memory_nodes (record_id, concept_id) SELECT record_id, concept_id FROM record_concepts")
                db.execSQL("DROP TABLE IF EXISTS record_concepts")
            }
        }

        val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE records ADD COLUMN analysis TEXT DEFAULT NULL")
                try {
                    db.execSQL("""UPDATE records SET analysis = json_object(
                        'summary', dialogue_ai_content,
                        'concepts', json_array(),
                        'emotion', CASE WHEN emotional_tags IS NULL OR emotional_tags = '' OR emotional_tags = '[]' THEN json_array() ELSE json(emotional_tags) END,
                        'relations', json_array(),
                        'meta', json_object('model', '', 'created_at', '', 'analysis_version', '1.0', 'prompt_version', '1')
                    ) WHERE ai_status = 'SUCCESS'""")
                } catch (_: Exception) {
                    android.util.Log.w("DB-MIGRATION", "SQLite JSON1 not available, skipping data backfill")
                }
            }
        }

        val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS candidate_edges (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, concept_a_id INTEGER NOT NULL, concept_b_id INTEGER NOT NULL, weight INTEGER NOT NULL DEFAULT 0, reason TEXT NOT NULL DEFAULT '', source TEXT NOT NULL DEFAULT 'AI', created_at INTEGER NOT NULL)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_candidate_edges_concept_a_b ON candidate_edges (concept_a_id, concept_b_id)")
            }
        }

        val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS protocols (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, name TEXT NOT NULL, created_at INTEGER NOT NULL, usage_count INTEGER NOT NULL DEFAULT 0)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_protocols_name ON protocols (name)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_protocols_usage_count ON protocols (usage_count)")
                db.execSQL("CREATE TABLE IF NOT EXISTS record_protocols (record_id TEXT NOT NULL, protocol_id INTEGER NOT NULL, PRIMARY KEY(record_id, protocol_id), FOREIGN KEY(record_id) REFERENCES records(id) ON DELETE CASCADE, FOREIGN KEY(protocol_id) REFERENCES protocols(id) ON DELETE CASCADE)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_record_protocols_record_id ON record_protocols (record_id)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_record_protocols_protocol_id ON record_protocols (protocol_id)")
            }
        }

        val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS audio_items (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, uri_string TEXT NOT NULL, file_name TEXT NOT NULL, duration_ms INTEGER NOT NULL DEFAULT 0, added_at INTEGER NOT NULL)")
            }
        }

        val MIGRATION_16_17 = object : Migration(16, 17) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Step 1: 去重附件 — 相同 (record_id, file_name) 只保留 id 最小的一条
                db.execSQL("DELETE FROM attachments WHERE id NOT IN (SELECT MIN(id) FROM attachments GROUP BY record_id, file_name)")
                // Step 2: 创建唯一索引
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_attachments_record_id_file_name ON attachments (record_id, file_name)")
                // Step 3: 修正 attachments_count
                db.execSQL("UPDATE records SET attachments_count = (SELECT COUNT(*) FROM attachments WHERE attachments.record_id = records.id)")
                // Step 4: 去重否决记录 — 相同 (keep, remove) 只保留 id 最小的一条
                db.execSQL("DELETE FROM rejected_merges WHERE id NOT IN (SELECT MIN(id) FROM rejected_merges GROUP BY keep, remove)")
                // Step 5: 创建唯一索引
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_rejected_merges_keep_remove ON rejected_merges (keep, remove)")
            }
        }

        val MIGRATION_17_18 = object : Migration(17, 18) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE records ADD COLUMN is_locked INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE records ADD COLUMN encrypted_data TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE records ADD COLUMN iv TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE records ADD COLUMN auth_tag TEXT NOT NULL DEFAULT ''")
                db.execSQL("CREATE TABLE IF NOT EXISTS encryption_config (id INTEGER PRIMARY KEY NOT NULL, enabled INTEGER NOT NULL DEFAULT 1, password_salt TEXT NOT NULL, password_hash TEXT NOT NULL, key_salt TEXT NOT NULL, question TEXT NOT NULL, question_hash TEXT NOT NULL, answer_salt TEXT NOT NULL, answer_hash TEXT NOT NULL, wrapped_key TEXT NOT NULL DEFAULT '', created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL)")
            }
        }

        val MIGRATION_18_19 = object : Migration(18, 19) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 为已存在的 encryption_config 表补充 enabled 列
                db.execSQL("ALTER TABLE encryption_config ADD COLUMN enabled INTEGER NOT NULL DEFAULT 1")
            }
        }

        val MIGRATION_19_20 = object : Migration(19, 20) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 补充迭代次数列：旧配置默认 600000（保持原哈希可验证）
                db.execSQL("ALTER TABLE encryption_config ADD COLUMN iterations INTEGER NOT NULL DEFAULT 600000")
            }
        }

        val MIGRATION_20_21 = object : Migration(20, 21) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 附件随记录加密：新增加密字段列（iv/auth_tag/enc_name/is_encrypted）
                db.execSQL("ALTER TABLE attachments ADD COLUMN iv TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE attachments ADD COLUMN auth_tag TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE attachments ADD COLUMN enc_name TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE attachments ADD COLUMN is_encrypted INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_21_22 = object : Migration(21, 22) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 性能：records 高频查询列补索引（增修/锁定/主时间线查询）
                db.execSQL("CREATE INDEX IF NOT EXISTS index_records_correction_of_id ON records(correction_of_id)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_records_is_locked ON records(is_locked)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_records_is_deleted_correction_of_id_created_at ON records(is_deleted, correction_of_id, created_at)")
            }
        }

        val MIGRATION_22_23 = object : Migration(22, 23) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 加密方案版本化：enc_version 区分新旧盐值编码；question_salt 存密保问题哈希的随机盐
                // 存量数据默认 enc_version=1（旧格式），新写入配置为 2 → 向后完全兼容
                db.execSQL("ALTER TABLE encryption_config ADD COLUMN enc_version INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE encryption_config ADD COLUMN question_salt TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE records ADD COLUMN enc_version INTEGER NOT NULL DEFAULT 1")
            }
        }

        val MIGRATION_23_24 = object : Migration(23, 24) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 对话录新增 AI 回复字段：新记录精确切分“我 / AI”，旧记录保持 content 原样。
                db.execSQL("ALTER TABLE records ADD COLUMN dialogue_ai_reply TEXT NOT NULL DEFAULT ''")
            }
        }

        val MIGRATION_24_25 = object : Migration(24, 25) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 已确认边记录来源记录 id（JSON 数组），供边详情页展示产生该边的记录。
                db.execSQL("ALTER TABLE candidate_edges ADD COLUMN source_record_ids TEXT NOT NULL DEFAULT ''")
            }
        }

        val MIGRATION_25_26 = object : Migration(25, 26) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 记录双向关联：linked_ids 存与当前记录存在联系的记录 id（JSON 数组）。
                db.execSQL("ALTER TABLE records ADD COLUMN linked_ids TEXT NOT NULL DEFAULT ''")
            }
        }

        /**
         * 全文检索索引（2026-11-19 换 FTS5）：
         * - 用 **trigram** 分词器：中文按 3 字窗口切，`MATCH` 支持子串，正好补上 FTS4 在中文下
         *   只会把整句当一个 token 的毛病；
         * - FTS5 自带 `bm25()` 排序，不再需要自己算。
         * 依赖自带 SQLite（requery 3.49），框架 SQLite 不保证有 FTS5。
         */
        private fun createFtsTables(db: SupportSQLiteDatabase) {
            db.execSQL("CREATE VIRTUAL TABLE IF NOT EXISTS records_fts5 USING fts5(content, notes, tokenize='trigram')")
            db.execSQL("CREATE TRIGGER IF NOT EXISTS records_fts5_ai AFTER INSERT ON records BEGIN INSERT INTO records_fts5(rowid, content, notes) VALUES (new.rowid, new.content, new.notes); END")
            db.execSQL("CREATE TRIGGER IF NOT EXISTS records_fts5_ad AFTER DELETE ON records BEGIN DELETE FROM records_fts5 WHERE rowid = old.rowid; END")
            db.execSQL("CREATE TRIGGER IF NOT EXISTS records_fts5_au AFTER UPDATE ON records BEGIN DELETE FROM records_fts5 WHERE rowid = old.rowid; INSERT INTO records_fts5(rowid, content, notes) VALUES (new.rowid, new.content, new.notes); END")
        }

        val MIGRATION_26_27 = object : Migration(26, 27) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 全文检索：content/notes 索引 + 触发器同步 + 回填存量。
                // 2026-11-19 起建的是 FTS5（trigram）；老库在 65→66 会再迁一次。
                createFtsTables(db)
                db.execSQL("INSERT OR REPLACE INTO records_fts5(rowid, content, notes) SELECT rowid, content, notes FROM records")
            }
        }

        val MIGRATION_27_28 = object : Migration(27, 28) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 秒针刻度：可命名的时间区间（start_ms/end_ms），软删 deleted_at。
                db.execSQL("CREATE TABLE IF NOT EXISTS ticks (id TEXT NOT NULL, name TEXT NOT NULL, start_ms INTEGER NOT NULL, end_ms INTEGER, deleted_at INTEGER, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL, PRIMARY KEY(id))")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_ticks_start_ms ON ticks(start_ms)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_ticks_deleted_at ON ticks(deleted_at)")
            }
        }

        val MIGRATION_28_29 = object : Migration(28, 29) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 定格切片：终结刻度最终化时快照 emotion/nodes 分数权重（frozen_at + slice_snapshot）。
                db.execSQL("ALTER TABLE ticks ADD COLUMN frozen_at INTEGER")
                db.execSQL("ALTER TABLE ticks ADD COLUMN slice_snapshot TEXT")
            }
        }

        val MIGRATION_29_30 = object : Migration(29, 30) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 节点分类层级：概念节点可归到一个分类（父项），用于组织/泛化治理。
                db.execSQL("CREATE TABLE IF NOT EXISTS concept_categories (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, name TEXT NOT NULL, created_at INTEGER NOT NULL)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_concept_categories_name ON concept_categories (name)")
                db.execSQL("ALTER TABLE memory_nodes ADD COLUMN category_id INTEGER")
            }
        }

        val MIGRATION_30_31 = object : Migration(30, 31) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 标签组：标签可归到一个分组（组织壳），用于分类浏览。
                db.execSQL("CREATE TABLE IF NOT EXISTS tag_groups (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, name TEXT NOT NULL, created_at INTEGER NOT NULL)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_tag_groups_name ON tag_groups (name)")
                db.execSQL("ALTER TABLE tags ADD COLUMN group_id INTEGER")
            }
        }

        val MIGRATION_31_32 = object : Migration(31, 32) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 刻度总结（后记）：给一段时期写长文，随时可改；与 slice_snapshot 独立，写它不触发重新定格。
                db.execSQL("ALTER TABLE ticks ADD COLUMN summary TEXT NOT NULL DEFAULT ''")
            }
        }

        val MIGRATION_32_33 = object : Migration(32, 33) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 总结的上一版留档：写长文最怕手一滑把整段弄坏，只留一版就够救回来。
                db.execSQL("ALTER TABLE ticks ADD COLUMN summary_prev TEXT NOT NULL DEFAULT ''")
            }
        }

        val MIGRATION_33_34 = object : Migration(33, 34) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 加密 v3（主密钥 + 密码包裹）：换密码不再需要重加密任何内容，只重写这一个字段。
                // 默认空串 = 仍是 v2 语义（密钥由密码现算）；真正的迁移在用户下次解锁（拿到密码）时
                // 一次原子写完成，**不在此搬任何数据**，所以本迁移是纯加列，零风险。
                db.execSQL("ALTER TABLE encryption_config ADD COLUMN wrapped_key_pw TEXT NOT NULL DEFAULT ''")
            }
        }

        val MIGRATION_34_35 = object : Migration(34, 35) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 记录间**有向带类型**关系：取代对称的 records.linked_ids（那个字段结构上表达不了方向、
                // 也没法反查"谁引用了我"）。linked_ids 本身保留不删（导出与既有 AI 规则还在读它）。
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS record_relations (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "from_id TEXT NOT NULL, to_id TEXT NOT NULL, " +
                        "type TEXT NOT NULL, created_at INTEGER NOT NULL)"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_record_relations_from_id ON record_relations(from_id)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_record_relations_to_id ON record_relations(to_id)")
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_record_relations_from_id_to_id_type " +
                        "ON record_relations(from_id, to_id, type)"
                )
                // 老数据（原来的关联 ID）→ 全部转成「更正」关系，方向按时间推：**新的记录更正老的**。
                //
                // ⚠️ 必须写成"**任一侧持有链接就成对，方向再由时间定**"：
                // 早先的写法只在"链接持有方"那一侧建边（要求 a.linked_ids 含 b 且 a 更新），
                // 于是**单边链接**（只有老的那条写着对方、新的那条没回写）整对丢掉 ——
                // 真机上就漏了 5 对。CASE 把两种遍历顺序归一到同一个 (from,to)，重复行由唯一索引 IGNORE。
                db.execSQL(LEGACY_LINKS_TO_RELATIONS_SQL)
            }
        }

        /**
         * 把老的对称字段 `linked_ids` 转成「更正」关系边（新的 → 老的）。
         * **幂等**：重复执行由 (from,to,type) 唯一索引 IGNORE 掉。
         */
        private const val LEGACY_LINKS_TO_RELATIONS_SQL =
            "INSERT OR IGNORE INTO record_relations (from_id, to_id, type, created_at) " +
                "SELECT " +
                "CASE WHEN a.created_at > b.created_at OR (a.created_at = b.created_at AND a.id > b.id) THEN a.id ELSE b.id END, " +
                "CASE WHEN a.created_at > b.created_at OR (a.created_at = b.created_at AND a.id > b.id) THEN b.id ELSE a.id END, " +
                "'CORRECT', " +
                "CASE WHEN a.created_at > b.created_at THEN a.created_at ELSE b.created_at END " +
                "FROM records a, records b " +
                "WHERE a.id != b.id " +
                "AND (a.linked_ids LIKE '%' || b.id || '%' OR b.linked_ids LIKE '%' || a.id || '%')"

        val MIGRATION_35_36 = object : Migration(35, 36) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 纯数据修补：35 那一版把**单边**的老链接漏掉了（只在"链接持有方"那侧建边）。
                // 这里补跑修正后的转换（幂等，已存在的边被 IGNORE）→ 已升级到 35 的设备也能自愈。
                db.execSQL(LEGACY_LINKS_TO_RELATIONS_SQL)
            }
        }

        val MIGRATION_36_37 = object : Migration(36, 37) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 增修与其原记录的关系应当是「**补充**」（增修是在原文上追加，不是推翻它），
                // 而 34→35 / 35→36 那两次转换把**所有**老链接一律写成了「更正」。
                // 这里把增修相关的边归位：先删掉该配对上非 CITE 的边，再按规范方向补一条
                // 「增修 → 原记录 / SUPPLEMENT」。幂等，重复跑不会多出边。
                db.execSQL(
                    "DELETE FROM record_relations WHERE type IN ('CORRECT','SUPPLEMENT') AND EXISTS (" +
                        "SELECT 1 FROM records am WHERE am.type = 'AMENDMENT' " +
                        "AND am.correction_of_id IS NOT NULL AND am.correction_of_id != '' " +
                        "AND ((am.id = record_relations.from_id AND am.correction_of_id = record_relations.to_id) " +
                        "OR (am.id = record_relations.to_id AND am.correction_of_id = record_relations.from_id)))"
                )
                db.execSQL(
                    "INSERT OR IGNORE INTO record_relations (from_id, to_id, type, created_at) " +
                        "SELECT id, correction_of_id, 'SUPPLEMENT', created_at FROM records " +
                        "WHERE type = 'AMENDMENT' AND correction_of_id IS NOT NULL AND correction_of_id != '' " +
                        "AND correction_of_id != id " +
                        // ⚠️ 必须确认原记录还在：原记录被永久删除的增修，若照样建边就会留下**悬空边**
                        // （运行时 addRelation 有端点检查，迁移里也得有 —— 第一版就是漏了这句，
                        //   真机上立刻冒出 16 条悬空边）。
                        "AND EXISTS (SELECT 1 FROM records p WHERE p.id = records.correction_of_id)"
                )
            }
        }

        val MIGRATION_37_38 = object : Migration(37, 38) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 清掉**悬空边**（任一端已不存在）：36→37 第一版给"原记录已被永久删除"的增修
                // 也建了边，真机上留下 16 条。这里统一收口，幂等；也顺手补上其它原因造成的孤儿边。
                db.execSQL(
                    "DELETE FROM record_relations " +
                        "WHERE NOT EXISTS (SELECT 1 FROM records x WHERE x.id = record_relations.from_id) " +
                        "OR NOT EXISTS (SELECT 1 FROM records y WHERE y.id = record_relations.to_id)"
                )
            }
        }

        val MIGRATION_38_39 = object : Migration(38, 39) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 真草稿（定格块）：独立表，与 records 无关 —— 草稿不进时间线/搜索/情绪/图谱。
                // 用 IF NOT EXISTS 是为了可重复执行（迁移测试也直接跑这段 SQL）。
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `draft_blocks` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`content` TEXT NOT NULL, " +
                        "`created_at` INTEGER NOT NULL, " +
                        "`updated_at` INTEGER)"
                )
            }
        }

        val MIGRATION_39_40 = object : Migration(39, 40) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 草稿块要能区分"作者写的"与"AI 整理出来的"：AI 块单独成区、不编号，
                // 且**不再作为下一次整理的输入**（否则 AI 的输出会被反复回炉）。
                // 先查表结构再加列 —— 迁移可能被重复执行（迁移测试也直接跑这段）。
                val hasColumn = db.query("PRAGMA table_info(draft_blocks)").use { c ->
                    val nameIdx = c.getColumnIndex("name")
                    var found = false
                    while (c.moveToNext()) {
                        if (nameIdx >= 0 && c.getString(nameIdx) == "is_ai") { found = true; break }
                    }
                    found
                }
                if (!hasColumn) {
                    db.execSQL("ALTER TABLE draft_blocks ADD COLUMN is_ai INTEGER NOT NULL DEFAULT 0")
                }
            }
        }

        val MIGRATION_40_41 = object : Migration(40, 41) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 草稿块从"是不是 AI 写的"（布尔）升级成"四类"（kind）：
                // MINE（作者写的，唯一编号）/ AI（整理产出）/ QA（质疑问答）/ DISCUSS（讨论）。
                // 用**重建表**的方式换列（小表、行数很少），把 is_ai=1 的行转成 kind='AI'。
                // 重建的建表语句必须与实体的期望完全一致，否则 Room 的 schema 校验会当场报错。
                val alreadyMigrated = db.query("PRAGMA table_info(draft_blocks)").use { c ->
                    val nameIdx = c.getColumnIndex("name")
                    var hasKind = false
                    while (c.moveToNext()) {
                        if (nameIdx >= 0 && c.getString(nameIdx) == "kind") { hasKind = true; break }
                    }
                    hasKind
                }
                if (!alreadyMigrated) {
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS `draft_blocks_new` (" +
                            "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                            "`content` TEXT NOT NULL, " +
                            "`created_at` INTEGER NOT NULL, " +
                            "`updated_at` INTEGER, " +
                            "`kind` TEXT NOT NULL DEFAULT 'MINE')"
                    )
                    db.execSQL(
                        "INSERT OR REPLACE INTO `draft_blocks_new` (id, content, created_at, updated_at, kind) " +
                            "SELECT id, content, created_at, updated_at, " +
                            "CASE WHEN is_ai = 1 THEN 'AI' ELSE 'MINE' END FROM `draft_blocks`"
                    )
                    db.execSQL("DROP TABLE `draft_blocks`")
                    db.execSQL("ALTER TABLE `draft_blocks_new` RENAME TO `draft_blocks`")
                }
            }
        }

        val MIGRATION_41_42 = object : Migration(41, 42) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 草稿从"一本"变成"多本"：新建 drafts 表，draft_blocks 加 draft_id。
                // 老数据（此前所有块）统一归到 **id=1 的那本**，名字留空让他自己改
                //（用户要求：名字要自己起，不做自动取标题）。重建 draft_blocks 以带上新列。
                val hasDrafts = db.query("SELECT name FROM sqlite_master WHERE type='table' AND name='drafts'").use { c ->
                    c.moveToFirst()
                }
                if (!hasDrafts) {
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS `drafts` (" +
                            "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                            "`name` TEXT NOT NULL, " +
                            "`created_at` INTEGER NOT NULL, " +
                            "`updated_at` INTEGER NOT NULL, " +
                            "`archived` INTEGER NOT NULL DEFAULT 0)"
                    )
                    db.execSQL(
                        "INSERT OR REPLACE INTO `drafts` (id, name, created_at, updated_at, archived) " +
                            "VALUES (1, '', " + System.currentTimeMillis() + ", " + System.currentTimeMillis() + ", 0)"
                    )
                }
                val hasDraftId = db.query("PRAGMA table_info(draft_blocks)").use { c ->
                    val nameIdx = c.getColumnIndex("name")
                    var found = false
                    while (c.moveToNext()) {
                        if (nameIdx >= 0 && c.getString(nameIdx) == "draft_id") { found = true; break }
                    }
                    found
                }
                if (!hasDraftId) {
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS `draft_blocks_new` (" +
                            "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                            "`content` TEXT NOT NULL, " +
                            "`created_at` INTEGER NOT NULL, " +
                            "`updated_at` INTEGER, " +
                            "`kind` TEXT NOT NULL DEFAULT 'MINE', " +
                            "`draft_id` INTEGER NOT NULL DEFAULT 1)"
                    )
                    db.execSQL(
                        "INSERT OR REPLACE INTO `draft_blocks_new` (id, content, created_at, updated_at, kind, draft_id) " +
                            "SELECT id, content, created_at, updated_at, kind, 1 FROM `draft_blocks`"
                    )
                    db.execSQL("DROP TABLE `draft_blocks`")
                    db.execSQL("ALTER TABLE `draft_blocks_new` RENAME TO `draft_blocks`")
                }
            }
        }

        val MIGRATION_42_43 = object : Migration(42, 43) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 草稿置顶（照 DeepSeek 侧栏的「置顶」分组）：加一列，旧数据默认不置顶。
                val hasPinned = db.query("PRAGMA table_info(drafts)").use { c ->
                    val nameIdx = c.getColumnIndex("name")
                    var found = false
                    while (c.moveToNext()) {
                        if (nameIdx >= 0 && c.getString(nameIdx) == "pinned") { found = true; break }
                    }
                    found
                }
                if (!hasPinned) {
                    db.execSQL("ALTER TABLE drafts ADD COLUMN pinned INTEGER NOT NULL DEFAULT 0")
                }
            }
        }

        /**
         * 43→44：记录的**转录来源**（草稿 → 随笔/对话录）。
         *
         * 只加两列、可空、旧数据一律 NULL（= 不是转录来的）→ 不影响任何既有查询；
         * 记录卡片上那个"回到草稿"的小图标只在这两列有值时出现。
         */
        val MIGRATION_43_44 = object : Migration(43, 44) {
            override fun migrate(db: SupportSQLiteDatabase) {
                fun hasColumn(table: String, column: String): Boolean =
                    db.query("PRAGMA table_info($table)").use { c ->
                        val nameIdx = c.getColumnIndex("name")
                        var found = false
                        while (c.moveToNext()) {
                            if (nameIdx >= 0 && c.getString(nameIdx) == column) { found = true; break }
                        }
                        found
                    }
                if (!hasColumn("records", "source_draft_id")) {
                    db.execSQL("ALTER TABLE records ADD COLUMN source_draft_id INTEGER")
                }
                if (!hasColumn("records", "source_block_id")) {
                    db.execSQL("ALTER TABLE records ADD COLUMN source_block_id INTEGER")
                }
            }
        }

        /**
         * 44→45：**删掉概念网络的"边"**（用户 2026-10-12：「把概念网络相关东西删了吧……和节点没关系」）。
         *
         * 只删网络那一半：`candidate_edges`（AI 断言的边 + 共现派生的边）整表去掉。
         * **节点那一半一个不碰**：`memory_nodes`、`record_memory_nodes`、`concept_categories`、
         * 每条记录的节点挂载、节点合并/重命名/否决表、导出的 `node_hierarchy` 全部保留
         *（它们是**索引**：点概念看记录、按概念检索、外部 AI 看概念体系）。
         * 为什么删：边只有"共现"和"当年 AI 随手写"两种来源，共现不是关系、AI 边是垃圾；
         * 而精确节点天生不可关系化（只指认一次的观察没有"普遍性"可言）→ 图必然一地散点。
         */
        val MIGRATION_44_45 = object : Migration(44, 45) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP TABLE IF EXISTS candidate_edges")
            }
        }

        /**
         * 45→46：**删掉本地音乐**（用户 2026-10-12：「本地音乐的也是，那个基本没用」）。
         *
         * `audio_items` 只是"本地音频文件清单"（uri + 文件名 + 时长），文件本体在外部存储。
         * 播放器/前台服务/通知一起删掉；语音实验室的 TTS 与它无关，不受影响。
         */
        val MIGRATION_45_46 = object : Migration(45, 46) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP TABLE IF EXISTS audio_items")
            }
        }

        /**
         * 46→47：**表盘**（用户 2026-10-19：「想做一个大"刻度"，就叫做"表盘"吧，包络刻度」）。
         *
         * 两张新表、老数据一行不动：
         * - `dials`：一段时期的框（名字 / 起止（终点可空=进行中）/ 总结 + 上一版 / 手动定格的切片快照）；
         * - `dial_ticks`：表盘 ↔ 刻度（多对多）—— 一个刻度可以被两个时期同时框住。
         *
         * 为什么不给 `ticks` 加 `dial_id`：表盘是观察层，时期天然重叠（「今年这 11 个月」⊃「考研那几个月」），
         * 单父会逼用户二选一（用户 2026-10-19 选的多父）。
         * 老安装升级后**一个表盘都没有**，既有刻度照旧工作、显示为"未归属"。
         */
        val MIGRATION_46_47 = object : Migration(46, 47) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `dials` (" +
                        "`id` TEXT NOT NULL, " +
                        "`name` TEXT NOT NULL, " +
                        "`start_ms` INTEGER NOT NULL, " +
                        "`end_ms` INTEGER, " +
                        "`frozen_at` INTEGER, " +
                        "`slice_snapshot` TEXT, " +
                        "`summary` TEXT NOT NULL, " +
                        "`summary_prev` TEXT NOT NULL, " +
                        "`created_at` INTEGER NOT NULL, " +
                        "`updated_at` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`id`))"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `dial_ticks` (" +
                        "`dial_id` TEXT NOT NULL, " +
                        "`tick_id` TEXT NOT NULL, " +
                        "PRIMARY KEY(`dial_id`, `tick_id`))"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_dial_ticks_tick_id` ON `dial_ticks` (`tick_id`)")
            }
        }

        /**
         * 47→48：**删掉表盘上的「打算盘」定格**（用户 2026-10-20：「定格的打算盘完全是没用的功能，
         * 不知道你设计那么多没用的东西干吗」）。
         *
         * 表盘只是"一段时期的框"，它自己没有内容可锁 —— 定格在这里没有任何语义，
         * 连同"改起止要清快照"那条规矩一起作废。SQLite 的 `DROP COLUMN` 在老系统上没有，按标准做法整表重建。
         */
        val MIGRATION_47_48 = object : Migration(47, 48) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `dials_new` (" +
                        "`id` TEXT NOT NULL, `name` TEXT NOT NULL, `start_ms` INTEGER NOT NULL, `end_ms` INTEGER, " +
                        "`summary` TEXT NOT NULL, `summary_prev` TEXT NOT NULL, " +
                        "`created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, PRIMARY KEY(`id`))"
                )
                db.execSQL(
                    "INSERT INTO `dials_new` (`id`,`name`,`start_ms`,`end_ms`,`summary`,`summary_prev`,`created_at`,`updated_at`) " +
                        "SELECT `id`,`name`,`start_ms`,`end_ms`,`summary`,`summary_prev`,`created_at`,`updated_at` FROM `dials`"
                )
                db.execSQL("DROP TABLE `dials`")
                db.execSQL("ALTER TABLE `dials_new` RENAME TO `dials`")
            }
        }

        val MIGRATION_48_49 = object : Migration(48, 49) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `record_vectors` (" +
                        "`record_id` TEXT NOT NULL, `chunk_index` INTEGER NOT NULL, `model` TEXT NOT NULL, " +
                        "`dim` INTEGER NOT NULL, `vec` BLOB NOT NULL, `text_hash` TEXT NOT NULL, " +
                        "`updated_at` INTEGER NOT NULL, PRIMARY KEY(`record_id`, `chunk_index`))"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_record_vectors_model` ON `record_vectors` (`model`)")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `embedding_state` (" +
                        "`record_id` TEXT NOT NULL, `status` TEXT NOT NULL, `model` TEXT, " +
                        "`error` TEXT, `updated_at` INTEGER NOT NULL, PRIMARY KEY(`record_id`))"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `topics` (" +
                        "`id` TEXT NOT NULL, `snapshot_id` TEXT NOT NULL, `name` TEXT NOT NULL, " +
                        "`name_source` TEXT NOT NULL, `centroid` BLOB NOT NULL, `member_count` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`id`))"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `record_topics` (" +
                        "`record_id` TEXT NOT NULL, `topic_id` TEXT NOT NULL, `score` REAL NOT NULL, " +
                        "PRIMARY KEY(`record_id`, `topic_id`))"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_record_topics_topic_id` ON `record_topics` (`topic_id`)")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `topic_overrides` (" +
                        "`centroid_hash` TEXT NOT NULL, `name` TEXT NOT NULL, `updated_at` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`centroid_hash`))"
                )
            }
        }
        val MIGRATION_49_50 = object : Migration(49, 50) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `topics` ADD COLUMN `keywords` TEXT NOT NULL DEFAULT ''")
            }
        }
        /** 关联边（2026-11-05）：为什么相关、相似度、来源、状态。 */
        /** 关联边（2026-11-05）：为什么相关、相似度、来源、状态。 */

        /** 51 → 52：来源系统（表 + 记录外键 + 预置五条）。 */
        val MIGRATION_51_52 = object : Migration(51, 52) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `origins` (" +
                        "`id` TEXT NOT NULL, `name` TEXT NOT NULL, `weight` REAL NOT NULL DEFAULT 1.0, " +
                        "`use_cluster` INTEGER NOT NULL DEFAULT 1, `use_link` INTEGER NOT NULL DEFAULT 1, " +
                        "`use_stats` INTEGER NOT NULL DEFAULT 1, `sort_order` INTEGER NOT NULL DEFAULT 0, " +
                        "`builtin` INTEGER NOT NULL DEFAULT 0, `created_at` INTEGER NOT NULL DEFAULT 0, " +
                        "PRIMARY KEY(`id`))"
                )
                db.execSQL("ALTER TABLE `records` ADD COLUMN `origin_id` TEXT")
                val now = System.currentTimeMillis()
                val rows = listOf(
                    Triple("origin_original", "原创", 1.0),
                    Triple("origin_dialogue", "对话", 0.8),
                    Triple("origin_rewrite", "摘录再创作", 0.7),
                    Triple("origin_transcript", "转录", 0.5),
                    Triple("origin_ai", "转录（AI）", 0.3)
                )
                rows.forEachIndexed { i, (id, name, w) ->
                    db.execSQL(
                        "INSERT OR REPLACE INTO `origins` (id,name,weight,use_cluster,use_link,use_stats,sort_order,builtin,created_at) VALUES (?,?,?,?,?,?,?,1,?)",
                        arrayOf<Any?>(id, name, w, 1, 1, 1, i, now)
                    )
                }
            }
        }

        /** 52 → 53：来源的三个用途各自一个权重。
         *
         * ⚠️ 直接 ALTER 加三列会失败：Room 校验的是**列集合**，旧的 weight/use_* 还在表里就成了多余列，
         * 启动时报 "Migration didn't properly handle: origins"。所以这里**重建表**：改名 → 建新 → 拷数据 → 删旧。
         */
        /** 2026-11-13：主题两级化。加两列，旧主题保持 level=0 / parent_id=null。 */
        /** 2026-11-14：分区（保存下来的筛选条件）。只存自定义的，内置两个由代码给出。 */
        val MIGRATION_57_58 = object : Migration(57, 58) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `zones` (" +
                        "`id` TEXT NOT NULL, `name` TEXT NOT NULL, `condition` TEXT NOT NULL, " +
                        "`sort` INTEGER NOT NULL, `builtin` INTEGER NOT NULL, " +
                        "`created_at` INTEGER NOT NULL, PRIMARY KEY(`id`))"
                )
            }
        }

        /**
         * 2026-11-15：动作表（agent 连接 v2 的地基）。
         *
         * 批准 / 回执 / 撤回都该以"动作"为单位，而不是逐条命令。
         * 纯新增一张表，不动任何现有表 —— 旧版本的数据照常读。
         */
        val MIGRATION_59_60 = object : Migration(59, 60) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `agent_actions` (" +
                        "`id` TEXT NOT NULL, `actor` TEXT NOT NULL, `title` TEXT NOT NULL, " +
                        "`command_ids` TEXT NOT NULL, `status` TEXT NOT NULL, " +
                        "`requires_confirm` INTEGER NOT NULL, `snapshot` TEXT NOT NULL, " +
                        "`note` TEXT NOT NULL, `created_at` INTEGER NOT NULL, " +
                        "`executed_at` INTEGER NOT NULL, `reverted_at` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`id`))"
                )
            }
        }

        /**
         * 2026-11-17：来源系统的「关联准入阈值」。
         * 与结构权重分工：阈值管进不进候选，权重管进了排第几。
         * 默认值按真机 321 条记录的 top8 相似度分布校准（见 Origin.builtins 注释）。
         */
        /**
 * 63 → 64（2026-11-18）：概念/节点系统整体下线。
 * 四张表连同分类一并删除——它们已经没有任何界面入口，留着只是私人数据，
 * 会妨碍以后接"真主题"时的测试。删除前已导出备份到
 * `~/Documents/memore-concepts-backup-20261118.json`。
 */
val MIGRATION_68_69 = object : Migration(68, 69) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // 2026-11-19：revive 早先没限定 source='AI'，把手动边（USER）当成 AI 边复活，
        // 并写进了 AI 的 why/引文。手动建边永远是空 why/空引文（RecordRepository.createRelation），
        // 所以"USER 且有引文"只可能是被污染的，改回 AI。
        db.execSQL("UPDATE `record_relations` SET `source` = 'AI' " +
            "WHERE `source` = 'USER' AND (`quote_a` <> '' OR `quote_b` <> '')")
    }
}
val MIGRATION_70_71 = object : Migration(70, 71) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // 2026-11-22：回看独立落库（和总结分开显示，重复保存直接覆盖）。
        db.execSQL("ALTER TABLE `ticks` ADD COLUMN `review` TEXT NOT NULL DEFAULT ''")
    }
}
val MIGRATION_69_70 = object : Migration(69, 70) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // 2026-11-22：创建 AI 札记时可以在同一步挂标签，绕过了 aiNoteRejection
        // （它只查已存在的 record_ids）。代码已堵，这里把历史遗留的标签清掉；
        // AI 札记按规矩只支持正文/附件/加密/语音。
        db.execSQL(
            "DELETE FROM `record_tags` WHERE `record_id` IN " +
                "(SELECT `id` FROM `records` WHERE `type` = 'AI_NOTE')"
        )
        db.execSQL(
            "UPDATE `tags` SET `usage_count` = (" +
                "SELECT COUNT(*) FROM `record_tags` rt JOIN `records` r ON rt.`record_id` = r.`id` " +
                "WHERE rt.`tag_id` = `tags`.`id` AND r.`is_deleted` = 0)"
        )
    }
}
val MIGRATION_67_68 = object : Migration(67, 68) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // 2026-11-19：分析历史（不覆盖）+ 关联边有效期（失效不删除）+ 输入指纹（判断过期）。
        db.execSQL("CREATE TABLE IF NOT EXISTS `analysis_versions` (" +
            "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `record_id` TEXT NOT NULL, " +
            "`created_at` INTEGER NOT NULL, `model` TEXT NOT NULL DEFAULT '', " +
            "`prompt_version` TEXT NOT NULL DEFAULT '', `input_hash` TEXT NOT NULL DEFAULT '', " +
            "`summary` TEXT NOT NULL DEFAULT '', `emotion_json` TEXT NOT NULL DEFAULT '[]', " +
            "`status` TEXT NOT NULL DEFAULT 'OK', `reason` TEXT NOT NULL DEFAULT '', " +
            "FOREIGN KEY(`record_id`) REFERENCES `records`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_analysis_versions_record_id ON analysis_versions(record_id)")
        db.execSQL("ALTER TABLE `records` ADD COLUMN `analysis_input_hash` TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE `record_relations` ADD COLUMN `valid_from` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE `record_relations` ADD COLUMN `valid_to` INTEGER")
        db.execSQL("UPDATE `record_relations` SET `valid_from` = `created_at` WHERE `valid_from` = 0")
    }
}
val MIGRATION_66_67 = object : Migration(66, 67) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // 2026-11-19：锚点表。记录里提到的 人/作品/地点/事件，带原句，供关联与检索用。
        db.execSQL("CREATE TABLE IF NOT EXISTS `record_entities` (" +
            "`record_id` TEXT NOT NULL, `name` TEXT NOT NULL, `kind` TEXT NOT NULL DEFAULT 'OTHER', " +
            "`quote` TEXT NOT NULL DEFAULT '', `created_at` INTEGER NOT NULL, " +
            "PRIMARY KEY(`record_id`, `name`, `kind`), " +
            "FOREIGN KEY(`record_id`) REFERENCES `records`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_record_entities_name ON record_entities(name)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_record_entities_record_id ON record_entities(record_id)")
    }
}
val MIGRATION_65_66 = object : Migration(65, 66) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // 2026-11-19：FTS4 -> FTS5（trigram 分词器）。
        // FTS4 在中文下只会把整句当一个 token，搜索基本不可用；FTS5 的 trigram 支持子串并自带 bm25。
        createFtsTables(db)
        db.execSQL("INSERT OR REPLACE INTO records_fts5(rowid, content, notes) SELECT rowid, content, notes FROM records")
        db.execSQL("DROP TRIGGER IF EXISTS records_fts_ai")
        db.execSQL("DROP TRIGGER IF EXISTS records_fts_ad")
        db.execSQL("DROP TRIGGER IF EXISTS records_fts_au")
        db.execSQL("DROP TABLE IF EXISTS records_fts")
    }
}
val MIGRATION_64_65 = object : Migration(64, 65) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // 2026-11-19：关联边保留"凭什么连"的两句原句。
        // 老边（quote_a/quote_b 为空）界面回落到 why，不丢内容。
        db.execSQL("ALTER TABLE `record_relations` ADD COLUMN `quote_a` TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE `record_relations` ADD COLUMN `quote_b` TEXT NOT NULL DEFAULT ''")
    }
}
val MIGRATION_63_64 = object : Migration(63, 64) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("DROP TABLE IF EXISTS record_memory_nodes")
        db.execSQL("DROP TABLE IF EXISTS memory_nodes")
        db.execSQL("DROP TABLE IF EXISTS concept_categories")
        db.execSQL("DROP TABLE IF EXISTS rejected_merges")
    }
}
val MIGRATION_62_63 = object : Migration(62, 63) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `origins` ADD COLUMN `link_threshold` REAL NOT NULL DEFAULT 0.70")
                db.execSQL("UPDATE `origins` SET `link_threshold` = 0.68 WHERE id = 'origin_rewrite'")
                db.execSQL("UPDATE `origins` SET `link_threshold` = 0.72 WHERE id = 'origin_dialogue'")
                db.execSQL("UPDATE `origins` SET `link_threshold` = 0.78 WHERE id = 'origin_transcript'")
                db.execSQL("UPDATE `origins` SET `link_threshold` = 1.0  WHERE id = 'origin_ai'")
            }
        }

        /** 2026-11-17：预设带上区域（用户要求 zone 也进预设）。 */
        val MIGRATION_61_62 = object : Migration(61, 62) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `record_presets` ADD COLUMN `zone_ids` TEXT NOT NULL DEFAULT '[]'")
            }
        }

        /** 2026-11-17：新建记录的预设（类型/来源/标签/协议各存一份）。 */
        val MIGRATION_60_61 = object : Migration(60, 61) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `record_presets` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`name` TEXT NOT NULL, `type` TEXT NOT NULL, `origin_id` TEXT, " +
                        "`tag_ids` TEXT NOT NULL, `protocol_ids` TEXT NOT NULL, " +
                        "`created_at` INTEGER NOT NULL, `last_used_at` INTEGER NOT NULL)"
                )
            }
        }

        /** 2026-11-14：分区成员（手动型分区靠它装记录）。锚点仍然用 records.is_anchored。 */
        val MIGRATION_58_59 = object : Migration(58, 59) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `zone_records` (" +
                        "`zone_id` TEXT NOT NULL, `record_id` TEXT NOT NULL, " +
                        "`added_at` INTEGER NOT NULL, PRIMARY KEY(`zone_id`, `record_id`))"
                )
            }
        }

        /** 2026-11-13：命令支持批次（一次批准一整批）。 */
        val MIGRATION_56_57 = object : Migration(56, 57) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `agent_commands` ADD COLUMN `batch_id` TEXT NOT NULL DEFAULT ''")
            }
        }

        /** 2026-11-13：agent 写命令队列。 */
        val MIGRATION_55_56 = object : Migration(55, 56) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `agent_commands` (" +
                        "`id` TEXT NOT NULL, `kind` TEXT NOT NULL, `payload` TEXT NOT NULL, " +
                        "`status` TEXT NOT NULL, `created_at` INTEGER NOT NULL, " +
                        "`executed_at` INTEGER NOT NULL, `note` TEXT NOT NULL, " +
                        "PRIMARY KEY(`id`))"
                )
            }
        }

        /** 2026-11-13：主题加代表句 summary（替代关键词做解释）。 */
        val MIGRATION_54_55 = object : Migration(54, 55) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `topics` ADD COLUMN `summary` TEXT NOT NULL DEFAULT ''")
            }
        }

        val MIGRATION_53_54 = object : Migration(53, 54) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `topics` ADD COLUMN `parent_id` TEXT")
                db.execSQL("ALTER TABLE `topics` ADD COLUMN `level` INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_52_53 = object : Migration(52, 53) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `origins` RENAME TO `origins_old`")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `origins` (" +
                        "`id` TEXT NOT NULL, `name` TEXT NOT NULL, " +
                        "`w_cluster` REAL NOT NULL DEFAULT 1.0, `w_link` REAL NOT NULL DEFAULT 1.0, " +
                        "`w_stats` REAL NOT NULL DEFAULT 1.0, `sort_order` INTEGER NOT NULL DEFAULT 0, " +
                        "`builtin` INTEGER NOT NULL DEFAULT 0, `created_at` INTEGER NOT NULL DEFAULT 0, " +
                        "PRIMARY KEY(`id`))"
                )
                db.execSQL(
                    "INSERT INTO `origins` (id,name,w_cluster,w_link,w_stats,sort_order,builtin,created_at) " +
                        "SELECT id, name, " +
                        "CASE WHEN use_cluster = 1 THEN weight ELSE 0 END, " +
                        "CASE WHEN use_link = 1 THEN weight ELSE 0 END, " +
                        "CASE WHEN use_stats = 1 THEN weight ELSE 0 END, " +
                        "sort_order, builtin, created_at FROM `origins_old`"
                )
                db.execSQL("DROP TABLE `origins_old`")
            }
        }

        val MIGRATION_50_51 = object : Migration(50, 51) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `record_relations` ADD COLUMN `why` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `record_relations` ADD COLUMN `score` REAL NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `record_relations` ADD COLUMN `source` TEXT NOT NULL DEFAULT 'USER'")
                db.execSQL("ALTER TABLE `record_relations` ADD COLUMN `status` TEXT NOT NULL DEFAULT 'ACTIVE'")
            }
        }
        fun create(context: Context): AppDatabase {
            return Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                DB_NAME
            )
                // 2026-11-19：自带 SQLite（FTS5 + trigram）。框架 SQLite 在 minSdk 26 不保证 FTS5，
                // 中文也没有可用分词器；这里换 openHelperFactory，.db 文件格式不变。
                .openHelperFactory(io.requery.android.database.sqlite.RequerySQLiteOpenHelperFactory())
                .addMigrations(MIGRATION_70_71, MIGRATION_69_70, MIGRATION_68_69, MIGRATION_67_68, MIGRATION_66_67, MIGRATION_65_66, MIGRATION_64_65, MIGRATION_63_64, MIGRATION_62_63, MIGRATION_61_62, MIGRATION_60_61, MIGRATION_59_60, MIGRATION_58_59, MIGRATION_57_58, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15, MIGRATION_15_16, MIGRATION_16_17, MIGRATION_17_18, MIGRATION_18_19, MIGRATION_19_20, MIGRATION_20_21, MIGRATION_21_22, MIGRATION_22_23, MIGRATION_23_24, MIGRATION_24_25, MIGRATION_25_26, MIGRATION_26_27, MIGRATION_27_28, MIGRATION_28_29, MIGRATION_29_30, MIGRATION_30_31, MIGRATION_31_32, MIGRATION_32_33, MIGRATION_33_34, MIGRATION_34_35, MIGRATION_35_36, MIGRATION_36_37, MIGRATION_37_38, MIGRATION_38_39, MIGRATION_39_40, MIGRATION_40_41, MIGRATION_41_42, MIGRATION_42_43, MIGRATION_43_44, MIGRATION_44_45, MIGRATION_45_46, MIGRATION_46_47, MIGRATION_47_48, MIGRATION_48_49, MIGRATION_49_50, MIGRATION_50_51, MIGRATION_51_52, MIGRATION_52_53, MIGRATION_53_54, MIGRATION_54_55, MIGRATION_55_56, MIGRATION_56_57)
                .addCallback(object : RoomDatabase.Callback() {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        super.onCreate(db)
                        // 全新安装：Room 只建声明实体表，FTS 表/触发器需在此补建（新库无记录，无需回填）。
                        createFtsTables(db)
                    }
                })
                .build()
        }

        @Volatile private var instance: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return instance ?: synchronized(this) {
                instance ?: create(context.applicationContext).also { instance = it }
            }
        }
    }
}



