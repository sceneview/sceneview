package io.github.sceneview.ar

import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pins the URI-scheme allowlist that gates `ARSceneView(playbackDatasetUri = …)` (#1845).
 *
 * The check itself lives inline in the Composable as a `require(...)`; the underlying
 * predicate [isAllowedPlaybackDatasetUri] is extracted as a top-level pure function so the
 * contract is testable without spinning up a Compose host. The Composable's runtime path
 * (a `require` with a clear message) is exercised end-to-end by the AR demo, but the
 * allowlist surface is locked here.
 *
 * Robolectric is needed because `android.net.Uri` is a stub on the host JVM —
 * `Uri.parse(...)` lives in `android.jar` and the parser implementation comes from
 * Robolectric's android-base layer.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class PlaybackDatasetUriSchemeTest {

    @Test
    fun `allowlist contains exactly content and file`() {
        // Locks the public contract — any expansion of the allowlist (e.g. to support a
        // `assets:` scheme one day) should be a deliberate change reviewed alongside the
        // KDoc on `playbackDatasetUri`.
        assertEquals(setOf("content", "file"), PLAYBACK_DATASET_URI_ALLOWED_SCHEMES)
    }

    @Test
    fun `null URI is allowed (no playback requested)`() {
        // The `playbackDatasetUri = null` default must not be rejected.
        assertTrue(isAllowedPlaybackDatasetUri(null))
    }

    @Test
    fun `content URI is allowed`() {
        // The canonical case — a SAF picker / MediaStore URI handed straight to ARCore.
        assertTrue(
            isAllowedPlaybackDatasetUri(Uri.parse("content://media/external/video/media/42")),
        )
    }

    @Test
    fun `file URI is allowed`() {
        // Symmetry with the legacy `playbackDataset: File?` overload.
        assertTrue(isAllowedPlaybackDatasetUri(Uri.parse("file:///sdcard/Download/session.mp4")))
    }

    @Test
    fun `https URI is rejected`() {
        // Defense-in-depth: an https URI handed straight to ARCore would either silently
        // fail (ARCore cannot pull from a remote URL) or worse — issue a network fetch in
        // the JNI layer. Reject at the boundary.
        assertFalse(isAllowedPlaybackDatasetUri(Uri.parse("https://example.com/session.mp4")))
    }

    @Test
    fun `http URI is rejected`() {
        assertFalse(isAllowedPlaybackDatasetUri(Uri.parse("http://example.com/session.mp4")))
    }

    @Test
    fun `data URI is rejected`() {
        // A data: URI cannot name an MP4 dataset and would be silently dropped by ARCore.
        assertFalse(isAllowedPlaybackDatasetUri(Uri.parse("data:video/mp4;base64,AAAA")))
    }

    @Test
    fun `custom scheme is rejected`() {
        // Arbitrary custom schemes — guard against a misconfigured deep-link path that
        // shipped a URI like `sceneview://...` straight to the Composable.
        assertFalse(isAllowedPlaybackDatasetUri(Uri.parse("sceneview://playback")))
    }

    @Test
    fun `URI without a scheme is rejected`() {
        // A relative URI lacks a scheme — Uri.parse returns the URI with a null scheme.
        // ARCore would not accept it either.
        assertFalse(isAllowedPlaybackDatasetUri(Uri.parse("/sdcard/Download/session.mp4")))
    }
}
