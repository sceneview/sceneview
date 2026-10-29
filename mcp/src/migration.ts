import { LATEST_SCENEVIEW_RELEASE } from "./generated/version.js";

export const MIGRATION_GUIDE = `# SceneView 2.x → 3.0 Migration Guide

SceneView 3.0 is a full rewrite from Android Views to **Jetpack Compose**. Nearly every public API changed. This guide covers every breaking change and how to fix it.

---

## 1. Gradle dependency

| 2.x | Current |
|-----|---------|
| \`io.github.sceneview:sceneview:2.x.x\` | \`io.github.sceneview:sceneview:${LATEST_SCENEVIEW_RELEASE}\` |
| \`io.github.sceneview:arsceneview:2.x.x\` | \`io.github.sceneview:arsceneview:${LATEST_SCENEVIEW_RELEASE}\` |

---

## 2. Root composable names

| 2.x | 3.0 |
|-----|-----|
| \`SceneView(…)\` | \`SceneView(…)\` |
| \`ArSceneView(…)\` | \`ARSceneView(…)\` |

**Before:**
\`\`\`kotlin
SceneView(modifier = Modifier.fillMaxSize())
\`\`\`

**After:**
\`\`\`kotlin
val engine = rememberEngine()
SceneView(modifier = Modifier.fillMaxSize(), engine = engine)
\`\`\`

---

## 3. Engine lifecycle

In 2.x the engine was managed internally. In 3.0 you own it — use \`rememberEngine()\` which ties it to the composition lifecycle.

| 2.x | 3.0 |
|-----|-----|
| Engine implicit | \`val engine = rememberEngine()\` |
| Never destroy manually | Never call \`engine.destroy()\` — \`rememberEngine\` does it |

---

## 4. Model loading

| 2.x | 3.0 |
|-----|-----|
| \`modelLoader.loadModelAsync(path) { … }\` | \`rememberModelInstance(modelLoader, path)\` (returns \`null\` while loading) |
| \`modelLoader.loadModel(path)\` | \`modelLoader.loadModelInstanceAsync(path)\` (imperative) |
| \`ModelRenderable.builder()\` | Removed — use GLB/glTF assets |

**Before:**
\`\`\`kotlin
var modelInstance by remember { mutableStateOf<ModelInstance?>(null) }
LaunchedEffect(Unit) {
    modelInstance = modelLoader.loadModelAsync("models/chair.glb")
}
\`\`\`

**After:**
\`\`\`kotlin
SceneView(engine = engine, modelLoader = modelLoader) {
    rememberModelInstance(modelLoader, "models/chair.glb")?.let { instance ->
        ModelNode(modelInstance = instance, scaleToUnits = 1.0f)
    }
}
\`\`\`

---

## 5. Node hierarchy — imperative → declarative DSL

In 2.x nodes were added imperatively (\`scene.addChild(node)\`). In 3.0 nodes are declared as composables inside \`SceneView { }\` or \`ARSceneView { }\`.

**Before:**
\`\`\`kotlin
val modelNode = ModelNode().apply {
    loadModelGlbAsync(
        glbFileLocation = "models/chair.glb",
        scaleToUnits = 1f,
    )
}
sceneView.addChildNode(modelNode)
\`\`\`

**After:**
\`\`\`kotlin
SceneView(engine = engine, modelLoader = modelLoader) {
    rememberModelInstance(modelLoader, "models/chair.glb")?.let { instance ->
        ModelNode(modelInstance = instance, scaleToUnits = 1f)
    }
}
\`\`\`

---

## 6. Removed nodes and replacements

| 2.x | 3.0 replacement |
|-----|-----------------|
| \`TransformableNode\` | Set \`isEditable = true\` on \`ModelNode\` |
| \`PlacementNode\` | \`HitResultNode\` cursor + \`AnchorNode(anchor = anchor)\` (anchor from \`createAnchorOrNull()\` on a tapped plane hit) |
| \`ViewRenderable\` | \`ViewNode\` with a \`@Composable\` content lambda |
| \`AnchorNode()\` (no-arg) | \`AnchorNode(anchor = anchor)\` (anchor from \`createAnchorOrNull()\` on a tapped plane hit) |

**TransformableNode:**
\`\`\`kotlin
// Before
val node = TransformableNode(transformationSystem).apply {
    setParent(anchorNode)
}

// After
ModelNode(
    modelInstance = instance,
    scaleToUnits = 1f,
    isEditable = true  // enables pinch-to-scale + drag-to-rotate
)
\`\`\`

**ViewRenderable → ViewNode:**
\`\`\`kotlin
// Before
ViewRenderable.builder()
    .setView(context, R.layout.my_layout)
    .build()
    .thenAccept { renderable -> … }

// After
val windowManager = rememberViewNodeManager()
SceneView(viewNodeWindowManager = windowManager) {
    ViewNode(windowManager = windowManager) {
        Card { Text("Hello 3D World!") }
    }
}
\`\`\`

---

## 7. Light configuration

LightNode's \`apply\` is a **named parameter** (not a trailing lambda). This is the most common silent breakage after migrating.

\`\`\`kotlin
// WRONG — trailing lambda is silently ignored
LightNode(engine = engine, type = LightManager.Type.DIRECTIONAL) {
    intensity(100_000f)
}

// CORRECT
LightNode(
    engine = engine,
    type = LightManager.Type.DIRECTIONAL,
    apply = {
        intensity(100_000f)
        castShadows(true)
    }
)
\`\`\`

---

## 8. Environment / IBL loading

| 2.x | 3.0 |
|-----|-----|
| \`environmentLoader.loadEnvironment(path)\` | \`environmentLoader.createHDREnvironment(path)\` |

\`\`\`kotlin
// 3.0
val environmentLoader = rememberEnvironmentLoader(engine)
SceneView(
    environment = rememberEnvironment(environmentLoader) {
        environmentLoader.createHDREnvironment("environments/sky_2k.hdr") ?: createEnvironment(environmentLoader)
    }
) { … }
\`\`\`

---

## 9. AR session configuration

In 3.0 \`sessionConfiguration\` is a lambda parameter on \`ARSceneView\` (not a separate builder).

\`\`\`kotlin
ARSceneView(
    engine = engine,
    modelLoader = modelLoader,
    sessionConfiguration = { session, config ->
        config.depthMode =
            if (session.isDepthModeSupported(Config.DepthMode.AUTOMATIC))
                Config.DepthMode.AUTOMATIC else Config.DepthMode.DISABLED
        config.instantPlacementMode = Config.InstantPlacementMode.LOCAL_Y_UP
        config.lightEstimationMode = Config.LightEstimationMode.ENVIRONMENTAL_HDR
    }
) { … }
\`\`\`

---

## 10. AR anchors (no more worldPosition hacks)

| 2.x pattern | 3.0 |
|-------------|-----|
| \`node.worldPosition = hitResult.hitPose.position\` | \`AnchorNode(anchor = anchor)\`, \`anchor = hitResult.createAnchorOrNull()\` |

Plain nodes whose \`worldPosition\` is set manually will drift when ARCore remaps its coordinate system. \`AnchorNode\` compensates automatically.

---

## 11. Shadows

In 3.0, \`ARSceneView\` has shadows enabled by default via \`createARView()\`. For \`SceneView\` (3D only), shadows are disabled by default — enable with:

\`\`\`kotlin
SceneView(
    view = rememberView(engine).also { it.setShadowingEnabled(true) },
    …
)
\`\`\`

---

## 12. Camera

| 2.x | 3.0 |
|-----|-----|
| \`CameraManipulator\` set on the View | \`cameraManipulator = rememberCameraManipulator()\` on \`SceneView\` |
| Custom camera via \`setCameraNode\` | \`cameraNode = rememberCameraNode(engine) { … }\` on \`SceneView\` |

---

## Checklist

- [ ] Replace \`SceneView(…)\` → \`SceneView(engine = rememberEngine(), …)\`
- [ ] Replace \`ArSceneView(…)\` → \`ARSceneView(engine = rememberEngine(), …)\`
- [ ] Replace \`modelLoader.loadModelAsync\` → \`rememberModelInstance\`
- [ ] Add null-check on every \`rememberModelInstance\` result
- [ ] Replace \`TransformableNode\` → \`isEditable = true\`
- [ ] Replace \`PlacementNode\` → \`AnchorNode\` + \`HitResultNode\`
- [ ] Replace \`ViewRenderable\` → \`ViewNode\` with Compose lambda
- [ ] Fix \`LightNode { … }\` → \`LightNode(type = LightManager.Type.POINT, apply = { … })\` (\`type\` is required)
- [ ] Remove manual \`engine.destroy()\` calls
- [ ] Replace manual \`worldPosition\` in AR → \`AnchorNode\`
- [ ] Replace \`ArFragment\` → \`ARSceneView()\` composable
- [ ] Replace \`onTapArPlane\` → \`rememberOnGestureListener(onSingleTapConfirmed = { event, node -> if (node == null) … })\` + \`latestFrame?.hitTest(event)\`
- [ ] Replace \`setRenderable\` → \`ModelNode(modelInstance = ...)\`
- [ ] Replace \`setParent\` → nest composable nodes inside parent
- [ ] Move all Filament calls to main thread (no Dispatchers.IO)

---

## 13. Material loading

| 2.x | 3.0 |
|-----|-----|
| \`materialLoader.loadMaterial(path)\` | \`materialLoader.createMaterial(path)\` |

---

## 14. Node.setRenderable

| 2.x | 3.0 |
|-----|-----|
| \`node.setRenderable(renderable)\` | \`ModelNode(modelInstance = instance)\` — pass model in constructor |

---

## 15. ArFragment (Android Views)

| 2.x | 3.0 |
|-----|-----|
| \`ArFragment\` in XML layout | \`ARSceneView()\` composable |
| \`arFragment.setOnTapArPlaneListener\` | \`ARSceneView(onGestureListener = rememberOnGestureListener(onSingleTapConfirmed = { event, node -> … }))\` |
| \`arFragment.arSceneView\` | Direct access via composable params |

---

## 16. Node.setParent / addChild (imperative)

| 2.x | 3.0 |
|-----|-----|
| \`node.setParent(parentNode)\` | Nest composable nodes inside parent's content block |
| \`sceneView.scene.addChild(node)\` | Declare nodes inside \`SceneView { }\` content block |

---

## Sceneform 1.x → SceneView 3.0

If migrating from Google Sceneform 1.x (the original \`com.google.ar.sceneform\` package):

| Sceneform 1.x | SceneView 3.0 |
|---|---|
| \`com.google.ar.sceneform.*\` imports | \`io.github.sceneview.*\` imports |
| \`ArFragment\` + XML | \`ARSceneView()\` composable |
| \`ModelRenderable.builder().setSource()\` | \`rememberModelInstance(modelLoader, "file.glb")\` |
| \`ViewRenderable.builder().setView()\` | \`ViewNode { ComposeContent() }\` |
| \`Node().setParent(scene)\` | Declare nodes inside \`SceneView { }\` |
| \`TransformableNode(transformSystem)\` | \`ModelNode(isEditable = true)\` |
| \`node.localPosition = Vector3()\` | \`position = Position(x, y, z)\` on node composable |
| \`ArSceneView\` (View) | \`ARSceneView()\` (Composable) |
| Java callbacks | Kotlin coroutines + Compose state |

**Key differences from Sceneform:**
- No more \`.sfa\`/\`.sfb\` files — use standard \`.glb\` models
- No more XML layouts — everything is Jetpack Compose
- No more \`Renderable\` classes — models are loaded as \`ModelInstance\`
- Threading is critical — all Filament calls must be on the main thread
`;
