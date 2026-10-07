package io.github.sceneview.demo

import android.net.Uri

/**
 * Parses an incoming intent's data URI and returns the demo id to open,
 * or `null` if the URI is not a valid SceneView deep link.
 *
 * Supported URI shapes:
 *
 * 1. **Custom scheme** — `sceneview://demo/<id>` (intent-filter in
 *    AndroidManifest, no store verification needed). The id is the
 *    last path segment.
 *
 * 2. **Verified App-Links** (future) — `https://sceneview.github.io/open?demo=<id>`.
 *    Pulled from the `demo` query parameter. Will become live once
 *    `/.well-known/assetlinks.json` ships on github.io with the
 *    published Play Store keystore SHA-256.
 *
 * The id must match an entry in [registry] (defaults to [ALL_DEMOS]) —
 * we don't blindly navigate to user-provided strings, both because the
 * demo registry is closed and because that prevents trivial fuzzing of
 * the navigation graph from a hostile QR code. Unknown ids return `null`
 * and the activity falls back to its normal start destination.
 *
 * Pure function: no side effects, no Android framework calls beyond
 * [Uri] accessors. Unit-testable on a plain JVM via Robolectric's
 * `Uri` shadow or by passing already-parsed primitives — see
 * `DeepLinkRouterTest`.
 */
internal object DeepLinkRouter {

    /** Custom URL scheme registered in `AndroidManifest.xml`. */
    const val SCHEME_CUSTOM: String = "sceneview"

    /** Custom URL host. Only `demo` is supported today. */
    const val HOST_CUSTOM: String = "demo"

    /** Hostname for verified App-Links (future). */
    const val HOST_HTTPS: String = "sceneview.github.io"

    /** Path prefix on the App-Links host. */
    const val PATH_HTTPS: String = "/open"

    /** Query parameter name carrying the demo id on the App-Links host. */
    const val QUERY_PARAM: String = "demo"

    /**
     * Query parameter name carrying the optional camera-to-model distance (zoom level),
     * in metres — `sceneview://demo/<id>?cameraDistance=<f>`. See [parseCameraDistance].
     */
    const val QUERY_PARAM_CAMERA_DISTANCE: String = "cameraDistance"

    /** Smallest accepted camera distance, in metres. Anything below resolves to `null`. */
    const val CAMERA_DISTANCE_MIN: Float = 0.05f

    /** Largest accepted camera distance, in metres. Anything above resolves to `null`. */
    const val CAMERA_DISTANCE_MAX: Float = 100f

    /**
     * Query parameter / intent-extra name carrying the optional initial tab a consolidated
     * demo should open on — `sceneview://demo/<id>?tab=<id|index>` and the `--es tab <v>`
     * QA extra. The value is either a 0-based segmented-button index (`?tab=1`) or a retired
     * alias token (`?tab=texture-streaming`) resolved through [ALIAS_INITIAL_TAB]. See [resolveInitialTab]
     * (#2315).
     */
    const val QUERY_PARAM_TAB: String = "tab"

    /**
     * Retired demo ids mapped to the consolidated demo that absorbed them.
     *
     * A demo id is part of the public deep-link surface (`sceneview://demo/<id>`),
     * so when two demos are merged into one (issue #1444) the old ids must keep
     * resolving rather than falling through to the demo list. [validate] consults
     * this table when a candidate id is not itself registered: an incoming
     * `movable-light` link is rewritten to the live `lighting` demo that now
     * hosts it as an in-demo mode.
     *
     * Keys are retired ids that no longer appear in [ALL_DEMOS]; values MUST be
     * live registered ids — `DeepLinkRouterTest` asserts this invariant.
     */
    val DEMO_ID_ALIASES: Map<String, String> = mapOf(
        "wall-placement" to "ar-placement",
        // #1444 — `movable-light` was merged into the consolidated `lighting` demo.
        "movable-light" to "lighting",
        // #2239 Batch 1 — Custom Geometry consolidation. The retired
        // `custom-mesh` and `shape` demos merged into `custom-geometry`.
        // #3423 then rebuilt that demo from scratch around a single
        // runtime-generated mesh, so its two tabs are gone: both aliases now
        // land on the same (only) view and neither carries an
        // [ALIAS_INITIAL_TAB] entry. Existing `sceneview://demo/custom-mesh`
        // and `sceneview://demo/shape` deep links keep working.
        "custom-mesh" to "custom-geometry",
        "shape" to "custom-geometry",
        // #2239 Batch 1 — Picking & Collision consolidation. The retired
        // `collision` and `view-node` demos merged into `picking-collision`.
        // #3329 then merged that demo's own two tabs into a single scene, so
        // both aliases now land on the same (only) view — no [ALIAS_INITIAL_TAB]
        // entry, because there is no tab left to pre-select.
        "collision" to "picking-collision",
        "view-node" to "picking-collision",
        // #2239 Batch 1 — Camera & Gestures consolidation. The retired
        // `camera-controls` and `gesture-editing` demos merged into
        // `camera-gestures` with a segmented-button toggle. #3500 then rebuilt
        // that demo from scratch around one stage and one camera, so its tabs
        // are gone: object gestures are the dock's Move toggle inside the same
        // scene. All three aliases now land on the same (only) view and none
        // carries an [ALIAS_INITIAL_TAB] entry. The deep links keep working.
        "camera-controls" to "camera-gestures",
        "gesture-editing" to "camera-gestures",
        // 2D in 3D — Inspect is the default; all four retired flat-content demos
        // open its Media gallery through [ALIAS_INITIAL_TAB].
        "text" to "two-d-in-three-d",
        "image" to "two-d-in-three-d",
        "video" to "two-d-in-three-d",
        "billboard" to "two-d-in-three-d",
        // #2239 Batch 2 — Lighting Lab consolidation. The retired `dynamic-sky`,
        // `environment`, `reflection-probes`, and `post-processing` demos merged
        // into `lighting-lab`. #3496 then rebuilt both lighting screens from
        // scratch and split them by role rather than by subject, which moved two
        // of those four: `dynamic-sky` is now the Sun rig of `lighting` and
        // `environment` its Image rig, while `reflection-probes` and
        // `post-processing` stay on the lab — which no longer has tabs, so they
        // carry no [ALIAS_INITIAL_TAB] entry. Every one of the four deep links
        // still resolves, each to the half that now hosts its subject.
        "dynamic-sky" to "lighting",
        "environment" to "lighting",
        "reflection-probes" to "lighting-lab",
        "post-processing" to "lighting-lab",
        // #2239 Batch 3 — the retired `animation` and `physics` demos merged into
        // `animation-physics`. #4083 then split the Physics tab back out as its own
        // `rolling-balls` demo, so `animation-physics` has no tabs any more and
        // `physics` lands on the tray of balls it always meant.
        "animation" to "animation-physics",
        "physics" to "rolling-balls",
        // #2239 Batch 4 — Materials consolidation. The retired `texture-streaming`
        // and `occlusion-material` demos merged into the existing `materials` entry
        // (the natural umbrella, kept live) with a segmented-button toggle.
        // `materials` lands on the default PBR Materials tab; `texture-streaming`
        // and `occlusion-material` pre-select their matching tabs (#2315 — see
        // [ALIAS_INITIAL_TAB]).
        "texture-streaming" to "materials",
        "occlusion-material" to "materials",
        // #2239 Batch 5 — Models consolidation. The retired `multi-model` and
        // `scene-gallery` demos merged into the existing `model-viewer` entry
        // (the flagship umbrella, kept live — its id + `ModelViewerDemo.kt` file
        // are referenced across docs) with a segmented-button toggle.
        "multi-model" to "model-viewer",
        "scene-gallery" to "model-viewer",
        // #3405 — AR placement consolidation. `ar-instant-placement` was a second,
        // hand-rolled implementation of the `ar-placement` screen whose only distinct
        // subject — `Config.InstantPlacementMode.LOCAL_Y_UP` and the
        // `SCREENSPACE_WITH_APPROXIMATE_DISTANCE → FULL_TRACKING` refinement — is now a mode
        // chosen on that demo's pre-AR chooser. No [ALIAS_INITIAL_TAB] entry: the
        // consolidated demo has phases, not segmented tabs, and the mode it lands on is a
        // user choice the alias has no business overriding.
        "ar-instant-placement" to "ar-placement",
        // #2239 catalogue regroup — `fog` merged into `lighting-lab` as its fifth
        // mode. Both are the same per-Filament-`View` option family the lab already
        // reaches through the `rememberView` handle, so the fold moved a 181-line
        // card with no modes of its own under the card literally named "Lighting
        // Lab". Since #3496 fog is a switch on the lab's one bench rather than a
        // mode, so the alias no longer pre-selects anything. `ARFogNode` keeps
        // `FogNode` demonstrated in AR (`ar-fog`).
        "fog" to "lighting-lab",
        // #2239 catalogue regroup — `gesture-feedback-preview` merged into
        // `camera-gestures` as its third mode. The Gestures mode flips `isEditable`
        // and draws no affordance; the preview drew the affordance with no controls.
        // Same subject, cut in half.
        "gesture-feedback-preview" to "camera-gestures",
        // #2239 catalogue regroup — `ar-terrain` and `ar-rooftop` merged into
        // `ar-geospatial-anchors`. 303 of ~485 lines were identical; the whole
        // difference is `altitudeAboveTerrain` vs `altitudeAboveRooftop` on the
        // resolve call and the `Anchor.*AnchorState` enum it answers with.
        "ar-terrain" to "ar-geospatial-anchors",
        "ar-rooftop" to "ar-geospatial-anchors",
        // #2239 catalogue regroup (#3463) — `ar-streetscape` merged into the
        // `ar-scene-mesh` card, now titled "Scene Geometry", as its second mode.
        // 246 of 445 lines were identical and both call the same primary API
        // (`Config.StreetscapeGeometryMode.ENABLED` +
        // `frame.getUpdatedTrackables(StreetscapeGeometry)`); the whole difference is
        // which node consumes the trackable — the classified `SceneMeshNode` or its raw
        // `StreetscapeGeometryNode` base. Samples step 0 then moved the Streetscape mode
        // to `ar-geospatial-anchors`: the raw Streetscape trackables sit with the Geospatial
        // anchors, and `ar-scene-mesh` keeps the classified `SceneMeshNode` alone.
        "ar-streetscape" to "ar-geospatial-anchors",
        // Samples step 0 — consolidation before any redesign (samples audit, 2026-10-02).
        // Each retired card is a mode of the card that absorbed it; the mode is
        // pre-selected through [ALIAS_INITIAL_TAB] when it is not the default one.
        // `ar-pose` is the Free pose mode of `ar-placement`; `placement-scene`, the same
        // flow as one `AutoPlacementScene` call, is its One call mode.
        "ar-pose" to "ar-placement",
        "placement-scene" to "ar-placement",
        "secondary-camera" to "camera-gestures",
        "double-pendulum" to "rolling-balls",
        "ar-record-playback" to "ar-rerun",
        "ar-collaborative" to "ar-cloud-anchor",
        // The two Android XR cards became the Hands and Face modes of `ar-xr`.
        "ar-hand-tracking" to "ar-xr",
        "ar-xr-face" to "ar-xr",
        // `video-recording` became the shared Record action of `DemoScaffold`; its link
        // opens Cosmos with the Record pill shown ([ALIAS_OPENS_RECORD]).
        "video-recording" to "cosmos",
    )

    /**
     * Ids removed from the catalogue with nothing that replaces them, mapped to the title
     * they shipped under. Same contract as iOS `DemoDeepLinkRegistry.removedIds`: a printed QR
     * code or a bookmark still resolves, and the user is told the truth — the demo is gone,
     * not pending. [MainActivity] says so and stays on the home screen.
     *
     * `placement-reticle-preview` left Android in #3275 and iOS in samples step 0; links to
     * it still come from iOS builds and docs that predate the removal.
     */
    val REMOVED_DEMO_IDS: Map<String, String> = mapOf(
        "placement-reticle-preview" to "Placement reticle preview",
    )

    /** The title of a [REMOVED_DEMO_IDS] id, or `null` for any other id. Never throws. */
    fun removedTitle(rawId: String?): String? = rawId?.trim()?.let { REMOVED_DEMO_IDS[it] }

    /**
     * Retired ids whose link opens the shared Record action of the card they now point to:
     * `video-recording` was a card of its own until the Record action replaced it.
     */
    val ALIAS_OPENS_RECORD: Set<String> = setOf("video-recording")

    /**
     * Retired alias ids whose content lives on a **non-default** tab of the consolidated
     * demo that absorbed them. When a consolidated demo is opened through one of these
     * aliases (e.g. `sceneview://demo/texture-streaming`), it should pre-select the matching segmented
     * tab instead of falling back to its default first tab (#2315).
     *
     * The index is 0-based and matches the order of the demo's segmented-button modes.
     * Aliases for the default mode or a demo without modes are omitted: an absent entry
     * means "no pre-selection". The retired text/image/video/billboard ids all open Media,
     * index 1 of 2D in 3D. `DeepLinkRouterTest` asserts every key is a known
     * [DEMO_ID_ALIASES] retired id, so this table cannot drift out of sync.
     */
    val ALIAS_INITIAL_TAB: Map<String, Int> = mapOf(
        // two-d-in-three-d — [Inspect, Media].
        "text" to 1,
        "image" to 1,
        "video" to 1,
        "billboard" to 1,
        "wall-placement" to 1,
        // lighting — [Image, Studio, Sun] since #3496. `environment` is deliberately
        // absent: the Image rig is index 0, the rig the demo already opens on.
        "movable-light" to 1,
        "dynamic-sky" to 2,
        // materials — [PBR Materials, Streaming, Occlusion]
        "texture-streaming" to 1,
        "occlusion-material" to 2,
        // model-viewer — [Single Model, Multi-Model]. `scene-gallery` is deliberately absent
        // since #4039 removed its Gallery section: the link opens the Single Model section.
        "multi-model" to 1,
        // ar-geospatial-anchors — [Terrain, Rooftop] (#2239). `ar-terrain` is the
        // default first mode, so it is deliberately absent: an absent entry means
        // "no pre-selection needed", which is exactly right for index 0.
        "ar-rooftop" to 1,
        // ar-geospatial-anchors — launch tab 2 is the Streetscape mode since samples
        // step 0 (tabs 0 and 1 stay Terrain and Rooftop inside the Anchors mode).
        "ar-streetscape" to 2,
        // Samples step 0. ar-placement — launch tab 2 is the Free pose mode (tabs 0 and 1
        // stay the floor and wall placements inside the Place mode).
        "ar-pose" to 2,
        // ar-placement — launch tab 3 is the One call mode.
        "placement-scene" to 3,
        // camera-gestures — [Camera, PiP].
        "secondary-camera" to 1,
        // rolling-balls — [Balls, Pendulum].
        "double-pendulum" to 1,
        // ar-rerun — [Rerun, Session MP4].
        "ar-record-playback" to 1,
        // ar-cloud-anchor — [Cloud anchors, Collaborative].
        "ar-collaborative" to 1,
        // ar-xr — [Hands, Face]. `ar-hand-tracking` is the default mode, so it is absent.
        "ar-xr-face" to 1,
    )

    /**
     * Named `?tab=` tokens of live demos, for a mode that was never a demo of its own and so
     * has no retired id in [ALIAS_INITIAL_TAB]: `sceneview://demo/cosmos?tab=spacetime` opens
     * Cosmos on its Star scene's Spacetime view. Keyed by demo id: a name means a tab of its
     * own demo only, so `?tab=spacetime` on any other demo is unrecognised.
     */
    val TAB_NAMES: Map<String, Map<String, Int>> = mapOf(
        "two-d-in-three-d" to mapOf("inspect" to 0, "media" to 1),
        // cosmos — [Starlight, Spacetime], the Star scene's two views.
        "cosmos" to mapOf("starlight" to 0, "spacetime" to 1),
        // Samples step 0 — each consolidated card's modes, by the [DemoMode] key its pill uses.
        // ar-placement — `wall` is the wall placement inside Place, the token iOS uses too.
        "ar-placement" to mapOf("place" to 0, "wall" to 1, "free-pose" to 2, "one-call" to 3),
        "camera-gestures" to mapOf("camera" to 0, "pip" to 1),
        "rolling-balls" to mapOf("balls" to 0, "pendulum" to 1),
        "ar-rerun" to mapOf("rerun" to 0, "session-mp4" to 1),
        "ar-cloud-anchor" to mapOf("cloud-anchors" to 0, "collaborative" to 1),
        "ar-geospatial-anchors" to mapOf("anchors" to 0, "terrain" to 0, "streetscape" to 2),
        "ar-xr" to mapOf("hands" to 0, "face" to 1),
    )

    /**
     * Tabs that left their demo for a demo of their own, keyed by (demo, 0-based tab). A link
     * that still asks for one — `sceneview://demo/animation-physics?tab=1`, the old Physics
     * tab — opens the new demo instead (#4083). See [resolveLaunch].
     */
    val SPLIT_OUT_TABS: Map<Pair<String, Int>, String> = mapOf(
        ("animation-physics" to 1) to "rolling-balls",
    )

    /**
     * The demos whose screen reads a launch tab (through `initialDemoMode` or
     * `DemoSettings.consumeInitialTab`). A tab resolved for any other demo is dropped by
     * [resolveLaunch]: nothing would consume it, so it would linger and pre-select a tab of
     * the next tabbed demo opened from the home (`animation-physics?tab=1`, then Materials
     * opening on Streaming).
     */
    val TABBED_DEMOS: Set<String> = setOf(
        "two-d-in-three-d", // DemoModeHost consumes Inspect / Media.
        "materials",
        "ar-placement",
        "model-viewer",
        "ar-geospatial-anchors",
        "lighting",
        "cosmos",
        // Samples step 0 — the cards whose `DemoModeHost` reads the launch tab.
        "camera-gestures",
        "rolling-balls",
        "ar-rerun",
        "ar-cloud-anchor",
        "ar-xr",
    )

    /**
     * Where an incoming link lands: the demo to open, the tab it should pre-select, and
     * whether it opens with the shared Record action shown ([ALIAS_OPENS_RECORD]).
     */
    data class Launch(val demoId: String?, val initialTab: Int?, val openRecord: Boolean = false)

    /**
     * Combines the validated [demoId] with the tab resolved by [resolveInitialTab] from
     * [rawId] and [tabParam]: a tab listed in [SPLIT_OUT_TABS] opens its new demo with no
     * tab, and a tab for a demo outside [TABBED_DEMOS] is dropped. A retired [rawId] that
     * names no mode of its new home (absent from [ALIAS_INITIAL_TAB]) drops its tab too: that
     * index counted the retired demo's own tabs, not the modes the absorbing demo has now
     * (`physics?tab=1` must not open Rolling Balls on Pendulum). A [rawId] in
     * [ALIAS_OPENS_RECORD] sets [Launch.openRecord]. Never throws.
     */
    fun resolveLaunch(demoId: String?, rawId: String?, tabParam: String?): Launch {
        if (demoId == null) return Launch(null, null)
        val retiredWithoutMode = rawId != demoId && rawId in DEMO_ID_ALIASES && rawId !in ALIAS_INITIAL_TAB
        val tab = if (retiredWithoutMode) null else resolveInitialTab(rawId, tabParam)
        if (tab != null) SPLIT_OUT_TABS[demoId to tab]?.let { return Launch(it, null) }
        return Launch(
            demoId = demoId,
            initialTab = tab?.takeIf { demoId in TABBED_DEMOS },
            openRecord = rawId in ALIAS_OPENS_RECORD,
        )
    }

    fun parse(data: Uri?, registry: List<DemoEntry> = ALL_DEMOS): String? {
        if (data == null) return null
        val candidate = extractCandidate(data) ?: return null
        return validate(candidate, registry)
    }

    /**
     * Validates a raw demo id against [registry]. Used by the QA-channel
     * ingress (`--es demo <id>` from `adb shell am`) so the same allow-list
     * applies whether the id comes from a URL deep-link or from the intent
     * extra. Without this, any app on the device could steer navigation by
     * passing an arbitrary string — same risk as the unvalidated
     * `ar_playback_file` extra fixed in commit `a7dec5e3`. See #958.
     *
     * Returns the candidate iff it is non-blank AND matches a registered
     * demo; otherwise `null`, which the caller treats as "no deep-link".
     *
     * A non-blank candidate that is not itself registered is given one more
     * chance through [DEMO_ID_ALIASES] — a retired id (e.g. `movable-light`)
     * resolves to the consolidated demo that absorbed it (e.g. `lighting`),
     * provided that target is itself registered. This keeps old deep links
     * working after demo consolidation (#1444). Truly unknown ids still
     * return `null`, so the fuzzing guard is unchanged.
     */
    fun validate(id: String?, registry: List<DemoEntry> = ALL_DEMOS): String? {
        val candidate = id?.takeIf { it.isNotBlank() } ?: return null
        if (registry.any { it.id == candidate }) return candidate
        val aliased = DEMO_ID_ALIASES[candidate] ?: return null
        return if (registry.any { it.id == aliased }) aliased else null
    }

    /**
     * Parses an optional camera-to-model distance (zoom level) from a deep-link URI's
     * `cameraDistance` query parameter — `sceneview://demo/<id>?cameraDistance=<f>`.
     *
     * Used by the device-QA harness to launch a demo at a near or far framing, since
     * Maestro has no pinch gesture (see #1571). Robust by construction: a missing
     * parameter, an unparseable string, a non-finite value (NaN / ±∞), or a value
     * outside `[CAMERA_DISTANCE_MIN, CAMERA_DISTANCE_MAX]` all return `null`, which the
     * caller treats as "keep the demo's default framing". Never throws.
     *
     * Mirrors [validateCameraDistance] — both ingress channels (URL deep link and the
     * `camera_distance` intent extra, see [coerceCameraDistanceExtra]) apply the
     * identical clamp.
     */
    fun parseCameraDistance(data: Uri?): Float? {
        if (data == null) return null
        val raw = runCatching { data.getQueryParameter(QUERY_PARAM_CAMERA_DISTANCE) }
            .getOrNull() ?: return null
        return validateCameraDistance(raw.toFloatOrNull())
    }

    /**
     * Validates an already-parsed camera distance against the accepted range. Shared by
     * the URL deep-link path ([parseCameraDistance]) and the `camera_distance`
     * intent-extra path ([coerceCameraDistanceExtra]) so both ingress channels behave
     * identically.
     *
     * Returns [value] iff it is non-null, finite, and within
     * `[CAMERA_DISTANCE_MIN, CAMERA_DISTANCE_MAX]`; otherwise `null`.
     */
    fun validateCameraDistance(value: Float?): Float? {
        if (value == null || !value.isFinite()) return null
        return value.takeIf { it in CAMERA_DISTANCE_MIN..CAMERA_DISTANCE_MAX }
    }

    /**
     * Coerces a raw `camera_distance` intent-extra value — whatever Bundle type it
     * arrived under — to a validated camera distance, or `null`.
     *
     * The extra's type depends on who launched the intent, and all senders must work
     * (#2652):
     *  - `adb shell am start --ef camera_distance 0.6` → `Float` (the documented channel);
     *  - Maestro's `launchApp` → `arguments:` — an env-interpolated value
     *    (`camera_distance: ${CAMERA_DISTANCE}`) reaches the intent as a **`String`**
     *    extra, and a bare unquoted YAML number could arrive as `Integer`/`Double`.
     *    Reading only `getFloatExtra` silently dropped the Maestro value to NaN, so the
     *    zoom-QA near/far relaunches (#1571) rendered at the auto-fit framing instead of
     *    the requested distance — verified on-emulator: `--ef` reframes, `--es` was
     *    ignored.
     *
     * Every branch funnels through [validateCameraDistance], so the accepted range and
     * non-finite rejection are identical across encodings. Unsupported types (and an
     * absent extra, `null`) return `null` — "keep the demo's auto-fit framing". Never
     * throws.
     */
    fun coerceCameraDistanceExtra(value: Any?): Float? = when (value) {
        is Number -> validateCameraDistance(value.toFloat())
        is String -> validateCameraDistance(value.toFloatOrNull())
        else -> null
    }

    /**
     * Resolves the 0-based tab index a consolidated demo should pre-select on launch, or
     * `null` for "keep the demo's default first tab" (#2315).
     *
     * Precedence — explicit user intent wins over the alias default, mirroring the
     * `cameraDistance` dual-ingress policy:
     *  1. An explicit [tabParam] (`--es tab <v>` extra or `?tab=<v>` query) — either a
     *     0-based index (`"1"`) or a retired-alias token (`"texture-streaming"`) looked up in
     *     [ALIAS_INITIAL_TAB]. See [parseTabValue].
     *  2. Otherwise, the launching [rawId] itself: if it is a retired alias whose content
     *     lives on a non-default tab (e.g. `texture-streaming` → 1), that tab.
     *
     * [rawId] is the **pre-validation** id as launched (the alias, e.g. `texture-streaming` —
     * not the resolved `materials`), since the alias is what carries the tab hint. Never
     * throws; an absent / blank / unparseable value falls through to the next rule and an
     * out-of-range index is left for the demo to clamp to its default tab.
     */
    fun resolveInitialTab(rawId: String?, tabParam: String?): Int? {
        parseTabValue(tabParam, rawId)?.let { return it }
        return rawId?.let { ALIAS_INITIAL_TAB[it] }
    }

    /**
     * Reads the raw `?tab=` query parameter off a deep-link URI, or `null` when absent.
     * Kept separate from [resolveInitialTab] so [MainActivity] can OR it with the
     * `--es tab` intent extra the same way it ORs the two `cameraDistance` channels.
     */
    fun parseTabParam(data: Uri?): String? {
        if (data == null) return null
        return runCatching { data.getQueryParameter(QUERY_PARAM_TAB) }.getOrNull()
    }

    /**
     * Parses a `?tab=` / `--es tab` value into a 0-based tab index: a non-negative integer
     * literal is taken as-is; any other token is looked up in [ALIAS_INITIAL_TAB] (so
     * `?tab=texture-streaming` selects the Streaming tab of `materials`), then in [demoId]'s
     * own [TAB_NAMES]. A blank, negative, or unrecognised value returns `null`. Never throws.
     */
    internal fun parseTabValue(raw: String?, demoId: String? = null): Int? {
        val token = raw?.trim()?.takeIf { it.isNotBlank() } ?: return null
        token.toIntOrNull()?.let { index -> return index.takeIf { it >= 0 } }
        return ALIAS_INITIAL_TAB[token] ?: demoId?.let { TAB_NAMES[it]?.get(token) }
    }

    /**
     * Resolves the `--es qa_state <id>` intent extra to the state id a demo may pin itself
     * to, or `null` for "run the real state machine" (#3421, #3455).
     *
     * One extra carries every demo's vocabulary — Cloud Anchors resolves its ids with
     * `cloudAnchorScenarioOf`, Point & Ask with `askStepForQaOverride` — and this is the
     * single place the gate lives: the id is returned **only when [qaMode] is on**. A
     * pinned state makes a screen claim something that never happened (a hosted anchor
     * code, a streamed answer), so unlike `qa_backdrop`, which only changes what is behind
     * the scene, an intent extra alone must never be enough to reach it. Blank ids are
     * dropped so a demo never has to distinguish `""` from absent. Never throws.
     */
    fun resolveQaState(qaMode: Boolean, raw: String?): String? {
        if (!qaMode) return null
        return raw?.trim()?.takeIf { it.isNotBlank() }
    }

    /**
     * Extracts the raw id token from a URI without validating it against
     * a registry. Exposed so tests can verify the URI parser separately
     * from the registry lookup.
     */
    internal fun extractCandidate(data: Uri): String? {
        val scheme = data.scheme?.lowercase() ?: return null
        return when (scheme) {
            SCHEME_CUSTOM -> {
                if (!data.host.equals(HOST_CUSTOM, ignoreCase = true)) return null
                data.lastPathSegment?.takeIf { it.isNotBlank() }
            }
            "https", "http" -> {
                if (!data.host.equals(HOST_HTTPS, ignoreCase = true)) return null
                if (data.path?.startsWith(PATH_HTTPS) != true) return null
                data.getQueryParameter(QUERY_PARAM)?.takeIf { it.isNotBlank() }
            }
            else -> null
        }
    }
}
