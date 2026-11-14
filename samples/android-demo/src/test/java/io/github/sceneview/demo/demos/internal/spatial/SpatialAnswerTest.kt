package io.github.sceneview.demo.demos.internal.spatial

import org.junit.Assert.*
import org.junit.Test

class SpatialAnswerTest {
    private val desk = "Show an 80 × 40 cm desk, 75 cm high, here"
    private fun parsed(text: String): SpatialAnswerSpec = (SpatialSentenceParser.parse(text) as SpatialResult.Success).spec
    private fun assertSize(text: String) {
        val spec = parsed(text)
        val size = (spec.subject as SpatialObject.Box).sizeM
        listOf(0.8f, 0.75f, 0.4f).zip(size).forEach { (expected, actual) -> assertEquals(expected, actual, 0.00001f) }
    }

    @Test fun deskSentence() {
        assertSize(desk)
        assertEquals("Desk", parsed(desk).label)
        assertEquals(DimensionSource.USER, parsed(desk).dimensionSource)
        assertEquals(SpatialPlacement.FLOOR, parsed(desk).placement)
    }
    @Test fun millimetresMetresAndBy() {
        assertSize("Show an 800 by 400 mm desk, 750 mm tall, here")
        assertSize("Show a 0.8 x 0.4 m desk, 0.75 m high, here")
        assertSize("Show a desk 800 mm wide, 0.75 m tall and 40 cm deep")
        assertSize("Show a desk 0.8 m by 40 cm, 750 mm high")
    }
    @Test fun explicitTablePlacement() {
        assertEquals(SpatialPlacement.TABLE, parsed("Show an 80 by 40 cm box, 75 cm high on the table").placement)
    }
    @Test fun unknownOrAmbiguousSentencesAreNotGuessed() {
        listOf("Make me a desk", "Show a large desk", "Show an 80 by 40 desk, 75 high", "Show an 80 x 40 cm desk, 75 cm high and 90 cm tall", "$desk and a chair", "Show a desk 80 cm wide 75 cm high 40 cm deep 2", "Show a desk 80 cm wide 75 cm high 40 cm deep, in red").forEach {
            assertEquals(it, SpatialResult.Error(SpatialError.UnknownSentence), SpatialSentenceParser.parse(it))
        }
    }
    @Test fun invalidSizes() {
        for (size in listOf(-1f, 0f, 0.01f, 10.01f, Float.NaN, Float.POSITIVE_INFINITY)) {
            val spec = parsed(desk).copy(subject = SpatialObject.Box(listOf(size, 1f, 1f)))
            assertEquals(SpatialError.InvalidSize, spec.validate())
        }
        assertNull(parsed(desk).copy(subject = SpatialObject.Box(listOf(0.0101f, 10f, 1f))).validate())
        assertEquals(SpatialError.InvalidSize, parsed(desk).copy(subject = SpatialObject.Box(listOf(1f, 2f))).validate())
        assertEquals(SpatialResult.Error(SpatialError.InvalidSize), SpatialSentenceParser.parse("Show a 0 x 40 cm desk, 75 cm high"))
    }
    @Test fun strictVersionAndLabel() {
        assertEquals(SpatialError.UnsupportedVersion, parsed(desk).copy(v = 2).validate())
        assertEquals(SpatialError.InvalidLabel, parsed(desk).copy(label = "a".repeat(61)).validate())
        assertNull(parsed(desk).copy(label = "a".repeat(60)).validate())
    }
    @Test fun unknownAssetIdAndUrls() {
        for (id in listOf("unknown", "https://example.com/desk.glb", "../models/chair.glb")) {
            assertEquals(SpatialError.UnknownAsset, parsed(desk).copy(subject = SpatialObject.Asset(id)).validate())
        }
        assertNull(parsed(desk).copy(subject = SpatialObject.Asset("wishbone-chair")).validate())
    }
    @Test fun strictPayloadAndRoundTrip() {
        val spec = parsed(desk)
        assertEquals(SpatialResult.Success(spec), SpatialPayload.decode(SpatialPayload.encode(spec)))
        val payload = """{"v":1,"object":{"kind":"box","sizeM":[0.8,0.75,0.4]},"dimensionSource":"user","placement":"floor","label":"Desk"}"""
        val decoded = (SpatialPayload.decode(payload) as SpatialResult.Success).spec
        assertEquals(spec.label, decoded.label)
        (spec.subject as SpatialObject.Box).sizeM.zip((decoded.subject as SpatialObject.Box).sizeM).forEach { (a, b) -> assertEquals(a, b, 0.00001f) }
        listOf("{}", "null", payload.replace("\"v\":1", "\"v\":1,\"extra\":true"), payload.replace("\"user\"", "\"unknown\""), payload.replace("\"box\"", "\"sphere\""), payload.replace("[0.8,0.75,0.4]", "[0.8]")).forEach {
            assertTrue(it, SpatialPayload.decode(it) is SpatialResult.Error)
        }
        val asset = spec.copy(subject = SpatialObject.Asset("wishbone-chair"))
        assertEquals(SpatialResult.Success(asset), SpatialPayload.decode(SpatialPayload.encode(asset)))
        assertEquals(SpatialResult.Error(SpatialError.UnknownAsset), SpatialPayload.decode(SpatialPayload.encode(asset.copy(subject = SpatialObject.Asset("unknown")))))
    }
}
