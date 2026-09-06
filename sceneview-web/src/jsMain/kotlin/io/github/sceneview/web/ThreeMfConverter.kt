package io.github.sceneview.web

import io.github.sceneview.core.threemf.ThreeMfLoader
import org.khronos.webgl.ArrayBuffer
import org.khronos.webgl.Int8Array
import org.khronos.webgl.Uint8Array
import org.khronos.webgl.get

/**
 * Converts a **3MF** payload to GLB before it reaches Filament.js, so `.3mf` loads through every
 * entry point of this library with no separate API (#3482).
 *
 * 3MF (`.3mf`) is what ChatGPT emits when it turns a drawing into a printable model, and what
 * every slicer reads and writes. Nothing on the web opened one in 3D. The conversion itself is
 * the shared KMP one — [ThreeMfLoader], the very same code the Android `ModelLoader` runs — so
 * the browser and the phone produce byte-identical GLB from the same file; there is no second
 * parser here.
 *
 * **This is the web twin of `io.github.sceneview.loaders.ModelLoader.convertThreeMfToGlb`**, down
 * to the cheap gate: a payload whose first four bytes are not the ZIP magic is returned as *the
 * very same [ArrayBuffer] instance*, so a 100 MB GLB is never copied just to find out it is not a
 * 3MF. Only past that gate does the container get read.
 */
internal object ThreeMfConverter {

    /** `PK` — the local-file-header magic every ZIP, and so every 3MF, starts with. */
    private const val ZIP_MAGIC_0 = 0x50 // 'P'
    private const val ZIP_MAGIC_1 = 0x4B // 'K'
    private const val ZIP_MAGIC_2 = 0x03
    private const val ZIP_MAGIC_3 = 0x04

    /**
     * Returns the GLB for a 3MF [buffer], or [buffer] itself for anything else — a glTF, a GLB, a
     * plain ZIP, a truncated file.
     *
     * Never throws: a payload that sniffs as 3MF but fails to parse is returned untouched and
     * reported on the console, so the caller's existing "Filament could not read this" error path
     * stays the single failure story instead of two competing ones.
     */
    fun convert(buffer: ArrayBuffer): ArrayBuffer {
        if (!startsWithZipMagic(buffer)) return buffer
        // Kotlin/JS ByteArray IS an Int8Array — view the payload, no copy (as loadSplatCloud does).
        val bytes = Int8Array(buffer).unsafeCast<ByteArray>()
        if (!ThreeMfLoader.isThreeMf(bytes)) return buffer
        return try {
            val glb = ThreeMfLoader.toGlb(bytes)
            console.log("SceneView: converted a 3MF payload to GLB (${glb.size} bytes)")
            glb.toArrayBuffer()
        } catch (e: Throwable) {
            console.error("SceneView: failed to convert 3MF payload", e)
            buffer
        }
    }

    /** `true` when [buffer] is a 3MF: ZIP magic, then a `3D/3dmodel.model` part. */
    fun isThreeMf(buffer: ArrayBuffer): Boolean =
        startsWithZipMagic(buffer) &&
            ThreeMfLoader.isThreeMf(Int8Array(buffer).unsafeCast<ByteArray>())

    private fun startsWithZipMagic(buffer: ArrayBuffer): Boolean {
        if (buffer.byteLength < 4) return false
        val head = Uint8Array(buffer, 0, 4)
        return head[0].toInt() == ZIP_MAGIC_0 &&
            head[1].toInt() == ZIP_MAGIC_1 &&
            head[2].toInt() == ZIP_MAGIC_2 &&
            head[3].toInt() == ZIP_MAGIC_3
    }
}

/**
 * The bytes of this array as a standalone [ArrayBuffer].
 *
 * A Kotlin/JS `ByteArray` is an `Int8Array`, and the one [ThreeMfLoader.toGlb] returns owns its
 * whole backing buffer — so the common case is a plain unwrap with no copy. The `slice` is the
 * correctness guard for a view that does not span its buffer, never the path taken here.
 */
internal fun ByteArray.toArrayBuffer(): ArrayBuffer {
    val view = unsafeCast<Int8Array>()
    return if (view.byteOffset == 0 && view.length == view.buffer.byteLength) {
        view.buffer
    } else {
        view.buffer.slice(view.byteOffset, view.byteOffset + view.length)
    }
}
