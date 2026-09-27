// Compose entry point for 3D Gaussian Splatting (#2646). Lives in its own file — not
// SceneView.kt, which is at detekt's TooManyFunctions file cap — same package, so imports are
// unaffected (the SurfaceMirroring.kt precedent, #2626).
package io.github.sceneview

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import io.github.sceneview.core.splat.SplatCloud
import io.github.sceneview.core.splat.SplatParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URL

/**
 * Creates and remembers a [SplatCloud] — the in-memory 3D Gaussian Splatting data model consumed
 * by [SceneScope.SplatNode] (#2646).
 *
 * [creator] runs once (and again whenever any of [keys] changes) — build or decode the cloud
 * there. Construction is pure CPU (no Filament calls), so a cheap synthetic cloud can be built
 * inline; keep heavy decoding out of composition by hoisting it behind your own state.
 *
 * ```kotlin
 * val cloud = rememberSplatCloud {
 *     SplatCloud(count = n, positions = ..., scales = ..., rotations = ..., colors = ..., opacities = ...)
 * }
 * SceneView { SplatNode(splatCloud = cloud) }
 * ```
 *
 * To load a `.spz` or `.ply` file, use the `rememberSplatCloud(fileLocation)` overload instead.
 *
 * @param keys    When any key changes, [creator] runs again and produces a new cloud.
 * @param creator Builds the [SplatCloud].
 */
@Composable
fun rememberSplatCloud(vararg keys: Any?, creator: () -> SplatCloud): SplatCloud =
    remember(keys = keys) { creator() }

/**
 * Loads a `.spz` (versions 2 to 4) or `.ply` 3D Gaussian Splatting file and remembers the
 * decoded [SplatCloud] — `null` while it loads, and `null` if it cannot be read or parsed (the
 * error is logged under the `SceneView` tag).
 *
 * The file is read on [Dispatchers.IO] and decoded on [Dispatchers.Default]; decoding is pure
 * CPU, so nothing here touches Filament or blocks the main thread. The format is sniffed from the
 * bytes, not the extension.
 *
 * ```kotlin
 * val cloud = rememberSplatCloud("splats/scan.spz")
 * SceneView {
 *     cloud?.let { SplatNode(splatCloud = it) }
 * }
 * ```
 *
 * @param fileLocation An asset path (`"splats/scan.spz"`, relative to `src/main/assets`), an
 *                     absolute file path, or a `file://`, `content://` or `http(s)://` URI.
 *                     A new location loads a new cloud.
 */
@Composable
fun rememberSplatCloud(fileLocation: String): SplatCloud? {
    val context = LocalContext.current.applicationContext
    val cloud by produceState<SplatCloud?>(initialValue = null, context, fileLocation) {
        value = null
        value = try {
            val bytes = withContext(Dispatchers.IO) { readSplatBytes(context, fileLocation) }
            withContext(Dispatchers.Default) { SplatParser.parse(bytes) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("SceneView", "rememberSplatCloud: cannot load $fileLocation", e)
            null
        }
    }
    return cloud
}

/** Reads [fileLocation] as [rememberSplatCloud] documents it. Blocking — call off the main thread. */
internal fun readSplatBytes(context: Context, fileLocation: String): ByteArray {
    val uri = Uri.parse(fileLocation)
    return when (splatSourceKind(uri.scheme, fileLocation)) {
        SplatSourceKind.Asset -> context.assets.open(fileLocation).use { it.readBytes() }
        SplatSourceKind.File -> File(fileLocation).readBytes()
        SplatSourceKind.Http -> URL(fileLocation).openStream().use { it.readBytes() }
        SplatSourceKind.ContentResolver -> requireNotNull(context.contentResolver.openInputStream(uri)) {
            "no content at $fileLocation"
        }.use { it.readBytes() }
    }
}

internal enum class SplatSourceKind { Asset, File, Http, ContentResolver }

/** Where [rememberSplatCloud] reads a location from, decided from its URI scheme alone. */
internal fun splatSourceKind(scheme: String?, fileLocation: String): SplatSourceKind = when {
    scheme == null -> if (fileLocation.startsWith("/")) SplatSourceKind.File else SplatSourceKind.Asset
    scheme.equals("http", ignoreCase = true) || scheme.equals("https", ignoreCase = true) -> SplatSourceKind.Http
    else -> SplatSourceKind.ContentResolver // file://, content://, android.resource://
}
