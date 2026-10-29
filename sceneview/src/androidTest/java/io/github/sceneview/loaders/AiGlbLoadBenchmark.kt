package io.github.sceneview.loaders

import android.net.Uri
import android.os.Debug
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.android.filament.Camera
import androidx.test.platform.app.InstrumentationRegistry
import io.github.sceneview.model.ModelInstance
import io.github.sceneview.render.RenderTestHarness
import kotlinx.coroutines.runBlocking
import org.junit.AfterClass
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Loading benchmark for GLBs with the profile of AI-generated models (5 to 60 MB, 2K-4K textures,
 * 100k-500k triangles), the files `tools/generate-ai-glbs.py` writes. Not a gate: it is skipped
 * unless the files are on the device, and it only logs what it measures.
 *
 * ```
 * python3 tools/generate-ai-glbs.py build/ai-glb
 * adb shell mkdir -p /data/local/tmp/ai-glb && adb push build/ai-glb/. /data/local/tmp/ai-glb/
 * ./gradlew :sceneview:connectedDebugAndroidTest \
 *   -Pandroid.testInstrumentationRunnerArguments.class=io.github.sceneview.loaders.AiGlbLoadBenchmark
 * adb logcat -d -s AiGlbBench
 * ```
 *
 * Each file is loaded [RUNS] times through [ModelLoader.loadModelInstance], the path
 * `rememberModelInstance` takes, then [RUNS] times through [ModelLoader.awaitModelInstance], the
 * path `rememberModelLoadState` takes, while the main thread pumps [ModelLoader.updateLoad] and
 * renders a frame every 16 ms, as `SceneView`'s frame loop does. Per run it reports:
 * - `ready`: wall time until the instance is returned, `textures`: until every texture is final;
 * - `mainLoad` / `mainLoadMax`: main-thread time spent in the load's own hops (total, longest),
 *   everything on the main thread but the benchmark's own frame pump;
 * - `updateLoad` / `updateLoadMax`: main-thread time inside the frame loop's `updateLoad()`, where
 *   gltfio uploads the decoded textures;
 * - `progress`: how many progress reports `awaitModelInstance` made;
 * - `javaPeak` / `nativePeak`: peak growth of the Java and native heaps during the load (GPU memory
 *   is not visible from here);
 * - `fps`: frames rendered per second once loaded, at [WIDTH]×[HEIGHT] off screen.
 *
 * Emulator numbers are orders of magnitude only: compare before/after on the same AVD, never with a
 * phone.
 */
@RunWith(AndroidJUnit4::class)
class AiGlbLoadBenchmark {

    companion object {
        private const val TAG = "AiGlbBench"
        private const val RUNS = 3
        private const val WIDTH = 1080
        private const val HEIGHT = 1920
        private const val TEXTURE_TIMEOUT_MS = 60_000L
        private const val FPS_FRAMES = 90

        private lateinit var harness: RenderTestHarness

        @JvmStatic
        @BeforeClass
        fun setupClass() {
            harness = RenderTestHarness(width = WIDTH, height = HEIGHT)
            harness.runOnMain {
                harness.view.camera = harness.engine.createCamera(harness.engine.entityManager.create()).apply {
                    setProjection(45.0, WIDTH.toDouble() / HEIGHT, 0.1, 100.0, Camera.Fov.VERTICAL)
                    lookAt(0.0, 0.0, 2.5, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0)
                }
            }
        }

        @JvmStatic
        @AfterClass
        fun teardownClass() {
            harness.destroy()
        }
    }

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val glbDir: File
        get() = File(
            InstrumentationRegistry.getArguments().getString("aiGlbDir") ?: "/data/local/tmp/ai-glb"
        )

    @Test
    fun loadAiGeneratedGlbs() {
        val files = glbDir.listFiles { file -> file.name.endsWith(".glb") }?.sortedBy { it.name }
        assumeTrue("no GLB in $glbDir (see the KDoc)", !files.isNullOrEmpty())
        // `-e aiGlbVia load` or `await` measures one path only.
        val via = InstrumentationRegistry.getArguments().getString("aiGlbVia")
        for (file in files!!) {
            if (via != "await") repeat(RUNS) { run -> Log.i(TAG, measure(file, run, await = false)) }
            if (via != "load") repeat(RUNS) { run -> Log.i(TAG, measure(file, run, await = true)) }
        }
    }

    private class MainThreadClock {
        // Main-thread messages, split into the benchmark's own (the frame-loop pump, its checks)
        // and everything else (the load's hops onto Dispatchers.Main). A message of ours flags
        // itself while it runs; the flag is read, then cleared, when the looper logs its end.
        var ours = false
        var loadTotalUs = 0L
        var loadMaxUs = 0L
        private var started = 0L

        fun install() {
            Looper.getMainLooper().setMessageLogging { line ->
                if (line.startsWith(">>>>> Dispatching")) {
                    started = SystemClock.elapsedRealtimeNanos()
                } else if (line.startsWith("<<<<< Finished") && started != 0L) {
                    val us = (SystemClock.elapsedRealtimeNanos() - started) / 1_000
                    if (!ours) {
                        loadTotalUs += us
                        loadMaxUs = maxOf(loadMaxUs, us)
                    }
                    ours = false
                    started = 0L
                }
            }
        }

        fun uninstall() = Looper.getMainLooper().setMessageLogging(null)
    }

    private fun measure(file: File, run: Int, await: Boolean): String {
        val modelLoader = ModelLoader(harness.engine, context)
        val clock = MainThreadClock()
        val runtime = Runtime.getRuntime()
        System.gc()
        val javaBase = runtime.totalMemory() - runtime.freeMemory()
        val nativeBase = Debug.getNativeHeapAllocatedSize()
        val javaPeak = AtomicLong(0)
        val nativePeak = AtomicLong(0)
        val sampling = AtomicBoolean(true)
        val sampler = Thread {
            while (sampling.get()) {
                javaPeak.accumulateAndGet(runtime.totalMemory() - runtime.freeMemory() - javaBase, ::maxOf)
                nativePeak.accumulateAndGet(Debug.getNativeHeapAllocatedSize() - nativeBase, ::maxOf)
                Thread.sleep(5)
            }
        }.apply { start() }

        var updateLoadTotalUs = 0L
        var updateLoadMaxUs = 0L
        fun ours(block: () -> Unit) = harness.runOnMain {
            clock.ours = true
            block()
        }
        fun pump() = ours {
            val start = SystemClock.elapsedRealtimeNanos()
            modelLoader.updateLoad()
            val us = (SystemClock.elapsedRealtimeNanos() - start) / 1_000
            updateLoadTotalUs += us
            updateLoadMaxUs = maxOf(updateLoadMaxUs, us)
            harness.renderFrames(1)
        }

        clock.install()
        val start = SystemClock.elapsedRealtime()
        var instance: ModelInstance? = null
        var outcome = "ok"
        var progressReports = 0
        val location = Uri.fromFile(file).toString()
        val load = Thread {
            try {
                instance = runBlocking {
                    if (await) {
                        modelLoader.awaitModelInstance(location) { progressReports++ }
                    } else {
                        modelLoader.loadModelInstance(location)
                    }
                }
                if (instance == null) outcome = "null"
            } catch (error: Throwable) {
                outcome = "threw ${error.javaClass.simpleName}: ${error.message}"
            }
        }.apply { start() }
        while (load.isAlive) {
            pump()
            Thread.sleep(16)
        }
        val readyMs = SystemClock.elapsedRealtime() - start
        var texturesMs = -1L
        if (instance != null) {
            ours { harness.scene.addEntities(instance!!.entities) }
            while (SystemClock.elapsedRealtime() - start < TEXTURE_TIMEOUT_MS) {
                pump()
                var done = false
                ours { done = !modelLoader.isLoading }
                if (done) {
                    texturesMs = SystemClock.elapsedRealtime() - start
                    break
                }
                Thread.sleep(16)
            }
            if (texturesMs < 0) outcome += ", textures never finished (progress ${modelLoader.progress})"
        }
        clock.uninstall()
        sampling.set(false)
        sampler.join()

        var fps = 0.0
        if (instance != null) {
            harness.runOnMain {
                harness.engine.flushAndWait()
                var rendered = 0
                val t0 = SystemClock.elapsedRealtimeNanos()
                repeat(FPS_FRAMES) { i ->
                    if (harness.renderer.beginFrame(harness.swapChain, t0 + i * 16_666_667L)) {
                        harness.renderer.render(harness.view)
                        harness.renderer.endFrame()
                        rendered++
                    }
                }
                harness.engine.flushAndWait()
                fps = rendered * 1e9 / (SystemClock.elapsedRealtimeNanos() - t0)
            }
            harness.runOnMain { harness.scene.removeEntities(instance!!.entities) }
        }
        harness.runOnMain { modelLoader.destroy() }

        return "${file.name} via=${if (await) "await" else "load"} run=$run " +
            "size=${file.length() / 1_000_000}MB outcome=$outcome " +
            "ready=${readyMs}ms textures=${texturesMs}ms " +
            "mainLoad=${clock.loadTotalUs / 1000}ms mainLoadMax=${clock.loadMaxUs / 1000}ms " +
            "updateLoad=${updateLoadTotalUs}us updateLoadMax=${updateLoadMaxUs}us progress=$progressReports " +
            "javaPeak=${javaPeak.get() / 1_000_000}MB nativePeak=${nativePeak.get() / 1_000_000}MB " +
            "fps=${"%.1f".format(fps)}"
    }
}
