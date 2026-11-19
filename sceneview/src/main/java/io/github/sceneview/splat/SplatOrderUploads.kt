package io.github.sceneview.splat

import io.github.sceneview.geometries.LatestWinsUploadGate
import java.nio.IntBuffer

/** The packed order and the draw-slot count that must change together. */
internal data class SplatOrderUpload(
    val order: IntBuffer,
    val sortedCount: Int,
    val isSorted: Boolean = true,
)

/**
 * Upload decisions separated from Filament so a stalled consumer can be tested on the JVM.
 * Submit, destroy and release callbacks run on the node's owning (main) thread.
 */
internal class SplatOrderUploads(
    upload: (SplatOrderUpload, onReleased: () -> Unit) -> Unit,
    onDeferredUpload: () -> Unit,
    /** Asked before a pending order goes out: false once the target (its engine) is gone. */
    private val targetAlive: () -> Boolean = { true },
) {
    private var destroyed = false

    // A completed CPU sort does not imply a consumed frame. Keep the initial order in the
    // same gate, and replace the entire pending order (including its filtered draw-slot count).
    private val uploads = LatestWinsUploadGate<SplatOrderUpload>(
        merge = { _, next -> next },
        canUpload = { !destroyed && targetAlive() },
        upload = upload,
        onDeferredUpload = { onDeferredUpload() },
    )

    fun submit(value: SplatOrderUpload) {
        if (!destroyed) uploads.submit(value)
    }

    /** Drops the order waiting for the gate: a newer filter made it stale. */
    fun discardPending() {
        uploads.discardPending()
    }

    fun destroy() {
        destroyed = true
        uploads.discardPending()
    }
}
