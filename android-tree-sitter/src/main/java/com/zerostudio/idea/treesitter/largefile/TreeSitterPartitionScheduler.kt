package com.zerostudio.idea.treesitter.largefile

import com.zerostudio.idea.treesitter.TSTree
import java.util.concurrent.PriorityBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Viewport-prioritized regional Tree-sitter scheduler.
 *
 * The supplied parser callback must parse the complete backing source through a
 * segmented source and set one included range for [PartitionParseRequest.contextStartUtf16]
 * ..[PartitionParseRequest.contextEndUtf16]. The overlap is intentionally explicit:
 * this scheduler does not pretend independent, context-free AST fragments are valid.
 *
 * Parsing is serialized by default because a TSParser instance is not concurrently
 * reusable. Applications may provide separate parser instances via the callback if
 * they deliberately raise [parallelism].
 */
data class PartitionParseRequest(
    val partition: DocumentPartition,
    val contextStartUtf16: Long,
    val contextEndUtf16: Long,
    val documentVersion: Long,
    val languageRevision: Long
)

class TreeSitterPartitionScheduler(
    private val partitionManager: DocumentPartitionManager,
    private val astCache: PartitionAstCache,
    private val contextChars: Long = 8L * 1024L,
    parallelism: Int = 1,
    private val onFailure: (PartitionParseRequest, Throwable) -> Unit = { _, _ -> }
) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val pending = HashMap<PartitionAstKey, PrioritizedTask>()
    private val lock = Any()
    private val executor = ThreadPoolExecutor(
        parallelism,
        parallelism,
        0L,
        TimeUnit.MILLISECONDS,
        PriorityBlockingQueue()
    )

    init {
        require(contextChars >= 0L)
        require(parallelism > 0)
    }

    /**
     * Queue the visible range and its configured prefetch partitions. The nearest
     * partition to the viewport center is parsed first; repeated requests are deduped.
     */
    fun requestViewport(
        source: Utf16DocumentSource,
        startUtf16: Long,
        endUtf16: Long,
        languageRevision: Long,
        parse: (PartitionParseRequest) -> TSTree?
    ): List<PartitionAstKey> {
        check(!closed.get()) { "Scheduler is closed" }
        val plan = partitionManager.planViewport(source, startUtf16, endUtf16)
        val center = startUtf16 + (endUtf16 - startUtf16) / 2L
        val keys = ArrayList<PartitionAstKey>(plan.partitions.size)
        val tasks = ArrayList<PrioritizedTask>(plan.partitions.size)

        for (partition in plan.partitions) {
            var contextStart = (partition.start - contextChars).coerceAtLeast(0L)
            var contextEnd = minOf(source.length, safeAdd(partition.endExclusive, contextChars))
            // Start/end at nearby line boundaries when possible so regex and Monarch
            // tokenization do not begin in the middle of an ordinary source line.
            contextStart = alignContextStartToLine(source, contextStart)
            contextEnd = alignContextEndToLine(source, contextEnd)
            // Context windows must not split a UTF-16 surrogate pair either.
            if (contextStart > 0L && contextStart < source.length &&
                Character.isHighSurrogate(source[contextStart - 1L]) &&
                Character.isLowSurrogate(source[contextStart])) {
                contextStart--
            }
            if (contextEnd > 0L && contextEnd < source.length &&
                Character.isHighSurrogate(source[contextEnd - 1L]) &&
                Character.isLowSurrogate(source[contextEnd])) {
                contextEnd++
            }
            val key = PartitionAstKey(
                plan.documentVersion,
                partition.index,
                partition.start,
                partition.endExclusive,
                contextStart,
                contextEnd,
                languageRevision
            )
            keys += key

            val cached = astCache.acquire(key)
            if (cached != null) {
                cached.close()
                continue
            }
            val distance = distanceToRange(center, partition.start, partition.endExclusive)
            val task = synchronized(lock) {
                if (closed.get() || pending.containsKey(key)) {
                    null
                } else {
                    val request = PartitionParseRequest(
                        partition, contextStart, contextEnd, plan.documentVersion, languageRevision
                    )
                    val queued = PrioritizedTask(distance) {
                        try {
                            if (closed.get() || source.version != request.documentVersion) return@PrioritizedTask
                            val tree = parse(request) ?: return@PrioritizedTask
                            if (closed.get() || source.version != request.documentVersion) {
                                tree.close()
                            } else {
                                astCache.put(key, tree, request.contextEndUtf16 - request.contextStartUtf16)
                            }
                        } catch (error: Throwable) {
                            onFailure(request, error)
                        } finally {
                            synchronized(lock) { pending.remove(key) }
                        }
                    }
                    pending[key] = queued
                    queued
                }
            }
            if (task != null) tasks += task
        }
        // All tasks have priorities computed before they enter the priority queue.
        synchronized(lock) {
            if (closed.get()) {
                tasks.forEach { it.cancel() }
            } else {
                tasks.forEach(executor::execute)
            }
        }
        return keys
    }

    fun cancelPending() {
        synchronized(lock) {
            pending.values.forEach { executor.remove(it); it.cancel() }
            pending.clear()
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        cancelPending()
        executor.shutdownNow()
        // A running parser callback may still be using a tree or preparing one for
        // admission. Never close the native AST cache while a worker can still touch it.
        var interrupted = false
        while (!executor.isTerminated) {
            try {
                executor.awaitTermination(1L, TimeUnit.SECONDS)
            } catch (_: InterruptedException) {
                interrupted = true
                executor.shutdownNow()
            }
        }
        astCache.close()
        if (interrupted) Thread.currentThread().interrupt()
    }

    private inner class PrioritizedTask(
        private val priority: Long,
        action: () -> Unit
    ) : Runnable, Comparable<PrioritizedTask> {
        private val actionRef = action
        private val cancelled = AtomicBoolean(false)
        fun cancel() { cancelled.set(true) }
        override fun run() { if (!cancelled.get()) actionRef() }
        override fun compareTo(other: PrioritizedTask): Int = priority.compareTo(other.priority)
    }

    private fun alignContextStartToLine(source: Utf16DocumentSource, offset: Long): Long {
        if (offset <= 0L) return 0L
        val windowStart = (offset - CONTEXT_LINE_ALIGNMENT_CHARS).coerceAtLeast(0L)
        var cursor = windowStart
        var lastNewline: Long? = null
        while (cursor < offset) {
            val chunk = source.readChunk(
                cursor,
                minOf(CONTEXT_LINE_ALIGNMENT_CHARS, offset - cursor).toInt()
            )
            if (chunk.isEmpty()) break
            val newline = chunk.lastIndexOf('\n')
            if (newline >= 0) lastNewline = cursor + newline
            cursor += chunk.length
        }
        return lastNewline?.plus(1L) ?: offset
    }

    private fun alignContextEndToLine(source: Utf16DocumentSource, offset: Long): Long {
        if (offset >= source.length) return source.length
        val limit = minOf(source.length, safeAdd(offset, CONTEXT_LINE_ALIGNMENT_CHARS))
        var cursor = offset
        while (cursor < limit) {
            val chunk = source.readChunk(
                cursor,
                minOf(CONTEXT_LINE_ALIGNMENT_CHARS, limit - cursor).toInt()
            )
            if (chunk.isEmpty()) break
            val newline = chunk.indexOf('\n')
            if (newline >= 0) return cursor + newline + 1L
            cursor += chunk.length
        }
        return offset
    }

    private fun distanceToRange(position: Long, start: Long, end: Long): Long = when {
        position < start -> start - position
        position > end -> position - end
        else -> 0L
    }

    private companion object { const val CONTEXT_LINE_ALIGNMENT_CHARS = 64 * 1024L }

    private fun safeAdd(a: Long, b: Long): Long =
        if (Long.MAX_VALUE - a < b) Long.MAX_VALUE else a + b
}
