package io.github.sceneview.demo

import android.net.Uri
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ViewInAr
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Pure-JVM tests for [DeepLinkRouter]. Robolectric is the cheapest path
 * to a real `android.net.Uri` parser without spinning up a device.
 *
 * Tests the **end-to-end intent → demo id** lookup, including the
 * registry guard so we can assert that fuzzed / spoofed deep links
 * fall through to `null` (= the activity falls back to the demo list).
 */
@RunWith(RobolectricTestRunner::class)
class DeepLinkRouterTest {

    // Title / subtitle don't matter for the router under test — it only
    // looks at the id. We pass arbitrary R.string.* values to satisfy the
    // post-#1099 resource-ID typed fields without resolving them.
    private val knownRegistry = listOf(
        DemoEntry("ar-rerun", R.string.demo_ar_rerun_title, R.string.demo_ar_rerun_subtitle, "Augmented Reality", Icons.Filled.ViewInAr),
        DemoEntry("model-viewer", R.string.demo_model_viewer, R.string.demo_model_viewer_subtitle, "3D Basics", Icons.Filled.ViewInAr),
    )

    // ── Custom scheme: sceneview://demo/<id> ──────────────────────────────

    @Test
    fun `custom scheme resolves a known demo id`() {
        val uri = Uri.parse("sceneview://demo/ar-rerun")
        assertEquals("ar-rerun", DeepLinkRouter.parse(uri, knownRegistry))
    }

    @Test
    fun `custom scheme returns null for an unknown demo id (guards against fuzzing)`() {
        val uri = Uri.parse("sceneview://demo/totally-not-a-demo")
        assertNull(DeepLinkRouter.parse(uri, knownRegistry))
    }

    @Test
    fun `custom scheme tolerates uppercase scheme and host`() {
        val uri = Uri.parse("SCENEVIEW://Demo/model-viewer")
        assertEquals("model-viewer", DeepLinkRouter.parse(uri, knownRegistry))
    }

    @Test
    fun `custom scheme returns null for missing path segment`() {
        val uri = Uri.parse("sceneview://demo")
        assertNull(DeepLinkRouter.parse(uri, knownRegistry))
    }

    @Test
    fun `custom scheme returns null for wrong host`() {
        val uri = Uri.parse("sceneview://playground/ar-rerun")
        assertNull(DeepLinkRouter.parse(uri, knownRegistry))
    }

    // ── HTTPS App-Links: sceneview.github.io/open?demo=<id> ───────────────

    @Test
    fun `https app link resolves a known demo id from the demo query parameter`() {
        val uri = Uri.parse("https://sceneview.github.io/open?demo=ar-rerun")
        assertEquals("ar-rerun", DeepLinkRouter.parse(uri, knownRegistry))
    }

    @Test
    fun `https app link returns null when demo query param is missing`() {
        val uri = Uri.parse("https://sceneview.github.io/open")
        assertNull(DeepLinkRouter.parse(uri, knownRegistry))
    }

    @Test
    fun `https app link returns null for the wrong path`() {
        val uri = Uri.parse("https://sceneview.github.io/playground?demo=ar-rerun")
        assertNull(DeepLinkRouter.parse(uri, knownRegistry))
    }

    @Test
    fun `https app link returns null for the wrong host`() {
        val uri = Uri.parse("https://example.com/open?demo=ar-rerun")
        assertNull(DeepLinkRouter.parse(uri, knownRegistry))
    }

    // ── Fall-through cases ────────────────────────────────────────────────

    @Test
    fun `null uri returns null`() {
        assertNull(DeepLinkRouter.parse(null, knownRegistry))
    }

    @Test
    fun `unsupported scheme returns null`() {
        val uri = Uri.parse("ftp://demo/ar-rerun")
        assertNull(DeepLinkRouter.parse(uri, knownRegistry))
    }

    @Test
    fun `extractCandidate exposes the raw id without registry validation`() {
        // Round-trip through the URI parser to cover a path other tests
        // don't (the bare-id extraction is the security-sensitive bit; we
        // want it covered independently of the registry).
        val uri = Uri.parse("sceneview://demo/some-future-id-not-yet-shipped")
        assertEquals("some-future-id-not-yet-shipped", DeepLinkRouter.extractCandidate(uri))
        // …but the public API still gates it on the registry:
        assertNull(DeepLinkRouter.parse(uri, knownRegistry))
    }

    // ── validate(id) — guard for the `--es demo <id>` QA ingress (#958) ───
    //
    // The QA channel used by `adb shell am start ... --es demo <id>` is
    // reachable by any app on the device. Before #958 the extra was
    // assigned directly to pendingDemoId, so unknown ids quietly routed to
    // PlaceholderDemo. validate() applies the same allow-list as parse().

    @Test
    fun `validate returns the id when it matches the registry`() {
        assertEquals("ar-rerun", DeepLinkRouter.validate("ar-rerun", knownRegistry))
    }

    @Test
    fun `validate returns null for an unknown id (guards QA channel)`() {
        assertNull(DeepLinkRouter.validate("totally-not-a-demo", knownRegistry))
    }

    @Test
    fun `validate returns null for null and blank ids`() {
        assertNull(DeepLinkRouter.validate(null, knownRegistry))
        assertNull(DeepLinkRouter.validate("", knownRegistry))
        assertNull(DeepLinkRouter.validate("   ", knownRegistry))
    }

    // ── cameraDistance — deep-link zoom param for the device-QA harness (#1571) ──
    //
    // Maestro has no pinch gesture, so the Android device-QA flows drive 3D camera
    // zoom via a deep-link param instead. parseCameraDistance reads the URL query
    // parameter; validateCameraDistance is the shared clamp both ingress channels use.

    @Test
    fun `parseCameraDistance reads a valid distance from the custom-scheme query param`() {
        val uri = Uri.parse("sceneview://demo/model-viewer?cameraDistance=3.5")
        assertEquals(3.5f, DeepLinkRouter.parseCameraDistance(uri))
    }

    @Test
    fun `parseCameraDistance reads a valid distance from the https app-link query param`() {
        val uri = Uri.parse("https://sceneview.github.io/open?demo=model-viewer&cameraDistance=8")
        assertEquals(8f, DeepLinkRouter.parseCameraDistance(uri))
    }

    @Test
    fun `parseCameraDistance returns null when the query param is absent`() {
        val uri = Uri.parse("sceneview://demo/model-viewer")
        assertNull(DeepLinkRouter.parseCameraDistance(uri))
    }

    @Test
    fun `parseCameraDistance returns null for an unparseable value`() {
        val uri = Uri.parse("sceneview://demo/model-viewer?cameraDistance=close")
        assertNull(DeepLinkRouter.parseCameraDistance(uri))
    }

    @Test
    fun `parseCameraDistance returns null for null uri`() {
        assertNull(DeepLinkRouter.parseCameraDistance(null))
    }

    @Test
    fun `validateCameraDistance accepts an in-range value`() {
        assertEquals(2.5f, DeepLinkRouter.validateCameraDistance(2.5f))
        assertEquals(
            DeepLinkRouter.CAMERA_DISTANCE_MIN,
            DeepLinkRouter.validateCameraDistance(DeepLinkRouter.CAMERA_DISTANCE_MIN),
        )
        assertEquals(
            DeepLinkRouter.CAMERA_DISTANCE_MAX,
            DeepLinkRouter.validateCameraDistance(DeepLinkRouter.CAMERA_DISTANCE_MAX),
        )
    }

    @Test
    fun `validateCameraDistance rejects null, non-finite and out-of-range values`() {
        assertNull(DeepLinkRouter.validateCameraDistance(null))
        assertNull(DeepLinkRouter.validateCameraDistance(Float.NaN))
        assertNull(DeepLinkRouter.validateCameraDistance(Float.POSITIVE_INFINITY))
        assertNull(DeepLinkRouter.validateCameraDistance(Float.NEGATIVE_INFINITY))
        assertNull(DeepLinkRouter.validateCameraDistance(0f))
        assertNull(DeepLinkRouter.validateCameraDistance(-5f))
        assertNull(
            DeepLinkRouter.validateCameraDistance(DeepLinkRouter.CAMERA_DISTANCE_MAX + 1f),
        )
    }

    @Test
    fun `validate rejects fuzzed ids that look like path traversal or HTML`() {
        // Spot-check the kinds of strings a hostile app on the device might
        // try via the unprotected --es channel. None of these are in the
        // registry, so all must drop to null.
        listOf(
            "../",
            "../../etc/passwd",
            "<script>alert(1)</script>",
            "ar-rerun ; rm -rf",
            "ar-rerun extra",
            "AR-RERUN", // case-sensitive: registry uses kebab-case lowercase
        ).forEach { hostile ->
            assertNull(
                "validate must reject '$hostile'",
                DeepLinkRouter.validate(hostile, knownRegistry),
            )
        }
    }

    // ── Demo-id aliases: retired ids redirect to consolidated demos (#1444) ──

    @Test
    fun `retired alias id resolves to the consolidated demo that absorbed it`() {
        // `movable-light` was merged into `lighting` in #1444. An incoming
        // deep link to the retired id must resolve to the consolidated demo.
        val registry = listOf(
            DemoEntry(
                "lighting",
                R.string.demo_lighting_title,
                R.string.demo_lighting_subtitle,
                "Lighting & Environment",
                Icons.Filled.ViewInAr,
            ),
        )
        assertEquals("lighting", DeepLinkRouter.validate("movable-light", registry))
        assertEquals(
            "lighting",
            DeepLinkRouter.parse(Uri.parse("sceneview://demo/movable-light"), registry),
        )
    }

    @Test
    fun `alias returns null when its target demo is not registered`() {
        // The alias must not conjure a navigation target out of thin air — if
        // the consolidated demo itself is missing from the registry, the link
        // still falls through to null (same as any unknown id).
        assertNull(DeepLinkRouter.validate("movable-light", knownRegistry))
    }

    @Test
    fun `every DEMO_ID_ALIASES target is a live registered demo`() {
        // Invariant: an alias value must point at a real, currently-registered
        // demo id, otherwise the redirect would 404. Keys must be retired ids
        // that are NOT themselves registered (else the alias is dead code).
        DeepLinkRouter.DEMO_ID_ALIASES.forEach { (retired, target) ->
            assertEquals(
                "alias target '$target' for retired id '$retired' must be a live demo",
                target,
                ALL_DEMOS.find { it.id == target }?.id,
            )
            assertNull(
                "retired alias id '$retired' must not also be a registered demo",
                ALL_DEMOS.find { it.id == retired },
            )
        }
    }
}
