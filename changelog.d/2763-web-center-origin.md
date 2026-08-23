<!-- category: Added -->
`centerOrigin` reaches full cross-platform parity (#2763). `sceneview-web` gains
`NodeHandle.centerOrigin(originX, originY, originZ)` — aligns the AABB point selected by a
normalized origin (`-1..1` per axis, `0` = bounding-box center) with the node origin, typed
in `sceneview-web.d.ts`. It calls the exact same `-(center + origin * halfExtent) * scale`
formula as Android's `ModelNode.centerOrigin(Position)`, now extracted into a shared
`sceneview-core` KMP function (`io.github.sceneview.math.centerOriginTranslation`) both
platforms compile — Android and Web literally share one code path and cannot numerically
diverge. A `centerOriginGoldenVectors` table pins that formula in `sceneview-core`'s
`commonTest`, which runs unmodified on both the JVM (`android`) and JS (Karma) targets; the
same expected values are duplicated in `SceneViewSwift`'s `ModelNodeTests` (RealityKit has no
dependency on the KMP module, so it reimplements the math natively), following the
`RerunWireFormatTest` cross-suite precedent. `llms.txt` documents the parity.
