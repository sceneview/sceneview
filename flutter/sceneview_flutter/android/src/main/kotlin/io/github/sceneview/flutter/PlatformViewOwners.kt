package io.github.sceneview.flutter

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.os.Bundle
import androidx.compose.runtime.Recomposer
import androidx.compose.ui.platform.AndroidUiDispatcher
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.findViewTreeSavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * View-tree owners for a platform view's [ComposeView] when the host app provides none
 * (#3928).
 *
 * A `ComposeView` needs a `ViewTreeLifecycleOwner` and a `ViewTreeSavedStateRegistryOwner`
 * somewhere above it, and a window recomposer that it looks up on the child of
 * `android.R.id.content`, i.e. on the `FlutterView`. A `ComponentActivity` (so
 * `FlutterFragmentActivity`) installs those owners on its decor view. A plain
 * `FlutterActivity` extends `android.app.Activity` and installs nothing, so the
 * `ComposeView` threw `ViewTreeLifecycleOwner not found from FlutterView` on its
 * first measure and the host app crashed at launch.
 *
 * When the host has them, nothing is installed and the host's lifecycle keeps driving
 * the scene. When it does not, this object supplies all three owners plus a parent
 * [Recomposer]. Its lifecycle follows the host [Activity] through activity lifecycle
 * callbacks (#3934): paused → STARTED, stopped → CREATED, resumed → RESUMED, so
 * `ARSceneView` pauses and resumes the ARCore session with the app. It ends DESTROYED
 * on [destroy], which `PlatformView.dispose()` calls once the composition — and with
 * it the Filament engine — is already torn down.
 */
internal class PlatformViewOwners private constructor(private val activity: Activity?) :
    LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateController = SavedStateRegistryController.create(this)
    private val recomposerScope = CoroutineScope(AndroidUiDispatcher.CurrentThread)

    /** Parent composition context: the host's window recomposer does not exist. */
    val recomposer = Recomposer(recomposerScope.coroutineContext)

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateController.savedStateRegistry
    override val viewModelStore = ViewModelStore()

    /** Mirrors [activity]'s start, pause, stop and resume onto [lifecycleRegistry] (#3934). */
    private val activityCallbacks = object : Application.ActivityLifecycleCallbacks {
        private fun follow(a: Activity, state: Lifecycle.State) {
            // Below API 29 these are application-wide callbacks: keep only our activity.
            if (a !== activity) return
            if (lifecycleRegistry.currentState == Lifecycle.State.DESTROYED) return
            lifecycleRegistry.currentState = state
        }

        override fun onActivityStarted(a: Activity) = follow(a, Lifecycle.State.STARTED)
        override fun onActivityResumed(a: Activity) = follow(a, Lifecycle.State.RESUMED)
        override fun onActivityPaused(a: Activity) = follow(a, Lifecycle.State.STARTED)
        override fun onActivityStopped(a: Activity) = follow(a, Lifecycle.State.CREATED)
        override fun onActivityCreated(a: Activity, savedInstanceState: Bundle?) {}
        override fun onActivitySaveInstanceState(a: Activity, outState: Bundle) {}

        // Flutter disposes the platform view, and dispose() calls destroy().
        override fun onActivityDestroyed(a: Activity) {}
    }

    init {
        savedStateController.performAttach()
        savedStateController.performRestore(null)
        lifecycleRegistry.currentState = initialState(activity)
        recomposerScope.launch(start = CoroutineStart.UNDISPATCHED) {
            recomposer.runRecomposeAndApplyChanges()
        }
        activity?.let { registerCallbacks(it, activityCallbacks) }
    }

    /** Moves the owners to DESTROYED and stops the recomposer. Idempotent. */
    fun destroy() {
        if (lifecycleRegistry.currentState == Lifecycle.State.DESTROYED) return
        activity?.let { unregisterCallbacks(it, activityCallbacks) }
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        viewModelStore.clear()
        recomposer.cancel()
        recomposerScope.cancel()
    }

    companion object {
        /**
         * Installs platform-view owners on [view] when the host view tree of [context]'s
         * activity has none. Returns them, or `null` when the host's own owners are used.
         * Must run on the main thread, before [view] is attached.
         */
        fun installIfHostHasNone(view: ComposeView, context: Context): PlatformViewOwners? {
            val activity = context.findActivity()
            if (activity.hostViewTreeHasOwners()) return null
            return PlatformViewOwners(activity).also { owners ->
                view.setViewTreeLifecycleOwner(owners)
                view.setViewTreeSavedStateRegistryOwner(owners)
                view.setViewTreeViewModelStoreOwner(owners)
                view.setParentCompositionContext(owners.recomposer)
            }
        }

        private fun Context.findActivity(): Activity? {
            var ctx: Context? = this
            while (ctx is ContextWrapper && ctx !is Activity) ctx = ctx.baseContext
            return ctx as? Activity
        }

        private fun Activity?.hostViewTreeHasOwners(): Boolean {
            val decor = this?.window?.peekDecorView() ?: return false
            return decor.findViewTreeLifecycleOwner() != null &&
                decor.findViewTreeSavedStateRegistryOwner() != null
        }

        /**
         * Where the lifecycle starts. A plain `FlutterActivity` is a [LifecycleOwner]
         * even though it sets no view-tree owners, so its current state is used. For
         * any other activity it is RESUMED, since a running Flutter UI creates the
         * platform view.
         */
        private fun initialState(activity: Activity?): Lifecycle.State =
            when (val state = (activity as? LifecycleOwner)?.lifecycle?.currentState) {
                null -> Lifecycle.State.RESUMED
                Lifecycle.State.INITIALIZED, Lifecycle.State.DESTROYED -> Lifecycle.State.CREATED
                else -> state
            }

        private fun registerCallbacks(
            activity: Activity,
            callbacks: Application.ActivityLifecycleCallbacks,
        ) {
            if (Build.VERSION.SDK_INT >= 29) {
                activity.registerActivityLifecycleCallbacks(callbacks)
            } else {
                activity.application.registerActivityLifecycleCallbacks(callbacks)
            }
        }

        private fun unregisterCallbacks(
            activity: Activity,
            callbacks: Application.ActivityLifecycleCallbacks,
        ) {
            if (Build.VERSION.SDK_INT >= 29) {
                activity.unregisterActivityLifecycleCallbacks(callbacks)
            } else {
                activity.application.unregisterActivityLifecycleCallbacks(callbacks)
            }
        }
    }
}
