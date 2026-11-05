package io.github.sceneview.material

import androidx.annotation.RestrictTo
import io.github.sceneview.model.Model
import io.github.sceneview.node.ModelNode
import io.github.sceneview.node.Node
import java.util.WeakHashMap
import kotlin.math.abs

/**
 * Scales the glow of glTF emissive materials, relative to each material's authored strength (#4950).
 *
 * Filament adds emissive after lighting and outside camera exposure, so a glow authored for a dark
 * scene stays at full strength in a daylit room, where it reads as a flat sticker. AR scales it
 * down with the room's light estimate; the authored `KHR_materials_emissive_strength` of each
 * material is the reference, read from the glTF when the model is loaded, because Filament has no
 * getter for a material parameter.
 *
 * Main thread only, like every Filament call.
 */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP_PREFIX)
object EmissiveScale {

    /** Authored strength by material name, per model; a model with no entry was authored at 1. */
    private val authored = WeakHashMap<Model, Map<String, Float>>()

    /** The scale last applied to each node, so a steady room costs no Filament call. */
    private val applied = WeakHashMap<ModelNode, Float>()

    /** Remembers the authored strengths of [model], from `GltfLimits.Parsed.emissiveStrengths`. */
    fun record(model: Model, strengths: Map<String, Float>?) {
        if (strengths == null) authored.remove(model) else authored[model] = strengths
    }

    /** Forgets [model], once destroyed. */
    fun forget(model: Model) {
        authored.remove(model)
    }

    /**
     * Sets every emissive material under [nodes] to its authored strength times [scale].
     *
     * A node already at [scale] (within 1 %) is skipped; [scale] 1 restores the authored glow.
     */
    fun apply(nodes: Iterable<Node>, scale: Float) {
        for (node in nodes) {
            if (node.isDestroyed) continue
            if (node is ModelNode && needsUpdate(applied[node], scale)) {
                applyTo(node, scale)
                applied[node] = scale
            }
            apply(node.childNodes, scale)
        }
    }

    private fun applyTo(node: ModelNode, scale: Float) {
        val modelInstance = node.modelInstance
        val strengths = authored[modelInstance.asset]
        for (materialInstance in modelInstance.materialInstances) {
            if (materialInstance.materialOrNull?.hasParameter(EMISSIVE_STRENGTH) != true) continue
            val strength = authoredEmissiveStrength(materialInstance.name, strengths) ?: continue
            materialInstance.setParameter(EMISSIVE_STRENGTH, strength * scale)
        }
    }

    /**
     * The authored strength of the material named [name]: 1 when the model declares none
     * ([strengths] `null`), `null` when it cannot be told (unknown or ambiguous name), in which
     * case the material is left alone.
     */
    internal fun authoredEmissiveStrength(name: String?, strengths: Map<String, Float>?): Float? {
        if (strengths == null) return 1f
        val strength = name?.let { strengths[it] } ?: return null
        return strength.takeUnless { it.isNaN() }
    }

    /** Whether a node last set to [last] has to move to [scale]. */
    internal fun needsUpdate(last: Float?, scale: Float): Boolean =
        last == null || abs(last - scale) > UPDATE_TOLERANCE * maxOf(abs(last), abs(scale))

    private const val EMISSIVE_STRENGTH = "emissiveStrength"
    private const val UPDATE_TOLERANCE = 0.01f
}
