package io.github.sceneview.snapshot

import android.graphics.Bitmap
import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composition
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.graphics.asImageBitmap
import io.github.sceneview.ExperimentalSceneViewApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The Compose wiring of [rememberModelThumbnail] — [rememberThumbnailState] — in a real
 * composition with fake loads: what a cell shows when its key changes, when the cache already
 * holds its image, and when a load it no longer wants completes.
 */
@OptIn(ExperimentalCoroutinesApi::class, ExperimentalSceneViewApi::class)
@RunWith(RobolectricTestRunner::class)
class ThumbnailStateCompositionTest {

    // ImageBitmap(w, h) asks for a colour space Robolectric's legacy bitmaps do not support.
    private val imageA = ModelThumbnailState.Ready(Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888).asImageBitmap())
    private val imageB = ModelThumbnailState.Ready(Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).asImageBitmap())

    @Test
    fun `a request starts loading and publishes its result`() = runTest {
        val host = Host(this)
        assertSame(ModelThumbnailState.Loading, host.value)

        host.finishLoad("a", imageA)

        assertSame(imageA, host.value)
        assertEquals(listOf("a"), host.started)
    }

    @Test
    fun `a key change shows loading at once, never the previous image`() = runTest {
        val host = Host(this)
        host.finishLoad("a", imageA)
        host.seen.clear()

        host.key = "b"
        host.frame()

        assertSame(ModelThumbnailState.Loading, host.value)
        assertTrue("the first composition with key b showed ${host.seen}", host.seen.none { it === imageA })
        host.finishLoad("b", imageB)
        assertSame(imageB, host.value)
    }

    @Test
    fun `a cached image is shown in the first composition and never loaded`() = runTest {
        val host = Host(this, cached = mapOf("b" to imageB))
        host.finishLoad("a", imageA)
        host.seen.clear()

        host.key = "b"
        host.frame()

        assertEquals(listOf<ModelThumbnailState>(imageB), host.seen.distinct())
        assertEquals(listOf("a"), host.started)
    }

    @Test
    fun `a superseded load never overwrites the current key`() = runTest {
        val host = Host(this)
        host.key = "b"
        host.frame()
        host.key = "c"
        host.frame()

        host.finishLoad("a", imageA)
        host.finishLoad("b", imageB)
        assertSame(ModelThumbnailState.Loading, host.value)

        val failed = ModelThumbnailState.Failed("nothing drawn")
        host.finishLoad("c", failed)
        assertSame(failed, host.value)
    }

    @Test
    fun `a failure is final for its key`() = runTest {
        val host = Host(this)
        val failed = ModelThumbnailState.Failed("could not load the model")
        host.finishLoad("a", failed)
        repeat(3) { host.frame() }

        assertSame(failed, host.value)
        assertEquals(listOf("a"), host.started)
        assertEquals(null, failed.bitmapOrNull())
        assertSame(imageA.bitmap, imageA.bitmapOrNull())
    }

    /** A composition with no UI: one [rememberThumbnailState] call, driven frame by frame. */
    private class Host(private val scope: TestScope, cached: Map<String, ModelThumbnailState> = emptyMap()) {
        private val clock = BroadcastFrameClock()
        private val loads = mutableMapOf<String, CompletableDeferred<ModelThumbnailState>>()
        private var frameTime = 0L

        /** Keys whose load started, in order. */
        val started = mutableListOf<String>()

        /** Every value composed, in order. */
        val seen = mutableListOf<ModelThumbnailState>()
        var key by mutableStateOf("a")
        lateinit var value: ModelThumbnailState
            private set

        init {
            val owner = Any()
            val recomposer = Recomposer(scope.backgroundScope.coroutineContext + clock)
            scope.backgroundScope.launch(clock) { recomposer.runRecomposeAndApplyChanges() }
            Composition(NoNodes(), recomposer).setContent {
                val requested = key
                value = rememberThumbnailState(
                    owner = owner,
                    key = requested,
                    cached = { cached[requested] },
                    load = {
                        started += requested
                        load(requested).await()
                    },
                )
                seen += value
            }
            settle()
        }

        private fun load(key: String) = loads.getOrPut(key) { CompletableDeferred() }

        fun finishLoad(key: String, result: ModelThumbnailState) {
            load(key).complete(result)
            frame()
        }

        fun frame() {
            settle()
            clock.sendFrame(frameTime++)
            settle()
        }

        private fun settle() {
            repeat(2) {
                Snapshot.sendApplyNotifications()
                scope.runCurrent()
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
}
