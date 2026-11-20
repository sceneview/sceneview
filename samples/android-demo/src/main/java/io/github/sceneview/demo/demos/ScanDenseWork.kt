package io.github.sceneview.demo.demos

/** CPU timings only: never used to schedule fusion or change snapshot contents. */
internal class ScanDenseWork {
    val nanos = LongArray(7)

    fun clear() = nanos.fill(0)

    inline fun <T> measure(stage: Int, block: () -> T): T {
        val started = System.nanoTime()
        try {
            return block()
        } finally {
            nanos[stage] += System.nanoTime() - started
        }
    }

    companion object {
        const val FUSE = 0
        const val MERGE = 1
        const val WHOLE = 2
        const val COVERED = 3
        const val WEAK = 4
        const val MESH = 5
        const val ATLAS = 6
    }
}
