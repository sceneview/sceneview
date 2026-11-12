package io.github.sceneview.ar

import android.content.Context
import android.hardware.display.DisplayManager
import android.view.Display
import android.view.WindowManager
import com.google.ar.core.RecordingStatus
import com.google.ar.core.Session
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.exceptions.FatalException
import io.github.sceneview.ar.arcore.ARSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowLog
import org.robolectric.shadows.ShadowLooper

/**
 * A failed `Session.resume()` reaches [ARCore.onArSessionFailed] instead of the host app.
 *
 * ARCore throws from `resume()` when another app holds the camera
 * ([CameraNotAvailableException]), when the permission was revoked ([SecurityException]), or on
 * an internal error ([FatalException], [IllegalStateException]). [ARCore] resumes from the host's
 * `onResume`, from a permission answer and from [ARCore.retrySession]: before the fix the
 * exception escaped all three and crashed the app.
 *
 * Robolectric instruments `com.google.ar.core` and [ResumeFailingSession] replaces the two
 * `Session` methods the path reaches: the native constructor and `resume()` (throw
 * [creationFailure] and [resumeFailure], when set). [ARSession] itself, and every line of
 * [ARCore], run for real.
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    instrumentedPackages = ["com.google.ar.core"],
    shadows = [ARSessionResumeFailureTest.ResumeFailingSession::class],
)
class ARSessionResumeFailureTest {

    private lateinit var context: Context
    private lateinit var arCore: ARCore
    private lateinit var failures: MutableList<Exception>
    private var created: Session? = null
    private var createdCallbacks = 0
    private var resumedCallbacks = 0
    private val published = mutableListOf<ARCoreAvailability?>()

    @Before
    fun setUp() {
        // A window context: `ARSession` reads the display, which an Application context lacks.
        val app = RuntimeEnvironment.getApplication()
        val display = app.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        context = app.createWindowContext(display, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
        failures = mutableListOf()
        created = null
        createdCallbacks = 0
        resumedCallbacks = 0
        creationFailure = null
        creationCalls = 0
        resumeFailure = null
        geometryFailure = null
        resumeCalls = 0
        published.clear()
        ShadowLog.clear()
        arCore = newARCore(onSessionResumed = { resumedCallbacks++ })
    }

    private fun newARCore(
        onSessionResumed: (Session) -> Unit,
        onArSessionFailed: (Exception) -> Unit = { failures += it },
    ) =
        ARCore(
            onSessionCreated = {
                created = it
                createdCallbacks++
            },
            onSessionResumed = onSessionResumed,
            onSessionPaused = {},
            onArSessionFailed = onArSessionFailed,
            onSessionConfigChanged = { _, _ -> },
        ).apply {
            checkCameraPermission = false
            features = emptySet()
            onARCoreAvailability = { published += it }
        }

    @After
    fun tearDown() {
        arCore.detachHost()
        // The shadow makes close synchronous and native-free; remove the arbiter's client too.
        arCore.session?.close()
        arCore.destroy()
    }

    @Test
    fun `every documented resume failure reaches onArSessionFailed from the host resume`() {
        listOf(
            CameraNotAvailableException("camera held by another app"),
            SecurityException("camera permission revoked"),
            FatalException("ARCore internal error"),
            IllegalStateException("ARCore internal state"),
        ).forEach { failure ->
            setUp()
            resumeFailure = failure

            arCore.resume(context, handler = null)

            assertNotNull("The session is created before it is resumed", created)
            assertEquals(listOf<Exception>(failure), failures)
            assertEquals(listOf<ARCoreAvailability?>(ARCoreAvailability.SessionFailed), published)
            assertFalse(
                "A session ARCore refused to resume must not read as resumed (${failure::class.simpleName})",
                (created as ARSession).isResumed,
            )
        }
    }

    @Test
    fun `retrying a resume failure preserves the live session and reports another failure`() {
        val failure = CameraNotAvailableException("camera held by another app")
        resumeFailure = failure
        arCore.resume(context, handler = null)
        val failed = arCore.session!!

        arCore.retrySession(context)

        assertSame(failed, arCore.session)
        assertFalse(failed.isClosed)
        assertFalse(failed.isResumed)
        assertEquals(1, creationCalls)
        assertEquals(1, createdCallbacks)
        assertEquals(2, resumeCalls)
        assertEquals(listOf<Exception>(failure, failure), failures)
        assertEquals(listOf<ARCoreAvailability?>(ARCoreAvailability.SessionFailed), published)
    }

    @Test
    fun `retrying a running session never calls native resume again`() {
        arCore.resume(context, handler = null)
        val running = arCore.session!!
        resumeFailure = CameraNotAvailableException("a second resume would fail")

        arCore.retrySession(context)

        assertSame(running, arCore.session)
        assertTrue(running.isResumed)
        assertEquals(1, resumeCalls)
        assertEquals(1, resumedCallbacks)
        assertTrue(failures.isEmpty())
        assertTrue(published.isEmpty())
    }

    @Test
    fun `retrying a fatal resume failure recreates the session`() {
        val failure = FatalException("unrecoverable native session")
        resumeFailure = failure
        arCore.resume(context, handler = null)
        val failed = arCore.session!!
        resumeFailure = null

        arCore.retryARCoreAvailability()

        assertNotSame(failed, arCore.session)
        assertTrue(failed.isClosed)
        assertTrue(arCore.session!!.isResumed)
        assertEquals(2, creationCalls)
        assertEquals(2, createdCallbacks)
        assertEquals(2, resumeCalls)
        assertEquals(1, resumedCallbacks)
        assertEquals(listOf<Exception>(failure), failures)
        assertEquals(listOf(ARCoreAvailability.SessionFailed, null), published)
    }

    @Test
    fun `retrying a creation failure creates and resumes a session`() {
        val failure = IllegalStateException("session creation failed")
        creationFailure = failure
        arCore.resume(context, handler = null)
        assertNull(arCore.session)
        assertEquals(0, createdCallbacks)
        assertEquals(0, resumeCalls)
        assertEquals(listOf<Exception>(failure), failures)
        assertEquals(ARCoreAvailability.SessionFailed, arCore.arCoreAvailability)
        creationFailure = null

        arCore.retryARCoreAvailability()

        assertSame(created, arCore.session)
        assertTrue(arCore.session!!.isResumed)
        assertEquals(2, creationCalls)
        assertEquals(1, createdCallbacks)
        assertEquals(1, resumedCallbacks)
        assertEquals(listOf(ARCoreAvailability.SessionFailed, null), published)
    }

    @Test
    fun `retrying a closed session creates another session`() {
        resumeFailure = CameraNotAvailableException("busy")
        arCore.resume(context, handler = null)
        val closed = arCore.session!!
        closed.close()
        resumeFailure = null

        arCore.retrySession(context)

        assertNotSame(closed, arCore.session)
        assertTrue(arCore.session!!.isResumed)
        assertEquals(2, creationCalls)
        assertEquals(2, createdCallbacks)
        assertEquals(2, resumeCalls)
        assertEquals(listOf(ARCoreAvailability.SessionFailed, null), published)
    }

    @Test
    fun `a resume failure from a session started outside a host resume reaches onArSessionFailed`() {
        val failure = CameraNotAvailableException("camera held by another app")
        resumeFailure = failure

        arCore.startSession(context)

        assertEquals(listOf<Exception>(failure), failures)
    }

    @Test
    fun `a resume failure is logged, for a host that set no failure callback`() {
        val failure = CameraNotAvailableException("camera held by another app")
        resumeFailure = failure

        arCore.resume(context, handler = null)

        assertTrue(
            "The failure must leave a trace in the log",
            ShadowLog.getLogsForTag("ARCore").any { it.throwable === failure },
        )
    }

    @Test
    fun `the next resume after a failure resumes the session once`() {
        resumeFailure = CameraNotAvailableException("camera held by another app")
        arCore.resume(context, handler = null)
        arCore.pause()
        resumeFailure = null

        arCore.resume(context, handler = null)

        assertTrue("The camera is free again: the session resumes", (created as ARSession).isResumed)
        assertEquals("onSessionResumed runs for the successful resume only", 1, resumedCallbacks)
        assertEquals(listOf(ARCoreAvailability.SessionFailed, null), published)
    }

    @Test
    fun `an exception thrown by the host's onSessionResumed is not reported as a resume failure`() {
        val hostBug = IllegalStateException("host callback bug")
        arCore = newARCore(onSessionResumed = { throw hostBug })

        val thrown = assertThrows(IllegalStateException::class.java) {
            arCore.resume(context, handler = null)
        }

        assertSame("The host's own exception reaches the host, unchanged", hostBug, thrown)
        assertEquals("ARCore did resume: nothing to report", emptyList<Exception>(), failures)
    }

    @Test
    fun `posted hand back logs host exceptions without reporting a session failure`() {
        val hostBug = IllegalStateException("host resumed callback")
        val reportBug = IllegalStateException("host failure callback")
        var failCallback = false
        arCore = newARCore(
            onSessionResumed = { if (failCallback) throw hostBug },
            onArSessionFailed = { failures += it; throw reportBug },
        )
        val covering = newARCore(onSessionResumed = {})
        try {
            arCore.resume(context, handler = null)
            covering.resume(context, handler = null)
            failCallback = true

            covering.pause()
            ShadowLooper.idleMainLooper()

            assertTrue("A host callback error must not invoke the session failure callback", failures.isEmpty())
            assertTrue(arCore.session!!.isResumed)
            assertTrue("Native resume succeeded: no session failure state", published.isEmpty())
            val logs = ShadowLog.getLogsForTag("ARCore")
            assertTrue(logs.any { it.throwable === hostBug })
            assertFalse(logs.any { it.throwable === reportBug })
        } finally {
            covering.detachHost()
            covering.session?.close()
            covering.destroy()
        }
    }

    @Test
    fun `repeated failures keep the failure state and report every attempt`() {
        resumeFailure = CameraNotAvailableException("busy")
        repeat(2) { arCore.resume(context, handler = null) }
        assertEquals(2, failures.size)
        assertEquals(listOf<ARCoreAvailability?>(ARCoreAvailability.SessionFailed), published)
    }

    @Test
    fun `retry while paused keeps the failure and leaves the session untouched`() {
        resumeFailure = CameraNotAvailableException("busy")
        arCore.resume(context, handler = null)
        val failed = created
        arCore.pause()
        resumeFailure = null

        arCore.retrySession(context)

        assertSame(failed, created)
        assertEquals(1, resumeCalls)
        assertEquals(ARCoreAvailability.SessionFailed, arCore.arCoreAvailability)
        arCore.resume(context, handler = null)
        assertEquals(2, resumeCalls)
        assertEquals(listOf(ARCoreAvailability.SessionFailed, null), published)
    }

    @Test
    fun `a detached host cannot retry even with an explicit context`() {
        resumeFailure = CameraNotAvailableException("busy")
        arCore.resume(context, handler = null)
        val failed = created
        arCore.detachHost()
        arCore.retrySession(context)
        assertSame(failed, created)
        assertEquals(1, resumeCalls)
        assertEquals(ARCoreAvailability.SessionFailed, arCore.arCoreAvailability)
    }

    @Test
    fun `try again keeps a failing camera visible and clears it only on success`() {
        resumeFailure = CameraNotAvailableException("busy")
        arCore.resume(context, handler = null)
        val failed = arCore.session!!
        arCore.retryARCoreAvailability()
        assertSame(failed, arCore.session)
        assertFalse(failed.isClosed)
        assertEquals(2, failures.size)
        assertEquals(listOf<ARCoreAvailability?>(ARCoreAvailability.SessionFailed), published)

        resumeFailure = null
        arCore.retryARCoreAvailability()

        assertEquals(listOf(ARCoreAvailability.SessionFailed, null), published)
        assertSame(failed, arCore.session)
        assertFalse(failed.isClosed)
        assertTrue(failed.isResumed)
        assertEquals(1, creationCalls)
        assertEquals(1, createdCallbacks)
        assertEquals(3, resumeCalls)
        assertEquals(1, resumedCallbacks)
    }

    @Test
    fun `display geometry exceptions propagate without being session failures`() {
        val bug = IllegalArgumentException("display geometry")
        geometryFailure = bug
        assertSame(bug, assertThrows(IllegalArgumentException::class.java) {
            arCore.resume(context, handler = null)
        })
        assertTrue((created as ARSession).isResumed)
        assertTrue(failures.isEmpty())
        assertTrue(published.isEmpty())
    }

    @Test
    fun `a host callback that pauses then throws is still a host exception`() {
        val bug = IllegalStateException("host paused itself")
        arCore = newARCore(onSessionResumed = {
            it.pause()
            throw bug
        })
        assertSame(bug, assertThrows(IllegalStateException::class.java) {
            arCore.resume(context, handler = null)
        })
        assertTrue(failures.isEmpty())
        assertTrue(published.isEmpty())
    }

    @Implements(Session::class)
    class ResumeFailingSession {
        @Suppress("FunctionName", "UNUSED_PARAMETER")
        @Implementation
        protected fun __constructor__(context: Context, features: Set<Session.Feature>) {
            creationCalls++
            creationFailure?.let { throw it }
        }

        @Implementation
        protected fun resume() {
            resumeCalls++
            resumeFailure?.let { throw it }
        }

        @Suppress("UNUSED_PARAMETER")
        @Implementation
        protected fun setDisplayGeometry(rotation: Int, width: Int, height: Int) {
            geometryFailure?.let { throw it }
        }

        @Implementation
        protected fun pause() = Unit

        @Implementation
        protected fun close() = Unit

        @Implementation
        protected fun getRecordingStatus(): RecordingStatus = RecordingStatus.NONE
    }

    private companion object {
        var creationFailure: Exception? = null
        var creationCalls = 0
        var resumeFailure: Exception? = null
        var geometryFailure: Exception? = null
        var resumeCalls = 0
    }
}
