package io.github.sceneview.ar

import com.google.ar.core.ArCoreApk
import com.google.ar.core.exceptions.UnavailableApkTooOldException
import com.google.ar.core.exceptions.UnavailableArcoreNotInstalledException
import com.google.ar.core.exceptions.UnavailableDeviceNotCompatibleException
import com.google.ar.core.exceptions.UnavailableSdkTooOldException
import com.google.ar.core.exceptions.UnavailableUserDeclinedInstallationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * JVM unit tests pinning the ARCore availability → UI state contract (#3374).
 *
 * The bug this guards against: on a device without ARCore every AR demo sat on
 * "Initializing AR — look around to start tracking" forever, because nothing in the
 * public API ever said *why* the session would not start. These tests pin the two pure
 * mappings and the [ARCore] flow that publishes them, so a future refactor cannot
 * silently go back to reporting nothing.
 */
class ARCoreAvailabilityTest {

    /** Records what [ARCore] publishes, without any Android or Filament dependency. */
    private class FakeHandler(
        var availability: ArCoreApk.Availability = ArCoreApk.Availability.SUPPORTED_INSTALLED,
        var installResult: Boolean = false,
        var installThrows: Exception? = null,
        var availabilityThrows: Exception? = null,
        var cameraGranted: Boolean = true,
    ) : ARPermissionHandler {

        var checkAvailabilityCallCount = 0
        var requestInstallCallCount = 0

        override fun hasCameraPermission(): Boolean = cameraGranted

        override fun requestCameraPermission(onResult: (granted: Boolean) -> Unit) {
            onResult(cameraGranted)
        }

        override fun shouldShowPermissionRationale(): Boolean = false

        override fun openAppSettings() = Unit

        override fun checkARCoreAvailability(): ArCoreApk.Availability {
            checkAvailabilityCallCount++
            availabilityThrows?.let { throw it }
            return availability
        }

        override fun requestARCoreInstall(userRequestedInstall: Boolean): Boolean {
            requestInstallCallCount++
            installThrows?.let { throw it }
            return installResult
        }
    }

    private lateinit var handler: FakeHandler
    private lateinit var published: MutableList<ARCoreAvailability?>
    private lateinit var failures: MutableList<Exception>
    private lateinit var arCore: ARCore

    @Before
    fun setUp() {
        handler = FakeHandler()
        published = mutableListOf()
        failures = mutableListOf()
        arCore = ARCore(
            onSessionCreated = {},
            onSessionResumed = {},
            onSessionPaused = {},
            onArSessionFailed = { failures += it },
            onSessionConfigChanged = { _, _ -> },
        ).apply {
            permissionHandler = handler
            onARCoreAvailability = { published += it }
        }
    }

    // ── Availability → state ────────────────────────────────────────────────

    @Test
    fun `installed and current maps to no state`() {
        assertNull(ArCoreApk.Availability.SUPPORTED_INSTALLED.toARCoreAvailability())
    }

    @Test
    fun `still checking maps to no state so the host keeps its initializing copy`() {
        assertNull(ArCoreApk.Availability.UNKNOWN_CHECKING.toARCoreAvailability())
    }

    @Test
    fun `not installed maps to NotInstalled`() {
        assertEquals(
            ARCoreAvailability.NotInstalled,
            ArCoreApk.Availability.SUPPORTED_NOT_INSTALLED.toARCoreAvailability()
        )
    }

    @Test
    fun `apk too old maps to NeedsUpdate`() {
        assertEquals(
            ARCoreAvailability.NeedsUpdate,
            ArCoreApk.Availability.SUPPORTED_APK_TOO_OLD.toARCoreAvailability()
        )
    }

    @Test
    fun `device not capable maps to Unsupported`() {
        assertEquals(
            ARCoreAvailability.Unsupported,
            ArCoreApk.Availability.UNSUPPORTED_DEVICE_NOT_CAPABLE.toARCoreAvailability()
        )
    }

    @Test
    fun `unknown error and timeout map to CheckFailed`() {
        assertEquals(
            ARCoreAvailability.CheckFailed,
            ArCoreApk.Availability.UNKNOWN_ERROR.toARCoreAvailability()
        )
        assertEquals(
            ARCoreAvailability.CheckFailed,
            ArCoreApk.Availability.UNKNOWN_TIMED_OUT.toARCoreAvailability()
        )
    }

    @Test
    fun `every availability constant is classified - none silently unhandled`() {
        // The whole point of #3374: no ARCore verdict may leave the UI without an answer.
        // A new ARCore constant must land somewhere, and never on "supported".
        val transient = setOf(
            ArCoreApk.Availability.SUPPORTED_INSTALLED,
            ArCoreApk.Availability.UNKNOWN_CHECKING,
        )
        ArCoreApk.Availability.values().forEach { value ->
            val state = value.toARCoreAvailability()
            if (value in transient) {
                assertNull("$value must not raise a card", state)
            } else {
                assertNotNull("$value must map to an explicit state", state)
            }
        }
    }

    // ── Exception → state ───────────────────────────────────────────────────

    @Test
    fun `install exceptions are classified`() {
        assertEquals(
            ARCoreAvailability.Unsupported,
            UnavailableDeviceNotCompatibleException().toARCoreAvailability()
        )
        assertEquals(
            ARCoreAvailability.NeedsUpdate,
            UnavailableApkTooOldException().toARCoreAvailability()
        )
        assertEquals(
            ARCoreAvailability.NotInstalled,
            UnavailableArcoreNotInstalledException().toARCoreAvailability()
        )
        assertEquals(
            ARCoreAvailability.NotInstalled,
            UnavailableUserDeclinedInstallationException().toARCoreAvailability()
        )
        assertEquals(
            ARCoreAvailability.CheckFailed,
            UnavailableSdkTooOldException().toARCoreAvailability()
        )
    }

    @Test
    fun `an unrecognised exception is retryable, never silent`() {
        assertEquals(
            ARCoreAvailability.CheckFailed,
            IllegalStateException("boom").toARCoreAvailability()
        )
    }

    // ── Actionability ───────────────────────────────────────────────────────

    @Test
    fun `only an incapable device has no action`() {
        assertFalse(ARCoreAvailability.Unsupported.isActionable)
        assertTrue(ARCoreAvailability.NotInstalled.isActionable)
        assertTrue(ARCoreAvailability.NeedsUpdate.isActionable)
        assertTrue(ARCoreAvailability.CheckFailed.isActionable)
        assertNull(ARCoreAvailability.Unsupported.actionRes())
        listOf(
            ARCoreAvailability.NotInstalled,
            ARCoreAvailability.NeedsUpdate,
            ARCoreAvailability.CheckFailed,
        ).forEach { assertNotNull("$it needs a button", it.actionRes()) }
    }

    // ── ARCore flow ─────────────────────────────────────────────────────────

    @Test
    fun `unsupported device publishes Unsupported and never requests an install`() {
        handler.availability = ArCoreApk.Availability.UNSUPPORTED_DEVICE_NOT_CAPABLE

        assertFalse(arCore.checkPermissionAndInstall(handler))

        assertEquals(listOf<ARCoreAvailability?>(ARCoreAvailability.Unsupported), published)
        assertEquals(ARCoreAvailability.Unsupported, arCore.arCoreAvailability)
        // The pre-#3374 crash path: `requestInstall` throws on a non-capable device.
        assertEquals(0, handler.requestInstallCallCount)
    }

    @Test
    fun `installed device publishes nothing and creates the session`() {
        assertTrue(arCore.checkPermissionAndInstall(handler))

        assertTrue(published.isEmpty())
        assertNull(arCore.arCoreAvailability)
    }

    @Test
    fun `still checking holds the session without raising a card`() {
        handler.availability = ArCoreApk.Availability.UNKNOWN_CHECKING

        assertFalse(arCore.checkPermissionAndInstall(handler))

        assertTrue(published.isEmpty())
        assertNull(arCore.arCoreAvailability)
    }

    @Test
    fun `missing ARCore publishes NotInstalled and launches the install flow`() {
        handler.availability = ArCoreApk.Availability.SUPPORTED_NOT_INSTALLED
        handler.installResult = true

        assertFalse(arCore.checkPermissionAndInstall(handler))

        assertEquals(listOf<ARCoreAvailability?>(ARCoreAvailability.NotInstalled), published)
        assertEquals(1, handler.requestInstallCallCount)
    }

    @Test
    fun `returning from the install clears the card`() {
        handler.availability = ArCoreApk.Availability.SUPPORTED_NOT_INSTALLED
        handler.installResult = true
        arCore.checkPermissionAndInstall(handler)

        // Second pass: `installRequested` is latched, so the session may start.
        assertTrue(arCore.checkPermissionAndInstall(handler))

        assertEquals(listOf(ARCoreAvailability.NotInstalled, null), published)
        assertNull(arCore.arCoreAvailability)
    }

    @Test
    fun `a throwing install request is classified instead of vanishing into onArSessionFailed`() {
        handler.availability = ArCoreApk.Availability.SUPPORTED_NOT_INSTALLED
        handler.installThrows = UnavailableDeviceNotCompatibleException()

        assertFalse(arCore.checkPermissionAndInstall(handler))

        assertEquals(
            listOf(ARCoreAvailability.NotInstalled, ARCoreAvailability.Unsupported),
            published
        )
        // The host callback still fires — this adds a state, it does not swallow the error.
        assertEquals(1, failures.size)
    }

    @Test
    fun `retry re-runs the check and can recover`() {
        handler.availability = ArCoreApk.Availability.UNKNOWN_ERROR
        arCore.checkPermissionAndInstall(handler)
        assertEquals(ARCoreAvailability.CheckFailed, arCore.arCoreAvailability)

        handler.availability = ArCoreApk.Availability.SUPPORTED_INSTALLED
        arCore.retryARCoreAvailability(handler)

        assertEquals(listOf(ARCoreAvailability.CheckFailed, null), published)
        assertNull(arCore.arCoreAvailability)
    }

    @Test
    fun `retry un-latches a cancelled install so the flow can start again`() {
        handler.availability = ArCoreApk.Availability.SUPPORTED_NOT_INSTALLED
        handler.installResult = true
        arCore.checkPermissionAndInstall(handler)
        assertEquals(1, handler.requestInstallCallCount)

        arCore.retryARCoreAvailability(handler)

        assertEquals(2, handler.requestInstallCallCount)
    }

    @Test
    fun `the same verdict is published once, not on every resume`() {
        handler.availability = ArCoreApk.Availability.UNSUPPORTED_DEVICE_NOT_CAPABLE

        repeat(3) { arCore.checkPermissionAndInstall(handler) }

        assertEquals(listOf<ARCoreAvailability?>(ARCoreAvailability.Unsupported), published)
        assertEquals(3, handler.checkAvailabilityCallCount)
    }

    @Test
    fun `disabling the availability check keeps the legacy behaviour`() {
        arCore.checkAvailability = false
        handler.availability = ArCoreApk.Availability.UNSUPPORTED_DEVICE_NOT_CAPABLE

        assertTrue(arCore.checkPermissionAndInstall(handler))

        assertTrue(published.isEmpty())
        assertEquals(0, handler.checkAvailabilityCallCount)
    }
}
