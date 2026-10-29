package io.github.sceneview.demo.demos.internal

import java.io.IOException
import java.util.Locale

/**
 * Why a 3D Scan was not saved, read off what stopped it (#4815): the "Couldn't save" card says
 * it with the action that helps, and `adb logcat -s ScanSave` says it with the figures. Pure
 * Kotlin, tested on the JVM.
 */
enum class ScanSaveFailure {
    /** The heap ran out: a long scan built, opened or written whole. */
    Memory,

    /** A file could not be written, and the phone has less room left than the scan needs. */
    Storage,

    /** The scan holds no camera path, point or surface: nothing to keep, however often it is saved. */
    Empty,

    /** A part scanned again that does not line up on its scan (#4736): the guide says why. */
    Refused,

    /** Anything else; the log names it. */
    Other,
    ;

    /** Whether "Save as file" can still keep the scan: never for an empty one or a full storage. */
    val offersFile: Boolean get() = this == Memory || this == Other

    companion object {
        /**
         * What stopped the save, from [error] or the first of its causes that says. A write that
         * failed is called a full storage only when [freeStorageBytes] is short of the
         * [scanBytes] the scan needs: any other I/O error is not the user's to free up.
         */
        fun of(error: Throwable, scanBytes: Long = 0L, freeStorageBytes: Long = Long.MAX_VALUE): ScanSaveFailure {
            for (cause in generateSequence(error) { it.cause?.takeIf { next -> next !== it } }.take(MAX_CAUSES)) {
                when (cause) {
                    is OutOfMemoryError -> return Memory
                    is IOException -> return if (freeStorageBytes < scanBytes) Storage else Other
                    is RerunImportFailure.Empty -> return Empty
                    else -> Unit
                }
            }
            return Other
        }

        /**
         * One logcat line for a save that failed at [stage] — what failed, how big the scan is and
         * what the phone had left — so a phone's log tells a full heap from a full storage.
         */
        @Suppress("LongParameterList") // one figure each, all of them read where the save fails
        fun logLine(
            stage: String,
            failure: ScanSaveFailure,
            error: Throwable?,
            logBytes: Long,
            mediaBytes: Long,
            heapUsedBytes: Long,
            heapMaxBytes: Long,
            freeStorageBytes: Long,
        ): String = "3D Scan save failed at $stage: $failure" +
            (error?.let { " (${it.javaClass.name}: ${it.message})" }.orEmpty()) +
            "; log ${mb(logBytes)}, media ${mb(mediaBytes)}" +
            "; heap ${mb(heapUsedBytes)} of ${mb(heapMaxBytes)}" +
            "; storage free ${mb(freeStorageBytes)}"

        /** Megabytes, one decimal: "75.3 MB". */
        fun mb(bytes: Long): String = String.format(Locale.US, "%.1f MB", bytes / BYTES_PER_MB)

        private const val MAX_CAUSES = 8
        private const val BYTES_PER_MB = 1024.0 * 1024.0
    }
}
