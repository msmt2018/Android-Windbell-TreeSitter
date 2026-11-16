package com.zerostudio.idea.treesitter.tsh

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zerostudio.idea.treesitter.TreeSitter
import com.zerostudio.idea.treesitter.java.TSLanguageJava
import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.StandardOpenOption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Exercises native document creation, a full Tree-sitter parse and a tail TSH
 * viewport query with a dense 100 MiB UTF-16LE source.
 *
 * The fixture is file-backed and transferred through the bounded direct-buffer
 * callback. No Kotlin String or ByteArray contains the whole document.
 */
@RunWith(AndroidJUnit4::class)
class TshNativeHundredMiBViewportTest {
    @Test
    fun nativeTshHighlightsTailViewportOfDenseHundredMiBDocument() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        TreeSitter.loadLibrary()
        System.loadLibrary("tree-sitter-java")

        val language = TSLanguageJava.getInstance()
        assertTrue("Java Tree-sitter language must be registered", language.nativeObject != 0L)

        val extension = "java"
        val engine = WindbellTshEngine.instance
        val tshSource = context.assets.open("tsh/java.tsh")
            .bufferedReader(Charsets.UTF_8).use { it.readText() }
        assertTrue(
            "Java TSH must compile: ${engine.lastCompileDiagnostics}",
            engine.loadTshSource(extension, tshSource, language)
        )

        val tail = "class LargeTailViewportProbe { int value; }\n"
        val tailBytes = tail.toByteArray(StandardCharsets.UTF_16LE)
        val documentBytes = 100L * 1024L * 1024L
        val fillerBytes = documentBytes - tailBytes.size
        assertTrue(fillerBytes > 0L && (fillerBytes and 1L) == 0L)

        val file = File.createTempFile("tsh-dense-100mb-", ".utf16", context.cacheDir)
        var documentHandle = 0L
        try {
            FileChannel.open(
                file.toPath(),
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING
            ).use { output ->
                val block = ByteBuffer.allocate(64 * 1024)
                for (index in 0 until block.capacity() step 2) {
                    block.put(index, 0x20.toByte())
                    block.put(index + 1, 0)
                }

                var offset = 0L
                while (offset < fillerBytes) {
                    block.clear()
                    val requested = minOf(block.capacity().toLong(), fillerBytes - offset).toInt()
                    block.limit(requested)
                    while (block.hasRemaining()) {
                        val written = output.write(block, offset)
                        assertTrue("Fixture write made no progress", written > 0)
                        offset += written.toLong()
                    }
                }
                val tailBuffer = ByteBuffer.wrap(tailBytes)
                while (tailBuffer.hasRemaining()) {
                    val written = output.write(tailBuffer, offset)
                    assertTrue("Tail fixture write made no progress", written > 0)
                    offset += written.toLong()
                }
                assertEquals(documentBytes, offset)
            }

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
                    // JNI requires even UTF-16LE windows. If a short read ended
                    // on an odd byte, the next callback safely rereads that byte.
                    total and -2
                }

                documentHandle = engine.createNativeDocument(
                    language = language,
                    extension = extension,
                    byteLength = documentBytes,
                    temporaryDirectory = context.cacheDir,
                    reader = reader
                )
                assertTrue("Native document creation returned an invalid handle", documentHandle != 0L)

                val captures = requireNotNull(engine.highlightNativeDocument(
                    documentHandle = documentHandle,
                    startByte = fillerBytes,
                    endByte = documentBytes
                )) { "Native TSH viewport query returned null" }
                assertEquals("Native capture rows must use six integers", 0, captures.size % 6)

                val nameStart = fillerBytes + tail.indexOf("LargeTailViewportProbe").toLong() * 2L
                val nameEnd = nameStart + "LargeTailViewportProbe".length.toLong() * 2L
                val typeStyleId = engine.getStyleCatalog(extension)
                    .firstOrNull { it.name == "type" }?.styleId
                assertNotNull("Java TSH must expose the type style", typeStyleId)

                val matchingCapture = (captures.indices step 6).any { index ->
                    captures[index].toLong() <= nameStart &&
                        captures[index + 1].toLong() >= nameEnd &&
                        (captures[index + 2] and 0xFFFF) == typeStyleId
                }
                assertTrue(
                    "The 100 MiB tail viewport must produce the Java type capture",
                    matchingCapture
                )
            }
        } finally {
            if (documentHandle != 0L) engine.closeNativeDocument(documentHandle)
            file.delete()
        }
    }
}
