package io.github.sceneview.ar

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions
import androidx.compose.runtime.saveable.SaverScope
import androidx.core.app.ActivityOptionsCompat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * JVM tests for the activity-result registration behind the camera permission dialog (#4467).
 *
 * Every `ARSceneView` used to register under one constant key, and never unregistered: two AR
 * views alive in one activity shared a single registration — the second replaced the first's
 * callback — and a disposed view stayed in the registry.
 *
 * The registry here is the real `ActivityResultRegistry`, with only the launch itself replaced
 * by a list: keys, request codes, saved state and pending results behave as on a device.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class ARPermissionRegistrationTest {

    /** A registry that records what it was asked to launch instead of launching it. */
    private class RecordingRegistry : ActivityResultRegistry() {
        val launchedRequestCodes = mutableListOf<Int>()

        override fun <I, O> onLaunch(
            requestCode: Int,
            contract: ActivityResultContract<I, O>,
            input: I,
            options: ActivityOptionsCompat?,
        ) {
            launchedRequestCodes += requestCode
        }

        /** The user answered the dialog launched under [requestCode]. */
        fun answer(requestCode: Int, granted: Boolean): Boolean = dispatchResult(
            requestCode,
            Activity.RESULT_OK,
            Intent()
                .putExtra(
                    RequestMultiplePermissions.EXTRA_PERMISSIONS,
                    arrayOf(Manifest.permission.CAMERA)
                )
                .putExtra(
                    RequestMultiplePermissions.EXTRA_PERMISSION_GRANT_RESULTS,
                    intArrayOf(
                        if (granted) PackageManager.PERMISSION_GRANTED
                        else PackageManager.PERMISSION_DENIED
                    )
                ),
        )

        /** Android cancelled the request launched under [requestCode] without showing it. */
        fun cancel(requestCode: Int): Boolean = dispatchResult(
            requestCode,
            Activity.RESULT_OK,
            Intent()
                .putExtra(RequestMultiplePermissions.EXTRA_PERMISSIONS, emptyArray<String>())
                .putExtra(RequestMultiplePermissions.EXTRA_PERMISSION_GRANT_RESULTS, IntArray(0)),
        )

        /** What the recreated activity's registry starts from. */
        fun recreated(): RecordingRegistry {
            val saved = Bundle().also(::onSaveInstanceState)
            return RecordingRegistry().apply { onRestoreInstanceState(saved) }
        }
    }

    private var cameraGranted = false

    /** A view's registration once composition has kept it: built, then registered. */
    private fun viewRegistration(
        registry: ActivityResultRegistry,
        state: ARPermissionRegistrationState = ARPermissionRegistrationState.create(),
    ) = ARPermissionRegistration(registry, state, isOwnedByView = true) { cameraGranted }
        .also { it.register() }

    private var nowMs = 0L

    /** `true` / `false` for each denial verdict published. */
    private val verdicts = mutableListOf<Boolean>()

    private fun arCore() = ARCore(
        onSessionCreated = {},
        onSessionResumed = {},
        onSessionPaused = {},
        onArSessionFailed = {},
        onSessionConfigChanged = { _, _ -> },
    ).apply {
        // Robolectric leaves the camera denied, and no session can be created on the JVM.
        checkAvailability = false
        nanoTime = { nowMs * 1_000_000L }
        onCameraPermissionDenied = { verdicts += it }
    }

    private fun activity(): ComponentActivity =
        Robolectric.buildActivity(ComponentActivity::class.java).setup().get()

    /** The state as the recreated view gets it back from saved state. */
    private fun ARPermissionRegistrationState.savedAndRestored(): ARPermissionRegistrationState {
        val saver = ARPermissionRegistrationState.Saver
        val saved = with(saver) { SaverScope { true }.save(this@savedAndRestored) }!!
        return saver.restore(saved)!!
    }

    // ── Two AR views in one activity ────────────────────────────────────────

    @Test
    fun `two views in one activity each get the answer to their own request`() {
        val registry = RecordingRegistry()
        val first = viewRegistration(registry)
        val second = viewRegistration(registry)
        val firstAnswers = mutableListOf<Boolean>()
        val secondAnswers = mutableListOf<Boolean>()

        first.requestCamera { firstAnswers += it }
        second.requestCamera { secondAnswers += it }
        val (firstCode, secondCode) = registry.launchedRequestCodes

        // Answered in the other order, so a shared registration could not get both right.
        registry.answer(secondCode, granted = false)
        registry.answer(firstCode, granted = true)

        assertNotEquals(firstCode, secondCode)
        assertEquals(listOf(true), firstAnswers)
        assertEquals(listOf(false), secondAnswers)
    }

    @Test
    fun `each view draws a key of its own`() {
        assertNotEquals(
            ARPermissionRegistrationState.create().key,
            ARPermissionRegistrationState.create().key,
        )
    }

    @Test
    fun `a cancelled request is reported to the view that made it only`() {
        val registry = RecordingRegistry()
        val first = viewRegistration(registry)
        val second = viewRegistration(registry)
        var firstCancelled = 0
        var secondCancelled = 0
        val answers = mutableListOf<Boolean>()
        first.onCameraRequestCancelled = { firstCancelled++ }
        second.onCameraRequestCancelled = { secondCancelled++ }

        first.requestCamera { answers += it }
        second.requestCamera { answers += it }
        registry.cancel(registry.launchedRequestCodes[1])

        assertEquals(0, firstCancelled)
        assertEquals(1, secondCancelled)
        assertTrue(answers.isEmpty())
    }

    // ── A view that left ────────────────────────────────────────────────────

    @Test
    fun `a view registers nothing until composition has kept it`() {
        val registry = RecordingRegistry()
        val state = ARPermissionRegistrationState.create()

        val built = ARPermissionRegistration(registry, state, isOwnedByView = true) { false }
        val viewHandler = ActivityARPermissionHandler(activity(), state, registry)

        // Built during composition: an abandoned composition must leave no key behind.
        assertFalse(built.isRegistered)
        assertFalse(viewHandler.isRegistered)
        // The public constructor is not tied to composition and registers at once, as before.
        assertTrue(ActivityARPermissionHandler(activity()).isRegistered)
    }

    @Test
    fun `a released view whose requests were answered leaves nothing in the registry`() {
        // Only once they were answered: a request still out keeps its key and request code
        // in the registry until the answer comes — see the next test for what that reaches.
        val registry = RecordingRegistry()
        val registration = viewRegistration(registry)
        registration.requestCamera { }
        registration.appSettingsLauncher.launch(Intent())
        val (cameraCode, settingsCode) = registry.launchedRequestCodes
        registry.answer(cameraCode, granted = false)
        registry.dispatchResult(settingsCode, Activity.RESULT_CANCELED, null)
        // Registered: the registry still knows both request codes.
        assertTrue(registration.isRegistered)

        registration.release()

        assertFalse(registration.isRegistered)
        // `dispatchResult` answers `false` for a request code no key is registered under.
        assertFalse(registry.answer(cameraCode, granted = true))
        assertFalse(registry.dispatchResult(settingsCode, Activity.RESULT_OK, null))
    }

    @Test
    fun `an answer arriving after the view left reaches nobody`() {
        val registry = RecordingRegistry()
        val registration = viewRegistration(registry)
        val answers = mutableListOf<Boolean>()
        registration.requestCamera { answers += it }

        registration.release()
        registry.answer(registry.launchedRequestCodes.single(), granted = true)

        assertTrue(answers.isEmpty())
    }

    @Test
    fun `releasing one view leaves the other able to ask and open settings`() {
        val registry = RecordingRegistry()
        val leaving = viewRegistration(registry)
        val staying = viewRegistration(registry)
        val answers = mutableListOf<Boolean>()

        leaving.release()
        staying.requestCamera { answers += it }
        staying.appSettingsLauncher.launch(Intent())
        registry.answer(registry.launchedRequestCodes.first(), granted = true)

        assertEquals(2, registry.launchedRequestCodes.size)
        assertEquals(listOf(true), answers)
    }

    @Test
    fun `a released registration registers again when its view comes back`() {
        val registry = RecordingRegistry()
        val registration = viewRegistration(registry)
        val answers = mutableListOf<Boolean>()
        registration.release()

        registration.register()
        registration.requestCamera { answers += it }
        registry.answer(registry.launchedRequestCodes.single(), granted = true)

        assertTrue(registration.isRegistered)
        assertEquals(listOf(true), answers)
    }

    @Test
    fun `a handler built by the host keeps its registration`() {
        val registry = RecordingRegistry()
        val hostBuilt = ARPermissionRegistration(
            registry,
            ARPermissionRegistrationState(SHARED_CAMERA_PERMISSION_KEY),
            isOwnedByView = false,
        ) { cameraGranted }
        val answers = mutableListOf<Boolean>()

        // It may be shared between views or outlive them: a view leaving does not touch it.
        hostBuilt.release()
        hostBuilt.requestCamera { answers += it }
        registry.answer(registry.launchedRequestCodes.single(), granted = true)

        assertTrue(hostBuilt.isRegistered)
        assertEquals(listOf(true), answers)
    }

    // ── A request pending across an activity recreation ─────────────────────

    @Test
    fun `a request pending across a recreation reaches the recreated view`() {
        val oldRegistry = RecordingRegistry()
        val state = ARPermissionRegistrationState.create()
        val oldView = viewRegistration(oldRegistry, state)
        val oldAnswers = mutableListOf<Boolean>()
        oldView.requestCamera { oldAnswers += it }
        val requestCode = oldRegistry.launchedRequestCodes.single()

        // Rotation with the dialog up: state is saved, then the old view is disposed.
        val restoredState = state.savedAndRestored()
        val newRegistry = oldRegistry.recreated()
        oldView.release()
        val newView = viewRegistration(newRegistry, restoredState)
        val newAnswers = mutableListOf<Boolean>()

        assertTrue(newView.inheritsCameraRequest)
        // `ARCore.create()` asks on the recreated view: the dialog is already up, so nothing
        // is launched — a second launch would be cancelled by Android and would have the
        // registry park the real answer.
        newView.requestCamera { newAnswers += it }
        assertTrue(newRegistry.launchedRequestCodes.isEmpty())

        newRegistry.answer(requestCode, granted = true)

        assertEquals(listOf(true), newAnswers)
        assertTrue(oldAnswers.isEmpty())
        assertFalse(restoredState.isCameraRequestOut)
    }

    @Test
    fun `an answer given while the activity was gone reaches the recreated view`() {
        val oldRegistry = RecordingRegistry()
        val state = ARPermissionRegistrationState.create()
        viewRegistration(oldRegistry, state).requestCamera { }
        val requestCode = oldRegistry.launchedRequestCodes.single()
        val restoredState = state.savedAndRestored()
        val newRegistry = oldRegistry.recreated()

        // The activity is recreated and handed the answer before the view is composed.
        newRegistry.answer(requestCode, granted = false)
        val newView = viewRegistration(newRegistry, restoredState)
        val answers = mutableListOf<Boolean>()
        newView.requestCamera { answers += it }

        assertEquals(listOf(false), answers)
        assertTrue(newRegistry.launchedRequestCodes.isEmpty())
        // The request is spent: asking again shows the dialog.
        newView.requestCamera { answers += it }
        assertEquals(1, newRegistry.launchedRequestCodes.size)
    }

    @Test
    fun `an answer kept across a recreation that is no longer true asks again`() {
        val oldRegistry = RecordingRegistry()
        val state = ARPermissionRegistrationState.create()
        viewRegistration(oldRegistry, state).requestCamera { }
        val restoredState = state.savedAndRestored()
        val newRegistry = oldRegistry.recreated()
        newRegistry.answer(oldRegistry.launchedRequestCodes.single(), granted = false)
        // Granted from system settings in between.
        cameraGranted = true

        val answers = mutableListOf<Boolean>()
        viewRegistration(newRegistry, restoredState).requestCamera { answers += it }

        assertTrue(answers.isEmpty())
        assertEquals(1, newRegistry.launchedRequestCodes.size)
    }

    @Test
    fun `a view restored in the same activity does not wait for a request it released`() {
        val registry = RecordingRegistry()
        val state = ARPermissionRegistrationState.create()
        val view = viewRegistration(registry, state)
        view.requestCamera { }
        // A back stack saves the view's state with the request out, then disposes the view.
        val restoredState = state.savedAndRestored()
        view.release()

        val back = viewRegistration(registry, restoredState)
        back.requestCamera { }

        assertFalse(back.inheritsCameraRequest)
        // Its answer was dropped with the registration: this one is a real launch.
        assertEquals(2, registry.launchedRequestCodes.size)
    }

    @Test
    fun `an answer parked for a view that never asks is not saved as a request still out`() {
        val oldRegistry = RecordingRegistry()
        val state = ARPermissionRegistrationState.create()
        viewRegistration(oldRegistry, state).requestCamera { }
        val restoredState = state.savedAndRestored()
        val newRegistry = oldRegistry.recreated()
        // Granted while the activity was gone: the recreated view finds the camera granted
        // and never asks, so nothing but registering reads the parked answer.
        newRegistry.answer(oldRegistry.launchedRequestCodes.single(), granted = true)
        cameraGranted = true
        viewRegistration(newRegistry, restoredState)

        assertFalse(restoredState.isCameraRequestOut)

        // Recreated once more, then the camera is revoked from settings: there is no dialog
        // to wait for, this request is a real launch.
        val laterRegistry = newRegistry.recreated()
        val laterView = viewRegistration(laterRegistry, restoredState.savedAndRestored())
        cameraGranted = false
        val answers = mutableListOf<Boolean>()
        laterView.requestCamera { answers += it }

        assertEquals(1, laterRegistry.launchedRequestCodes.size)
        laterRegistry.answer(laterRegistry.launchedRequestCodes.single(), granted = false)
        assertEquals(listOf(false), answers)
    }

    @Test
    fun `a request found cancelled after a recreation is not waited for`() {
        val oldRegistry = RecordingRegistry()
        val state = ARPermissionRegistrationState.create()
        viewRegistration(oldRegistry, state).requestCamera { }
        val restoredState = state.savedAndRestored()
        val newRegistry = oldRegistry.recreated()
        // Android cancelled it with an empty result, parked until the view registers.
        newRegistry.cancel(oldRegistry.launchedRequestCodes.single())

        val newView = viewRegistration(newRegistry, restoredState)
        val answers = mutableListOf<Boolean>()
        newView.requestCamera { answers += it }

        assertFalse(newView.inheritsCameraRequest)
        // Nothing is coming for the old request: this one had to be launched.
        assertEquals(1, newRegistry.launchedRequestCodes.size)
        newRegistry.answer(newRegistry.launchedRequestCodes.single(), granted = true)
        assertEquals(listOf(true), answers)
    }

    // ── ARCore releases the registration with the host ──────────────────────

    @Test
    fun `detaching the host releases the view's registration and creating restores it`() {
        val registry = RecordingRegistry()
        val activity = activity()
        val handler =
            ActivityARPermissionHandler(activity, ARPermissionRegistrationState.create(), registry)
        val arCore = arCore()
        arCore.create(activity, handler, emptySet())
        assertTrue(handler.isRegistered)
        // The camera is denied: creating asked for it, through this view's registration.
        assertEquals(1, registry.launchedRequestCodes.size)

        arCore.detachHost()
        assertFalse(handler.isRegistered)

        // The same view attached again (its lifecycle changed): `create()` registers again,
        // and its request is answered.
        arCore.create(activity, handler, emptySet())
        assertTrue(handler.isRegistered)
        assertEquals(2, registry.launchedRequestCodes.size)
        nowMs += 5_000
        registry.answer(registry.launchedRequestCodes.last(), granted = false)
        assertEquals(1, verdicts.size)
    }

    @Test
    fun `a state kept after the host left opens no settings screen`() {
        val registry = RecordingRegistry()
        val activity = activity()
        val handler =
            ActivityARPermissionHandler(activity, ARPermissionRegistrationState.create(), registry)
        val arCore = arCore()
        arCore.permissionHandler = handler
        handler.register()

        arCore.detachHost()
        arCore.openAppSettings()

        assertTrue(registry.launchedRequestCodes.isEmpty())
        assertFalse(handler.isRegistered)
    }

    @Test
    fun `a request taken over after a recreation is not read as answered at once`() {
        val activity = activity()
        val oldRegistry = RecordingRegistry()
        val state = ARPermissionRegistrationState.create()
        ActivityARPermissionHandler(activity, state, oldRegistry).requestCameraPermission { }
        val requestCode = oldRegistry.launchedRequestCodes.single()
        // Recreated with the dialog up: the registry and the view's state come from the bundle.
        val newRegistry = oldRegistry.recreated()
        val handler = ActivityARPermissionHandler(activity, state.savedAndRestored(), newRegistry)

        arCore().create(activity, handler, emptySet())
        assertTrue(newRegistry.launchedRequestCodes.isEmpty())
        // The user refuses 100 ms after the recreation — the dialog had been up for longer.
        // Timed from `create()`, that is "refused instantly": a blocked dialog, "Open settings".
        nowMs += 100
        newRegistry.answer(requestCode, granted = false)

        assertEquals(listOf(false), verdicts)
    }

    @Test
    fun `detaching the host leaves a handler the host built registered`() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val handler = ActivityARPermissionHandler(activity)
        val arCore = ARCore(
            onSessionCreated = {},
            onSessionResumed = {},
            onSessionPaused = {},
            onArSessionFailed = {},
            onSessionConfigChanged = { _, _ -> },
        ).apply { permissionHandler = handler }

        arCore.detachHost()

        assertTrue(handler.isRegistered)
    }
}
