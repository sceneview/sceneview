package io.github.sceneview

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Looper
import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composition
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.platform.LocalContext
import com.google.android.filament.gltfio.AssetLoader
import io.github.sceneview.loaders.ModelLoader
import io.github.sceneview.node.FilamentWithoutJni
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
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
import java.nio.Buffer
import java.util.Collections

/**
 * `rememberModelInstance(modelLoader, "models/x.gltf")` converts the model off the main thread and
 * calls Filament on it.
 *
 * The fixture is a glTF with one embedded WebP texture: loading it decodes that texture
 * ([BitmapFactory], recorded here) before Filament parses the result ([AssetLoader]'s native
 * `nCreateAsset`, recorded too). The composition runs headless on the main looper thread, as an
 * app's does. Before the fix, the asset overload read the bytes on IO and then converted them in
 * the composition's own coroutine: the decode ran on the main thread.
 */
@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(
    instrumentedPackages = ["com.google.android.filament"],
    shadows = [
        RememberModelInstanceThreadTest.RecordingBitmapFactory::class,
        RememberModelInstanceThreadTest.RecordingAssetLoader::class,
    ],
)
class RememberModelInstanceThreadTest {

    private val context: Context = RuntimeEnvironment.getApplication()
    private val mainThread: Thread = Looper.getMainLooper().thread

    @Before
    fun reset() {
        decodeThreads.clear()
        createAssetThreads.clear()
    }

    @Test
    fun `the asset overload decodes textures off the main thread and parses on it`() = runTest {
        val loader = ModelLoader(FilamentWithoutJni.engine(), context)
        val host = Host(this, context) { rememberModelInstance(loader, "models/webp-texture.gltf") }

        host.settleUntil { createAssetThreads.isNotEmpty() }

        assertEquals("The fixture's one WebP texture is decoded", 1, decodeThreads.size)
        val onMain = decodeThreads.count { it === mainThread }
        println("rememberModelInstance(asset): texture decodes on the main thread = $onMain of ${decodeThreads.size}")
        assertEquals(
            "Texture decoding must not run on the main thread (ran on ${decodeThreads.map { it.name }})",
            0,
            onMain,
        )
        assertTrue("The model was parsed at least once", createAssetThreads.isNotEmpty())
        assertTrue(
            "Filament's parse must stay on the main thread (ran on ${createAssetThreads.map { it.name }})",
            createAssetThreads.all { it === mainThread },
        )
    }

    /** Records the thread of every decode; decodes nothing, so the texture is left as it was. */
    @Implements(BitmapFactory::class)
    @Suppress("UnusedParameter", "FunctionOnlyReturningConstant") // Robolectric shadows mirror native signatures
    class RecordingBitmapFactory {
        companion object {
            @JvmStatic
            @Implementation
            fun decodeByteArray(data: ByteArray, offset: Int, length: Int, opts: BitmapFactory.Options?): Bitmap? {
                decodeThreads += Thread.currentThread()
                return null
            }
        }
    }

    /** gltfio's loader with a non-zero handle (its constructor demands one), recording parses. */
    @Implements(AssetLoader::class)
    @Suppress("UnusedParameter", "FunctionOnlyReturningConstant") // Robolectric shadows mirror native signatures
    class RecordingAssetLoader {
        companion object {
            @JvmStatic
            @Implementation
            fun nCreateAssetLoader(nativeEngine: Long, provider: Any?, nativeEntities: Long): Long = 1L

            @JvmStatic
            @Implementation
            fun nCreateAsset(nativeLoader: Long, buffer: Buffer, remaining: Int): Long {
                createAssetThreads += Thread.currentThread()
                return 0L
            }
        }
    }

    /** A no-UI composition of [content], on the test's (main looper) thread. */
    private class Host(
        private val scope: TestScope,
        context: Context,
        content: @androidx.compose.runtime.Composable () -> Unit,
    ) {
        private val clock = BroadcastFrameClock()
        private val recomposer = Recomposer(scope.backgroundScope.coroutineContext + clock)

        init {
            scope.backgroundScope.launch(clock) { recomposer.runRecomposeAndApplyChanges() }
            Composition(NoNodes(), recomposer).setContent {
                CompositionLocalProvider(LocalContext provides context) { content() }
            }
        }

        /** Lets the load's hops (IO, Default, the main looper, back here) run, for up to 5 s. */
        fun settleUntil(done: () -> Boolean) {
            val deadline = System.currentTimeMillis() + 5_000
            while (!done() && System.currentTimeMillis() < deadline) {
                Snapshot.sendApplyNotifications()
                scope.runCurrent()
                shadowOf(Looper.getMainLooper()).idle()
                Thread.sleep(10)
            }
        }
    }

    private class NoNodes : AbstractApplier<Unit>(Unit) {
        override fun insertTopDown(index: Int, instance: Unit) = Unit
        override fun insertBottomUp(index: Int, instance: Unit) = Unit
        override fun remove(index: Int, count: Int) = Unit
        override fun move(from: Int, to: Int, count: Int) = Unit
        override fun onClear() = Unit
    }

    private companion object {
        val decodeThreads: MutableList<Thread> = Collections.synchronizedList(mutableListOf())
        val createAssetThreads: MutableList<Thread> = Collections.synchronizedList(mutableListOf())
    }
}
