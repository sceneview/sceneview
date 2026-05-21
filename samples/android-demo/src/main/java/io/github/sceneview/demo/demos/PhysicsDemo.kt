package io.github.sceneview.demo.demos

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.google.android.filament.LightManager
import io.github.sceneview.SceneView
import io.github.sceneview.demo.DemoScaffold
import io.github.sceneview.demo.R
import io.github.sceneview.demo.SceneViewColors
import io.github.sceneview.demo.rememberFirstFrameState
import io.github.sceneview.demo.sketchfab.SampleAssets
import io.github.sceneview.demo.sketchfab.SketchfabAssetResolver
import io.github.sceneview.demo.sketchfab.SketchfabSlug
import io.github.sceneview.loaders.ModelLoader
import io.github.sceneview.math.Position
import io.github.sceneview.math.Size
import io.github.sceneview.model.Model
import io.github.sceneview.node.ModelNode as ModelNodeImpl
import io.github.sceneview.node.SphereNode as SphereNodeImpl
import io.github.sceneview.rememberCameraManipulator
import io.github.sceneview.rememberCameraNode
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberEnvironmentLoader
import io.github.sceneview.rememberMaterialLoader
import io.github.sceneview.rememberModelLoader
import io.github.sceneview.sample.rememberMaterialInstance
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Demonstrates [PhysicsNode] — drop streamed crash-test bodies (chairs, vases,
 * barrels, amphorae) that fall under gravity and bounce off the floor.
 *
 * Stage 2 migration ([#1152](https://github.com/sceneview/sceneview/issues/1152)).
 *  - Previous version: 5 coloured spheres bouncing on a plane — useful to verify the
 *    rigid-body integrator but not a real visual showcase of "physics on a real GLB".
 *  - New version: the same simulation, but with the four streamed entries from
 *    [SampleAssets.byCategory]`["physics"]` (Ceramic Vase, Wooden Stool,
 *    Wooden Barrel, Clay Amphora — all CC-BY from Sketchfab) cycling through each
 *    drop. The "Bundled spheres" chip preserves the v4.3.1 spheres-only mode for
 *    QA / offline / store-listing screenshot determinism.
 *
 * Physics math is unchanged — every dropped object is treated as a sphere of
 * `collisionRadius = 0.1 m` so the bounce reads naturally regardless of the actual
 * mesh shape (we don't ship a convex-hull collider — `feedback_demo_quality` calls
 * out that the demo's goal is to showcase the SDK's wiring, not to ship a physics
 * engine). The visual mesh is a `ModelNode` parented to the simulated [SphereNodeImpl];
 * the parent sphere is rendered invisibly via a transparent material so only the
 * mesh is visible to the viewer.
 *
 * Each "Drop" press adds a new body. "Reset" clears every body by incrementing a
 * generation key that forces full recomposition. Streamed slugs use the resolver's
 * fallback path when no Sketchfab key is configured, so the carousel always drops
 * something visible even offline.
 */
@Composable
fun PhysicsDemo(onBack: () -> Unit) {
    // Start with 5 bodies so the first frame already shows the demo's hook
    // (a colourful rain on the floor) instead of a near-empty scene.
    var bodyCount by remember { mutableIntStateOf(5) }
    var generation by remember { mutableIntStateOf(0) }

    // Streamed `physics` slugs from SampleAssets. selectedSlug == null means
    // "Bundled spheres" — the v4.3.1 visual default. Selecting a slug arms
    // it as the carousel of streamed crash-test bodies (chairs / vases /
    // barrels / amphorae) cycling through each drop.
    val physicsSlugs = remember { SampleAssets.byCategory["physics"].orEmpty() }
    var selectedSlug by remember { mutableStateOf<SketchfabSlug?>(physicsSlugs.firstOrNull()) }

    val context = LocalContext.current

    // Warm the `physics` cache so the very first drop renders without a pop-in.
    // The resolver dedupes concurrent calls, so the per-body resolve below picks
    // up the cached file as soon as the prefetch lands.
    LaunchedEffect(Unit) {
        runCatching {
            SketchfabAssetResolver.getInstance(context).prefetchAll("physics")
        }
    }

    // Resolve the currently-selected slug to a local file (null while
    // downloading / staging the bundled fallback). When null, drops fall
    // back to the original spheres-only mode so the user sees something
    // moving while the streamed mesh lands.
    val selectedFile: File? = selectedSlug?.let { slug ->
        produceState<File?>(initialValue = null, key1 = slug.uid) {
            value = runCatching {
                SketchfabAssetResolver.getInstance(context).resolve(slug)
            }.getOrNull()
        }.value
    }

    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val materialLoader = rememberMaterialLoader(engine)
    val environmentLoader = rememberEnvironmentLoader(engine)
    // Camera above and back from the scene, angled down. The look-at target sits between
    // the floor (y = -0.5) and the spheres' rest height (y ≈ -0.42) rather than the scene
    // origin, so the 1.6 m ground plane is vertically centred in the viewport instead of
    // being shoved into the bottom third with its near edge clipped (#1463). Pulled back to
    // z = 4 so the full plane depth fits with headroom for the drop column above it.
    val cameraNode = rememberCameraNode(engine) {
        position = Position(0f, 2f, 4f)
        lookAt(Position(0f, -0.35f, 0f))
    }

    val firstFrame = rememberFirstFrameState()

    DemoScaffold(
        title = stringResource(R.string.demo_physics_title),
        onBack = onBack,
        firstFrameRendered = firstFrame.rendered,
        controls = {
            Text("Bodies: $bodyCount", style = MaterialTheme.typography.labelLarge)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(onClick = { bodyCount++ }) {
                    Text("Drop")
                }
                Button(onClick = { bodyCount += 10 }) {
                    Text("Drop 10")
                }
                Button(onClick = {
                    bodyCount = 1
                    generation++
                }) {
                    Text("Reset")
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.demo_physics_picker_label),
                style = MaterialTheme.typography.labelLarge,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // "Bundled spheres" chip preserves the v4.3.1 visual default
                // — useful for QA / offline / store-listing screenshots.
                FilterChip(
                    selected = selectedSlug == null,
                    onClick = {
                        selectedSlug = null
                        // Reset so the chip swap is unambiguous — spheres
                        // first, then more spheres on tap.
                        bodyCount = 5
                        generation++
                    },
                    label = {
                        Text(stringResource(R.string.demo_physics_picker_spheres))
                    },
                )
                physicsSlugs.forEach { slug ->
                    FilterChip(
                        selected = selectedSlug?.uid == slug.uid,
                        onClick = {
                            selectedSlug = slug
                            bodyCount = 5
                            generation++
                        },
                        label = { Text(slug.displayName) },
                    )
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.demo_physics_picker_subtitle),
                style = MaterialTheme.typography.labelSmall,
            )
        }
    ) {
        // key(generation) forces full recomposition on reset
        key(generation) {
            SceneView(
                modifier = Modifier.fillMaxSize(),
                engine = engine,
                modelLoader = modelLoader,
                materialLoader = materialLoader,
                environmentLoader = environmentLoader,
                cameraNode = cameraNode,
                onFrame = firstFrame.onFrame,
                cameraManipulator = rememberCameraManipulator(
                    orbitHomePosition = cameraNode.worldPosition
                )
            ) {
                // Left-side counter-fill — same as v4.3.1, kept verbatim.
                LightNode(
                    type = LightManager.Type.DIRECTIONAL,
                    direction = io.github.sceneview.math.Direction(-0.3f, -1f, -0.5f),
                    apply = {
                        intensity(5_000f)
                    }
                )
                val groundMaterial = rememberMaterialInstance(
                    materialLoader, SceneViewColors.SurfaceDim
                )
                // Ramp4 is a fixed 4-colour list — call the helper once per slot so
                // each MaterialInstance gets the same disposal hygiene as the rest.
                val sphereMaterials = listOf(
                    rememberMaterialInstance(materialLoader, SceneViewColors.Ramp4[0]),
                    rememberMaterialInstance(materialLoader, SceneViewColors.Ramp4[1]),
                    rememberMaterialInstance(materialLoader, SceneViewColors.Ramp4[2]),
                    rememberMaterialInstance(materialLoader, SceneViewColors.Ramp4[3]),
                )

                // Ground plane — must use Size(x, y=0, z) for a HORIZONTAL floor
                PlaneNode(
                    materialInstance = groundMaterial,
                    size = Size(x = 1.6f, y = 0f, z = 1.6f),
                    position = Position(y = -0.5f),
                )

                // Streamed mesh path. The model file is `null` until the
                // resolver returns (or the user picked "Bundled spheres").
                // The GLB is parsed once into a single `Model` (geometry +
                // materials live here, shared by every instance); each falling
                // body then gets its OWN `ModelInstance` spawned from it below.
                // A `ModelInstance` wraps exactly one Filament entity / one
                // TransformManager slot, so it can only ride one body at a
                // time — sharing it across bodies meant every ModelNode wrote
                // the same entity transform (last-write-wins) and only one
                // mesh was ever visible (#1706).
                val streamedModel: Model? = selectedFile?.let { file ->
                    rememberStreamedModel(modelLoader, file)
                }

                // collisionRadius is the bouncing-sphere radius PhysicsNode uses
                // to offset the contact point off the floor. Keep it consistent
                // regardless of whether we render a sphere or a streamed mesh —
                // the demo's value is the simulation hook-up, not a per-mesh
                // collider, and `feedback_demo_quality` says the SDK is the
                // showcase, not bespoke physics.
                val collisionRadius = 0.08f

                for (i in 0 until bodyCount) {
                    val xOffset = (i % 5 - 2) * 0.18f + (if (i / 5 % 2 == 0) 0f else 0.09f)
                    val zOffset = ((i / 5) % 3 - 1) * 0.18f
                    val startY = 0.6f + (i / 5) * 0.18f

                    var nodeRef by remember(i) { mutableStateOf<SphereNodeImpl?>(null) }

                    // The simulated SphereNode is rendered "invisibly" (it
                    // carries the colour ramp material when the user is in
                    // bundled-sphere mode; in streamed mode we ALSO render
                    // it — same coloured silhouette — so the dropped streamed
                    // mesh sits visually on top of a soft colour pad which
                    // hides the bounding-sphere abstraction).
                    SphereNode(
                        radius = collisionRadius,
                        materialInstance = sphereMaterials[i % 4],
                        position = Position(x = xOffset, y = startY, z = zOffset),
                        apply = { nodeRef = this }
                    ) {
                        // Streamed mesh child — only rendered when a streamed
                        // slug is selected AND its download has landed. The
                        // child inherits the sphere's transform so it rides
                        // the simulation. Each body spawns its OWN
                        // `ModelInstance` from the shared `Model` so it has an
                        // independent Filament entity — `createInstance` only
                        // duplicates the lightweight entity tree, geometry and
                        // materials stay shared (#1706). Keyed on `i` so the
                        // instance is created once per body, on the main
                        // composition thread (Filament JNI is @MainThread).
                        val model = streamedModel
                        val slug = selectedSlug
                        if (model != null && slug != null) {
                            val instance = remember(i, model) {
                                modelLoader.createInstance(model)
                            }
                            if (instance != null) {
                                ModelNode(
                                    modelInstance = instance,
                                    scaleToUnits = slug.scaleToUnits,
                                    centerOrigin = Position(0f, 0f, 0f),
                                )
                            }
                        }
                    }

                    // PhysicsNode attaches an onFrame callback that applies
                    // gravity + bounce. The radius is the bounding-sphere
                    // collision radius — the streamed mesh child rides
                    // visually on top.
                    nodeRef?.let { node ->
                        PhysicsNode(
                            node = node,
                            restitution = 0.7f,
                            floorY = -0.5f,
                            radius = collisionRadius,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Parses the streamed GLB at [file] once into a single [Model] that every
 * dropped body then spawns its own [ModelInstance] from.
 *
 * `releaseSourceData = false` is mandatory here: [ModelLoader.createInstance]
 * cannot run after the source glTF data has been released, so we keep it
 * resident for as long as new bodies may still be dropped.
 *
 * Threading mirrors `rememberModelInstance`: the file bytes are read on
 * [Dispatchers.IO], then `createModel` (a `@MainThread` Filament JNI call)
 * runs back on the composition's main dispatcher inside [produceState].
 * Returns `null` while loading.
 */
@Composable
private fun rememberStreamedModel(
    modelLoader: ModelLoader,
    file: File,
): Model? = produceState<Model?>(initialValue = null, key1 = modelLoader, key2 = file.absolutePath) {
    // Read the GLB bytes (and any external glTF resources) off the main
    // thread, then call `createModel` — a @MainThread Filament JNI call —
    // back on the composition's main dispatcher (produceState's context).
    val buffer = withContext(Dispatchers.IO) {
        runCatching { java.nio.ByteBuffer.wrap(file.readBytes()) }.getOrNull()
    } ?: return@produceState
    value = runCatching {
        modelLoader.createModel(
            buffer = buffer,
            releaseSourceData = false,
            resourceResolver = { resourceFile ->
                runCatching {
                    java.nio.ByteBuffer.wrap(File(file.parent, resourceFile).readBytes())
                }.getOrNull()
            },
        )
    }.getOrNull()
}.value
