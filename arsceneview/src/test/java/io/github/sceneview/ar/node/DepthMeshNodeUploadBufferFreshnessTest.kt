package io.github.sceneview.ar.node

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source guard for #1841. Filament retains the direct buffers asynchronously, so this invariant
 * cannot be observed without its native engine.
 */
class DepthMeshNodeUploadBufferFreshnessTest {

    @Test
    fun `uploadGeometry allocates fresh native-order buffers`() {
        val source = File("src/main/java/io/github/sceneview/ar/node/DepthMeshNode.kt")
            .readText()
            .replace(Regex("""/\*[\s\S]*?\*/|//[^\n]*"""), "")
            .replace(Regex("""\s+"""), "")
        val upload = source.substringAfter("privatefunuploadGeometry(")
            .substringBefore("privatefun", missingDelimiterValue = source)

        assertTrue(Regex("ByteBuffer\\.allocateDirect\\(").findAll(upload).count() == 2)
        assertTrue(Regex("\\.order\\(ByteOrder\\.nativeOrder\\(\\)\\)").findAll(upload).count() == 2)
        listOf(
            "vertexUploadBuffer",
            "indexUploadBuffer",
            "acquireDirectBuffer",
            "MIN_UPLOAD_BUFFER_BYTES",
        ).forEach { cachedBufferSymbol ->
            assertFalse(
                "$cachedBufferSymbol would violate Filament's buffer lifetime",
                source.contains(cachedBufferSymbol),
            )
        }
    }
}
