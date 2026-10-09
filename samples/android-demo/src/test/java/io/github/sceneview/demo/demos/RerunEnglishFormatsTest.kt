package io.github.sceneview.demo.demos

import io.github.sceneview.demo.demos.internal.ArDebugFormat
import io.github.sceneview.demo.demos.internal.RerunMesh
import io.github.sceneview.demo.demos.internal.RerunSessionSource
import io.github.sceneview.demo.demos.internal.RerunStoredSession
import io.github.sceneview.demo.demos.internal.RoomMeasure
import io.github.sceneview.demo.demos.internal.formatFileSize
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale
import java.util.TimeZone

/**
 * The Rerun screens are in English: a French Pixel 9 showed "Room · 30 sept., 13:56" and
 * "4,7 Mo" in them. Dates and sizes stay English whatever the phone's language.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RerunEnglishFormatsTest {
    private lateinit var locale: Locale
    private lateinit var zone: TimeZone

    @Before
    fun frenchPhone() {
        locale = Locale.getDefault()
        zone = TimeZone.getDefault()
        Locale.setDefault(Locale.FRANCE)
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    }

    @After
    fun restore() {
        Locale.setDefault(locale)
        TimeZone.setDefault(zone)
    }

    @Test
    fun `a scan recorded on a French phone is titled in English`() {
        assertEquals("Room · Sep 30, 1:56 PM", recordingTitle(SEP_30_13_56_UTC).plainSpaces())
    }

    @Test
    fun `a session's date reads in English on a French phone`() {
        val session = RerunStoredSession(
            id = "A",
            title = "Room",
            createdAt = SEP_30_13_56_UTC / 1000,
            source = RerunSessionSource.Recorded,
            duration = 1f,
            pathMetres = 1f,
            points = 1,
            planes = 1,
            photos = 1,
        )
        assertEquals("Sep 30, 1:56 PM · ${RerunSessionSource.Recorded.label}", sessionOrigin(session).plainSpaces())
    }

    @Test
    fun `an export's size reads in English on a French phone`() {
        assertEquals("4.7 MB", formatFileSize(4_700_000))
    }

    /**
     * A French Pixel 9 showed "3,3 × 3,7 m · 24k triangles" under "Room 3.2 × 6.3 m": the figures
     * card, the measures in the room and the surface's caption all keep the English point.
     */
    @Test
    fun `the room's figures keep the decimal point on a French phone`() {
        assertEquals("3.3 m", RoomMeasure.metres(3.3f))
        assertEquals("3.3", RoomMeasure.metres(3.3f, unit = false))
        assertEquals("7.2 m²", RoomMeasure.squareMetres(7.2f))
        assertEquals("3.2 m", ArDebugFormat.distance(3.2f))
        assertEquals("1,204 m", ArDebugFormat.distance(1204f))
        assertEquals("25,300", ArDebugFormat.count(25_300))
        assertEquals("4.8k", ArDebugFormat.compactCount(4_812))
        assertEquals("0:18", ArDebugFormat.clock(18.4f))
    }

    @Test
    fun `the surface's caption carries no size, and no comma, on a French phone`() {
        val build = RerunModelBuild(
            mesh = RerunMesh(FloatArray(0), FloatArray(0), IntArray(0), IntArray(0)),
            glb = ByteArray(0),
            bounds = floatArrayOf(0f, 0f, 0f, 3.3f, 1.2f, 3.7f),
            triangles = 24_300,
            vertices = 12_000,
            buildMs = 1_500,
            voxelBytes = 0,
            budgetReached = false,
        )
        assertEquals("Surface preview · 24k triangles", ModelCopy.stats(build))
    }

    // ICU puts a narrow no-break space before "PM" on recent JDKs and Android versions.
    private fun String.plainSpaces() = replace('\u202F', ' ').replace('\u00A0', ' ')

    private companion object {
        /** 2026-09-30 13:56 UTC. */
        const val SEP_30_13_56_UTC = 1_790_776_560_000L
    }
}
