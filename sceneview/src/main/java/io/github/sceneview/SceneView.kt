package io.github.sceneview

import android.annotation.SuppressLint
import android.content.Context
import android.media.MediaRecorder
import android.opengl.EGLContext
import android.os.SystemClock
import android.util.AttributeSet
import android.util.Log
import android.view.Choreographer
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceView
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.fragment.app.Fragment
import androidx.fragment.app.findFragment
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.findViewTreeLifecycleOwner
import com.google.android.filament.ColorGrading
import com.google.android.filament.Colors
import com.google.android.filament.Engine
import com.google.android.filament.Filament
import com.google.android.filament.IndirectLight
import com.google.android.filament.LightManager
import com.google.android.filament.MaterialInstance
import com.google.android.filament.RenderableManager
import com.google.android.filament.Renderer
import com.google.android.filament.Scene
import com.google.android.filament.Skybox
import com.google.android.filament.SwapChain
import com.google.android.filament.ToneMapper
import com.google.android.filament.View
import com.google.android.filament.View.AntiAliasing
import com.google.android.filament.View.BlendMode
import com.google.android.filament.View.QualityLevel
import com.google.android.filament.Viewport
import com.google.android.filament.android.DisplayHelper
import com.google.android.filament.android.UiHelper
import com.google.android.filament.gltfio.Gltfio
import com.google.android.filament.utils.KTX1Loader
import com.google.android.filament.utils.Manipulator
import com.google.android.filament.utils.Utils
import dev.romainguy.kotlin.math.Float2
import io.github.sceneview.collision.CollisionSystem
import io.github.sceneview.collision.HitResult
import io.github.sceneview.environment.Environment
import io.github.sceneview.gesture.CameraGestureDetector
import io.github.sceneview.gesture.GestureDetector
import io.github.sceneview.gesture.MoveGestureDetector
import io.github.sceneview.gesture.RotateGestureDetector
import io.github.sceneview.gesture.ScaleGestureDetector
import io.github.sceneview.loaders.EnvironmentLoader
import io.github.sceneview.loaders.MaterialLoader
import io.github.sceneview.loaders.ModelLoader
import io.github.sceneview.managers.color
import io.github.sceneview.math.Position
import io.github.sceneview.math.Transform
import io.github.sceneview.math.colorOf
import io.github.sceneview.math.toColor
import io.github.sceneview.model.Model
import io.github.sceneview.model.ModelInstance
import io.github.sceneview.node.CameraNode
import io.github.sceneview.node.LightNode
import io.github.sceneview.node.Node
import io.github.sceneview.node.ViewNode2
import io.github.sceneview.utils.OpenGL
import io.github.sceneview.utils.SurfaceMirrorer
import io.github.sceneview.utils.intervalSeconds
import io.github.sceneview.utils.readBuffer
import io.github.sceneview.utils.setKeepScreenOn

typealias Entity = Int
typealias EntityInstance = Int
typealias FilamentEntity = com.google.android.filament.Entity
typealias FilamentEntityInstance = com.google.android.filament.EntityInstance

/**
 * A SurfaceView that manages rendering and interactions with the 3D scene.
 *
 * Maintains the scene graph, a hierarchical organization of a scene's content.
 * A scene can have zero or more child nodes and each node can have zero or more child nodes.
 * The Scene also provides hit testing, a way to detect which node is touched by a MotionEvent or
 * Ray.
 */
open class SceneView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
    defStyleRes: Int = 0,
    /**
     * Provide your own instance if you want to share Filament resources between multiple views.
     */
    sharedEngine: Engine? = null,
    /**
     * Consumes a blob of glTF 2.0 content (either JSON or GLB) and produces a [Model] object, which is
     * a bundle of Filament textures, vertex buffers, index buffers, etc.
     *
     * A [Model] is composed of 1 or more [ModelInstance] objects which contain entities and components.
     */
    sharedModelLoader: ModelLoader? = null,
    /**
     * A Filament Material defines the visual appearance of an object.
     *
     * Materials function as a templates from which [MaterialInstance]s can be spawned.
     */
    sharedMaterialLoader: MaterialLoader? = null,
    /**
     * Utility for decoding an HDR file or consuming KTX1 files and producing Filament textures,
     * IBLs, and sky boxes.
     *
     * KTX is a simple container format that makes it easy to bundle miplevels and cubemap faces
     * into a single file.
     */
    sharedEnvironmentLoader: EnvironmentLoader? = null,
    /**
     * Provide your own instance if you want to share [Node]s' scene between multiple views.
     */
    sharedScene: Scene? = null,
    /**
     * Encompasses all the state needed for rendering a {@link Scene}.
     *
     * [View] instances are heavy objects that internally cache a lot of data needed for
     * rendering. It is not advised for an application to use many View objects.
     *
     * For example, in a game, a [View] could be used for the main scene and another one for the
     * game's user interface. More <code>View</code> instances could be used for creating special
     * effects (e.g. a [View] is akin to a rendering pass).
     */
    sharedView: View? = null,
    /**
     * A [Renderer] instance represents an operating system's window.
     *
     * Typically, applications create a [Renderer] per window. The [Renderer] generates drawing
     * commands for the render thread and manages frame latency.
     */
    sharedRenderer: Renderer? = null,
    /**
     * Represents a virtual camera, which determines the perspective through which the scene is
     * viewed.
     *
     * All other functionality in Node is supported. You can access the position and rotation of the
     * camera, assign a collision shape to it, or add children to it.
     */
    sharedCameraNode: CameraNode? = null,
    /**
     * Always add a direct light source since it is required for shadowing.
     *
     * We highly recommend adding an [IndirectLight] as well.
     */
    sharedMainLightNode: LightNode? = null,
    /**
     * Defines the lighting environment and the skybox of the scene.
     *
     * Environments are usually captured as high-resolution HDR equirectangular images and processed
     * by the cmgen tool to generate the data needed by IndirectLight.
     *
     * You can also process an hdr at runtime but this is more consuming.
     *
     * - Currently IndirectLight is intended to be used for "distant probes", that is, to represent
     * global illumination from a distant (i.e. at infinity) environment, such as the sky or distant
     * mountains.
     * Only a single IndirectLight can be used in a Scene. This limitation will be lifted in the
     * future.
     *
     * - When added to a Scene, the Skybox fills all untouched pixels.
     *
     * @see [EnvironmentLoader]
     */
    sharedEnvironment: Environment? = null,
    /**
     * Controls whether the render target (SurfaceView) is opaque or not.
     * The render target is considered opaque by default.
     */
    isOpaque: Boolean = true,
    /**
     * Physics system to handle collision between nodes, hit testing on a nodes,...
     */
    sharedCollisionSystem: CollisionSystem? = null,
    /**
     * Helper that enables camera interaction similar to sketchfab or Google Maps.
     *
     * Needs to be a callable function because it can be reinitialized in case of viewport change
     * or camera node manual position changed.
     *
     * The first onTouch event will make the first manipulator build. So you can change the camera
     * position before any user gesture.
     *
     * Clients notify the camera manipulator of various mouse or touch events, then periodically
     * call its getLookAt() method so that they can adjust their camera(s). Three modes are
     * supported: ORBIT, MAP, and FREE_FLIGHT. To construct a manipulator instance, the desired mode
     * is passed into the create method.
     */
    cameraManipulator: CameraGestureDetector.CameraManipulator? =
        createDefaultCameraManipulator(sharedCameraNode?.worldPosition),
    /**
     * Used for Node's that can display an Android [View]
     *
     * Manages a [FrameLayout] that is attached directly to a [WindowManager] that other views can be
     * added and removed from.
     *
     * To render a [View], the [View] must be attached to a [WindowManager] so that it can be properly
     * drawn. This class encapsulates a [FrameLayout] that is attached to a [WindowManager] that other
     * views can be added to as children. This allows us to safely and correctly draw the [View]
     * associated with a [RenderableManager] [Entity] and a [MaterialInstance] while keeping them
     * isolated from the rest of the activities View hierarchy.
     *
     * Additionally, this manages the lifecycle of the window to help ensure that the window is
     * added/removed from the WindowManager at the appropriate times.
     */
    var viewNodeWindowManager: ViewNode2.WindowManager? = null,
    /**
     * The listener invoked for all the gesture detector callbacks.
     *
     * Responds to Android touch events with listeners.
     */
    onGestureListener: GestureDetector.OnGestureListener? = null,
    var onTouchEvent: ((e: MotionEvent, hitResult: HitResult?) -> Boolean)? = null,
    sharedActivity: ComponentActivity? = null,
    sharedLifecycle: Lifecycle? = null,
) : SurfaceView(context, attrs, defStyleAttr, defStyleRes) {

    /** ## Deprecated: Use [CameraGestureDetector.DefaultCameraManipulator]
     *
     * Replace `manipulator = Manipulator.Builder().build()` with
     * `cameraManipulator = CameraGestureDetector.DefaultCameraManipulator(manipulator =
     * Manipulator.Builder().build())`
     */
    @Deprecated("Use CameraGestureDetector.DefaultCameraManipulator")
    constructor(
        context: Context,
        attrs: AttributeSet? = null,
        defStyleAttr: Int = 0,
        defStyleRes: Int = 0,
        sharedEngine: Engine? = null,
        sharedModelLoader: ModelLoader? = null,
        sharedMaterialLoader: MaterialLoader? = null,
        sharedEnvironmentLoader: EnvironmentLoader? = null,
        sharedScene: Scene? = null,
        sharedView: View? = null,
        sharedRenderer: Renderer? = null,
        sharedCameraNode: CameraNode? = null,
        sharedMainLightNode: LightNode? = null,
        sharedEnvironment: Environment? = null,
        isOpaque: Boolean = true,
        sharedCollisionSystem: CollisionSystem? = null,
        manipulator: Manipulator,
        viewNodeWindowManager: ViewNode2.WindowManager? = null,
        onGestureListener: GestureDetector.OnGestureListener? = null,
        onTouchEvent: ((e: MotionEvent, hitResult: HitResult?) -> Boolean)? = null,
        sharedActivity: ComponentActivity? = null,
        sharedLifecycle: Lifecycle? = null,
    ) : this(
        context = context,
        attrs = attrs,
        defStyleAttr = defStyleAttr,
        defStyleRes = defStyleRes,
        sharedEngine = sharedEngine,
        sharedModelLoader = sharedModelLoader,
        sharedMaterialLoader = sharedMaterialLoader,
        sharedEnvironmentLoader = sharedEnvironmentLoader,
        sharedScene = sharedScene,
        sharedView = sharedView,
        sharedRenderer = sharedRenderer,
        sharedCameraNode = sharedCameraNode,
        sharedMainLightNode = sharedMainLightNode,
        sharedEnvironment = sharedEnvironment,
        isOpaque = isOpaque,
        sharedCollisionSystem = sharedCollisionSystem,
        cameraManipulator = CameraGestureDetector.createDefaultCameraManipulator(manipulator),
        viewNodeWindowManager = viewNodeWindowManager,
        onGestureListener = onGestureListener,
        onTouchEvent = onTouchEvent,
        sharedActivity = sharedActivity,
        sharedLifecycle = sharedLifecycle,
    )

    val engine = sharedEngine ?: createEglContext().let {
        defaultEglContext = it
        createEngine(it).also { defaultEngine = it }
    }

    val modelLoader = sharedModelLoader ?: createModelLoader(engine, context).also {
        defaultModelLoader = it
    }
    val materialLoader = sharedMaterialLoader ?: createMaterialLoader(engine, context).also {
        defaultMaterialLoader = it
    }

    /**
     * Utility for decoding an HDR file or consuming KTX1 files and producing Filament textures,
     * IBLs, and sky boxes.
     *
     * KTX is a simple container format that makes it easy to bundle miplevels and cubemap faces
     * into a single file.
     */
    val environmentLoader = sharedEnvironmentLoader
        ?: createEnvironmentLoader(engine, context).also { defaultEnvironmentLoader = it }

    /**
     * Defines the lighting environment and the skybox of the scene.
     *
     * Environments are usually captured as high-resolution HDR equirectangular images and processed
     * by the cmgen tool to generate the data needed by IndirectLight.
     *
     * You can also process an hdr at runtime but this is more consuming.
     *
     * - Currently IndirectLight is intended to be used for "distant probes", that is, to represent
     * global illumination from a distant (i.e. at infinity) environment, such as the sky or distant
     * mountains.
     * Only a single IndirectLight can be used in a Scene. This limitation will be lifted in the
     * future.
     *
     * - When added to a Scene, the Skybox fills all untouched pixels.
     *
     * @see [EnvironmentLoader]
     */
    var environment = sharedEnvironment ?: createEnvironment(environmentLoader, isOpaque)
        set(value) {
            if (field != value) {
                field = value
                indirectLight = environment.indirectLight
                skybox = environment.skybox
            }
        }

    val view = (sharedView ?: createView(engine).also { defaultView = it }).also { view ->
//        setOpaque(isOpaque)
        view.blendMode = if (isOpaque) BlendMode.OPAQUE else BlendMode.TRANSLUCENT
        view.scene = (sharedScene ?: createScene(engine).also { defaultScene = it }).also { scene ->
            scene.indirectLight = environment.indirectLight
            scene.skybox = environment.skybox
        }
    }
    var scene
        get() = view.scene!!
        set(value) {
            if (view.scene != value) {
                view.scene = value
            }
        }
    val renderer =
        (sharedRenderer ?: createRenderer(engine).also { defaultRenderer = it }).also { renderer ->
            if (!isOpaque) {
                // clear the swapchain with transparent pixels
                renderer.clearOptions = renderer.clearOptions.apply {
                    clear = !isOpaque
                }
            }
        }

    val uiHelper = UiHelper(UiHelper.ContextErrorPolicy.DONT_CHECK).also { uiHelper ->
        uiHelper.renderCallback = SurfaceCallback()
        uiHelper.isOpaque = isOpaque
        // Make the render target transparent
        uiHelper.attachTo(this@SceneView)
    }

    protected var _cameraNode: CameraNode? = null

    /**
     * Represents a virtual camera, which determines the perspective through which the scene is
     * viewed.
     *
     * All other functionality in Node is supported. You can access the position and rotation of the
     * camera, assign a collision shape to it, or add children to it. Disabling the camera turns off
     * rendering.
     */
    open val cameraNode: CameraNode get() = _cameraNode!!

    private var _mainLightNode: LightNode? =
        (sharedMainLightNode ?: createMainLightNode(engine).also { defaultMainLight = it })

    /**
     * Always add a direct light source since it is required for shadowing.
     *
     * We highly recommend adding an [IndirectLight] as well.
     */
    open var mainLightNode: LightNode?
        get() = _mainLightNode
        set(value) {
            if (_mainLightNode != value) {
                _mainLightNode?.let { removeNode(it) }
                _mainLightNode = value
                value?.let { addNode(it) }
            }
        }

    /**
     * IndirectLight is used to simulate environment lighting.
     *
     * Environment lighting has a two components:
     * - irradiance
     * - reflections (specular component)
     *
     * @see IndirectLight.Builder
     * @see EnvironmentLoader
     */
    open var indirectLight: IndirectLight?
        get() = scene.indirectLight
        set(value) {
            if (scene.indirectLight != value) {
                scene.indirectLight = value
            }
        }

    /**
     * The Skybox is drawn last and covers all pixels not touched by geometry.
     *
     * When added to a [SceneView], the `Skybox` fills all untouched pixels.
     *
     * The Skybox to use to fill untouched pixels, or null to unset the Skybox.
     *
     * @see Skybox.Builder
     * @see ModelLoader
     */
    var skybox: Skybox?
        get() = scene.skybox
        set(value) {
            if (scene.skybox != value) {
                scene.skybox = value
            }
        }

    var childNodes = listOf<Node>()
        set(value) {
            val removedNodes = (field - value.toSet())
            val addedNodes = (value - field.toSet())
            field = value.toList()
            removedNodes.forEach {
                removeNode(it)
            }
            addedNodes.forEach {
                addNode(it)
            }
        }

    /**
     * Inverts winding for front face rendering.
     *
     * Inverts the winding order of front faces. By default front faces use a counter-clockwise
     * winding order. When the winding order is inverted, front faces are faces with a clockwise
     * winding order.
     *
     * Changing the winding order will directly affect the culling mode in materials
     * (see [com.google.android.filament.Material.getCullingMode]).
     *
     * Inverting the winding order of front faces is useful when rendering mirrored reflections
     * (water, mirror surfaces, front camera in AR, etc.).
     *
     * `true` to invert front faces, false otherwise.
     */
    var isFrontFaceWindingInverted: Boolean
        get() = view.isFrontFaceWindingInverted
        set(value) {
            view.isFrontFaceWindingInverted = value
        }

    /**
     * Physics system to handle collision between nodes, hit testing on a nodes,...
     */
    val collisionSystem = (sharedCollisionSystem ?: createCollisionSystem(view).also {
        defaultCollisionSystem = it
    })

    /**
     * Invoked when an frame is processed.
     *
     * Registers a callback to be invoked when a valid Frame is processing.
     *
     * The callback to be invoked once per frame **immediately before the scene is updated.
     *
     * The callback will only be invoked if the Frame is considered as valid.
     */
    var onFrame: ((frameTimeNanos: Long) -> Unit)? = null

    /**
     * Detects various gestures and events.
     *
     * The gesture listener callback will notify users when a particular motion event has occurred.
     * Responds to Android touch events with listeners.
     */
    var gestureDetector: GestureDetector? =
        GestureDetector(context = context, listener = onGestureListener)
        private set

    /**
     * The listener invoked for all the gesture detector callbacks.
     *
     * Responds to Android touch events with listeners.
     */
    var onGestureListener: GestureDetector.OnGestureListener?
        get() = gestureDetector?.listener
        set(value) {
            gestureDetector?.listener = value
        }

    var cameraGestureDetector: CameraGestureDetector? =
        CameraGestureDetector(viewHeight = ::getHeight, cameraManipulator = cameraManipulator)
        private set

    /**
     * Helper that enables camera interaction similar to sketchfab or Google Maps.
     *
     * Needs to be a callable function because it can be reinitialized in case of viewport change
     * or camera node manual position changed.
     *
     * The first onTouch event will make the first manipulator build. So you can change the camera
     * position before any user gesture.
     *
     * Clients notify the camera manipulator of various mouse or touch events, then periodically
     * call its getLookAt() method so that they can adjust their camera(s). Three modes are
     * supported: ORBIT, MAP, and FREE_FLIGHT. To construct a manipulator instance, the desired mode
     * is passed into the create method.
     */
    var cameraManipulator: CameraGestureDetector.CameraManipulator?
        get() = cameraGestureDetector?.cameraManipulator
        set(value) {
            cameraGestureDetector?.cameraManipulator = value
        }

    protected open val activity: ComponentActivity? = sharedActivity
        get() = field ?: try {
            findFragment<Fragment>().requireActivity()
        } catch (e: Exception) {
            context as? ComponentActivity
        }

    open var lifecycle: Lifecycle? = sharedLifecycle
        set(value) {
            field?.removeObserver(lifecycleObserver)
            field = value
            value?.addObserver(lifecycleObserver)
        }

    protected var isDestroyed = false

    private val displayHelper = DisplayHelper(context)
    private var swapChain: SwapChain? = null
    private val lifecycleObserver = LifeCycleObserver()
    private val frameCallback = FrameCallback()

    private var lastTouchEvent: MotionEvent? = null
    private var surfaceMirrorer: SurfaceMirrorer? = null
    private var lastFrameTimeNanos: Long? = null

    private var defaultEglContext: EGLContext? = null
    private var defaultEngine: Engine? = null
    private var defaultScene: Scene? = null
    private var defaultView: View? = null
    private var defaultRenderer: Renderer? = null
    private var defaultModelLoader: ModelLoader? = null
    private var defaultMaterialLoader: MaterialLoader? = null
    private var defaultEnvironmentLoader: EnvironmentLoader? = null
    private var defaultCollisionSystem: CollisionSystem? = null
    private var defaultCameraNode: CameraNode? = null
    private var defaultMainLight: LightNode? = null

    init {
        _mainLightNode?.let { addNode(it) }

        setCameraNode(sharedCameraNode ?: createCameraNode(engine).also {
            defaultCameraNode = it
        })

        lifecycle?.addObserver(lifecycleObserver)

        keepScreenOn = true
    }

    /**
     * Sets this View's Camera.
     *
     * This method associates the specified Camera with this View. A Camera can be associated with
     * several View instances. To remove an existing association, simply pass null.
     *
     * The View does not take ownership of the Scene pointer. Before destroying a Camera, be sure
     * to remove it from all associated Views.
     */
    fun setCameraNode(cameraNode: CameraNode) {
        if (_cameraNode != cameraNode) {
            _cameraNode?.collisionSystem = null
            _cameraNode = cameraNode
            cameraNode.collisionSystem = collisionSystem
            cameraNode.setView(view)
            view.camera = cameraNode.camera
        }
    }

    /**
     * Add a node to the [Scene] as a direct child.
     *
     * If the node is already in the scene, no change is made.
     *
     * @param node the node to add as a child
     * @throws IllegalArgumentException if the child is the same object as the parent, or if the
     * parent is a descendant of the child
     */
    fun addChildNode(node: Node) {
        childNodes = childNodes + node
    }

    /**
     * Add multiple nodes to the [Scene] as a direct child.
     *
     * If the nodes are already in the scene, no change is made.
     *
     * @param nodes the nodes to add as children
     * @throws IllegalArgumentException if the child is the same object as the parent, or if the
     * parent is a descendant of the child
     */
    fun addChildNodes(nodes: List<Node>) {
        childNodes = childNodes + nodes
    }

    /**
     * Removes a node from the children of this [Scene].
     *
     * If the node is not in the scene, no change is made.
     *
     * @param node the node to remove from the children
     */
    fun removeChildNode(node: Node) {
        childNodes = childNodes - node
    }

    /**
     * Removes multiple nodes from the children of this [Scene].
     *
     * If the nodes are not in the scene, no change is made.
     *
     * @param nodes the nodes to remove from the children
     */
    fun removeChildNodes(nodes: List<Node>) {
        childNodes = childNodes - nodes
    }

    /**
     * Removes all nodes from the children of this [Scene].
     */
    fun clearChildNodes() {
        childNodes = listOf()
    }

    fun startMirroring(
        surface: Surface,
        left: Int = 0,
        bottom: Int = 0,
        width: Int = this.width,
        height: Int = this.height
    ) {
        if (surfaceMirrorer == null) {
            surfaceMirrorer = SurfaceMirrorer()
        }
        surfaceMirrorer?.startMirroring(this, surface, left, bottom, width, height)
    }

    fun stopMirroring(surface: Surface) {
        surfaceMirrorer?.stopMirroring(this, surface)
        surfaceMirrorer = null
    }

    fun startRecording(mediaRecorder: MediaRecorder) {
        mediaRecorder.apply {
            setVideoSource(MediaRecorder.VideoSource.SURFACE)
        }
        mediaRecorder.prepare()
        mediaRecorder.start()
        startMirroring(mediaRecorder.surface)
    }

    fun stopRecording(mediaRecorder: MediaRecorder) {
        stopMirroring(mediaRecorder.surface)
        mediaRecorder.stop()
        mediaRecorder.reset()
        mediaRecorder.surface.release()
    }

    /**
     * Force destroy.
     *
     * You don't have to call this method because everything is already lifecycle aware.
     * Meaning that they are already self destroyed when they receive the `onDestroy()` callback.
     */
    open fun destroy() {
        if (!isDestroyed) {
            lifecycle = null
            Choreographer.getInstance().removeFrameCallback(frameCallback)

            runCatching { uiHelper.detach() }

            defaultCameraNode?.destroy()
            defaultMainLight?.destroy()

//        runCatching { ResourceManager.getInstance().destroyAllResources() }

            // Set before the engine goes: it may only go once its backend is idle, and the view is
            // destroyed for every caller from here on. A second destroy() has nothing left to do.
            isDestroyed = true

            val ownEngine = defaultEngine
            val destroyEngineResources = engineResourcesDestroyer()
            val eglContext = defaultEglContext
            if (ownEngine == null) {
                // A shared engine belongs to whoever created it: nothing is deferred, its owner is
                // free to destroy it as soon as this returns.
                destroyEngineResources()
                eglContext?.let { OpenGL.destroyEglContext(it) }
            } else {
                // Engine.destroy() joins the driver thread once it has run everything queued, and
                // Engine.destroyRenderer() waits for that same backlog. On the main thread, with a
                // backend that is behind, that wait is an ANR: it is polled instead, and the same
                // calls run in the same order once there is nothing left to wait for. With an idle
                // backend - the usual case - all of it still happens before destroy() returns.
                //
                // The loads in flight stop now, as they always did by the time destroy() returned:
                // no result is delivered to the app for a view it has already destroyed.
                defaultMaterialLoader?.cancelLoads()
                defaultModelLoader?.cancelLoads()
                val startedAt = SystemClock.uptimeMillis()
                // Deferred, this block waits in the main looper's queue: it references the engine
                // and what is destroyed with it, not this view.
                ownEngine.whenBackendIdle { deferred ->
                    // Not valid: destroyed by the app in the meantime, and what lived on it with it.
                    if (ownEngine.isValid) {
                        destroyEngineResources()
                        ownEngine.safeDestroy()
                        if (deferred) {
                            Log.i(
                                "Sceneview",
                                "Engine destroyed once its backend was idle, " +
                                        "${SystemClock.uptimeMillis() - startedAt} ms after the view"
                            )
                        }
                    }
                    // The EGL context is shared with the engine: always released after it.
                    if (!deferred) {
                        eglContext?.let { OpenGL.destroyEglContext(it) }
                    } else {
                        // Nobody is left to catch it here. A try and not runCatching, which in a
                        // class is the extension on `this` and would make the block hold the view.
                        try {
                            eglContext?.let { OpenGL.destroyEglContext(it) }
                        } catch (e: Exception) {
                            Log.w("Sceneview", "EGL context not destroyed", e)
                        }
                    }
                }
            }
        }
    }

    /**
     * What destroys what this view created on its [engine], which must still be alive then: the
     * renderer first, the loaders last.
     *
     * It holds those objects and not this view, so that a teardown waiting for the backend does
     * not keep the view. The loaders still reference the [Context] they were created with.
     */
    private fun engineResourcesDestroyer(): () -> Unit {
        val engine = engine
        val renderer = defaultRenderer
        val view = defaultView
        val scene = defaultScene
        val environmentLoader = defaultEnvironmentLoader
        val materialLoader = defaultMaterialLoader
        val modelLoader = defaultModelLoader
        return {
            renderer?.let { engine.safeDestroyRenderer(it) }
            view?.let { engine.safeDestroyView(it) }
            scene?.let { engine.safeDestroyScene(it) }
            environmentLoader?.destroy()
            materialLoader?.let { engine.safeDestroyMaterialLoader(it) }
            modelLoader?.let { engine.safeDestroyModelLoader(it) }
        }
    }

    /**
     * Callback that occurs for each display frame. Updates the scene and reposts itself to be
     * called by the choreographer on the next frame.
     *
     * @param frameTimeNanos time in nanoseconds when the frame started being rendered,
     * Typically comes from [Choreographer.FrameCallback]
     */
    protected open fun onFrame(frameTimeNanos: Long) {
        modelLoader.updateLoad()

        childNodes.forEach { it.onFrame(frameTimeNanos) }

        if (uiHelper.isReadyToRender) {
//            transformManager.openLocalTransformTransaction()

            // Only update the camera manipulator if a touch has been made
            cameraManipulator?.let { manipulator ->
                if (lastTouchEvent != null) {
                    manipulator.update(frameTimeNanos.intervalSeconds(lastFrameTimeNanos).toFloat())
                    // Extract the camera basis from the helper and push it to the Filament camera.
                    cameraNode.transform = manipulator.getTransform()
                }
            }

            onFrame?.invoke(frameTimeNanos)

//            transformManager.commitLocalTransformTransaction()

            // Render the scene, unless the renderer wants to skip the frame.
            if (renderer.beginFrame(swapChain!!, frameTimeNanos)) {
                renderer.render(view)
                surfaceMirrorer?.onFrame(this)
                renderer.endFrame()
            }
        }

        lastFrameTimeNanos = frameTimeNanos
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()

        if (lifecycle == null) {
            lifecycle = runCatching { findViewTreeLifecycleOwner()?.lifecycle }.getOrNull()
        }
    }

    override fun onDetachedFromWindow() {
        if (!isDestroyed) {
            destroy()
        }
        super.onDetachedFromWindow()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        // This makes sure that the view's onTouchListener is called.
        if (!super.onTouchEvent(event)) {
            lastTouchEvent = event
            val hitResult = collisionSystem.hitTest(event).firstOrNull {
                it.node.isTouchable
            }
            if (onTouchEvent?.invoke(event, hitResult) != true &&
                hitResult?.node?.onTouchEvent(event, hitResult) != true
            ) {
                gestureDetector?.onTouchEvent(event, hitResult)
                cameraGestureDetector?.onTouchEvent(event)
            }

            return true
        }
        return false
    }

    protected open fun onResized(width: Int, height: Int) {
        view.viewport = Viewport(0, 0, width, height)
        cameraManipulator?.setViewport(width, height)
        cameraNode.updateProjection()
    }

    internal fun addNode(node: Node) {
        node.collisionSystem = collisionSystem
        if (node.sceneEntities.isNotEmpty()) {
            scene.addEntities(node.sceneEntities.toIntArray())
        }
        node.onChildAdded += ::addNode
        node.onChildRemoved += ::removeNode
        node.onAddedToScene(scene)
        node.childNodes.forEach { addNode(it) }
    }

    internal fun removeNode(node: Node) {
        node.collisionSystem = null
        if (node.sceneEntities.isNotEmpty()) {
            scene.removeEntities(node.sceneEntities.toIntArray())
        }
        node.onChildAdded -= ::addNode
        node.onChildRemoved -= ::removeNode
        node.onRemovedFromScene(scene)
        node.childNodes.forEach { removeNode(it) }
    }

    internal fun replaceNode(oldNode: Node?, newNode: Node?) {
        oldNode?.let { removeNode(it) }
        newNode?.let { addNode(it) }
    }

    fun setOnGestureListener(
        onDown: (e: MotionEvent, node: Node?) -> Unit = { _, _ -> },
        onShowPress: (e: MotionEvent, node: Node?) -> Unit = { _, _ -> },
        onSingleTapUp: (e: MotionEvent, node: Node?) -> Unit = { _, _ -> },
        onScroll: (e1: MotionEvent?, e2: MotionEvent, node: Node?, distance: Float2) -> Unit = { _, _, _, _ -> },
        onLongPress: (e: MotionEvent, node: Node?) -> Unit = { _, _ -> },
        onFling: (e1: MotionEvent?, e2: MotionEvent, node: Node?, velocity: Float2) -> Unit = { _, _, _, _ -> },
        onSingleTapConfirmed: (e: MotionEvent, node: Node?) -> Unit = { _, _ -> },
        onDoubleTap: (e: MotionEvent, node: Node?) -> Unit = { _, _ -> },
        onDoubleTapEvent: (e: MotionEvent, node: Node?) -> Unit = { _, _ -> },
        onContextClick: (e: MotionEvent, node: Node?) -> Unit = { _, _ -> },
        onMoveBegin: (detector: MoveGestureDetector, e: MotionEvent, node: Node?) -> Unit = { _, _, _ -> },
        onMove: (detector: MoveGestureDetector, e: MotionEvent, node: Node?) -> Unit = { _, _, _ -> },
        onMoveEnd: (detector: MoveGestureDetector, e: MotionEvent, node: Node?) -> Unit = { _, _, _ -> },
        onRotateBegin: (detector: RotateGestureDetector, e: MotionEvent, node: Node?) -> Unit = { _, _, _ -> },
        onRotate: (detector: RotateGestureDetector, e: MotionEvent, node: Node?) -> Unit = { _, _, _ -> },
        onRotateEnd: (detector: RotateGestureDetector, e: MotionEvent, node: Node?) -> Unit = { _, _, _ -> },
        onScaleBegin: (detector: ScaleGestureDetector, e: MotionEvent, node: Node?) -> Unit = { _, _, _ -> },
        onScale: (detector: ScaleGestureDetector, e: MotionEvent, node: Node?) -> Unit = { _, _, _ -> },
        onScaleEnd: (detector: ScaleGestureDetector, e: MotionEvent, node: Node?) -> Unit = { _, _, _ -> }
    ) {
        onGestureListener = object : GestureDetector.OnGestureListener {
            override fun onDown(e: MotionEvent, node: Node?) = onDown(e, node)
            override fun onShowPress(e: MotionEvent, node: Node?) = onShowPress(e, node)
            override fun onSingleTapUp(e: MotionEvent, node: Node?) = onSingleTapUp(e, node)
            override fun onScroll(
                e1: MotionEvent?,
                e2: MotionEvent,
                node: Node?,
                distance: Float2
            ) = onScroll(e1, e2, node, distance)

            override fun onLongPress(e: MotionEvent, node: Node?) = onLongPress(e, node)
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, node: Node?, velocity: Float2) =
                onFling(e1, e2, node, velocity)

            override fun onSingleTapConfirmed(e: MotionEvent, node: Node?) =
                onSingleTapConfirmed(e, node)

            override fun onDoubleTap(e: MotionEvent, node: Node?) = onDoubleTap(e, node)
            override fun onDoubleTapEvent(e: MotionEvent, node: Node?) = onDoubleTapEvent(e, node)
            override fun onContextClick(e: MotionEvent, node: Node?) = onContextClick(e, node)
            override fun onMoveBegin(detector: MoveGestureDetector, e: MotionEvent, node: Node?) =
                onMoveBegin(detector, e, node)

            override fun onMove(detector: MoveGestureDetector, e: MotionEvent, node: Node?) =
                onMove(detector, e, node)

            override fun onMoveEnd(detector: MoveGestureDetector, e: MotionEvent, node: Node?) =
                onMoveEnd(detector, e, node)

            override fun onRotateBegin(
                detector: RotateGestureDetector,
                e: MotionEvent,
                node: Node?
            ) = onRotateBegin(detector, e, node)

            override fun onRotate(detector: RotateGestureDetector, e: MotionEvent, node: Node?) =
                onRotate(detector, e, node)

            override fun onRotateEnd(detector: RotateGestureDetector, e: MotionEvent, node: Node?) =
                onRotateEnd(detector, e, node)

            override fun onScaleBegin(detector: ScaleGestureDetector, e: MotionEvent, node: Node?) =
                onScaleBegin(detector, e, node)

            override fun onScale(detector: ScaleGestureDetector, e: MotionEvent, node: Node?) =
                onScale(detector, e, node)

            override fun onScaleEnd(detector: ScaleGestureDetector, e: MotionEvent, node: Node?) =
                onScaleEnd(detector, e, node)
        }
    }

    private inner class LifeCycleObserver : DefaultLifecycleObserver {
        override fun onResume(owner: LifecycleOwner) {

            viewNodeWindowManager?.resume(this@SceneView)

            // Start the drawing when the renderer is resumed.  Remove and re-add the callback
            // to avoid getting called twice.
            Choreographer.getInstance().removeFrameCallback(frameCallback)
            Choreographer.getInstance().postFrameCallback(frameCallback)
        }

        override fun onPause(owner: LifecycleOwner) {
            Choreographer.getInstance().removeFrameCallback(frameCallback)

            viewNodeWindowManager?.pause()
        }

        override fun onDestroy(owner: LifecycleOwner) {
            viewNodeWindowManager?.destroy()
            destroy()
        }
    }

    private inner class FrameCallback : Choreographer.FrameCallback {
        override fun doFrame(timestamp: Long) {
            // Always post the callback for the next frame.
            Choreographer.getInstance().postFrameCallback(this)

            onFrame(timestamp)
        }
    }

    private inner class SurfaceCallback : UiHelper.RendererCallback {
        override fun onNativeWindowChanged(surface: Surface) {
            swapChain?.let { runCatching { engine.destroySwapChain(it) } }
            swapChain = engine.createSwapChain(surface)
            displayHelper.attach(renderer, display)
        }

        override fun onDetachedFromSurface() {
            displayHelper.detach()
            swapChain?.let {
                runCatching { engine.destroySwapChain(it) }
                // Android takes the surface back when this returns, so the backend is given the
                // time to finish with it - but not forever: this is the main thread, and a backend
                // that needs longer than the bound is an ANR. Past it the queued commands still
                // run, in order; a frame that reaches a surface already gone is dropped by EGL.
                if (!engine.flushAndWait(SURFACE_DETACH_WAIT_NANOS)) {
                    Log.w(
                        "Sceneview",
                        "Surface detached, backend still busy after " +
                                "${SURFACE_DETACH_WAIT_NANOS / 1_000_000} ms; not waiting longer"
                    )
                }
                swapChain = null
            }
        }

        override fun onResized(width: Int, height: Int) {
            this@SceneView.onResized(width, height)

            // Wait for the pending frames to be processed before returning, to avoid a race
            // between the surface being resized and the frames still to be rendered into it — but
            // not forever: this is the main thread. A frame that is later than the bound reaches
            // the surface at its former size; the next one is rendered at the new one.
            val fence = engine.createFence()
            if (isFenceBusy(fence, SURFACE_RESIZE_WAIT_NANOS)) {
                Log.w(
                    "Sceneview",
                    "Surface resized, backend still busy after " +
                            "${SURFACE_RESIZE_WAIT_NANOS / 1_000_000} ms; not waiting longer"
                )
            }
            engine.destroyFence(fence)
        }
    }

    class DefaultCameraNode(engine: Engine) : CameraNode(engine) {
        init {
            transform = Transform(position = Position(0.0f, 0.0f, 1.0f))
            // Set the exposure on the camera, this exposure follows the sunny f/16 rule
            // Since we define a light that has the same intensity as the sun, it guarantees a
            // proper exposure
            setExposure(16.0f, 1.0f / 125.0f, 100.0f)
        }
    }

    class DefaultLightNode(engine: Engine) : LightNode(
        engine = engine,
        type = LightManager.Type.DIRECTIONAL,
        apply = {
            color(DEFAULT_MAIN_LIGHT_COLOR)
            intensity(DEFAULT_MAIN_LIGHT_COLOR_INTENSITY)
            direction(0.0f, -1.0f, 0.0f)
            castShadows(true)
        })

    companion object {

        init {
            Gltfio.init()
            Filament.init()
            Utils.init()
        }

        const val DEFAULT_MAIN_LIGHT_COLOR_TEMPERATURE = 6_500.0f
        const val DEFAULT_MAIN_LIGHT_COLOR_INTENSITY = 100_000.0f

        val DEFAULT_MAIN_LIGHT_COLOR = Colors.cct(DEFAULT_MAIN_LIGHT_COLOR_TEMPERATURE).toColor()
        val DEFAULT_MAIN_LIGHT_INTENSITY = DEFAULT_MAIN_LIGHT_COLOR_INTENSITY

        val DEFAULT_OBJECT_POSITION = Position(0.0f, 0.0f, -4.0f)

        fun createEglContext() = OpenGL.createEglContext()
        fun createEngine(eglContext: EGLContext) = Engine.create(eglContext)

        fun createScene(engine: Engine) = engine.createScene()

        fun createView(engine: Engine) =
            engine.createView().apply {
                // On mobile, better use lower quality color buffer
                renderQuality = renderQuality.apply {
                    hdrColorBuffer = QualityLevel.MEDIUM
                }
                // Dynamic resolution often helps a lot
                dynamicResolutionOptions = dynamicResolutionOptions.apply {
                    // Disabled cause generating some camera stream wrong scaling ratio
                    enabled = false
                    homogeneousScaling = true
                    quality = QualityLevel.MEDIUM
                }

                // MSAA is needed with dynamic resolution MEDIUM
                multiSampleAntiAliasingOptions = multiSampleAntiAliasingOptions.apply {
                    enabled = false
                }

                // FXAA is pretty cheap and helps a lot
                antiAliasing = AntiAliasing.FXAA
                // Ambient occlusion is the cheapest effect that adds a lot of quality
                ambientOcclusionOptions = ambientOcclusionOptions.apply {
                    enabled = false
                }
                // Bloom is pretty expensive but adds a fair amount of realism
//                bloomOptions = bloomOptions.apply {
//                    enabled = true
//                }
                // Change the ToneMapper to FILMIC to avoid some over saturated colors, for example
                // material orange 500.
                colorGrading = ColorGrading.Builder()
                    .toneMapper(ToneMapper.Filmic())
                    .build(engine)
                setShadowingEnabled(false)
            }

        fun createRenderer(engine: Engine) = engine.createRenderer()

        fun createModelLoader(engine: Engine, context: Context) = engine.createModelLoader(context)
        fun createMaterialLoader(engine: Engine, context: Context) =
            engine.createMaterialLoader(context)

        fun createEnvironmentLoader(engine: Engine, context: Context) =
            engine.createEnvironmentLoader(context)

        fun createCameraNode(engine: Engine): CameraNode = DefaultCameraNode(engine)

        fun createDefaultCameraManipulator(
            orbitHomePosition: Position? = null,
            targetPosition: Position? = null
        ) = CameraGestureDetector.DefaultCameraManipulator(
            orbitHomePosition = orbitHomePosition,
            targetPosition = targetPosition
        )

        fun createViewNodeManager(context: Context) = ViewNode2.WindowManager(context)

        fun createMainLightNode(engine: Engine): LightNode = DefaultLightNode(engine)

        fun createEnvironment(environmentLoader: EnvironmentLoader, isOpaque: Boolean = true) =
            createEnvironment(
                engine = environmentLoader.engine,
                isOpaque = isOpaque,
                indirectLight = KTX1Loader.createIndirectLight(
                    environmentLoader.engine,
                    environmentLoader.context.assets.readBuffer(
                        fileLocation = "environments/neutral/neutral_ibl.ktx"
                    ),
                ).indirectLight,
            )

        fun createEnvironment(
            engine: Engine,
            isOpaque: Boolean = true,
            indirectLight: IndirectLight? = null,
            skybox: Skybox? = Skybox.Builder()
                .color(colorOf(rgb = 0.0f, a = if (isOpaque) 1.0f else 0.0f).toFloatArray())
                .build(engine),
            sphericalHarmonics: List<Float>? = null
        ) = Environment(indirectLight, skybox, sphericalHarmonics)

        fun createCollisionSystem(view: View) = CollisionSystem(view)
    }
}
