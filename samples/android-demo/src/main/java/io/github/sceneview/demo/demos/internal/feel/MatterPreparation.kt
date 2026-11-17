package io.github.sceneview.demo.demos.internal.feel

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async

/** Opening first; remaining samples are prepared serially only after its frame is revealed. */
internal class MatterPreparation {
    private val ready = mutableSetOf<FeelMaterial>()
    private val requests = mutableMapOf<FeelMaterial, Deferred<Unit>>()

    fun prepared(kind: FeelMaterial) { ready += kind }

    /** Called on Main. A user request can overtake a CPU bake, but never duplicate its upload. */
    fun request(scope: CoroutineScope, kind: FeelMaterial, prepare: suspend () -> Unit): Deferred<Unit> =
        requests.getOrPut(kind) {
            scope.async {
                prepare()
                prepared(kind)
            }
        }

    fun next(selected: FeelMaterial, revealed: Boolean): FeelMaterial? = when {
        FeelMaterial.MemoryFoam !in ready -> FeelMaterial.MemoryFoam
        !revealed -> null
        selected !in ready -> selected
        else -> FeelMaterial.entries.firstOrNull { it !in ready }
    }

    companion object {
        const val FRAMES_BETWEEN_MATERIALS = 4
    }
}
