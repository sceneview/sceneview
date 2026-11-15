package io.github.sceneview.node

import androidx.annotation.RestrictTo
import com.google.android.filament.IndirectLight
import com.google.android.filament.Scene
import dev.romainguy.kotlin.math.length
import io.github.sceneview.math.Position
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/**
 * Sets the scene's fallback IBL without replacing an active [ReflectionProbeNode] (#4939).
 *
 * Shared by `SceneView` and `ARSceneView`: an AR estimate replaces the baseline fallback,
 * while an active probe keeps its own IBL unchanged. Main thread only; the caller owns [light].
 */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP_PREFIX)
fun Scene.setEnvironmentLight(light: IndirectLight?) {
    reflectionProbes.setEnvironmentLight(light)
}

/**
 * Withdraws [light] before its owner destroys it, without clearing a probe or a newer fallback.
 * Main thread only. Pair with [setEnvironmentLight].
 */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP_PREFIX)
fun Scene.releaseEnvironmentLight(light: IndirectLight?) {
    reflectionProbes.releaseEnvironmentLight(light)
}

/**
 * Decides which indirect light (IBL) a scene shows while [ReflectionProbeNode]s are in it (#4928).
 *
 * Each probe used to swap the scene's light on its own and remember the one it replaced, which
 * broke as soon as there was more than one actor: `priority` was never read, a probe kept its light
 * after the camera left its zone, a new scene environment overwrote an active probe, and chained
 * probes handed back each other's stale lights. The registry is now the one place that writes the
 * scene's light for probes. Every change — a probe added, removed or updated, a new scene
 * environment — resolves the whole set again:
 *
 * - among the probes whose zone holds the camera, the highest [Probe.priority] wins, and on a tie
 *   the probe added most recently ([newProbe]) wins;
 * - with no active probe, the scene shows the latest environment or AR estimate declared through
 *   [setEnvironmentLight], or a light the caller wrote directly since that declaration.
 *
 * A light the engine has already destroyed is never handed back to the scene.
 *
 * Pure Kotlin over the light type [L], so the rules run in JVM tests; the Filament side is a
 * [Target] over a [Scene] (see [reflectionProbes]). Main thread only, like every Filament call.
 */
internal class ReflectionProbeRegistry<L : Any>(private val target: Target<L>) {

    /** The scene slot the registry writes. */
    interface Target<L : Any> {
        /** The light the scene shows now. */
        var light: L?

        /** `false` once the engine destroyed [light]: it must never be shown again. */
        fun isAlive(light: L): Boolean
    }

    /** One composed probe. Keeps its creation order across updates, so ties stay stable. */
    class Probe<L : Any> internal constructor(internal val order: Long) {
        internal var isRegistered = false
        internal var light: L? = null
        internal var position = Position()
        internal var radius = 0f
        internal var priority = 0
        internal var cameraPosition = Position()

        /** Global probes (non-positive radius) are always active; local ones while the camera is inside. */
        internal val isActive: Boolean
            get() = radius <= 0f || length(cameraPosition - position) <= radius
    }

    private val probes = mutableListOf<Probe<L>>()
    private var nextOrder = 0L

    /** Whether a `SceneView` or `ARSceneView` declared the scene's fallback light. */
    private var environmentDeclared = false
    private var environmentLight: L? = null

    /** A direct caller write after the last environment declaration, including an explicit null. */
    private var callerDeclared = false
    private var callerLight: L? = null

    /** The light this registry last wrote, to tell its own writes from the caller's. */
    private var applied: L? = null

    /** A new probe, ordered after every probe created before it. Not active until [update]. */
    fun newProbe(): Probe<L> = Probe(nextOrder++)

    /** Adds or updates [probe] and shows whichever light now wins. */
    fun update(
        probe: Probe<L>,
        light: L?,
        position: Position,
        radius: Float,
        priority: Int,
        cameraPosition: Position,
    ) {
        val wasActive = probe.isRegistered && probe.isActive
        val changed = !probe.isRegistered || probe.light !== light || probe.priority != priority
        probe.light = light
        probe.position = position
        probe.radius = radius
        probe.priority = priority
        probe.cameraPosition = cameraPosition
        if (!probe.isRegistered) {
            probe.isRegistered = true
            probes += probe
        }
        // A camera that moves inside (or outside) a zone changes nothing: most frames stop here.
        if (changed || wasActive != probe.isActive) resolve(force = false)
    }

    /** Removes [probe]; the next probe in line, or the scene's own light, takes over. */
    fun remove(probe: Probe<L>) {
        if (!probe.isRegistered) return
        probe.isRegistered = false
        probes -= probe
        resolve(force = false)
    }

    /**
     * Declares the scene's fallback light: the environment or latest AR estimate. It is shown
     * at once unless an active probe overrides it, and supersedes any previous caller write.
     */
    fun setEnvironmentLight(light: L?) {
        environmentDeclared = true
        environmentLight = light
        callerDeclared = false
        callerLight = null
        // The environment replaces whatever the scene showed, like a direct write used to.
        resolve(force = true)
    }

    /**
     * Withdraws [light] as the scene's own light, before its owner destroys it. Only lets go of a
     * light the scene still got from here: a light the caller wrote since is left alone.
     */
    fun releaseEnvironmentLight(light: L?) {
        if (!environmentDeclared || environmentLight !== light) return
        captureCallerLight()
        environmentDeclared = false
        environmentLight = null
        // Even a direct caller write must not keep this owner's soon-to-be-destroyed light.
        if (callerLight === light) {
            callerDeclared = false
            callerLight = null
        }
        resolve(force = target.light === light, captureCaller = false)
    }

    /** The light the scene would show now. Exposed for tests. */
    internal fun resolvedLight(): L? = wanted(winner())

    internal val probeCount: Int get() = probes.size

    // A winning probe without a light means "no IBL in this zone", not "fall back".
    private fun wanted(winner: Probe<L>?): L? = if (winner != null) winner.light else fallback()

    private fun winner(): Probe<L>? = probes
        .filter { it.isActive && it.light?.let(target::isAlive) != false }
        .maxWithOrNull(compareBy<Probe<L>>({ it.priority }, { it.order }))

    private fun fallback(): L? =
        (if (callerDeclared) callerLight else if (environmentDeclared) environmentLight else null)
            ?.takeIf(target::isAlive)

    private fun captureCallerLight() {
        val current = target.light
        if (current !== applied) {
            callerDeclared = true
            callerLight = current
        }
    }

    private fun resolve(force: Boolean, captureCaller: Boolean = !force) {
        // Environment declarations supersede caller writes; other changes remember them even
        // when an environment was declared, so the next probe cycle can hand them back.
        if (captureCaller) captureCallerLight()
        val current = target.light
        val winner = winner()
        val wanted = wanted(winner)
        if (current === wanted) {
            applied = wanted
            return
        }
        // Overwrite only what is ours to overwrite: the scene's own light, a light this registry
        // wrote, an empty slot, or anything when a probe claims the scene. A light the caller wrote
        // over a declared environment stays until the environment changes again.
        if (force || winner != null || current == null || current === applied) {
            target.light = wanted
            applied = wanted
        }
    }
}

/** The registry of [this] scene's probes, created on first use and dropped with the scene. */
internal val Scene.reflectionProbes: ReflectionProbeRegistry<IndirectLight>
    get() = synchronized(sceneRegistries) {
        sceneRegistries.getOrPut(this) { ReflectionProbeRegistry(SceneIndirectLight(WeakReference(this))) }
    }

private val sceneRegistries = WeakHashMap<Scene, ReflectionProbeRegistry<IndirectLight>>()

/** Holds its scene weakly: the registry is the value of a weak map keyed on that same scene. */
private class SceneIndirectLight(
    private val scene: WeakReference<Scene>,
) : ReflectionProbeRegistry.Target<IndirectLight> {
    override var light: IndirectLight?
        get() = scene.get()?.indirectLight
        set(value) {
            scene.get()?.indirectLight = value
        }

    // The handle getter throws once the engine destroyed the light.
    override fun isAlive(light: IndirectLight): Boolean = runCatching { light.nativeObject }.isSuccess
}
