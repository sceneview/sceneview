package io.github.sceneview.demo.demos

import io.github.sceneview.demo.demos.internal.ArDebugFormat
import io.github.sceneview.demo.demos.internal.RerunSessionSource
import io.github.sceneview.demo.demos.internal.RerunStoredSession
import io.github.sceneview.demo.demos.internal.formatFileSize
import io.github.sceneview.demo.demos.internal.liveCaptureStats
import io.github.sceneview.demo.demos.internal.rerunStatusUx
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
 * "4,7 Mo" in them. Dates, words and units stay English whatever the phone's language; the
 * numbers take the phone's decimal separator, as every figure of the app does: "4,7 MB" on a
 * French phone, "4.7 MB" on an English one.
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
    fun `an export's size keeps English units with the French decimal comma`() {
        assertEquals("4,7 MB", formatFileSize(4_700_000))
    }

    /** The debug figures (distances, counts) take the phone's separators; the clock has none. */
    @Test
    fun `the debug figures take the French separators`() {
        assertEquals("3,2 m", ArDebugFormat.distance(3.2f))
        assertEquals("1 204 m", ArDebugFormat.distance(1204f).plainSpaces())
        assertEquals("25 300", ArDebugFormat.count(25_300).plainSpaces())
        assertEquals("4,8k", ArDebugFormat.compactCount(4_812))
        assertEquals("1 204 m²", ArDebugFormat.area(1204f).plainSpaces())
        assertEquals("0:18", ArDebugFormat.clock(18.4f))
    }

    /** The replay's live row and the streaming status count with the same separators. */
    @Test
    fun `the live row and the streaming status take the French separators`() {
        val live = liveCaptureStats(frames = 1204, metersMoved = 2.3f, surfaces = 1, placements = 3)
        assertEquals(listOf("1 204", "2,3 m"), live.take(2).map { it.value.plainSpaces() })
        val status = rerunStatusUx(isConnected = true, eventsSent = 1204, eventsPerSecond = 10f)
        assertEquals("1 204 events sent · 10 per second", status.detail.plainSpaces())
    }

    @Test
    fun `German writes the comma, British and US English the point`() {
        Locale.setDefault(Locale.GERMANY)
        assertEquals("4,7 MB" to "1.204 m", formatFileSize(4_700_000) to ArDebugFormat.distance(1204f))
        assertEquals("3,2 m" to "4,8k", ArDebugFormat.distance(3.2f) to ArDebugFormat.compactCount(4_812))
        Locale.setDefault(Locale.UK)
        assertEquals("4.7 MB" to "1,204 m", formatFileSize(4_700_000) to ArDebugFormat.distance(1204f))
        Locale.setDefault(Locale.US)
        assertEquals("3.2 m" to "4.8k", ArDebugFormat.distance(3.2f) to ArDebugFormat.compactCount(4_812))
    }

    @Test
    fun `share sizes keep English units across unit boundaries`() {
        assertEquals("0 B", formatFileSize(0))
        assertEquals("999 B", formatFileSize(999))
        assertEquals("1 kB", formatFileSize(1_000))
        assertEquals("1,0 MB", formatFileSize(1_000_000))
        assertEquals("12,4 MB", formatFileSize(12_400_000))
        assertEquals("1,0 GB", formatFileSize(1_000_000_000))
    }

    @Test
    fun `a size rounds before it picks its unit`() {
        assertEquals("999 kB", formatFileSize(999_499))
        assertEquals("1,0 MB", formatFileSize(999_500))
        assertEquals("1,0 MB", formatFileSize(999_999))
        assertEquals("999,9 MB", formatFileSize(999_949_999))
        assertEquals("1,0 GB", formatFileSize(999_950_000))
    }

    @Test
    fun `share and placement actions use the agreed English vocabulary`() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        val labels = mapOf(
            io.github.sceneview.demo.R.string.scan_place to "Place in AR",
            io.github.sceneview.demo.R.string.scan_share to "Share",
            io.github.sceneview.demo.R.string.scan_close to "Close",
            io.github.sceneview.demo.R.string.scan_share_include_photos to "Include photos",
            io.github.sceneview.demo.R.string.ar_place_view_in_3d to "3D",
            io.github.sceneview.demo.R.string.ar_place_keep_scanning to "Try again",
            io.github.sceneview.demo.R.string.ar_place_scan_again to "Try again",
            io.github.sceneview.demo.R.string.ar_place_show_in_3d to "Show in 3D",
            io.github.sceneview.demo.R.string.ar_place_tracking_paused to "Move slowly",
            io.github.sceneview.demo.R.string.ar_place_tracking_paused_low_light to
                "Move slowly. Try a brighter area.",
        )
        for ((id, expected) in labels) assertEquals(expected, context.getString(id))
        assertEquals(
            "This file contains your room in 3D, its photos and the path you walked. " +
                "Anyone you send it to can see them.",
            context.getString(io.github.sceneview.demo.R.string.scan_share_privacy),
        )
    }

    @Test
    fun `a scan shared without photos still says it shows the room`() {
        // The baked surfaces are camera pixels and the points carry their colours: the sentence
        // may not promise a file free of images.
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        assertEquals(
            "Even without photos, the 3D surfaces and points in this file show your room " +
                "and the path you walked. Anyone you send it to can see them.",
            context.getString(io.github.sceneview.demo.R.string.scan_share_privacy_without_photos),
        )
    }

    // ICU puts a narrow no-break space before "PM" on recent JDKs and Android versions.
    private fun String.plainSpaces() = replace('\u202F', ' ').replace('\u00A0', ' ')

    private companion object {
        /** 2026-09-30 13:56 UTC. */
        const val SEP_30_13_56_UTC = 1_790_776_560_000L
    }

    /** "Room · Oct 19, 11:58 AM · 19 Oct 2026" wrote the date twice, in two formats (#4714, #4720). */
    @Test
    fun `the plan writes a scan's date once, in the format of every other screen`() {
        val seconds = SEP_30_13_56_UTC / 1000
        val titled = planHeading(recordingTitle(SEP_30_13_56_UTC), seconds)
        assertEquals("Room · Sep 30, 2026, 1:56 PM", titled.title.plainSpaces())
        assertEquals(null, titled.date)
        val renamed = planHeading("Garden", seconds)
        assertEquals("Garden", renamed.title)
        assertEquals("Sep 30, 2026, 1:56 PM", renamed.date?.plainSpaces())
        assertEquals(PlanHeading("Garden", null), planHeading("Garden", null))
    }

    @Test
    fun `a card whose title says when does not say it again`() {
        val session = RerunStoredSession(
            id = "A",
            title = recordingTitle(SEP_30_13_56_UTC),
            createdAt = SEP_30_13_56_UTC / 1000,
            source = RerunSessionSource.Recorded,
            duration = 1f,
            pathMetres = 1f,
            points = 1,
            planes = 1,
            photos = 1,
        )
        assertEquals(RerunSessionSource.Recorded.label, sessionOrigin(session))
    }
}
