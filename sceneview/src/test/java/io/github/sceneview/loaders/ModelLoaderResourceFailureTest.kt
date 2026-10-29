package io.github.sceneview.loaders

import android.os.Looper
import android.util.Log
import com.google.android.filament.gltfio.AssetLoader
import com.google.android.filament.gltfio.FilamentAsset
import com.google.android.filament.gltfio.ResourceLoader
import io.github.sceneview.model.Model
import io.github.sceneview.node.FilamentWithoutJni
import io.github.sceneview.utils.readBuffer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
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
import org.robolectric.shadows.ShadowLog
import java.io.File
import java.nio.Buffer
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

/**
 * What a load does with the external resources of a `.gltf` (its `.bin` buffers, its textures),
 * and what `clear()` / `destroy()` may cancel.
 *
 * A real [ModelLoader] over a Filament whose natives return zero (Robolectric instruments
 * `com.google.android.filament`), except where the shadows below say otherwise: every parse
 * "succeeds" with asset handle [ASSET], which names the external resources [resourceUris], and
 * gltfio's `asyncBeginLoad` answers [beginAccepted], `asyncGetLoadProgress` answers [loadProgress],
 * and `asyncCancelLoad` / `evictResourceData` calls are counted.
 */
@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(
    instrumentedPackages = ["com.google.android.filament"],
    shadows = [
        ModelLoaderResourceFailureTest.ParsingAssetLoader::class,
        ModelLoaderResourceFailureTest.ListedResourcesAsset::class,
        ModelLoaderResourceFailureTest.AnsweringResourceLoader::class,
    ],
)
class ModelLoaderResourceFailureTest {

    private lateinit var loader: ModelLoader

    @Before
    fun setUp() {
        destroyedAssets.clear()
        resourceUris = arrayOf(TEXTURE)
        beginAccepted = true
        loadProgress = 0f
        assetHandle = ASSET
        begunAssets.clear()
        cancelLoadCalls.set(0)
        evictCalls.set(0)
        ShadowLog.clear()
        loader = ModelLoader(FilamentWithoutJni.engine(), RuntimeEnvironment.getApplication())
    }

    @After
    fun tearDown() {
        destroyedAssets.clear()
    }

    @Test
    fun `a resource next to a glTF at the root of assets resolves to its bare name`() {
        assertEquals("texture.png", ModelLoader.getFolderPath("chair.gltf", "texture.png"))
        assertEquals("models/texture.png", ModelLoader.getFolderPath("models/chair.gltf", "texture.png"))
        assertEquals(
            "file:///data/models/scene.bin",
            ModelLoader.getFolderPath("file:///data/models/scene.gltf", "scene.bin"),
        )
    }

    @Test
    fun `a missing texture loads the model without it, and says so`() {
        // TEXTURE resolves next to the model file, where it does not exist.
        val result = await { loader.loadModelAsync(modelFileUri(), onResult = it) }

        assertTrue("onResult must be called", result.called)
        assertNotNull("A missing texture must not fail the whole load", result.value)
        assertEquals("The loaded model must stay alive", emptyList<Long>(), destroyedAssets.toList())
        assertWarned(TEXTURE, "model.gltf", "loading the model without it")
    }

    @Test
    fun `a missing buffer fails the load and destroys the model`() {
        resourceUris = arrayOf(BUFFER, TEXTURE)

        val result = await { loader.loadModelAsync(modelFileUri(), onResult = it) }

        assertTrue("onResult must be called", result.called)
        assertNull("A model without its geometry buffer must not be handed out", result.value)
        assertEquals("The model created before the miss must be destroyed", listOf(ASSET), destroyedAssets.toList())
        assertWarned(BUFFER, "model.gltf", "the model was destroyed")
    }

    @Test
    fun `a present buffer loads, even when a texture is missing`() {
        resourceUris = arrayOf(BUFFER, TEXTURE)

        val result = await { loader.loadModelAsync(modelFileUri(withBuffer = true), onResult = it) }

        assertTrue("onResult must be called", result.called)
        assertNotNull(result.value)
        assertEquals(emptyList<Long>(), destroyedAssets.toList())
    }

    @Test
    fun `a begin gltfio refuses fails the load and destroys the model`() {
        resourceUris = emptyArray()
        refuseBeginBeforeQueuingTextures()

        val result = await { loader.loadModelAsync(modelFileUri(), onResult = it) }

        assertTrue("onResult must be called", result.called)
        assertNull(result.value)
        assertEquals(listOf(ASSET), destroyedAssets.toList())
        assertWarned("refused", "model.gltf", "meshopt")
        assertNoBlockingCancel()
    }

    @Test
    fun `createModel throws on a missing buffer and destroys the model`() {
        resourceUris = arrayOf(BUFFER, TEXTURE)

        val failure = runCatching { loader.createModel(File(java.net.URI(modelFileUri()))) }.exceptionOrNull()

        assertTrue("Expected IllegalArgumentException, got $failure", failure is IllegalArgumentException)
        assertEquals(listOf(ASSET), destroyedAssets.toList())
        assertWarned(BUFFER, "the model was destroyed")
    }

    @Test
    fun `createModel skips a missing texture and keeps the model`() {
        resourceUris = arrayOf(BUFFER, TEXTURE)

        val model = loader.createModel(File(java.net.URI(modelFileUri(withBuffer = true))))

        assertNotNull(model)
        assertEquals(emptyList<Long>(), destroyedAssets.toList())
        assertWarned(TEXTURE, "loading the model without it")
    }

    @Test
    fun `createModel throws when gltfio refuses the begin`() {
        resourceUris = emptyArray()
        refuseBeginBeforeQueuingTextures()

        val failure = runCatching { loader.createModel(File(java.net.URI(modelFileUri()))) }.exceptionOrNull()

        assertTrue("Expected IllegalArgumentException, got $failure", failure is IllegalArgumentException)
        assertEquals(listOf(ASSET), destroyedAssets.toList())
        assertWarned("refused", "meshopt")
        assertNoBlockingCancel()
    }

    @Test
    fun `a refused begin leaves the load still decoding in flight, so its destroy waits`() {
        resourceUris = emptyArray()
        // Model A begins its load and still has textures decoding.
        assetHandle = DECODING_ASSET
        loadProgress = 0.5f
        val decoding = await { loader.loadModelAsync(modelFileUri(), onResult = it) }.value
        assertNotNull(decoding)

        // Model B is refused: gltfio returned before draining A's decoders.
        assetHandle = REFUSED_ASSET
        beginAccepted = false
        val refused = await { loader.loadModelAsync(modelFileUri(), onResult = it) }

        assertNull(refused.value)
        assertEquals("The refused model is freed at once", listOf(REFUSED_ASSET), destroyedAssets.toList())
        assertEquals(
            "The native load must be pointed back at A",
            listOf(DECODING_ASSET, REFUSED_ASSET, DECODING_ASSET),
            begunAssets.toList(),
        )
        assertEquals("A's decoding must not be cancelled by B's refusal", 0, cancelLoadCalls.get())

        loader.destroyModel(decoding!!)

        assertTrue(
            "A must not be freed while its textures are decoding (#3868): deferred or cancelled first",
            DECODING_ASSET !in destroyedAssets || cancelLoadCalls.get() > 0,
        )
    }

    @Test
    fun `createModel chains the resolver's IOException of a missing buffer, logged once`() {
        resourceUris = arrayOf(BUFFER, TEXTURE)
        val unreadable = java.io.IOException("disk gone")

        val failure = runCatching {
            loader.createModel(File(java.net.URI(modelFileUri()))) { uri ->
                if (uri == BUFFER) throw unreadable else null
            }
        }.exceptionOrNull()

        assertTrue("Expected IllegalArgumentException, got $failure", failure is IllegalArgumentException)
        assertSame("The resolver's IOException must be the cause", unreadable, failure?.cause)
        assertEquals(listOf(ASSET), destroyedAssets.toList())
        assertEquals(
            "The buffer miss must be logged exactly once",
            1,
            warnings().count { BUFFER in it.msg },
        )
        assertEquals(
            "The texture miss must be logged exactly once",
            1,
            warnings().count { TEXTURE in it.msg },
        )
    }

    @Test
    fun `createModel logs a texture IOException once, with the error`() {
        resourceUris = arrayOf(TEXTURE)
        val unreadable = java.io.IOException("disk gone")

        val model = loader.createModel(File(java.net.URI(modelFileUri()))) { throw unreadable }

        assertNotNull(model)
        val textureWarnings = warnings().filter { TEXTURE in it.msg }
        assertEquals(1, textureWarnings.size)
        assertSame(unreadable, textureWarnings.single().throwable)
        assertTrue(textureWarnings.single().msg.contains("loading the model without it"))
    }

    @Test
    fun `createModel destroys the model and rethrows when the resolver throws`() {
        resourceUris = arrayOf(BUFFER, TEXTURE)

        val failure = runCatching {
            loader.createModel(File(java.net.URI(modelFileUri()))) { throw SecurityException("denied") }
        }.exceptionOrNull()

        assertTrue("Expected the resolver's SecurityException, got $failure", failure is SecurityException)
        assertEquals(listOf(ASSET), destroyedAssets.toList())
    }

    @Test
    fun `createModelInstance throws when its resolver clears the loader, and asks gltfio nothing more`() {
        resourceUris = arrayOf(BUFFER, TEXTURE)
        val asked = mutableListOf<String>()

        val failure = runCatching {
            loader.createModelInstance(File(java.net.URI(modelFileUri()))) { asked += it; loader.clear(); null }
        }.exceptionOrNull()

        assertTrue("Expected IllegalStateException, got $failure", failure is IllegalStateException)
        assertTrue(failure!!.message!!, "resource resolver destroyed" in failure.message!!)
        assertEquals("the resolver is not asked again once the model is gone", listOf(BUFFER), asked)
        assertEquals(listOf(ASSET), destroyedAssets.toList())
        assertEquals("no asyncBeginLoad on the freed asset", emptyList<Long>(), begunAssets.toList())
    }

    @Test
    fun `createModel throws when its resolver destroys the loader`() {
        val failure = runCatching {
            loader.createModel(File(java.net.URI(modelFileUri()))) { loader.destroy(); null }
        }.exceptionOrNull()

        assertTrue("Expected IllegalStateException, got $failure", failure is IllegalStateException)
        assertEquals(listOf(ASSET), destroyedAssets.toList())
        assertEquals("no asyncBeginLoad on a freed loader", emptyList<Long>(), begunAssets.toList())
    }

    @Test
    fun `createInstancedModel throws on a missing buffer and destroys the model`() {
        resourceUris = arrayOf(BUFFER, TEXTURE)

        val failure = runCatching {
            loader.createInstancedModel(File(java.net.URI(modelFileUri())).readBuffer(), count = 2)
        }.exceptionOrNull()

        assertTrue("Expected IllegalArgumentException, got $failure", failure is IllegalArgumentException)
        assertEquals(listOf(ASSET), destroyedAssets.toList())
        assertWarned(BUFFER, "the model was destroyed")
    }

    @Test
    fun `createModel never asks the resolver for a data URI`() {
        val dataUri = "data:application/octet-stream;base64,AAAAAA=="
        resourceUris = arrayOf(dataUri, TEXTURE)
        val asked = mutableListOf<String>()

        val model = loader.createModel(File(java.net.URI(modelFileUri()))) { asked += it; null }

        assertNotNull(model)
        assertEquals(listOf(TEXTURE), asked)
    }

    @Test
    fun `a load failing after the model was created destroys that model`() {
        val result = await {
            loader.loadModelAsync(
                modelFileUri(),
                resourceResolver = { error("No location for $it") },
                onResult = it,
            )
        }

        assertTrue("onResult must be called", result.called)
        assertNull(result.value)
        assertEquals(
            "The model created before the failure must not stay registered (a leak per retry)",
            listOf(ASSET),
            destroyedAssets.toList(),
        )
    }

    @Test
    fun `clear and destroy cancel the loader's loads, not the scope it was given`() {
        val callerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val unrelated = callerScope.launch { awaitCancellation() }
        try {
            val scoped = ModelLoader(FilamentWithoutJni.engine(), RuntimeEnvironment.getApplication(), callerScope)

            scoped.clear()
            assertTrue("clear() must not cancel the caller's other coroutines", unrelated.isActive)

            scoped.destroy()
            assertTrue("destroy() must not cancel the caller's other coroutines", unrelated.isActive)
        } finally {
            callerScope.cancel()
        }
    }

    /** Every parse succeeds, with handle [ASSET]; destroys are recorded. */
    @Implements(AssetLoader::class)
    @Suppress("UnusedParameter", "FunctionOnlyReturningConstant") // Robolectric shadows mirror native signatures
    class ParsingAssetLoader {
        companion object {
            @JvmStatic
            @Implementation
            fun nCreateAssetLoader(nativeEngine: Long, provider: Any?, nativeEntities: Long): Long = 1L

            @JvmStatic
            @Implementation
            fun nCreateAsset(nativeLoader: Long, buffer: Buffer, remaining: Int): Long = assetHandle

            @JvmStatic
            @Implementation
            fun nCreateInstancedAsset(nativeLoader: Long, buffer: Buffer, remaining: Int, instances: LongArray): Long =
                ASSET

            @JvmStatic
            @Implementation
            fun nDestroyAsset(nativeLoader: Long, nativeAsset: Long) {
                destroyedAssets += nativeAsset
            }
        }
    }

    /** Every asset names the external resources [resourceUris]. */
    @Implements(FilamentAsset::class)
    @Suppress("UnusedParameter") // Robolectric shadows mirror native signatures
    class ListedResourcesAsset {
        companion object {
            @JvmStatic
            @Implementation
            fun nGetResourceUriCount(nativeAsset: Long): Int = resourceUris.size

            @JvmStatic
            @Implementation
            fun nGetResourceUris(nativeAsset: Long, result: Array<String?>) {
                resourceUris.copyInto(result)
            }
        }
    }

    /** gltfio's `asyncBeginLoad` answers [beginAccepted]. */
    @Implements(ResourceLoader::class)
    @Suppress("UnusedParameter") // Robolectric shadows mirror native signatures
    class AnsweringResourceLoader {
        companion object {
            @JvmStatic
            @Implementation
            fun nAsyncBeginLoad(nativeLoader: Long, nativeAsset: Long): Boolean {
                begunAssets += nativeAsset
                return beginAccepted
            }

            @JvmStatic
            @Implementation
            fun nAsyncGetLoadProgress(nativeLoader: Long): Float = loadProgress

            @JvmStatic
            @Implementation
            fun nAsyncCancelLoad(nativeLoader: Long) {
                cancelLoadCalls.incrementAndGet()
            }

            @JvmStatic
            @Implementation
            fun nEvictResourceData(nativeLoader: Long) {
                evictCalls.incrementAndGet()
            }
        }
    }

    /**
     * gltfio refusing a fresh asset (its meshopt data fails to decode) before it queues any
     * texture: with nothing pending, it reports a progress of 1 (`ResourceLoader.cpp`).
     */
    private fun refuseBeginBeforeQueuingTextures() {
        beginAccepted = false
        loadProgress = 1f
    }

    /** The refused load was detached without `asyncCancelLoad` (a `flushAndWait`), its data evicted. */
    private fun assertNoBlockingCancel() {
        assertEquals("asyncCancelLoad flushes the engine on the main thread", 0, cancelLoadCalls.get())
        assertTrue("The resources fed to the refused load must be evicted", evictCalls.get() > 0)
    }

    private fun warnings() = ShadowLog.getLogsForTag("ModelLoader").filter { it.type == Log.WARN }

    /** Some `Log.w` under the `ModelLoader` tag mentions every one of [fragments]. */
    private fun assertWarned(vararg fragments: String) {
        val warnings = warnings()
        assertTrue(
            "Expected a ModelLoader warning naming ${fragments.toList()}, got ${warnings.map { it.msg }}",
            warnings.any { log -> fragments.all { it in log.msg } },
        )
    }

    private class Result(var called: Boolean = false, var value: Model? = null)

    /** Starts a load and pumps the main looper (where `onResult` is delivered) for up to 5 s. */
    private fun await(start: ((Model?) -> Unit) -> Unit): Result {
        val result = Result()
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

    /**
     * A `model.gltf` that declares [BUFFER] as its buffer and [TEXTURE] as its image. Alone in its
     * folder, so neither resource exists next to it — except [BUFFER] when [withBuffer].
     */
    private fun modelFileUri(withBuffer: Boolean = false): String {
        val folder = File.createTempFile("model", "").apply {
            delete()
            mkdirs()
            deleteOnExit()
        }
        val file = File(folder, "model.gltf").apply {
            writeText(
                """{"asset":{"version":"2.0"},"buffers":[{"uri":"$BUFFER","byteLength":4}],""" +
                    """"images":[{"uri":"$TEXTURE"}]}""",
            )
            deleteOnExit()
        }
        if (withBuffer) File(folder, BUFFER).apply { writeBytes(ByteArray(4)) }.deleteOnExit()
        return "file://${file.absolutePath}"
    }

    private companion object {
        const val ASSET = 7L
        const val DECODING_ASSET = 11L
        const val REFUSED_ASSET = 12L
        const val BUFFER = "scene.bin"
        const val TEXTURE = "missing.png"

        @Volatile
        var resourceUris: Array<String> = arrayOf(TEXTURE)

        @Volatile
        var beginAccepted = true

        @Volatile
        var loadProgress = 0f

        @Volatile
        var assetHandle = ASSET

        val begunAssets: MutableList<Long> = Collections.synchronizedList(mutableListOf<Long>())

        val cancelLoadCalls = AtomicInteger()
        val evictCalls = AtomicInteger()

        val destroyedAssets: MutableList<Long> = Collections.synchronizedList(mutableListOf<Long>())
    }
}
