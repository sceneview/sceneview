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
 * The lifecycle of the activity hosting this composable — pass it to [ARSceneView] to keep the
 * camera live while an AR screen animates in and out of a `NavHost`.
 *
 * Opt-in: [ARSceneView] follows `LocalLifecycleOwner` by default. Inside a `NavHost` that is
 * the destination's back stack entry, which Navigation moves to `CREATED` when its exit
 * transition *starts*: the AR session stops while the screen is still on screen and the surface
 * shows the last camera image, frozen, for the whole transition. Bound to the activity instead,
 * the session runs while the composable is in the composition and the activity is resumed,
 * pauses when the activity pauses (Home, screen off, another activity on top) and is closed
 * when the composable leaves the composition.
 *
 * ```kotlin
 * ARSceneView(
 *     modifier = Modifier.fillMaxSize(),
 *     lifecycle = rememberHostLifecycle(),
 * )
 * ```
 *
 * Use it for an AR screen that is a `NavHost` destination with enter/exit transitions. Do not
 * use it where the host keeps the `ARSceneView` composed but not visible (a `ViewPager2` page, a
 * screen under a full-screen `dialog()` destination): the camera would stay on there. When two
 * `ARSceneView`s are resumed together, the one that resumed last takes the camera and gives it
 * back when it is disposed, paused or fails to resume. On a predictive back gesture from one AR
 * screen to another, the screen under the finger keeps its last frame until the gesture ends.
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
