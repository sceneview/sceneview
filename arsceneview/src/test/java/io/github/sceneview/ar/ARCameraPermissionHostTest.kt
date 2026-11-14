package io.github.sceneview.ar

import android.content.Context
import com.google.ar.core.ArCoreApk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * JVM unit tests pinning when [ARCore] may start a session from a camera permission answer,
 * and that it never does for a host that is gone (#4452).
 *
 * `ARSceneView` hands an [ARCameraPermissionState] to its host, and a system dialog answers
 * whenever the user gets to it: both outlive the composable. Before `detachHost()`, a grant
 * arriving after the scene left composition created and resumed a session nobody rendered,
 * and held the camera.
 *
 * Robolectric only supplies the `Context` that [ARCore.resume] wants; no session is created —
 * [ARCore.startSession] is replaced by a counter.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class ARCameraPermissionHostTest {

    private class FakeHandler : ARPermissionHandler {
        var granted = false
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
        override fun shouldShowPermissionRationale(): Boolean = true

        override fun openAppSettings() = Unit

        override fun checkARCoreAvailability(): ArCoreApk.Availability =
            ArCoreApk.Availability.SUPPORTED_INSTALLED

        override fun requestARCoreInstall(userRequestedInstall: Boolean): Boolean = false

        fun answer(granted: Boolean) {
            this.granted = granted
            pendingAnswers.removeAt(0).invoke(granted)
        }
    }

    private val context: Context = RuntimeEnvironment.getApplication()
    private lateinit var handler: FakeHandler
    private lateinit var arCore: ARCore
    private lateinit var verdicts: MutableList<Boolean?>
    private var sessionsStarted = 0
    private var nowMs = 0L

    @Before
    fun setUp() {
        handler = FakeHandler()
        verdicts = mutableListOf()
        sessionsStarted = 0
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
            startSession = { sessionsStarted++ }
            onCameraPermissionDenied = { verdicts += it }
            onCameraPermissionGranted = { verdicts += null }
        }
    }

    // ── A resumed host ──────────────────────────────────────────────────────

    @Test
    fun `a grant that never paused the host starts the session once`() {
        // An in-app prompt: no system dialog, so no resume is coming to create the session.
        arCore.resume(context, handler)
        nowMs += 2_000
        handler.answer(granted = true)

        assertEquals(1, sessionsStarted)
    }

    @Test
    fun `a grant behind the system dialog leaves the start to the resume`() {
        arCore.resume(context, handler)
        arCore.pause()
        nowMs += 2_000
        handler.answer(granted = true)

        assertEquals(0, sessionsStarted)
    }

    @Test
    fun `asking again with the camera granted meanwhile starts the session`() {
        handler.instantAnswer = false
        arCore.resume(context, handler)
        handler.granted = true

        arCore.retryCameraPermission(handler)

        assertEquals(1, sessionsStarted)
        assertEquals(1, handler.requestCount)
        assertEquals(listOf(true, null), verdicts)
    }

    @Test
    fun `asking again on a paused host starts nothing`() {
        handler.instantAnswer = false
        arCore.resume(context, handler)
        arCore.pause()
        handler.granted = true

        arCore.retryCameraPermission(handler)

        assertEquals(0, sessionsStarted)
    }

    // ── A host that left ────────────────────────────────────────────────────

    @Test
    fun `a grant arriving after the scene left starts nothing and says nothing`() {
        arCore.resume(context, handler)
        arCore.detachHost()
        arCore.destroy()

        handler.answer(granted = true)

        assertEquals(0, sessionsStarted)
        assertNull(arCore.session)
        assertTrue(verdicts.isEmpty())
    }

    @Test
    fun `a refusal arriving after the scene left says nothing`() {
        arCore.resume(context, handler)
        arCore.detachHost()
        arCore.destroy()

        handler.answer(granted = false)

        assertTrue(verdicts.isEmpty())
    }

    @Test
    fun `a permission state kept past the scene neither asks nor starts`() {
        handler.instantAnswer = false
        arCore.resume(context, handler)
        arCore.detachHost()
        arCore.destroy()
        handler.granted = true

        // What `ARCameraPermissionState.request` runs.
        arCore.retryCameraPermission(handler)

        assertEquals(0, sessionsStarted)
        assertEquals(1, handler.requestCount)
        assertEquals(listOf<Boolean?>(true), verdicts)
    }

    // ── A request Android cancelled ─────────────────────────────────────────

    @Test
    fun `an empty permission result is no answer`() {
        assertNull(cameraPermissionAnswer(emptyMap()))
        assertEquals(true, cameraPermissionAnswer(mapOf("android.permission.CAMERA" to true)))
        assertEquals(false, cameraPermissionAnswer(mapOf("android.permission.CAMERA" to false)))
    }

    @Test
    fun `a cancelled request offers to ask again and is not a refusal`() {
        arCore.resume(context, handler)
        // Cancelled at once — as an answer, that would read as a blocked dialog.
        arCore.cancelPendingCameraRequest()

        assertEquals(listOf<Boolean?>(false), verdicts)

        // The user asks again and dismisses the dialog: still the first refusal, so still
        // "ask again" — a counted cancellation would have sent to settings here.
        arCore.retryCameraPermission(handler)
        arCore.pause()
        nowMs += 2_000
        handler.answer(granted = false)

        assertEquals(2, handler.requestCount)
        assertEquals(listOf<Boolean?>(false), verdicts)
    }

    @Test
    fun `cancelling with no request out does nothing`() {
        arCore.cancelPendingCameraRequest()

        assertTrue(verdicts.isEmpty())
    }
}
