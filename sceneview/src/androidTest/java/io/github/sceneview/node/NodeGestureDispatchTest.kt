package io.github.sceneview.node

import android.content.Context
import android.view.MotionEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.filament.Camera
import com.google.android.filament.Filament
import com.google.android.filament.Viewport
import com.google.android.filament.gltfio.Gltfio
import com.google.android.filament.utils.Utils
import io.github.sceneview.SceneNodeManager
import io.github.sceneview.collision.Box
import io.github.sceneview.collision.CollisionSystem
import io.github.sceneview.collision.Vector3
import io.github.sceneview.createEglContext
import io.github.sceneview.createEngine
import io.github.sceneview.createView
import io.github.sceneview.gesture.MoveGestureDetector
import io.github.sceneview.gesture.ScaleGestureDetector
import io.github.sceneview.math.Position
import io.github.sceneview.safeDestroy
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Behavioral proof for sceneview/sceneview#3739: [Node]'s `open` 3-arg value overloads
 * [Node.onMove] and [Node.onScale] must actually run during a live gesture — they used to be
 * dead API surface because [NodeGestureDelegate]'s 2-arg entry points called their own 3-arg
 * functions directly instead of dispatching through `node.onMove(...)` / `node.onScale(...)`.
 *
 * [NodeGestureDelegateDispatchContractTest] pins the same fix at the source level (a real
 * gesture pipeline can't run on the JVM); this class drives the real thing on-device: a genuine
 * [android.view.ScaleGestureDetector]-recognized pinch for scale, and a genuine ray-cast hit
 * test for move, each against a [Node] subclass overriding the 3-arg callback. `onRotate`
 * already dispatches correctly and is out of scope (tracked separately by #3735).
 */
@RunWith(AndroidJUnit4::class)
class NodeGestureDispatchTest {

    private lateinit var context: Context
    private lateinit var engine: com.google.android.filament.Engine
    private lateinit var filamentScene: com.google.android.filament.Scene
    private lateinit var filamentView: com.google.android.filament.View
    private lateinit var collisionSystem: CollisionSystem
    private lateinit var nodeManager: SceneNodeManager

    @Before
    fun setup() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            Gltfio.init(); Filament.init(); Utils.init()
            val eglContext = createEglContext()
            engine = createEngine(eglContext)
            filamentScene = engine.createScene()
            filamentView = createView(engine)
            filamentView.viewport = Viewport(0, 0, 512, 512)
            val camera = engine.createCamera(engine.entityManager.create())
            camera.setProjection(45.0, 1.0, 0.1, 100.0, Camera.Fov.VERTICAL)
            // Aim explicitly rather than relying on the default transform's forward axis: eye at
            // (0,0,5) looking at the world origin, so a node placed at (0,0,0) is guaranteed to be
            // dead-center in the view frustum for the ray-cast test below.
            camera.lookAt(0.0, 0.0, 5.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0)
            filamentView.camera = camera
            collisionSystem = CollisionSystem(filamentView)
            nodeManager = SceneNodeManager(filamentScene, collisionSystem)
        }
    }

    @After
    fun teardown() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            engine.destroyScene(filamentScene)
            engine.destroyView(filamentView)
            engine.safeDestroy()
        }
    }

    // ── onScale ──────────────────────────────────────────────────────────────

    private class RecordingScaleNode(engine: com.google.android.filament.Engine) : Node(engine) {
        var lastScaleFactor: Float? = null
        override fun onScale(
            detector: ScaleGestureDetector,
            e: MotionEvent,
            scaleFactor: Float
        ): Boolean {
            lastScaleFactor = scaleFactor
            return super.onScale(detector, e, scaleFactor)
        }
    }

    @Test
    fun onScale_valueOverride_isCalledDuringLiveScaleGesture() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val node = RecordingScaleNode(engine)
            node.isEditable = true
            val detector = ScaleGestureDetector(context, node)

            // A real 2-finger pinch: the pointers start close together then spread apart, so the
            // framework's android.view.ScaleGestureDetector recognizes the gesture and computes a
            // scaleFactor > 1.
            var t = 0L
            val down = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, 300f, 500f, 0)
            detector.onTouchEvent(down)
            down.recycle()

            t = 10L
            val pointerDown = twoPointerEvent(
                t,
                MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                x1 = 300f, y1 = 500f,
                x2 = 310f, y2 = 500f
            )
            detector.onTouchEvent(pointerDown)
            pointerDown.recycle()

            t = 20L
            val move = twoPointerEvent(
                t,
                MotionEvent.ACTION_MOVE,
                x1 = 200f, y1 = 500f,
                x2 = 420f, y2 = 500f
            )
            detector.onTouchEvent(move)
            move.recycle()

            assertNotNull(
                "Node.onScale(detector, e, scaleFactor) must be invoked during a live pinch " +
                    "gesture — it was silently skipped before the fix (#3739)",
                node.lastScaleFactor
            )
            assertTrue(
                "the reported scaleFactor should reflect the pointers spreading apart",
                (node.lastScaleFactor ?: 0f) > 1f
            )

            node.destroy()
        }
    }

    private fun twoPointerEvent(
        time: Long,
        action: Int,
        x1: Float, y1: Float,
        x2: Float, y2: Float
    ): MotionEvent {
        val props = arrayOf(
            MotionEvent.PointerProperties().apply { id = 0 },
            MotionEvent.PointerProperties().apply { id = 1 }
        )
        val coords = arrayOf(
            MotionEvent.PointerCoords().apply { x = x1; y = y1; pressure = 1f; size = 1f },
            MotionEvent.PointerCoords().apply { x = x2; y = y2; pressure = 1f; size = 1f }
        )
        return MotionEvent.obtain(time, time, action, 2, props, coords, 0, 0, 1f, 1f, 0, 0, 0, 0)
    }

    // ── onMove ───────────────────────────────────────────────────────────────

    private class RecordingMoveNode(engine: com.google.android.filament.Engine) : Node(engine) {
        var lastWorldPosition: Position? = null
        override fun onMove(
            detector: MoveGestureDetector,
            e: MotionEvent,
            worldPosition: Position
        ): Boolean {
            lastWorldPosition = worldPosition
            return super.onMove(detector, e, worldPosition)
        }
    }

    @Test
    fun onMove_valueOverride_isCalledDuringLiveMoveGesture() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val parent = Node(engine)
            // Dead-center in the frustum (see the camera.lookAt in setup()), sized generously so
            // the screen-center ray is guaranteed to hit it.
            parent.collisionShape = Box(Vector3(4f, 4f, 4f), Vector3(0f, 0f, 0f))

            val child = RecordingMoveNode(engine)
            parent.addChildNode(child)
            // Registering the parent (with the child already attached) propagates the
            // CollisionSystem to the child too — see CollisionSystemIntegrationTest.
            nodeManager.addNode(parent)

            child.isEditable = true
            child.isPositionEditable = true

            val moveDetector = MoveGestureDetector(context, child)

            // MoveGestureDetector only begins a move after ACTION_DOWN followed by an ACTION_MOVE
            // past its internal drag-distance threshold (kMinDragDistance = 1000px^2).
            val down = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_DOWN, 256f, 256f, 0)
            moveDetector.onTouchEvent(down)
            down.recycle()

            val move = MotionEvent.obtain(10L, 10L, MotionEvent.ACTION_MOVE, 256f, 300f, 0)
            moveDetector.onTouchEvent(move)
            move.recycle()

            assertNotNull(
                "Node.onMove(detector, e, worldPosition) must be invoked during a live move " +
                    "gesture once the ray hits the parent — it was silently skipped before the " +
                    "fix (#3739)",
                child.lastWorldPosition
            )

            nodeManager.removeNode(parent)
            child.destroy()
            parent.destroy()
        }
    }
}
