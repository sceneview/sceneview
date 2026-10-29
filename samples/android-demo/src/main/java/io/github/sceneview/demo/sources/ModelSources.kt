package io.github.sceneview.demo.sources

import android.content.Context
import androidx.annotation.VisibleForTesting
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * SharedPreferences-backed memory of the last source picked in the Explore tab
 * (#2645). Shares the Explore prefs file with `RecentSearchesStore` — one
 * ordered list + one enum slug, far too small to justify DataStore.
 */
class SelectedSourceStore internal constructor(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): ModelSourceId? = ModelSourceId.fromSlug(prefs.getString(KEY_SELECTED_SOURCE, null))

    fun save(id: ModelSourceId) {
        prefs.edit().putString(KEY_SELECTED_SOURCE, id.slug).apply()
    }

    companion object {
        // Same file as RecentSearchesStore — both are tiny Explore-tab prefs.
        private const val PREFS_NAME = "io.github.sceneview.demo.explore"
        private const val KEY_SELECTED_SOURCE = "selectedSource"
    }
}

/**
 * Compose-facing handle over the available [ModelSource]s and the currently
 * selected one. The picker calls [select]; the selection is persisted so the
 * tab reopens on the same catalog.
 */
class ModelSourcesState internal constructor(
    /** Only the sources usable in this build (Sketchfab is dropped when no key). */
    val sources: List<ModelSource>,
    initialSelected: ModelSource,
    private val store: SelectedSourceStore,
) {
    var selected by mutableStateOf(initialSelected)
        private set

    /** Switch the active source (idempotent) and persist the choice. */
    fun select(source: ModelSource) {
        if (source.id == selected.id) return
        selected = source
        store.save(source.id)
    }

    companion object {
        /**
         * Build the available-source list in display order, dropping unavailable
         * sources (Sketchfab without a key). Icosa + Poly Haven are always
         * available, so the list is never empty and the tab is never sourceless.
         */
        @VisibleForTesting
        internal fun buildSources(context: Context): List<ModelSource> =
            listOf(
                SketchfabSource.getInstance(context),
                IcosaGalleryService(context),
                PolyHavenService(context),
            ).filter { it.isAvailable }

        /**
         * Resolve the initial selection: the persisted choice when it is still
         * available, else the first available source (Sketchfab on Play builds,
         * Icosa otherwise).
         */
        @VisibleForTesting
        internal fun resolveInitial(sources: List<ModelSource>, savedId: ModelSourceId?): ModelSource =
            sources.firstOrNull { it.id == savedId } ?: sources.first()
    }
}

/**
 * Process-wide cache of the available sources. Building them creates the OkHttp
 * clients, Sketchfab's on-disk HTTP cache and the JSON codecs, which held the main
 * thread long enough on a cold process to freeze the tap on "Browse online models"
 * (#4622). [load] does that work once, on [Dispatchers.IO]; later opens reuse it.
 */
internal object ModelSourceRegistry {
    @Volatile
    private var cached: List<ModelSource>? = null

    /** The sources if a previous [load] already built them, else `null`. */
    fun peek(): List<ModelSource>? = cached

    /** Build the sources off the main thread, once per process. */
    suspend fun load(context: Context): List<ModelSource> = cached ?: withContext(Dispatchers.IO) {
        synchronized(this@ModelSourceRegistry) {
            cached ?: ModelSourcesState.buildSources(context.applicationContext).also { cached = it }
        }
    }
}

/**
 * Remember the Explore tab's source registry + selection for the current build.
 *
 * `null` until the sources are built — see [ModelSourceRegistry]. The caller shows
 * its loading state meanwhile, so the first frame of the gallery never waits on them.
 */
@Composable
fun rememberModelSources(): ModelSourcesState? {
    val context = LocalContext.current.applicationContext
    val sources by produceState(initialValue = ModelSourceRegistry.peek(), context) {
        if (value == null) value = ModelSourceRegistry.load(context)
    }
    return remember(sources) {
        sources?.let { available ->
            val store = SelectedSourceStore(context)
            ModelSourcesState(available, ModelSourcesState.resolveInitial(available, store.load()), store)
        }
    }
}
