package io.github.sceneview.demo.ui.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.google.android.filament.Engine
import io.github.sceneview.rememberEngine

/**
 * The home hero's [Engine], owned above the navigation so a return to Home reuses it (#5202).
 *
 * **Why.** The hero renders through a `TextureView` (`SurfaceType.TextureSurface`), an in-process
 * `SurfaceTexture`. On Adreno 618 (Pixel 4a, Android 13) every *new* EGL context that swaps into
 * such a surface leaves one KGSL draw context behind in the kernel driver, after its surface,
 * texture and context are all destroyed. Home is a navigation destination, so it leaves the
 * composition whenever a demo opens; with its own `rememberEngine()` every return built a new
 * engine, so a new EGL context, and after about 200 returns `eglCreateContext` failed with
 * `EGL_BAD_ALLOC` and the app aborted. Reusing one context costs nothing per return: measured
 * on the device, 400 new `SurfaceTexture`s on one context left no driver context behind.
 *
 * **Ownership.** [ProvideHomeHeroEngine] wraps the whole navigation graph. Its engine comes from
 * `rememberEngine()`, so it is destroyed, through the SDK's backend-idle teardown, when the
 * app's composition goes (the activity is destroyed). Everything made *with* it — view, loaders,
 * model, lights, renderer, swap chain — still belongs to [HomeHeroStage] and is released every
 * time Home leaves; only the engine and its EGL context outlive a visit. Being remembered higher
 * up the slot table than any of them, the engine is also the last thing forgotten.
 *
 * **Lazily.** No engine is made until the hero first asks for one ([rememberHomeHeroEngine]): a
 * cold start on a demo by deep link, or on a device that only shows [HomeHeroStill], creates
 * none. Once made it stays for the activity's life, idle while a demo runs on its own engine.
 * A recreated activity starts without one again, whatever destination it restores.
 */
@Stable
internal class HomeHeroEngineHost(
    /** The engine, or null until the hero has asked for it once. */
    val engine: Engine?,
    private val onRequest: () -> Unit,
) {
    /** Asks the provider for the engine; it arrives on the next composition. Idempotent. */
    fun request() = onRequest()
}

/** Null outside [ProvideHomeHeroEngine]: the hero then makes and owns its own engine. */
internal val LocalHomeHeroEngine = compositionLocalOf<HomeHeroEngineHost?> { null }

/**
 * Provides the home hero's [Engine] to [content] — see [HomeHeroEngineHost]. Call it once, above
 * the `NavHost`, on the main thread like any composition.
 *
 * @param rememberHeroEngine Makes and owns the engine; a test hands in one without JNI.
 */
@Composable
internal fun ProvideHomeHeroEngine(
    rememberHeroEngine: @Composable () -> Engine = { rememberEngine() },
    content: @Composable () -> Unit,
) {
    // Not saved: a recreated activity (process death, a locale or density change) may come back
    // on a demo, and must not build an engine for a hero that is not on screen. Home asks again.
    var wanted by remember { mutableStateOf(false) }
    val engine = if (wanted) rememberHeroEngine() else null
    val host = remember(engine) { HomeHeroEngineHost(engine) { wanted = true } }
    CompositionLocalProvider(LocalHomeHeroEngine provides host, content = content)
}

/**
 * The engine [HomeHeroStage] renders with: the one [ProvideHomeHeroEngine] keeps across visits,
 * or, outside it (a preview, a test host), one of its own. Null for the single composition between
 * the first request and the provider's answer.
 */
@Composable
internal fun rememberHomeHeroEngine(): Engine? {
    val host = LocalHomeHeroEngine.current ?: return rememberEngine()
    if (host.engine == null) {
        LaunchedEffect(host) { host.request() }
    }
    return host.engine
}
