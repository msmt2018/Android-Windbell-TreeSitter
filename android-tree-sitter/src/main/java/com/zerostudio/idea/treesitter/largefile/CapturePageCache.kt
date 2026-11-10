package com.zerostudio.idea.treesitter.largefile

/** One bounded result page; [nextStartUtf16] is null when the range is exhausted. */
data class CapturePage<T : Any>(
    val key: CaptureCacheKey,
    val pageIndex: Int,
    val startUtf16: Long,
    val endUtf16: Long,
    val captures: List<T>,
    val nextStartUtf16: Long?
) {
    init {
        require(pageIndex >= 0 && startUtf16 >= 0L && endUtf16 >= startUtf16)
        require(nextStartUtf16 == null || nextStartUtf16 >= endUtf16)
    }
}

/** Versioned, byte-budgeted cache for immutable native capture pages. */
class CapturePageCache<T : Any>(
    maxBytes: Long,
    private val weighCapture: (T) -> Long
) : AutoCloseable {
    private val cache = ByteBudgetLruCache<CapturePageKey, CapturePage<T>>(
        maxBytes,
        { page -> 64L + page.captures.sumOf { weighCapture(it).coerceAtLeast(0L) } }
    )

    private data class CapturePageKey(val identity: CaptureCacheKey, val pageIndex: Int)

    fun get(identity: CaptureCacheKey, pageIndex: Int): CapturePage<T>? =
        cache.get(CapturePageKey(identity, pageIndex))

    fun put(page: CapturePage<T>) {
        cache.put(CapturePageKey(page.key, page.pageIndex), page)
    }

    fun invalidateDocumentVersion(version: Long) {
        cache.invalidate { key, _ -> key.identity.documentVersion != version }
    }

    fun invalidateGrammar(grammarRevision: Long) {
        cache.invalidate { key, _ -> key.identity.grammarRevision != grammarRevision }
    }

    fun invalidateTheme(themeRevision: Long) {
        cache.invalidate { key, _ -> key.identity.themeRevision != themeRevision }
    }

    val bytesUsed: Long get() = cache.bytesUsed
    val size: Int get() = cache.size
    override fun close() = cache.close()
}
