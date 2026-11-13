package io.github.sceneview.render

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.sceneview.loaders.ModelLoader
import io.github.sceneview.math.Position
import io.github.sceneview.math.Scale
import io.github.sceneview.math.Transform
import io.github.sceneview.model.AnimationEnvelopes
import io.github.sceneview.model.animationEnvelope
import io.github.sceneview.model.model
import io.github.sceneview.model.restPoseBoundingBox
import io.github.sceneview.toAabb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Compile-only without a device. Uses the existing CC-BY Khronos Fox test asset. */
@RunWith(AndroidJUnit4::class)
class AnimationEnvelopeRenderTest {
    @Test fun foxEnvelopeContainsRestAndNeverChangesTheVisibleAnimatorPose() = runBlocking {
        val harness = RenderTestHarness()
        var loader: ModelLoader? = null
        try {
            withContext(Dispatchers.Main.immediate) {
                val modelLoader = ModelLoader(harness.engine, InstrumentationRegistry.getInstrumentation().context)
                loader = modelLoader
                val model = modelLoader.createModel("khronos_fox.glb", releaseSourceData = false)
                val instance = model.instance
                val secondInstance = checkNotNull(modelLoader.createInstance(model))
                model.releaseSourceData()
                assertTrue(instance.skinCount > 0)
                assertTrue(instance.animator.animationCount > 0)
                val transforms = harness.engine.transformManager
                // Application root edits must not contaminate a model-space cache.
                transforms.setTransform(transforms.getInstance(instance.root),
                    Transform(position = Position(5f, -2f, 3f), scale = Scale(0.01f)).toFloatArray())
                val animator = instance.animator
                val clip = 0
                animator.applyAnimation(clip, animator.getAnimationDuration(clip) * 0.37f)
                if (animator.animationCount > 1) animator.applyCrossFade(1, 0.2f, 0.4f)
                animator.updateBoneMatrices()
                fun snapshot() = instance.entities.map {
                    transforms.getTransform(transforms.getInstance(it), null)
                }
                val before = snapshot()
                val start = System.nanoTime()
                val envelope = instance.animationEnvelope(clip, samples = 24)
                val wallMs = (System.nanoTime() - start) / 1_000_000.0
                val after = snapshot()
                before.zip(after).forEach { (a, b) -> assertArrayEquals(a, b, 0f) }
                val rest = instance.model.restPoseBoundingBox.toAabb()
                assertEquals(25, envelope.poses.size)
                assertEquals(rest, envelope.poses.first())
                assertTrue(envelope.bounds.min.x <= rest.min.x + 0.001f)
                assertTrue(envelope.bounds.min.y <= rest.min.y + 0.001f)
                assertTrue(envelope.bounds.min.z <= rest.min.z + 0.001f)
                assertTrue(envelope.bounds.max.x >= rest.max.x - 0.001f)
                assertTrue(envelope.bounds.max.y >= rest.max.y - 0.001f)
                assertTrue(envelope.bounds.max.z >= rest.max.z - 0.001f)
                assertTrue("sampled motion must differ from the rest pose",
                    envelope.poses.drop(1).any { it != rest })
                assertSame(envelope, instance.animationEnvelope(clip, samples = 24))
                assertSame(envelope, secondInstance.animationEnvelope(clip, samples = 24))
                assertSame(envelope, instance.animationEnvelope(animator.getAnimationName(clip), samples = 24))
                // A second clip cannot inherit the first clip's private TRS state.
                if (animator.animationCount > 1) instance.animationEnvelope(1, samples = 4)
                before.zip(snapshot()).forEach { (a, b) -> assertArrayEquals(a, b, 0f) }
                val sampler = AnimationEnvelopes.sampler(instance, clip, 24)
                Log.i("AnimationEnvelope", "Fox: 24 poses, wall=$wallMs ms, " +
                    "CPU=${sampler.totalSamplingNanos / 1_000_000.0} ms, " +
                    "max batch=${sampler.maxBatchNanos / 1_000_000.0} ms")
                assertTrue(sampler.totalSamplingNanos > 0)

                val cube = modelLoader.createModelInstance("unit_cube_1m.glb")
                assertEquals(0, cube.skinCount)
                assertEquals(listOf(cube.model.restPoseBoundingBox.toAabb()), cube.animationEnvelope().poses)
            }
        } finally {
            withContext(Dispatchers.Main.immediate) { loader?.destroy() }
            harness.destroy()
        }
    }
}
