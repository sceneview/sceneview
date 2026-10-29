package io.github.sceneview.loaders

import android.os.Looper
import com.google.android.filament.Engine
import com.google.android.filament.gltfio.AssetLoader
import io.github.sceneview.node.FilamentWithoutJni
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.LooperMode
import java.io.File
import java.util.Collections

/**
 * A failed `load*Async` reports `null` and leaves the loader working; `clear()` does not end it.
 *
 * A real [ModelLoader] with its default scope, over a Filament whose native methods return zero
 * (Robolectric instruments `com.google.android.filament`; only `AssetLoader`'s constructor needs a
 * non-zero handle, see [LiveAssetLoader]). A file that does exist therefore
 * "parses" to `null` without throwing, which isolates [ModelLoader.clear] from the failure path.
 *
 * Before the fix, the loader's scope was a plain `CoroutineScope(Dispatchers.IO)`: a missing asset
 * or an unreachable URL threw out of the `launch`, reached the thread's uncaught-exception handler
 * (a process crash on Android), cancelled the scope, and every later `load*Async` on that loader
 * was a silent no-op. `clear()` cancelled the scope itself, with the same result.
 */
@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(
    instrumentedPackages = ["com.google.android.filament"],
    shadows = [ModelLoaderAsyncFailureTest.LiveAssetLoader::class],
)
class ModelLoaderAsyncFailureTest {

    private lateinit var engine: Engine
    private lateinit var loader: ModelLoader
    private val uncaught = Collections.synchronizedList(mutableListOf<Throwable>())
    private var previousHandler: Thread.UncaughtExceptionHandler? = null

    @Before
    fun setUp() {
        previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, e -> uncaught += e }
        engine = FilamentWithoutJni.engine()
        loader = ModelLoader(engine, RuntimeEnvironment.getApplication())
    }

    @After
    fun tearDown() {
        Thread.setDefaultUncaughtExceptionHandler(previousHandler)
    }

    @Test
    fun `a missing asset reports null and never reaches the uncaught handler`() {
        val result = awaitModel { loader.loadModelAsync("models/does-not-exist.glb", onResult = it) }

        assertTrue("onResult must be called for a failed load", result.called)
        assertNull(result.value)
        assertEquals("A failed load must not reach the app's uncaught handler", emptyList<Throwable>(), uncaught.toList())
    }

    @Test
    fun `an unreachable URL reports null and never reaches the uncaught handler`() {
        // Port 1 on loopback: refused at once, no real network involved.
        val result = awaitInstance {
            loader.loadModelInstanceAsync("http://127.0.0.1:1/model.glb", onResult = it)
        }

        assertTrue("onResult must be called for a failed load", result.called)
        assertNull(result.value)
        assertEquals("A failed load must not reach the app's uncaught handler", emptyList<Throwable>(), uncaught.toList())
    }

    @Test
    fun `the loader still loads after a failed load`() {
        awaitModel { loader.loadModelAsync("models/does-not-exist.glb", onResult = it) }

        val second = awaitModel { loader.loadModelAsync(existingFileUri(), onResult = it) }

        assertTrue("A load after a failed one must still report its result", second.called)
    }

    @Test
    fun `the loader still loads after clear`() {
        loader.clear()

        val result = awaitModel { loader.loadModelAsync(existingFileUri(), onResult = it) }

        assertTrue("clear() must not end the loader: a later load must report its result", result.called)
    }

    /** gltfio's `AssetLoader` refuses a zero native handle; give it one. Nothing else differs. */
    @Implements(AssetLoader::class)
    @Suppress("UnusedParameter", "FunctionOnlyReturningConstant") // Robolectric shadows mirror native signatures
    class LiveAssetLoader {
        companion object {
            @JvmStatic
            @Implementation
            fun nCreateAssetLoader(nativeEngine: Long, provider: Any?, nativeEntities: Long): Long = 1L
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────────────────────

    private class Result<T>(var called: Boolean = false, var value: T? = null)

    private fun awaitModel(start: ((io.github.sceneview.model.Model?) -> Unit) -> Unit) =
        await<io.github.sceneview.model.Model>(start)

    private fun awaitInstance(start: ((io.github.sceneview.model.ModelInstance?) -> Unit) -> Unit) =
        await<io.github.sceneview.model.ModelInstance>(start)

    /** Starts a load and pumps the main looper (where `onResult` is delivered) for up to 5 s. */
    private fun <T> await(start: ((T?) -> Unit) -> Unit): Result<T> {
        val result = Result<T>()
        start { value ->
            result.called = true
            result.value = value
        }
        val deadline = System.currentTimeMillis() + 5_000
        while (!result.called && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        return result
    }

    /** A file that exists: with no native Filament it parses to `null`, without an exception. */
    private fun existingFileUri(): String {
        val file = File.createTempFile("model", ".glb").apply {
            writeBytes(ByteArray(64))
            deleteOnExit()
        }
        return "file://${file.absolutePath}"
    }
}
