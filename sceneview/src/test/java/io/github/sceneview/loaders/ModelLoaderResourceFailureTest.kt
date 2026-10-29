package io.github.sceneview.loaders

import android.os.Looper
import android.util.Log
import com.google.android.filament.Engine
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
import org.junit.Assert.fail
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
import java.util.concurrent.atomic.AtomicReference

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
    private lateinit var engine: Engine

    @Before
    fun setUp() {
        destroyedAssets.clear()
        instancedAssets.clear()
        resourceUris = arrayOf(TEXTURE)
        beginAccepted = true
        loadProgress = 0f
        assetHandle = ASSET
        begunAssets.clear()
        cancelLoadCalls.set(0)
        evictCalls.set(0)
        fedResources.clear()
        ShadowLog.clear()
        engine = FilamentWithoutJni.engine()
        loader = ModelLoader(engine, RuntimeEnvironment.getApplication())
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
    fun `a percent-encoded buffer is also fed under the name cgltf decodes it to`() {
        // cgltf decodes a buffer uri before it looks it up among the resources fed to gltfio.
        val encoded = "my%20model.bin"
        resourceUris = arrayOf(encoded)

        // A `file://` location is a URL: its resolver reads `my model.bin`, a `File` resolver the name as it is.
        val fileUrl = modelFileUri(withBuffer = true, buffer = encoded, bufferFile = "my model.bin")
        val result = await { loader.loadModelAsync(fileUrl, onResult = it) }

        assertNotNull(result.value)
        assertEquals(listOf(encoded, "my model.bin"), fedResources.toList())

        fedResources.clear()
        loader.createModel(File(java.net.URI(modelFileUri(withBuffer = true, buffer = encoded))))
        assertEquals("the synchronous load too", listOf(encoded, "my model.bin"), fedResources.toList())

        fedResources.clear()
        resourceUris = arrayOf(BUFFER)
        loader.createModel(File(java.net.URI(modelFileUri(withBuffer = true))))
        assertEquals("a plain name is fed once", listOf(BUFFER), fedResources.toList())
    }

    @Test
    fun `a decoded name that is another resource's is not fed over it`() {
        // gltfio replaces a resource fed twice under one name: `a b.bin` would get the 4 bytes of `a%20b.bin`.
        listOf(listOf("a b.bin", "a%20b.bin"), listOf("a%20b.bin", "a b.bin")).forEach { order ->
            fedResources.clear()
            resourceUris = order.toTypedArray()
            val buffers = order.joinToString(",") { """{"uri":"$it","byteLength":${if (it == "a b.bin") 12 else 4}}""" }
            val model = gltfIn("""{"asset":{"version":"2.0"},"buffers":[$buffers]}""", mapOf("a b.bin" to 12, "a%20b.bin" to 4))

            loader.createModel(model)

            assertEquals("each fed once, under its own name", order, fedResources.toList())
        }
    }

    @Test
    fun `loadModel does not feed a decoded name that is another resource's over it`() {
        // A `file://` location is a URL: its resolver reads the file `a b.bin` for both.
        listOf(listOf("a b.bin", "a%20b.bin"), listOf("a%20b.bin", "a b.bin")).forEach { order ->
            fedResources.clear()
            resourceUris = order.toTypedArray()
            val buffers = order.joinToString(",") { """{"uri":"$it","byteLength":${if (it == "a b.bin") 12 else 4}}""" }
            val model = gltfIn("""{"asset":{"version":"2.0"},"buffers":[$buffers]}""", mapOf("a b.bin" to 12))

            val result = await { loader.loadModelAsync("file://${model.absolutePath}", onResult = it) }

            assertNotNull(result.value)
            assertEquals("each fed once, under its own name", order, fedResources.toList())
        }
    }

    @Test
    fun `a name two buffer uris decode to is fed for neither, and refused when read`() {
        // gltfio keeps the last resource fed under one name: `a%20b.bin` would get the 4 bytes of `a%20%62.bin`.
        listOf(listOf("a%20b.bin", "a%20%62.bin"), listOf("a%20%62.bin", "a%20b.bin")).forEach { order ->
            resourceUris = order.toTypedArray()
            val buffers = order.joinToString(",") { """{"uri":"$it","byteLength":${if (it == "a%20b.bin") 100 else 4}}""" }
            val files = mapOf("a%20b.bin" to 100, "a%20%62.bin" to 4)

            fedResources.clear()
            loader.createModel(gltfIn("""{"asset":{"version":"2.0"},"buffers":[$buffers]}""", files))
            assertEquals("unread: each fed under its own name only", order, fedResources.toList())

            fedResources.clear()
            val read = """{"asset":{"version":"2.0"},"bufferViews":[{"buffer":${order.indexOf("a%20b.bin")},""" +
                """"byteLength":100}],"buffers":[$buffers]}"""
            val failure = runCatching { loader.createModel(gltfIn(read, files)) }.exceptionOrNull()
            assertTrue("Expected IllegalArgumentException, got $failure", failure is IllegalArgumentException)
            assertTrue(failure!!.message, "decodes to a b.bin, as another buffer uri does" in failure.message.orEmpty())
            assertTrue("a b.bin" !in fedResources)
        }
    }

    @Test
    fun `an image is fed under its own uri only, as gltfio looks it up`() {
        resourceUris = arrayOf("my%20model.bin", "my%20albedo.png")
        val model = gltfIn(
            """{"asset":{"version":"2.0"},"buffers":[{"uri":"my%20model.bin","byteLength":4}],""" +
                """"images":[{"uri":"my%20albedo.png"}]}""",
            mapOf("my%20model.bin" to 4, "my%20albedo.png" to 4),
        )

        loader.createModel(model)

        assertEquals(listOf("my%20model.bin", "my model.bin", "my%20albedo.png"), fedResources.toList())
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

    @Test
    fun `createModel refuses an external buffer shorter than it declares, and destroys the model`() {
        resourceUris = arrayOf(BUFFER)

        val failure = runCatching {
            loader.createModel(File(java.net.URI(modelFileUri(withBuffer = true, bufferBytes = 2))))
        }.exceptionOrNull()

        assertTrue("Expected GltfLimitException, got $failure", failure is GltfLimitException)
        assertTrue(failure!!.message!!.contains("shorter than the 4 it declares"))
        assertEquals(listOf(ASSET), destroyedAssets.toList())
        assertEquals("gltfio must never begin reading past the buffer", emptyList<Long>(), begunAssets.toList())
    }

    @Test
    fun `loadModel refuses an external buffer shorter than it declares, and destroys the model`() {
        resourceUris = arrayOf(BUFFER)

        val result = await { loader.loadModelAsync(modelFileUri(withBuffer = true, bufferBytes = 2), onResult = it) }

        assertTrue(result.called)
        assertNull(result.value)
        assertEquals(listOf(ASSET), destroyedAssets.toList())
        assertEquals(emptyList<Long>(), begunAssets.toList())
    }

    @Test
    fun `createModel refuses an accessor at a negative offset with an IllegalArgumentException`() {
        val failure = runCatching { loader.createModel(File(java.net.URI(negativeOffsetGlbUri()))) }.exceptionOrNull()

        assertTrue("Expected GltfLimitException, got $failure", failure is GltfLimitException)
        assertTrue(failure!!.message!!.contains("accessor 1 has a negative byteOffset"))
        assertEquals("gltfio never sees it", emptyList<Long>(), destroyedAssets.toList())
    }

    @Test
    fun `awaitModelInstance relays the refusal of an accessor at a negative offset to its caller`() {
        val failure = awaitFailure { loader.awaitModelInstance(negativeOffsetGlbUri()) }

        assertTrue("Expected GltfLimitException, got $failure", failure is GltfLimitException)
        assertTrue(failure!!.message!!.contains("accessor 1 has a negative byteOffset"))
        assertEquals("gltfio never sees it", emptyList<Long>(), destroyedAssets.toList())
    }

    @Test
    fun `awaitModelInstance relays the refusal of a short external buffer, and destroys the model`() {
        resourceUris = arrayOf(BUFFER)

        val failure = awaitFailure { loader.awaitModelInstance(modelFileUri(withBuffer = true, bufferBytes = 2)) }

        assertTrue("Expected GltfLimitException, got $failure", failure is GltfLimitException)
        assertTrue(failure!!.message!!.contains("shorter than the 4 it declares"))
        assertEquals(listOf(ASSET), destroyedAssets.toList())
        assertEquals("gltfio must never begin reading past the buffer", emptyList<Long>(), begunAssets.toList())
    }

    @Test
    fun `loadModel answers null for an accessor at a negative offset, rather than throwing`() {
        val result = await { loader.loadModelAsync(negativeOffsetGlbUri(), onResult = it) }

        assertTrue(result.called)
        assertNull(result.value)
        assertEquals(emptyList<Long>(), begunAssets.toList())
        assertTrue(
            "the warning names the offset, not an IndexOutOfBoundsException: ${warnings().map { it.msg }}",
            warnings().any { it.msg.contains("accessor 1 has a negative byteOffset") },
        )
    }

    @Test
    fun `createInstance takes a live model of another loader, as it always did`() {
        resourceUris = emptyArray()
        val model = loader.createModel(File(java.net.URI(modelFileUri())), releaseSourceData = false)
        val other = ModelLoader(engine, RuntimeEnvironment.getApplication())

        // The shadow answers no instance; what matters is that SceneView lets the call through.
        assertNull(other.createInstance(model))
        assertEquals("gltfio was asked, for that model's asset", listOf(ASSET), instancedAssets)
    }

    @Test
    fun `createInstance refuses a destroyed model rather than reading its freed handle`() {
        resourceUris = emptyArray()
        loadProgress = 1f
        val model = loader.createModel(File(java.net.URI(modelFileUri())), releaseSourceData = false)
        loader.destroyModel(model)

        try {
            ModelLoader(engine, RuntimeEnvironment.getApplication()).createInstance(model)
            fail("expected an IllegalStateException")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message!!.contains("destroyed model"))
        }
    }

    @Test
    fun `createInstance refuses a model whose destroy waits for its textures`() {
        resourceUris = emptyArray()
        loadProgress = 0.5f
        val model = await { loader.loadModelAsync(modelFileUri(), onResult = it) }.value!!
        loader.destroyModel(model)
        assertTrue("still decoding: the destroy is deferred", ASSET !in destroyedAssets)

        try {
            loader.createInstance(model)
            fail("expected an IllegalStateException")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message!!.contains("destroyed model"))
        }
    }

    @Test
    fun `destroyModel after the engine is gone calls into no native`() {
        resourceUris = emptyArray()
        loadProgress = 0.5f
        val model = await { loader.loadModelAsync(modelFileUri(), onResult = it) }.value!!
        // Its textures are done: an engine still alive would see the model detached and freed at once.
        loadProgress = 1f
        Engine::class.java.getDeclaredField("mNativeObject").apply { isAccessible = true }.setLong(engine, 0L)
        cancelLoadCalls.set(0)
        progressCalls.set(0)

        loader.destroyModel(model)
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals("destroyAsset reaches into the freed engine", emptyList<Long>(), destroyedAssets.toList())
        assertEquals("asyncCancelLoad flushes the freed engine", 0, cancelLoadCalls.get())
        assertEquals("no progress is asked of gltfio either", 0, progressCalls.get())
        assertEquals("the model is forgotten all the same", false, loader.isLoading)
    }

    @Test
    fun `progress read off the main thread is the main thread's last read, not a native call`() {
        resourceUris = emptyArray()
        loadProgress = 0.5f
        await { loader.loadModelAsync(modelFileUri(), onResult = it) }
        loader.updateLoad()
        loadProgress = 0.9f

        var offMain = -1f
        var loadingOffMain = false
        Thread {
            offMain = loader.progress
            loadingOffMain = loader.isLoading
        }.apply { start(); join() }

        assertEquals(0.5f, offMain)
        assertTrue(loadingOffMain)
        assertEquals("the main thread still asks gltfio", 0.9f, loader.progress)
    }

    @Test
    fun `updateLoad asks gltfio for its progress only while a load is pending`() {
        progressCalls.set(0)
        repeat(3) { loader.updateLoad() }
        assertEquals("nothing loading: no progress read, frame after frame", 0, progressCalls.get())

        resourceUris = emptyArray()
        loadProgress = 0.5f
        await { loader.loadModelAsync(modelFileUri(), onResult = it) }
        progressCalls.set(0)
        loader.updateLoad()
        assertEquals("loading: read each frame", 1, progressCalls.get())

        loadProgress = 1f
        loader.updateLoad()
        progressCalls.set(0)
        repeat(3) { loader.updateLoad() }
        assertEquals("loaded: no progress read any more", 0, progressCalls.get())
        assertEquals(false, loader.isLoading)
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

            @JvmStatic
            @Implementation
            fun nCreateInstance(nativeLoader: Long, nativeAsset: Long): Long {
                instancedAssets += nativeAsset
                return 0L
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
            fun nAsyncGetLoadProgress(nativeLoader: Long): Float {
                progressCalls.incrementAndGet()
                return loadProgress
            }

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

            @JvmStatic
            @Implementation
            fun nAddResourceData(nativeLoader: Long, uri: String, buffer: Buffer, remaining: Int) {
                fedResources += uri
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

    /** Runs [block] off the main thread, pumping the main looper for up to 5 s; what it threw. */
    private fun awaitFailure(block: suspend () -> Unit): Throwable? {
        val failure = AtomicReference<Throwable?>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val job = scope.launch { failure.set(runCatching { block() }.exceptionOrNull()) }
        val deadline = System.currentTimeMillis() + 5_000
        while (!job.isCompleted && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        shadowOf(Looper.getMainLooper()).idle()
        scope.cancel()
        assertTrue("the load never finished", job.isCompleted)
        return failure.get()
    }

    /**
     * A `model.gltf` that declares [BUFFER] as its buffer and [TEXTURE] as its image. Alone in its
     * folder, so neither resource exists next to it — except [BUFFER] when [withBuffer], of
     * [bufferBytes] bytes against the 4 it declares.
     */
    private fun modelFileUri(
        withBuffer: Boolean = false,
        bufferBytes: Int = 4,
        buffer: String = BUFFER,
        bufferFile: String = buffer,
    ): String {
        val folder = File.createTempFile("model", "").apply {
            delete()
            mkdirs()
            deleteOnExit()
        }
        val file = File(folder, "model.gltf").apply {
            writeText(
                """{"asset":{"version":"2.0"},"buffers":[{"uri":"$buffer","byteLength":4}],""" +
                    """"images":[{"uri":"$TEXTURE"}]}""",
            )
            deleteOnExit()
        }
        if (withBuffer) File(folder, bufferFile).apply { writeBytes(ByteArray(bufferBytes)) }.deleteOnExit()
        return "file://${file.absolutePath}"
    }

    /** A `model.gltf` of [json], alone in its folder with [files] of the given sizes. */
    private fun gltfIn(json: String, files: Map<String, Int>): File {
        val folder = File.createTempFile("model", "").apply {
            delete()
            mkdirs()
            deleteOnExit()
        }
        files.forEach { (name, size) -> File(folder, name).apply { writeBytes(ByteArray(size)) }.deleteOnExit() }
        return File(folder, "model.gltf").apply {
            writeText(json)
            deleteOnExit()
        }
    }

    /** A GLB whose index accessor starts 4 bytes before its buffer view. */
    private fun negativeOffsetGlbUri(): String {
        val json = """{"asset":{"version":"2.0"},"accessors":[{"componentType":5126,"type":"VEC3","count":3},""" +
            """{"bufferView":0,"byteOffset":-4,"componentType":5123,"type":"SCALAR","count":3}],""" +
            """"bufferViews":[{"buffer":0,"byteLength":8}],"buffers":[{"byteLength":8}],""" +
            """"meshes":[{"primitives":[{"attributes":{"POSITION":0},"indices":1}]}]}"""
        val jsonBytes = json.toByteArray().let { it + ByteArray((4 - it.size % 4) % 4) { ' '.code.toByte() } }
        val total = 12 + 8 + jsonBytes.size + 8 + 8
        val glb = java.nio.ByteBuffer.allocate(total).order(java.nio.ByteOrder.LITTLE_ENDIAN).apply {
            putInt(0x46546C67).putInt(2).putInt(total)
            putInt(jsonBytes.size).putInt(0x4E4F534A).put(jsonBytes)
            putInt(8).putInt(0x004E4942).put(ByteArray(8))
        }.array()
        val file = File.createTempFile("negative-offset", ".glb").apply {
            writeBytes(glb)
            deleteOnExit()
        }
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
        val progressCalls = AtomicInteger()
        val evictCalls = AtomicInteger()
        val fedResources: MutableList<String> = Collections.synchronizedList(mutableListOf<String>())

        val destroyedAssets: MutableList<Long> = Collections.synchronizedList(mutableListOf<Long>())
        val instancedAssets: MutableList<Long> = Collections.synchronizedList(mutableListOf<Long>())
    }
}
