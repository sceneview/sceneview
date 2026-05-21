package io.github.sceneview.ar.xr

import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * JVM unit test for [XrFeatures] — exercises the negative path that matters
 * to the 99% of consumers building mobile-only apps: when
 * `androidx.xr.runtime.Session` is NOT on the classpath (the default for
 * `arsceneview/`), [XrFeatures.isAvailable] must return `false` rather than
 * throw [ClassNotFoundException].
 *
 * The positive path (XR runtime present) needs an instrumented test on an
 * Android XR device / emulator and is covered by Slices 2 / 3.
 */
@RunWith(RobolectricTestRunner::class)
class XrFeaturesTest {

    @Test
    fun `isAvailable returns false when xr runtime classes are absent`() {
        // `androidx.xr.runtime` is not declared as a test dependency of
        // arsceneview/, so the classpath lookup must miss and the helper
        // must short-circuit to false without raising.
        val context = RuntimeEnvironment.getApplication()
        assertFalse(XrFeatures.isAvailable(context))
    }
}
