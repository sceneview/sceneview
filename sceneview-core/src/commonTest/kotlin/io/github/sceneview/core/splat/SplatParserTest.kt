package io.github.sceneview.core.splat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SplatParserTest {

    private val onePly = buildPly(
        listOf(
            "x", "y", "z", "f_dc_0", "f_dc_1", "f_dc_2",
            "opacity", "scale_0", "scale_1", "scale_2", "rot_0", "rot_1", "rot_2", "rot_3"
        ),
        listOf(floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, -3f, -3f, -3f, 1f, 0f, 0f, 0f)),
    )

    private val oneSpz = buildSpzGzip(
        listOf(
            SplatSample(
                position = floatArrayOf(0f, 0f, 0f),
                logScale = floatArrayOf(-3f, -3f, -3f),
                quaternionXyzw = floatArrayOf(0f, 0f, 0f, 1f),
                color = floatArrayOf(0.5f, 0.5f, 0.5f),
                opacity = 0.5f,
            )
        ),
        version = 2,
    )

    @Test
    fun sniffsPly() {
        assertEquals(1, SplatParser.parse(onePly).count)
    }

    @Test
    fun sniffsGzipSpz() {
        assertEquals(1, SplatParser.parse(oneSpz).count)
    }

    @Test
    fun handlesPlyWithCrlfMagic() {
        // Some writers emit CRLF line endings; the "ply\r\n" magic and header must still parse.
        val props = listOf(
            "x", "y", "z", "f_dc_0", "f_dc_1", "f_dc_2",
            "opacity", "scale_0", "scale_1", "scale_2", "rot_0", "rot_1", "rot_2", "rot_3"
        )
        val header = buildString {
            append("ply\r\n")
            append("format binary_little_endian 1.0\r\n")
            append("element vertex 1\r\n")
            for (p in props) append("property float $p\r\n")
            append("end_header\r\n")
        }.encodeToByteArray()
        val body = ByteArray(props.size * 4) // all-zero float32 vertex
        assertEquals(1, SplatParser.parse(header + body).count)
    }

    @Test
    fun rejectsEmptyInput() {
        assertFailsWith<SplatParseException> { SplatParser.parse(ByteArray(0)) }
    }

    @Test
    fun rejectsGarbageInput() {
        assertFailsWith<SplatParseException> {
            SplatParser.parse(bytesOf(0xDE, 0xAD, 0xBE, 0xEF, 0x00, 0x11))
        }
    }

    @Test
    fun fromPlyRejectsGzipInput() {
        // Feeding gzip bytes to the PLY entry point must fail cleanly, not crash.
        assertFailsWith<SplatParseException> { SplatParser.fromPly(oneSpz) }
    }
}
