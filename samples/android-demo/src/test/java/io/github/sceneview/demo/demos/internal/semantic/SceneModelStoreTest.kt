package io.github.sceneview.demo.demos.internal.semantic

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * The furniture detector is not in the APK. The first replay that needs it downloads it from the
 * `assets-v1` release, checks its SHA-256 and size, and keeps it in app storage; every later
 * replay reuses that file. Offline, or with a file that fails its check, recognition turns itself
 * off and the replay runs without it.
 *
 * Every download here goes to a local fake server: no test touches the network.
 */
class SceneModelStoreTest {

    @get:Rule val tmp = TemporaryFolder()

    private val payload = ByteArray(300_000) { (it * 31 % 251).toByte() }
    private val sha = sha256(payload)

    /** The real entry, shrunk to a payload the fake server can serve. */
    private val asset = SceneModel.ASSET.copy(file = sha, sha256 = sha, bytes = payload.size.toLong())

    // ── The published file ───────────────────────────────────────────────

    @Test fun `the app fetches the published YOLOX-Tiny by its fingerprint`() {
        assertEquals(YOLOX_SHA256, SceneModel.SHA256)
        assertEquals(YOLOX_BYTES, SceneModel.BYTES)
        assertEquals(
            "https://github.com/sceneview/sceneview/releases/download/assets-v1/$YOLOX_SHA256",
            SceneModel.URL,
        )
        // The release names each file after its hash, so the URL can never serve another model.
        assertEquals(YOLOX_SHA256, SceneModel.ASSET.file)
        assertEquals(YOLOX_SHA256, SceneModel.ASSET.sha256)
        assertEquals(YOLOX_BYTES, SceneModel.ASSET.bytes)
        assertEquals("Apache-2.0", SceneModel.ASSET.license)
    }

    @Test fun `the APK carries no detector`() {
        val root = repoRoot()
        val manifest = Json.parseToJsonElement(File(root, "assets/manifest.json").readText()).jsonObject
        val paths = manifest.getValue("files").jsonArray.map { it.jsonObject.getValue("path").jsonPrimitive.content }
        val models = paths.filter { path -> MODEL_EXTENSIONS.any { path.endsWith(".$it") } }
        assertTrue("fetched into the APK: $models", models.isEmpty())
        val dirs = manifest.getValue("scopes").jsonObject.values
            .flatMap { scope -> scope.jsonObject.getValue("dirs").jsonArray.map { it.jsonPrimitive.content } }
        assertTrue("an ML folder is still fetched into the APK", dirs.none { it.startsWith("$APK_ASSETS/ml") })
        // A model left over from an older fetch would still be packaged by a local build.
        val stray = File(root, APK_ASSETS).walk().filter { it.extension in MODEL_EXTENSIONS }.toList()
        assertTrue("delete $stray: it would be packaged into the APK", stray.isEmpty())
    }

    @Test fun `the detector's licence travels with the app`() {
        val credits = File(repoRoot(), "$APK_ASSETS/CREDITS.md").readText()
        val downloaded = credits.substringAfter(DOWNLOADED_HEADING, missingDelimiterValue = "")
        assertTrue("CREDITS.md has no \"$DOWNLOADED_HEADING\" section", downloaded.isNotEmpty())
        val line = downloaded.lines().first { YOLOX_FILE in it }
        assertTrue(line, "Apache-2.0" in line)
        assertTrue(line, "https://github.com/Megvii-BaseDetection/YOLOX" in line)
        // Listed as downloaded, not among the files bundled in the APK.
        assertFalse(credits.substringBefore(DOWNLOADED_HEADING).contains(YOLOX_FILE))
        // The file the app runs is a conversion: its credit says so, and from what.
        val note = downloaded.lines().dropWhile { YOLOX_FILE !in it }.drop(1).first()
        assertTrue(note, "onnx2tf" in note)
    }

    // ── Download ─────────────────────────────────────────────────────────

    @Test fun `the first use downloads the detector, checks it and keeps it`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(ok(payload))
            server.start()
            val dir = tmp.newFolder("scene-detector")
            val states = ArrayList<SceneModelState>()

            val file = store(server, dir).fetch { states += it }

            assertNotNull(file)
            assertTrue(file!!.readBytes().contentEquals(payload))
            assertEquals(dir, file.parentFile)
            assertEquals("/$sha", server.takeRequest(5, TimeUnit.SECONDS)!!.url.encodedPath)
            // The pill shows progress while the file is on the wire, then goes away.
            assertTrue("no progress shown: $states", states.first() is SceneModelState.Downloading)
            assertEquals(SceneModelState.Ready, states.last())
            val fractions = states.filterIsInstance<SceneModelState.Downloading>().map { it.fraction }
            assertEquals(fractions.sorted(), fractions)
            assertTrue(fractions.all { it in 0f..1f })
            // Renamed into place: nothing half-written is left beside it.
            assertEquals(listOf(sha), dir.list()!!.toList())
        }
    }

    @Test fun `a detector already on the device is reused without a request`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(ok(payload))
            server.start()
            val dir = tmp.newFolder("scene-detector")
            assertNotNull(store(server, dir).fetch())
            assertEquals(1, server.requestCount)

            // The next launch of the app: a new store on the same storage.
            val states = ArrayList<SceneModelState>()
            val file = store(server, dir).fetch { states += it }

            assertTrue(file!!.readBytes().contentEquals(payload))
            assertEquals(1, server.requestCount)
            assertEquals(listOf<SceneModelState>(SceneModelState.Ready), states)
            assertEquals(file, store(server, dir).cached())
        }
    }

    @Test fun `a file that fails its fingerprint is deleted and recognition turns off`() = runBlocking {
        MockWebServer().use { server ->
            val corrupt = payload.copyOf().also { it[10] = (it[10] + 1).toByte() }
            server.enqueue(ok(corrupt))
            server.start()
            val dir = tmp.newFolder("scene-detector")
            val states = ArrayList<SceneModelState>()

            assertNull(store(server, dir).fetch { states += it })

            assertEquals(SceneModelState.Unusable, states.last())
            assertEquals(0, dir.listFiles()!!.size)
            assertNull(store(server, dir).cached())
        }
    }

    @Test fun `a copy damaged on the device since its download is fetched again at the next launch`() = runBlocking {
        MockWebServer().use { server ->
            repeat(2) { server.enqueue(ok(payload)) }
            server.start()
            val dir = tmp.newFolder("scene-detector")
            val file = store(server, dir).fetch()!!
            // Same size, one byte changed: a size check alone takes it for the file it was.
            file.writeBytes(file.readBytes().also { it[10] = (it[10] + 1).toByte() })

            // The next launch of the app hashes the copy before the runtime opens it.
            val nextLaunch = store(server, dir)
            assertNull("a damaged copy passed for the detector", nextLaunch.cached())
            val again = nextLaunch.fetch()

            assertEquals(2, server.requestCount)
            assertTrue(again!!.readBytes().contentEquals(payload))
            assertEquals(listOf(sha), dir.list()!!.toList())
        }
    }

    @Test fun `a file of the wrong size is deleted too`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(ok(payload + ByteArray(1_000)))
            server.start()
            val dir = tmp.newFolder("scene-detector")
            val states = ArrayList<SceneModelState>()

            assertNull(store(server, dir).fetch { states += it })

            assertEquals(SceneModelState.Unusable, states.last())
            assertEquals(0, dir.listFiles()!!.size)
        }
    }

    @Test fun `offline, recognition turns off and a later replay downloads the detector`() = runBlocking {
        val dir = tmp.newFolder("scene-detector")
        val states = ArrayList<SceneModelState>()
        // A server that is gone: the connection is refused, as on a phone with no network.
        val gone = MockWebServer().apply { start() }
        val goneUrl = gone.url("/").toString().trimEnd('/')
        gone.close()

        assertNull(SceneModelStore(dir, OkHttpClient(), goneUrl, asset).fetch { states += it })
        assertEquals(SceneModelState.Offline, states.last())
        assertNull(SceneModelStore(dir, OkHttpClient(), goneUrl, asset).cached())

        MockWebServer().use { server ->
            server.enqueue(ok(payload))
            server.start()
            assertTrue(store(server, dir).fetch()!!.readBytes().contentEquals(payload))
        }
    }

    @Test fun `a file not found reads as unavailable, not as no connection`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse.Builder().code(404).build())
            server.start()
            val states = ArrayList<SceneModelState>()

            assertNull(store(server, tmp.newFolder("scene-detector")).fetch { states += it })

            assertEquals(SceneModelState.Unavailable, states.last())
        }
    }

    @Test fun `a server error reads as unavailable and keeps what already arrived`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse.Builder().code(503).build())
            server.start()
            val dir = tmp.newFolder("scene-detector")
            File(dir, "$sha.part").writeBytes(payload.copyOfRange(0, 1_000))
            val states = ArrayList<SceneModelState>()

            assertNull(store(server, dir).fetch { states += it })

            assertEquals(SceneModelState.Unavailable, states.last())
            assertEquals(1_000L, File(dir, "$sha.part").length())
        }
    }

    @Test fun `a download cut mid-file resumes where it stopped`() = runBlocking {
        MockWebServer().use { server ->
            // The connection drops halfway through the body.
            server.enqueue(
                MockResponse.Builder().code(200).body(Buffer().write(payload))
                    .onResponseBody(SocketEffect.CloseSocket()).build(),
            )
            server.start()
            val dir = tmp.newFolder("scene-detector")
            val states = ArrayList<SceneModelState>()

            assertNull(store(server, dir).fetch { states += it })

            assertEquals(SceneModelState.Offline, states.last())
            val kept = File(dir, "$sha.part").length().toInt()
            assertTrue("kept $kept B of ${payload.size}", kept in 1 until payload.size)
            assertNull(server.takeRequest(5, TimeUnit.SECONDS)!!.headers["Range"])

            server.enqueue(
                MockResponse.Builder().code(206).body(Buffer().write(payload, kept, payload.size - kept)).build(),
            )
            val file = store(server, dir).fetch()

            assertEquals("bytes=$kept-", server.takeRequest(5, TimeUnit.SECONDS)!!.headers["Range"])
            assertTrue(file!!.readBytes().contentEquals(payload))
            assertEquals(listOf(sha), dir.list()!!.toList())
        }
    }

    @Test fun `a server that ignores the resume restarts the file cleanly`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(ok(payload))
            server.start()
            val dir = tmp.newFolder("scene-detector")
            File(dir, "$sha.part").writeBytes(ByteArray(1_000) { 7 })

            val file = store(server, dir).fetch()

            assertEquals("bytes=1000-", server.takeRequest(5, TimeUnit.SECONDS)!!.headers["Range"])
            assertTrue(file!!.readBytes().contentEquals(payload))
            assertEquals(listOf(sha), dir.list()!!.toList())
        }
    }

    private fun store(server: MockWebServer, dir: File) =
        SceneModelStore(dir, OkHttpClient(), server.url("/").toString().trimEnd('/'), asset)

    private fun ok(body: ByteArray) = MockResponse.Builder().code(200).body(Buffer().write(body)).build()

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** The repository root, wherever Gradle runs the unit tests from. */
    private fun repoRoot(): File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "assets/manifest.json").isFile }

    private companion object {
        /** YOLOX-Tiny 0.1.1rc0 converted to LiteRT (float16), as published on `assets-v1`. */
        const val YOLOX_SHA256 = "d1d61787dcd087234e41914688315b69f502a8dfdfb10982cdcd3b7c57549e0a"
        const val YOLOX_BYTES = 10_178_328L
        const val YOLOX_FILE = "yolox_tiny.tflite"

        /** No model of either runtime may be packaged into the APK. */
        val MODEL_EXTENSIONS = setOf("onnx", "tflite")
        const val APK_ASSETS = "samples/android-demo/src/main/assets"
        const val DOWNLOADED_HEADING = "## Downloaded on first use"
    }
}
