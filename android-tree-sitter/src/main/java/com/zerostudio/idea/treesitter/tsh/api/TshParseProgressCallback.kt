package com.zerostudio.idea.treesitter.tsh.api

/**
 * Receives coarse Tree-sitter parse progress for a snapshot-backed parse.
 *
 * [currentUtf16Offset] is the current source offset in UTF-16 code units, not UTF-8/Native bytes.
 * Return true to stop the current parse. The callback runs on the parsing thread and must be
 * fast, non-blocking, and safe to call from that worker thread.
 */
fun interface TshParseProgressCallback {
    fun shouldCancel(currentUtf16Offset: Long, hasError: Boolean): Boolean
}
