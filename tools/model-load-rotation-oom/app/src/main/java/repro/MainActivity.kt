package repro

import android.os.Bundle
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
    }

    private fun used() = Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
