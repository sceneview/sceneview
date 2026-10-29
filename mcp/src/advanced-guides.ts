/**
 * advanced-guides.ts
 *
 * Animation, gesture, and performance guides for SceneView developers.
 */

// ─── Animation Guide ─────────────────────────────────────────────────────────

export const ANIMATION_GUIDE = `# SceneView Animation Guide

## 1. Playing glTF Model Animations

GLB/glTF models can embed skeletal and morph-target animations. SceneView exposes them via \`Animator\` on the model instance.

\`\`\`kotlin
@Composable
fun AnimatedModelScreen() {
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val modelInstance = rememberModelInstance(modelLoader, "models/character.glb")

    SceneView(engine = engine) {
        modelInstance?.let { instance ->
            ModelNode(
                modelInstance = instance,
                scaleToUnits = 1.0f,
                // autoAnimate = true plays the first animation automatically
                autoAnimate = true
            )
        }
    }
}
\`\`\`

### Controlling Animations Manually

\`\`\`kotlin
@Composable
fun ManualAnimationScreen() {
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val modelInstance = rememberModelInstance(modelLoader, "models/robot.glb")

    SceneView(
        engine = engine,
        onFrame = { frameTimeNanos ->
            modelInstance?.let { instance ->
                val animator = instance.animator
                if (animator.animationCount > 0) {
                    // Update animation time
                    animator.applyAnimation(
                        animationIndex = 0,
                        time = (frameTimeNanos / 1_000_000_000.0).toFloat()
                    )
                    animator.updateBoneMatrices()
                }
            }
        }
    ) {
        modelInstance?.let { instance ->
            ModelNode(modelInstance = instance, scaleToUnits = 1.0f)
        }
    }
}
\`\`\`

### Listing Available Animations

\`\`\`kotlin
modelInstance?.let { instance ->
    val animator = instance.animator
    for (i in 0 until animator.animationCount) {
        val name = animator.getAnimationName(i)
        val duration = animator.getAnimationDuration(i)
        Log.d("Anim", "Animation $i: '$name' (\${duration}s)")
    }
}
\`\`\`

## 2. Spring Animations (KMP Core)

SceneView's KMP core provides spring-based animations for smooth, physics-driven motion.

\`\`\`kotlin
import io.github.sceneview.core.animation.Spring
import io.github.sceneview.core.animation.SpringAnimation

@Composable
fun SpringAnimatedNode() {
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val modelInstance = rememberModelInstance(modelLoader, "models/cube.glb")

    // Spring-based position animation
    var targetY by remember { mutableFloatStateOf(0f) }
    val springY = remember {
        SpringAnimation(
            spring = Spring(
                stiffness = Spring.StiffnessMedium,
                dampingRatio = Spring.DampingRatioMediumBouncy
            ),
            initialValue = 0f
        )
    }

    SceneView(
        engine = engine,
        onFrame = { _ ->
            springY.target = targetY
            springY.advance(0.016f) // ~60fps dt
        }
    ) {
        modelInstance?.let { instance ->
            ModelNode(
                modelInstance = instance,
                scaleToUnits = 0.5f,
                position = Position(0f, springY.value, 0f)
            )
        }
    }

    // Toggle position on button press
    Button(onClick = { targetY = if (targetY == 0f) 2f else 0f }) {
        Text("Bounce")
    }
}
\`\`\`

### Spring Presets

| Preset | Stiffness | Damping | Use Case |
|--------|-----------|---------|----------|
| \`StiffnessHigh\` / \`DampingRatioNoBouncy\` | 10000 | 1.0 | Snappy UI, instant feel |
| \`StiffnessMedium\` / \`DampingRatioLowBouncy\` | 1500 | 0.75 | General purpose |
| \`StiffnessMediumLow\` / \`DampingRatioMediumBouncy\` | 400 | 0.5 | Playful bounce |
| \`StiffnessLow\` / \`DampingRatioHighBouncy\` | 200 | 0.2 | Heavy bounce, toy-like |

## 3. Property Animations

Animate any node property (position, rotation, scale) over time with easing.

\`\`\`kotlin
@Composable
fun PropertyAnimationDemo() {
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val modelInstance = rememberModelInstance(modelLoader, "models/sphere.glb")

    // Compose animation for rotation
    val infiniteTransition = rememberInfiniteTransition(label = "rotate")
    val rotationY by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 3000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "rotY"
    )

    SceneView(engine = engine) {
        modelInstance?.let { instance ->
            ModelNode(
                modelInstance = instance,
                scaleToUnits = 0.5f,
                rotation = Rotation(0f, rotationY, 0f)
            )
        }
    }
}
\`\`\`

### Animating Scale with Compose

\`\`\`kotlin
var isExpanded by remember { mutableStateOf(false) }
val scale by animateFloatAsState(
    targetValue = if (isExpanded) 2.0f else 1.0f,
    animationSpec = spring(
        dampingRatio = Spring.DampingRatioMediumBouncy,
        stiffness = Spring.StiffnessMedium
    ),
    label = "scale"
)

ModelNode(
    modelInstance = instance,
    scaleToUnits = scale
)
\`\`\`

## 4. Smooth Transform (KMP Core)

SmoothTransform interpolates between current and target transforms over time — ideal for following a moving target.

\`\`\`kotlin
import io.github.sceneview.core.animation.SmoothTransform

@Composable
fun SmoothFollowDemo() {
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val modelInstance = rememberModelInstance(modelLoader, "models/drone.glb")

    val smoothPosition = remember { SmoothTransform(smoothTime = 0.3f) }
    var targetPosition by remember { mutableStateOf(Position(0f, 1f, 0f)) }

    SceneView(
        engine = engine,
        onFrame = { _ ->
            smoothPosition.target = targetPosition
            smoothPosition.advance(0.016f)
        }
    ) {
        modelInstance?.let { instance ->
            ModelNode(
                modelInstance = instance,
                scaleToUnits = 0.3f,
                position = smoothPosition.current
            )
        }
    }
}
\`\`\`

## 5. AR Animation — Animated Model on Anchor

\`\`\`kotlin
import com.google.ar.core.Anchor
import com.google.ar.core.Frame
import com.google.ar.core.Plane
import io.github.sceneview.ar.arcore.createAnchorOrNull
import io.github.sceneview.rememberOnGestureListener
// …plus androidx.compose.runtime.* and the io.github.sceneview entry points (ARSceneView, rememberEngine, …)

@Composable
fun ARAnimatedModel() {
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val modelInstance = rememberModelInstance(modelLoader, "models/dancing_character.glb")
    var anchor by remember { mutableStateOf<Anchor?>(null) }
    var latestFrame by remember { mutableStateOf<Frame?>(null) }

    ARSceneView(
        engine = engine,
        modelLoader = modelLoader,
        planeRenderer = true,
        onSessionUpdated = { _, frame -> latestFrame = frame },
        // A confirmed single tap, never a raw ACTION_UP: the end of a drag or a pinch on the
        // placed model must not move it. Leaving onTouchEvent unset keeps the model's gestures.
        onGestureListener = rememberOnGestureListener(
            onSingleTapConfirmed = { event, node ->
                if (node == null) { // empty space, not the placed model
                    latestFrame?.hitTest(event)
                        ?.firstOrNull { (it.trackable as? Plane)?.isPoseInPolygon(it.hitPose) == true }
                        ?.createAnchorOrNull()
                        // Replacing the state key disposes the old AnchorNode, which detaches its anchor.
                        ?.let { anchor = it }
                }
            }
        )
    ) {
        anchor?.let { a ->
            AnchorNode(anchor = a) {
                modelInstance?.let { instance ->
                    ModelNode(
                        modelInstance = instance,
                        scaleToUnits = 0.5f,
                        autoAnimate = true // plays embedded animation
                    )
                }
            }
        }
    }
}
\`\`\`

## Key Takeaways

1. **Embedded animations** — use \`autoAnimate = true\` or control \`Animator\` manually in \`onFrame\`.
2. **Compose animations** — use \`animateFloatAsState\`, \`InfiniteTransition\`, \`Animatable\` — they integrate naturally with SceneView nodes.
3. **Spring animations** — use KMP core \`SpringAnimation\` for physics-driven motion.
4. **SmoothTransform** — use for smooth following / interpolation (camera follow, target tracking).
5. **Threading** — all animation updates in \`onFrame\` run on the main thread (safe for Filament).
`;

// ─── Gesture Guide ───────────────────────────────────────────────────────────

export const GESTURE_GUIDE = `# SceneView Gesture Guide

## 1. Built-in Gestures with \`isEditable\`

The simplest way to add gestures. Setting \`isEditable = true\` on a \`ModelNode\` enables:
- **Pinch to scale** — two-finger pinch scales the model
- **Drag to rotate** — single-finger drag rotates around Y axis
- **Tap to select** — tap highlights the model

\`\`\`kotlin
@Composable
fun EditableModelScreen() {
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val modelInstance = rememberModelInstance(modelLoader, "models/chair.glb")

    SceneView(engine = engine) {
        modelInstance?.let { instance ->
            ModelNode(
                modelInstance = instance,
                scaleToUnits = 1.0f,
                // Enable built-in gestures
                isEditable = true
            )
        }
    }
}
\`\`\`

### What \`isEditable\` Provides

| Gesture | Action | Modifier |
|---------|--------|----------|
| Single tap | Select / deselect | — |
| Single-finger drag | Rotate around Y axis | — |
| Two-finger pinch | Scale up / down | Clamped to min/max |
| Two-finger drag | Pan / translate | — |

## 2. Custom Gesture Handling with \`onTouchEvent\`

For full control, use the \`onTouchEvent\` callback on \`SceneView\` or \`ARSceneView\`.

\`\`\`kotlin
@Composable
fun CustomGestureScreen() {
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val modelInstance = rememberModelInstance(modelLoader, "models/robot.glb")
    var selectedNode by remember { mutableStateOf<ModelNode?>(null) }

    SceneView(
        engine = engine,
        onTouchEvent = { event, hitResult ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    // hitResult contains the node that was tapped (if any)
                    // Use this to detect taps on specific models
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    // Handle drag gestures
                    true
                }
                MotionEvent.ACTION_UP -> {
                    // Handle tap release
                    true
                }
                else -> false
            }
        }
    ) {
        modelInstance?.let { instance ->
            ModelNode(
                modelInstance = instance,
                scaleToUnits = 1.0f
            )
        }
    }
}
\`\`\`

## 3. Tap-to-Place in AR

The most common AR gesture — tap a detected plane to place a model.

\`\`\`kotlin
import com.google.ar.core.Anchor
import com.google.ar.core.Frame
import com.google.ar.core.Plane
import io.github.sceneview.ar.arcore.createAnchorOrNull
import io.github.sceneview.rememberOnGestureListener
// …plus androidx.compose.runtime.* and the io.github.sceneview entry points (ARSceneView, rememberEngine, …)

@Composable
fun TapToPlaceAR() {
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val modelInstance = rememberModelInstance(modelLoader, "models/furniture.glb")
    var anchor by remember { mutableStateOf<Anchor?>(null) }
    var latestFrame by remember { mutableStateOf<Frame?>(null) }

    ARSceneView(
        engine = engine,
        modelLoader = modelLoader,
        planeRenderer = true,
        onSessionUpdated = { _, frame -> latestFrame = frame },
        // A confirmed single tap, never a raw ACTION_UP: the end of a drag or a pinch on the
        // placed model must not move it. Leaving onTouchEvent unset keeps the model's gestures.
        onGestureListener = rememberOnGestureListener(
            onSingleTapConfirmed = { event, node ->
                if (node == null) { // empty space, not the placed model
                    latestFrame?.hitTest(event)
                        ?.firstOrNull { (it.trackable as? Plane)?.isPoseInPolygon(it.hitPose) == true }
                        ?.createAnchorOrNull()
                        // Replacing the state key disposes the old AnchorNode, which detaches its anchor.
                        ?.let { anchor = it }
                }
            }
        )
    ) {
        anchor?.let { a ->
            AnchorNode(anchor = a) {
                modelInstance?.let { instance ->
                    ModelNode(
                        modelInstance = instance,
                        scaleToUnits = 0.5f,
                        isEditable = true // allow further manipulation after placement
                    )
                }
            }
        }
    }
}
\`\`\`

## 4. Drag-to-Rotate with Custom Sensitivity

\`\`\`kotlin
@Composable
fun DragToRotateScreen() {
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val modelInstance = rememberModelInstance(modelLoader, "models/shoe.glb")
    var rotationY by remember { mutableFloatStateOf(0f) }
    var lastX by remember { mutableFloatStateOf(0f) }
    val sensitivity = 0.5f

    SceneView(
        engine = engine,
        onTouchEvent = { event, _ ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    lastX = event.x
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val deltaX = event.x - lastX
                    rotationY += deltaX * sensitivity
                    lastX = event.x
                    true
                }
                else -> false
            }
        }
    ) {
        modelInstance?.let { instance ->
            ModelNode(
                modelInstance = instance,
                scaleToUnits = 1.0f,
                rotation = Rotation(0f, rotationY, 0f)
            )
        }
    }
}
\`\`\`

## 5. Pinch-to-Scale with Custom Limits

\`\`\`kotlin
@Composable
fun PinchToScaleScreen() {
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val modelInstance = rememberModelInstance(modelLoader, "models/vase.glb")
    var currentScale by remember { mutableFloatStateOf(1.0f) }
    val minScale = 0.3f
    val maxScale = 3.0f

    val scaleDetector = rememberScaleGestureDetector { detector ->
        currentScale = (currentScale * detector.scaleFactor).coerceIn(minScale, maxScale)
    }

    SceneView(
        engine = engine,
        onTouchEvent = { event, _ ->
            scaleDetector.onTouchEvent(event)
            true
        }
    ) {
        modelInstance?.let { instance ->
            ModelNode(
                modelInstance = instance,
                scaleToUnits = currentScale
            )
        }
    }
}
\`\`\`

## 6. Multi-Model Selection

\`\`\`kotlin
@Composable
fun MultiModelSelectionScreen() {
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val chair = rememberModelInstance(modelLoader, "models/chair.glb")
    val table = rememberModelInstance(modelLoader, "models/table.glb")
    var selectedModel by remember { mutableStateOf<String?>(null) }

    SceneView(engine = engine) {
        chair?.let { instance ->
            ModelNode(
                modelInstance = instance,
                scaleToUnits = 0.5f,
                position = Position(-1f, 0f, 0f),
                isEditable = true,
                // Per-node tap: Node.onSingleTapConfirmed is (MotionEvent) -> Boolean
                apply = { onSingleTapConfirmed = { selectedModel = "chair"; true } }
            )
        }
        table?.let { instance ->
            ModelNode(
                modelInstance = instance,
                scaleToUnits = 0.5f,
                position = Position(1f, 0f, 0f),
                isEditable = true,
                apply = { onSingleTapConfirmed = { selectedModel = "table"; true } }
            )
        }
    }

    // Show selection UI
    selectedModel?.let { name ->
        Text("Selected: $name", modifier = Modifier.padding(16.dp))
    }
}
\`\`\`

## 7. HitResultNode — Surface Cursor

A model that follows the user's gaze / screen center on AR planes — before they tap to place.

\`\`\`kotlin
@Composable
fun SurfaceCursorAR() {
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val cursorModel = rememberModelInstance(modelLoader, "models/cursor_ring.glb")

    ARSceneView(
        engine = engine,
        modelLoader = modelLoader,
        planeRenderer = true
    ) {
        // HitResultNode automatically follows the center hit on AR planes
        HitResultNode(engine = engine) {
            cursorModel?.let { instance ->
                ModelNode(modelInstance = instance, scaleToUnits = 0.1f)
            }
        }
    }
}
\`\`\`

## Key Takeaways

1. **\`isEditable = true\`** — one-liner for pinch/drag/tap on any ModelNode.
2. **\`onTouchEvent\`** — full MotionEvent access for custom gesture logic.
3. **AR tap-to-place** — in \`onGestureListener = rememberOnGestureListener(onSingleTapConfirmed = { event, node -> if (node == null) … })\`, hit-test the latest ARCore \`Frame\` (from \`onSessionUpdated\`) and call \`createAnchorOrNull()\` on the in-polygon plane hit. Never place on a raw \`ACTION_UP\` in \`onTouchEvent\`: it also fires at the end of every drag and pinch, and returning \`true\` there swallows the editable model's gestures. Swapping the \`anchor\` state disposes the old \`AnchorNode\`, which detaches its anchor.
4. **Custom rotation/scale** — track touch deltas or use \`ScaleGestureDetector\`.
5. **HitResultNode** — AR surface cursor that follows the center of the screen.
6. **Multi-model** — set \`onSingleTapConfirmed\` per ModelNode (in \`apply\`) for selection, or use \`rememberOnGestureListener(onSingleTapConfirmed = { e, node -> })\` on the scene.
`;

// ─── Performance Tips ────────────────────────────────────────────────────────

export const PERFORMANCE_TIPS = `# SceneView Performance Optimization Guide

## 1. Model Optimization

### Polygon Budget

| Device Tier | Max Triangles | Max Draw Calls | Max Textures |
|-------------|--------------|----------------|--------------|
| Low-end     | 50K          | 20             | 8 x 512px    |
| Mid-range   | 100K         | 50             | 16 x 1024px  |
| High-end    | 300K+        | 100+           | 32 x 2048px  |

### LOD (Level of Detail)

Use multiple LODs in your glTF models to reduce triangle count at distance. Filament respects glTF LOD extensions.

\`\`\`kotlin
@Composable
fun LODModelDemo() {
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    // Use a GLB exported with LOD levels from Blender/3ds Max
    // Filament picks the appropriate LOD automatically based on screen coverage
    val modelInstance = rememberModelInstance(modelLoader, "models/building_lod.glb")

    SceneView(engine = engine) {
        modelInstance?.let { instance ->
            ModelNode(
                modelInstance = instance,
                scaleToUnits = 5.0f
            )
        }
    }
}
\`\`\`

### Texture Compression

**Always use KTX2 with Basis Universal** for production builds:

\`\`\`bash
# Install KTX tools: https://github.com/KhronosGroup/KTX-Software
# Convert textures to KTX2 with Basis Universal compression
toktx --t2 --bcmp --assign_oetf srgb output.ktx2 input.png

# Or compress entire glTF with gltf-transform
npx @gltf-transform/cli optimize input.glb output.glb \\
    --compress draco \\
    --texture-compress ktx2
\`\`\`

**Texture size guidelines:**
- Diffuse/base color: 1024x1024 max on mobile
- Normal maps: 512x512 (hard to see detail at higher res on mobile)
- Roughness/metallic: 512x512 (pack into single texture channels)
- Environment HDR: 2K (sky_2k.hdr) is sufficient for most scenes

### Mesh Optimization with Draco / Meshopt

\`\`\`bash
# Draco compression (lossy, smallest files, supported by Filament)
npx @gltf-transform/cli optimize model.glb compressed.glb \\
    --compress draco

# Meshopt compression (lossless, good balance, supported by Filament)
npx @gltf-transform/cli optimize model.glb compressed.glb \\
    --compress meshopt

# Typical savings: 60-90% file size reduction
\`\`\`

## 2. Runtime Performance

### Engine and Loader Reuse

\`\`\`kotlin
// CORRECT — single engine for entire app
@Composable
fun MyApp() {
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)

    // Pass engine and modelLoader to all scenes
    NavHost(...) {
        composable("scene1") { SceneScreen1(engine, modelLoader) }
        composable("scene2") { SceneScreen2(engine, modelLoader) }
    }
}

// WRONG — each screen creates its own engine (wastes GPU memory)
@Composable
fun BadScene() {
    val engine = rememberEngine() // creates new engine each time!
    SceneView(engine = engine) { /* ... */ }
}
\`\`\`

### Avoid Per-Frame Allocations

\`\`\`kotlin
// WRONG — creates new Position every frame
SceneView(
    engine = engine,
    onFrame = { _ ->
        node.position = Position(x, y, z) // allocation every frame!
    }
)

// CORRECT — reuse mutable position
val position = remember { MutablePosition() }
SceneView(
    engine = engine,
    onFrame = { _ ->
        position.set(x, y, z)
        node.position = position
    }
)
\`\`\`

### Limit Concurrent Model Loads

\`\`\`kotlin
// WRONG — loads 20 models at once, spikes memory
items.forEach { item ->
    val model = rememberModelInstance(modelLoader, item.path)
    // All 20 start loading simultaneously
}

// CORRECT — stagger or paginate model loading
val visibleItems = items.take(4) // only load visible models
visibleItems.forEach { item ->
    val model = rememberModelInstance(modelLoader, item.path)
    model?.let { ModelNode(modelInstance = it, scaleToUnits = 0.5f) }
}
\`\`\`

## 3. Frustum Culling

Filament performs frustum culling automatically — objects outside the camera view are not rendered. You can improve this by:

\`\`\`kotlin
// Set accurate bounding boxes on nodes for better culling
ModelNode(
    modelInstance = instance,
    scaleToUnits = 1.0f
    // Filament automatically computes bounding boxes from mesh data
    // For custom geometry, set boundingBox explicitly
)
\`\`\`

**Tips for effective frustum culling:**
- Break large scenes into multiple ModelNodes instead of one giant mesh
- Each ModelNode has its own bounding box and can be culled independently
- Use \`scaleToUnits\` — it helps Filament compute tighter bounding boxes

## 4. Instancing

When rendering multiple copies of the same model (e.g., trees, buildings), use instancing to share mesh data.

\`\`\`kotlin
@Composable
fun InstancedTreesScene() {
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val treeInstance = rememberModelInstance(modelLoader, "models/tree.glb")

    SceneView(engine = engine) {
        // Each ModelNode with the same modelInstance shares GPU mesh data
        // Filament handles instancing internally when using the same asset
        treeInstance?.let { instance ->
            for (i in 0 until 10) {
                val x = (i % 5) * 2.0f - 4.0f
                val z = (i / 5) * 2.0f - 1.0f
                ModelNode(
                    modelInstance = instance,
                    scaleToUnits = 1.5f,
                    position = Position(x, 0f, z)
                )
            }
        }
    }
}
\`\`\`

> **Note:** When placing the same \`modelInstance\` in multiple \`ModelNode\`s, the mesh and material data is shared on the GPU. For independent transforms, Filament handles per-instance transforms efficiently.

## 5. Lighting Optimization

\`\`\`kotlin
// Limit shadow-casting lights — each adds a depth render pass
LightNode(
    type = LightNode.Type.DIRECTIONAL,
    apply = {
        intensity(100_000f)
        direction(0f, -1f, -1f)
        castShadows(true) // adds one depth pass
    }
)

// Use small HDR environments
val environmentLoader = rememberEnvironmentLoader(engine)
val environment = rememberEnvironment(environmentLoader) {
    environmentLoader.createHDREnvironment("environments/sky_2k.hdr")
        ?: createEnvironment(environmentLoader)
}
// NOT: sky_4k.hdr or sky_8k.hdr (wastes GPU memory on mobile)
\`\`\`

**Shadow optimization checklist:**
- Only 1-2 shadow-casting lights max on mobile
- Reduce shadow map resolution if needed (Filament defaults are reasonable)
- Disable shadows on objects that don't need them

## 6. Post-Processing

\`\`\`kotlin
// Lighter preset: shadows, SSAO and bloom off, dynamic resolution on
SceneView(
    engine = engine,
    renderQuality = RenderQuality.Performance
) { /* ... */ }

// Or disable post-processing entirely on the Filament View (no bloom, SSAO, tone mapping, FXAA)
val view = rememberView(engine) {
    createView(engine).apply { isPostProcessingEnabled = false }
}
SceneView(engine = engine, view = view) {
    // Simpler rendering, better FPS
}
\`\`\`

| Effect | Cost | Recommendation |
|--------|------|----------------|
| Tone mapping | Low | Keep enabled (visual quality) |
| FXAA | Low | Keep enabled (anti-aliasing) |
| Bloom | Medium | Disable on low-end devices |
| SSAO | High | Disable on mobile |
| Screen-space reflections | High | Disable on mobile |

## 7. Profiling with Systrace

\`\`\`bash
# Capture a Systrace for Filament rendering
python systrace.py -t 5 -o trace.html gfx view

# Or use Android Studio Profiler:
# 1. Run app with profiling enabled
# 2. Open CPU profiler → Record trace
# 3. Look for Filament sections: "Filament::Renderer", "Filament::View"
\`\`\`

### Key Metrics to Watch

| Metric | Target | Action if Over |
|--------|--------|----------------|
| Frame time | < 16.6ms (60fps) | Reduce polygons, textures |
| Draw calls | < 50 | Merge meshes, use instancing |
| GPU memory | < 256MB | Compress textures, reduce LOD |
| Model load time | < 2s | Use Draco/Meshopt, smaller models |

## 8. Android GPU Inspector (AGI)

For detailed GPU profiling:

1. Install AGI from [developer.android.com/agi](https://developer.android.com/agi)
2. Enable GPU profiling in device developer options
3. Capture a frame: AGI shows exact GPU time per draw call
4. Identify bottlenecks: shader compilation, overdraw, texture sampling

## Key Takeaways

1. **Compress models** — Draco/Meshopt + KTX2 textures. Target 60-90% size reduction.
2. **Reuse engines** — one \`rememberEngine()\` per app, pass it down.
3. **Limit loads** — max 3-4 concurrent \`rememberModelInstance\` calls.
4. **Frustum culling** — break scenes into multiple nodes for automatic culling.
5. **Instancing** — reuse the same \`modelInstance\` for repeated objects.
6. **Profile** — use Systrace or AGI to find real bottlenecks, don't guess.
7. **Post-processing** — disable what you don't need (\`renderQuality = RenderQuality.Performance\`, or \`view.isPostProcessingEnabled = false\`).
`;
