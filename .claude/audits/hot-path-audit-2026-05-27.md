# Hot-path audit — 2026-05-27

**Trigger:** [#2187](https://github.com/sceneview/sceneview/issues/2187) — `Node.quaternion` setter decomposed Filament 4×4 matrix every frame at 60–120 Hz, causing scale drift + mesh skew. Fixed in PR #2217 (commit 3b65099) by caching `_position/_quaternion/_scale` backing fields.

**Goal:** find every other place that runs on a per-frame path (60–120 Hz) and pays a hidden cost — allocation, JNI round-trip, matrix decomposition, redundant recomputation, boxing, closure capture. Whether the cost is "drift" (like #2187) or "GC pause" or "JNI thunks" or "wasted CPU", we want all of them.

**Scope:** 5 parallel surface audits — `sceneview/`, `arsceneview/`, `sceneview-core/`, `SceneViewSwift/`, `sceneview-web/`. Each agent reports against tiers 1–6.

---

## Pre-audit findings (Claude orchestrator — Node.kt + Math.kt quick scan)

These are confirmed by reading [Node.kt:180-378](sceneview/src/main/java/io/github/sceneview/node/Node.kt#L180-L378) and [Math.kt:100-220](sceneview-core/src/commonMain/kotlin/io/github/sceneview/math/Math.kt#L100-L220). Agent reports will likely re-list some of these — that's fine, dedupe at synthesis.

### N1. `worldPosition` / `worldQuaternion` / `worldScale` / `worldRotation` getters re-decompose every read — IMPACT: HIGH

**Location:** [Node.kt:234-235](sceneview/src/main/java/io/github/sceneview/node/Node.kt#L234-L235), [Node.kt:261-262](sceneview/src/main/java/io/github/sceneview/node/Node.kt#L261-L262), [Node.kt:294-295](sceneview/src/main/java/io/github/sceneview/node/Node.kt#L294-L295), [Node.kt:322-323](sceneview/src/main/java/io/github/sceneview/node/Node.kt#L322-L323)

**Pattern:** Tier 1 (matrix decomposition) + Tier 2 (JNI)

**What's wrong:** PR #2217 added `_position/_quaternion/_scale` caches for the LOCAL transform — but the WORLD variants still call `transformManager.getWorldTransform(transformInstance)` (1 JNI) then `.position`/`.quaternion`/`.scale`/`.rotation` (each decomposes the matrix). Any code that reads `node.worldPosition` per frame for N nodes pays N × (JNI + decomposition) every frame. Same #2187 family.

**Fix sketch:** cache `_worldTransform: Transform?` with a dirty flag. Invalidate on local transform change, parent change, or parent's world transform change. Recursive invalidation down the tree on parent move.

### N2. `rotation` getter allocates Float3 (Rotation) every read — IMPACT: MED

**Location:** [Node.kt:280-281](sceneview/src/main/java/io/github/sceneview/node/Node.kt#L280-L281)

**Pattern:** Tier 3 (allocation)

**What's wrong:** `quaternion.toEulerAngles()` returns a fresh `Rotation` (typealiased `Float3`) every call. A user iterating animations via `node.rotation.y += 1f` per frame allocates per access. Cheap individually, but x × frames × nodes adds up.

**Fix sketch:** Two options — (a) discourage `rotation` for hot paths in docs, (b) cache `_rotation` and invalidate when quaternion changes.

### N3. `parentInstance` / `parentEntity` enchaîne 2–4 JNI par accès — IMPACT: MED

**Location:** [Node.kt:364-378](sceneview/src/main/java/io/github/sceneview/node/Node.kt#L364-L378)

**Pattern:** Tier 2 (JNI redundancy)

**What's wrong:** `parentInstance` getter calls `parentEntity` (1 JNI to TransformManager.getParentOrNull) then `transformManager.getInstance(it)` (2nd JNI). Setter does `if (parentInstance != value)` — that's 2 JNI just for equality. Each parent-change therefore: 2 JNI for the equality check + 1 JNI for `setParent`. Same for `parentEntity` (check + set = 2 JNI). If users restructure trees during animations, this triples the cost.

**Fix sketch:** cache `_parent: Entity?` (or `_parentInstance: EntityInstance?`) on the Node — TransformManager `setParent` is the only thing that mutates it, so it's safe to cache. Invalidate when explicitly reparenting.

### N4. `transform` getter still JNI-hits on every read — IMPACT: MED

**Location:** [Node.kt:338-339](sceneview/src/main/java/io/github/sceneview/node/Node.kt#L338-L339)

**Pattern:** Tier 2 (JNI)

**What's wrong:** PR #2217 caches position/quaternion/scale individually, but `node.transform` still calls `transformManager.getTransform(transformInstance)`. Any code that does `node.transform` per frame (e.g., to write to a child or to a render listener) pays JNI per read. Could compose from the 3 caches.

**Fix sketch:** when `_position/_quaternion/_scale` are pristine (no external mutation), the getter can compose `Transform(position, quaternion, scale)` from caches — at the cost of one allocation. Allocation vs JNI is a trade — measure. Alternatively, cache `_transform: Mat4?` with a "transform cache valid" flag.

### M1. `slerp(Transform, Transform, Double, Float)` decomposes 6× per call — IMPACT: HIGH

**Location:** [Math.kt:182-198](sceneview-core/src/commonMain/kotlin/io/github/sceneview/math/Math.kt#L182-L198)

**Pattern:** Tier 1 (decomposition round-trip)

**What's wrong:** Reads `start.position`, `start.quaternion`, `start.scale`, `end.position`, `end.quaternion`, `end.scale` — that's 6 matrix decompositions per call. Called from `SmoothTransform`-style animations every frame for every animated node. On a 50-node smooth-follow scene at 120 Hz: 36 000 matrix decompositions per second.

**Fix sketch:** caller should pass already-decomposed TRS, not Mat4. Add overload `slerp(startPos, startRot, startScl, endPos, endRot, endScl, dt, speed)` and have callers supply pristine TRS. Same #2187 fix shape — caches at the call site.

### M2. `Mat4.toColumnsFloatArray()` allocates 16-float array on every call — IMPACT: HIGH (if per-frame)

**Location:** [Math.kt:120-125](sceneview-core/src/commonMain/kotlin/io/github/sceneview/math/Math.kt#L120-L125)

**Pattern:** Tier 2 (allocation)

**What's wrong:** Returns a fresh `FloatArray(16)` every call. Used by uniform/UBO upload paths and `MaterialInstance.setParameter("matrix", floatArray, 0, 16)` style calls. If any per-frame path calls this for a uniform update, that's 64 bytes/frame/uniform — small but adds up.

**Fix sketch:** add `Mat4.copyToFloatArray(out: FloatArray, offset: Int = 0)` for hot paths. Keep the allocating variant for convenience. Audit callers and migrate the hot ones.

### M3. `Mat4.toColumnsDoubleArray()` allocates 3 arrays for 1 conversion — IMPACT: LOW

**Location:** [Math.kt:108-109](sceneview-core/src/commonMain/kotlin/io/github/sceneview/math/Math.kt#L108-L109)

**Pattern:** Tier 5 (boxing/iterator)

**What's wrong:** `toColumnsFloatArray().map { it.toDouble() }.toDoubleArray()` — allocates FloatArray (16), then `List<Double>` from map (16 boxes), then DoubleArray (16). 3 allocations.

**Fix sketch:** direct fill — `val out = DoubleArray(16); out[0] = x.x.toDouble(); ...`. Probably not on a hot path but trivial to fix.

### M4. `FloatArray.toLinearSpace()` allocates intermediate List — IMPACT: LOW

**Location:** [Math.kt:203](sceneview-core/src/commonMain/kotlin/io/github/sceneview/math/Math.kt#L203)

**Pattern:** Tier 5 (boxing)

**What's wrong:** `.map { pow(it, 2.2f) }.toFloatArray()` — boxed List<Float> intermediate.

**Fix sketch:** `FloatArray(this.size) { i -> pow(this[i], 2.2f) }`.

---

## Agent reports — to be filled in

### A1. sceneview/ Android 3D core — ✅ DONE (17 findings : 6 HIGH, 4 MED, 7 LOW)

**Top-3 highest-leverage fixes** (agent's own ranking) :
1. **SV1** — cache `transformInstance` lazily once per Node (its value never changes)
2. **SV4** — cache world-space TRS (natural extension of #2187, completes the fix)
3. **SV3** — single-read fix in `NodeAnimationDelegate.onFrame` — the most localized 30-second fix in the entire audit

| ID | Title | Tier | Impact | File |
|----|-------|------|--------|------|
| SV1 | `transformInstance` JNI on every property access | 2 | HIGH | `sceneview/.../node/Node.kt:559` |
| SV2 | `TransformManager.getTransform/setTransform` allocate FloatArray+Mat4 per call | 3 | HIGH | `sceneview/.../managers/TransformManager.kt:21-56` |
| SV3 | `NodeAnimationDelegate.onFrame` reads `node.transform` 3× per frame | 2+3 | HIGH | `sceneview/.../node/NodeAnimationDelegate.kt:50-71` |
| SV4 | World-space getters decompose every read (no `_world*` cache — extension de #2187) | 1 | HIGH | `sceneview/.../node/Node.kt:234-326` |
| SV5 | `Manipulator.transform` allocates 4 arrays + Mat4 per frame | 3 | HIGH | `sceneview/.../gesture/CameraManipulator.kt:27-32` |
| SV6 | `ModelNode.sanitizeEmptyBoundingBoxes` runs every frame even after AABBs are valid | 2+3 | HIGH | `sceneview/.../node/ModelNode.kt:476-502` |
| SV7 | `ModelNode.applyAnimations` allocates Map.Entry per playing anim per frame | 5 | MED | `sceneview/.../node/ModelNode.kt:504-527` |
| SV8 | `LightComponent.color` getter allocates FloatArray(3) per read | 3 | MED | `sceneview/.../components/LightComponent.kt:98-102` |
| SV9 | `NodeGestureDelegate` allocates Float2/Float3 per gesture event | 3 | MED | `sceneview/.../node/NodeGestureDelegate.kt:117,128,200-203` |
| SV10 | `CameraGestureDetector.TouchPair` allocates 4 Float2 per MotionEvent | 3 | MED | `sceneview/.../gesture/CameraGestureDetector.kt:169-202` |
| SV11 | `forEach` on `List<Node>` / `Set<Node>` allocates iterator per frame | 5 | LOW | `sceneview/.../Scene.kt:557` + `Node.kt:863,889,980` |
| SV12 | `Node.childNodes` set mutation rebuilds whole Set | 3 | LOW | `sceneview/.../node/Node.kt:406-426,800-804` |
| SV13 | `Float2` alloc in `TouchPair.midpoint`/`separation` | 3 | LOW | `sceneview/.../gesture/CameraGestureDetector.kt:183-184` |
| SV14 | `MaterialInstance.setParameter(Mat3/Mat4)` allocates fresh FloatArray | 3 | LOW | `sceneview/.../material/MaterialInstance.kt:29-33` |
| SV15 | `Mat4.toColumnsDoubleArray` allocates 3 arrays via map { it.toDouble() } | 3+5 | LOW | `sceneview-core/.../math/Math.kt:108-109` (used from `sceneview/.../utils/Camera.kt:96-102`) |
| SV16 | `CollisionSystem.hitTest` allocates HitResult per collider per touch | 3+5 | LOW | `sceneview/.../collision/CollisionSystem.kt:80-89` |
| SV17 | `parentInstance` setter equality check fires 2 JNI | 2 | LOW | `sceneview/.../node/Node.kt:364-378` |

### A2. arsceneview/ Android AR — ✅ DONE (15 findings : 4 HIGH, 7 MED, 4 LOW)

**Synthesis :** F3 + F4 together = the AR-side direct equivalent of #2187 (PoseNode.pose setter → worldTransform → decompose/recompose, fed `FloatArray(16)` allocations from `Pose.transform`). F1 + F2 are the JNI+collection-realloc-per-frame trap that scales O(N×M) with anchors/trackables.

| ID | Title | Tier | Impact | File |
|----|-------|------|--------|------|
| AR1 | TrackableNode JNI + List + linear `in` per node per frame | 2,3,5 | HIGH | `arsceneview/.../node/TrackableNode.kt:61-70` |
| AR2 | AnchorNode `in frame.updatedAnchors` per anchor per frame | 2,3,5 | HIGH | `arsceneview/.../node/AnchorNode.kt:104-116` |
| AR3 | `PoseNode.pose` setter goes through matrix decomp/recomp (cousin de #2187) | 1,3 | HIGH | `arsceneview/.../node/PoseNode.kt:56-63` + `arcore/Pose.kt:25-26` |
| AR4 | `Pose.transform` allocates fresh `FloatArray(16)` every read | 3 | HIGH | `arsceneview/.../arcore/Pose.kt:25-26` |
| AR5 | `ARCameraNode` allocates projection matrix array every frame | 2,3 | MED | `arsceneview/.../node/ARCameraNode.kt:80-87` + `arcore/Camera.kt:25-27` |
| AR6 | AugmentedFaceNode pose JNI ×4 + decomp ×4 per frame | 1,2 | MED | `arsceneview/.../node/AugmentedFaceNode.kt:252-256` |
| AR7 | DepthMeshNode 2× `ByteBuffer.allocateDirect` per rebuild (5 Hz) | 3 | MED | `arsceneview/.../node/DepthMeshNode.kt:299-313` |
| AR8 | PointCloudNode same pattern, **per frame** (no rate-limit) | 3 | MED | `arsceneview/.../node/PointCloudNode.kt:139-201` + `arcore/PointCloudGeometry.kt:30-56` |
| AR9 | `rememberDetectedPlanes` `.filter.toSet()` every Compose frame | 2,3,5 | MED | `arsceneview/.../arcore/Plane.kt:188-202` |
| AR10 | PlaneRenderer `frame.getUpdatedPlanes()` + linear contains | 2,3 | MED | `arsceneview/.../scene/PlaneRenderer.kt:167-185`, `PlaneRendererV2.kt:229-251` |
| AR11 | DepthHitResultNode `Pose.makeTranslation` per frame | 1,3 | LOW | `arsceneview/.../node/DepthHitResultNode.kt:83-100` |
| AR12 | HitResultNode `frame.hitTest(xPx, yPx)` per node per frame | 2,3 | MED | `arsceneview/.../node/HitResultNode.kt:95-105` |
| AR13 | `frame.cameraTextureName` JNI every frame | 2 | LOW | `arsceneview/.../camera/ARCameraStream.kt:465-467` |
| AR14 | `buildList { }` in plane visualizers' `updateRenderable` | 3 | LOW | `arsceneview/.../PlaneVisualizer.kt:206-209`, `PlaneVisualizerV2.kt:729-732` |
| AR15 | Public `Frame` extensions reallocate JNI list per call | 2,3 | LOW | `arsceneview/.../arcore/Frame.kt:48-77` |

### A3. sceneview-core/ KMP math + collision — ✅ DONE (21 findings : 5 HIGH, 9 MED, 7 LOW)

**Top-3** (agent's own ranking) : KMP-F1+F2 (#2187-family slerp(Transform,Transform) → 6 decomps), KMP-F5 (`Mat4.toColumnsFloatArray()` per Filament TRS commit), KMP-F7 (`Ray.getOrigin/Direction` defensive Vector3 copies per intersection test).

| ID | Title | Tier | Impact | File |
|----|-------|------|--------|------|
| KMP1 | `slerp(Transform, Transform, …)` decomposes both Mat4 inputs per frame (6×) | 1 | HIGH | `sceneview-core/.../math/Math.kt:182-198` |
| KMP2 | `NodeAnimationDelegate.onFrame` causes second Mat4 decomp via TRS setter | 1 | HIGH | `sceneview/.../node/NodeAnimationDelegate.kt:50-71` (caller — overlaps SV3) |
| KMP3 | `worldToLocalQuaternion` / `localToWorldQuaternion` decompose Mat4 per call | 1 | HIGH | `sceneview-core/.../math/TransformConversions.kt:49-63` |
| KMP4 | `worldToLocalRotation` Euler→Quat→Mat4-decomp→Quat→Euler chain | 1+2 | MED | `sceneview-core/.../math/TransformConversions.kt:73-83` |
| KMP5 | `Mat4.toColumnsFloatArray()` allocates `FloatArray(16)` per call | 2 | HIGH | `sceneview-core/.../math/Math.kt:120-125` |
| KMP6 | `Mat4.toColumnsDoubleArray()` 3× allocs per call | 2 | MED | `sceneview-core/.../math/Math.kt:108-109` |
| KMP7 | `Ray.getOrigin/Direction` defensive Vector3 copies per intersection | 3 | HIGH | `sceneview-core/.../collision/Ray.kt:33,42` |
| KMP8 | `withIndex()` in mesh/octree triangle loops boxes per element | 6 | MED | `sceneview-core/.../collision/MeshCollider.kt:135`, `Octree.kt:199` |
| KMP9 | `MeshCollider.rayTriangleIntersection` allocates MeshHitResult+Vector3.zero on every miss | 2/3 | MED | `sceneview-core/.../collision/MeshCollider.kt:86-123` |
| KMP10 | `Octree.query/queryRay` allocate a list per recursion + addAll | 6 | MED | `sceneview-core/.../collision/Octree.kt:80-124` |
| KMP11 | `Octree.objectCount` lambda+recursion on every access | 6 | LOW | `sceneview-core/.../collision/Octree.kt:32-33` |
| KMP12 | `Box.rayIntersection` & `Capsule.rayIntersection` Vector3 allocs per ray test | 3 | MED | `sceneview-core/.../collision/Box.kt:109-162`, `Capsule.kt:113-165` |
| KMP13 | `Capsule.capsuleBoxIntersection` allocates Sphere per test point | 3 | MED | `sceneview-core/.../collision/Capsule.kt:326-335` |
| KMP14 | `Intersections.boxBoxIntersection` ~40 Vector3 allocs per SAT test | 3 | MED | `sceneview-core/.../collision/Intersections.kt:26-56` |
| KMP15 | `closestPointOnBox` ~10 Vector3 allocs per call | 3 | MED | `sceneview-core/.../collision/Intersections.kt:74-114` |
| KMP16 | `Matrix.set(FloatArray)` element-by-element copy | 6 | LOW | `sceneview-core/.../collision/Matrix.kt:21-30` |
| KMP17 | `FloatArray.toLinearSpace()` intermediate `List<Float>` | 2 | LOW | `sceneview-core/.../math/Math.kt:203` |
| KMP18 | `Capsule.getSegmentEndpoints()` Pair+5×Vector3 per call | 3 | MED | `sceneview-core/.../collision/Capsule.kt:81-91` |
| KMP19 | `slerp(Quaternion,Quaternion,Float)` extra Quaternion alloc on dot<0 | 2 | LOW | `sceneview-core/.../animation/Interpolation.kt:212-246` |
| KMP20 | `List<Position>.getCenter()` reduce allocates per element | 2 | LOW | `sceneview-core/.../math/Math.kt:218-219` |
| KMP21 | (Verified clean) `generateShape` triangulation gated by Compose memo | 4 | none | `sceneview-core/.../geometries/ShapeGeometry.kt` |

---

## SYNTHESIS

**Total findings across 5 surfaces : 83** (after dedupe with pre-audit, **~75 unique**).

**Severity counts :** 22 HIGH, 32 MED, 29 LOW.

### Grouped by theme (proposed GitHub issues — one per theme)

#### THEME 1 — Complete the #2187 fix (HIGHEST priority)

The PR #2217 fix only covered LOCAL Node TRS. Every getter/path that still decomposes a Mat4 is a #2187 cousin :

| Issue | Findings | Surface | Impact |
|-------|----------|---------|--------|
| **I1** Cache `_worldTransform / _worldPosition / _worldQuaternion / _worldScale` with dirty flag | N1, SV4 | Android sceneview | HIGH — billboards, look-at, camera-tracking all read worldPosition per frame |
| **I2** `slerp(Transform, Transform, …)` → overload with pre-decomposed TRS; migrate NodeAnimationDelegate | M1, KMP1, KMP2, SV3 | KMP core + Android | HIGH — 6 decomps × 50 nodes × 120 Hz = 36 000 decomps/s eliminated |
| **I3** AR `PoseNode.pose` setter → bypass `worldTransform(pose.transform)`, write position/quaternion directly | AR3, AR4 | Android AR | HIGH — every anchor/plane/face hits this every frame |
| **I4** KMP `worldToLocalQuaternion / localToWorldQuaternion` decompose Mat4 — add Quaternion-direct overload | KMP3, KMP4 | KMP core | HIGH |
| **I5** Web `refreshContentCentering` mat4 round-trip per model per non-latched frame | W2 | Web | HIGH — direct cousin |

#### THEME 2 — Per-frame JNI redundancy

| Issue | Findings | Surface | Impact |
|-------|----------|---------|--------|
| **I6** Cache `transformInstance / renderableInstance / lightInstance` lazily on Node (value never changes) | SV1 | Android sceneview | HIGH — fires on every property read across the codebase |
| **I7** `parentEntity / parentInstance` equality check fires 2-4 JNI per read — cache `_parent` | N3, SV17 | Android sceneview | MED |
| **I8** AR — batch `frame.getUpdatedTrackables / updatedAnchors` once per frame into a HashSet (O(N×M) → O(N+M)) | AR1, AR2, AR9, AR10 | Android AR | HIGH |
| **I9** `ARCameraNode` allocates projection matrix `FloatArray(16)` every frame — cache & invalidate on resize/near/far change | AR5 | Android AR | MED |
| **I10** `ARCameraStream` reads `frame.cameraTextureName` JNI every frame even when unchanged | AR13 | Android AR | LOW |

#### THEME 3 — Per-frame allocation in render/animation paths

| Issue | Findings | Surface | Impact |
|-------|----------|---------|--------|
| **I11** `Mat4.toColumnsFloatArray()` → add `copyColumnsInto(out: FloatArray, offset: Int)`; migrate TransformManager.setTransform first | M2, KMP5, SV2 | KMP core + Android | HIGH |
| **I12** `NodeAnimationDelegate.onFrame` reads `node.transform` 3× per frame — single-read fix (30 sec) | SV3 | Android sceneview | HIGH — **most localized fix in entire audit** |
| **I13** `Manipulator.transform` allocates 4 arrays + Mat4 + Float4×4 per frame + dirty-skip | SV5 | Android sceneview | HIGH |
| **I14** `ModelNode.sanitizeEmptyBoundingBoxes` runs every frame after AABBs are valid — flag once sanitized | SV6 | Android sceneview | HIGH |
| **I15** `Mat4.toColumnsDoubleArray()` 3 allocs → 1 (rewrite as `DoubleArray(16) { … }`) | M3, KMP6, SV15 | KMP core | MED |
| **I16** `MaterialInstance.setParameter(Mat3/Mat4)` → add scratch-FloatArray overload | SV14 | Android sceneview | LOW (depends on user code) |
| **I17** `LightComponent.color / .position / .direction` getters allocate FloatArray(3) per read | SV8 | Android sceneview | MED |
| **I18** Web — kill rAF allocation sources (lookAt arrays, float3 arrays, billboard mat4s, viewport array) | W1, W3, W4, W5 | Web | HIGH (GC sawtooth on iOS Safari) |
| **I19** Web — dirty-flag gate for render loop (skip rendering when idle) | W9 | Web | MED (battery + GPU win) |

#### THEME 4 — Collision / ray-cast hygiene (KMP)

| Issue | Findings | Surface | Impact |
|-------|----------|---------|--------|
| **I20** `Ray.getOrigin/Direction` defensive Vector3 copies — add internal `originRef/directionRef` package-private accessors | KMP7 | KMP core | HIGH (thousands of Vector3 per mesh ray test) |
| **I21** Collision intersection alloc hygiene — Box, Capsule, Intersections (SAT, closestPointOnBox) | KMP9, KMP12, KMP13, KMP14, KMP15, KMP18 | KMP core | MED-HIGH bundled |
| **I22** Octree query allocates list per recursion + uses `withIndex()` boxing | KMP8, KMP10 | KMP core | MED |
| **I23** `CollisionSystem.hitTest(ray)` allocates HitResult per collider + 2 lists per touch | SV16 | Android sceneview | MED (per-touch path) |

#### THEME 5 — SwiftUI/RealityKit hot paths

| Issue | Findings | Surface | Impact |
|-------|----------|---------|--------|
| **I24** Auto-rotate `.task` mutates `@State camera` → full SwiftUI body re-eval at 60 Hz. Move state out of @State (use a class) | iOS3 | SceneViewSwift | HIGH |
| **I25** Framing-driver `.task` O(n²) `visualBounds` walk + 30 Hz body churn | iOS4 | SceneViewSwift | MED |
| **I26** Rerun bridge `DispatchQueue.main.async { eventCount += 1 }` per emit (~420/s) → batch + throttle Published | iOS7, iOS5, iOS9 | SceneViewSwift | MED |
| **I27** Identity writes to `entities.root.{orientation,scale,position}` every frame even when unchanged + `look(at:from:)` rebuilt without diff guard | iOS2, iOS10 | SceneViewSwift | MED |
| **I28** Cache mainLight/fillLight Entity refs (skip `children.first { }` walk) — alignment hardening | iOS1 | SceneViewSwift | LOW (hardening) |

#### THEME 6 — Gesture / touch event allocations

| Issue | Findings | Surface | Impact |
|-------|----------|---------|--------|
| **I29** `CameraGestureDetector.TouchPair` pooling (4 Float2 allocs per MotionEvent) | SV9, SV10, SV13 | Android sceneview | MED |
| **I30** `NodeGestureDelegate` Float2/Float3 per gesture event + hoist `Float3(y=1.0f)` constant | SV9 | Android sceneview | MED |

#### THEME 7 — Iterator / boxing

| Issue | Findings | Surface | Impact |
|-------|----------|---------|--------|
| **I31** `forEach` on `List<Node>` / `Set<Node>` allocates iterator per frame (Scene.kt render loop) | SV11 | Android sceneview | LOW |
| **I32** `Node.childNodes` set mutation rebuilds whole Set on every add/remove | SV12 | Android sceneview | LOW (loadtime only) |
| **I33** `ModelNode.applyAnimations` Map.Entry boxing per playing anim | SV7 | Android sceneview | MED |

#### THEME 8 — Web minor (LOW)

| Issue | Findings | Surface | Impact |
|-------|----------|---------|--------|
| **I34** Web minor wins — `{x,y}` per mousemove, layout reflow per frame, `setTimeout` closures | W6, W8, W11, W18 | Web | LOW bundled |
| **I35** Web `contentBoxes()` allocates Aabb + DoubleArray(3)/model/non-latched frame | W10 | Web | MED |

---

### Recommended action

35 issues total proposed. Of these :
- **15 HIGH** — file individually so `/issue-batch` can drive PRs in parallel
- **12 MED** — file bundled where natural (collision hygiene, gesture allocs, identity writes)
- **8 LOW** — bundle into 2-3 catch-all issues or skip

**Filing strategy proposal :** umbrella tracker issue + 15-20 sub-issues with cross-references.

### A4. SceneViewSwift/ Apple — ✅ DONE (12 findings : 2 HIGH, 5 MED, 5 LOW)

**Note agent :** pas d'équivalent direct de #2187 sur Apple — RealityKit stocke Transform en 3 champs séparés (translation/scale/orientation), pas de round-trip matriciel. Les gros gains viennent du **SwiftUI body churn** déclenché par les `.task` qui mutent `@State` à 60 Hz.

| ID | Title | Tier | Impact | File |
|----|-------|------|--------|------|
| iOS1 | Tagged-child linear scan vs cached entity ref (hardening — code OK aujourd'hui) | 5 | HIGH (latent) | `SceneViewSwift/.../SceneView.swift:962-979,1003-1008` |
| iOS2 | `look(at:from:)` rebuilt every update sans diff guard | 5 | MED | `SceneViewSwift/.../SceneView.swift:1327-1328` |
| iOS3 | Auto-rotate `.task` drives full SwiftUI body re-eval à 60 Hz | 4 | HIGH | `SceneViewSwift/.../SceneView.swift:573-590` |
| iOS4 | Framing-driver task : 30 Hz body churn + O(n²) `visualBounds` walk | 4+5 | MED | `SceneViewSwift/.../SceneView.swift:591-625,1096-1113` |
| iOS5 | `EntityObserver`/`SceneObserver` `@Published` churn + recursive count | 4+5 | MED | `SceneViewSwift/.../Observation/SceneObservation.swift:70-88,129-151` |
| iOS6 | `simd_quatf(simd_float3x3(...))` + `String(describing:)` per float (Rerun) | 2+3 | LOW (rate-limited) | `SceneViewSwift/.../Rerun/RerunWireFormat.swift:182-196,261-277,481-487` |
| iOS7 | `DispatchQueue.main.async { eventCount += 1 }` per emit (~420/s) | 6 | MED | `SceneViewSwift/.../Rerun/RerunBridge.swift:349-367` |
| iOS8 | `[[Float]]` allocation per plane vertex (Rerun) | 2+3 | LOW (rate-limited) | `SceneViewSwift/.../Rerun/RerunWireFormat.swift:199-226` |
| iOS9 | `[Float]` flat-buffer rebuild per point-cloud emit (Rerun) | 2+3 | MED | `SceneViewSwift/.../Rerun/RerunWireFormat.swift:233-252` |
| iOS10 | Identity writes to `entities.root.orientation/scale/position` per frame | 5 | MED | `SceneViewSwift/.../SceneView.swift:1323-1325,1362-1364` |
| iOS12 | `LightSlot ==` uses `Entity ===` (documentation hardening only) | 6 | LOW | `SceneViewSwift/.../SceneView.swift:50-60` |
| iOS13 | `frame.anchors` + `frame.rawFeaturePoints` accessed unconditionally (Rerun) | 3 | LOW (rate-limited) | `SceneViewSwift/.../Rerun/RerunBridge.swift:172-180` |

**Out-of-scope but flagged :** `entityDragGesture` dispatches cumulative drag translation every frame instead of delta — correctness bug, not perf. Worth follow-up.

### A5. sceneview-web/ Web — ✅ DONE (18 findings : 5 HIGH, 7 MED, 6 LOW)

**Top 3 to fix first** (agent's own ranking):
1. **F2** — `refreshContentCentering` decompose+recompose mat4 per model per non-latched frame ([sceneview-web/.../SceneView.kt:684-733](sceneview-web/src/jsMain/kotlin/io/github/sceneview/web/SceneView.kt#L684-L733)). **Direct cousin of #2187**.
2. **F5 + F1 + F3** — rAF allocation sources (lookAt arrays / float3 arrays / billboard mat4 arrays) → GC sawtooth on iOS Safari.
3. **F9** — no dirty-flag gate → full render every frame even when scene is static. Battery + GPU win.

**All findings:**

| ID | Title | Tier | Impact | File |
| --- | --- | --- | --- | --- |
| W1 | `OrbitCameraController.update()` allocates 3 `float3` arrays/frame | 1, 5 | HIGH | `sceneview-web/.../OrbitCameraController.kt:115-119` |
| W2 | `refreshContentCentering` copies+writes back 2 mat4 arrays/model/frame (#2187-equivalent) | 1, 2, 3 | HIGH | `sceneview-web/.../SceneView.kt:684-733` |
| W3 | `_updateBillboards` allocates a 16-element matrix per billboard/frame | 1, 2, 6 | HIGH | `website-static/js/sceneview.js:1036-1092` |
| W4 | XR `renderView` allocates a viewport array per eye/frame | 1, 2, 4 | HIGH | `sceneview-web/.../xr/ARSceneView.kt:244-246,471-473` |
| W5 | Static JS render loop builds fresh `lookAt` arrays per frame | 1 | HIGH | `website-static/js/sceneview.js:1534-1557` |
| W6 | Mouse/touch handlers allocate `{x,y}` objects per move | 1, 6 | MED | `website-static/js/sceneview.js:1425,1439,1464,1480` |
| W7 | `models.forEach` lambda + per-frame `animator.getAnimationCount/Duration` WASM calls | 1, 2, 5 | MED | `sceneview-web/.../SceneView.kt:847-865` |
| W8 | Per-frame `canvas.clientWidth/Height` poll forces layout reflow | 6 | MED | `sceneview-web/.../SceneView.kt:825-832` |
| W9 | No dirty-flag guard — render runs every frame when static | 4 | MED | both |
| W10 | `contentBoxes()` allocates `Aabb` + `DoubleArray(3)`/model/non-latched frame | 1, 2 | MED | `sceneview-web/.../SceneView.kt:605-632` |
| W11 | Touch handlers re-box `e.touches[n].clientX/Y` via `as Number).toDouble()` | 5 | MED | `sceneview-web/.../OrbitCameraController.kt:218-243` |
| W12 | Video chroma-key path does CPU getImageData round-trip + duplicate read | 2, 4 | LOW | `website-static/js/sceneview.js:938-942` |
| W13 | `_updateQuadTexture` rebuilds `TextureSampler` and `Uint8Array` view per upload | 2, 4 | LOW | `website-static/js/sceneview.js:1178-1213` |
| W14 | `addLight()` mat4 allocated per call (setup only) | 1 | LOW | `website-static/js/sceneview.js:686-689` |
| W15 | `ResizeObserver` rebuilds projection w/o size-change guard | 2, 4 | LOW | `website-static/js/sceneview.js:1490-1504` |
| W16 | Kotlin `EventListener { handler(it) }` adds per-event trampoline | 5 | LOW | `sceneview-web/.../OrbitCameraController.kt:144-148` |
| W17 | `js("{passive: false}")` re-allocated per `addEventListener` (setup) | 1 | LOW | `sceneview-web/.../OrbitCameraController.kt:138,147` |
| W18 | Resume-auto-rotate `setTimeout` closure allocated per release | 1 | LOW | `website-static/js/sceneview.js:1445,1451,1485` |

Agent note: Kotlin/JS module is healthy (~6 k LOC, 30+ files) — not a stub. Both code paths share the same Filament.js anti-patterns; the static JS predates the Kotlin port's `AutoCenterGate` hardening (#1391/#1633).

---

## Synthesis (filled at end)

Final ranked table with one row per finding → one GitHub issue.

---

## Re-grade addendum — 2026-06-06 (tracker [#2370](https://github.com/sceneview/sceneview/issues/2370) §3)

Headless re-grade of three audit rows against the **current state of `main`**, requested
in #2370 section 3 ("hot-path audit hygiene"). Each verdict below was confirmed by reading
the live source, not by re-reasoning from the original audit text. The audit rows above are
left as the historical 2026-05-27 snapshot; this section is the standing correction.

| Audit row | Original grade | Re-graded | Verified against |
|---|---|---|---|
| **SV7 / I33** `ModelNode.applyAnimations` Map.Entry boxing | MED | **Partial false-positive — downgrade** | `sceneview/.../node/ModelNode.kt:205` |
| **AR3 / part of I3** `PoseNode.pose` setter matrix decomp | HIGH | **DONE on main — mark stale** | `arsceneview/.../ar/node/PoseNode.kt` (pose setter) |
| **AR4 / part of I3** `Pose.transform` `FloatArray(16)` per read | HIGH | **DONE on main — mark stale** | `arsceneview/.../ar/node/ARCameraNode.kt:54-55` + PoseNode setter |

### SV7 / I33 — partial false-positive, downgrade the realized win

`ModelNode.playingAnimations` is a **public** `var playingAnimations = mutableMapOf<Int, PlayingAnimation>()`
(`ModelNode.kt:205`), iterated via `forEach { (index, animation) -> … }` in `applyAnimations`
and read elsewhere (`AnimationState`, `SceneInspector`). The audit's proposed fix — replacing
the `Map` with parallel arrays / a primitive-keyed structure to kill the `Map.Entry` boxing —
**requires a public-API break** (the `MutableMap<Int, PlayingAnimation>` type is observable by
consumers). The per-frame boxing is real but bounded by the *number of concurrently-playing
animations* (typically 1–2), not by node count, so the realized win is modest. Net: keep the
finding on record, but it is **not** a clean internal optimization — re-grade to LOW / "needs
public-API design", below the I-series HIGH/MED batch threshold.

### AR3 + AR4 — already fixed on main (mark stale)

`PoseNode.pose`'s setter (above) no longer routes through `worldTransform(pose.transform)`. It
writes the ARCore Pose's **translation + rotation components directly**
(`worldTransform(position = value.position, quaternion = value.quaternion)`), with an in-source
comment explicitly citing this exact alloc/decomp avoidance and the umbrella #2266 / #2263.
`ARCameraNode.kt:54-55` likewise writes `worldPosition = it.position` / `worldQuaternion = it.quaternion`
instead of `worldTransform = it.transform`. Both AR3 (PoseNode decomp) and AR4 (`Pose.transform`
`FloatArray(16)` per read on the per-frame path) are therefore **resolved on `main`** — the
`pose.transform` round-trip is no longer on any per-frame anchor/plane/face/camera path. Issue I3
("AR `PoseNode.pose` setter → bypass `worldTransform(pose.transform)`") is **DONE**; close/skip it
in any tracker that still lists it open.

### Still-open AR items (AR6 / AR9 / AR10 / AR11 / AR13 / I8) — NOT re-graded here

The remaining AR per-frame items from #2370 §3 (AugmentedFaceNode pose JNI, `rememberDetectedPlanes`
`.filter.toSet()`, `PlaneRenderer` updated-planes linear-contains → **I8 HIGH**, `DepthHitResultNode`
`Pose.makeTranslation`, `frame.cameraTextureName` JNI/frame) were **not** verified-as-fixed in this
headless pass and require a tracker-coverage decision (are they folded into an existing #2329-family
issue, or do they need their own?). That is a backlog-triage / issue-filing call left to the
orchestrator — deliberately **not** auto-filed here to avoid an issue burst. Tracked back in #2370 §3.
