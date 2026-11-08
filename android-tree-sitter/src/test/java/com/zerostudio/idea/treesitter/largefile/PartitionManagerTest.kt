package com.zerostudio.idea.treesitter.largefile

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PartitionManagerTest {
    @Test fun partitionsAreContiguousAndUtf16Aligned() {
        val text = buildString {
            repeat(200) { append("line-").append(it).append("-😀\n") }
        }
        val manager = PartitionManager(targetPartitionChars = 64, minimumPartitionChars = 16)
        val parts = manager.rebuild(text, version = 7)
        assertThat(parts).isNotEmpty()
        assertThat(parts.first().startChar).isEqualTo(0)
        assertThat(parts.last().endChar).isEqualTo(text.length)
        parts.forEachIndexed { index, part ->
            assertThat(part.startByte).isEqualTo(part.startChar.toLong() * 2)
            assertThat(part.endByte).isEqualTo(part.endChar.toLong() * 2)
            assertThat(part.version).isEqualTo(7)
            if (index > 0) assertThat(parts[index - 1].endChar).isEqualTo(part.startChar)
            if (part.endChar < text.length) {
                assertThat(Character.isHighSurrogate(text[part.endChar - 1]) &&
                    Character.isLowSurrogate(text[part.endChar])).isFalse()
            }
        }
    }

    @Test fun viewportSelectsOnlyIntersectingPartitionsAndPrefetch() {
        val text = (0 until 1000).joinToString("\n") { "line-$it" }
        val manager = PartitionManager(128, 32)
        val all = manager.rebuild(text)
        val selected = manager.select(PartitionManager.Viewport(500, 520), 0)
        assertThat(selected).isNotEmpty()
        assertThat(selected.size).isLessThan(all.size)
        val prefetched = manager.select(PartitionManager.Viewport(500, 520), 1)
        assertThat(prefetched.size).isAtLeast(selected.size)
    }

    @Test fun weightedLruEvictsOldestAndRejectsOversizedEntry() {
        val cache = BoundedLruCache<String, ByteArray>(10) { it.size.toLong() }
        cache.put("a", ByteArray(6))
        cache.put("b", ByteArray(4))
        assertThat(cache.get("a")).isNotNull()
        cache.put("c", ByteArray(5))
        assertThat(cache.get("a")).isNull()
        assertThat(cache.get("b")).isNotNull()
        assertThat(cache.weight()).isAtMost(10L)
        cache.put("huge", ByteArray(11))
        assertThat(cache.get("huge")).isNull()
    }

    @Test fun versionedEntriesCanBeInvalidatedWithoutClearingOtherVersions() {
        data class Key(val version: Long, val page: Int)
        val cache = BoundedLruCache<Key, ByteArray>(100) { it.size.toLong() }
        cache.put(Key(1, 0), ByteArray(4))
        cache.put(Key(2, 0), ByteArray(4))
        cache.removeIf { it.version == 1L }
        assertThat(cache.get(Key(1, 0))).isNull()
        assertThat(cache.get(Key(2, 0))).isNotNull()
    }
}
