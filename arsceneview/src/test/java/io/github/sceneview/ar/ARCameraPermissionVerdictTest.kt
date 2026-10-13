package io.github.sceneview.ar

import com.google.ar.core.ArCoreApk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * JVM unit tests pinning what [ARCore] says about a refused camera permission (#4452).
 *
 * Measured on a Pixel 4a before the fix: a system dialog dismissed with Back was reported
 * as a permanent denial — "Camera access was turned off… Open settings" — although Android
 * would have shown the dialog again; and a host had no signal at all that the session was
 * waiting for the camera, so its own loading card stayed up over the explanation.
 */
class ARCameraPermissionVerdictTest {

    /** A permission handler whose dialog is answered by the test. */
    private class FakeHandler : ARPermissionHandler {
        var granted = false

        /** `true` when Android would ask the app to explain itself (refused once). */
        var rationale = false

        /** Answers given at once, as a blocked dialog does. `null` keeps the request open. */
        var instantAnswer: Boolean? = null

        val pendingAnswers = mutableListOf<(Boolean) -> Unit>()
        var requestCount = 0

        override fun hasCameraPermission(): Boolean = granted

        override fun requestCameraPermission(onResult: (granted: Boolean) -> Unit) {
            requestCount++
            val answer = instantAnswer
            if (answer != null) onResult(answer) else pendingAnswers += onResult
        }

        // Inverted on this interface: `true` means there is NO rationale to show.
        override fun shouldShowPermissionRationale(): Boolean = !rationale

        override fun openAppSettings() = Unit

        override fun checkARCoreAvailability(): ArCoreApk.Availability =
            ArCoreApk.Availability.SUPPORTED_INSTALLED

        override fun requestARCoreInstall(userRequestedInstall: Boolean): Boolean = false

        /** The user answers the dialog that is on screen. */
        fun answer(granted: Boolean, rationaleAfter: Boolean = rationale) {
            this.granted = granted
            rationale = rationaleAfter
            pendingAnswers.removeAt(0).invoke(granted)
        }
    }

    private lateinit var handler: FakeHandler
    private lateinit var arCore: ARCore

    /** `true` / `false` for a denial verdict, `null` for a grant. */
    private lateinit var verdicts: MutableList<Boolean?>
    private var nowMs = 0L

    @Before
    fun setUp() {
        handler = FakeHandler()
        verdicts = mutableListOf()
        nowMs = 0L
        arCore = ARCore(
            onSessionCreated = {},
            onSessionResumed = {},
            onSessionPaused = {},
            onArSessionFailed = {},
            onSessionConfigChanged = { _, _ -> },
        ).apply {
            permissionHandler = handler
            nanoTime = { nowMs * 1_000_000L }
            onCameraPermissionDenied = { verdicts += it }
            onCameraPermissionGranted = { verdicts += null }
        }
    }

    /** The user looks at the dialog for [ms] before answering. */
    private fun userTakes(ms: Long) {
        // The system dialog is another activity: the host is paused while it shows.
        arCore.pause()
        nowMs += ms
    }

    // ── The pure verdict ────────────────────────────────────────────────────

    @Test
    fun `a refusal that raises the rationale flag is never blocked`() {
        assertFalse(isCameraPromptBlocked(false, true, 0, 0))
        assertFalse(isCameraPromptBlocked(true, true, 5_000, 3))
    }

    @Test
    fun `the rationale flag going down is the second refusal`() {
        assertTrue(isCameraPromptBlocked(true, false, 5_000, 0))
    }

    @Test
    fun `an answer faster than a dialog can be read is a blocked dialog`() {
        assertTrue(isCameraPromptBlocked(false, false, 0, 0))
        // The two answers measured on the Pixel 4a: idle screen, then a scene still loading.
        assertTrue(isCameraPromptBlocked(false, false, 289, 0))
        assertTrue(isCameraPromptBlocked(false, false, 509, 0))
        assertTrue(isCameraPromptBlocked(false, false, CAMERA_PROMPT_INSTANT_RETURN_MS - 1, 0))
    }

    @Test
    fun `a first slow unexplained refusal is a dismissed dialog`() {
        assertFalse(isCameraPromptBlocked(false, false, CAMERA_PROMPT_INSTANT_RETURN_MS, 0))
        assertFalse(isCameraPromptBlocked(false, false, 4_000, 0))
    }

    @Test
    fun `a second slow unexplained refusal sends to settings`() {
        assertTrue(isCameraPromptBlocked(false, false, 4_000, 1))
    }

    // ── What ARCore publishes ───────────────────────────────────────────────

    @Test
    fun `first refusal offers to ask again`() {
        assertFalse(arCore.checkPermissionAndInstall(handler))
        userTakes(2_000)
        handler.answer(granted = false, rationaleAfter = true)

        assertEquals(listOf<Boolean?>(false), verdicts)
        assertTrue(arCore.isCameraPermissionDenied)
    }

    @Test
    fun `dismissed dialog offers to ask again`() {
        // Back, or a tap outside: refused, and no rationale flag — the exact shape of a
        // permanent denial, which is what it used to be reported as.
        arCore.checkPermissionAndInstall(handler)
        userTakes(2_000)
        handler.answer(granted = false, rationaleAfter = false)

        assertEquals(listOf<Boolean?>(false), verdicts)
    }

    @Test
    fun `permanently denied sends to settings`() {
        handler.instantAnswer = false

        assertFalse(arCore.checkPermissionAndInstall(handler))

        assertEquals(listOf<Boolean?>(true), verdicts)
        assertTrue(arCore.isCameraPermissionDenied)
    }

    @Test
    fun `second refusal from the dialog sends to settings`() {
        handler.rationale = true
        arCore.checkPermissionAndInstall(handler)
        userTakes(3_000)
        handler.answer(granted = false, rationaleAfter = false)

        assertEquals(listOf<Boolean?>(true), verdicts)
    }

    @Test
    fun `dismissing the dialog twice sends to settings`() {
        arCore.checkPermissionAndInstall(handler)
        userTakes(2_000)
        handler.answer(granted = false)
        arCore.retryCameraPermission(handler)
        userTakes(2_000)
        handler.answer(granted = false)

        assertEquals(listOf<Boolean?>(false, true), verdicts)
        assertEquals(2, handler.requestCount)
    }

    @Test
    fun `asks once however many times the host resumes`() {
        arCore.checkPermissionAndInstall(handler)
        arCore.checkPermissionAndInstall(handler)
        userTakes(2_000)
        handler.answer(granted = false, rationaleAfter = true)
        arCore.checkPermissionAndInstall(handler)
        arCore.checkPermissionAndInstall(handler)

        assertEquals(1, handler.requestCount)
        assertEquals(listOf<Boolean?>(false), verdicts)
    }

    @Test
    fun `an answer that never arrives still explains itself`() {
        arCore.checkPermissionAndInstall(handler)
        userTakes(2_000)
        // The host is back in front and nobody delivered the answer.
        assertFalse(arCore.checkPermissionAndInstall(handler))

        assertEquals(listOf<Boolean?>(false), verdicts)
        assertTrue(arCore.isCameraPermissionDenied)
    }

    @Test
    fun `a replaced handler does not leave the request unanswered`() {
        arCore.checkPermissionAndInstall(handler)
        val replacement = FakeHandler()

        assertFalse(arCore.checkPermissionAndInstall(replacement))

        assertEquals(listOf<Boolean?>(false), verdicts)
        assertEquals(0, replacement.requestCount)
    }

    @Test
    fun `a late answer corrects the fallback verdict`() {
        handler.rationale = true
        arCore.checkPermissionAndInstall(handler)
        userTakes(2_000)
        arCore.checkPermissionAndInstall(handler)
        handler.answer(granted = false, rationaleAfter = false)

        assertEquals(listOf<Boolean?>(false, true), verdicts)
    }

    @Test
    fun `a grant takes the explanation down`() {
        arCore.checkPermissionAndInstall(handler)
        userTakes(2_000)
        handler.answer(granted = false, rationaleAfter = true)
        arCore.retryCameraPermission(handler)
        userTakes(1_500)
        handler.answer(granted = true)

        assertEquals(listOf(false, null), verdicts)
        assertFalse(arCore.isCameraPermissionDenied)
    }

    @Test
    fun `a grant from settings takes the explanation down on the next resume`() {
        handler.instantAnswer = false
        arCore.checkPermissionAndInstall(handler)
        arCore.pause()
        handler.granted = true

        assertTrue(arCore.checkPermissionAndInstall(handler))

        assertEquals(listOf(true, null), verdicts)
        assertFalse(arCore.isCameraPermissionDenied)
    }

    @Test
    fun `a grant with nothing denied publishes nothing`() {
        handler.granted = true

        assertTrue(arCore.checkPermissionAndInstall(handler))

        assertTrue(verdicts.isEmpty())
    }

    @Test
    fun `the same verdict is published once`() {
        handler.instantAnswer = false
        arCore.checkPermissionAndInstall(handler)
        arCore.retryCameraPermission(handler)
        arCore.retryCameraPermission(handler)

        assertEquals(listOf<Boolean?>(true), verdicts)
        assertEquals(3, handler.requestCount)
    }
}
