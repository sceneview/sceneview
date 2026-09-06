package io.github.sceneview.demo

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.Immutable
import io.github.sceneview.core.threemf.ThreeMfLoader
import java.io.File

/**
 * A model file another app handed to this one — "Open with SceneView" (#3482).
 *
 * @property location A `file://` URI inside the app's own cache. The incoming `content://`
 *   URI is deliberately **not** carried around: its read grant is scoped to the launching
 *   intent, so it would expire under the demo's navigation, and it is not a path the AR
 *   handoff could pass on.
 * @property displayName The file's own name, shown as the viewer's title — the user opened
 *   *their* file and the screen should say so.
 */
@Immutable
data class OpenedModel(val location: String, val displayName: String) {
    /** The staged copy on disk, for cache housekeeping. */
    fun file(): File? = Uri.parse(location).path?.let(::File)
}

/**
 * Reads a model file out of an incoming `ACTION_VIEW` / `ACTION_SEND` intent, copies it into the
 * app's cache and returns where it landed — or `null` when the intent carries no model.
 *
 * **Why a copy.** A `content://` URI arrives with a read grant tied to that one intent. The demo
 * navigates (viewer → AR placement), survives configuration changes and may be resumed from the
 * recents list, all of which outlive the grant; and `ARPlacementDemo` places a model by *location*
 * string, which a revoked content URI cannot be. A cache copy is a stable `file://` path for as
 * long as the app cares about it, and it is the same shape the Sketchfab resolver already stages.
 */
object OpenedModelIntent {

    /** File extensions this app advertises in its manifest and accepts here. */
    val SupportedExtensions: Set<String> = setOf("3mf", "glb", "gltf", "stl", "obj", "ply")

    /**
     * MIME types that name a model on their own. `application/octet-stream` is deliberately NOT
     * here — a share sheet labels a `.3mf` that way on most Android versions, but so is every
     * other binary file, so accepting it by MIME alone would claim files this app cannot open.
     * The extension carries that case instead; see [looksLikeModel].
     */
    val SupportedMimeTypes: Set<String> = setOf(
        "model/3mf",
        "application/vnd.ms-package.3dmanufacturing-3dmodel+xml",
        "model/gltf-binary",
        "model/gltf+json",
        "model/stl",
        "model/x.stl-ascii",
        "model/x.stl-binary",
        "application/sla",
        "application/vnd.ms-pki.stl",
        "model/obj",
        "application/x-ply",
    )

    /** The URI an `ACTION_VIEW` or `ACTION_SEND` intent carries a model in, if any. */
    fun modelUri(intent: Intent?): Uri? {
        val action = intent?.action ?: return null
        val uri = when (action) {
            Intent.ACTION_VIEW -> intent.data
            // `EXTRA_STREAM` first, then the clip. A sender's read grant only ever covers
            // `getData()` and the ClipData — never a bare extra — so a share that arrives with
            // both must be read from the clip, or opening it is a SecurityException.
            Intent.ACTION_SEND -> intent.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri
                ?: IntentCompat.getStream(intent)
            else -> null
        } ?: return null
        // A `sceneview://demo/<id>` or `https://sceneview.github.io/open?...` VIEW intent is a
        // deep link, not a file — DeepLinkRouter owns those.
        if (uri.scheme == DeepLinkRouter.SCHEME_CUSTOM) return null
        if (uri.host.equals(DeepLinkRouter.HOST_HTTPS, ignoreCase = true)) return null
        return uri
    }

    /**
     * `true` when a name/MIME pair names a model this app opens.
     *
     * Both signals are consulted because in practice neither is reliable on its own: a share sheet
     * routinely labels a `.3mf` `application/octet-stream`, and a `content://` URI routinely has no
     * usable file name. Either one saying "model" is enough.
     */
    fun looksLikeModel(displayName: String?, mimeType: String?): Boolean {
        val extension = displayName?.substringAfterLast('.', "")?.lowercase()
        if (extension in SupportedExtensions) return true
        return mimeType?.lowercase()?.substringBefore(';')?.trim() in SupportedMimeTypes
    }

    /**
     * Copy the model at [uri] into the cache and describe it, or return `null` if it cannot be read
     * or is not a model this app opens.
     *
     * **The file's own bytes are the deciding signal, not what Android says about it.** Measured on
     * `emulator-5554` with a real share: a `.3mf` sent through the share sheet arrives as
     * `application/octet-stream` **and** with no queryable display name at all (the MediaStore row
     * is not readable without a media permission the demo has no reason to hold), so both metadata
     * signals were empty and a metadata-only check refused a file it could open perfectly. Content
     * sniffing is what makes "Open with SceneView" work in the case it exists for.
     *
     * Blocking I/O — call it off the main thread.
     */
    fun stage(context: Context, uri: Uri, mimeType: String?): OpenedModel? {
        val declaredName = displayName(context, uri)
        val declaredType = mimeType ?: runCatching { context.contentResolver.getType(uri) }.getOrNull()
        val target = File(openedModelsDir(context), STAGED_FILE_NAME)
        val copied = runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            } ?: return null
        }.isSuccess
        if (!copied || target.length() == 0L) {
            target.delete()
            return null
        }
        val format = detectFormat(target)
        if (format == null && !looksLikeModel(declaredName, declaredType)) {
            target.delete()
            return null
        }
        val name = declaredName
            ?: "model.${format ?: extensionFor(declaredType)}"
        return OpenedModel(location = Uri.fromFile(target).toString(), displayName = name)
    }

    /**
     * The extension a file's own bytes say it is (`3mf` / `glb` / `gltf`), or `null` when they say
     * nothing this app can open.
     *
     * 3MF is confirmed with the SDK's own reader rather than by ZIP magic alone: a plain ZIP, a
     * `.docx` and a `.jar` all start with `PK`, and only [ThreeMfLoader.isThreeMf] knows whether
     * there is a `3D/3dmodel.model` part inside. That check needs the whole archive in memory, so
     * it is capped — past the cap the declared name and MIME decide, which is the right trade for a
     * file far larger than any print.
     *
     * Internal rather than private so `OpenedModelIntentTest` can point it at real bytes.
     */
    internal fun detectFormat(file: File): String? {
        val header = runCatching {
            file.inputStream().use { stream ->
                ByteArray(HEADER_BYTES).let { it.copyOf(maxOf(stream.read(it), 0)) }
            }
        }.getOrNull() ?: return null
        return when {
            header.startsWith(GlbMagic) -> "glb"
            header.startsWith(ZipMagic) -> "3mf".takeIf { isThreeMfFile(file) }
            looksLikeGltfJson(header) -> "gltf"
            else -> null
        }
    }

    private fun isThreeMfFile(file: File): Boolean {
        if (file.length() > MaxSniffBytes) return false
        return runCatching { ThreeMfLoader.isThreeMf(file.readBytes()) }.getOrDefault(false)
    }

    /** A glTF JSON document opens with `{` and names its `"asset"` object near the top. */
    private fun looksLikeGltfJson(header: ByteArray): Boolean {
        val text = header.decodeToString().trimStart()
        return text.startsWith("{") && text.contains("\"asset\"")
    }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
        size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }

    /**
     * Delete previously-opened files, keeping only [keep]. Called on every open so the cache holds
     * the file the user is looking at and nothing else — an opened model can be tens of megabytes
     * and there is no session in which yesterday's file is wanted.
     */
    fun sweep(context: Context, keep: File?) {
        openedModelsDir(context).listFiles()?.forEach { file ->
            if (file.absolutePath != keep?.absolutePath) file.delete()
        }
    }

    private fun openedModelsDir(context: Context): File =
        File(context.cacheDir, "opened-models").apply { mkdirs() }

    private fun displayName(context: Context, uri: Uri): String? = when (uri.scheme) {
        ContentResolver.SCHEME_CONTENT -> runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        }.getOrNull()

        else -> uri.lastPathSegment
    }?.takeIf { it.isNotBlank() }

    private fun extensionFor(mimeType: String?): String = when {
        mimeType?.contains("3mf", ignoreCase = true) == true -> "3mf"
        mimeType?.contains("3dmanufacturing", ignoreCase = true) == true -> "3mf"
        mimeType?.contains("gltf+json", ignoreCase = true) == true -> "gltf"
        mimeType?.contains("stl", ignoreCase = true) == true -> "stl"
        mimeType?.contains("sla", ignoreCase = true) == true -> "stl"
        mimeType?.contains("obj", ignoreCase = true) == true -> "obj"
        mimeType?.contains("ply", ignoreCase = true) == true -> "ply"
        else -> "glb"
    }

    /**
     * One staged file, always under the same name. The user's own file name is kept in
     * [OpenedModel.displayName] for the title; using it on disk would mean sanitising untrusted
     * text into a path, and there is never more than one opened model to hold.
     */
    private const val STAGED_FILE_NAME = "opened-model"

    private const val HEADER_BYTES = 4096
    private const val MaxSniffBytes = 64L * 1024 * 1024
    private val GlbMagic = "glTF".encodeToByteArray()
    private val ZipMagic = byteArrayOf(0x50, 0x4B, 0x03, 0x04)
}

/**
 * Reads `EXTRA_STREAM` out of a share-sheet intent.
 *
 * The typed `getParcelableExtra(name, Uri::class.java)` is the non-deprecated API and is tried
 * first — but it returns `null` for a `Uri` on Android 13/14: the framework matches the parcel's
 * declared class against the requested one, and a real `Uri` is always a hidden subclass
 * (`Uri$HierarchicalUri`). Verified on `emulator-5554` (API 36): a `.3mf` shared through the typed
 * reader alone silently opened nothing. The deprecated untyped reader is the fallback, not the
 * default, so the day the platform fixes it this code is already on the good path.
 */
private object IntentCompat {
    @Suppress("DEPRECATION")
    fun getStream(intent: Intent): Uri? =
        androidx.core.content.IntentCompat
            .getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            ?: intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri
}
