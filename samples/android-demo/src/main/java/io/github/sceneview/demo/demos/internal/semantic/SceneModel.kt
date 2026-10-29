package io.github.sceneview.demo.demos.internal.semantic

import io.github.sceneview.demo.hdpack.HdAsset
import io.github.sceneview.demo.hdpack.HdPackHttpException
import io.github.sceneview.demo.hdpack.HdPackIntegrityException
import io.github.sceneview.demo.hdpack.HdPackManifest
import io.github.sceneview.demo.hdpack.HdPackStore
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.File
import java.io.IOException

/**
 * The furniture detector: YOLOX-Tiny (Apache-2.0), converted from its ONNX export to LiteRT in
 * float16 — 10 MB, too heavy to ride in every APK for a feature only a scan's replay uses. It is
 * published on the `assets-v1` release under its own SHA-256, downloaded the first time a replay
 * recognises a scan — on a metered network, once the user asks —, and kept in app storage.
 */
object SceneModel {
    /** The model's name in logs and in the corpus test's outputs. */
    const val NAME = "yolox_tiny"

    const val SHA256 = "d1d61787dcd087234e41914688315b69f502a8dfdfb10982cdcd3b7c57549e0a"
    const val BYTES = 10_178_328L

    /** Where `tools/fetch-assets.sh` reads the repository's binary assets from, too. */
    const val BASE_URL = "https://github.com/sceneview/sceneview/releases/download/assets-v1"

    /** The release names each file after its hash: this URL can never serve another model. */
    const val URL = "$BASE_URL/$SHA256"

    /** The detector as a one-file download for [HdPackStore], with the credit `CREDITS.md` gives it. */
    val ASSET = HdAsset(
        id = "yolox-tiny",
        title = "YOLOX-Tiny (COCO object detector, TFLite)",
        file = SHA256,
        sha256 = SHA256,
        bytes = BYTES,
        license = "Apache-2.0",
        author = "Megvii Inc. (Megvii-BaseDetection/YOLOX)",
        source = "https://github.com/Megvii-BaseDetection/YOLOX/releases/tag/0.1.1rc0",
    )
}

/** Where the detector is, as the replay tells the user. */
sealed interface SceneModelState {
    /** On the device and checked: recognition runs. */
    data object Ready : SceneModelState

    /** On the wire, [fraction] of it on the device. */
    data class Downloading(val fraction: Float) : SceneModelState

    /** No network, or it dropped mid-file: what arrived is kept, and a later replay resumes it. */
    data object Offline : SceneModelState

    /**
     * The network works but the server did not send the file — a 404, a 5xx: what arrived is kept,
     * and a later replay asks again. Not "no connection", which would send the user to their Wi-Fi.
     */
    data object Unavailable : SceneModelState

    /**
     * Not on the device, and the network is metered: nothing is fetched until the user asks — the
     * HD pack's rule. On a network that is not metered it downloads without asking.
     */
    data object Metered : SceneModelState

    /**
     * The file failed its SHA-256 or size check and was deleted, or LiteRT would not load it.
     * Recognition is off; the replay runs without it.
     */
    data object Unusable : SceneModelState
}

/**
 * The detector's file in [dir], a folder of its own: downloaded once, resumed after a cut, checked
 * against its SHA-256 and size before it is renamed into place, and reused from then on. Built on
 * [HdPackStore] — a pack of one file — so the transfer, the hashing and the rename run on
 * [Dispatchers.IO], never on the caller's thread. Building one hashes the copy already in [dir],
 * once per launch: one damaged on the device since its download is fetched again, never handed to
 * the runtime, which maps it and trusts it. Do it off the main thread too.
 *
 * [client] and [baseUrl] are for tests; [asset] too, with a payload a fake server can serve.
 * [onFailure] hears why a fetch ended without a file — the app logs it.
 */
class SceneModelStore(
    dir: File,
    client: OkHttpClient? = null,
    baseUrl: String = SceneModel.BASE_URL,
    private val asset: HdAsset = SceneModel.ASSET,
    private val onFailure: (IOException) -> Unit = {},
) {
    /**
     * Beside the detector's file when that copy was fetched again after LiteRT refused an
     * earlier one ([markRefetched]): kept across launches, deleted with the file by [discard], and
     * once a copy loads ([clearRefetched]).
     */
    private val refetched = File(dir, "${asset.file}$REFETCHED")

    private val store = HdPackManifest(version = 1, assets = listOf(asset)).let { manifest ->
        if (client == null) {
            HdPackStore(manifest, dir, baseUrl = baseUrl, hashOnScan = true)
        } else {
            HdPackStore(manifest, dir, client, baseUrl, hashOnScan = true)
        }
    }

    /** The detector already on the device, `null` until it has been downloaded and checked. */
    fun cached(): File? = store.readyFile(asset.id)

    /**
     * The detector's file, downloaded first if it is not on the device yet; `null` when it could
     * not be ([SceneModelState.Offline] or [SceneModelState.Unusable]). [onState] hears every step
     * on the caller's dispatcher — the progress while it downloads, then how it ended.
     */
    suspend fun fetch(onState: (SceneModelState) -> Unit = {}): File? {
        cached()?.let {
            onState(SceneModelState.Ready)
            return it
        }
        val part = store.partFile(asset)
        onState(SceneModelState.Downloading(fraction(withContext(Dispatchers.IO) { part.length() })))
        return try {
            coroutineScope {
                val progress = launch(start = CoroutineStart.UNDISPATCHED) {
                    store.transfer.collect { transfer ->
                        transfer?.fractionOf(asset.id)?.let { onState(SceneModelState.Downloading(it)) }
                    }
                }
                try {
                    store.downloadMissing(only = setOf(asset.id))
                } finally {
                    progress.cancel()
                }
            }
            cached().also { onState(if (it != null) SceneModelState.Ready else SceneModelState.Unusable) }
        } catch (corrupt: HdPackIntegrityException) {
            // A file that grew past its size stops mid-stream, before the store deletes its part
            // file: nothing that failed a check is kept, whichever check it failed.
            withContext(Dispatchers.IO) { part.delete() }
            onFailure(corrupt)
            onState(SceneModelState.Unusable)
            null
        } catch (refused: HdPackHttpException) {
            onFailure(refused)
            onState(SceneModelState.Unavailable)
            null
        } catch (offline: IOException) {
            onFailure(offline)
            onState(SceneModelState.Offline)
            null
        }
    }

    /**
     * Deletes the detector's file from the device — one that passed its checks but that LiteRT
     * would not load — so the next [fetch] downloads it again.
     */
    suspend fun discard() {
        store.removeAll()
    }

    /**
     * Notes that the copy now on the device replaces one LiteRT refused, so that no launch
     * fetches a third. Written once that copy has arrived: a download deletes the names it does not
     * know. A marker that cannot be written is only logged ([onFailure]).
     */
    suspend fun markRefetched() {
        withContext(Dispatchers.IO) {
            try {
                refetched.createNewFile()
            } catch (failure: IOException) {
                onFailure(failure)
            }
        }
    }

    /** Whether the copy on the device was fetched again after LiteRT refused one ([markRefetched]). */
    suspend fun isRefetched(): Boolean = withContext(Dispatchers.IO) { refetched.isFile }

    /**
     * Forgets [markRefetched] once LiteRT has loaded the copy on the device: a file damaged
     * there later is fetched once more, as a first refusal is.
     */
    suspend fun clearRefetched() {
        withContext(Dispatchers.IO) { refetched.delete() }
    }

    private fun fraction(onDisk: Long): Float =
        if (asset.bytes > 0L) (onDisk.toFloat() / asset.bytes).coerceIn(0f, 1f) else 0f

    private companion object {
        const val REFETCHED = ".refetched"
    }
}
