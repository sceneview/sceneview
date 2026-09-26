package io.github.sceneview.demo.demos.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the Rerun Debug demo's on-screen copy (#3831): the demo used to open on
 * "Saving needs the Rerun recording service on a connected computer. Open Settings for
 * connection details." for every Play Store user without a computer attached.
 */
class RerunStatusUxTest {

    private val jargon = listOf("sidecar", "service", "Settings", "ARCore", "tracking", "dev machine", "127.0.0.1")

    private fun assertPlain(text: String) {
        for (word in jargon) {
            assertFalse("'$text' contains '$word'", text.contains(word, ignoreCase = true))
        }
    }

    @Test
    fun `without a computer the status says what the demo does, calmly`() {
        val ux = rerunStatusUx(isConnected = false, eventsSent = 0, eventsPerSecond = 0f)
        assertFalse(ux.live)
        assertEquals("No computer connected", ux.title)
        assertEquals(RERUN_INTRO, ux.detail)
        assertPlain(ux.title)
        assertPlain(ux.detail)
    }

    @Test
    fun `while connected the status counts what reached the computer`() {
        val ux = rerunStatusUx(isConnected = true, eventsSent = 1204, eventsPerSecond = 10f)
        assertTrue(ux.live)
        assertEquals("Streaming to your computer", ux.title)
        assertEquals("1,204 events sent · 10 per second", ux.detail)
        assertEquals("1 event sent", rerunStatusUx(true, 1, 0f).detail)
        assertEquals("0 events sent", rerunStatusUx(true, 0, Float.NaN).detail)
    }

    @Test
    fun `the intro is one plain sentence`() {
        assertPlain(RERUN_INTRO)
        assertEquals(1, RERUN_INTRO.count { it == '.' })
        assertTrue(RERUN_INTRO.endsWith("."))
    }

    @Test
    fun `setup steps carry the two commands a developer must type`() {
        val commands = RERUN_SETUP_STEPS.mapNotNull { it.command }
        assertEquals(
            listOf(
                "adb reverse tcp:9876 tcp:9876",
                "python3 samples/android-demo/tools/rerun-bridge.py --save recording.rrd",
            ),
            commands,
        )
        RERUN_SETUP_STEPS.forEach { assertPlain(it.text) }
    }

    @Test
    fun `save is offered only when it can work`() {
        assertFalse(rerunShowsSaveAction(isConnected = false, sharing = false))
        assertTrue(rerunShowsSaveAction(isConnected = true, sharing = false))
        assertTrue(rerunShowsSaveAction(isConnected = false, sharing = true))
    }

    @Test
    fun `save labels and failures carry no jargon either`() {
        listOf(
            rerunSaveActionUx(sharing = false, isConnected = false).label,
            rerunSaveActionUx(sharing = false, isConnected = true).label,
            rerunSaveActionUx(sharing = true, isConnected = true).label,
            rerunSaveFailureMessage(null),
            rerunSaveFailureMessage("sidecar is in live mode; restart with --save"),
        ).forEach(::assertPlain)
    }
}
