package com.zerostudio.idea.treesitter.tsh.api

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class TshTextAccessProviderContractTest {
    private class Snapshot : TshTextSnapshot {
        override val documentId = "doc"
        override val version = 7L
        private val content = "abcdef"
        override val lengthUtf16 = content.length.toLong()
        override fun readUtf16(start: Long, end: Long): CharSequence {
            require(start >= 0 && end >= start && end <= lengthUtf16)
            return content.substring(start.toInt(), end.toInt())
        }
    }

    private class Provider : TshTextAccessProvider {
        override val id = "test"
        override val threading = TshTextAccessThreading.ANY_THREAD
        override fun supports(document: Any) = true
        override fun snapshot(document: Any) = TshResult.Success(Snapshot())
    }

    @Test
    fun textAccessProviderParticipatesInEngineCapabilityNegotiation() {
        val hostDocument = Any()
        val objects = TshObjectRegistry()
        val providers = TshProviderRegistry()
        val provider = Provider()
        providers.register(provider)
        val descriptor = objects.register(hostDocument, "document")
        val resolution = providers.resolveFor(
            hostDocument,
            descriptor,
            required = TshCapabilitySet.of(TshCapability("text.snapshot", 1))
        )
        assertTrue(resolution.isSatisfiable)
        assertTrue(resolution.selectedProviders.any { it === provider })
        assertTrue(provider.capabilities.contains("text.chunk"))
    }

    @Test
    fun readsBoundedChunksWithoutFlatteningContract() {
        val provider = Provider()
        val lease = (provider.acquireSnapshot(Any()) as TshResult.Success).value
        val chunk = provider.readChunk(lease, 2L, 3)
        val chunkValue = (chunk as TshResult.Success).value
        assertEquals("cde", chunkValue.text.toString())
        assertEquals(7L, chunkValue.documentVersion)
        lease.close()
        assertTrue(lease.isClosed)
        assertTrue(provider.readChunk(lease, 0L, 2) is TshResult.Failure)
    }

    @Test
    fun chunkReadsNeverSplitSurrogatePairsAndRejectMalformedEdits() {
        val snapshot = object : TshTextSnapshot {
            override val documentId = "unicode"
            override val version = 1L
            private val content = "ab😀cd"
            override val lengthUtf16 = content.length.toLong()
            override fun readUtf16(start: Long, end: Long): CharSequence =
                content.substring(start.toInt(), end.toInt())
        }
        val provider = ProviderWithSnapshot(snapshot)
        val lease = (provider.acquireSnapshot(Any()) as TshResult.Success).value
        val tooSmall = provider.readChunk(lease, 2L, 1)
        assertTrue(tooSmall is TshResult.Failure)
        val emoji = provider.readChunk(lease, 2L, 2) as TshResult.Success
        assertEquals("😀", emoji.value.text.toString())
        val splitStart = provider.readChunk(lease, 3L, 2)
        assertTrue(splitStart is TshResult.Failure)
        assertThrows(IllegalArgumentException::class.java) {
            TshTextEdit(0, 0, "\uD800")
        }
        lease.close()
    }

    private class ProviderWithSnapshot(private val value: TshTextSnapshot) : TshTextAccessProvider {
        override val id = "unicode"
        override val threading = TshTextAccessThreading.ANY_THREAD
        override fun supports(document: Any) = true
        override fun snapshot(document: Any) = TshResult.Success(value)
    }


    @Test
    fun retainedSnapshotLeasesCloseIndependentlyAndReleaseRootOnce() {
        val snapshot = Snapshot()
        var releaseCount = 0
        val first = TshSharedTextSnapshotLease.acquire(snapshot) { releaseCount++ }
        val second = first.retain() as TshResult.Success
        val secondLease = second.value
        val third = secondLease.retain() as TshResult.Success
        val thirdLease = third.value

        first.close()
        assertTrue(first.isClosed)
        assertFalse(secondLease.isClosed)
        assertFalse(thirdLease.isClosed)
        assertEquals("abcdef", secondLease.snapshot.readUtf16(0, 6).toString())
        assertEquals(0, releaseCount)

        secondLease.close()
        secondLease.close()
        assertEquals(0, releaseCount)
        thirdLease.close()
        assertEquals(1, releaseCount)
        assertTrue(thirdLease.isClosed)
        assertTrue(thirdLease.retain() is TshResult.Failure)
    }

    @Test
    fun defaultProviderLeaseCanBeRetainedWithoutSharingCloseState() {
        val provider = Provider()
        val first = (provider.acquireSnapshot(Any()) as TshResult.Success).value
        val second = (first.retain() as TshResult.Success).value

        first.close()
        assertTrue(first.isClosed)
        assertFalse(second.isClosed)
        assertEquals("abcdef", second.snapshot.readUtf16(0, 6).toString())
        second.close()
    }

    @Test
    fun legacyCustomLeaseWithoutRetainFailsExplicitly() {
        val snapshot = Snapshot()
        val legacyLease = object : TshTextSnapshotLease {
            override val snapshot: TshTextSnapshot = snapshot
            override val isClosed: Boolean = false
            override fun close() = Unit
        }
        val result = legacyLease.retain()
        assertTrue(result is TshResult.Failure)
        assertEquals("TSH-TEXT-LEASE-001", (result as TshResult.Failure).diagnostics.single().code)
    }


    @Test
    fun invalidSnapshotMetadataIsReturnedAsStructuredFailure() {
        val invalid = object : TshTextSnapshot {
            override val documentId = ""
            override val version = -1L
            override val lengthUtf16 = -1L
            override fun readUtf16(start: Long, end: Long): CharSequence = ""
        }
        val provider = ProviderWithSnapshot(invalid)
        val result = provider.acquireSnapshot(Any())
        assertTrue(result is TshResult.Failure)
        assertEquals("TSH-TEXT-011", (result as TshResult.Failure).diagnostics.single().code)
    }

    @Test
    fun finalSnapshotReleaseFailureRemainsInspectable() {
        val lease = TshSharedTextSnapshotLease.acquire(Snapshot()) {
            throw IllegalStateException("unpin failed")
        }
        lease.close()
        assertEquals("TSH-TEXT-LEASE-005", lease.finalReleaseDiagnostics.single().code)
    }

    @Test
    fun rejectsOverlappingOrUnorderedEdits() {
        assertThrows(IllegalArgumentException::class.java) {
            TshTextChange("doc", 1, 2, listOf(
                TshTextEdit(3, 5, "x"), TshTextEdit(4, 6, "y")
            ))
        }
        assertThrows(IllegalArgumentException::class.java) {
            TshTextChange("doc", 1, 2, listOf(
                TshTextEdit(5, 6, "x"), TshTextEdit(1, 2, "y")
            ))
        }
    }
}
