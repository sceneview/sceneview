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
import io.github.sceneview.demo.ErrorScrim
import io.github.sceneview.demo.LoadingScrim
import io.github.sceneview.demo.R
import io.github.sceneview.demo.common.rememberModelDemoEnvironment
import io.github.sceneview.demo.initialDemoMode
import io.github.sceneview.demo.rememberFirstFrameState
import io.github.sceneview.demo.rememberHeroOrbitCameraManipulator
import io.github.sceneview.demo.sketchfab.SampleAssets
import io.github.sceneview.demo.sketchfab.SketchfabAssetResolver
import io.github.sceneview.environment.rememberHDREnvironment
import io.github.sceneview.math.Position
import io.github.sceneview.math.Size
import io.github.sceneview.rememberCameraManipulator
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberEnvironment
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
// Lighting uses the studio HDR so reflections + IBL hit the streamed
// extension materials cleanly — sheen, transmission, and iridescence all
// rely heavily on environment lighting to read.
//
// Honours the umbrella's hard rules:
//   - **No Sketchfab WebView / external link.** Local file URLs only.
//   - **No network required to render something useful.** Empty key / cold
//     cache → resolver stages the bundled fallback for each slug. The
//     demo's fallback assets do not carry the actual extension materials
//     (those are author-controlled) but they keep the viewport non-empty
//     so the comparison UX stays understandable.
//
// @see SampleAssets for the curated `materials` category.
// @see SketchfabAssetResolver for the resolve / fallback contract.

@Composable
private fun PbrSection(
    onBack: () -> Unit,
    mode: MaterialsMode,
    onModeChange: (MaterialsMode) -> Unit,
) {
    val context = LocalContext.current
    val resolver = remember(context) { SketchfabAssetResolver.getInstance(context) }

    // Three curated `materials` slugs — sheen, transmission, iridescence.
    // Stage 2 keeps the count low so the offline-fallback footprint stays
    // bounded; Stage 3 will expand once a CI maintenance cron validates each
    // slug weekly.
    val slugs = remember { SampleAssets.byCategory["materials"].orEmpty() }
    var selectedIndex by remember { mutableStateOf(0) }
    val selectedSlug = slugs.getOrNull(selectedIndex)

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

    // Studio HDR — extension materials (sheen / transmission / iridescence)
    // are heavily IBL-dependent and read flatly under default ambient light.
    // Skybox enabled so the user can see the same environment the materials
    // are reflecting / refracting.
    val hdrEnvironment = rememberHDREnvironment(
        environmentLoader,
        "environments/studio_2k.hdr",
        createSkybox = true,
    )
    val fallbackEnvironment = rememberEnvironment(environmentLoader)
    val activeEnvironment = hdrEnvironment ?: fallbackEnvironment

    // A failed resolve is surfaced as [MaterialResolveState.Error] instead of
    // being swallowed into a `null` path that hangs the loading scrim forever
    // (#2088). `retryTick` re-runs the resolve when the user taps Retry.
    // A null slug (empty registry category) exits to [MaterialResolveState.Empty]
    // so the loading scrim is not shown forever (#2122).
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
    val isEmpty = resolveState is MaterialResolveState.Empty

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
    val modelInstance = rememberFileModelInstance(modelLoader, resolvedFile)

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
                slugs.forEachIndexed { index, slug ->
                    FilterChip(
                        selected = index == selectedIndex,
                        onClick = { selectedIndex = index },
                        // displayName + the KHR_* tag give the user a clear
                        // read of "which extension am I looking at" without
                        // needing a second line of copy. tags is a
                        // List<String> from the registry — we surface the
                        // first one (`KHR_materials_*`).
                        label = { Text(slug.displayName) },
                    )
                }
            }
            // Extension tag — the registry's `tags[0]` is the
            // `KHR_materials_*` extension name. Displayed below the chips so
            // the user can map the chip choice to the glTF extension being
            // demonstrated.
            selectedSlug?.let { slug ->
                val extension = slug.tags.firstOrNull().orEmpty()
                if (extension.isNotBlank()) {
                    Text(
                        text = extension,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
                Text(
                    text = stringResource(R.string.demo_materials_credit, slug.author),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        },
    ) {
        val cameraManipulator = rememberHeroOrbitCameraManipulator(
            trigger = modelInstance != null,
            radius = 1.2f,
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
                val instance = modelInstance
                val slug = selectedSlug
                if (instance != null && slug != null) {
                    ModelNode(
                        modelInstance = instance,
                        scaleToUnits = slug.scaleToUnits,
                        centerOrigin = Position(0f, 0f, 0f),
                    )
                }
            }
            // Mutually exclusive with LoadingScrim: a resolve failure shows the
            // error scrim (with Retry) instead of hanging on "Streaming…" (#2088).
            // An empty registry category (no slugs) shows nothing — the viewport
            // is already blank; do not show "Loading…" forever (#2122).
            if (resolveError != null) {
                ErrorScrim(
                    message = resolveError,
                    onRetry = { retryTick++ },
                    label = stringResource(R.string.demo_materials_error),
                    retryLabel = stringResource(R.string.demo_materials_retry),
                )
            } else if (!isEmpty) {
                LoadingScrim(
                    loading = modelInstance == null,
                    label = stringResource(R.string.demo_materials_loading),
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

    // Studio HDR + skybox — metallic variants read flat without an
    // environment to reflect. Falls back to the neutral IBL while the HDR
    // streams in (rememberHDREnvironment returns null until decoded).
    val hdrEnvironment = rememberHDREnvironment(
        environmentLoader,
        "environments/studio_2k.hdr",
        createSkybox = true,
    )
    val fallbackEnvironment = rememberEnvironment(environmentLoader)
    val activeEnvironment = hdrEnvironment ?: fallbackEnvironment

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
// Stage:
//  - A virtual helmet sits at `z = -2 m`.
//  - A flat 1 × 1 m plane sits between the camera and the helmet at `z = -1.2 m`.
//
// Toggle:
//  - **Occluder visible ON** — the plane wears a tinted unlit material so the user can SEE
//    where it is. The helmet behind it draws normally because the plane is opaque-painted
//    (so it should also occlude — proving the toggle ground truth).
//  - **Occluder visible OFF** — the plane wears the new occlusion material. The plane
//    itself is now invisible (zero pixels painted), but its depth value still goes into the
//    depth buffer, so any helmet fragment behind it fails the depth test and is hidden.
//
// Effect: the helmet visibly disappears WHERE the plane is, with no plane painted on top.
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
                environment = rememberModelDemoEnvironment(environmentLoader),
                // Static camera — the whole section is about depth ordering at a fixed
                // viewpoint. No orbit so the user sees the occlusion effect from a
                // single, reproducible angle.
                cameraManipulator = rememberCameraManipulator(
                    orbitHomePosition = Position(0f, 0.1f, 0.5f),
                    targetPosition = Position(0f, 0f, -2f),
                ),
                // The hand-authored helmet + plane positions are meaningful — keep them
                // in world space instead of letting the union bbox auto-centre move
                // them (same reason as CollisionDemo / #1430).
                autoCenterContent = false,
            ) {
                val instance = helmetInstance
                if (instance != null) {
                    // Helmet at z = -2 m — the target whose visibility the occluder
                    // controls.
                    ModelNode(
                        modelInstance = instance,
                        scaleToUnits = 0.4f,
                        centerOrigin = Position(0f, 0f, -2f),
                    )
                }
                // Occluder plane at z = -1.2 m — between the camera and the helmet.
                // Slightly smaller than the helmet's bbox so the user can clearly see
                // the occluded vs. unoccluded silhouette difference.
                PlaneNode(
                    size = Size(x = 0.5f, y = 0.5f, z = 0f),
                    materialInstance =
                        if (occluderVisible) debugVisibleMaterial else occlusionMaterial,
                    position = Position(0f, 0f, -1.2f),
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
     * No slug available (empty registry category — the materials category has no entries).
     * The viewport stays blank; the loading scrim is not shown (#2122).
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
    if (file == null) return null
    return produceState<io.github.sceneview.model.ModelInstance?>(
        initialValue = null,
        key1 = modelLoader,
        key2 = file.absolutePath,
    ) {
        value = runCatching {
            modelLoader.loadModelInstance("file://${file.absolutePath}")
        }.getOrNull()
    }.value
}
