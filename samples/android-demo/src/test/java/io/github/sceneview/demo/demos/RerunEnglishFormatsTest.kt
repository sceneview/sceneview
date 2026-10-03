package io.github.sceneview.demo.demos

import io.github.sceneview.demo.demos.internal.RerunSessionSource
import io.github.sceneview.demo.demos.internal.RerunStoredSession
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

    @Test
    fun `share sizes stay English across unit boundaries`() {
        assertEquals("0 B", formatFileSize(0))
        assertEquals("999 B", formatFileSize(999))
        assertEquals("1 kB", formatFileSize(1_000))
        assertEquals("1.0 MB", formatFileSize(1_000_000))
        assertEquals("12.4 MB", formatFileSize(12_400_000))
        assertEquals("1.0 GB", formatFileSize(1_000_000_000))
    }

    @Test
    fun `share and placement actions use the agreed English vocabulary`() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        val labels = mapOf(
            io.github.sceneview.demo.R.string.room_scan_place to "Place",
            io.github.sceneview.demo.R.string.room_scan_share to "Share",
            io.github.sceneview.demo.R.string.room_scan_close to "Close",
            io.github.sceneview.demo.R.string.room_scan_include_photos to "Include photos",
            io.github.sceneview.demo.R.string.ar_place_view_in_3d to "3D",
            io.github.sceneview.demo.R.string.ar_place_keep_scanning to "Try again",
            io.github.sceneview.demo.R.string.ar_place_scan_again to "Try again",
            io.github.sceneview.demo.R.string.ar_place_tracking_paused to "Move slowly.",
        )
        for ((id, expected) in labels) assertEquals(expected, context.getString(id))
        assertEquals(
            "This file contains photos of your room and the path you walked. " +
                "Anyone you send it to can see them.",
            context.getString(io.github.sceneview.demo.R.string.room_scan_share_privacy),
        )
    }

    // ICU puts a narrow no-break space before "PM" on recent JDKs and Android versions.
    private fun String.plainSpaces() = replace('\u202F', ' ').replace('\u00A0', ' ')

    private companion object {
        /** 2026-09-30 13:56 UTC. */
        const val SEP_30_13_56_UTC = 1_790_776_560_000L
    }
}
