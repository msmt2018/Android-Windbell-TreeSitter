package com.zerostudio.idea.treesitter.tsh

import androidx.test.ext.junit.runners.AndroidJUnit4
import android.os.SystemClock
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.zerostudio.idea.treesitter.TreeSitter
import com.zerostudio.idea.treesitter.java.TSLanguageJava
import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.StandardOpenOption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * High-token-density stress coverage, deliberately separate from the sparse 100 MiB comment
 * fixture. The repeated nested blocks create tens of thousands of real Java declarations and
 * Tree-sitter Query captures before a tail viewport is queried.
 */
@RunWith(AndroidJUnit4::class)
class TshNativeDenseSyntaxViewportTest {
    @Test
    fun nativeTshParsesDenseJavaDeclarationsAndHighlightsTailViewport() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        TreeSitter.loadLibrary()
        System.loadLibrary("tree-sitter-java")

        val language = TSLanguageJava.getInstance()
        assertTrue("Java Tree-sitter language must be registered", language.nativeObject != 0L)

        val engine = WindbellTshEngine.instance
        val tshSource = context.assets.open("tsh/java.tsh")
            .bufferedReader(Charsets.UTF_8).use { it.readText() }
        assertTrue(
            "Java TSH must compile: ${engine.lastCompileDiagnostics}",
            engine.loadTshSource("java", tshSource, language)
        )

        val prefix = "class DenseTokenDocument { void run() {\n"
        // Each declaration is in its own block, so the repeated local name is legal Java.
        // java.tsh's native Tree-sitter Query intentionally captures this identifier.
        val declaration = "{ int multiline = 1; }\n"
        val declarationCount = 32_768
        val declarationsPerChunk = 1_024
        val suffix = "} }\n"
        val tail = "class DenseTailViewportProbe {}\n"

        val prefixBytes = prefix.toByteArray(StandardCharsets.UTF_16LE)
        val declarationBytes = declaration.toByteArray(StandardCharsets.UTF_16LE)
        val suffixBytes = suffix.toByteArray(StandardCharsets.UTF_16LE)
        val tailBytes = tail.toByteArray(StandardCharsets.UTF_16LE)
        val declarationBytesTotal = declarationBytes.size.toLong() * declarationCount
        val documentBytes = prefixBytes.size.toLong() + declarationBytesTotal +
            suffixBytes.size.toLong() + tailBytes.size.toLong()
        val tailStartByte = prefixBytes.size.toLong() + declarationBytesTotal + suffixBytes.size
        assertTrue("High-density fixture must exceed the 1 MiB windowed-parse threshold", documentBytes > 1024L * 1024L)

        val file = File.createTempFile("tsh-high-density-java-", ".utf16", context.cacheDir)
        var documentHandle = 0L
        try {
            val fixtureWriteStarted = SystemClock.elapsedRealtimeNanos()
            FileChannel.open(
                file.toPath(),
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING
            ).use { output ->
                var offset = 0L
                fun write(buffer: ByteBuffer, description: String) {
                    while (buffer.hasRemaining()) {
                        val written = output.write(buffer, offset)
                        assertTrue("$description write made no progress", written > 0)
                        offset += written.toLong()
                    }
                }

                write(ByteBuffer.wrap(prefixBytes), "Prefix")
                val chunk = ByteBuffer.allocate(declarationBytes.size * declarationsPerChunk)
                repeat(declarationsPerChunk) { chunk.put(declarationBytes) }
                chunk.flip()
                repeat(declarationCount / declarationsPerChunk) {
                    write(chunk.asReadOnlyBuffer(), "Dense declaration chunk")
                }
                write(ByteBuffer.wrap(suffixBytes), "Suffix")
                write(ByteBuffer.wrap(tailBytes), "Tail")
                assertEquals("Generated UTF-16LE file length must match its fixture", documentBytes, offset)
            }
            val fixtureWriteMs =
                (SystemClock.elapsedRealtimeNanos() - fixtureWriteStarted) / 1_000_000L
            Log.i("TSHPerf", "dense32768 fixtureWriteMs=$fixtureWriteMs bytes=$documentBytes")

            FileChannel.open(file.toPath(), StandardOpenOption.READ).use { input ->
                val reader = NativeTextWindowReader { byteOffset, maxBytes, destination ->
                    destination.clear()
                    destination.limit(maxBytes)
                    var total = 0
                    while (destination.hasRemaining()) {
                        val count = input.read(destination, byteOffset + total)
                        if (count <= 0) break
                        total += count
                    }
                    // The native bridge accepts even UTF-16LE windows. If storage returns
                    // an odd short read, the next callback rereads the final byte.
                    total and -2
                }

                val nativeCreateStarted = SystemClock.elapsedRealtimeNanos()
                documentHandle = engine.createNativeDocument(
                    language = language,
                    extension = "java",
                    byteLength = documentBytes,
                    temporaryDirectory = context.cacheDir,
                    reader = reader
                )
                val nativeCreateMs =
                    (SystemClock.elapsedRealtimeNanos() - nativeCreateStarted) / 1_000_000L
                Log.i("TSHPerf", "dense32768 nativeCreateAndParseMs=$nativeCreateMs bytes=$documentBytes")
                assertTrue("Dense Native document creation returned an invalid handle", documentHandle != 0L)

                val fullQueryStarted = SystemClock.elapsedRealtimeNanos()
                val allCaptures = requireNotNull(engine.highlightNativeDocument(
                    documentHandle = documentHandle,
                    startByte = 0L,
                    endByte = documentBytes
                )) { "Dense Java full-document Tree-sitter Query returned null" }
                val fullQueryMs =
                    (SystemClock.elapsedRealtimeNanos() - fullQueryStarted) / 1_000_000L
                Log.i("TSHPerf", "dense32768 fullDocumentQueryAndJniMs=$fullQueryMs captureRows=${allCaptures.size / 6}")
                assertEquals("Native capture rows must use six integers", 0, allCaptures.size % 6)

                val variableStyleId = engine.getStyleCatalog("java")
                    .first { it.name == "variable" }.styleId
                val kotlinCountStarted = SystemClock.elapsedRealtimeNanos()
                val declarationCaptures = (allCaptures.indices step 6).count { index ->
                    (allCaptures[index + 2] and 0xFFFF) == variableStyleId
                }
                val kotlinCountMs =
                    (SystemClock.elapsedRealtimeNanos() - kotlinCountStarted) / 1_000_000L
                Log.i("TSHPerf", "dense32768 kotlinCaptureCountMs=$kotlinCountMs declarationCaptures=$declarationCaptures")
                assertTrue(
                    "High-density fixture must produce a variable capture for each repeated declaration; " +
                        "expected at least $declarationCount, got $declarationCaptures",
                    declarationCaptures >= declarationCount
                )

                val tailQueryStarted = SystemClock.elapsedRealtimeNanos()
                val tailViewport = requireNotNull(engine.highlightNativeDocument(
                    documentHandle = documentHandle,
                    startByte = tailStartByte,
                    endByte = documentBytes
                )) { "Dense Java tail viewport query returned null" }
                val tailQueryMs =
                    (SystemClock.elapsedRealtimeNanos() - tailQueryStarted) / 1_000_000L
                Log.i("TSHPerf", "dense32768 tailViewportQueryMs=$tailQueryMs captureRows=${tailViewport.size / 6}")
                assertEquals("Tail capture rows must use six integers", 0, tailViewport.size % 6)

                val nameStart = tailStartByte + tail.indexOf("DenseTailViewportProbe").toLong() * 2L
                val nameEnd = nameStart + "DenseTailViewportProbe".length.toLong() * 2L
                val typeStyleId = engine.getStyleCatalog("java")
                    .first { it.name == "type" }.styleId
                val hasTailType = (tailViewport.indices step 6).any { index ->
                    tailViewport[index].toLong() <= nameStart &&
                        tailViewport[index + 1].toLong() >= nameEnd &&
                        (tailViewport[index + 2] and 0xFFFF) == typeStyleId
                }
                assertTrue(
                    "Tail viewport must retain the Java type capture after a dense full-document parse",
                    hasTailType
                )
            }
        } finally {
            if (documentHandle != 0L) engine.closeNativeDocument(documentHandle)
            file.delete()
        }
    }
}
