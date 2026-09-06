package io.github.sceneview.core.threemf

import io.github.sceneview.core.splat.crc32

/**
 * Fixtures for the 3MF reader tests.
 *
 * [CubeThreeMf] is a **real** file, not a hand-rolled approximation: it was produced by Python's
 * `zipfile` with `ZIP_DEFLATED`, so the tests exercise the dynamic-Huffman DEFLATE path, the
 * `[Content_Types].xml` / `_rels/.rels` OPC parts and the exact XML shape a writer emits —
 * everything a hand-built stored ZIP would quietly skip.
 *
 * It is a 20 mm cube in millimetres, coloured from a two-entry `<basematerials>` palette (the first
 * six triangles teal, the last six amber), placed by a build item translated 5 mm along X.
 */
internal object ThreeMfTestFixtures {

    /** A deflate-compressed 3MF written by Python's `zipfile`. See the object docs. */
    val CubeThreeMf: ByteArray get() = decodeBase64(CubeThreeMfBase64)

    private const val CubeThreeMfBase64 =
        "UEsDBBQAAAAIAJsIJl0YJdOkxwAAAEQBAAATAAAAW0NvbnRlbnRfVHlwZXNdLnhtbIWQTWrDMBCF9z6F0DbYchMopdjO" +
        "oklPkB5gkMc/RBoJaRzS22ectBQKpcvH++Z9MM3+6p26YMpzoFY/VbVWSDb0M42t/ji9ly963xXN6TNiVsJSbvXEHF+N" +
        "yXZCD7kKEUmaISQPLDGNJoI9w4hmW9fPxgZiJC553dBdoZoDDrA4VserFA9zQpe1enugq63VEKObLbD05kL9L0/55ajk" +
        "8s7kaY55I4A2fzh86NH9I/E/w7veAy0DWF6SvKOUvA58Kxpzf0pX3ABQSwMEFAAAAAgAmwgmXWPBjv21AAAABwEAAAsA" +
        "AABfcmVscy8ucmVsc2WPzQrCQAyE732KJXeb1oKIdOtFBK9SH2DZpj/Y/WF3FX17oyBYvARCMt/M1PuHmcWdQpyclVDm" +
        "BQiy2nWTHSRc2uNqC/smq880q8QvcZx8FKyxUcKYkt8hRj2SUTF3nixfeheMSryGAb3SVzUQrotig+GXAU0mFlTRqjBQ" +
        "koDVAavOuI7m/DNBnDoJrOZs7dPTn7GZdHDR9SnXzry1yt56pdMtcAv2Lissyi8TkOvgok+TvQBQSwMEFAAAAAgAmwgm" +
        "XelX0hriAQAAegUAABAAAAAzRC8zZG1vZGVsLm1vZGVsjZTbjpswEIbv8xSWe70xh5BUK8gqW5UX2N3eGzPZuPIBYZMm" +
        "ffoaG9QlzVKEZGPmm5l/Btv500UKdIbWcK0KHK8jjEAxXXP1XuC31/LhK37ar3KpaxCoU9wWWHIhuAQLLUbO+1HQngX1" +
        "8PbiPyhT4JO1zSMhhp1AUrOWnLXa6KNdMy1JWkuquiNltmtdHsJ0CySJ4oxECd6vUO6C05paihSVUOBD0wjOqHUS8f6F" +
        "gYIfHH4hC8aiI7+4KJCT0af3b8HormVg3ALlFTVOhJPLqTCI1wXO+iyDZcjxClRgVHPTCHplWui2wF+i8jk7fC9LTP7h" +
        "D7Lq679xKMvnNIpGh5xMUvtPuvoJzHoVMUb22rhYvrl4CPytqwCjJsh0s6rhUuBoUCzBnPwbyt1Ps3yoMazggjyKrn78" +
        "3Y/k1pwssyefAsvswZz8T8AMkHxO3Adcvyc9Qbl1fVfvYuzRuETn2Mc4JwVO3ZS6IK7V8Ydy7qFJQOM5dBPQLKDbBeg2" +
        "oLsFAuKAZgvQQcBmDo0nZQ1a4zl0OxFwH00mfd0tQHcLBKQBjSZlzaKbuwLcHrnZFGQ8UjkJZ7O/QMjHGySvOi5qj3AL" +
        "EgVsPMEtVeaoW+lWKPLP3znrZ5/ZXQUhiMvXn/b96g9QSwECFAMUAAAACACbCCZdGCXTpMcAAABEAQAAEwAAAAAAAAAA" +
        "AAAAgAEAAAAAW0NvbnRlbnRfVHlwZXNdLnhtbFBLAQIUAxQAAAAIAJsIJl1jwY79tQAAAAcBAAALAAAAAAAAAAAAAACA" +
        "AfgAAABfcmVscy8ucmVsc1BLAQIUAxQAAAAIAJsIJl3pV9Ia4gEAAHoFAAAQAAAAAAAAAAAAAACAAdYBAAAzRC8zZG1v" +
        "ZGVsLm1vZGVsUEsFBgAAAAADAAMAuAAAAOYDAAAAAA=="

    /**
     * Wrap [parts] into a ZIP with **stored** (uncompressed) entries — enough for the reader, and
     * the only ZIP a test can build without a deflate encoder. Used for the variant archives
     * (units, components, malformed XML) that would be unreadable as base64 blobs.
     */
    fun zipOf(vararg parts: Pair<String, String>): ByteArray {
        val local = ByteArrayBuilder()
        val central = ByteArrayBuilder()
        for ((name, content) in parts) {
            val nameBytes = name.encodeToByteArray()
            val data = content.encodeToByteArray()
            val offset = local.size
            local.addIntLe(LocalHeaderSignature)
            local.addShortLe(NeedVersion)
            local.addShortLe(0) // flags
            local.addShortLe(0) // method: stored
            local.addIntLe(0) // mtime + mdate
            local.addIntLe(crc32(data))
            local.addIntLe(data.size)
            local.addIntLe(data.size)
            local.addShortLe(nameBytes.size)
            local.addShortLe(0) // extra length
            local.addBytes(nameBytes)
            local.addBytes(data)

            central.addIntLe(CentralHeaderSignature)
            central.addShortLe(NeedVersion) // version made by
            central.addShortLe(NeedVersion)
            central.addShortLe(0) // flags
            central.addShortLe(0) // method: stored
            central.addIntLe(0) // mtime + mdate
            central.addIntLe(crc32(data))
            central.addIntLe(data.size)
            central.addIntLe(data.size)
            central.addShortLe(nameBytes.size)
            central.addShortLe(0) // extra
            central.addShortLe(0) // comment
            central.addShortLe(0) // disk
            central.addShortLe(0) // internal attributes
            central.addIntLe(0) // external attributes
            central.addIntLe(offset)
            central.addBytes(nameBytes)
        }
        val directory = central.toArray()
        val out = ByteArrayBuilder()
        out.addBytes(local.toArray())
        val directoryOffset = out.size
        out.addBytes(directory)
        out.addIntLe(EndOfCentralDirectorySignature)
        out.addShortLe(0) // this disk
        out.addShortLe(0) // directory start disk
        out.addShortLe(parts.size)
        out.addShortLe(parts.size)
        out.addIntLe(directory.size)
        out.addIntLe(directoryOffset)
        out.addShortLe(0) // comment length
        return out.toArray()
    }

    /** A 3MF package around [modelXml], with the OPC part a reader may look for. */
    fun threeMfOf(modelXml: String): ByteArray = zipOf(
        "[Content_Types].xml" to ContentTypes,
        "3D/3dmodel.model" to modelXml
    )

    /**
     * A one-triangle `<model>` document: [attributes] go on the root element, [extra] is inserted
     * at the top of `<resources>` and [objectAttributes] on the single `<object>`.
     */
    fun modelXml(
        attributes: String = """unit="millimeter"""",
        extra: String = "",
        objectAttributes: String = "",
        build: String = """<build><item objectid="1"/></build>"""
    ): String = """<?xml version="1.0" encoding="UTF-8"?>
<model $attributes xmlns="http://schemas.microsoft.com/3dmanufacturing/core/2015/02">
  <resources>
    $extra
    <object id="1" type="model" $objectAttributes>
      <mesh>
        <vertices>
          <vertex x="0" y="0" z="0"/>
          <vertex x="10" y="0" z="0"/>
          <vertex x="0" y="10" z="0"/>
        </vertices>
        <triangles>
          <triangle v1="0" v2="1" v3="2"/>
        </triangles>
      </mesh>
    </object>
  </resources>
  $build
</model>
"""

    private const val ContentTypes =
        """<?xml version="1.0" encoding="UTF-8"?><Types xmlns="x"><Default Extension="model"/></Types>"""

    private const val LocalHeaderSignature = 0x04034B50
    private const val CentralHeaderSignature = 0x02014B50
    private const val EndOfCentralDirectorySignature = 0x06054B50
    private const val NeedVersion = 20

    private fun ByteArrayBuilder.addShortLe(value: Int) {
        addBytes(byteArrayOf((value and 0xFF).toByte(), ((value ushr 8) and 0xFF).toByte()))
    }

    /** Minimal Base64 decoder — the stdlib one is still opt-in experimental on this Kotlin line. */
    private fun decodeBase64(text: String): ByteArray {
        val out = ByteArrayBuilder(text.length)
        var buffer = 0
        var bits = 0
        for (char in text) {
            val value = Base64Alphabet.indexOf(char)
            if (value < 0) continue
            buffer = (buffer shl 6) or value
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out.addBytes(byteArrayOf(((buffer ushr bits) and 0xFF).toByte()))
            }
        }
        return out.toArray()
    }

    private const val Base64Alphabet =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
}
