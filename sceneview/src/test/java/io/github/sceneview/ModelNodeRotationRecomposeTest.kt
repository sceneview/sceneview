package io.github.sceneview

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure-JVM regression test for the recomposition contract of the `ModelNode` composable
 * (`SceneScope.ModelNode`) — issue #2639.
 *
 * Before the fix, `ModelNode` pushed its declarative transform to the underlying runtime node
 * from a bare `SideEffect { node.rotation = rotation; node.position = …; node.scale = … }` block.
 * `SideEffect` runs after **every** successful (re)composition and is not keyed, so any
 * recomposition — even one where the `rotation` parameter never changed — re-applied the declared
 * `rotation`, silently clobbering a rotation the user had just applied with a gesture
 * (`NodeGestureDelegate`) or a frame-loop driver (physics/animation/camera-follow) had written on
 * the runtime node. Net symptom: the user rotates the model, an unrelated state change triggers a
 * recomposition, and the model snaps back to its declared rotation.
 *
 * The fix mirrors the idiom the bare `Node` composable already uses: component-keyed
 * `DisposableEffect(node, rotation.x, rotation.y, rotation.z)`. The effect body re-runs only when a
 * rotation component actually changes, so a bare recomposition leaves the gesture-mutated runtime
 * rotation untouched, while a genuine declared-rotation change still propagates.
 *
 * The test module has no Compose UI-test dependency, so — like [MainLightReactivityTest] — this
 * pins the *intended contract* by simulating the two effect patterns in plain JVM: it documents
 * that an unkeyed-`SideEffect` applier re-clobbers a gesture rotation on an unchanged recompose
 * while a component-keyed applier stays idle. It is a semantic model of that contract, **not** a
 * guard on the production wiring — it never touches `SceneScope.ModelNode`, so it would not, on its
 * own, fail if that composable regressed to an unkeyed `SideEffect`. The KDoc rationale in
 * `SceneScope.kt` is the source of truth for the production effect keying.
 */
class ModelNodeRotationRecomposeTest {

    /** A minimal 3-component rotation, standing in for the runtime node's `rotation`. */
    private data class Rot(val x: Float, val y: Float, val z: Float)

    /**
     * Simulates the OLD unkeyed `SideEffect { node.rotation = declared }`: the declared value is
     * re-applied on every recomposition, regardless of what a gesture wrote in between.
     */
    private class SideEffectApplier(private val declared: Rot) {
        fun recompose(node: Ref<Rot>) {
            node.value = declared
        }
    }

    /**
     * Simulates the NEW component-keyed `DisposableEffect(node, rotation.x, rotation.y, rotation.z)`:
     * the effect body re-applies the declared value only when a keyed component actually changed
     * since the last time it ran (Compose skips the block when all keys compare equal).
     */
    private class KeyedEffectApplier {
        private var lastKeys: Rot? = null

        fun recompose(node: Ref<Rot>, declared: Rot) {
            if (lastKeys != declared) {
                node.value = declared
                lastKeys = declared
            }
        }
    }

    private class Ref<T>(var value: T)

    @Test
    fun keyedEffectDoesNotClobberGestureRotationOnUnchangedRecompose() {
        val declared = Rot(0f, 0f, 0f)
        val node = Ref(declared)
        val applier = KeyedEffectApplier()

        // First composition applies the declared rotation.
        applier.recompose(node, declared)
        assertEquals(declared, node.value)

        // User rotates the model with a gesture — the runtime node now holds a different rotation.
        node.value = Rot(0f, 90f, 0f)

        // An unrelated Compose state change triggers a recomposition with the SAME `rotation` param.
        applier.recompose(node, declared)

        assertEquals(
            "A recomposition with an unchanged `rotation` param must NOT re-apply the declared " +
                "rotation — the gesture-applied runtime rotation must survive (#2639).",
            Rot(0f, 90f, 0f),
            node.value,
        )
    }

    @Test
    fun oldSideEffectPatternClobbersGestureRotation() {
        // Documents the broken behaviour the fix removes: the unkeyed SideEffect re-applies the
        // declared rotation on every recomposition, wiping out the gesture-applied value.
        val declared = Rot(0f, 0f, 0f)
        val node = Ref(declared)
        val applier = SideEffectApplier(declared)

        applier.recompose(node)
        node.value = Rot(0f, 90f, 0f) // gesture

        applier.recompose(node) // bare recomposition

        assertEquals(
            "The old unkeyed SideEffect is expected to clobber the gesture rotation — this is the " +
                "bug #2639 fixes.",
            declared,
            node.value,
        )
    }

    @Test
    fun keyedEffectStillPropagatesGenuineDeclaredRotationChange() {
        // The fix must not break reactivity: when the caller genuinely changes the `rotation`
        // parameter from Compose state, the new declared value must still reach the runtime node.
        val node = Ref(Rot(0f, 0f, 0f))
        val applier = KeyedEffectApplier()

        applier.recompose(node, Rot(0f, 0f, 0f))
        node.value = Rot(0f, 90f, 0f) // gesture in between

        // Caller mutates the declared rotation and recomposes.
        applier.recompose(node, Rot(45f, 0f, 0f))

        assertEquals(
            "A genuine change to the declared `rotation` parameter must still propagate to the " +
                "runtime node (reactivity preserved).",
            Rot(45f, 0f, 0f),
            node.value,
        )
    }
}
