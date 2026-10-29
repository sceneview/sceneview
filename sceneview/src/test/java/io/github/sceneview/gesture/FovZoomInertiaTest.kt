package io.github.sceneview.gesture

import com.google.android.filament.Engine
import io.github.sceneview.math.Transform
import io.github.sceneview.node.CameraNode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(instrumentedPackages = ["com.google.android.filament"])
class FovZoomInertiaTest {

    @Test
    fun `a released FOV pinch coasts until stopped`() {
        val engine = Engine::class.java
            .getDeclaredConstructor(Long::class.javaPrimitiveType, Engine.Config::class.java)
            .apply { isAccessible = true }
            .newInstance(1L, null)
        val manipulator = FovZoomCameraManipulator(
            inner = NoOpManipulator,
            cameraNode = CameraNode(engine, 1),
        )
        var clock = 0L
        manipulator.nanoTime = { clock }

        manipulator.scrollBegin(0, 0, 100f)
        clock += 16_000_000L
        manipulator.scrollUpdate(0, 0, 100f, 200f)
        clock += 1_000_000L
        manipulator.scrollEnd()

        assertTrue("the FOV pinch should still owe frames after release", manipulator.isFrameActive)
        val releasedFov = manipulator.currentFovForTest()
        manipulator.update(1f / 60f)
        assertNotEquals(
            "a coast frame should keep changing the FOV",
            releasedFov,
            manipulator.currentFovForTest(),
        )

        manipulator.stopInertia()
        assertFalse("stopInertia must end the FOV coast", manipulator.isFrameActive)
    }

    @Test
    fun `a pinch over a manipulator that predates stopInertia does not crash`() {
        val engine = Engine::class.java
            .getDeclaredConstructor(Long::class.javaPrimitiveType, Engine.Config::class.java)
            .apply { isAccessible = true }
            .newInstance(1L, null)
        val manipulator = FovZoomCameraManipulator(
            inner = PreStopInertiaManipulator,
            cameraNode = CameraNode(engine, 1),
        )

        // scrollBegin stops the previous coast first, which reaches the inner manipulator.
        manipulator.scrollBegin(0, 0, 100f)
        manipulator.scrollUpdate(0, 0, 100f, 200f)
        manipulator.scrollEnd()
        manipulator.stopInertia()
        assertFalse("the FOV coast still stops", manipulator.isFrameActive)
    }

    private fun FovZoomCameraManipulator.currentFovForTest(): Double =
        javaClass.getDeclaredField("currentFov").apply { isAccessible = true }.getDouble(this)

    private object NoOpManipulator : CameraGestureDetector.CameraManipulator {
        override fun setViewport(width: Int, height: Int) = Unit
        override fun getTransform(): Transform = Transform()
        override fun grabBegin(x: Int, y: Int, strafe: Boolean) = Unit
        override fun grabUpdate(x: Int, y: Int) = Unit
        override fun grabEnd() = Unit
        override fun scrollBegin(x: Int, y: Int, separation: Float) = Unit
        override fun scrollUpdate(
            x: Int,
            y: Int,
            prevSeparation: Float,
            currSeparation: Float,
        ) = Unit
        override fun scrollEnd() = Unit
        override fun update(deltaTime: Float) = Unit
    }

    /**
     * What a Java manipulator compiled before `stopInertia()` existed looks like at runtime: the
     * default lives in Kotlin's `DefaultImpls`, which such a class never got.
     */
    private object PreStopInertiaManipulator :
        CameraGestureDetector.CameraManipulator by NoOpManipulator {
        override fun stopInertia(): Unit = throw AbstractMethodError("stopInertia")
    }
}
