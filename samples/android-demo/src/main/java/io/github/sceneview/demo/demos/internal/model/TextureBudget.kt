package io.github.sceneview.demo.demos.internal.model

import com.google.android.filament.Engine
import com.google.android.filament.Texture

/**
 * How much of its key photos a model's texture keeps, and how large its atlas may grow: what
 * makes a wall, a frame or a parquet readable up close, bounded by what the phone's heap holds.
 *
 * - [photoPixels]: the most pixels a kept photo has; a larger one is box-filtered down to it.
 * - [totalPhotoPixels]: the most pixels all the kept photos have together. A photo keeps
 *   [photoPixels] only while the photos still to decode would fit at [BASE]'s; past that it
 *   comes down to [BASE]'s, so the photos' bytes stay bounded whatever the recording holds.
 * - [atlasSize]: the atlas's longest side at most.
 *
 * The recorder keeps a handful of sharp photos at 960 pixels on their long side and the rest at
 * 320 ([io.github.sceneview.demo.demos.internal.ScanPhotoPolicy]). Under [BASE], the sharp ones
 * come down to the others' size; under [LARGE], they are kept whole.
 */
internal data class TextureBudget(
    val photoPixels: Long,
    val totalPhotoPixels: Long,
    val atlasSize: Int,
) {
    init {
        require(photoPixels > 0 && totalPhotoPixels >= photoPixels && atlasSize > 0) { "an empty texture budget" }
    }

    /** The most pixels the next photo keeps, after [used] pixels kept and with [left] photos still to come after it. */
    internal fun photoCap(used: Long, left: Int): Long {
        if (photoPixels <= BASE.photoPixels) return photoPixels
        val reserved = left.toLong() * BASE.photoPixels
        return if (used + photoPixels + reserved <= totalPhotoPixels) photoPixels else BASE.photoPixels
    }

    companion object {
        /**
         * The budget a phone with a small heap gets, and the one a finish that ran out of memory
         * falls back to: ~60 photos of 240 × 320, a 2048 atlas. A Pixel 4a's 192 MB.
         */
        val BASE = TextureBudget(
            photoPixels = PhotoTexturing.MAX_PHOTO_PIXELS,
            totalPhotoPixels = PhotoTexturing.MAX_PHOTOS * PhotoTexturing.MAX_PHOTO_PIXELS,
            atlasSize = TextureAtlas.MAX_SIZE,
        )

        /**
         * The budget from [LARGE_HEAP_BYTES]: a sharp photo of 720 × 960 kept whole — the
         * recorder's 15 of them among 45 small ones — at most 15 M photo pixels (60 MB) in all,
         * a 4096 atlas.
         */
        val LARGE = TextureBudget(
            photoPixels = 720L * 960,
            totalPhotoPixels = 15_000_000L,
            atlasSize = TextureAtlas.MAX_SIZE * 2,
        )

        /**
         * The heap ([Runtime.maxMemory], on a phone its growth limit) from which a finish gets
         * [LARGE]: 256 MB, what most recent phones report. A Pixel 4a reports 192 MB.
         */
        const val LARGE_HEAP_BYTES = 256L * 1024 * 1024

        /**
         * The budget for a heap of [heapBytes], its atlas within the GPU's [maxTextureSize]: a
         * model's texture past it is one SceneView's model loader refuses. OpenGL ES 3.0
         * guarantees [BASE]'s; `0`, the GPU's limit not known yet, keeps to it.
         */
        fun forHeap(heapBytes: Long, maxTextureSize: Int = gpuMaxTextureSize): TextureBudget = when {
            heapBytes < LARGE_HEAP_BYTES -> BASE
            maxTextureSize >= LARGE.atlasSize -> LARGE
            else -> LARGE.copy(atlasSize = BASE.atlasSize)
        }

        /**
         * The GPU's largest texture side, `Texture.getMaxTextureSize`, once read ([readGpuLimit]);
         * `0` until then.
         */
        @Volatile
        var gpuMaxTextureSize: Int = 0

        /** [gpuMaxTextureSize] read from [engine]'s GPU, and returned. */
        fun readGpuLimit(engine: Engine): Int =
            Texture.getMaxTextureSize(engine, Texture.Sampler.SAMPLER_2D).also { gpuMaxTextureSize = it }
    }
}
