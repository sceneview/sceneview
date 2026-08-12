package io.github.sceneview.node

import com.google.android.filament.Engine
import com.google.android.filament.LightManager
import com.google.android.filament.Material
import io.github.sceneview.Entity
import io.github.sceneview.EntityInstance
import io.github.sceneview.NULL_ENTITY
import io.github.sceneview.components.LightComponent
import io.github.sceneview.lightGeneration
import io.github.sceneview.safeDestroyLight
import io.github.sceneview.math.Color
import io.github.sceneview.math.toColor

/**
 * Light source [Node] in the scene such as a sun or street lights.
 *
 * At least one light must be added to a scene in order to see anything (unless the
 * [Material.Shading.UNLIT] is used).
 *
 * Creation and destruction:
 * - A Light component is created using the [LightManager.Builder] and destroyed by calling
 * [LightManager.destroy].
 *
 * @see LightManager
 */
open class LightNode(
    engine: Engine,
    entity: Entity
) : Node(engine, entity), LightComponent {

    override var isTouchable = false
    override var isEditable = false

    /**
     * Cached [LightManager] instance handle for this entity.
     *
     * `0` means "not yet looked up". We only pay the `getInstance` JNI thunk once instead of on
     * every [LightComponent] getter/setter — reactive light setups (`rememberMainLightNode`'s
     * `SideEffect { node.apply(apply) }`) re-apply on every recomposition, so this is read at
     * recomposition frequency. Same lazy-once caching #2280 applied to [transformInstance]
     * (#2269, #2285). A `0` result (component not built yet) is never frozen — it re-looks-up
     * on the next read.
     *
     * The handle is **not** stable for the lifetime of the light component: [LightManager] is a
     * packed-array store that compacts on removal by swapping the last live entity into the
     * freed slot, silently reindexing that other entity's handle. So destroying *any* light on
     * this [engine] can invalidate this cache, and every subsequent read/write would land on
     * another light — reporting back exactly what was written while the renderer uses the real
     * component (#2991, the [LightManager] half of #3123).
     * [io.github.sceneview.lightGeneration] is bumped on every light-component destroy, so
     * comparing the snapshotted generation against the current one on each read detects the
     * reindex in O(1) and forces a fresh lookup.
     */
    private var _lightInstance: EntityInstance = 0
    private var _lightInstanceGeneration = -1
    override val lightInstance: EntityInstance
        get() {
            val currentGeneration = engine.lightGeneration()
            if (_lightInstance == 0 || _lightInstanceGeneration != currentGeneration) {
                _lightInstance = lightManager.getInstance(entity)
                _lightInstanceGeneration = currentGeneration
            }
            return _lightInstance
        }

    /**
     * Per-instance scratch buffer for the [color] getter, reused across reads so each read no
     * longer allocates a throwaway `FloatArray(3)` to receive Filament's RGB out-param (#2328
     * SV8). Only the returned [Color] (a fresh [io.github.sceneview.math.Color]) is allocated —
     * the scratch is never handed to a caller, so there is no aliasing risk. Light reads happen
     * on the render/main thread (a Filament JNI requirement), so this single-threaded reuse
     * introduces no race.
     */
    private val colorScratch = FloatArray(3)

    /**
     * Overrides the [LightComponent.color] default getter to reuse [colorScratch] instead of
     * allocating a fresh `FloatArray(3)` on every read. Behaviour is identical: the value handed
     * to the caller is still a freshly-built [Color].
     */
    override var color: Color
        get() = colorScratch.apply { lightManager.getColor(lightInstance, this) }.toColor()
        set(value) {
            lightManager.setColor(lightInstance, value.r, value.g, value.b)
        }

    constructor(
        engine: Engine,
        entity: Entity = NULL_ENTITY,
        builder: LightManager.Builder
    ) : this(engine, entity) {
        // `this.entity` — NOT the `entity` parameter, which is the NULL_ENTITY sentinel when
        // the caller let the node allocate its own. Node resolved it; the parameter did not.
        builder.build(engine, this.entity)
    }

    constructor(
        engine: Engine,
        type: LightManager.Type,
        entity: Entity = NULL_ENTITY,
        apply: LightManager.Builder.() -> Unit
    ) : this(engine, entity, LightManager.Builder(type).apply(apply))

    override fun destroy() {
        // Not `lightManager.destroy(entity)` directly: this removal compacts LightManager's
        // packed array and reindexes another live light's handle, so it must bump the engine's
        // light generation or every other LightNode keeps a cache that now points elsewhere
        // (#2991).
        engine.safeDestroyLight(entity)
        super.destroy()
    }
}