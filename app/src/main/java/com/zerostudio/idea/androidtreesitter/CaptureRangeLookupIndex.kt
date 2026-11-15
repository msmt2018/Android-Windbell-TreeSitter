package com.zerostudio.idea.androidtreesitter

/**
 * Augmented AVL interval tree for viewport capture-cache lookup.
 *
 * maxEnd and session bounds prune nested/overlapping ranges without a linear
 * scan of LRU history. The index stores keys only; payloads and byte accounting
 * remain owned by the LRU cache.
 */
internal data class CaptureRangeKey(
    val sessionId: Long,
    val startUtf16: Int,
    val endUtf16: Int
) {
    init {
        require(sessionId >= 0L)
        require(startUtf16 >= 0 && endUtf16 >= startUtf16)
    }
}

internal class CaptureRangeLookupIndex {
    private class Node(val key: CaptureRangeKey) {
        var left: Node? = null
        var right: Node? = null
        var height: Int = 1
        var maxEnd: Int = key.endUtf16
        var minSession: Long = key.sessionId
        var maxSession: Long = key.sessionId
    }

    private var root: Node? = null
    private val lock = Any()

    val size: Int
        get() = synchronized(lock) { count(root) }

    fun add(key: CaptureRangeKey) = synchronized(lock) {
        root = insert(root, key)
    }

    fun remove(key: CaptureRangeKey) = synchronized(lock) {
        root = delete(root, key)
    }

    /** Returns a cached interval that fully contains the requested source range. */
    fun findContaining(sessionId: Long, startUtf16: Int, endUtf16: Int): CaptureRangeKey? {
        if (sessionId < 0L || startUtf16 < 0 || endUtf16 <= startUtf16) return null
        return synchronized(lock) { find(root, sessionId, startUtf16, endUtf16) }
    }

    private fun find(node: Node?, sessionId: Long, start: Int, end: Int): CaptureRangeKey? {
        if (node == null || node.maxEnd < end ||
            sessionId < node.minSession || sessionId > node.maxSession) return null

        val left = node.left
        if (left != null && left.maxEnd >= end &&
            sessionId >= left.minSession && sessionId <= left.maxSession) {
            find(left, sessionId, start, end)?.let { return it }
        }

        val key = node.key
        if (key.sessionId == sessionId && key.startUtf16 <= start && key.endUtf16 >= end) {
            return key
        }

        if (key.sessionId < sessionId ||
            (key.sessionId == sessionId && key.startUtf16 <= start)) {
            return find(node.right, sessionId, start, end)
        }
        return null
    }

    private fun insert(node: Node?, key: CaptureRangeKey): Node {
        if (node == null) return Node(key)
        val order = compare(key, node.key)
        if (order < 0) node.left = insert(node.left, key)
        else if (order > 0) node.right = insert(node.right, key)
        else return node
        return balance(update(node))
    }

    private fun delete(node: Node?, key: CaptureRangeKey): Node? {
        node ?: return null
        val order = compare(key, node.key)
        if (order < 0) node.left = delete(node.left, key)
        else if (order > 0) node.right = delete(node.right, key)
        else {
            val left = node.left
            val right = node.right
            if (right == null) return left
            val successor = minimum(right)
            successor.right = deleteMinimum(right)
            successor.left = left
            return balance(update(successor))
        }
        return balance(update(node))
    }

    private fun deleteMinimum(node: Node): Node? {
        val left = node.left ?: return node.right
        node.left = deleteMinimum(left)
        return balance(update(node))
    }

    private fun minimum(node: Node): Node {
        var current = node
        while (current.left != null) current = current.left!!
        return current
    }

    private fun update(node: Node): Node {
        node.height = 1 + maxOf(height(node.left), height(node.right))
        node.maxEnd = maxOf(
            node.key.endUtf16,
            node.left?.maxEnd ?: Int.MIN_VALUE,
            node.right?.maxEnd ?: Int.MIN_VALUE
        )
        node.minSession = minOf(
            node.key.sessionId,
            node.left?.minSession ?: Long.MAX_VALUE,
            node.right?.minSession ?: Long.MAX_VALUE
        )
        node.maxSession = maxOf(
            node.key.sessionId,
            node.left?.maxSession ?: Long.MIN_VALUE,
            node.right?.maxSession ?: Long.MIN_VALUE
        )
        return node
    }

    private fun balance(node: Node): Node {
        val factor = height(node.left) - height(node.right)
        if (factor > 1) {
            val left = node.left!!
            if (height(left.left) < height(left.right)) node.left = rotateLeft(left)
            return rotateRight(node)
        }
        if (factor < -1) {
            val right = node.right!!
            if (height(right.right) < height(right.left)) node.right = rotateRight(right)
            return rotateLeft(node)
        }
        return node
    }

    private fun rotateLeft(node: Node): Node {
        val pivot = node.right!!
        node.right = pivot.left
        pivot.left = update(node)
        return update(pivot)
    }

    private fun rotateRight(node: Node): Node {
        val pivot = node.left!!
        node.left = pivot.right
        pivot.right = update(node)
        return update(pivot)
    }

    private fun height(node: Node?): Int = node?.height ?: 0

    private fun count(node: Node?): Int =
        if (node == null) 0 else 1 + count(node.left) + count(node.right)

    private fun compare(left: CaptureRangeKey, right: CaptureRangeKey): Int {
        val session = left.sessionId.compareTo(right.sessionId)
        if (session != 0) return session
        val start = left.startUtf16.compareTo(right.startUtf16)
        if (start != 0) return start
        return left.endUtf16.compareTo(right.endUtf16)
    }
}
