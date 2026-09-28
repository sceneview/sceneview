package io.github.sceneview.demo.demos.internal

import io.github.sceneview.demo.sketchfab.SampleAssets
import io.github.sceneview.demo.sketchfab.SketchfabService
import io.github.sceneview.demo.sketchfab.SketchfabSlug
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import java.io.File
import kotlin.random.Random

/**
 * What the Model Viewer's "Surprise me" draws from (#4034): single objects from the curated
 * [SampleAssets] registry, each one small enough to land in a few seconds on a phone.
 *
 * Until #4034 a roll searched Sketchfab for "pbr" and took a random hit. The filter kept up to
 * 200,000 faces with no size cap, so a roll could pull a 20 MB living-room interior, and each
 * failed download fell through to another query. A roll took about 40 s on a phone and landed
 * as scattered untextured blocks. Every entry here is CC-BY (the registry enforces it, and the
 * viewer shows the author), is one object you can frame, and was measured on the Sketchfab
 * download API on 2026-09-28: the GLB size is in the comment. The largest is 5.2 MB. The
 * registry's other entries are left out on purpose: the scans and tree groups weigh 9 to 72 MB,
 * and the crates pile and the potteries are several objects, not one. Three small ones were tried
 * and dropped after a look on the emulator: the Coffee Mug renders as a glowing white block
 * under the viewer's lighting, the Picture Frame lands edge-on at the viewer's hero angle, and
 * the Fantasy Butterfly frames as an empty screen (its bounds are far larger than its mesh).
 *
 * [SURPRISE_MAX_BYTES] still caps a download at run time, because an author can re-upload a
 * heavier model under the same uid.
 */
internal val SURPRISE_POOL_UIDS: List<String> = listOf(
    "88ed6191446749b9a9e24b995bcb5e1d", // PBR Low-Poly Fox, 1.5 MB
    "7377ec591df04445a1aae370017aaa13", // Desk Lamp, 1.2 MB
    "eeb9d9f0627f4783b5d16a8732f0d1a4", // Vintage Camera, 4.2 MB
    "574e006a4e50408d9565e82fafe8ef19", // Retro TV Robot, 0.1 MB, animated
    "7190ff66cb3d4e729a2ab95aeb9e797f", // Walking Robot, 0.1 MB, animated
    "80f8d9a6dadc411e89ca366cb0cfb0d9", // Fluttering Butterfly, 4.1 MB, animated
    "7fab655234e84e0ea6a3ada36ece2ad1", // Wooden End Table, 0.3 MB
    "f91f4cf36fec4e5e8fabda6deda315bc", // Decorated Vase, 2.9 MB
    "5b7aefe2295f4ea5953bccb970ae76c0", // Wine Barrel, 3.2 MB
    "72a1583116e049e1adce28b2baf5527c", // Crystal Glass Decanter, 0.2 MB
    "a54b2ac109d146fb80cfc37c9da26cfb", // Cushioned Sofa, 1.9 MB
    "fd582b0d4a8c4af1a1b5c4f21a481c93", // Skovfogedegen Oak, 5.2 MB
)

/** The pool as registry entries, in [SURPRISE_POOL_UIDS] order. */
internal val SURPRISE_POOL: List<SketchfabSlug> =
    SURPRISE_POOL_UIDS.mapNotNull { uid -> SampleAssets.all.firstOrNull { it.uid == uid } }

/**
 * A download bigger than this is abandoned and the roll moves on (#4034): 8 MB, half again the
 * largest pool entry, so an honest re-upload still fits and a 20 MB scan does not.
 */
internal const val SURPRISE_MAX_BYTES: Long = 8L * 1024 * 1024

/** Thrown from a download's progress callback to abandon a file over [SURPRISE_MAX_BYTES]. */
internal class SurpriseTooLarge(bytes: Long) : IllegalStateException("Surprise download over the cap: $bytes bytes")

/**
 * Abandons the download once it is known to be over [SURPRISE_MAX_BYTES]: from the announced
 * [totalBytes] when the CDN sends one, from the [bytesRead] so far when it does not.
 */
internal fun checkSurpriseSize(bytesRead: Long, totalBytes: Long) {
    val known = maxOf(bytesRead, totalBytes)
    if (known > SURPRISE_MAX_BYTES) throw SurpriseTooLarge(known)
}

/**
 * A shuffle bag over [items]: every entry comes up once before any comes up again, and the
 * last entry of one pass never opens the next, so two rolls in a row never show the same model.
 * A plain `random()` showed the same camera three times in six rolls.
 */
internal class SurpriseBag<T>(private val items: List<T>, private val random: Random = Random.Default) {
    private val queue = ArrayDeque<T>()
    private var last: T? = null

    /** The entry [next] returns, without taking it. `null` only when [items] is empty. */
    fun peek(): T? {
        if (queue.isEmpty()) refill()
        return queue.firstOrNull()
    }

    /**
     * Takes the next entry. With [prefer], takes the first entry left in this pass that
     * matches it, if any: a retry after a failed download asks for one already on disk.
     */
    fun next(prefer: ((T) -> Boolean)? = null): T? {
        if (queue.isEmpty()) refill()
        val preferred = prefer?.let { queue.firstOrNull(it) }
        val item = if (preferred != null) preferred.also { queue.remove(it) } else queue.removeFirstOrNull()
        if (item != null) last = item
        return item
    }

    private fun refill() {
        val pass = items.shuffled(random).toMutableList()
        if (pass.size > 1 && pass.first() == last) pass.add(pass.removeAt(0))
        queue.addAll(pass)
    }
}

/** One bag for the process, so leaving the viewer and coming back does not restart the pass. */
internal object SurpriseRolls {
    val bag: SurpriseBag<SketchfabSlug> = SurpriseBag(SURPRISE_POOL)
}

/**
 * The next roll's download, started while the user looks at the current model (#4034), so the
 * roll after it opens from the cache. One file at a time; a failed prefetch yields `null` and
 * the roll downloads the file itself.
 */
internal class SurprisePrefetch {
    private var uid: String? = null
    private var download: Deferred<File?>? = null

    /** Starts downloading the bag's next entry in [scope], unless it is on disk or on its way. */
    fun warm(scope: CoroutineScope, service: SketchfabService) {
        val next = SurpriseRolls.bag.peek() ?: return
        if (service.isCached(next.uid) || (uid == next.uid && download?.isActive == true)) return
        download?.cancel()
        uid = next.uid
        download = scope.async {
            runCatching { service.downloadModel(next.uid) { read, total -> checkSurpriseSize(read, total) } }
                .getOrNull()
        }
    }

    /** The download in flight for [uid], if the prefetch is fetching that one. */
    fun inFlight(uid: String): Deferred<File?>? = download?.takeIf { this.uid == uid && it.isActive }
}
