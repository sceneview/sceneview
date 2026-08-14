package io.github.sceneview.demo.demos

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.sceneview.SceneView
import io.github.sceneview.demo.DemoScaffold
import io.github.sceneview.demo.DemoSettings
import io.github.sceneview.demo.ErrorScrim
import io.github.sceneview.demo.LoadingScrim
import io.github.sceneview.demo.R
import io.github.sceneview.demo.common.rememberMaterialsShowcaseEnvironment
import io.github.sceneview.demo.common.rememberModelDemoEnvironment
import io.github.sceneview.demo.demos.internal.MaterialsSubject
import io.github.sceneview.demo.demos.internal.MaterialsSubjects
import io.github.sceneview.demo.initialDemoMode
import io.github.sceneview.demo.rememberFirstFrameState
import io.github.sceneview.demo.rememberHeroOrbitCameraManipulator
import io.github.sceneview.demo.sketchfab.SketchfabAssetResolver
import io.github.sceneview.math.Position
import io.github.sceneview.math.Size
// `ModelInstance` is a typealias for Filament's `FilamentInstance`; `.model` is an
// extension property (ModelInstance.kt) and needs its own import to resolve.
import io.github.sceneview.model.model
import io.github.sceneview.rememberCameraManipulator
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberEnvironmentLoader
import io.github.sceneview.rememberMaterialLoader
import io.github.sceneview.rememberModelInstance
import io.github.sceneview.rememberModelLoader
import io.github.sceneview.sample.rememberMaterialInstance
import io.github.sceneview.sample.rememberOcclusionMaterialInstance
import io.github.sceneview.sample.rememberUnlitMaterialInstance
import java.io.File

/**
 * Unified "Materials" demo — consolidates the retired `texture-streaming` and
 * `occlusion-material` demos into the existing `materials` entry behind a
 * single segmented-button toggle (#2239 Batch 4).
 *
 * Each sub-mode showcases one facet of materials / textures:
 *
 * - **PBR Materials** — streamed KHR_materials_* extension showcase (sheen,
 *   transmission, iridescence). (The original `materials` demo.)
 * - **Streaming** — runtime texture / material swap on an already-loaded
 *   model. (Formerly `texture-streaming`.)
 * - **Occlusion** — invisible depth-writing surface that hides content behind
 *   it. (Formerly `occlusion-material`.)
 *
 * Each sub-mode keeps its own `SceneView` + its own [rememberEngine] / loaders,
 * so switching tabs tears down the inactive section completely — no engine is
 * hoisted above the `when`, which is what prevents resource leaks across tab
 * switches (Batch 1 review confirmed this pattern). Old deep links route
 * through [io.github.sceneview.demo.DeepLinkRouter.DEMO_ID_ALIASES]; the
 * `materials` id itself stays a live registered demo (the natural umbrella).
 */
@Composable
fun MaterialsDemo(onBack: () -> Unit) {
    var mode by remember {
        mutableStateOf(initialDemoMode(MaterialsMode.entries, MaterialsMode.Pbr))
    }
    when (mode) {
        MaterialsMode.Pbr -> PbrSection(onBack, mode) { mode = it }
        MaterialsMode.Streaming -> StreamingSection(onBack, mode) { mode = it }
        MaterialsMode.Occlusion -> OcclusionSection(onBack, mode) { mode = it }
    }
}

private enum class MaterialsMode(val label: String) {
    Pbr("PBR Materials"),
    Streaming("Streaming"),
    Occlusion("Occlusion"),
}

@Composable
private fun ModeSelector(
    current: MaterialsMode,
    onModeChange: (MaterialsMode) -> Unit,
) {
    val modes = MaterialsMode.entries
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        modes.forEachIndexed { index, m ->
            SegmentedButton(
                selected = m == current,
                onClick = { onModeChange(m) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = modes.size),
                label = { Text(m.label) },
            )
        }
    }
    Spacer(modifier = Modifier.height(12.dp))
}

// ─── PBR Materials section ───────────────────────────────────────────────────
// The original `materials` demo. Streamed showcase of the KHR_materials_* PBR
// extension family — sheen, transmission, iridescence — sourced from
// Sketchfab's CC-BY PBR catalogue (the same curated set declared in
// [SampleAssets]'s `materials` category).
//
// The previous version of this demo (parity with the iOS placeholder) was a
// 5-sphere metallic/roughness spectrum that didn't actually exercise any of
// the modern glTF material extensions. Stage 2 replaces it with curated
// extension-bearing models so the demo answers "what do KHR_materials_sheen
// / _transmission / _iridescence look like in SceneView?" at a glance.
//
// Lighting uses the studio HDR as IBL (no skybox) so reflections hit the
// extension materials cleanly — sheen, transmission, clearcoat and iridescence
// all rely heavily on environment lighting to read — while the backdrop stays
// the demo's own flat surface at every orbit angle. One shared constant:
// [io.github.sceneview.demo.common.MATERIALS_SHOWCASE_HDR].
//
// **The first frame is deterministic (#2874).** The section opens on the
// bundled [MaterialsSubjects.BUNDLED_DEFAULT] — Khronos' ToyCar, whose GLB
// carries clearcoat + sheen + transmission — so what it renders on a cold
// launch does not depend on an API key, the network or the disk cache.
// Selecting a streamed chip is an explicit user action. Before this, the
// section opened on streamed slug 0 and rendered either the Sketchfab model
// or its bundled fallback depending on connectivity, which made the demo
// unusable as a store frame or a screenshot-diff subject.
//
// Honours the umbrella's hard rules:
//   - **No Sketchfab WebView / external link.** Local file URLs only.
//   - **No network required to render something useful.** The default subject
//     is bundled outright, and a streamed chip whose resolve fails still
//     stages its bundled fallback through the resolver.
//
// @see MaterialsSubjects for the subject list + framing / determinism contract.
// @see SketchfabAssetResolver for the resolve / fallback contract.

@Composable
private fun PbrSection(
    onBack: () -> Unit,
    mode: MaterialsMode,
    onModeChange: (MaterialsMode) -> Unit,
) {
    val context = LocalContext.current
    val resolver = remember(context) { SketchfabAssetResolver.getInstance(context) }

    // The chips: the bundled default first, then the three curated `materials`
    // slugs — sheen, transmission, iridescence. Stage 2 keeps the streamed count
    // low so the offline-fallback footprint stays bounded; Stage 3 will expand
    // once a CI maintenance cron validates each slug weekly.
    //
    // Cold launch lands on the BUNDLED subject, never a streamed one (#2874):
    // what a streamed slug resolves to depends on the API key, the network and
    // the cache, so the demo used to render a different subject run to run —
    // measured as an insect on one device and the helmet fallback on another,
    // from the same build. The determinism contract lives in [MaterialsSubjects]
    // and is asserted by `MaterialsSubjectsTest`; the streamed catalogue is
    // untouched and stays one tap away.
    val subjects = remember { MaterialsSubjects.all() }
    var selectedIndex by remember { mutableIntStateOf(MaterialsSubjects.DEFAULT_INDEX) }
    val selectedSubject = subjects.getOrNull(selectedIndex)
    // Non-null only while a STREAMED chip is selected — the resolve pipeline
    // below keys off it, and a null value means "the bundled subject is on
    // screen, nothing to resolve".
    val selectedSlug = (selectedSubject as? MaterialsSubject.Streamed)?.slug

    // Bumped by the error scrim's Retry button. It is a `produceState` key so a
    // tap re-runs the resolve coroutine for the same slug instead of leaving the
    // demo stuck on a failed resolution forever (#2088).
    var retryTick by remember { mutableStateOf(0) }

    LaunchedEffect(resolver) {
        runCatching { resolver.prefetchAll("materials") }
    }

    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val environmentLoader = rememberEnvironmentLoader(engine)

    // The one showcase IBL, shared with the Streaming section (#2874). Extension
    // materials (sheen / transmission / iridescence / clearcoat) are heavily
    // IBL-dependent and read flatly under default ambient light. No skybox: the
    // camera orbits, so a drawn environment put a different backdrop behind the
    // subject on every capture — see the helper's KDoc for the measurement.
    val activeEnvironment = rememberMaterialsShowcaseEnvironment(environmentLoader)

    // A failed resolve is surfaced as [MaterialResolveState.Error] instead of
    // being swallowed into a `null` path that hangs the loading scrim forever
    // (#2088). `retryTick` re-runs the resolve when the user taps Retry.
    // A null slug — the bundled chip is selected (#2874), or the registry
    // category is empty (#2122) — exits to [MaterialResolveState.Empty] and
    // leaves the bundled instance driving the viewport.
    val resolveState: MaterialResolveState by produceState<MaterialResolveState>(
        initialValue = MaterialResolveState.Loading,
        key1 = resolver,
        key2 = selectedSlug?.uid,
        key3 = retryTick,
    ) {
        value = MaterialResolveState.Loading
        val slug = selectedSlug ?: run {
            value = MaterialResolveState.Empty
            return@produceState
        }
        value = runCatching { resolver.resolve(slug) }
            .fold(
                onSuccess = { MaterialResolveState.Resolved(it) },
                onFailure = { MaterialResolveState.Error(it.message ?: it.javaClass.simpleName) },
            )
    }
    val resolvedFile = (resolveState as? MaterialResolveState.Resolved)?.file
    val resolveError = (resolveState as? MaterialResolveState.Error)?.message

    // The bundled default subject, loaded from `assets/` — no network, no cache,
    // no API key, the same bytes on every launch. Loaded unconditionally (and
    // kept loaded while a streamed chip is on screen) so its slot stays stable
    // and so switching back to it is instant.
    val bundledInstance =
        rememberModelInstance(modelLoader, MaterialsSubjects.BUNDLED_DEFAULT.assetPath)

    // Load the resolved file (streamed GLB or bundled fallback) through
    // [rememberFileModelInstance] → `ModelLoader.loadModelInstance("file://…")`,
    // NOT the two-argument `rememberModelInstance(modelLoader, fileUri)`. The
    // latter binds to the asset-path overload — Kotlin prefers the candidate that
    // needs no default argument — which feeds the `file://` string straight to
    // `AssetManager.open`; that throws, the instance stays `null`, and the
    // "Streaming material…" scrim hangs forever even though the bundled fallback
    // resolved instantly offline (#2302 — same root cause as #1422 / the
    // Multi-Model section of ModelViewerDemo). Called unconditionally so its
    // `produceState` slot stays stable (#1464).
    val streamedInstance = rememberFileModelInstance(modelLoader, resolvedFile)

    // What is actually on screen: the bundled subject unless a streamed chip is
    // selected. Never a silent fallback between the two — a streamed chip that
    // fails shows its error scrim rather than quietly swapping the subject,
    // which is the ambiguity #2874 was about.
    val modelInstance = if (selectedSlug == null) bundledInstance else streamedInstance

    val firstFrame = rememberFirstFrameState()

    DemoScaffold(
        title = stringResource(R.string.demo_materials_title),
        onBack = onBack,
        firstFrameRendered = firstFrame.rendered,
        controls = {
            ModeSelector(mode, onModeChange)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                subjects.forEachIndexed { index, subject ->
                    FilterChip(
                        selected = index == selectedIndex,
                        onClick = { selectedIndex = index },
                        // displayName + the KHR_* tag give the user a clear
                        // read of "which extension am I looking at" without
                        // needing a second line of copy. The tag comes from the
                        // registry's `tags[0]` for a streamed slug, and from the
                        // GLB's own `extensionsUsed` for the bundled default.
                        label = { Text(subject.displayName) },
                    )
                }
            }
            // Extension tag — the `KHR_materials_*` family this subject
            // demonstrates. Displayed below the chips so the user can map the
            // chip choice to the glTF extension being demonstrated.
            selectedSubject?.let { subject ->
                if (subject.extensionTag.isNotBlank()) {
                    Text(
                        text = subject.extensionTag,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
                Text(
                    text = stringResource(R.string.demo_materials_credit, subject.author),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        },
    ) {
        // One orbit radius for every chip (#2874). The subject is normalised to
        // [MaterialsSubjects.FRAMING_UNITS] below, so the camera does not have to
        // move when the chip changes — and no subject reads as a speck because it
        // happens to be a 15 cm beetle next to a 90 cm sofa.
        val cameraManipulator = rememberHeroOrbitCameraManipulator(
            trigger = modelInstance != null,
            radius = MaterialsSubjects.ORBIT_RADIUS_METERS,
            yHeight = 0f,
            durationMillis = 18_000,
        )
        Box(modifier = Modifier.fillMaxSize()) {
            SceneView(
                modifier = Modifier.fillMaxSize(),
                onFrame = firstFrame.onFrame,
                engine = engine,
                modelLoader = modelLoader,
                environmentLoader = environmentLoader,
                environment = activeEnvironment,
                cameraManipulator = cameraManipulator,
            ) {
                // Both subjects stay MOUNTED; the inactive one is hidden. Feeding
                // one call site an instance that swaps on every chip re-keys
                // `remember(engine, modelInstance)` in SceneScope.ModelNode, and the
                // outgoing node's DisposableEffect runs `node.destroy()` — which
                // walks `childNodes` and calls `engine.safeDestroyEntity` on the
                // entities the ModelInstance only BORROWS (`ownsEntity` is false, so
                // the id survives but the renderable component does not, Node.kt:1284).
                // `bundledInstance` is retained for the whole session and never
                // reloaded, so one chip round-trip left it with zero renderables:
                // Toy Car → any streamed chip → Toy Car rendered a silent black
                // viewport, with no scrim because the instance is still non-null.
                //
                // Every subject is normalised to the SAME size rather than to its own
                // `scaleToUnits` (#2874) — the camera is fixed, so a per-model scale
                // is what made one chip fill the viewport and the next read as a speck.
                //
                // `autoAnimate = !qaMode` mirrors ModelViewerDemo (#2958): `qaMode`
                // freezes the orbit yaw but NOT model animation, so a subject with a
                // baked animation would keep moving under it and the section's golden
                // screenshots would drift frame to frame. Every subject the section can
                // show today is static (ToyCar declares no animation; the three
                // `materials` slugs are `hasBakedAnimation = false`), so this changes no
                // pixel now — it is what keeps the contract true when the CATALOG gains
                // an animated slug, which is a data edit no code review would catch.
                // Read once at node creation, like ModelViewerDemo: the QA harness sets
                // `DemoSettings.qaMode` before launching a demo, so the value is already
                // settled when these nodes mount. Re-keying on it instead would destroy
                // and rebuild the nodes, which is exactly the entity-teardown defect
                // #2939 fixed above.
                bundledInstance?.let { instance ->
                    ModelNode(
                        modelInstance = instance,
                        scaleToUnits = MaterialsSubjects.FRAMING_UNITS,
                        isVisible = selectedSlug == null,
                        autoAnimate = !DemoSettings.qaMode,
                    )
                }
                streamedInstance?.let { instance ->
                    ModelNode(
                        modelInstance = instance,
                        scaleToUnits = MaterialsSubjects.FRAMING_UNITS,
                        isVisible = selectedSlug != null,
                        autoAnimate = !DemoSettings.qaMode,
                    )
                }
            }
            // Mutually exclusive with LoadingScrim: a resolve failure shows the
            // error scrim (with Retry) instead of hanging on "Streaming…" (#2088).
            // Only a STREAMED chip can fail to resolve; the bundled default has
            // nothing to resolve, so it never reaches the error branch.
            if (resolveError != null && selectedSlug != null) {
                ErrorScrim(
                    message = resolveError,
                    onRetry = { retryTick++ },
                    label = stringResource(R.string.demo_materials_error),
                    retryLabel = stringResource(R.string.demo_materials_retry),
                )
            } else {
                // Covers both subjects: the bundled GLB decoding on a cold start
                // and a streamed slug still resolving. `modelInstance` is the
                // single source of "is there something to show yet", so an empty
                // registry category can no longer strand the scrim (#2122) —
                // there is always a bundled subject behind it. The label follows
                // the subject: the default one is decoded from `assets/`, and
                // saying "Streaming…" over it would be a lie.
                LoadingScrim(
                    loading = modelInstance == null,
                    label = stringResource(
                        if (selectedSlug == null) {
                            R.string.demo_materials_loading_bundled
                        } else {
                            R.string.demo_materials_loading
                        }
                    ),
                )
            }
        }
    }
}

// ─── Streaming section ───────────────────────────────────────────────────────
// Formerly TextureStreamingDemo (issue #1480). Runtime texture / material
// streaming sample.
//
// A single loaded model — a [SphereNode], the cleanest possible canvas for
// reading a material — whose surface material is swapped live from a chip
// picker. Selecting a chip reassigns the node's [com.google.android.filament.MaterialInstance]
// via Filament's `setMaterialInstanceAt(...)`, which is cheap: no geometry
// rebuild, no model reload. The model loads exactly once; only the material
// data changes.
//
// Distinct from the PBR Materials section (#1423), which streams a *whole new
// model* per chip. Here the model is constant and the material is the variable —
// the pattern you reach for when an end user is "trying on" finishes,
// skins, or texture packs on a product in a viewer.
//
// Threading: every [MaterialInstance] is allocated by
// [rememberMaterialInstance] (a `DisposableEffect`-backed composable helper),
// so all Filament JNI allocation happens on the main thread and every handle
// is destroyed when the demo leaves the composition — no leak across a
// home → demo → home navigation cycle. The runtime swap itself
// (`setMaterialInstanceAt`) is driven from `SphereNode`'s `SideEffect`,
// also on the main thread.
//
// Lighting uses the studio HDR so the metallic / roughness contrast across
// the variants actually reads — PBR surfaces are heavily IBL-dependent and
// look flat under default ambient light.

/**
 * One swappable PBR material "set" — a named appearance the demo can apply
 * to the model at runtime.
 *
 * Each variant bundles the four parameters that fully describe a Filament
 * colored PBR surface: a base [color] plus the [metallic] / [roughness] /
 * [reflectance] triple. Swapping the active variant is the teaching point
 * of the Streaming section — it shows that material/texture data can be
 * exchanged on an *already-loaded* model without rebuilding its geometry.
 *
 * The variants are bundled in-app rather than fetched from the network so
 * the demo renders something useful with zero connectivity. Streaming the
 * same kind of data from a remote catalogue (Sketchfab material packs,
 * a CDN of `.ktx` texture sets, …) is a documented follow-up — see the
 * `changelog.d/1480-texture-streaming.md` note.
 */
private data class MaterialVariant(
    val labelRes: Int,
    val color: Color,
    val metallic: Float,
    val roughness: Float,
    val reflectance: Float,
)

@Composable
private fun StreamingSection(
    onBack: () -> Unit,
    mode: MaterialsMode,
    onModeChange: (MaterialsMode) -> Unit,
) {
    // Bundled material "sets" — a spread of metallic / roughness so the
    // runtime swap is visually obvious. Polished Steel and Brushed Gold sit
    // at high metallic; Matte Plastic and Glazed Ceramic at the dielectric
    // end; Copper bridges the two with a warm tint.
    val variants = remember {
        listOf(
            MaterialVariant(
                labelRes = R.string.demo_texture_streaming_variant_steel,
                color = Color(0xFFB8BCC4),
                metallic = 1.0f,
                roughness = 0.18f,
                reflectance = 0.6f,
            ),
            MaterialVariant(
                labelRes = R.string.demo_texture_streaming_variant_gold,
                color = Color(0xFFE6B64C),
                metallic = 1.0f,
                roughness = 0.32f,
                reflectance = 0.7f,
            ),
            MaterialVariant(
                labelRes = R.string.demo_texture_streaming_variant_copper,
                color = Color(0xFFC06A3E),
                metallic = 0.85f,
                roughness = 0.45f,
                reflectance = 0.6f,
            ),
            MaterialVariant(
                labelRes = R.string.demo_texture_streaming_variant_plastic,
                color = Color(0xFF6446CD),
                metallic = 0.0f,
                roughness = 0.7f,
                reflectance = 0.4f,
            ),
            MaterialVariant(
                labelRes = R.string.demo_texture_streaming_variant_ceramic,
                color = Color(0xFFEDE8E0),
                metallic = 0.0f,
                roughness = 0.12f,
                reflectance = 0.5f,
            ),
        )
    }

    var selectedIndex by remember { mutableIntStateOf(0) }
    val selectedVariant = variants[selectedIndex]

    val engine = rememberEngine()
    val materialLoader = rememberMaterialLoader(engine)
    val environmentLoader = rememberEnvironmentLoader(engine)

    // The same showcase IBL the PBR section uses — metallic variants read flat
    // without an environment to reflect. One shared constant so the two material
    // sections cannot drift apart visually (#2874).
    val activeEnvironment = rememberMaterialsShowcaseEnvironment(environmentLoader)

    // One MaterialInstance per variant, allocated up front and owned by the
    // composition. Pre-allocating all of them (instead of one re-keyed
    // instance) means a chip tap is a pure pointer swap — no JNI allocation
    // on the interaction path, so the swap is instant.
    val materialInstances = variants.map { variant ->
        rememberMaterialInstance(
            materialLoader = materialLoader,
            color = variant.color,
            metallic = variant.metallic,
            roughness = variant.roughness,
            reflectance = variant.reflectance,
        )
    }
    val selectedMaterial = materialInstances[selectedIndex]

    val firstFrame = rememberFirstFrameState()

    DemoScaffold(
        title = stringResource(R.string.demo_materials_title),
        onBack = onBack,
        firstFrameRendered = firstFrame.rendered,
        controls = {
            ModeSelector(mode, onModeChange)
            Text(
                text = stringResource(R.string.demo_texture_streaming_picker_label),
                style = MaterialTheme.typography.labelLarge,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                variants.forEachIndexed { index, variant ->
                    FilterChip(
                        selected = index == selectedIndex,
                        onClick = { selectedIndex = index },
                        label = { Text(stringResource(variant.labelRes)) },
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(
                    R.string.demo_texture_streaming_variant_detail,
                    selectedVariant.metallic,
                    selectedVariant.roughness,
                ),
                style = MaterialTheme.typography.labelSmall,
            )
        },
    ) {
        // The model is a primitive SphereNode (built synchronously, no async
        // load), so the idle orbit can start right away.
        val cameraManipulator = rememberHeroOrbitCameraManipulator(
            trigger = true,
            radius = 1.6f,
            yHeight = 0f,
            durationMillis = 20_000,
        )
        Box(modifier = Modifier.fillMaxSize()) {
            SceneView(
                modifier = Modifier.fillMaxSize(),
                onFrame = firstFrame.onFrame,
                engine = engine,
                materialLoader = materialLoader,
                environmentLoader = environmentLoader,
                environment = activeEnvironment,
                cameraManipulator = cameraManipulator,
            ) {
                // The model is loaded once. `SphereNode` propagates a changed
                // `materialInstance` through `setMaterialInstanceAt(0, …)` in
                // its SideEffect, so swapping the picker re-skins this exact
                // node without rebuilding geometry — the streaming-swap point.
                SphereNode(
                    radius = 0.5f,
                    materialInstance = selectedMaterial,
                    position = Position(0f, 0f, 0f),
                )
            }
        }
    }
}

// ─── Occlusion section ───────────────────────────────────────────────────────
// Formerly OcclusionMaterialDemo (#1776, parity child of #1754). Showcase for
// [`MaterialLoader.createOcclusionInstance()`][io.github.sceneview.loaders.MaterialLoader.createOcclusionInstance]
// — the SceneView equivalent of RealityKit's `OcclusionMaterial` and Sceneform
// legacy's `MaterialFactory.makeOcclusionMaterial(...)`.
//
// Stage (#2304 framing):
//  - A virtual helmet sits at the world origin, scaled to 0.6 m and framed close by a static
//    camera looking at the origin, lit by the shared studio IBL so it fills the viewport and
//    reads against the dark background.
//  - A vertical occluder plane sits between the camera and the helmet at `z = +0.7 m`, with
//    its edge on the helmet's centre line so it hides exactly one lateral half of the helmet.
//
// Toggle:
//  - **Occluder visible ON** — the plane wears a tinted unlit material so the user can SEE
//    where it is. The helmet behind it draws normally because the plane is opaque-painted
//    (so it should also occlude — proving the toggle ground truth).
//  - **Occluder visible OFF** — the plane wears the new occlusion material. The plane
//    itself is now invisible (zero pixels painted), but its depth value still goes into the
//    depth buffer, so any helmet fragment behind it fails the depth test and is hidden.
//
// Effect: one half of the helmet visibly disappears WHERE the plane is, with no plane
// painted on top — a sharp vertical cut down the middle into the dark background.
// That's the entire contract — a "ghost wall" that blocks virtual content without ever
// rendering itself.
//
// The whole section is non-AR (3D `SceneView { }`), so the comparison is reproducible on
// every device — no ARCore required. For AR scenes that want the same effect against the
// **live camera depth image**, use
// [`ARCameraStream.isDepthOcclusionEnabled`][io.github.sceneview.ar.camera.ARCameraStream]
// instead — that path samples ARCore's per-pixel depth, not a static occluder mesh, and is
// the right tool when the "occluder" is the user's real-world environment.

@Composable
private fun OcclusionSection(
    onBack: () -> Unit,
    mode: MaterialsMode,
    onModeChange: (MaterialsMode) -> Unit,
) {
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val materialLoader = rememberMaterialLoader(engine)
    val environmentLoader = rememberEnvironmentLoader(engine)

    // Hoisted so the helmet loads once for the whole section — re-toggling the occluder
    // never re-parses the GLB.
    val helmetInstance = rememberModelInstance(
        modelLoader,
        "models/khronos_damaged_helmet.glb"
    )

    // Two materials for the in-front plane.
    //
    // 1. `occlusionMaterial` — invisible, depth-writing. The thing this section exists to
    //    demonstrate. Allocate once and reuse — no parameters to tweak.
    val occlusionMaterial = rememberOcclusionMaterialInstance(materialLoader)
    // 2. `debugVisibleMaterial` — a translucent slate plate that lets the user see WHERE
    //    the plane is when the toggle is ON. Used as a ground-truth visual; not the
    //    feature being demonstrated.
    val debugVisibleMaterial = rememberUnlitMaterialInstance(
        materialLoader,
        Color(0.4f, 0.4f, 0.45f, 1f),
    )

    // UI state — true means "show the debug-visible plate", false means "use the occlusion
    // material". Default `false` so the user opens the section on the actual feature — see
    // the helmet visibly cut by an invisible plane — before being shown the ground truth.
    var occluderVisible by remember { mutableStateOf(false) }

    val firstFrame = rememberFirstFrameState()

    DemoScaffold(
        title = stringResource(R.string.demo_materials_title),
        onBack = onBack,
        firstFrameRendered = firstFrame.rendered,
        controls = {
            ModeSelector(mode, onModeChange)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.demo_occlusion_material_toggle),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = occluderVisible,
                    onCheckedChange = { occluderVisible = it },
                )
            }
            Text(
                text = stringResource(R.string.demo_occlusion_material_explainer),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            SceneView(
                modifier = Modifier.fillMaxSize(),
                onFrame = firstFrame.onFrame,
                engine = engine,
                modelLoader = modelLoader,
                materialLoader = materialLoader,
                environmentLoader = environmentLoader,
                // Studio IBL (no skybox) — the helmet reads via its own lighting against
                // the dark background, and the occluded region stays dark too, so the
                // hidden half reads as "gone" rather than as a painted slab (#2304).
                environment = rememberModelDemoEnvironment(environmentLoader),
                // Static camera — the whole section is about depth ordering at a fixed
                // viewpoint. No orbit so the user sees the occlusion effect from a single,
                // reproducible angle. Framed close on the helmet (which sits at the world
                // origin) so it fills the viewport; eye x == target x == 0, so the occluder
                // wall's edge (at world x = 0) projects to the screen centre — a clean
                // vertical cut down the helmet's middle (#2304).
                cameraManipulator = rememberCameraManipulator(
                    orbitHomePosition = Position(0f, 0.2f, 1.4f),
                    targetPosition = Position(0f, 0f, 0f),
                ),
                // The hand-authored helmet + plane positions are meaningful — keep them
                // in world space instead of letting the union bbox auto-centre move
                // them (same reason as CollisionDemo / #1430).
                autoCenterContent = false,
            ) {
                val instance = helmetInstance
                if (instance != null) {
                    // Helmet at the world origin, scaled to 0.6 m so it fills the close
                    // framing (#2304) — the target one lateral half of which the occluder
                    // hides. Placed at the origin and viewed by a camera looking at the
                    // origin (the proven framing pattern other demos use); centerOrigin is
                    // *not* used here because the composable's `position` overrides it.
                    ModelNode(
                        modelInstance = instance,
                        scaleToUnits = 0.6f,
                    )
                }
                // Occluder wall at z = +0.7 m — between the camera (z = +1.4) and the
                // helmet (at the origin), so it clearly sits in front. Its edge is at
                // world x = 0 (the helmet's centre line) and it extends to one side, so it
                // hides exactly one lateral half of the helmet: the other half stays fully
                // visible, giving an obvious vertical occlusion cut down the middle (#2304).
                // The previous 0.5×0.5 plane was *centred* on the helmet and, being closer
                // to the camera, covered the whole silhouette — so the helmet simply
                // vanished and nothing read.
                PlaneNode(
                    size = Size(x = 1.4f, y = 1.4f, z = 0f),
                    materialInstance =
                        if (occluderVisible) debugVisibleMaterial else occlusionMaterial,
                    position = Position(0.7f, 0f, 0.7f),
                )
            }
            LoadingScrim(
                loading = helmetInstance == null,
                label = stringResource(R.string.demo_materials_loading),
            )
        }
    }
}

/** Resolution lifecycle for a streamed material slug. See [PbrSection]. */
private sealed interface MaterialResolveState {
    /** Resolve coroutine in flight. */
    data object Loading : MaterialResolveState

    /**
     * Nothing to resolve — either the bundled subject is selected (the cold-launch
     * default, #2874) or the registry's `materials` category is empty (#2122).
     * Either way the streamed pipeline stays idle; what the viewport shows is
     * driven by the bundled instance.
     */
    data object Empty : MaterialResolveState

    /** Resolve succeeded — [file] is the on-disk GLB (streamed or bundled fallback). */
    data class Resolved(val file: File) : MaterialResolveState

    /** Resolve failed — [message] is a short human-readable reason. */
    data class Error(val message: String) : MaterialResolveState
}

/**
 * Load a [io.github.sceneview.model.ModelInstance] from a nullable local [File],
 * returning `null` until the file is ready.
 *
 * The resolver always hands back a real on-disk [File] (streamed GLB or staged
 * bundled fallback), so the model must be loaded through
 * [io.github.sceneview.loaders.ModelLoader.loadModelInstance], which understands
 * `file://` URIs. The two-argument `rememberModelInstance(modelLoader, String)`
 * call is **not** usable here: Kotlin overload resolution binds it to the
 * asset-path overload (the one without a defaulted `resourceResolver`), which
 * feeds the `file://` string straight to `AssetManager.open` — that throws, the
 * instance stays `null`, and the PBR section hangs forever on the
 * "Streaming material…" scrim (#2302). Mirrors `rememberFileModelInstance` in
 * ModelViewerDemo (the Multi-Model fix, #1422); loading via `produceState` +
 * `loadModelInstance` keeps the Filament JNI work on the loader's own Main hop.
 */
@Composable
private fun rememberFileModelInstance(
    modelLoader: io.github.sceneview.loaders.ModelLoader,
    file: File?,
): io.github.sceneview.model.ModelInstance? {
    val instance = produceState<io.github.sceneview.model.ModelInstance?>(
        initialValue = null,
        key1 = modelLoader,
        key2 = file?.absolutePath,
    ) {
        // The null check lives INSIDE produceState, not as an early `return null`
        // above it: a null file now means "nothing streamed is selected" on every
        // bundled-chip selection, and an early return would add / remove the
        // `produceState` slot on each chip switch (#1464).
        value = file?.let {
            runCatching { modelLoader.loadModelInstance("file://${it.absolutePath}") }.getOrNull()
        }
    }.value
    // Same disposal contract as the library's `rememberModelInstance`
    // (SceneView.kt) — `produceState` cancels its producer on a key change but
    // never destroys the model it already produced, so without this every chip
    // switch abandoned the previous streamed `Model` GPU-resident in
    // `ModelLoader.models` until the section's engine was torn down (#2459).
    // Keying on the produced value fires `onDispose` for the PREVIOUS instance;
    // registered here — before the consuming `ModelNode`, which the caller
    // declares later — so on a swap the node detaches first and the buffers are
    // freed after, respecting #2424's render-loop ordering. `onDispose` runs on
    // the composition (main) thread, satisfying the Filament JNI contract.
    DisposableEffect(instance) {
        onDispose { instance?.let { modelLoader.destroyModel(it.model) } }
    }
    return instance
}
