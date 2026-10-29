package io.github.sceneview.demo.demos.internal.semantic

import android.content.Context
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.SocketEffect
import okhttp3.OkHttpClient
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * The detector is shared by every replay of the process: the first one that needs it fetches it,
 * the others wait for it and show its progress meanwhile. A download cut short — no network, the
 * replay left — resumes where it stopped; a file that fails its check is not fetched again; one
 * that the runtime refuses is fetched once more, never in a loop — not even one launch after the
 * other, until a copy loads: a file damaged after that is fetched once more. On a metered network
 * nothing is fetched until the user asks, as for the HD pack, and each download asks again; a
 * download already under way goes on.
 *
 * Every download goes to a local fake server, into the app's own storage.
 */
@RunWith(RobolectricTestRunner::class)
class SceneDetectorShareTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val dir = File(context.filesDir, "scene-detector")

    private val payload = ByteArray(300_000) { (it * 31 % 251).toByte() }
    private val sha = sha256(payload)

    /** The real entry, shrunk to a payload the fake server can serve. */
    private val asset = SceneModel.ASSET.copy(file = sha, sha256 = sha, bytes = payload.size.toLong())

    private val part get() = File(dir, "$sha.part")

    @Before
    fun emptyStorage() {
        dir.deleteRecursively()
    }

    @Test
    fun `offline, a later replay resumes the download where it stopped`() = runBlocking {
        MockWebServer().use { server ->
            // The connection drops halfway through the body.
            server.enqueue(ok(payload).newBuilder().onResponseBody(SocketEffect.CloseSocket()).build())
            server.start()
            val detectors = share(server)
            val states = ArrayList<SceneModelState>()

            assertNull(detectors.share { states += it })

            assertEquals(SceneModelState.Offline, states.last())
            val kept = part.length().toInt()
            assertTrue("kept $kept B of ${payload.size}", kept in 1 until payload.size)
            assertNull(server.takeRequest(5, TimeUnit.SECONDS)!!.headers["Range"])

            server.enqueue(rest(kept))
            val detector = detectors.share()

            assertEquals("bytes=$kept-", server.takeRequest(5, TimeUnit.SECONDS)!!.headers["Range"])
            assertEquals(FakeDetector.name, detector!!.name)
            assertEquals(listOf(sha), dir.list()!!.toList())
        }
    }

    @Test
    fun `a file that fails its check is not fetched again`() = runBlocking {
        MockWebServer().use { server ->
            val wrong = payload.copyOf().also { it[0] = (it[0] + 1).toByte() }
            server.enqueue(ok(wrong))
            server.enqueue(ok(wrong))
            server.start()
            val detectors = share(server)
            val states = ArrayList<SceneModelState>()

            assertNull(detectors.share { states += it })
            assertEquals(SceneModelState.Unusable, states.last())

            val again = ArrayList<SceneModelState>()
            assertNull(detectors.share { again += it })

            assertEquals(listOf<SceneModelState>(SceneModelState.Unusable), again)
            assertEquals(1, server.requestCount)
            assertFalse("a failed file was kept", part.exists())
        }
    }

    @Test
    fun `leaving the replay mid-download keeps what arrived, and the next replay resumes it`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(slow(payload))
            server.start()
            val detectors = share(server)

            val replay = launch(Dispatchers.Default) { detectors.share() }
            until("a part of the file") { part.length() > 0 }
            replay.cancelAndJoin()

            val kept = part.length().toInt()
            assertTrue("kept $kept B of ${payload.size}", kept in 1 until payload.size)
            server.takeRequest(5, TimeUnit.SECONDS)

            server.enqueue(rest(kept))
            assertNotNull(detectors.share())
            assertEquals("bytes=$kept-", server.takeRequest(5, TimeUnit.SECONDS)!!.headers["Range"])
        }
    }

    @Test
    fun `a second replay opened during the download shows its progress`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(slow(payload))
            server.start()
            val detectors = share(server)

            val first = async(Dispatchers.Default) { detectors.share() }
            until("the first replay's download") { part.length() > 0 }
            val states = Collections.synchronizedList(ArrayList<SceneModelState>())
            val second = async(Dispatchers.Default) { detectors.share { states += it } }
            until("the second replay's progress") { states.any { it is SceneModelState.Downloading } }

            assertFalse("the progress came after the download", first.isCompleted)
            assertNotNull(first.await())
            assertNotNull(second.await())
            assertEquals(SceneModelState.Ready, states.last())
            assertEquals(1, server.requestCount)
        }
    }

    @Test
    fun `a model the runtime refuses is fetched once more, then given up`() = runBlocking {
        MockWebServer().use { server ->
            repeat(3) { server.enqueue(ok(payload)) }
            server.start()
            var opened = 0
            val detectors = share(server, opens = { opened++; null })
            val states = ArrayList<SceneModelState>()

            assertNull(detectors.share { states += it })

            assertEquals(2, opened)
            assertEquals(2, server.requestCount)
            assertEquals(SceneModelState.Unusable, states.last())

            assertNull(detectors.share())
            assertEquals(2, opened)
            assertEquals(2, server.requestCount)
        }
    }

    @Test
    fun `a device LiteRT has no library for keeps its copy and fetches no other`() = runBlocking {
        MockWebServer().use { server ->
            repeat(2) { server.enqueue(ok(payload)) }
            server.start()
            val opened = AtomicInteger()
            // What LiteRT throws on a 32-bit x86 device: the file is not at fault.
            val noRuntime = { _: File ->
                opened.incrementAndGet()
                throw UnsatisfiedLinkError("no LiteRT library for this ABI")
            }
            val detectors = share(server, opens = noRuntime)
            val states = ArrayList<SceneModelState>()

            val first = runCatching { detectors.share { states += it } }

            assertTrue("the replay failed: ${first.exceptionOrNull()}", first.isSuccess)
            assertNull(first.getOrNull())
            assertEquals(SceneModelState.Unusable, states.last())
            assertEquals("fetched again for a runtime that is missing", 1, server.requestCount)
            assertNotNull("the copy was deleted", store(server).cached())
            assertFalse("the copy was marked as fetched again", store(server).isRefetched())

            assertNull(detectors.share())
            assertEquals(1, opened.get())

            // The next launch tries the copy it kept, and downloads nothing.
            val next = runCatching { share(server, opens = noRuntime).share() }
            assertTrue("the next launch failed: ${next.exceptionOrNull()}", next.isSuccess)
            assertEquals(1, server.requestCount)
        }
    }

    @Test
    fun `with no LiteRT library, opening the detector says so rather than refuse the file`() = runBlocking {
        // This JVM has no LiteRT library, as a 32-bit x86 device has none.
        dir.mkdirs()
        val file = File(dir, sha).apply { writeBytes(payload) }

        val thrown = runCatching { openSceneDetector(file) }.exceptionOrNull()

        assertTrue("got $thrown", thrown is UnsatisfiedLinkError)
    }

    @Test
    fun `a device LiteRT ships no library for downloads nothing`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(ok(payload))
            server.start()
            val opened = AtomicInteger()
            val opens = { _: File -> opened.incrementAndGet().let { FakeDetector } }
            // A 32-bit x86 device: the app runs as x86, which LiteRT ships no library for.
            val detectors = share(server, opens = opens, abis = listOf("x86"))
            val states = ArrayList<SceneModelState>()

            val shared = detectors.share { states += it }

            assertEquals("10 MB downloaded for a runtime that cannot run", 0, server.requestCount)
            assertEquals(0, opened.get())
            assertNull(shared)
            assertEquals(listOf<SceneModelState>(SceneModelState.Unusable), states)
        }
    }

    @Test
    fun `on mobile data, a device LiteRT ships no library for asks for no tap`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(ok(payload))
            server.start()
            // An x86 device that also translates ARM code still runs the app as x86, its first ABI.
            val detectors = share(server, metered = { true }, abis = listOf("x86", "armeabi-v7a"))
            val states = Collections.synchronizedList(ArrayList<SceneModelState>())

            val replay = async(Dispatchers.Default) { detectors.share { states += it } }
            val answered = withTimeoutOrNull(2_000) { replay.join() } != null
            if (!answered) replay.cancelAndJoin()

            assertTrue("still waiting for a tap; heard $states", answered)
            assertEquals(listOf<SceneModelState>(SceneModelState.Unusable), states.toList())
            assertEquals(0, server.requestCount)
        }
    }

    @Test
    fun `an x86_64 device, the emulator's ABI, downloads and loads it`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(ok(payload))
            server.start()

            assertNotNull(share(server, abis = listOf("x86_64", "x86")).share())
            assertEquals(1, server.requestCount)
        }
    }

    @Test
    fun `a model refused once loads from its second download`() = runBlocking {
        MockWebServer().use { server ->
            repeat(2) { server.enqueue(ok(payload)) }
            server.start()
            var opened = 0
            val detectors = share(server, opens = { if (opened++ == 0) null else FakeDetector })

            assertNotNull(detectors.share())
            assertEquals(2, server.requestCount)
        }
    }

    @Test
    fun `a model refused after its second download is not fetched again at the next launch`() = runBlocking {
        MockWebServer().use { server ->
            repeat(3) { server.enqueue(ok(payload)) }
            server.start()
            var opened = 0
            assertNull(share(server, opens = { opened++; null }).share())
            assertEquals(2, server.requestCount)

            // The app restarts: a new share on the same storage, the runtime refusing still.
            val states = ArrayList<SceneModelState>()
            assertNull(share(server, opens = { opened++; null }).share { states += it })

            assertEquals("the copy kept on the device was not tried", 3, opened)
            assertEquals("a third copy was fetched", 2, server.requestCount)
            assertEquals(SceneModelState.Unusable, states.last())

            // A launch where the runtime takes it uses that same copy, with no download.
            assertNotNull(share(server).share())
            assertEquals(2, server.requestCount)
        }
    }

    @Test
    fun `a copy that loads clears the mark, so a file damaged later is fetched once more`() = runBlocking {
        MockWebServer().use { server ->
            repeat(4) { server.enqueue(ok(payload)) }
            server.start()
            // The runtime refuses the first copy and takes the one fetched again.
            var opened = 0
            assertNotNull(share(server, opens = { if (opened++ == 0) null else FakeDetector }).share())
            assertEquals(2, server.requestCount)
            assertFalse("the mark outlived a copy that loaded", store(server).isRefetched())

            // Later the file is damaged on the device — same size, other bytes — and the runtime
            // refuses it: the next launch fetches it once more, and only once.
            File(dir, sha).let { file -> file.writeBytes(file.readBytes().also { it[0] = (it[0] + 1).toByte() }) }
            val states = ArrayList<SceneModelState>()
            assertNotNull(share(server, opens = { intactOrRefused(it) }).share { states += it })

            assertEquals("the damaged copy was not fetched exactly once more", 3, server.requestCount)
            assertEquals(SceneModelState.Ready, states.last())
            assertFalse("the mark outlived a copy that loaded", store(server).isRefetched())
        }
    }

    @Test
    fun `a replay opened on mobile data while another downloads shows that download`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(slow(payload))
            server.start()
            val metered = AtomicBoolean(false)
            val detectors = share(server, metered = metered::get)

            val first = async(Dispatchers.Default) { detectors.share() }
            until("the first replay's download") { part.length() > 0 }
            // The phone left the Wi-Fi mid-download: the download under way goes on, asked for nothing.
            metered.set(true)
            val states = Collections.synchronizedList(ArrayList<SceneModelState>())
            val second = async(Dispatchers.Default) { detectors.share { states += it } }
            until("the second replay's progress") { states.any { it is SceneModelState.Downloading } }

            assertFalse("the second replay asked for a download under way: $states", SceneModelState.Metered in states)
            assertNotNull(first.await())
            assertNotNull(second.await())
            assertEquals(1, server.requestCount)
        }
    }

    @Test
    fun `a download cut on mobile data waits for the go before another replay resumes it`() = runBlocking {
        MockWebServer().use { server ->
            // Trickling, then the connection drops halfway through the body.
            server.enqueue(slow(payload).newBuilder().onResponseBody(SocketEffect.CloseSocket()).build())
            server.start()
            val metered = AtomicBoolean(false)
            val detectors = share(server, metered = metered::get)

            val first = async(Dispatchers.Default) { detectors.share() }
            until("the first replay's download") { part.length() > 0 }
            metered.set(true)
            val states = Collections.synchronizedList(ArrayList<SceneModelState>())
            val second = async(Dispatchers.Default) { detectors.share { states += it } }
            until("the second replay's progress") { states.any { it is SceneModelState.Downloading } }

            // The download it followed is cut: on mobile data, resuming it is the user's call.
            assertNull(first.await())
            until("the second replay's question") { SceneModelState.Metered in states }
            assertEquals(1, server.requestCount)
            assertTrue(second.isActive)

            server.enqueue(rest(part.length().toInt()))
            detectors.allowMetered()
            assertNotNull(second.await())
            assertEquals(2, server.requestCount)
        }
    }

    @Test
    fun `on a metered network nothing is fetched before the tap`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(ok(payload))
            server.start()
            val detectors = share(server, metered = { true })
            val states = Collections.synchronizedList(ArrayList<SceneModelState>())

            val replay = async(Dispatchers.Default) { detectors.share { states += it } }
            until("the download card") { SceneModelState.Metered in states }
            delay(200)

            assertEquals(0, server.requestCount)
            assertFalse(replay.isCompleted)

            detectors.allowMetered()

            assertNotNull(replay.await())
            assertEquals(1, server.requestCount)
            assertEquals(SceneModelState.Ready, states.last())

            // Once on the device it is used on any network, the next launch of the app included.
            val nextLaunch = ArrayList<SceneModelState>()
            assertNotNull(share(server, metered = { true }).share { nextLaunch += it })
            assertEquals(listOf<SceneModelState>(SceneModelState.Ready), nextLaunch)
            assertEquals(1, server.requestCount)
        }
    }

    @Test
    fun `on mobile data, a copy fetched again after a refusal waits for a new tap`() = runBlocking {
        MockWebServer().use { server ->
            repeat(2) { server.enqueue(ok(payload)) }
            server.start()
            val opened = AtomicInteger()
            val refusedOnce = { _: File -> if (opened.getAndIncrement() == 0) null else FakeDetector }
            val detectors = share(server, metered = { true }, opens = refusedOnce)
            val states = Collections.synchronizedList(ArrayList<SceneModelState>())

            val replay = async(Dispatchers.Default) { detectors.share { states += it } }
            until("the download card") { SceneModelState.Metered in states }
            detectors.allowMetered()

            // The tap was for one download: the runtime refused that copy, and another one on
            // mobile data is asked for again.
            val cards = { synchronized(states) { states.count { it == SceneModelState.Metered } } }
            until("the second download card") { cards() == 2 }
            delay(200)
            assertEquals(1, server.requestCount)
            assertFalse("a second copy was fetched on mobile data without a tap", replay.isCompleted)

            detectors.allowMetered()
            assertNotNull(replay.await())
            assertEquals(2, server.requestCount)
            assertEquals(SceneModelState.Ready, states.last())
        }
    }

    @Test
    fun `nothing of ONNX Runtime runs at the app's launch`() {
        @Suppress("DEPRECATION")
        val providers = context.packageManager
            .getPackageInfo(context.packageName, PackageManager.GET_PROVIDERS)
            .providers.orEmpty().map { it.name }
        // The merged manifest is the one read here: an app with no provider at all proves nothing.
        assertTrue("no provider read from the merged manifest", providers.isNotEmpty())
        val onnx = providers.filter { it.startsWith("ai.onnxruntime.") }
        assertTrue("started at every cold start: $onnx", onnx.isEmpty())
    }

    private fun share(
        server: MockWebServer,
        metered: () -> Boolean = { false },
        opens: (File) -> SceneDetector? = { FakeDetector },
        abis: List<String> = listOf("arm64-v8a"),
    ) = SceneDetectorShare(
        storeOf = { store(server) },
        metered = metered,
        open = { file -> opens(file) },
        abis = abis,
    )

    /** The detector's store on the app's storage, as a launch of the app builds it. */
    private fun store(server: MockWebServer) =
        SceneModelStore(dir, OkHttpClient(), server.url("/").toString().trimEnd('/'), asset)

    /** The runtime as it treats a file damaged on the device: it loads only the intact model. */
    private fun intactOrRefused(file: File): SceneDetector? =
        if (file.readBytes().contentEquals(payload)) FakeDetector else null

    private fun ok(body: ByteArray) = MockResponse.Builder().code(200).body(Buffer().write(body)).build()

    /** The whole file, trickling: long enough to leave, or to open a second replay, mid-download. */
    private fun slow(body: ByteArray) = ok(body).newBuilder().throttleBody(8_192, 50, TimeUnit.MILLISECONDS).build()

    /** What is left of the file from [from] on, as a server that honours the resume answers. */
    private fun rest(from: Int) =
        MockResponse.Builder().code(206).body(Buffer().write(payload, from, payload.size - from)).build()

    private suspend fun until(what: String, condition: () -> Boolean) {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!condition()) {
            assertTrue("still waiting for $what", System.nanoTime() < end)
            delay(5)
        }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private object FakeDetector : SceneDetector {
        override val name = "fake"
        override fun detect(image: SceneImage) = emptyList<Detection2D>()
    }
}
