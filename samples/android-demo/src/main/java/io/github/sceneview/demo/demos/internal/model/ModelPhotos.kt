package io.github.sceneview.demo.demos.internal.model

import io.github.sceneview.demo.demos.internal.ArDebugTrace
import io.github.sceneview.demo.demos.internal.BakePhoto
import io.github.sceneview.demo.demos.internal.DebugPose
import io.github.sceneview.demo.demos.internal.ReplayLens

/**
 * One key photo of a scan: where the camera stood ([pose], display-oriented, as the recorder keeps
 * it) and how to get its upright pixels. Decoding is left to [load], called only for the photos
 * the texture keeps, so a scan of hundreds of photos never holds them all at once.
 */
internal class ModelPhoto(val pose: DebugPose, val load: () -> BakePhoto?)

/** The key photos a model is textured from, all taken through one [lens] (the manifest's). */
internal class ModelPhotos(val lens: ReplayLens, val photos: List<ModelPhoto>) {
    companion object {
        /**
         * The photos of a recording: each image of [trace] at the camera pose in force when it was
         * taken — the last pose at or before it, the one the recorder logged with it — decoded by
         * [decode] from its path (the pixels only: the photo keeps the trace's pose). `null` for a
         * recording without photos or poses.
         */
        fun of(trace: ArDebugTrace, lens: ReplayLens, decode: (String) -> BakePhoto?): ModelPhotos? {
            if (trace.imageCount == 0 || trace.poseCount == 0) return null
            val photos = (0 until trace.imageCount).mapNotNull { i ->
                val pose = poseAt(trace, trace.imageTime(i)) ?: return@mapNotNull null
                val path = trace.imagePath(i)
                ModelPhoto(pose) { decode(path)?.let { BakePhoto(pose, it.width, it.height, it.argb) } }
            }
            return if (photos.isEmpty()) null else ModelPhotos(lens, photos)
        }

        private fun poseAt(trace: ArDebugTrace, time: Float): DebugPose? {
            var lo = 0
            var hi = trace.poseCount
            while (lo < hi) {
                val mid = (lo + hi) ushr 1
                if (trace.poseTime(mid) <= time + TIME_SLACK_S) lo = mid + 1 else hi = mid
            }
            return if (lo == 0) null else trace.pose(lo - 1)
        }

        /** A pose logged in the same instant as its photo counts as before it. */
        private const val TIME_SLACK_S = 1e-4f
    }
}

/** Encodes the texture atlas — ARGB, rows top first — as a JPEG; `null` when it cannot. */
internal fun interface AtlasEncoder {
    fun jpeg(argb: IntArray, width: Int, height: Int): ByteArray?
}
