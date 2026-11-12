package io.github.sceneview.demo.demos.internal.spatial

import java.util.Base64
import org.junit.Assert.*
import org.junit.Test

class SpatialLinkTest {
    private val desk = SpatialAnswerSpec(1, SpatialObject.Box(listOf(0.8f, 0.75f, 0.4f)),
        DimensionSource.USER, SpatialPlacement.FLOOR, "Desk 80 × 40 cm, 75 cm high")
    private fun raw(spec: SpatialAnswerSpec) = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(SpatialPayload.encode(spec).toByteArray(Charsets.UTF_8))

    /** The exact payload sceneview-mcp's `show_in_space` emits for a desk (#5015): the two sides share one contract. */
    @Test fun decodesTheMcpShowInSpacePayload() {
        val mcp = "eyJ2IjoxLCJvYmplY3QiOnsia2luZCI6ImJveCIsInNpemVNIjpbMC44LDAuNzUsMC40XX0sImRpbWVuc2lvblNvdXJjZSI6InVzZXIiLCJwbGFjZW1lbnQiOiJmbG9vciIsImxhYmVsIjoiRGVzayJ9"
        assertEquals(
            SpatialResult.Success(desk.copy(label = "Desk")),
            SpatialLink.decode(mcp),
        )
    }

    @Test fun boxAndAssetRoundTrip() {
        for (spec in listOf(desk, desk.copy(subject = SpatialObject.Asset("wishbone-chair")))) {
            val encoded = SpatialLink.encode(spec)
            assertTrue(encoded.matches(Regex("[A-Za-z0-9_-]+")))
            assertEquals(SpatialResult.Success(spec), SpatialLink.decode(encoded))
            assertEquals("sceneview://demo/spatial-answer?answer=$encoded", SpatialLink.customUri(spec))
            assertEquals("https://sceneview.github.io/open?demo=spatial-answer&answer=$encoded", SpatialLink.httpsUri(spec))
        }
    }
    @Test fun malformedBase64AndJson() {
        for (payload in listOf("", "%", "a", "ab", "e30=", "a+b/", "e30", "bnVsbA")) {
            assertEquals(payload, SpatialResult.Error(SpatialError.MalformedPayload), SpatialLink.decode(payload))
        }
    }
    @Test fun oversizedPayload() {
        assertEquals(SpatialResult.Error(SpatialError.MalformedPayload), SpatialLink.decode("A".repeat(2049)))
    }
    @Test fun decodedSpecsAreValidated() {
        val cases = listOf(
            desk.copy(v = 2) to SpatialError.UnsupportedVersion,
            desk.copy(label = "a".repeat(61)) to SpatialError.InvalidLabel,
            desk.copy(subject = SpatialObject.Box(listOf(0.01f, 1f, 1f))) to SpatialError.InvalidSize,
            desk.copy(subject = SpatialObject.Box(listOf(11f, 1f, 1f))) to SpatialError.InvalidSize,
            desk.copy(subject = SpatialObject.Asset("unknown")) to SpatialError.UnknownAsset,
        )
        for ((spec, error) in cases) {
            assertEquals(SpatialResult.Error(error), SpatialLink.decode(raw(spec)))
            assertThrows(IllegalArgumentException::class.java) { SpatialLink.encode(spec) }
        }
    }
}
