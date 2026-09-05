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
     * alias token (`?tab=physics`) resolved through [ALIAS_INITIAL_TAB]. See [resolveInitialTab]
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
        // `camera-gestures` with a segmented-button toggle. `camera-controls`
        // lands on the default Camera Modes tab and `gesture-editing`
        // pre-selects the Node Gestures tab (#2315 — see [ALIAS_INITIAL_TAB]).
        "camera-controls" to "camera-gestures",
        "gesture-editing" to "camera-gestures",
        // #2239 Batch 1 — 2D in 3D consolidation. The retired `text`, `image`,
        // `video`, and `billboard` demos merged into `two-d-in-three-d`.
        // #3424 then rebuilt that demo from scratch around one annotated model
        // and a set of `ViewNode` cards, so its four tabs are gone: all four
        // aliases now land on the same (only) view and none of them carries an
        // [ALIAS_INITIAL_TAB] entry. The deep links keep working.
        "text" to "two-d-in-three-d",
        "image" to "two-d-in-three-d",
        "video" to "two-d-in-three-d",
        "billboard" to "two-d-in-three-d",
        // #2239 Batch 2 — Lighting Lab consolidation. The retired `dynamic-sky`,
        // `environment`, `reflection-probes`, and `post-processing` demos merged
        // into `lighting-lab` with a segmented-button toggle. `dynamic-sky` lands
        // on the default Sky tab; `environment`, `reflection-probes`, and
        // `post-processing` pre-select their matching tabs (#2315 — see
        // [ALIAS_INITIAL_TAB]).
        "dynamic-sky" to "lighting-lab",
        "environment" to "lighting-lab",
        "reflection-probes" to "lighting-lab",
        "post-processing" to "lighting-lab",
        // #2239 Batch 3 — Animation & Physics consolidation. The retired
        // `animation` and `physics` demos merged into `animation-physics` with a
        // segmented-button toggle. `animation` lands on the default Animation tab
        // and `physics` pre-selects the Physics tab (#2315 — see [ALIAS_INITIAL_TAB]).
        "animation" to "animation-physics",
        "physics" to "animation-physics",
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
        // mode. Both are the same per-Filament-`View` option family the lab's Post-FX
        // mode already reaches through the `rememberView` handle, so the fold moved a
        // 181-line card with no modes of its own under the card literally named
        // "Lighting Lab". `ARFogNode` keeps `FogNode` demonstrated in AR (`ar-fog`).
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
        // `StreetscapeGeometryNode` base. The live id stays `ar-scene-mesh` because iOS
        // ships a screen under it too. `ar-streetscape` pre-selects mode 1 (#2315 — see
        // [ALIAS_INITIAL_TAB]).
        "ar-streetscape" to "ar-scene-mesh",
    )

    /**
     * Retired alias ids whose content lives on a **non-default** tab of the consolidated
     * demo that absorbed them. When a consolidated demo is opened through one of these
     * aliases (e.g. `sceneview://demo/physics`), it should pre-select the matching segmented
     * tab instead of falling back to its default first tab (#2315).
     *
     * The index is 0-based and matches the order of the demo's segmented-button modes.
     * Aliases that map to the default first tab (index 0 — e.g. `custom-mesh`, `collision`,
     * `text`) or to a demo that has no tabs at all (`shape` since #3423; `image`, `video`
     * and `billboard` since #3424) are intentionally omitted: they already land correctly,
     * so an absent entry
     * means "no pre-selection". `DeepLinkRouterTest` asserts every key is a known
     * [DEMO_ID_ALIASES] retired id, so this table cannot drift out of sync.
     */
    val ALIAS_INITIAL_TAB: Map<String, Int> = mapOf(
        // lighting — [Types, Movable]
        "movable-light" to 1,
        // camera-gestures — [Camera Modes, Node Gestures]
        "gesture-editing" to 1,
        // lighting-lab — [Sky, Environment, Reflections, Post-FX]
        "environment" to 1,
        "reflection-probes" to 2,
        "post-processing" to 3,
        // animation-physics — [Animation, Physics]
        "physics" to 1,
        // materials — [PBR Materials, Streaming, Occlusion]
        "texture-streaming" to 1,
        "occlusion-material" to 2,
        // model-viewer — [Single Model, Multi-Model, Gallery]
        "multi-model" to 1,
        "scene-gallery" to 2,
        // lighting-lab — [Sky, Environment, Reflections, Post-FX, Fog] (#2239)
        "fog" to 4,
        // camera-gestures — [Camera, Gestures, Feedback] (#2239)
        "gesture-feedback-preview" to 2,
        // ar-geospatial-anchors — [Terrain, Rooftop] (#2239). `ar-terrain` is the
        // default first mode, so it is deliberately absent: an absent entry means
        // "no pre-selection needed", which is exactly right for index 0.
        "ar-rooftop" to 1,
        // ar-scene-mesh — [Mesh, Streetscape] (#3463). The Mesh mode is what the
        // surviving `ar-scene-mesh` id already opened, so only the retired id needs
        // an entry.
        "ar-streetscape" to 1,
    )

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
     *     0-based index (`"1"`) or a retired-alias token (`"physics"`) looked up in
     *     [ALIAS_INITIAL_TAB]. See [parseTabValue].
     *  2. Otherwise, the launching [rawId] itself: if it is a retired alias whose content
     *     lives on a non-default tab (e.g. `physics` → 1), that tab.
     *
     * [rawId] is the **pre-validation** id as launched (the alias, e.g. `physics` — not the
     * resolved `animation-physics`), since the alias is what carries the tab hint. Never
     * throws; an absent / blank / unparseable value falls through to the next rule and an
     * out-of-range index is left for the demo to clamp to its default tab.
     */
    fun resolveInitialTab(rawId: String?, tabParam: String?): Int? {
        parseTabValue(tabParam)?.let { return it }
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
     * `?tab=physics` selects the Physics tab). A blank, negative, or unrecognised value returns
     * `null`. Never throws.
     */
    internal fun parseTabValue(raw: String?): Int? {
        val token = raw?.trim()?.takeIf { it.isNotBlank() } ?: return null
        token.toIntOrNull()?.let { index -> return index.takeIf { it >= 0 } }
        return ALIAS_INITIAL_TAB[token]
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
