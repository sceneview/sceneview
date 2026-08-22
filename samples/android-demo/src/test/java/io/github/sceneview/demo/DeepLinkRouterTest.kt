@file:Suppress("MaxLineLength") // test DemoEntry constructor args are long by nature

package io.github.sceneview.demo

import android.net.Uri
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ViewInAr
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
        DemoEntry("ar-rerun", R.string.demo_ar_rerun_title, R.string.demo_ar_rerun_subtitle, "Augmented Reality", Icons.Filled.ViewInAr, order = 1, tags = setOf("test")),
        DemoEntry("model-viewer", R.string.demo_model_viewer, R.string.demo_model_viewer_subtitle, "3D Basics", Icons.Filled.ViewInAr, order = 2, tags = setOf("test")),
    )

    // Registry holding the three #2239 Batch 1 consolidated demos that the
    // retired ids redirect to. The router only inspects `id`, so the title /
    // subtitle resources are arbitrary (see the note above).
    private val consolidatedRegistry = listOf(
        DemoEntry("custom-geometry", R.string.demo_custom_geometry_title, R.string.demo_custom_geometry_subtitle, "Advanced", Icons.Filled.ViewInAr, order = 3, tags = setOf("test")),
        DemoEntry("picking-collision", R.string.demo_picking_collision_title, R.string.demo_picking_collision_subtitle, "Interaction", Icons.Filled.ViewInAr, order = 4, tags = setOf("test")),
        DemoEntry("camera-gestures", R.string.demo_camera_and_gestures_title, R.string.demo_camera_and_gestures_subtitle, "Interaction", Icons.Filled.ViewInAr, order = 5, tags = setOf("test")),
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
                order = 1,
                tags = setOf("test"),
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

    // ── #2239 Batch 1 alias redirects — three demo consolidations ─────────────
    //
    // Batch 1 merged six demos into three consolidated ones, each behind a
    // segmented-button toggle. The retired ids stay on the public deep-link
    // surface and must keep resolving to their consolidated demo rather than
    // falling through to the demo list.

    @Test
    fun `Batch 1 retired ids resolve to their consolidated demo`() {
        val expected = mapOf(
            "custom-mesh" to "custom-geometry",
            "shape" to "custom-geometry",
            "collision" to "picking-collision",
            "view-node" to "picking-collision",
            "camera-controls" to "camera-gestures",
            "gesture-editing" to "camera-gestures",
        )
        expected.forEach { (retired, consolidated) ->
            assertEquals(
                "validate('$retired') must redirect to '$consolidated'",
                consolidated,
                DeepLinkRouter.validate(retired, consolidatedRegistry),
            )
            assertEquals(
                "sceneview://demo/$retired must resolve to '$consolidated'",
                consolidated,
                DeepLinkRouter.parse(Uri.parse("sceneview://demo/$retired"), consolidatedRegistry),
            )
        }
    }

    // ── Initial-tab pre-selection: alias + ?tab= deep-link param (#2315) ──────
    //
    // Consolidated demos open on their default first tab unless an alias (e.g.
    // `shape`) or an explicit `--es tab` / `?tab=` value pre-selects another. The
    // resolution is pure (raw id + raw param → index); the bad-index clamp lives in
    // the demo composable (initialDemoMode), out of this unit's reach.

    @Test
    fun `resolveInitialTab maps a non-default alias to its tab`() {
        assertEquals(1, DeepLinkRouter.resolveInitialTab("shape", null))
        assertEquals(1, DeepLinkRouter.resolveInitialTab("movable-light", null))
        assertEquals(2, DeepLinkRouter.resolveInitialTab("scene-gallery", null))
        assertEquals(3, DeepLinkRouter.resolveInitialTab("billboard", null))
    }

    @Test
    fun `resolveInitialTab returns null for a default-tab alias or a plain id`() {
        // Aliases that land on tab 0 are intentionally absent from ALIAS_INITIAL_TAB.
        assertNull(DeepLinkRouter.resolveInitialTab("custom-mesh", null))
        assertNull(DeepLinkRouter.resolveInitialTab("text", null))
        // A live consolidated id with no tab hint keeps its default tab.
        assertNull(DeepLinkRouter.resolveInitialTab("custom-geometry", null))
        assertNull(DeepLinkRouter.resolveInitialTab(null, null))
    }

    @Test
    fun `explicit tab param wins over the alias default`() {
        // `?tab=0` forces the default tab even when launched via the `shape` alias.
        assertEquals(0, DeepLinkRouter.resolveInitialTab("shape", "0"))
        // An explicit integer index on a plain id.
        assertEquals(2, DeepLinkRouter.resolveInitialTab("custom-geometry", "2"))
        // An explicit alias-token tab value resolves through ALIAS_INITIAL_TAB.
        assertEquals(3, DeepLinkRouter.resolveInitialTab("two-d-in-three-d", "billboard"))
    }

    @Test
    fun `resolveInitialTab falls back to the alias when the tab param is unusable`() {
        // A negative / unparseable / blank explicit value is ignored; the alias applies.
        assertEquals(1, DeepLinkRouter.resolveInitialTab("shape", "-1"))
        assertEquals(1, DeepLinkRouter.resolveInitialTab("shape", "garbage"))
        assertEquals(1, DeepLinkRouter.resolveInitialTab("shape", "  "))
    }

    @Test
    fun `parseTabValue accepts a non-negative index or an alias token`() {
        assertEquals(0, DeepLinkRouter.parseTabValue("0"))
        // Out-of-range index is passed through — the demo clamps it, not the router.
        assertEquals(5, DeepLinkRouter.parseTabValue("5"))
        // Trimmed, then looked up as an alias token.
        assertEquals(2, DeepLinkRouter.parseTabValue(" occlusion-material "))
    }

    @Test
    fun `parseTabValue returns null for blank, negative or unknown tokens`() {
        assertNull(DeepLinkRouter.parseTabValue(null))
        assertNull(DeepLinkRouter.parseTabValue(""))
        assertNull(DeepLinkRouter.parseTabValue("   "))
        assertNull(DeepLinkRouter.parseTabValue("-1"))
        assertNull(DeepLinkRouter.parseTabValue("not-a-tab"))
    }

    @Test
    fun `parseTabParam reads the tab query parameter`() {
        assertEquals(
            "1",
            DeepLinkRouter.parseTabParam(Uri.parse("sceneview://demo/custom-geometry?tab=1")),
        )
        assertEquals(
            "shape",
            DeepLinkRouter.parseTabParam(Uri.parse("sceneview://demo/custom-geometry?tab=shape")),
        )
        assertNull(DeepLinkRouter.parseTabParam(Uri.parse("sceneview://demo/custom-geometry")))
        assertNull(DeepLinkRouter.parseTabParam(null))
    }

    // ── camera_distance intent-extra coercion (#2652) ─────────────────────

    @Test
    fun `camera distance extra accepts every sender encoding`() {
        // adb --ef delivers a Float; Maestro launchApp delivers env-interpolated
        // values as String extras and could deliver bare YAML numbers as
        // Integer/Double. All must resolve identically (#2652).
        assertEquals(0.6f, DeepLinkRouter.coerceCameraDistanceExtra(0.6f))
        assertEquals(0.6f, DeepLinkRouter.coerceCameraDistanceExtra(0.6))
        assertEquals(40f, DeepLinkRouter.coerceCameraDistanceExtra(40))
        assertEquals(40f, DeepLinkRouter.coerceCameraDistanceExtra(40L))
        assertEquals(0.6f, DeepLinkRouter.coerceCameraDistanceExtra("0.6"))
        assertEquals(40f, DeepLinkRouter.coerceCameraDistanceExtra("40"))
    }

    @Test
    fun `camera distance extra rejects garbage without throwing`() {
        assertNull(DeepLinkRouter.coerceCameraDistanceExtra(null))
        assertNull(DeepLinkRouter.coerceCameraDistanceExtra("not-a-number"))
        assertNull(DeepLinkRouter.coerceCameraDistanceExtra(""))
        assertNull(DeepLinkRouter.coerceCameraDistanceExtra(true))
        assertNull(DeepLinkRouter.coerceCameraDistanceExtra(Float.NaN))
        assertNull(DeepLinkRouter.coerceCameraDistanceExtra(Double.POSITIVE_INFINITY))
        assertNull(DeepLinkRouter.coerceCameraDistanceExtra("NaN"))
    }

    @Test
    fun `camera distance extra applies the shared clamp on every encoding`() {
        // Below CAMERA_DISTANCE_MIN (0.05) and above CAMERA_DISTANCE_MAX (100)
        // must drop to null — auto-fit framing — never crash or pass through.
        assertNull(DeepLinkRouter.coerceCameraDistanceExtra(0.01f))
        assertNull(DeepLinkRouter.coerceCameraDistanceExtra("0.01"))
        assertNull(DeepLinkRouter.coerceCameraDistanceExtra(500))
        assertNull(DeepLinkRouter.coerceCameraDistanceExtra("500"))
        // Boundary values are inclusive.
        assertEquals(
            DeepLinkRouter.CAMERA_DISTANCE_MIN,
            DeepLinkRouter.coerceCameraDistanceExtra(DeepLinkRouter.CAMERA_DISTANCE_MIN),
        )
        assertEquals(
            DeepLinkRouter.CAMERA_DISTANCE_MAX,
            DeepLinkRouter.coerceCameraDistanceExtra(DeepLinkRouter.CAMERA_DISTANCE_MAX),
        )
    }

    @Test
    fun `camera distance URL query applies the same clamp as the extra channel`() {
        assertEquals(
            0.6f,
            DeepLinkRouter.parseCameraDistance(
                Uri.parse("sceneview://demo/model-viewer?cameraDistance=0.6"),
            ),
        )
        assertNull(
            DeepLinkRouter.parseCameraDistance(
                Uri.parse("sceneview://demo/model-viewer?cameraDistance=junk"),
            ),
        )
        assertNull(DeepLinkRouter.parseCameraDistance(null))
    }

    @Test
    fun `every ALIAS_INITIAL_TAB key is a known retired alias on a non-default tab`() {
        // Guards against a typo'd key drifting from DEMO_ID_ALIASES, and against
        // listing a tab-0 alias (those are meant to be omitted — absent = default).
        DeepLinkRouter.ALIAS_INITIAL_TAB.forEach { (alias, index) ->
            assertTrue(
                "ALIAS_INITIAL_TAB key '$alias' must be a known retired alias id",
                DeepLinkRouter.DEMO_ID_ALIASES.containsKey(alias),
            )
            assertTrue(
                "ALIAS_INITIAL_TAB only lists non-default tabs; '$alias' -> $index must be >= 1",
                index >= 1,
            )
        }
    }
}
