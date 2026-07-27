package io.github.sceneview.node

import com.google.android.filament.Engine
import com.google.android.filament.LightManager
import com.google.android.filament.Material
import io.github.sceneview.Entity
import io.github.sceneview.EntityInstance
import io.github.sceneview.NULL_ENTITY
import io.github.sceneview.components.LightComponent
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
     * `0` means "not yet looked up". The handle is stable for the lifetime of the light
     * component on this entity, so we only pay the `getInstance` JNI thunk once instead of on
     * every [LightComponent] getter/setter — reactive light setups (`rememberMainLightNode`'s
     * `SideEffect { node.apply(apply) }`) re-apply on every recomposition, so this is read at
     * recomposition frequency. Same lazy-once caching #2280 applied to [transformInstance]
     * (#2269, #2285). A `0` result (component not built yet) is never frozen — it re-looks-up
     * on the next read.
     */
    private var _lightInstance: EntityInstance = 0
    override val lightInstance: EntityInstance
        get() {
            if (_lightInstance == 0) {
                _lightInstance = lightManager.getInstance(entity)
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
        lightManager.destroy(entity)
        super.destroy()
    }
}