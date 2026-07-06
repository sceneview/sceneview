package io.github.sceneview.web.nodes

import io.github.sceneview.math.Transform
import io.github.sceneview.math.copyColumnsInto
import io.github.sceneview.web.bindings.Engine
import io.github.sceneview.web.bindings.Entity
import io.github.sceneview.web.bindings.EntityManager

/**
 * Engine-side backing of a [Node] — the thin seam between the pure-Kotlin
 * scene-graph state (owned by [Node], shared with Android via the
 * `sceneview-core` `SceneNode` contract) and the Filament.js
 * `TransformManager` (issue #2024).
 *
 * Exists as an interface (rather than [Node] calling Filament directly) so the
 * graph semantics — hierarchy, local/world transform composition, recursive
 * destroy — are unit-testable in `jsTest` without a WebGL context or the
 * Filament WASM module, which Karma cannot provide. Production nodes always
 * use [FilamentNodeBackend]; tests substitute a recording fake.
 */
internal interface NodeBackend {

    /** Pushes the node's composed local transform matrix to the engine. */
    fun setLocalTransform(transform: Transform)

    /**
     * Re-parents this backend's transform component under [parent]
     * (`null` = detach), so the engine composes `world = parentWorld * local`
     * on its side — mirroring Android `Node`'s `transformManager.setParent`.
     */
    fun setParent(parent: NodeBackend?)

    /**
     * Re-parents an engine entity this node does NOT own (e.g. a gltfio
     * asset's root entity) under this node's transform component, so the
     * foreign subtree inherits this node's transform — the web analog of
     * Android `ModelNode` adopting `modelInstance.root` (#2024 slice 2).
     */
    fun adoptChildEntity(child: Entity)

    /** Frees every engine resource owned by the node (transform component + entity). */
    fun destroy()
}

/**
 * Production [NodeBackend]: one Filament [Entity] with a `TransformManager`
 * component per node — byte-for-byte the ownership model of Android
 * `io.github.sceneview.node.Node`.
 */
internal class FilamentNodeBackend(
    val engine: Engine,
    val entity: Entity,
) : NodeBackend {

    private val transformManager = engine.getTransformManager()

    /** Reusable Kotlin-side mat4 scratch — keeps [setLocalTransform] allocation-light. */
    private val floatScratch = FloatArray(16)

    /**
     * Reusable flat 16-element column-major JS array passed to
     * `TransformManager.setTransform` — same scratch pattern as
     * `SceneView.transformScratch` (#2268), so a transform write never
     * allocates a fresh JS array.
     */
    private val matrixScratch: dynamic = js("new Array(16)")

    init {
        if (!transformManager.hasComponent(entity)) {
            transformManager.create(entity)
        }
    }

    /**
     * The `TransformManager$Instance` for [entity], fetched per call and never
     * cached: Filament transform instances are unstable across component
     * create/destroy of *other* entities (component-array compaction), so a
     * cached instance can silently dangle.
     */
    private val instance: dynamic get() = transformManager.getInstance(entity)

    override fun setLocalTransform(transform: Transform) {
        transform.copyColumnsInto(floatScratch)
        for (i in 0..15) {
            matrixScratch[i] = floatScratch[i]
        }
        transformManager.setTransform(instance, matrixScratch)
    }

    override fun setParent(parent: NodeBackend?) {
        // A JS `null` parent is REJECTED by embind at runtime ("null is not
        // a valid TransformManager$Instance" — proven in-browser against the
        // pinned Filament.js by the kotlin-bundle.spec.ts #2024-P1 probe).
        // The working detach is the null INSTANCE (native instance 0):
        // `getInstance()` of an entity that never got a transform component —
        // exactly Android's `setParent(i, 0)`.
        transformManager.setParent(
            instance,
            (parent as? FilamentNodeBackend)?.instance ?: nullParentInstance(),
        )
    }

    /**
     * The engine's null `TransformManager$Instance`, obtained through a
     * lazily-created component-less sentinel entity (one per page, never
     * destroyed — a bare entity id, no engine resources). The sentinel must
     * NEVER be given a transform component, or its instance stops being 0.
     */
    private fun nullParentInstance(): dynamic =
        transformManager.getInstance(detachSentinel ?: EntityManager.get().create().also {
            detachSentinel = it
        })

    private companion object {
        /** Shared across all backends — entity ids are engine-global. */
        private var detachSentinel: Entity? = null
    }

    override fun adoptChildEntity(child: Entity) {
        // gltfio asset roots always carry a transform component, but be
        // defensive — setParent on a missing instance is a WASM abort, not a
        // catchable Kotlin exception.
        if (!transformManager.hasComponent(child)) {
            transformManager.create(child)
        }
        transformManager.setParent(transformManager.getInstance(child), instance)
    }

    override fun destroy() {
        // Component first, then entity — the same leak-ordering rule as the
        // light teardown in SceneView.destroy() (#1700).
        if (transformManager.hasComponent(entity)) {
            transformManager.destroy(entity)
        }
        engine.destroyEntity(entity)
    }
}
