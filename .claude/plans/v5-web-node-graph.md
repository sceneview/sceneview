# v5 — `sceneview-web` Node scene-graph architecture (#2024 / #2520)

> **Status:** implementation-ready design. **Not code.** Executable later by an
> Opus/Sonnet-class agent without re-deriving the architecture (per #2517).
> **Scope:** bring `sceneview-web` to `Node`-tree (scene-graph) parity with
> SceneView Android (`io.github.sceneview.node.Node` + the `SceneScope`
> composable DSL). v5 is a deliberate major milestone — **breaking changes are
> allowed and are called out explicitly** (§3, §3.5).
>
> Every claim about *current* behaviour cites `file:line` from the repo at the
> time of writing. Anything not statically verifiable is flagged "not verified".

## Table of contents

- [0. TL;DR — the decision](#0-tldr--the-decision)
- [1. Where we are today (verified)](#1-where-we-are-today-verified)
- [2. Parity matrix — node type × platform](#2-parity-matrix--node-type--platform)
- [3. Target API — the web Node tree](#3-target-api--the-web-node-tree)
- [4. Architecture](#4-architecture)
  - [4.1 Node hierarchy over Filament.js entities](#41-node-hierarchy-over-filamentjs-entities)
  - [4.2 Transform propagation — reuse `sceneview-core`, not a web fork](#42-transform-propagation--reuse-sceneview-core-not-a-web-fork)
  - [4.3 The declarative-layer decision (3 options, one pick)](#43-the-declarative-layer-decision-3-options-one-pick)
- [5. Migration path](#5-migration-path)
- [6. Phasing](#6-phasing)
- [7. Risks](#7-risks)
- [8. Open questions for the maintainer (max 3)](#8-open-questions-for-the-maintainer-max-3)
- [Appendix A — rejected alternatives](#appendix-a--rejected-alternatives)

---

## 0. TL;DR — the decision

1. **Build a retained-mode `Node` tree** (`nodes/Node.kt` + concrete subtypes)
   that **implements the already-existing `SceneNode` interface from
   `sceneview-core` commonMain** (`sceneview-core/src/commonMain/.../rendering/NodeLifecycle.kt:16`),
   backed per-node by a Filament.js `Entity` + `TransformManager` component.
   `sceneview-web` already depends on `sceneview-core` via
   `api(project(":sceneview-core"))` (`sceneview-web/build.gradle.kts:26`), so
   this is wiring, not a new dependency.

2. **Reuse `sceneview-core` for ALL transform math and the graph manager** —
   `SceneNode`, `SceneGraph` (`sceneview-core/.../scene/SceneGraph.kt`),
   `NodeTransform` (`.../rendering/NodeTransform.kt`), the `math/*` types, and
   `SmoothTransform` all compile to `js(IR)` today and are currently *unused*
   (no platform implements `SceneNode` — verified by grep). Do **not** write a
   web-specific transform fork.

3. **For parent/child transform inheritance, delegate to Filament's
   `TransformManager.setParent`** — exactly what Android `Node` does
   (`sceneview/.../node/Node.kt:493`). The web binding is the *only* blocker:
   `external class TransformManager` (`bindings/Filament.kt:279-289`) declares
   `setParent`-less. Phase 1 adds the one missing `external fun setParent(...)`.

4. **Declarative layer = retained-mode imperative tree + a thin builder DSL,
   NOT a Compose-runtime port.** Picked over (a) a Compose/Kotlin-JS runtime and
   (b) a pure-imperative-only surface. Rationale in §4.3. The Android *idiom*
   (declarative node names, `apply` lambdas, additive transforms) is preserved
   so AI-generated code reads the same across platforms, but the *engine* is a
   plain retained tree the JS host mutates — no recomposer on the WASM hot path
   (the #2508 / #2332 render-loop class makes a per-frame recomposition tick a
   real liability, not a hypothetical one).

5. **Migration:** the hand-written vanilla-JS `sceneview.js` viewer is **out of
   scope** (it is *not* the Kotlin/JS bundle — see §1). The Kotlin/JS
   `@JsExport` surface (`SceneViewJS` / `window.sceneview.*`) stays
   **source-compatible** for v5 (every existing method keeps working, delegating
   to nodes internally) and **gains** an additive node API. The internal Kotlin
   `SceneView`/`SceneViewBuilder` DSL **may break** (it is not `@JsExport` and
   has no external consumers — verified).

---

## 1. Where we are today (verified)

There are **two completely separate "web" surfaces** in the repo. Conflating
them is the #1 way this design goes wrong, so pin them first:

| Surface | File | Language | Consumed by | In scope for #2024? |
|---|---|---|---|---|
| **A. Kotlin/JS `sceneview-web` bundle** | `sceneview-web/src/jsMain/...` | Kotlin/JS → WASM Filament.js | `window.sceneview.*` (`@JsExport`); the web-demo `kotlin-bundle.spec.ts` | **YES** |
| **B. Hand-written `sceneview.js`** | `website-static/js/sceneview.js` (2364 lines) + a copy at `samples/web-demo/site/js/sceneview.js` (2190 lines) | Vanilla JS, no Kotlin | 8 showcase pages on sceneview.github.io; the web-demo's `site/index.html` (`<script src="js/sceneview.js">`, index.html:16) | **NO** (different codebase; tracked separately, e.g. #2508) |

This doc is about **Surface A only.** Surface B is a parallel hand-written
viewer with its own `SceneView.create()` / `SceneView.modelViewer()` global
(`samples/web-demo/site/js/sceneview.js:2186-2187`) and its own lifecycle bugs
(#2508). It is **not** generated from the Kotlin source and must not be touched
by the v5 node work.

### 1.1 Surface A — the current public API

**Kotlin DSL (`SceneView` + `SceneViewBuilder`)** — fire-and-forget builder,
*not* `@JsExport`, internal to the module:

- `SceneView.create(canvas, assets, configure, onError, onReady)` —
  `SceneView.kt:285-393`. The `configure: SceneViewBuilder.() -> Unit` block is
  the DSL entry point.
- `SceneViewBuilder` exposes `camera { }`, `light { }`, `model(url) { }`,
  `geometry { }`, `environment(...)`, `cameraControls(...)`, `autoRotate(...)`,
  `autoCenterContent(...)` — `SceneView.kt:1204-1333`.
- The four `*Config` classes (`CameraConfig`, `LightConfig`, `ModelConfig`,
  `GeometryConfig`) under `nodes/` are **builder *inputs***, consumed once in
  `SceneViewBuilder.apply()` (`SceneView.kt:1269-1332`) and never retained as
  addressable handles. `nodes/ModelConfig.kt:16`, `nodes/GeometryConfig.kt:19`,
  `nodes/LightConfig.kt:15`, `nodes/CameraConfig.kt:20`.

**Content is stored flat, in world space, un-addressable after `create()`:**

- Models → a private `models: MutableList<LoadedModel>` (`SceneView.kt:94`),
  where `LoadedModel` is a private render-state class (`SceneView.kt:175-235`).
- Lights → a private `lightEntities: MutableList<Entity>` (`SceneView.kt:96`).
- There is **no `Node` type** — no `parent`, no `childNodes`, no transform
  inheritance. "Centering" is a whole-scene offset applied directly to each
  asset's *root entity* transform via the `TransformManager`
  (`refreshContentCentering`, `SceneView.kt:903-961`), precisely *because* there
  is no parent root node to translate (the doc comment at `SceneView.kt:898-901`
  says so explicitly).

**`@JsExport` surface (`SceneViewJS`, registered as `SceneViewer`)** —
`SceneViewJS.kt:18-192`. Methods: `loadModel`, `setEnvironment`,
`setEnvironmentWithSkybox`, `setCameraOrbit`, `setCameraTarget`, `setAutoRotate`,
`setAutoRotateSpeed`, `setZoomLimits`, `startRendering`, `stopRendering`,
`resize`, `setBackgroundColor`, `fitToModels`, `setAutoCenterContent`,
`dispose`. All camera/viewer-level — **none address a node.** The hand-written
TypeScript mirror is `sceneview-web/sceneview-web.d.ts:43-100`.

**Global registration** — `Main.kt:21-40` hangs `createViewer`,
`createViewerAutoRotate`, `createViewerFull`, `modelViewer`,
`modelViewerAutoRotate`, `haptic`, `version` on `window.sceneview`.

### 1.2 The `setParent` gap (the load-bearing binding hole)

`external class TransformManager` (`bindings/Filament.kt:279-289`) declares:

```
hasComponent, getInstance, setTransform, getTransform, getWorldTransform,
create, destroy, openLocalTransformTransaction, commitLocalTransformTransaction
```

— and **no `setParent` / `getParent`**. Filament.js *has* `setParent` (it is in
the upstream `filament.d.ts` `TransformManager`; not re-verifiable from this
clone — flagged). Android's `Node` uses exactly this call for hierarchy:
`transformManager.setParent(transformInstance, value ?: 0)`
(`sceneview/.../node/Node.kt:493`). **This single missing `external` declaration
is the only engine-level blocker** — once declared, composed transform
inheritance "falls out of Filament's `TransformManager` for free" (issue #2024's
own words).

### 1.3 The XR `*Node` classes are NOT scene-graph nodes (verified)

`xr/XRAnchorNode.kt:45-48` is a per-frame *pose wrapper*: `class XRAnchorNode(val
anchor, onUpdate)` with `onAttached`/`onLost`/`update(frame, refSpace)` — **no
`parent`, no `childNodes`, no `position`/`transform`/`worldTransform`, does not
implement `SceneNode`.** Same shape for the sibling `XR*Node` files. They are
out of scope for the core node tree (they describe tracked anchors, not graph
membership) but **should compose with it in a later phase**: an `XRAnchorNode`'s
pose should be able to drive a real `Node`'s `worldTransform` (§6, Phase 5).

### 1.4 The unused gift in `sceneview-core` (the key finding)

`sceneview-core` commonMain **already ships a portable scene-graph contract that
targets `js(IR)` and is currently implemented by nobody**:

| Asset | File | What it gives v5-web |
|---|---|---|
| `interface SceneNode` | `rendering/NodeLifecycle.kt:16-106` | The exact node contract: `name`, `isVisible`, `isHittable`, local `position/quaternion/rotation/scale/transform`, world `worldPosition/...worldTransform`, `parent`, `childNodes`, `addChildNode`/`removeChildNode`, `lookAt`/`lookTowards`, `onAddedToScene`/`onRemovedFromScene`/`onFrame`/`destroy`. |
| `class SceneGraph` | `scene/SceneGraph.kt:29-176` | A ready graph manager: `addNode(node, parent)`, `removeNode` (recursive), `findNode`/`findAllNodes`, `dispatchFrame(deltaTime)`, `hitTest(ray) → List<HitResult>`. |
| `object NodeTransform` | `rendering/NodeTransform.kt:20+` | local↔world conversion in pure kotlin-math (`getLocalPosition`, `getWorldTransform`, …). |
| `math/*` | `math/Math.kt`, `TransformConversions.kt`, etc. | `Position`, `Rotation`, `Scale`, `Transform`, `Quaternion` interop — the **same types Android `Node` uses**. |
| `animation/SmoothTransform.kt` | `updateSmoothTransform(...)` (`:50`) | Pure-function smooth transform (the Android `Node.smoothTransform` analog). |
| `geometries/*` | `generateCube/Sphere/Cylinder/Plane` | Already consumed today by `GeometryGLBBuilder.kt`. |

**Implication:** the v5-web Node tree is *not* a from-scratch design. It is
"make `sceneview-web` the first real implementor of the `SceneNode` interface
the core already defines," wiring each `SceneNode` member to a Filament.js
entity + `TransformManager` component. This also retroactively justifies the
core abstraction and keeps Android/web on one shared contract.

---

## 2. Parity matrix — node type × platform

Android column = the `SceneScope` composables (`SceneScope.kt`, verified line
refs). "web-today" = what Surface A exposes now. "web-v5" = the target.
"core-reusable" = the commonMain asset the web impl should lean on.

| Node / capability | Android (`SceneScope.kt`) | web-today (Surface A) | web-v5 target | core-reusable |
|---|---|---|---|---|
| **base `Node`** (transform + identity, children) | `Node()` :167 | none (flat) | `Node` impl of `SceneNode` | `SceneNode`, `NodeTransform`, `SceneGraph` |
| `ModelNode` | `ModelNode()` :267 | `model { }` builder (flat `LoadedModel`) :1227 | `ModelNode : Node` | reuses load pipeline `loadModel` :451 |
| `LightNode` | `LightNode()` :374 | `light { }` builder → `lightEntities` :96 | `LightNode : Node` | — (Filament `LightManager`) |
| `CameraNode` | `CameraNode()` :439 | implicit single camera | `CameraNode : Node` | — |
| `GeometryNode` (base) | (via `GeometryNode`/`MeshNode`) | `geometry { }` → `addGeometry` :714 | `GeometryNode : Node` | `geometries/*`, `GeometryGLBBuilder` |
| `CubeNode` | `CubeNode()` :459 | `geometry{ cube() }` | `CubeNode : GeometryNode` | `generateCube` |
| `SphereNode` | `SphereNode()` :514 | `geometry{ sphere() }` | `SphereNode` | `generateSphere` |
| `CylinderNode` | `CylinderNode()` :592 | `geometry{ cylinder() }` | `CylinderNode` | `generateCylinder` |
| `PlaneNode` | `PlaneNode()` :839 | `geometry{ plane() }` | `PlaneNode` | `generatePlane` |
| `ConeNode` | `ConeNode()` :653 | none | defer (post-v5) | `CylinderGeometry` |
| `TorusNode` | `TorusNode()` :712 | none | defer | `TorusGeometry` |
| `CapsuleNode` | `CapsuleNode()` :776 | none | defer | `CapsuleGeometry` |
| `LineNode` | `LineNode()` :1323 | (hand-written `sceneview.js` only) | defer | `LineGeometry` |
| `PathNode` | `PathNode()` :1386 | none | defer | `PathGeometry` |
| `ShapeNode` | `ShapeNode()` :1562 | none | defer | `ShapeGeometry` |
| `MeshNode` | `MeshNode()` :1438 | none | defer | `GeometryData` |
| `ImageNode` | `ImageNode()` :895/:935/:974 | (hand-written only) | defer | — |
| `TextNode` | `TextNode()` :1067 | (hand-written `createText`) | defer | — |
| `VideoNode` | `VideoNode()` :1139/:1194 | (hand-written) | defer | — |
| `ViewNode` (Compose-plane) | `ViewNode()` :1260 | n/a (no Compose on web) | **not applicable** | — |
| `BillboardNode` | `BillboardNode()` :1020 | (hand-written) | defer | — |
| `ReflectionProbeNode` | `ReflectionProbeNode()` :1506 | none | defer | — |
| `PhysicsNode` | `PhysicsNode()` :1646/:1685 | none | defer | `physics/*` (core) |
| `FogNode` / `DynamicSkyNode` | (own composables) | none | defer | — |
| **parent/child + composed transform** | `Node.parent`/`childNodes` :520/:531 → `TransformManager.setParent` :493 | **none** | **YES** (the headline) | `SceneNode.addChildNode` + Filament `setParent` |
| **world↔local transform** | `Node.worldTransform` :449 | n/a | YES | `NodeTransform` |
| **`isVisible` cascade** | `Node.isVisible` :133 | n/a | YES (Phase 4) | `SceneNode.isVisible` |
| **smooth transform** | `Node.smoothTransform` :157 | n/a | defer (Phase 5) | `SmoothTransform` |
| **collision / hitTest** | `Node.collider`/`collisionShape` :563/:581 | n/a | defer (Phase 5) | `SceneGraph.hitTest`, `collision/*` |
| **gestures** | `NodeGestureDelegate` | n/a | defer (post-v5) | `gesture/*` |
| **`addNode`/`removeNode`** | scope attach/detach :135/:142 | n/a | `SceneView.addNode`/`removeNode` | `SceneGraph` |
| **`@JsExport` node handle** | (Compose, no JS) | n/a | `NodeHandle` JS class | — |

**Headline gap:** every "none / n/a" in the parent/child + transform-inheritance
rows. v5 closes exactly those; the per-geometry-subtype rows marked "defer" are
additive and can land incrementally after the base tree exists.

---

## 3. Target API — the web Node tree

Two layers, matching Android's two-layer split (imperative `Node` classes +
declarative composable wrappers). On web the "declarative" layer is a builder
DSL, not Compose (§4.3), but the *names and shapes* mirror Android so AI-emitted
code is portable.

### 3.1 Layer 1 — imperative Kotlin `Node` classes (impl `SceneNode`)

```kotlin
// nodes/Node.kt  (NEW) — the base, implements the core contract
open class Node(
    val engine: Engine,
    val entity: Entity = EntityManager.get().create(),
) : SceneNode {                       // <-- sceneview-core interface
    private val transformManager = engine.getTransformManager()
    init { if (!transformManager.hasComponent(entity)) transformManager.create(entity) }

    override var name: String? = null
    override var isVisible: Boolean = true        // Phase 4 wires the cascade
    override var isHittable: Boolean = true

    // local transform — read/write through TransformManager (mat4 column-major)
    override var position: Position   // composes into `transform`
    override var rotation: Rotation
    override var scale: Scale
    override var transform: Transform  // get/setTransform(instance, …)
    override var quaternion: Quaternion

    // world transform — derived from TransformManager.getWorldTransform
    override var worldTransform: Transform  // get via getWorldTransform; set via NodeTransform
    override var worldPosition: Position
    // …worldQuaternion / worldRotation / worldScale via NodeTransform

    // hierarchy — the v5 headline
    override var parent: SceneNode? = null ; private set
    override var childNodes: Set<SceneNode> = emptySet() ; private set
    override fun addChildNode(node: SceneNode) { /* setParent on the Filament instance */ }
    override fun removeChildNode(node: SceneNode) { /* setParent(child, 0) */ }

    override fun lookAt(target, up) { /* NodeTransform / kotlin-math */ }
    override fun onFrame(dt: Float) {}
    override fun destroy() { transformManager.destroy(entity); engine.destroyEntity(entity) }
}

class ModelNode(engine, modelInstance, autoAnimate=true, scaleToUnits=null, …) : Node(engine, …)
class GeometryNode(engine, geometry: GeometryData, materialInstance) : Node(engine, …)
class CubeNode(engine, size, …)     : GeometryNode(…)   // generateCube from core
class SphereNode(engine, radius, …) : GeometryNode(…)
class CylinderNode(...)             : GeometryNode(…)
class PlaneNode(...)                : GeometryNode(…)
class LightNode(engine, type, intensity, …) : Node(engine, …)
class CameraNode(engine, …)         : Node(engine, …)
```

`SceneView` keeps a `SceneGraph` (from core) as its node container and forwards
`onFrame` from `renderLoop` (`SceneView.kt:1121`) into
`sceneGraph.dispatchFrame(deltaSeconds)`. New public methods on `SceneView`:
`fun addNode(node: Node)`, `fun removeNode(node: Node)` — mirroring Android's
scope `attach`/`detach` (`SceneScope.kt:135/:142`).

### 3.2 Layer 2 — the builder DSL (additive, mirrors Android names)

`SceneViewBuilder` gains an `add(node: Node)` and node-returning forms so a
caller can keep a handle. The existing `model { } / geometry { } / light { }`
DSL **stays and starts returning / internally creating real nodes** (delegating,
non-breaking for the Kotlin DSL shape):

```kotlin
SceneView.create(canvas) {
    val root = Node()                       // explicit grouping pivot
    add(root)
    root.add {                              // NodeScope-like child block
        model("models/helmet.glb")          // child of root
        light { directional(); intensity(100_000.0) }
    }
}
```

### 3.3 Layer 3 — the `@JsExport` JS-consumer shape

The headline new JS type is a **`NodeHandle`** so plain-JS callers can address a
node after creation (the thing Surface A cannot do today). Names chosen to read
the same as the Kotlin/Android API:

```typescript
// additive to sceneview-web.d.ts
export interface NodeHandle {
  setPosition(x: number, y: number, z: number): void;
  setRotation(x: number, y: number, z: number): void;   // Euler degrees
  setScale(x: number, y: number, z: number): void;
  setScaleUniform(s: number): void;
  setVisible(v: boolean): void;
  addChild(child: NodeHandle): void;
  removeChild(child: NodeHandle): void;
  getWorldPosition(): [number, number, number];
  destroy(): void;
}

export interface SceneViewer {
  // … all existing methods unchanged …
  addNode(): NodeHandle;                                  // empty pivot node
  addModelNode(url: string): Promise<NodeHandle>;         // resolves when loaded
  addCubeNode(size: number): NodeHandle;
  addSphereNode(radius: number): NodeHandle;
  addLightNode(type: "directional"|"point"|"spot"): NodeHandle;
  removeNode(node: NodeHandle): void;
}
```

`NodeHandle` is a `@JsExport`/`@JsName`'d wrapper around a Kotlin `Node` (same
pattern `SceneViewJS` already uses to wrap `SceneView`, `SceneViewJS.kt:20-26`),
so the unmangled JS names stay stable across Kotlin/JS minifier passes.

### 3.4 What is intentionally NOT in v5 (deferred)

Gestures, collision/hit-testing on the JS surface, smooth transform, physics
nodes, and the long tail of geometry subtypes (`Cone/Torus/Capsule/Line/Path/
Shape/Mesh/Image/Text/Video/Billboard/ReflectionProbe`). Each is additive over
the base tree; none changes the architecture. `ViewNode` is **not applicable**
(no Compose UI on web).

### 3.5 What breaks in v5 (major bump — explicit)

- **No `@JsExport` / `window.sceneview.*` method is removed or renamed.** The
  whole `SceneViewJS` surface (`SceneViewJS.kt:18-192`) and the `Main.kt`
  factories stay source-compatible. JS consumers upgrade with zero changes and
  *opt in* to nodes.
- **MAY break — internal Kotlin DSL only:** `SceneViewBuilder.apply()`'s
  internal mechanics change (it now constructs `Node`s instead of stuffing
  `*Config` into flat lists). The `*Config` classes (`nodes/*Config.kt`) become
  *thin constructor-arg holders* or are folded into the node constructors. These
  types are **not `@JsExport`** and have **no external consumers** (verified:
  they are referenced only inside `SceneView.kt`/`SceneViewBuilder`), so this is
  a source-internal break, invisible to JS users.
- **Behavioural:** the whole-scene `refreshContentCentering` root-entity offset
  (`SceneView.kt:903-961`) is **replaced** by translating a single real root
  `Node` (the iOS approach the comment at `SceneView.kt:898-901` says was the
  ideal but impossible without a root node). Visual result identical; the
  per-asset offset bookkeeping (`baseTransform`, `transformScratch`,
  `applyTranslatedTransform`) collapses into one node transform. This is a v5
  simplification, gated behind the same `autoCenterContent` flag.

---

## 4. Architecture

### 4.1 Node hierarchy over Filament.js entities

Each `Node` owns one Filament `Entity` with a `TransformManager` component
(`bindings/Filament.kt:279`). The graph is **the `TransformManager`'s own parent
tree** — there is no second source of truth:

- `addChildNode(child)` → `transformManager.setParent(child.instance,
  this.instance)` (Phase 1 binding). Filament then composes
  `child.worldTransform = parent.worldTransform * child.localTransform`
  automatically — read back via `getWorldTransform` (already bound,
  `Filament.kt:284`). This is byte-for-byte what Android `Node` does
  (`Node.kt:493`), so behaviour matches the reference platform by construction.
- The Kotlin-side `SceneGraph` (core) holds the *membership* mirror (root list,
  `allNodes`, recursive remove, frame dispatch, hit-test) so iteration,
  `findNode`, and `onFrame` fan-out don't have to walk the WASM heap every frame
  — `SceneGraph.dispatchFrame` (`scene/SceneGraph.kt:122-126`) is a pure-Kotlin
  recursive walk over `childNodes`.
- **Entities still get added to the Filament `Scene`** for rendering
  (`scene.addEntities`, as `loadModel` does today, `SceneView.kt:504`). The node
  tree governs *transform*; scene membership governs *visibility/rendering*.
  `isVisible=false` (Phase 4) removes the entity from the scene (or sets a
  zero-scale / culling layer — decided in Phase 4, see Open Question Q2).

### 4.2 Transform propagation — reuse `sceneview-core`, not a web fork

**Decision: reuse, do not fork.** The web `Node` delegates:

- **Composition (parent→child):** Filament's `TransformManager.setParent` +
  `getWorldTransform`. Zero Kotlin math on the hot path.
- **local↔world helper math** (e.g. setting a `worldPosition` on a child, or
  `lookAt`): `NodeTransform` (`rendering/NodeTransform.kt`) — pure kotlin-math,
  already `js(IR)`-compiled.
- **types:** `Position/Rotation/Scale/Transform/Quaternion` from
  `sceneview-core` `math/*` — *the same types Android uses*, so the parity
  matrix is type-exact, not just shape-similar.

This means the web `Node` and the Android `Node` share the *same transform
contract* (`SceneNode`) and the *same math library*, differing only in the
native backend call (`TransformManager` JNI on Android vs the embind binding on
web). That is the strongest possible parity guarantee — drift is caught at
compile time against a shared interface.

**Why not a web-specific transform path?** The only thing web needs that core
doesn't already give is the embind marshalling of mat4 ↔ JS `number[]` — and
that lives in the `external` binding + the existing `readMat4`/scratch helpers
(`SceneView.kt:971-1004`), not in the math. There is nothing to fork.

### 4.3 The declarative-layer decision (3 options, one pick)

The question #2520 poses: *"Compose-like recomposition in Kotlin/JS, vs
retained-mode diff, vs imperative tree the JS host mutates."* Analysis:

#### Option A — full Compose-runtime port (recomposer in Kotlin/JS)

Run a real Compose runtime in the browser so the web `Scene { }` block is a true
`@Composable` mirroring `SceneScope` (`SceneScope.kt`).

- **Pro:** maximal source parity with Android; one mental model.
- **Con (decisive):** Compose runtime on Kotlin/JS is heavyweight and immature
  for non-UI trees; it adds a recomposer tick and snapshot machinery onto a
  render loop that the project has spent multiple issues making *cheaper*
  (#2332 on-demand render gate `SceneView.kt:59`, #2274 rAF alloc, #2508 the
  never-paused-loop class). A per-frame recomposition pass is exactly the kind
  of always-on cost #2508 warns about. **Bundle size** balloons (WASM + Compose
  runtime). **No JS consumer** can drive a Kotlin recomposer from plain JS — the
  `@JsExport` audience (the whole point of `SceneViewJS`) is left out. **Reject.**

#### Option B — retained-mode imperative tree the JS host mutates (PICK)

A plain retained `Node` tree (`SceneNode` impls) that the host — Kotlin DSL *or*
plain JS via `NodeHandle` — mutates imperatively. No recomposer; mutations are
direct `setTransform`/`setParent` calls, gated by the existing on-demand render
gate (`requestRender`, `SceneView.kt:86`).

- **Pro:** zero new per-frame cost (the render loop stays exactly as lean as
  #2332 made it; a static tree costs one near-empty rAF tick). Plain-JS callers
  get first-class node access via `NodeHandle` (§3.3). Reuses `SceneGraph`
  (`scene/SceneGraph.kt`) verbatim. Smallest bundle delta. **Matches how
  Filament.js itself is meant to be driven.**
- **Pro (parity):** the Android *idiom* is preserved at the DSL layer
  (`model { }`, `Node { … }`, `apply` lambdas, additive transforms) so an AI
  reading the docs emits structurally-identical code; only the execution model
  underneath differs (retained vs recomposed), which is invisible to the
  generated code.
- **Con:** no automatic state-driven diffing — a caller that wants
  "re-render the tree when my data changes" writes the mutation explicitly. For
  a 3D viewer (vs a UI framework) this is the *normal* expectation and matches
  the hand-written `sceneview.js` mental model the web audience already has.

#### Option C — pure imperative, no builder DSL at all

Expose only `addModelNode()/addNode()` etc., drop the `model { }` DSL.

- **Con:** breaks the existing Kotlin DSL (`SceneViewBuilder`) for no benefit
  and diverges the *authoring* idiom from Android, hurting AI portability — the
  explicit anti-goal of an AI-first SDK. **Reject.**

**Decision: Option B.** Retained-mode imperative `Node` tree + a thin builder
DSL whose surface mirrors Android's composable names. This keeps the AI-facing
idiom aligned with Android (so generated code ports), gives plain-JS consumers
real node handles, and adds **nothing** to the carefully-tuned render loop. The
declarative *feel* is a DSL veneer; the engine is retained.

> Future door left open: if Compose-for-web matures and the project ever wants a
> true `@Composable Scene { }` on web, Option B's retained tree is exactly the
> "applier" target a Compose runtime would drive — so B is a strict prerequisite
> for A, never a dead end.

---

## 5. Migration path

| Audience | What they use today | v5 impact | Action required |
|---|---|---|---|
| **Plain-JS via `window.sceneview.*`** (`Main.kt`, `SceneViewJS`) | `createViewer`, `modelViewer`, `sv.loadModel(...)`, camera setters | **Source-compatible.** Every method keeps working (delegates to a node internally). New `addNode`/`addModelNode`/… are additive. | None to keep working; opt in to nodes when ready. |
| **TypeScript consumers** (`sceneview-web.d.ts`) | the `SceneViewer` interface | Additive: `NodeHandle` + node methods appended; nothing removed. | Update `.d.ts` (kept hand-written, see its header note `sceneview-web.d.ts:1-11`). |
| **The web-demo** (`samples/web-demo/site/index.html`) | **the hand-written `sceneview.js`** (`<script src="js/sceneview.js">`, index.html:16) — *NOT* the Kotlin bundle | **No impact** — it's Surface B. The `kotlin-bundle.spec.ts` smoke test (Surface A) keeps asserting `window.sceneview.createViewer` resolves (`kotlin-bundle.spec.ts:60`). | Add one Playwright spec exercising `addNode` on the Kotlin bundle. |
| **The showcase website `sceneview.js`** (`website-static/js/...`) | Surface B, hand-written | **Out of scope** for #2024. (Its own lifecycle issues — #2508 — are tracked separately.) | None here. A *separate* future effort could regenerate it from Kotlin, but that is not v5-node work. |
| **Internal Kotlin DSL** (`SceneView`/`SceneViewBuilder`/`*Config`) | not exported, module-internal | **May break** (mechanics rewritten to build nodes). | Internal refactor only; no external contract. |
| **Docs** (`llms.txt` Web section, `docs/docs/quickstart-web.md`) | the #895 "divergence" note | Replace the divergence note with the node API once it lands (acceptance criterion of #2024). | Doc PR in the final phase. |

**Net:** v5 is a major bump, but for *external* web consumers it is effectively
**non-breaking and additive** — the break is confined to internal Kotlin that no
one outside the module imports.

---

## 6. Phasing

Each phase compiles (`:sceneview-web:compileKotlinJs` green) and adds value
independently — same incremental contract #2024 lays out, refined with the core
reuse and the JS handle.

| Phase | Scope | Compiles & ships? | Effort |
|---|---|---|---|
| **P1 — bind `setParent`** | Add `external fun setParent(instance, parent)` (and `getParent`) to `TransformManager` (`bindings/Filament.kt:279-289`). No behaviour change yet. Unit-test it via a 2-entity parent/child world-transform composition. | Yes (additive binding) | **S** |
| **P2 — base `Node : SceneNode`** | `nodes/Node.kt` implementing the core `SceneNode` interface (`NodeLifecycle.kt:16`): entity + `TransformManager` component; local/world transform; `name`/`isVisible`/`isHittable`; `parent`/`childNodes`/`addChildNode`/`removeChildNode` wired to `setParent`. `jsTest` covers parent→child composed transforms (the #2024 acceptance test). | Yes | **M** |
| **P3 — concrete subtypes + `SceneView.addNode`** | `ModelNode`, `GeometryNode`+`Cube/Sphere/Cylinder/Plane`, `LightNode`, `CameraNode` — each wrapping the asset/entity creation `loadModel`/`addGeometry`/`addLight` does today (`SceneView.kt:451/714/649`). `SceneView` holds a `SceneGraph`; `addNode`/`removeNode`; `renderLoop` forwards `onFrame` to `sceneGraph.dispatchFrame`. Existing `model { }`/`geometry { }`/`light { }` DSL delegates to nodes (non-breaking Kotlin shape). | Yes | **L** |
| **P4 — `@JsExport` `NodeHandle` + root-node centering** | `NodeHandle` JS class (§3.3) + `addNode`/`addModelNode`/… on `SceneViewJS`; `.d.ts` update. Replace whole-scene `refreshContentCentering` (`SceneView.kt:903`) with a single real root `Node` translation; wire the `isVisible` cascade. New Playwright spec on the Kotlin bundle. | Yes | **M** |
| **P5 — compose with XR + smooth/collision (additive)** | Let `XRAnchorNode`'s pose drive a real `Node.worldTransform` (so AR-placed content is a graph node, `xr/XRAnchorNode.kt:45`); optional `SmoothTransform` (`animation/SmoothTransform.kt:50`) and `SceneGraph.hitTest` exposure. Long-tail geometry subtypes land here or later, each additive. | Yes | **M** (open-ended) |
| **P6 — docs** | Replace the #895 `llms.txt` Web divergence note with the node API; update `docs/docs/quickstart-web.md`; cross-platform-check parity. | Yes (doc) | **S** |

Critical path to the #2024 acceptance criteria is **P1 → P2 → P3** (base `Node`
with parent/child + composed transforms, existing DSL still works, `jsTest`
covers composition). P4 delivers the JS-consumer payoff; P5/P6 are the tail.

---

## 7. Risks

| Risk | Likelihood | Mitigation |
|---|---|---|
| **`setParent` not actually exposed by the pinned Filament.js (1.52.3, `build.gradle.kts:29`)** | Low (it's standard `TransformManager`) — **not statically verified from this clone** | P1 unit-test asserts a 2-entity composition *before* any node code depends on it. If absent, fall back to manual `worldTransform = parent.world * local` composition in `onFrame` using `NodeTransform` (core already has the math) — slower but functional. |
| **WASM memory / lifecycle (the #2508 / #2460 class)** | Medium | Every `Node.destroy()` must free its entity + `TransformManager` component (mirror the light-leak fix `SceneView.kt:1074-1078` and the camera-entity fix `:1093-1098`). `SceneGraph.removeNode` is recursive (`SceneGraph.kt:68-86`) — wire `onRemovedFromScene → destroy` so a subtree teardown frees every entity exactly once. Use-after-free guards like the existing `superseded` flag (`SceneView.kt:234`) carry over to async node loads. |
| **Render-loop regression** | Medium | Option B adds **zero** per-frame cost by design. `dispatchFrame` only runs when nodes have `onFrame` work; the on-demand gate (`RenderGate`, `SceneView.kt:59`) is untouched. Guard with the existing render-gate tests + a "static node tree → idle gate latches" assertion. |
| **Bundle size growth** | Low | No Compose runtime (Option B). New code is plain Kotlin/JS over existing core types already in the dependency graph (`build.gradle.kts:26`). Measure `productionExecutableCompileSync` output delta in P3/P4. |
| **Perf of mat4 ↔ JS `number[]` marshalling per node per frame** | Low–Medium | The auto-center pass already solved this (`transformScratch` reuse, local-transform transactions, `SceneView.kt:172/932`). Reuse the same scratch-buffer + transaction pattern for batch node updates; static nodes never marshal (transform set once). |
| **Two-source-of-truth drift (Kotlin `SceneGraph` membership vs Filament parent tree)** | Medium | Make Filament's `setParent` the *only* hierarchy mutation path; `SceneGraph.addNode/removeNode` calls it. Never set parent on one side without the other — enforce by having `Node.addChildNode` do both atomically. |
| **AI-first docs go stale** (`llms.txt` still shows the divergence note after nodes land) | Medium | P6 is a hard acceptance criterion of #2024; `check-doc-drift.sh` flags it; the weekly `doc-audit.yml` catches residual drift. |

---

## 8. Open questions for the maintainer (max 3)

1. **`NodeHandle` JS ergonomics — flat setters vs a transform object?**
   §3.3 proposes flat `setPosition(x,y,z)` to match the existing camera setters
   (`SceneViewJS.setCameraTarget`, `SceneViewJS.kt:82`). Should the JS surface
   instead expose a richer `node.transform = {position, rotation, scale}` object
   (closer to the Kotlin `Transform`), or stay flat for minimal mangling
   surface? Flat is lower-risk; richer is more future-proof. **Recommendation:
   flat for v5, richer additively later.**

2. **`isVisible=false` implementation — scene-remove vs zero-scale vs culling
   layer?** Android cascades visibility through the node tree
   (`Node.isVisible`, `Node.kt:133`). On web the cheapest correct option needs a
   call: remove the entity from the Filament `Scene` (clean, but loses it from
   `getRenderableCount`), set a culling layer, or hide via the renderable
   manager. This is a Phase 4 detail but the choice affects the `SceneNode`
   contract semantics — confirm the desired behaviour (does `isVisible=false`
   keep the node in `SceneGraph.allNodes` for hit-testing? Android says yes).

3. **Should the long-tail geometry subtypes
   (`Cone/Torus/Capsule/Line/Path/Shape/Mesh`) be in-scope for v5.0 or a v5.x
   point release?** The core generators all exist (`geometries/*`), so each is a
   small additive `GeometryNode` subclass. Pulling them all into v5.0 widens the
   surface (and the parity-audit obligation) substantially. **Recommendation:
   ship `Cube/Sphere/Cylinder/Plane` (already in the builder today) in v5.0;
   defer the rest to v5.x as demand surfaces** — but confirm, since "full parity"
   could be read as all-subtypes.

---

## Appendix A — rejected alternatives

- **A web-specific `WebNode` base that does NOT implement core `SceneNode`.**
  Rejected: throws away the one cross-platform contract the core already defines
  (`NodeLifecycle.kt:16`) and guarantees Android/web transform drift. The whole
  point of the core module is shared logic; a parallel web node type defeats it.
- **A web-specific transform/math fork.** Rejected (§4.2): core `NodeTransform`
  + `math/*` already compile to `js(IR)` and are the same types Android uses;
  there is nothing web-specific to fork beyond the embind mat4 marshalling,
  which lives in the binding, not the math.
- **Full Compose-runtime port (Option A, §4.3).** Rejected: per-frame
  recomposer cost on a render loop the project deliberately made on-demand
  (#2332/#2508), large bundle, and excludes the plain-JS `@JsExport` audience.
- **Regenerating the hand-written `website-static/js/sceneview.js` from
  Kotlin/JS as part of v5.** Rejected as out of scope: it is Surface B, a
  separate hand-written viewer with its own consumers and bugs (#2508); folding
  it into the node work conflates two codebases and balloons the PR. A future
  consolidation could revisit it, but #2024 is Surface A only.
