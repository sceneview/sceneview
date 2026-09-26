package io.github.sceneview.flutter

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
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
 * [Recomposer], tied to the platform view: RESUMED from creation, DESTROYED on
 * [destroy], which `PlatformView.dispose()` calls once the composition — and with it
 * the Filament engine — is already torn down.
 */
internal class PlatformViewOwners private constructor() :
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

    init {
        savedStateController.performAttach()
        savedStateController.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
        recomposerScope.launch(start = CoroutineStart.UNDISPATCHED) {
            recomposer.runRecomposeAndApplyChanges()
        }
    }

    /** Moves the owners to DESTROYED and stops the recomposer. Idempotent. */
    fun destroy() {
        if (lifecycleRegistry.currentState == Lifecycle.State.DESTROYED) return
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
            if (context.hostViewTreeHasOwners()) return null
            return PlatformViewOwners().also { owners ->
                view.setViewTreeLifecycleOwner(owners)
                view.setViewTreeSavedStateRegistryOwner(owners)
                view.setViewTreeViewModelStoreOwner(owners)
                view.setParentCompositionContext(owners.recomposer)
            }
        }

        private fun Context.hostViewTreeHasOwners(): Boolean {
            var ctx: Context? = this
            while (ctx is ContextWrapper && ctx !is Activity) ctx = ctx.baseContext
            val decor = (ctx as? Activity)?.window?.peekDecorView() ?: return false
            return decor.findViewTreeLifecycleOwner() != null &&
                decor.findViewTreeSavedStateRegistryOwner() != null
        }
    }
}
