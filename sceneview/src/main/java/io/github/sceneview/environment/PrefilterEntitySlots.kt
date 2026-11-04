package io.github.sceneview.environment

import android.util.Log
import com.google.android.filament.EntityManager

/**
 * Keeps the entities of [com.google.android.filament.utils.IBLPrefilterContext] visible to the
 * engine that renders them, on Filament 1.77.0 and later.
 *
 * **The defect.** Since Filament 1.77.0 (google/filament 9a01374c1, "Fix linker flags"),
 * `libfilament-jni.so` applies its version script to release builds and stops exporting
 * `utils::*`. `libgltfio-jni.so` then carries and exports its own copy of `libutils`, and
 * `libfilament-utils-jni.so` binds `utils::EntityManager::get()` to that copy. The process ends up
 * with two entity managers: the engine's (the one `EntityManager.get()` returns in Java), and a
 * second one that only `IBLPrefilterContext` uses for its camera and full-screen-triangle entities.
 *
 * Before rendering, `FScene::prepare` keeps only the entities its engine's manager calls alive:
 * same slot index, same generation. The second manager hands out generation 0 on slots 1, 2, 3…
 * As soon as the engine's manager has destroyed an entity on one of those slots (any screen
 * teardown does), the prefilter's triangle on that slot is dropped, nothing is drawn, and the
 * cubemap comes out black. Hence "HDR environments are black on every engine but the first".
 *
 * **The workaround.** Take the first [SLOT_COUNT] slots of the engine's manager before anything
 * else does, and never release them: their generation stays 0, which is exactly what the
 * prefilter's manager hands out on those slots. The cost is [SLOT_COUNT] empty entities, held for
 * the life of the process.
 *
 * **The limit.** Each `IBLPrefilterContext` takes two new slots from the prefilter's manager (its
 * camera and its triangle) and none ever comes back: its destructor releases the triangle only, and
 * a released slot returns to that manager's free list only when something advances its epochs,
 * which only the engine does, on its own manager (`FEngine` calls `advanceEpoch`). Context k gets
 * slots 2k-1 and 2k, so the reservation covers the first [SLOT_COUNT] / 2 = 2048 contexts
 * **created** in the process, alive or destroyed. The next one renders black again. SceneView
 * creates one context per `EnvironmentLoader`, so one per engine that loads an HDR environment.
 *
 * Remove this file once `libfilament-jni.so` exports `utils::EntityManager` again, or
 * `IBLPrefilterContext` takes the engine's entity manager.
 */
internal object PrefilterEntitySlots {

    private const val TAG = "PrefilterEntitySlots"

    /**
     * Slots reserved: two per `IBLPrefilterContext` created in the process. Creating them takes
     * about 0.5 ms (median of 10 cold starts, Android 16 emulator on Apple silicon; 0.3 ms for 1024).
     */
    internal const val SLOT_COUNT = 4096

    /** Low bits of an entity id: its slot index (`Entity::GENERATION_SHIFT`, libs/utils). */
    private const val INDEX_MASK = (1 shl 17) - 1

    @Volatile
    private var reserved: IntArray? = null

    /**
     * The first slot index the reservation got, or 0 before [reserve] ran. 1 means it covers every
     * slot the prefilter's manager hands out; more means some other code created entities first.
     */
    internal val firstSlot: Int get() = reserved?.firstOrNull()?.and(INDEX_MASK) ?: 0

    /**
     * Reserves the slots, once per process. Must run before the first Filament entity is created
     * to cover them all; SceneView calls it right after loading Filament (`loadFilamentLibraries`).
     */
    @Synchronized
    fun reserve() {
        if (reserved != null) return
        val entities = IntArray(SLOT_COUNT)
        EntityManager.get().create(entities)
        reserved = entities
        val first = entities.first() and INDEX_MASK
        if (first != 1) {
            Log.w(
                TAG,
                "Entity slots reserved from index $first, not 1: ${first - 1} entities were created " +
                    "before SceneView initialised Filament. HDR environments loaded after one of " +
                    "them is destroyed may render black (Filament 1.77+ IBLPrefilterContext)."
            )
        }
    }
}
