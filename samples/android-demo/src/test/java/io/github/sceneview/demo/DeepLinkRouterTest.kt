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

    @Test
    fun `retired wall link opens placement with wall selected`() {
        val uri = Uri.parse("sceneview://demo/wall-placement")
        assertEquals("ar-placement", DeepLinkRouter.parse(uri))
        assertEquals(1, DeepLinkRouter.resolveInitialTab("wall-placement", null))
        assertEquals(0, DeepLinkRouter.resolveInitialTab("wall-placement", "0"))
        assertTrue(ALL_DEMOS.none { it.id == "wall-placement" })
    }


    @Test
    fun `old physics tab link opens rolling balls with no tab`() {
        val uri = Uri.parse("sceneview://demo/animation-physics?tab=1")
        val launch = DeepLinkRouter.resolveLaunch(
            DeepLinkRouter.parse(uri),
            DeepLinkRouter.extractCandidate(uri),
            DeepLinkRouter.parseTabParam(uri),
        )
        assertEquals(DeepLinkRouter.Launch("rolling-balls", null), launch)
        assertTrue(ALL_DEMOS.any { it.id == "rolling-balls" })
    }

    @Test
    fun `cosmos spacetime link opens cosmos on the spacetime view`() {
        val uri = Uri.parse("sceneview://demo/cosmos?tab=spacetime")
        val launch = DeepLinkRouter.resolveLaunch(
            DeepLinkRouter.parse(uri),
            DeepLinkRouter.extractCandidate(uri),
            DeepLinkRouter.parseTabParam(uri),
        )
        assertEquals(DeepLinkRouter.Launch("cosmos", 1), launch)
        assertEquals(
            DeepLinkRouter.Launch("cosmos", 0),
            DeepLinkRouter.resolveLaunch("cosmos", "cosmos", "starlight"),
        )
        // The names are Cosmos's own: on another tabbed demo they mean nothing.
        assertNull(DeepLinkRouter.resolveInitialTab("materials", "spacetime"))
        assertNull(DeepLinkRouter.resolveInitialTab("model-viewer", "starlight"))
    }

    @Test
    fun `tab for a demo without tabs is dropped`() {
        // Kept, it would linger and open the next tabbed demo (Materials) on Streaming.
        assertEquals(
            DeepLinkRouter.Launch("animation-physics", null),
            DeepLinkRouter.resolveLaunch("animation-physics", "animation-physics", "2"),
        )
        assertEquals(
            DeepLinkRouter.Launch("rolling-balls", null),
            DeepLinkRouter.resolveLaunch("rolling-balls", "physics", "1"),
        )
        assertEquals(
            DeepLinkRouter.Launch("materials", 1),
            DeepLinkRouter.resolveLaunch("materials", "texture-streaming", null),
        )
        assertEquals(DeepLinkRouter.Launch(null, null), DeepLinkRouter.resolveLaunch(null, "nope", "1"))
    }

    @Test
    fun `every alias tab lands on a demo that reads its tab`() {
        DeepLinkRouter.ALIAS_INITIAL_TAB.keys.forEach { alias ->
            val target = DeepLinkRouter.DEMO_ID_ALIASES.getValue(alias)
            assertTrue("$alias -> $target", target in DeepLinkRouter.TABBED_DEMOS)
        }
        DeepLinkRouter.TABBED_DEMOS.forEach { id ->
            assertTrue(id, ALL_DEMOS.any { it.id == id })
        }
    }

    // Title / subtitle don't matter for the router under test — it only
    // looks at the id. We pass arbitrary R.string.* values to satisfy the
    // post-#1099 resource-ID typed fields without resolving them.
    private val knownRegistry = listOf(
        DemoEntry("ar-rerun", R.string.demo_ar_rerun_title, R.string.demo_ar_rerun_subtitle, "Augmented Reality", Icons.Filled.ViewInAr, order = 1, tags = setOf("test"), addedIn = "4.0.0"),
        DemoEntry("model-viewer", R.string.demo_model_viewer, R.string.demo_model_viewer_subtitle, "3D Basics", Icons.Filled.ViewInAr, order = 2, tags = setOf("test"), addedIn = "4.0.0"),
    )

    // Registry holding the three #2239 Batch 1 consolidated demos that the
    // retired ids redirect to. The router only inspects `id`, so the title /
    // subtitle resources are arbitrary (see the note above).
    private val consolidatedRegistry = listOf(
        DemoEntry("custom-geometry", R.string.demo_custom_geometry_title, R.string.demo_custom_geometry_subtitle, "Advanced", Icons.Filled.ViewInAr, order = 3, tags = setOf("test"), addedIn = "4.0.0"),
        DemoEntry("picking-collision", R.string.demo_picking_collision_title, R.string.demo_picking_collision_subtitle, "Interaction", Icons.Filled.ViewInAr, order = 4, tags = setOf("test"), addedIn = "4.0.0"),
        DemoEntry("camera-gestures", R.string.demo_camera_and_gestures_title, R.string.demo_camera_and_gestures_subtitle, "Interaction", Icons.Filled.ViewInAr, order = 5, tags = setOf("test"), addedIn = "4.0.0"),
    )

    // Registry holding the three consolidated demos the #2239 catalogue-regroup
    // slice redirects onto. The router only inspects `id`, so the title /
    // subtitle resources are arbitrary (see the note above).
    private val regroupRegistry = listOf(
        DemoEntry("lighting-lab", R.string.demo_lighting_lab_title, R.string.demo_lighting_lab_subtitle, "Rendering", Icons.Filled.ViewInAr, order = 6, tags = setOf("test"), addedIn = "4.0.0"),
        DemoEntry("camera-gestures", R.string.demo_camera_and_gestures_title, R.string.demo_camera_and_gestures_subtitle, "Interaction", Icons.Filled.ViewInAr, order = 7, tags = setOf("test"), addedIn = "4.0.0"),
        DemoEntry("ar-geospatial-anchors", R.string.demo_ar_geospatial_anchors_title, R.string.demo_ar_geospatial_anchors_subtitle, "AR Anchors", Icons.Filled.ViewInAr, order = 8, tags = setOf("test"), addedIn = "4.0.0"),
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
                addedIn = "4.0.0",
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

    // ── #2239 catalogue regroup — the four ids retired by the section slice ───
    //
    // `fog` -> `lighting-lab` (mode 4), `gesture-feedback-preview` ->
    // `camera-gestures` (which #3500 rebuilt without modes), and `ar-terrain` /
    // `ar-rooftop` -> `ar-geospatial-anchors` (modes 0 and 1). Every one of these ids is on the
    // public deep-link surface — docs, QR codes, the Maestro flows that
    // deliberately drive retired ids to reach a consolidated demo's modes — so
    // "the card is gone" must never mean "the link is gone".

    @Test
    fun `catalogue-regroup retired ids resolve to their consolidated demo`() {
        val expected = mapOf(
            "fog" to "lighting-lab",
            "gesture-feedback-preview" to "camera-gestures",
            "ar-terrain" to "ar-geospatial-anchors",
            "ar-rooftop" to "ar-geospatial-anchors",
        )
        expected.forEach { (retired, consolidated) ->
            assertEquals(
                "validate('$retired') must redirect to '$consolidated'",
                consolidated,
                DeepLinkRouter.validate(retired, regroupRegistry),
            )
            assertEquals(
                "sceneview://demo/$retired must resolve to '$consolidated'",
                consolidated,
                DeepLinkRouter.parse(Uri.parse("sceneview://demo/$retired"), regroupRegistry),
            )
            assertNull(
                "'$retired' must be gone from the real catalogue — it is a retired id",
                ALL_DEMOS.find { it.id == retired },
            )
        }
    }

    @Test
    fun `catalogue-regroup aliases pre-select the mode that holds their content`() {
        // #3496 rebuilt `lighting-lab` as one tab-less bench, so `fog` — like the
        // three other ids the lab absorbed — no longer pre-selects anything: the
        // fog switch is live on the only frame the lab has.
        assertNull(DeepLinkRouter.ALIAS_INITIAL_TAB["fog"])
        assertNull(DeepLinkRouter.resolveInitialTab("fog", null))
        // lighting: [Image, Studio, Sun] — `dynamic-sky` is the Sun rig.
        assertEquals(2, DeepLinkRouter.resolveInitialTab("dynamic-sky", null))
        // camera-gestures has no modes since the #3500 rebuild — one stage, one camera,
        // and object gestures behind the dock's Move toggle rather than a third tab. Both
        // of its retired ids therefore land on the same (only) view, which is what an
        // absent entry means. The redirect itself is asserted above; what would be wrong
        // is a surviving pre-selection pointing at a tab index that no longer exists.
        assertNull(DeepLinkRouter.ALIAS_INITIAL_TAB["gesture-feedback-preview"])
        assertNull(DeepLinkRouter.resolveInitialTab("gesture-feedback-preview", null))
        assertNull(DeepLinkRouter.ALIAS_INITIAL_TAB["gesture-editing"])
        assertNull(DeepLinkRouter.resolveInitialTab("gesture-editing", null))
        // ar-geospatial-anchors: [Terrain, Rooftop]
        assertEquals(1, DeepLinkRouter.resolveInitialTab("ar-rooftop", null))
        // `ar-terrain` is mode 0 — absent on purpose, an absent entry means
        // "already lands correctly".
        assertNull(DeepLinkRouter.ALIAS_INITIAL_TAB["ar-terrain"])
        assertNull(DeepLinkRouter.resolveInitialTab("ar-terrain", null))
    }

    // ── Samples step 0 — consolidation before any redesign (2026-10-02) ──
    //
    // Android 51 → 43 cards. Every retired card is a mode of the card that absorbed it, and
    // its id stays on the public deep-link surface (docs, QR codes, Maestro flows): the link
    // must open the absorbing card on that mode, never fall through to the demo list.

    @Test
    fun `step 0 retired ids open the card that absorbed them`() {
        val expected = mapOf(
            "ar-pose" to "ar-placement",
            "placement-scene" to "ar-placement",
            "secondary-camera" to "camera-gestures",
            "double-pendulum" to "rolling-balls",
            "ar-record-playback" to "ar-rerun",
            "ar-collaborative" to "ar-cloud-anchor",
            "ar-streetscape" to "ar-geospatial-anchors",
            "ar-hand-tracking" to "ar-xr",
            "ar-xr-face" to "ar-xr",
            "video-recording" to "cosmos",
        )
        expected.forEach { (retired, card) ->
            assertEquals("validate('$retired')", card, DeepLinkRouter.validate(retired, ALL_DEMOS))
            assertEquals(
                "sceneview://demo/$retired",
                card,
                DeepLinkRouter.parse(Uri.parse("sceneview://demo/$retired"), ALL_DEMOS),
            )
            assertEquals(
                "the App-Links form of $retired",
                card,
                DeepLinkRouter.parse(Uri.parse("https://sceneview.github.io/open?demo=$retired"), ALL_DEMOS),
            )
            assertNull("'$retired' is retired", ALL_DEMOS.find { it.id == retired })
        }
        assertEquals("Android ships 43 cards after step 0", 43, ALL_DEMOS.size)
    }

    @Test
    fun `step 0 retired ids pre-select the mode that holds their content`() {
        fun launch(rawId: String, tab: String? = null) =
            DeepLinkRouter.resolveLaunch(DeepLinkRouter.validate(rawId, ALL_DEMOS), rawId, tab)

        assertEquals(DeepLinkRouter.Launch("ar-placement", 2), launch("ar-pose"))
        // `placement-scene` is the One call mode: the AutoPlacementScene screen, not Place.
        assertEquals(DeepLinkRouter.Launch("ar-placement", 3), launch("placement-scene"))
        assertEquals(DeepLinkRouter.Launch("camera-gestures", 1), launch("secondary-camera"))
        assertEquals(DeepLinkRouter.Launch("rolling-balls", 1), launch("double-pendulum"))
        assertEquals(DeepLinkRouter.Launch("ar-rerun", 1), launch("ar-record-playback"))
        assertEquals(DeepLinkRouter.Launch("ar-cloud-anchor", 1), launch("ar-collaborative"))
        assertEquals(DeepLinkRouter.Launch("ar-geospatial-anchors", 2), launch("ar-streetscape"))
        assertEquals(DeepLinkRouter.Launch("ar-xr", null), launch("ar-hand-tracking"))
        assertEquals(DeepLinkRouter.Launch("ar-xr", 1), launch("ar-xr-face"))
        // The Record action replaced `video-recording`: its link opens Cosmos with the pill.
        assertEquals(DeepLinkRouter.Launch("cosmos", null, openRecord = true), launch("video-recording"))
        assertEquals(DeepLinkRouter.Launch("cosmos", null), launch("cosmos"))
        // The modes are addressable by name on the live ids too.
        assertEquals(DeepLinkRouter.Launch("rolling-balls", 1), launch("rolling-balls", "pendulum"))
        assertEquals(DeepLinkRouter.Launch("camera-gestures", 1), launch("camera-gestures", "pip"))
        assertEquals(DeepLinkRouter.Launch("ar-rerun", 1), launch("ar-rerun", "session-mp4"))
        assertEquals(DeepLinkRouter.Launch("ar-cloud-anchor", 1), launch("ar-cloud-anchor", "collaborative"))
        assertEquals(DeepLinkRouter.Launch("ar-placement", 2), launch("ar-placement", "free-pose"))
        assertEquals(DeepLinkRouter.Launch("ar-placement", 3), launch("ar-placement", "one-call"))
        // `?tab=wall` is the token iOS accepts for the wall placement; Android reads it too.
        assertEquals(DeepLinkRouter.Launch("ar-placement", 1), launch("ar-placement", "wall"))
        assertEquals(DeepLinkRouter.Launch("ar-placement", 1), launch("wall-placement", "wall"))
        assertEquals(DeepLinkRouter.Launch("ar-geospatial-anchors", 2), launch("ar-geospatial-anchors", "streetscape"))
        assertEquals(DeepLinkRouter.Launch("ar-xr", 1), launch("ar-xr", "face"))
        // The old in-card tabs keep their index inside the default mode.
        assertEquals(DeepLinkRouter.Launch("ar-placement", 1), launch("wall-placement"))
        assertEquals(DeepLinkRouter.Launch("ar-geospatial-anchors", 1), launch("ar-rooftop"))
        // `ar-scene-mesh` lost its Streetscape mode, so it reads no tab any more.
        assertEquals(DeepLinkRouter.Launch("ar-scene-mesh", null), launch("ar-scene-mesh", "1"))
    }

    @Test
    fun `every TAB_NAMES mode names a tab of a demo that reads it`() {
        DeepLinkRouter.TAB_NAMES.forEach { (demo, names) ->
            assertTrue("$demo must read its launch tab", demo in DeepLinkRouter.TABBED_DEMOS)
            assertTrue("$demo must be registered", ALL_DEMOS.any { it.id == demo })
            names.values.forEach { assertTrue("$demo tab $it", it >= 0) }
        }
    }

    @Test
    fun `a removed demo resolves to no demo and keeps its title`() {
        // Same contract as iOS `removedIds`: the link says the demo is gone.
        assertNull(DeepLinkRouter.validate("placement-reticle-preview", ALL_DEMOS))
        assertEquals(
            "Placement reticle preview",
            DeepLinkRouter.removedTitle("placement-reticle-preview"),
        )
        assertNull(DeepLinkRouter.removedTitle("model-viewer"))
        assertNull(DeepLinkRouter.removedTitle(null))
        DeepLinkRouter.REMOVED_DEMO_IDS.keys.forEach { id ->
            assertNull("$id is removed, not registered", ALL_DEMOS.find { it.id == id })
            assertNull("$id is removed, not aliased", DeepLinkRouter.DEMO_ID_ALIASES[id])
        }
    }

    // ── Initial-tab pre-selection: alias + ?tab= deep-link param (#2315) ──────
    //
    // Consolidated demos open on their default first tab unless an alias (e.g.
    // `movable-light`) or an explicit `--es tab` / `?tab=` value pre-selects another. The
    // resolution is pure (raw id + raw param → index); the bad-index clamp lives in
    // the demo composable (initialDemoMode), out of this unit's reach.

    @Test
    fun `resolveInitialTab maps a non-default alias to its tab`() {
        assertEquals(1, DeepLinkRouter.resolveInitialTab("movable-light", null))
        assertEquals(1, DeepLinkRouter.resolveInitialTab("multi-model", null))
        assertEquals(2, DeepLinkRouter.resolveInitialTab("occlusion-material", null))
    }

    @Test
    fun `resolveInitialTab returns null for a default-tab alias or a plain id`() {
        // Aliases that land on tab 0 are intentionally absent from ALIAS_INITIAL_TAB.
        assertNull(DeepLinkRouter.resolveInitialTab("custom-mesh", null))
        // `shape` joined them in #3423: `custom-geometry` was rebuilt around a single
        // runtime-generated mesh and has no tabs left to pre-select.
        assertNull(DeepLinkRouter.resolveInitialTab("shape", null))
        // `scene-gallery` joined them in #4039: the Models demo lost its Gallery section, and
        // the old link opens the Single Model section (a stale index 2 would fall to the
        // default anyway, but only through the composable's clamp).
        assertNull(DeepLinkRouter.resolveInitialTab("scene-gallery", null))
        assertEquals("model-viewer", DeepLinkRouter.DEMO_ID_ALIASES["scene-gallery"])
        // `physics` joined them in #4083: the balls left Animation & Physics for their own
        // `rolling-balls` demo, which has no tabs.
        assertNull(DeepLinkRouter.resolveInitialTab("physics", null))
        assertEquals("rolling-balls", DeepLinkRouter.DEMO_ID_ALIASES["physics"])
        // A live consolidated id with no tab hint keeps its default tab.
        assertNull(DeepLinkRouter.resolveInitialTab("custom-geometry", null))
        assertNull(DeepLinkRouter.resolveInitialTab(null, null))
    }

    @Test
    fun `2D in 3D routes named modes and every retired media id`() {
        fun launch(id: String, tab: String? = null) =
            DeepLinkRouter.resolveLaunch(
                demoId = DeepLinkRouter.validate(id, ALL_DEMOS), rawId = id, tabParam = tab)
        assertEquals(DeepLinkRouter.Launch("two-d-in-three-d", null), launch("two-d-in-three-d"))
        assertEquals(DeepLinkRouter.Launch("two-d-in-three-d", 0), launch("two-d-in-three-d", "inspect"))
        assertEquals(DeepLinkRouter.Launch("two-d-in-three-d", 1), launch("two-d-in-three-d", "media"))
        for (alias in listOf("text", "image", "video", "billboard")) {
            assertEquals(DeepLinkRouter.Launch("two-d-in-three-d", 1), launch(alias))
            assertEquals(DeepLinkRouter.Launch("two-d-in-three-d", 0), launch(alias, "0"))
        }
    }

    @Test
    fun `explicit tab param wins over the alias default`() {
        // `?tab=0` forces the default tab even when launched via the `movable-light` alias.
        assertEquals(0, DeepLinkRouter.resolveInitialTab("movable-light", "0"))
        // An explicit integer index on a plain id.
        assertEquals(2, DeepLinkRouter.resolveInitialTab("custom-geometry", "2"))
        // An explicit alias-token tab value resolves through ALIAS_INITIAL_TAB.
        assertEquals(2, DeepLinkRouter.resolveInitialTab("materials", "occlusion-material"))
    }

    @Test
    fun `resolveInitialTab falls back to the alias when the tab param is unusable`() {
        // A negative / unparseable / blank explicit value is ignored; the alias applies.
        assertEquals(1, DeepLinkRouter.resolveInitialTab("movable-light", "-1"))
        assertEquals(1, DeepLinkRouter.resolveInitialTab("movable-light", "garbage"))
        assertEquals(1, DeepLinkRouter.resolveInitialTab("movable-light", "  "))
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

    /**
     * The invariant `DEMO_ID_ALIASES`' own KDoc states and nothing was actually checking:
     * a key must be RETIRED (absent from the live catalogue) and a value must be LIVE.
     *
     * Both halves fail loudly for a reason. A key that is still registered means the alias
     * is dead code the router never consults; a value that is not registered means every
     * link through that alias 404s to the demo list — which is exactly the failure a
     * consolidation is supposed to prevent.
     */
    @Test
    fun `every retired alias id is gone from the catalogue and points at a live demo`() {
        val liveIds = ALL_DEMOS.map { it.id }.toSet()
        DeepLinkRouter.DEMO_ID_ALIASES.forEach { (retired, live) ->
            assertTrue(
                "'$retired' is an alias key, so it must no longer be a registered demo id",
                retired !in liveIds,
            )
            assertTrue(
                "alias '$retired' -> '$live', but '$live' is not a registered demo id",
                live in liveIds,
            )
        }
    }

    /**
     * #3405 — `ar-instant-placement` was folded into `ar-placement` (instant placement is a
     * mode of the one flow now). Its deep link is part of the public surface: an
     * `sceneview://demo/ar-instant-placement` link in a doc, a QR code or a store listing
     * has to keep opening the screen that absorbed it.
     */
    @Test
    fun `the retired instant-placement link resolves onto the consolidated flow`() {
        val consolidated = listOf(
            DemoEntry(
                "ar-placement",
                R.string.demo_ar_placement_title,
                R.string.demo_ar_placement_subtitle,
                "Augmented Reality",
                Icons.Filled.ViewInAr,
                order = 6,
                tags = setOf("test"),
                addedIn = "4.0.0",
            ),
        )
        assertEquals(
            "ar-placement",
            DeepLinkRouter.parse(
                Uri.parse("sceneview://demo/ar-instant-placement"),
                consolidated,
            ),
        )
        // And through the QA / intent-extra ingress, which shares `validate`.
        assertEquals(
            "ar-placement",
            DeepLinkRouter.validate("ar-instant-placement", consolidated),
        )
    }

    /**
     * The consolidated demo has *phases*, not segmented tabs, so the alias must not try to
     * pre-select one — a mode is the user's choice on the chooser, and a link that silently
     * forced instant placement would be answering a question it was never asked.
     */
    @Test
    fun `the instant-placement alias carries no tab pre-selection`() {
        assertNull(DeepLinkRouter.ALIAS_INITIAL_TAB["ar-instant-placement"])
    }

    // ── `--es qa_state <id>` — the one QA state seam (#3421, #3455) ────────────────────

    /**
     * Under `qa_mode` the extra is passed through verbatim (trimmed): the router does not
     * know any demo's vocabulary, so both Cloud Anchor and Point & Ask ids must survive it
     * unchanged for each demo's own resolver to recognise them.
     */
    @Test
    fun `qa_state is accepted when qa_mode is on`() {
        assertEquals("placing", DeepLinkRouter.resolveQaState(qaMode = true, raw = "placing"))
        assertEquals("resolve_not_found", DeepLinkRouter.resolveQaState(qaMode = true, raw = "resolve_not_found"))
        assertEquals("failed-persistent", DeepLinkRouter.resolveQaState(qaMode = true, raw = "failed-persistent"))
        assertEquals("ready", DeepLinkRouter.resolveQaState(qaMode = true, raw = "  ready "))
    }

    /**
     * Without `qa_mode` the extra is ignored outright — a pinned state makes a screen claim
     * something that never happened, so `adb shell am start --es qa_state hosted` against a
     * production install must be a no-op. This is the path that keeps the seam out of
     * release builds; a blank id is dropped on either path.
     */
    @Test
    fun `qa_state is ignored when qa_mode is off`() {
        assertNull(DeepLinkRouter.resolveQaState(qaMode = false, raw = "placing"))
        assertNull(DeepLinkRouter.resolveQaState(qaMode = false, raw = "hosted"))
        assertNull(DeepLinkRouter.resolveQaState(qaMode = false, raw = "answered"))
        assertNull(DeepLinkRouter.resolveQaState(qaMode = false, raw = null))
        assertNull(DeepLinkRouter.resolveQaState(qaMode = true, raw = null))
        assertNull(DeepLinkRouter.resolveQaState(qaMode = true, raw = ""))
        assertNull(DeepLinkRouter.resolveQaState(qaMode = true, raw = "   "))
    }
}
