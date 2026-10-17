package io.github.sceneview.ar

import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner

/**
 * The lifecycle an [ARSceneView] follows by default: the one of the activity hosting it.
 *
 * `LocalLifecycleOwner` is narrower than the screen is visible. Inside a `NavHost` it is the
 * destination's back stack entry, which Navigation moves to `CREATED` when its exit transition
 * *starts* and only to `RESUMED` when its enter transition *ends*. An AR session bound to it
 * stops its camera while the screen is still animating on screen, and the surface keeps showing
 * the last camera image for the whole transition.
 *
 * The camera has one honest owner, the activity: the session must stop when the activity is
 * paused, and it is closed when the composable leaves the composition. Between the two the
 * picture should stay live, so this is what the default binds to.
 *
 * Public so that a screen can name the default, for instance to choose between it and a
 * narrower lifecycle:
 *
 * ```kotlin
 * ARSceneView(
 *     lifecycle = if (pauseWhenCovered) LocalLifecycleOwner.current.lifecycle
 *                 else rememberHostLifecycle(),
 * )
 * ```
 *
 * @return the lifecycle of the first [LifecycleOwner] found by unwrapping `LocalContext` (the
 * activity, also from inside a `Dialog` or a themed subtree), or `LocalLifecycleOwner`'s when
 * the context is not hosted by one.
 */
@Composable
fun rememberHostLifecycle(): Lifecycle {
    val context = LocalContext.current
    val local = LocalLifecycleOwner.current.lifecycle
    return remember(context, local) { context.findHostLifecycle() ?: local }
}

/**
 * The lifecycle of the first [LifecycleOwner] found by unwrapping this context, which is the
 * activity for a composable set in one (a `Dialog` or a themed wrapper included), or `null`.
 */
internal fun Context.findHostLifecycle(): Lifecycle? {
    var context: Context? = this
    while (context != null) {
        if (context is LifecycleOwner) return context.lifecycle
        context = (context as? ContextWrapper)?.baseContext
    }
    return null
}
