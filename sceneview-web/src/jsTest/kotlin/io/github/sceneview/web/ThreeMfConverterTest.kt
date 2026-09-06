package io.github.sceneview.web

import org.khronos.webgl.ArrayBuffer
import org.khronos.webgl.Int8Array
import org.khronos.webgl.Uint8Array
import org.khronos.webgl.get
import org.khronos.webgl.set
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Unit tests for [ThreeMfConverter] — the step that lets `loadModel` take a `.3mf` URL (#3482).
 *
 * The parsing itself is covered by `sceneview-core`'s 23 3MF tests, on both the JVM and this
 * very JS target. What is web-specific, and what these cover, is the *plumbing*: the cheap ZIP
 * gate, the no-copy pass-through for everything that is not a 3MF, and the `ArrayBuffer` ⇄
 * `ByteArray` round trip that a wrong `unsafeCast` would corrupt in silence.
 *
 * The fixture is byte-identical to `ThreeMfTestFixtures.CubeThreeMf` in the core tests — a real
 * 20 mm two-coloured cube written by Python's `zipfile` with `ZIP_DEFLATED`.
 */
class ThreeMfConverterTest {

    @Test
    fun convertsARealThreeMfToGlb() {
        val glb = ThreeMfConverter.convert(cubeThreeMf())
        val head = Uint8Array(glb, 0, 4)
        assertEquals(
            "glTF",
            (0..3).map { head[it].toInt().toChar() }.joinToString(""),
            "A 3MF must come out as a GLB — Filament.js reads nothing else here.",
        )
        assertTrue(glb.byteLength > 1000, "The converted GLB carries the cube's geometry.")
    }

    @Test
    fun sniffsARealThreeMf() {
        assertTrue(ThreeMfConverter.isThreeMf(cubeThreeMf()))
    }

    @Test
    fun aGlbIsReturnedAsTheVerySameInstance() {
        // The cheap gate's whole point: a 100 MB GLB must not be copied just to
        // find out it is not a 3MF. Identity, not equality, is the assertion.
        val glb = bufferOf(0x67, 0x6C, 0x54, 0x46, 0x02, 0x00, 0x00, 0x00)
        assertSame(glb, ThreeMfConverter.convert(glb))
        assertFalse(ThreeMfConverter.isThreeMf(glb))
    }

    @Test
    fun aPlainZipThatIsNotAThreeMfIsReturnedUntouched() {
        // Passes the 4-byte magic gate, fails the central-directory lookup: the
        // payload must come back untouched rather than throw or return garbage.
        val zip = bufferOf(0x50, 0x4B, 0x03, 0x04, 0x14, 0x00, 0x00, 0x00)
        assertSame(zip, ThreeMfConverter.convert(zip))
        assertFalse(ThreeMfConverter.isThreeMf(zip))
    }

    @Test
    fun aTruncatedPayloadIsReturnedUntouched() {
        val tiny = bufferOf(0x50, 0x4B)
        assertSame(tiny, ThreeMfConverter.convert(tiny))
        assertFalse(ThreeMfConverter.isThreeMf(tiny))
    }

    @Test
    fun byteArrayRoundTripsThroughArrayBufferWithoutCorruption() {
        // `toArrayBuffer()` unwraps a Kotlin/JS ByteArray's own Int8Array. A wrong
        // offset or length here would truncate or shift every converted GLB.
        // Signed readback (`Int8Array`), because a `ByteArray` is signed: a
        // `Uint8Array` would report 149 where Kotlin holds -107 and the check
        // would be about JS number signs rather than about the bytes.
        val bytes = ByteArray(5) { (it * 37 + 1).toByte() }
        val view = Int8Array(bytes.toArrayBuffer())
        assertEquals(5, view.length)
        for (i in 0 until 5) {
            assertEquals(bytes[i], view[i], "byte $i survived the round trip")
        }
    }

    private fun bufferOf(vararg bytes: Int): ArrayBuffer {
        val view = Uint8Array(bytes.size)
        bytes.forEachIndexed { i, b -> view[i] = b.toByte() }
        return view.buffer
    }

    private fun cubeThreeMf(): ArrayBuffer {
        val binary = js("atob")(CubeThreeMfBase64) as String
        val view = Uint8Array(binary.length)
        for (i in binary.indices) view[i] = binary[i].code.toByte()
        return view.buffer
    }

    private companion object {
        /** The `sceneview-core` 3MF fixture, verbatim: a 20 mm two-coloured cube. */
        const val CubeThreeMfBase64 =
        "UEsDBBQAAAAIAJsIJl0YJdOkxwAAAEQBAAATAAAAW0NvbnRlbnRfVHlwZXNdLnhtbIWQTWrDMBCF9z6F0DbYchMopdjOoklP" +
        "kB5gkMc/RBoJaRzS22ectBQKpcvH++Z9MM3+6p26YMpzoFY/VbVWSDb0M42t/ji9ly963xXN6TNiVsJSbvXEHF+NyXZCD7kK" +
        "EUmaISQPLDGNJoI9w4hmW9fPxgZiJC553dBdoZoDDrA4VserFA9zQpe1enugq63VEKObLbD05kL9L0/55ajk8s7kaY55I4A2" +
        "fzh86NH9I/E/w7veAy0DWF6SvKOUvA58Kxpzf0pX3ABQSwMEFAAAAAgAmwgmXWPBjv21AAAABwEAAAsAAABfcmVscy8ucmVs" +
        "c2WPzQrCQAyE732KJXeb1oKIdOtFBK9SH2DZpj/Y/WF3FX17oyBYvARCMt/M1PuHmcWdQpyclVDmBQiy2nWTHSRc2uNqC/sm" +
        "q880q8QvcZx8FKyxUcKYkt8hRj2SUTF3nixfeheMSryGAb3SVzUQrotig+GXAU0mFlTRqjBQkoDVAavOuI7m/DNBnDoJrOZs" +
        "7dPTn7GZdHDR9SnXzry1yt56pdMtcAv2Lissyi8TkOvgok+TvQBQSwMEFAAAAAgAmwgmXelX0hriAQAAegUAABAAAAAzRC8z" +
        "ZG1vZGVsLm1vZGVsjZTbjpswEIbv8xSWe70xh5BUK8gqW5UX2N3eGzPZuPIBYZMmffoaG9QlzVKEZGPmm5l/Btv500UKdIbW" +
        "cK0KHK8jjEAxXXP1XuC31/LhK37ar3KpaxCoU9wWWHIhuAQLLUbO+1HQngX18PbiPyhT4JO1zSMhhp1AUrOWnLXa6KNdMy1J" +
        "WkuquiNltmtdHsJ0CySJ4oxECd6vUO6C05paihSVUOBD0wjOqHUS8f6FgYIfHH4hC8aiI7+4KJCT0af3b8HormVg3ALlFTVO" +
        "hJPLqTCI1wXO+iyDZcjxClRgVHPTCHplWui2wF+i8jk7fC9LTP7hD7Lq679xKMvnNIpGh5xMUvtPuvoJzHoVMUb22rhYvrl4" +
        "CPytqwCjJsh0s6rhUuBoUCzBnPwbyt1Ps3yoMazggjyKrn783Y/k1pwssyefAsvswZz8T8AMkHxO3Adcvyc9Qbl1fVfvYuzR" +
        "uETn2Mc4JwVO3ZS6IK7V8Ydy7qFJQOM5dBPQLKDbBeg2oLsFAuKAZgvQQcBmDo0nZQ1a4zl0OxFwH00mfd0tQHcLBKQBjSZl" +
        "zaKbuwLcHrnZFGQ8UjkJZ7O/QMjHGySvOi5qj3ALEgVsPMEtVeaoW+lWKPLP3znrZ5/ZXQUhiMvXn/b96g9QSwECFAMUAAAA" +
        "CACbCCZdGCXTpMcAAABEAQAAEwAAAAAAAAAAAAAAgAEAAAAAW0NvbnRlbnRfVHlwZXNdLnhtbFBLAQIUAxQAAAAIAJsIJl1j" +
        "wY79tQAAAAcBAAALAAAAAAAAAAAAAACAAfgAAABfcmVscy8ucmVsc1BLAQIUAxQAAAAIAJsIJl3pV9Ia4gEAAHoFAAAQAAAA" +
        "AAAAAAAAAACAAdYBAAAzRC8zZG1vZGVsLm1vZGVsUEsFBgAAAAADAAMAuAAAAOYDAAAAAA=="
    }
}
