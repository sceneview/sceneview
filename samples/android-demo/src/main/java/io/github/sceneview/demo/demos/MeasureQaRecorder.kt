package io.github.sceneview.demo.demos

import android.content.Context
import android.util.Log
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Debug-only accuracy log of the `ar-measure` demo: the whole board, one JSON line a figure
 * (`measureQaLines`), rewritten after every change to
 * `getExternalFilesDir("measure-qa")/measure-<epoch ms of the first point>.jsonl` and mirrored
 * to Logcat under the `MeasureQA` tag. Only built when `BuildConfig.DEBUG`; release builds
 * never create one.
 *
 * A board that becomes empty (Clear, or Undo down to nothing) ends the file as it was last
 * written — Clear never wipes a measurement — and the next first point starts a new file.
 *
 * Called on the main thread; the file write runs on a single background thread, in order.
 */
internal class MeasureQaRecorder(context: Context) {
    private val appContext = context.applicationContext
    private val writer: ExecutorService = Executors.newSingleThreadExecutor()
    /** File of the current session, named on its first point; created with its first segment. */
    private var fileName: String? = null
    private var fileCreated = false

    /**
     * @param reason what changed the board (`point`, `undo`, `clear`, `share`…), logged only.
     * @param boardEmpty no point at all on the board.
     */
    fun record(reason: String, boardEmpty: Boolean, lines: List<String>) {
        if (boardEmpty) {
            fileName?.takeIf { fileCreated }?.let { Log.i(TAG, "$reason: board empty, $it kept as last written") }
            fileName = null
            fileCreated = false
            return
        }
        val name = fileName ?: "measure-${System.currentTimeMillis()}.jsonl".also { fileName = it }
        Log.i(TAG, "$reason: ${lines.size} figure(s) -> $name")
        lines.forEach { Log.i(TAG, it) }
        // No file before the first segment: a lone point undone leaves nothing behind.
        if (lines.isEmpty() && !fileCreated) return
        fileCreated = true
        val text = if (lines.isEmpty()) "" else lines.joinToString("\n", postfix = "\n")
        if (writer.isShutdown) return
        runCatching {
            writer.execute {
                runCatching {
                    val dir = appContext.getExternalFilesDir(DIR) ?: error("no external files dir")
                    File(dir, name).writeText(text)
                }.onFailure { Log.w(TAG, "could not write $name", it) }
            }
        }.onFailure { Log.w(TAG, "writer closed, $name not written", it) }
    }

    /** Lets the queued writes finish, then stops the writer thread. */
    fun close() = writer.shutdown()

    private companion object {
        const val TAG = "MeasureQA"
        const val DIR = "measure-qa"
    }
}
