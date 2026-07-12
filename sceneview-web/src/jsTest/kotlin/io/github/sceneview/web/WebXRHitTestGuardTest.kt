package io.github.sceneview.web

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Regression test for the **#2668 MED-2** hit-test-source leak in
 * `WebXRSession.setupHitTesting`.
 *
 * `setupHitTesting` chains `session.requestReferenceSpace(...).then { session
 * .requestHitTestSource(...).then { source -> hitTestSource = source } }`. If
 * `stop()` runs before that promise resolves, `stop()` has already done
 * `hitTestSource?.cancel(); hitTestSource = null` — but the still-pending
 * `.then` unconditionally overwrote `hitTestSource` with the freshly resolved,
 * never-cancelled `XRHitTestSource`, clobbering `stop()`'s intentional null
 * and leaking the resource (`renderFrame` never runs again once `isRunning`
 * is false, so nothing else ever reads or cancels it).
 *
 * `WebXRSession` has a private constructor and needs a live WebXR device to
 * construct for real, so — mirroring `LoadModelStaleCallbackTest` and
 * `AssetResourceTrackerTest`'s approach for the same class of async-teardown
 * race — this test reproduces the guarded `.then` body verbatim against a
 * `FakeHitTestSource` stand-in, unit-testable without a browser XR session.
 */
class WebXRHitTestGuardTest {

    /** Stand-in for a WebXR `XRHitTestSource`. */
    private class FakeHitTestSource {
        var cancelled = false
        fun cancel() {
            cancelled = true
        }
    }

    /**
     * Reproduces the guarded body of `setupHitTesting`'s innermost `.then`:
     * adopt [source] via [assign] only while [isRunning], otherwise cancel it
     * immediately instead of leaking it.
     */
    private fun onHitTestSourceResolved(
        isRunning: Boolean,
        source: FakeHitTestSource,
        assign: (FakeHitTestSource) -> Unit,
    ) {
        if (isRunning) {
            assign(source)
        } else {
            source.cancel()
        }
    }

    @Test
    fun sourceResolvingWhileRunningIsAdopted() {
        var hitTestSource: FakeHitTestSource? = null
        val source = FakeHitTestSource()

        onHitTestSourceResolved(isRunning = true, source = source) { hitTestSource = it }

        assertEquals(source, hitTestSource, "a source resolved while running must be adopted")
        assertFalse(source.cancelled, "an adopted source must not be cancelled")
    }

    /**
     * #2668 MED-2: `stop()` ran (isRunning = false, hitTestSource already
     * nulled) before the promise resolved — the late resolution must cancel
     * the source and must NOT overwrite the intentional null.
     */
    @Test
    fun sourceResolvingAfterStopIsCancelledAndNotAdopted() {
        var hitTestSource: FakeHitTestSource? = null // stop() already set this to null
        val source = FakeHitTestSource()

        onHitTestSourceResolved(isRunning = false, source = source) { hitTestSource = it }

        assertTrue(source.cancelled, "a source resolving after stop() must be cancelled, not leaked")
        assertNull(hitTestSource, "stop()'s intentional null must not be clobbered")
    }

    /**
     * A stale post-stop resolution must not replace whatever the field
     * currently holds, even if it happens to be non-null — guards against a
     * partial fix that cancels the new source but still assigns it.
     */
    @Test
    fun stalePostStopSourceNeverReplacesFieldEvenIfNonNull() {
        val stalePriorSource = FakeHitTestSource()
        var hitTestSource: FakeHitTestSource? = stalePriorSource
        val lateSource = FakeHitTestSource()

        onHitTestSourceResolved(isRunning = false, source = lateSource) { hitTestSource = it }

        assertTrue(lateSource.cancelled, "the late source must be cancelled")
        assertEquals(stalePriorSource, hitTestSource, "the field must be untouched when not running")
    }
}
