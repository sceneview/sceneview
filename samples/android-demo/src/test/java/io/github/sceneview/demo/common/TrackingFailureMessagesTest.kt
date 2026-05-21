package io.github.sceneview.demo.common

import androidx.test.core.app.ApplicationProvider
import com.google.ar.core.TrackingFailureReason
import io.github.sceneview.demo.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Pure-JVM tests for the [TrackingFailureReason] -> string-resource mapping.
 *
 * The mapping is the bit most likely to regress (e.g. someone adds a new
 * `TrackingFailureReason` case in a future ARCore upgrade and forgets to
 * handle it) — every enum value gets a pinned expectation here.
 */
@RunWith(RobolectricTestRunner::class)
class TrackingFailureMessagesTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun `null and NONE return null - no banner to surface`() {
        assertNull(trackingFailureStringRes(null))
        assertNull(trackingFailureStringRes(TrackingFailureReason.NONE))
        assertNull(trackingFailureMessage(context, null))
        assertNull(trackingFailureMessage(context, TrackingFailureReason.NONE))
    }

    @Test
    fun `BAD_STATE maps to bad_state string resource`() {
        assertEquals(
            R.string.tracking_failure_bad_state,
            trackingFailureStringRes(TrackingFailureReason.BAD_STATE),
        )
    }

    @Test
    fun `INSUFFICIENT_LIGHT maps to insufficient_light string resource`() {
        assertEquals(
            R.string.tracking_failure_insufficient_light,
            trackingFailureStringRes(TrackingFailureReason.INSUFFICIENT_LIGHT),
        )
    }

    @Test
    fun `EXCESSIVE_MOTION maps to excessive_motion string resource`() {
        assertEquals(
            R.string.tracking_failure_excessive_motion,
            trackingFailureStringRes(TrackingFailureReason.EXCESSIVE_MOTION),
        )
    }

    @Test
    fun `INSUFFICIENT_FEATURES maps to insufficient_features string resource`() {
        assertEquals(
            R.string.tracking_failure_insufficient_features,
            trackingFailureStringRes(TrackingFailureReason.INSUFFICIENT_FEATURES),
        )
    }

    @Test
    fun `CAMERA_UNAVAILABLE maps to camera_unavailable string resource`() {
        assertEquals(
            R.string.tracking_failure_camera_unavailable,
            trackingFailureStringRes(TrackingFailureReason.CAMERA_UNAVAILABLE),
        )
    }

    @Test
    fun `every non-NONE TrackingFailureReason resolves to a non-empty message`() {
        // Future-proofing: if ARCore adds a new TrackingFailureReason enum
        // value and we forget to handle it, the `when` will fall through and
        // this test will fail with a null message.
        TrackingFailureReason.values()
            .filterNot { it == TrackingFailureReason.NONE }
            .forEach { reason ->
                val msg = trackingFailureMessage(context, reason)
                assertNotNull(
                    "Missing tracking-failure message for $reason — add a `when` branch.",
                    msg,
                )
                assertEquals(
                    "Tracking-failure message for $reason must be non-blank",
                    msg!!.trim(), msg.trim()
                )
            }
    }
}
