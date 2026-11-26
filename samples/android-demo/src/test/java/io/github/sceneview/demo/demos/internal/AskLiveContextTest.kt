package io.github.sceneview.demo.demos.internal

import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZonedDateTime
import java.util.Locale

class AskLiveContextTest {
    private val now = ZonedDateTime.parse("2026-11-25T12:34:56+01:00")
    private val floor = AskPlaneFacts(AskPlaneKind.Upward, -1f, 3f, 4f)
    private val table = AskPlaneFacts(AskPlaneKind.Upward, -0.2f, 1.2f, 0.8f)

    @Test fun `lowest upward plane determines floor and camera height`() {
        val context = buildAskLiveContext(2.4f, floor, listOf(table, floor), 0.6f, now, Locale.FRANCE)
        assertEquals(AskLiveSurface("floor", 3f, 4f), context.surface)
        assertEquals(1.6f, context.cameraHeightMeters!!, 0.0001f)
        assertEquals(2.4f, context.distanceAheadMeters)
        assertEquals("2026-11-25T12:34:56+01:00", context.localTime)
        assertEquals("fr-FR", context.locale)
    }

    @Test fun `higher upward plane is a table and vertical plane is a wall`() {
        assertEquals("table", buildAskLiveContext(1f, table, listOf(floor, table), 1f).surface!!.kind)
        val wall = AskPlaneFacts(AskPlaneKind.Vertical, 1f, 2f, 3f)
        assertEquals(AskLiveSurface("wall", 2f, 3f), buildAskLiveContext(2f, wall, listOf(floor), 1f).surface)
        val ceiling = wall.copy(kind = AskPlaneKind.Downward)
        assertEquals("ceiling", buildAskLiveContext(2f, ceiling, emptyList(), 1f).surface!!.kind)
    }

    @Test fun `missing floor never invents camera height or placement distance`() {
        val context = buildAskLiveContext(null, null, emptyList(), 1f)
        assertNull(context.distanceAheadMeters)
        assertNull(context.cameraHeightMeters)
        assertNull(context.surface)
        assertFalse(context.toJson().containsKey("distanceAheadMeters"))
        assertFalse(context.toJson().containsKey("cameraHeightMeters"))
    }

    @Test fun `invalid dimensions and negative camera height stay absent`() {
        val invalid = floor.copy(extentX = Float.NaN, extentZ = 0f)
        val context = buildAskLiveContext(Float.POSITIVE_INFINITY, invalid, listOf(floor), -2f)
        assertNull(context.distanceAheadMeters)
        assertNull(context.surface!!.widthMeters)
        assertNull(context.surface.heightMeters)
        assertNull(context.cameraHeightMeters)
    }

    @Test fun `JSON includes the optional JPEG and all supported facts`() {
        val context = buildAskLiveContext(0.7f, floor, listOf(floor), 1f, now, Locale.US)
            .copy(image = AskLiveImage("/9j/2Q=="))
        val json = context.toJson()
        assertEquals("floor", json["surface"]!!.jsonObject["kind"]!!.jsonPrimitive.content)
        assertEquals("image/jpeg", json["image"]!!.jsonObject["mediaType"]!!.jsonPrimitive.content)
        assertEquals("/9j/2Q==", json["image"]!!.jsonObject["data"]!!.jsonPrimitive.content)
        assertFalse(context.copy(image = null).toJson().containsKey("image"))
    }
}
