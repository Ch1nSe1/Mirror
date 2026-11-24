package memore.app.data.vector

import memore.app.data.local.AppDatabase
import memore.app.data.model.EmbeddingState
import memore.app.data.model.Record
import memore.app.data.model.RecordVector
import memore.app.data.repository.RecordRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 向量索引服务（设计文档 §6）。
 *
 *  - [mark]：记录被写过（新增/编辑/增修/恢复），2 秒去抖后重算
 *  - [syncMissing]：启动时补齐（未索引 / 模型变了 / 文本变了 / 失败重试）
 *  - 跳过：加密记录、文本过短
 *  - 增修：并入父记录一起算（不为增修单独建向量）
 */
class VectorIndexService(
    private val db: AppDatabase,
    private val recordRepository: RecordRepository,
    private val provider: EmbeddingProvider?
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val started = AtomicBoolean(false)
    private val queue = Channel<String>(capacity = Channel.UNLIMITED)

    val available: Boolean get() = provider != null
    val modelName: String get() = provider?.model ?: ""

    companion object {
        /** 少于这个字数不建向量（噪声） */
        const val MIN_TEXT_LENGTH = 10
        /** 保存后去抖窗口 */
        const val DEBOUNCE_MS = 2000L
        /** 批量回填时每组记录数（一次嵌入调用处理这么多条） */
        const val BATCH_SIZE = 8

        /** float32 小端 → FloatArray（与 floatsToBytes 对称） */
        fun bytesToFloats(bytes: ByteArray): FloatArray {
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            return FloatArray(bytes.size / 4) { buf.getFloat() }
        }
    }

    fun mark(recordId: String) {
        if (!available) return
        queue.trySend(recordId)
    }

    /** 只启动一次；不阻塞调用方。 */
    fun ensureStarted() {
        if (!available) return
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            // 消费者：攒一会儿再算，避免连续编辑反复嵌入
            for (first in queue) {
                val pending = linkedSetOf(first)
                delay(DEBOUNCE_MS)
                while (true) {
                    val next = queue.tryReceive().getOrNull() ?: break
                    pending += next
                }
                for (id in pending) {
                    runCatching { indexRecord(id) }
                }
            }
        }
        scope.launch {
            val n = runCatching { syncMissing() }
            android.util.Log.i("Vector", "syncMissing -> $n, stats=${runCatching { stats() }.getOrNull()}")
        }
    }

    /**
     * 启动补齐（批量版）：先一次性判定谁需要算，再按 8 条一组做嵌入。
     * 逐条算在小样本上没问题，但 178 条时会因为每条一次张量构建 + 一次事务而变慢。
     */
    suspend fun syncMissing(limit: Int = 2000, force: Boolean = false, onProgress: ((Int) -> Unit)? = null): Int {
        val p = provider ?: return 0
        val dao = db.vectorDao()
        runCatching { dao.pruneOrphanStates(); dao.pruneOrphanVectors() }

        // 2026-11-21：AI 札记不建向量（它只有正文/附件/加密/语音，不参与检索与结构）。
        val records = recordRepository.getAllActiveRecords()
            .filter { it.type != memore.app.data.model.RecordType.AI_NOTE }
            .take(limit)
        if (records.isEmpty()) return 0
        val states = dao.allStates().associateBy { it.recordId }
        val amendments = runCatching {
            recordRepository.getAmendmentsForRecords(records.map { it.id })
                .groupBy { it.correctionOfId ?: "" }
        }.getOrDefault(emptyMap())

        val pending = ArrayList<Pair<Record, String>>()
        for (r in records) {
            val state = states[r.id]
            val needs = force || when {
                state == null -> true
                // 加密记录的状态以「加密」为准（历史上可能被误记成"过短"）
                r.isLocked && state.status != EmbeddingState.STATUS_SKIPPED_LOCKED -> true
                state.status == EmbeddingState.STATUS_FAILED -> true
                state.status == EmbeddingState.STATUS_SKIPPED_LOCKED && !r.isLocked -> true
                state.status == EmbeddingState.STATUS_SKIPPED_SHORT && r.content.length >= MIN_TEXT_LENGTH -> true
                state.status == EmbeddingState.STATUS_OK && state.model != p.model -> true
                state.status == EmbeddingState.STATUS_OK -> {
                    val expected = TextChunker.hash(p.model, buildText(r, amendments[r.id].orEmpty()))
                    dao.byRecord(r.id).firstOrNull()?.textHash != expected
                }
                else -> false
            }
            if (needs) pending += r to buildText(r, amendments[r.id].orEmpty())
        }
        if (pending.isEmpty()) return 0

        var processed = 0
        for (group in pending.chunked(BATCH_SIZE)) {
            mutex.withLock {
                val now = System.currentTimeMillis()
                val chunked = ArrayList<Pair<Record, List<String>>>(group.size)
                val flat = ArrayList<String>()
                for ((record, text) in group) {
                    if (record.isLocked) {
                        dao.deleteByRecord(record.id)
                        dao.upsertState(EmbeddingState(record.id, EmbeddingState.STATUS_SKIPPED_LOCKED, p.model))
                        continue
                    }
                    if (text.trim().length < MIN_TEXT_LENGTH) {
                        dao.deleteByRecord(record.id)
                        dao.upsertState(EmbeddingState(record.id, EmbeddingState.STATUS_SKIPPED_SHORT, p.model))
                        continue
                    }
                    val chunks = TextChunker.chunk(text)
                    if (chunks.isEmpty()) continue
                    chunked += record to chunks
                    flat += chunks
                }
                if (flat.isEmpty()) return@withLock

                val vectors = runCatching { p.embed(flat, isQuery = false) }.getOrElse { e ->
                    chunked.forEach { (record, _) ->
                        dao.upsertState(
                            EmbeddingState(
                                recordId = record.id,
                                status = EmbeddingState.STATUS_FAILED,
                                model = p.model,
                                error = e.message?.take(200)
                            )
                        )
                    }
                    return@withLock
                }

                var cursor = 0
                for ((record, chunks) in chunked) {
                    val rows = chunks.mapIndexed { index, _ ->
                        RecordVector(
                            recordId = record.id,
                            chunkIndex = index,
                            model = p.model,
                            dim = p.dim,
                            vec = floatsToBytes(vectors[cursor + index]),
                            textHash = TextChunker.hash(p.model, buildText(record, amendments[record.id].orEmpty())),
                            updatedAt = now
                        )
                    }
                    cursor += chunks.size
                    dao.upsertAll(rows)
                    dao.upsertState(EmbeddingState(record.id, EmbeddingState.STATUS_OK, p.model, updatedAt = now))
                }
            }
            processed += group.size
            android.util.Log.i("Vector", "补齐进度 $processed/${pending.size}")
            onProgress?.invoke(processed)
    }
        return processed
    }

    /** 重算一条记录（含增修文本）。 */
    suspend fun indexRecord(recordId: String): EmbeddingState? {
        val p = provider ?: return null
        return mutex.withLock {
            val dao = db.vectorDao()
            val record = recordRepository.getRecordById(recordId)
            if (record == null || record.isDeleted) {
                dao.deleteByRecord(recordId)
                dao.deleteState(recordId)
                return@withLock null
            }

            // 加密记录：**明文根本不在库里**（content 为空，密文在 encrypted_data），无从嵌入。
            // 所以这不是隐私取舍，是能力边界；要搜就得解锁后重建索引，暂不做。
            if (record.isLocked) {
                dao.deleteByRecord(recordId)
                val state = EmbeddingState(
                    recordId = recordId,
                    status = EmbeddingState.STATUS_SKIPPED_LOCKED,
                    model = p.model
                )
                dao.upsertState(state)
                return@withLock state
            }

            val text = buildText(record)
            if (text.trim().length < MIN_TEXT_LENGTH) {
                dao.deleteByRecord(recordId)
                val state = EmbeddingState(
                    recordId = recordId,
                    status = EmbeddingState.STATUS_SKIPPED_SHORT,
                    model = p.model
                )
                dao.upsertState(state)
                return@withLock state
            }

            val chunks = TextChunker.chunk(text)
            if (chunks.isEmpty()) {
                dao.deleteByRecord(recordId)
                return@withLock null
            }

            return@withLock try {
                val vectors = p.embed(chunks, isQuery = false)
                val now = System.currentTimeMillis()
                val rows = chunks.mapIndexed { index, chunk ->
                    RecordVector(
                        recordId = recordId,
                        chunkIndex = index,
                        model = p.model,
                        dim = p.dim,
                        vec = floatsToBytes(vectors[index]),
                        textHash = TextChunker.hash(p.model, text),
                        updatedAt = now
                    )
                }
                dao.upsertAll(rows)
                val state = EmbeddingState(
                    recordId = recordId,
                    status = EmbeddingState.STATUS_OK,
                    model = p.model,
                    updatedAt = now
                )
                dao.upsertState(state)
                state
            } catch (e: Exception) {
                val state = EmbeddingState(
                    recordId = recordId,
                    status = EmbeddingState.STATUS_FAILED,
                    model = p.model,
                    error = e.message?.take(200)
                )
                dao.upsertState(state)
                state
            }
        }
    }

    /** 删除索引（记录被删时调用）。 */
    suspend fun forget(recordId: String) {
        runCatching {
            db.vectorDao().deleteByRecord(recordId)
            db.vectorDao().deleteState(recordId)
        }
    }

    /**
     * 全量重建（改）：只清状态不清向量，边重建边替换；中途退出不留空洞。
     */
    suspend fun rebuildAll(): Int {
        mutex.withLock { db.vectorDao().deleteAllStates() }
        return syncMissing(limit = 100000, force = true)
    }

    /** 后台重建：跑在服务自己的 scope，页面退出不中断；进度写进 VectorRebuildState。 */
    fun rebuildInBackground() {
        if (VectorRebuildState.state.value.running) return
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope.launch {
            val total = runCatching { recordRepository.getAllActiveRecords().size }.getOrDefault(0)
            VectorRebuildState.start(total)
            val t0 = System.currentTimeMillis()
            android.util.Log.i("Vector", "重建开始 total=$total")
            try {
                val n = syncMissing(limit = 100000, force = true, onProgress = { done -> VectorRebuildState.progress(done, total) })
                android.util.Log.i("Vector", "重建结束 处理=$n 耗时=${System.currentTimeMillis() - t0}ms")
                VectorRebuildState.finish()
            } catch (e: Exception) {
                VectorRebuildState.finish(e.message ?: "重建失败")
            }
        }
    }

    suspend fun stats(): VectorStats {
        val dao = db.vectorDao()
        val states = dao.allStates()
        return VectorStats(
            indexed = states.count { it.status == EmbeddingState.STATUS_OK },
            locked = states.count { it.status == EmbeddingState.STATUS_SKIPPED_LOCKED },
            short = states.count { it.status == EmbeddingState.STATUS_SKIPPED_SHORT },
            failed = states.count { it.status == EmbeddingState.STATUS_FAILED },
            totalRecords = runCatching { recordRepository.getAllActiveRecords().size }.getOrDefault(0),
            model = modelName
        )
    }

    /** 记录参与嵌入的文本：正文 + 备注 + 它的增修（增修不单独建向量）。 */
    private suspend fun buildText(record: Record): String {
        val amendments = runCatching {
            recordRepository.getAmendmentsForRecords(listOf(record.id))
        }.getOrDefault(emptyList())
        return buildText(record, amendments)
    }

    /** 文本 = 正文 + 备注 + 增修（增修并入父记录，不单独建向量）。 */
    private fun buildText(record: Record, amendments: List<Record>): String {
        val sb = StringBuilder()
        sb.append(record.content)
        if (record.notes.isNotBlank()) sb.append('\n').append(record.notes)
        amendments.filter { it.content.isNotBlank() }.sortedBy { it.createdAt }
            .forEach { sb.append('\n').append(it.content) }
        return sb.toString().trim()
    }

    @Suppress("unused")
    private suspend fun buildTextLegacy(record: Record): String {
        val sb = StringBuilder()
        sb.append(record.content)
        if (record.notes.isNotBlank()) sb.append('\n').append(record.notes)
        runCatching {
            recordRepository.getAmendmentsForRecords(listOf(record.id))
                .filter { it.content.isNotBlank() }
                .sortedBy { it.createdAt }
                .forEach { sb.append('\n').append(it.content) }
        }
        return sb.toString().trim()
    }

    private fun floatsToBytes(values: FloatArray): ByteArray {
        val buf = ByteBuffer.allocate(values.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        values.forEach { buf.putFloat(it) }
        return buf.array()
    }


}

data class VectorStats(
    val indexed: Int,
    val locked: Int,
    val short: Int,
    val failed: Int,
    val totalRecords: Int,
    val model: String
) {
    val pending: Int get() = (totalRecords - indexed - locked - short - failed).coerceAtLeast(0)
}
