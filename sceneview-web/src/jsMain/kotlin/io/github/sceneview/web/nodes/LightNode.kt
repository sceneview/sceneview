package io.github.sceneview.web.nodes

import io.github.sceneview.web.bindings.Engine
import io.github.sceneview.web.bindings.Entity
import io.github.sceneview.web.bindings.EntityManager
import io.github.sceneview.web.bindings.LightManager
import io.github.sceneview.web.bindings.float3

/**
 * A [Node] that owns a Filament light — the web mirror of Android
 * `io.github.sceneview.node.LightNode` (issue #2024, slice 2b of
 * `.claude/plans/v5-web-node-graph.md`).
 *
 * Created via `SceneView.addLightNode(config)`, which runs the exact
 * `LightManager.Builder` pipeline `SceneView.addLight` uses today (same enum
 * `LightManager$Type`, same `intensity`/`color`/`direction`/`position`/
 * `castShadows`/`falloff` builder calls, `scene.addEntity`, tracked in
 * `lightEntities` for leak-free teardown #1700) and then re-parents the built
 * light entity under this node's pivot — so moving/rotating the node moves the
 * light, exactly like Android `LightNode` owning its light entity.
 *
 * **Ownership (slice-2b semantics):** the light component + entity are owned by
 * the `SceneView` light tracker, not by this node — [destroy] frees the node's
 * own pivot entity but NOT the light (freed by `SceneView.destroy()`, which
 * destroys the light component before the entity, the #1700 order).
 *
 * The runtime mutators ([intensity] / [color] / [direction] / [position]) push
 * straight through the Filament `LightManager` *instance* bindings
 * (`getInstance` → `setIntensity`/`setColor`/`setDirection`/`setPosition`),
 * proven in-browser by the `kotlin-bundle.spec.ts` #2024-P1 slice-2b probe
 * before this node depends on them (the mandatory embind probe-first rule —
 * Karma stubs the Filament externals, so `jsTest` can never validate them).
 * Until the light entity is built ([controller] is `null` — e.g. a test-backed
 * node), the setters only cache the value; the built light picks up the cached
 * config at construction.
 */
class LightNode internal constructor(
    backend: NodeBackend,
    val type: LightType,
) : Node(backend) {

    /** See [Node]'s secondary constructor. */
    constructor(engine: Engine, type: LightType, entity: Entity = EntityManager.get().create()) :
        this(FilamentNodeBackend(engine, entity), type)

    /**
     * The engine-side seam pushing runtime mutations to the Filament
     * `LightManager` instance, `null` until the light entity is built (a
     * test-backed node, or before `SceneView.addLightNode` wires it). Kept as
     * an interface so the mutator round-trips are `jsTest`-checkable without the
     * Filament WASM module — production uses [FilamentLightController].
     */
    internal var controller: LightController? = null
        set(value) {
            field = value
            // Flush the cached config onto a freshly-wired controller so a
            // config set before the light was built still lands.
            value?.let {
                it.setIntensity(_intensity)
                it.setColor(_colorR, _colorG, _colorB)
                if (type == LightType.DIRECTIONAL) {
                    it.setDirection(_directionX, _directionY, _directionZ)
                } else {
                    it.setPosition(_positionX, _positionY, _positionZ)
                }
            }
        }

    private var _intensity: Double = 100_000.0
    private var _colorR: Double = 1.0
    private var _colorG: Double = 1.0
    private var _colorB: Double = 1.0
    private var _directionX: Double = 0.0
    private var _directionY: Double = -1.0
    private var _directionZ: Double = -0.5
    private var _positionX: Double = 0.0
    private var _positionY: Double = 3.0
    private var _positionZ: Double = 0.0

    /** Luminous intensity. Directional: lux; point/spot: candela. */
    var intensity: Double
        get() = _intensity
        set(value) {
            _intensity = value
            if (!isDestroyed) controller?.setIntensity(value)
        }

    /** Linear RGB colour (each channel typically `0.0..1.0`). */
    fun color(r: Double, g: Double, b: Double) {
        _colorR = r; _colorG = g; _colorB = b
        if (!isDestroyed) controller?.setColor(r, g, b)
    }

    /** Direction the light points, for `DIRECTIONAL` / `SPOT` lights. */
    fun direction(x: Double, y: Double, z: Double) {
        _directionX = x; _directionY = y; _directionZ = z
        if (!isDestroyed) controller?.setDirection(x, y, z)
    }

    /** Light position, for `POINT` / `SPOT` lights. */
    fun position(x: Double, y: Double, z: Double) {
        _positionX = x; _positionY = y; _positionZ = z
        if (!isDestroyed) controller?.setPosition(x, y, z)
    }
}

/**
 * Engine-side seam for [LightNode]'s runtime mutators — the light analog of
 * [NodeBackend]. Exists as an interface so the config-flush + mutator forwarding
 * is unit-testable in `jsTest` without the Filament WASM module (a recording
 * fake), while production goes through [FilamentLightController].
 */
internal interface LightController {
    fun setIntensity(value: Double)
    fun setColor(r: Double, g: Double, b: Double)
    fun setDirection(x: Double, y: Double, z: Double)
    fun setPosition(x: Double, y: Double, z: Double)
}

/**
 * Production [LightController]: mutates a Filament light through the
 * [LightManager] *instance* bindings. The instance is fetched per call and
 * never cached — Filament light instances are unstable across component
 * create/destroy of other entities (component-array compaction), the same
 * reason [FilamentNodeBackend] never caches its transform instance.
 */
internal class FilamentLightController(
    private val lightManager: LightManager,
    private val entity: Entity,
) : LightController {

    private val instance: dynamic get() = lightManager.getInstance(entity)

    override fun setIntensity(value: Double) {
        lightManager.setIntensity(instance, value)
    }

    override fun setColor(r: Double, g: Double, b: Double) {
        lightManager.setColor(instance, float3(r, g, b))
    }

    override fun setDirection(x: Double, y: Double, z: Double) {
        lightManager.setDirection(instance, float3(x, y, z))
    }

    override fun setPosition(x: Double, y: Double, z: Double) {
        lightManager.setPosition(instance, float3(x, y, z))
    }
}
