package com.zerostudio.idea.androidtreesitter

/**
 * Stable native capture-cache keys for ordinary editor viewport requests.
 *
 * The native document cache is keyed by exact byte ranges. Canonical pages make
 * slightly different line/frame requests inside the same source page converge
 * on one immutable capture result, so scrolling within or back to that page
 * does not trigger another Regex/ATS execution merely because line batches
 * were coalesced differently.
 *
 * A request crossing a page boundary (or larger than one page) remains exact:
 * the current interval lookup requires one cached interval to contain the whole
 * requested range, and must never report partial coverage as complete.
 */
internal object CapturePageRanges {
    const val PAGE_SIZE_UTF16: Int = 16 * 1024

    fun canonicalize(key: CaptureRangeKey, documentLengthUtf16: Int): CaptureRangeKey {
        require(documentLengthUtf16 >= 0)
        if (key.startUtf16 >= key.endUtf16) return key
        val documentEnd = minOf(key.endUtf16, documentLengthUtf16)
        if (documentEnd <= key.startUtf16) return key

        val pageStart = (key.startUtf16 / PAGE_SIZE_UTF16) * PAGE_SIZE_UTF16
        val pageEnd = minOf(
            documentLengthUtf16.toLong(),
            pageStart.toLong() + PAGE_SIZE_UTF16.toLong()
        ).toInt()

        return if (key.startUtf16 >= pageStart && documentEnd <= pageEnd && pageStart < pageEnd) {
            key.copy(startUtf16 = pageStart, endUtf16 = pageEnd)
        } else {
            key
        }
    }
}
