package io.github.sceneview

import androidx.annotation.RestrictTo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import com.google.android.filament.Scene
import java.util.WeakHashMap

/**
 * How long frames keep being drawn after the last invalidation.
 *
 * Same reason as the web SDK's `RenderGate` settle window: Filament finalises texture uploads, IBL
 * prefiltering and shadow map work across several frames after the change that triggered them, so a
 * gate that stops on the first clean frame leaves an untextured model — or an unlit one — on screen
 * until something else happens to wake it. Half a second is cheap once, and it is the difference
 * between "render-on-demand" and "render-on-demand, mostly".
 *
 * **A duration, not a frame count.** The work being waited on is Filament's, and Filament's is
 * measured in wall-clock time — a texture upload does not get faster because the panel refreshes
 * faster. A budget of 30 *frames* was half a second on a 60 Hz panel and a quarter of a second on a
 * 120 Hz one, so the newer and faster the device, the shorter the tail it got; and with a
 * [FrameRatePolicy.maxFps] cap in play, 30 frames at 5 fps would have been a six-second tail. The
 * clock that drives it is the render loop's own `withFrameNanos` timestamp, so it stays testable on
 * a virtual clock and never reads the system clock behind the caller's back.
 */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP_PREFIX)
const val SETTLE_DURATION_NANOS: Long = 500_000_000L

/**
 * Presented frames the settle window owes after the last change, however long they take.
 *
 * [SETTLE_DURATION_NANOS] alone is a run of frames only while frames are faster than it. On a GPU
 * still linking a model's materials each frame takes over a second (1.5 s on `emulator-5554`), so
 * the very first frame presented after a change already lands past the deadline and closes the
 * window. The scene then parks on that one frame, which is exactly the "first frame after a change"
 * the window exists not to stop on. A readiness signal that needs a second frame (a loading cover)
 * never gets it, and the scene stays frozen on whatever that frame drew until a touch wakes it
 * (#3982). Two frames is the floor: Filament refuses a new frame while the backend is behind, so an
 * accepted second frame is evidence that the first was executed.
 */
internal const val SETTLE_MIN_PRESENTED_FRAMES: Int = 2

/**
 * Decides, once per frame, whether the GPU submit happens — the Android port of the web SDK's
 * `RenderGate` (`sceneview-web/src/jsMain/kotlin/io/github/sceneview/web/RenderGate.kt`).
 *
 * Two safety properties are deliberate, and both err towards drawing:
 *
 * - It starts **dirty**, so the very first frame after attach is always presented.
 * - Any doubt re-arms the whole settle budget rather than shortening it.
 *
 * The gate never decides to *stop the loop*; it only answers "submit this tick?". Parking the
 * coroutine is [SceneView]'s decision, taken only when [isSettled] reads `true` — i.e. the budget is
 * spent **and** nothing is pending. That split is what keeps `session.update()`, `updateLoad()` and
 * the node ticks running on a tick whose GPU work was skipped.
 *
 * Not thread-safe by design: every caller is the main/render thread.
 *
 * Library-group API, not app API: `arsceneview` builds one too, so that a virtual-content change
 * landing between two ARCore camera images is presented rather than waiting for the sensor.
 */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP_PREFIX)
class FrameRateGate @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP_PREFIX) constructor(
    private val settleDurationNanos: Long = SETTLE_DURATION_NANOS
) {

    // Snapshot state, because parking reads it through `derivedStateOf` and a plain field would
    // never wake the suspended loop (#3108: the park must be woken by a snapshot apply, not polled).
    private val dirtyState = mutableStateOf(true)

    /**
     * The frame time at which the settle window closes. Only meaningful while [owesSettle] is
     * `true`, which is why the gate does not need a sentinel for "no tick seen yet": it starts owing
     * frames, and the first [shouldRender] sets the deadline from the first real frame time.
     */
    private var settleUntilNanos: Long = 0L

    /**
     * Whether the settle window is still open. A plain field, deliberately — the park site reads it
     * outside `withFrameNanos` and the loop is awake by definition for as long as it is `true`, so
     * making it snapshot state would apply a snapshot per frame on every rendering scene, which is
     * the exact per-frame cost this whole design exists to remove.
     */
    private var owesSettle: Boolean = true

    /** Frames presented since the window was last re-armed. See [SETTLE_MIN_PRESENTED_FRAMES]. */
    private var presentedSinceArm: Int = 0

    /** `true` while an invalidation is waiting to be consumed by the next [shouldRender]. */
    val isDirty: Boolean get() = dirtyState.value

    /** `true` when the window is closed and nothing is pending — the only state safe to park in. */
    val isSettled: Boolean get() = !owesSettle && !dirtyState.value

    /**
     * Marks the scene changed. Safe to call from any of the push sources, including several times
     * per frame — it is idempotent until the next [shouldRender] consumes it.
     */
    fun requestRender() {
        // Guarded write: a scene that invalidates every frame (a playing animation) would otherwise
        // publish a snapshot apply per frame, waking every observer of this state for nothing.
        if (!dirtyState.value) dirtyState.value = true
    }

    /**
     * Answers "submit this tick?", and re-arms the settle window when [active] or a pending
     * invalidation says the picture is still moving.
     *
     * @param active whether any *pull* source is live this tick — a gesture in flight, a coasting
     * manipulator, a playing animation, an async load, a recording. Continuous activity therefore
     * keeps the window open and renders continuously, without a per-frame [requestRender].
     * @param frameTimeNanos the loop's frame time for this tick (`withFrameNanos`). It is the only
     * clock the settle window uses, so a test drives it with whatever cadence it wants to model.
     */
    fun shouldRender(active: Boolean, frameTimeNanos: Long): Boolean {
        if (dirtyState.value || active) {
            settleUntilNanos = frameTimeNanos + settleDurationNanos
            owesSettle = true
            presentedSinceArm = 0
            if (dirtyState.value) dirtyState.value = false
        }
        return owesSettle
    }

    /**
     * Closes the settle window once [frameTimeNanos] has reached its deadline and at least
     * [SETTLE_MIN_PRESENTED_FRAMES] frames were presented since the last change. Called only when a
     * frame really reached the surface: it is presented frames that carry Filament's upload and
     * prefiltering work, so a window closed on ticks that drew nothing would not have waited for
     * anything.
     */
    fun didRender(frameTimeNanos: Long) {
        if (!owesSettle) return
        if (presentedSinceArm < SETTLE_MIN_PRESENTED_FRAMES) presentedSinceArm++
        if (frameTimeNanos >= settleUntilNanos && presentedSinceArm >= SETTLE_MIN_PRESENTED_FRAMES) {
            owesSettle = false
        }
    }
}

/**
 * Wakes a [FrameRatePolicy.OnDemand] scene that changed in a way the library cannot observe.
 *
 * Everything the SDK owns — node transforms, animations, gestures, loads, surface changes — already
 * invalidates on its own. This is the escape hatch for the rest: a Filament material parameter or
 * light property written directly, an external simulation stepping the scene, or a `PixelCopy` or
 * screenshot that needs a fresh frame on the surface first — request it, then wait for your next
 * `onFrame` before reading the surface, because the request itself is fire-and-forget and there is
 * no "await one frame" API to hand you.
 *
 * ```kotlin
 * val invalidator = rememberRenderInvalidator()
 * SceneView(renderInvalidator = invalidator, modifier = Modifier.fillMaxSize()) { … }
 *
 * // later, after writing straight into Filament:
 * materialInstance.setParameter("baseColorFactor", color)
 * invalidator.requestRender()
 * ```
 *
 * Calls made before the view is attached are remembered and applied on attach, so an invalidation
 * racing composition is never lost.
 */
class RenderInvalidator {

    // Volatile, unlike [FrameRateGate]'s own state: this is the *public* escape hatch, and the
    // migration guide points it at "a texture updated off-thread". The visibility of the handoff
    // is therefore guaranteed here; what still has to happen on the main thread is the gate's own
    // `requestRender`, which is why the KDoc below says so rather than leaving it to be found.
    @Volatile
    private var gate: FrameRateGate? = null

    @Volatile
    private var pending: Boolean = false

    /**
     * Requests one more rendered frame. Cheap, idempotent, safe to over-call.
     *
     * **Call it on the main thread.** A background thread that finished a texture upload or a
     * simulation step should post here rather than call across — the gate it forwards to is the
     * render loop's own state and is not thread-safe. The request itself is never lost either way:
     * an invalidator that has not been attached yet remembers one and replays it on attach.
     */
    fun requestRender() {
        val currentGate = gate
        if (currentGate != null) currentGate.requestRender() else pending = true
    }

    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP_PREFIX)
    fun attach(gate: FrameRateGate) {
        this.gate = gate
        if (pending) {
            pending = false
            gate.requestRender()
        }
    }

    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP_PREFIX)
    fun detach(gate: FrameRateGate) {
        if (this.gate === gate) this.gate = null
    }
}

/**
 * Remembers a [RenderInvalidator] for the current composition, to pass to
 * [SceneView]`(renderInvalidator = …)`.
 */
@Composable
fun rememberRenderInvalidator(): RenderInvalidator = remember { RenderInvalidator() }

/**
 * Maps a Filament [Scene] to the [RenderInvalidator] of the view rendering it, so a
 * [io.github.sceneview.node.Node] can reach its view's gate from
 * [io.github.sceneview.node.Node.attachedScene] alone — the one link every managed node has,
 * including the sub-nodes a glTF hierarchy creates.
 *
 * Mirrors `EngineDestroyQueue.of(engine)`: a [WeakHashMap] keyed on the Filament object, so a
 * destroyed scene's entry disappears with it and no view is kept alive by this registry.
 *
 * **Several views per scene (#3723).** `SceneView(scene = …)` is a public parameter, so two views
 * — or an `ARSceneView` and a `SceneView` — may render one Filament [Scene]. Each registers its own
 * invalidator here, [requestRender] wakes all of them, and [unregister] with an invalidator removes
 * only that one, so the view that stays keeps waking on node changes.
 */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP_PREFIX)
object SceneRenderInvalidators {

    private val registry = InvalidatorRegistry<Scene>()

    /** Adds [invalidator] to the views woken for [scene]. Registering it twice is a no-op. */
    fun register(scene: Scene, invalidator: RenderInvalidator) = registry.register(scene, invalidator)

    /** Removes [invalidator] from [scene] and leaves any other view on the same scene registered. */
    fun unregister(scene: Scene, invalidator: RenderInvalidator) = registry.unregister(scene, invalidator)

    /**
     * Removes **every** view registered for [scene].
     *
     * Kept for binary compatibility. A view leaving composition must call
     * `unregister(scene, invalidator)` instead: this overload also silences any other view still
     * rendering the same scene (#3723).
     */
    fun unregister(scene: Scene) = registry.unregisterAll(scene)

    /** Wakes every view rendering [scene]. A no-op when none is registered. */
    fun requestRender(scene: Scene) = registry.requestRender(scene)

    /**
     * One of the invalidators registered for [scene] — the most recently registered — or `null`.
     *
     * With several views on one scene it reaches only one of them; use [requestRender] to wake
     * them all.
     */
    fun of(scene: Scene): RenderInvalidator? = registry.latest(scene)
}

/**
 * The storage behind [SceneRenderInvalidators], generic over the key so it can be tested on the
 * JVM without a native Filament [Scene].
 *
 * Keys are held weakly ([WeakHashMap]): a destroyed scene never retains a view. Invalidators are
 * compared by identity, in registration order.
 *
 * The per-key lists are immutable and replaced on every (rare) register / unregister, so the hot
 * path — [requestRender], called on every node transform change — reads one reference under the
 * lock and allocates nothing.
 */
internal class InvalidatorRegistry<K : Any> {

    private val invalidators = WeakHashMap<K, List<RenderInvalidator>>()

    @Synchronized
    fun register(key: K, invalidator: RenderInvalidator) {
        val views = invalidators[key].orEmpty()
        if (views.none { it === invalidator }) invalidators[key] = views + invalidator
    }

    @Synchronized
    fun unregister(key: K, invalidator: RenderInvalidator) {
        val views = invalidators[key] ?: return
        val remaining = views.filterNot { it === invalidator }
        if (remaining.isEmpty()) invalidators.remove(key) else invalidators[key] = remaining
    }

    @Synchronized
    fun unregisterAll(key: K) {
        invalidators.remove(key)
    }

    @Synchronized
    fun latest(key: K): RenderInvalidator? = invalidators[key]?.lastOrNull()

    @Synchronized
    fun registered(key: K): List<RenderInvalidator> = invalidators[key].orEmpty()

    /** Requests a frame from every registered view, outside the lock. */
    fun requestRender(key: K) {
        val views = registered(key)
        for (i in views.indices) views[i].requestRender()
    }
}
