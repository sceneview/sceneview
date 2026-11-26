package repro

import android.os.Bundle
import android.os.Debug
import java.io.File
import android.os.SystemClock
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import io.github.sceneview.SceneView
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberModelInstance
import io.github.sceneview.rememberModelLoader
import kotlinx.coroutines.delay

/**
 * MAVLab-like scene (Drone3DScreen.DroneScene): rememberEngine + rememberModelLoader + one
 * rememberModelInstance("models/drone.glb"). The activity is recreated on rotation.
 *
 * Heap budget: `adb shell am start -n repro.mavlab/repro.MainActivity --ei budgetMiB 16` holds
 * ballast so that only budgetMiB of Java heap is left, as on MAVLab's Android 8 emulator whose
 * ART growth limit was 16 MiB. Direct ByteBuffers are Java-heap arrays on ART, so they count too.
 */
class MainActivity : ComponentActivity() {
    companion object {
        var ballast: Array<ByteArray>? = null
        val processStart = SystemClock.uptimeMillis()
        var generation = 0
        var handlerInstalled = false
    }

    /**
     * On an OutOfMemoryError: log every thread's stack (where a cancelled load still is) and dump
     * the heap to files/oom.hprof (`adb exec-out run-as repro.mavlab cat files/oom.hprof`), so the
     * holder of each large array can be read off a GC-root path. Then the default handler runs.
     */
    private fun installOomHandler() {
        if (handlerInstalled) return
        handlerInstalled = true
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        val dir = filesDir
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            if (generateSequence(error) { it.cause }.any { it is OutOfMemoryError }) {
                ballast = null
                runCatching {
                    Log.e("REPRO_THREADS", "OOM on ${thread.name}; all threads:")
                    Thread.getAllStackTraces().forEach { (t, frames) ->
                        Log.e("REPRO_THREADS", "--- ${t.name} (${t.state})")
                        frames.take(40).forEach { Log.e("REPRO_THREADS", "    at $it") }
                    }
                }
                runCatching {
                    val f = File(dir, "oom.hprof")
                    Debug.dumpHprofData(f.absolutePath)
                    Log.e("REPRO_THREADS", "hprof ${f.length()} bytes")
                }.onFailure { Log.e("REPRO_THREADS", "hprof failed: $it") }
            }
            previous?.uncaughtException(thread, error)
        }
    }

    private fun used() = Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        installOomHandler()
        // extraMiB > 0: hold that much on top of the app's own baseline, under the device's real
        // heap limit, to stand in for a larger app (MAVLab's own UI and state) than this repro.
        val extra = intent.getIntExtra("extraMiB", 0)
        if (extra > 0 && ballast == null) {
            ballast = Array(extra) { ByteArray(1024 * 1024 - 16) }
            Log.i("REPRO", "ballast extra=$extra MiB used=${used() shr 20} MiB")
        }
        val budget = intent.getIntExtra("budgetMiB", 0)
        if (budget > 0 && ballast == null) {
            System.gc()
            val max = Runtime.getRuntime().maxMemory()
            val keep = budget * 1024L * 1024L
            val toHold = max - keep - used()
            // 1 MiB chunks: one huge array may not fit in a fragmented heap.
            ballast = Array((toHold / (1024 * 1024)).toInt().coerceAtLeast(0)) { ByteArray(1024 * 1024 - 16) }
            Log.i("REPRO", "ballast max=${max shr 20} MiB held=${ballast!!.size} MiB budget=$budget MiB used=${used() shr 20} MiB")
        }
        val gen = ++generation
        Log.i("REPRO", "activity#$gen onCreate t=${SystemClock.uptimeMillis() - processStart} ms maxMemory=${Runtime.getRuntime().maxMemory() shr 20} MiB")
        setContent {
            val engine = rememberEngine()
            val modelLoader = rememberModelLoader(engine)
            SceneView(modifier = Modifier.fillMaxSize(), engine = engine, modelLoader = modelLoader) {
                val instance = rememberModelInstance(modelLoader, "models/drone.glb")
                LaunchedEffect(instance) {
                    if (instance != null) Log.i("REPRO", "activity#$gen MODEL VISIBLE t=${SystemClock.uptimeMillis() - processStart} ms")
                }
                instance?.let { ModelNode(modelInstance = it, scaleToUnits = 0.58f, autoAnimate = false) }
            }
            LaunchedEffect(Unit) {
                var peak = 0L
                while (true) {
                    val u = used(); if (u > peak) peak = u
                    Log.i("REPRO_HEAP", "activity#$gen usedNoBallast=${(u - (ballast?.size ?: 0) * (1024L * 1024L - 16)) shr 10} KiB peakNoBallast=${(peak - (ballast?.size ?: 0) * (1024L * 1024L - 16)) shr 10} KiB")
                    delay(250)
                }
            }
        }
    }

    override fun onDestroy() {
        Log.i("REPRO", "activity onDestroy t=${SystemClock.uptimeMillis() - processStart} ms")
        super.onDestroy()
    }
}
