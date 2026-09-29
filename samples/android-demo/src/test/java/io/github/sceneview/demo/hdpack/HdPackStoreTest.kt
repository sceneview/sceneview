package io.github.sceneview.demo.hdpack

import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.WorkInfo
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * The HD pack contract, offline: a content-addressed manifest, a verified and resumable
 * download into `filesDir/hd-pack/<sha256>.<ext>`, and deletion only of what the manifest no
 * longer lists (2026-09-29).
 */
class HdPackStoreTest {

    @get:Rule val tmp = TemporaryFolder()

    private val payload = ByteArray(300_000) { (it * 31 % 251).toByte() }
    private val sha = sha256(payload)
    private val manifest = HdPackManifest(
        version = 1,
        assets = listOf(
            HdAsset(
                id = "flight-helmet",
                title = "Flight Helmet",
                file = "$sha.glb",
                sha256 = sha,
                bytes = payload.size.toLong(),
                license = "CC0-1.0",
                author = "Gary Hsu (Microsoft)",
                source = "https://example.com/flight-helmet",
            ),
        ),
    )
    private val asset = manifest.assets.single()

    // ── Manifest ─────────────────────────────────────────────────────────

    @Test fun `the bundled manifest parses and is content addressed`() {
        val text = File(repoRoot(), "assets/hd-pack/android.json").readText()
        val parsed = HdPackManifest.parse(text)
        val helmet = parsed.asset("flight-helmet")!!
        assertEquals("${helmet.sha256}.glb", helmet.file)
        assertEquals(
            "https://github.com/sceneview/sceneview/releases/download/hd-pack-v1/${helmet.file}",
            helmet.url,
        )
        assertEquals(parsed.assets.sumOf { it.bytes }, parsed.totalBytes)
    }

    @Test fun `a file not named after its hash is refused`() {
        val bad = """{"version":1,"assets":[{"id":"a","title":"A","file":"helmet.glb","sha256":"$sha",""" +
            """"bytes":1,"license":"CC0-1.0","author":"x","source":"y"}]}"""
        assertParseFails(bad)
    }

    @Test fun `an unknown schema version is refused, not guessed at`() {
        assertParseFails("""{"version":2,"assets":[]}""")
    }

    @Test fun `duplicate ids are refused`() {
        val entry = """{"id":"a","title":"A","file":"$sha.glb","sha256":"$sha","bytes":1,""" +
            """"license":"CC0-1.0","author":"x","source":"y"}"""
        assertParseFails("""{"version":1,"assets":[$entry,$entry]}""")
    }

    @Test fun `the bundled manifest lists the museum scans at real-world scale`() {
        val parsed = HdPackManifest.parse(File(repoRoot(), "assets/hd-pack/android.json").readText())
        // The ids iOS matches 1:1 — renaming one orphans the other platform's entry.
        assertEquals(
            listOf("flight-helmet", "apollo11-exterior", "apollo11-interior", "woolly-mammoth", "perseverance"),
            parsed.assets.map { it.id },
        )
        // The Smithsonian Apollo scans are authored in centimetres.
        assertEquals(0.01f, parsed.asset("apollo11-exterior")!!.scale)
        assertEquals(0.01f, parsed.asset("apollo11-interior")!!.scale)
        assertEquals(1f, parsed.asset("woolly-mammoth")!!.scale)
        assertEquals(1f, parsed.asset("perseverance")!!.scale)
        // An entry with no scale is in metres already.
        assertEquals(1f, parsed.asset("flight-helmet")!!.scale)
        for (asset in parsed.assets) {
            assertTrue("${asset.id} has no credit", asset.author.isNotBlank() && asset.license.isNotBlank())
            assertTrue("${asset.id} source is not a link", asset.source.startsWith("http"))
        }
    }

    @Test fun `a zero or negative scale is refused`() {
        for (scale in listOf("0", "-1", "0.0")) {
            assertParseFails(
                """{"version":1,"assets":[{"id":"a","title":"A","file":"$sha.glb","sha256":"$sha",""" +
                    """"bytes":1,"scale":$scale,"license":"CC0-1.0","author":"x","source":"y"}]}""",
            )
        }
    }

    // ── Order and progress ───────────────────────────────────────────────

    @Test fun `the model on screen downloads first, then the smallest files`() {
        fun entry(id: String, bytes: Long) = asset.copy(id = id, bytes = bytes)
        val store = HdPackStore(
            HdPackManifest(1, listOf(entry("big", 30), entry("small", 10), entry("mid", 20))),
            tmp.newFolder(),
        )
        assertEquals(listOf("small", "mid", "big"), store.downloadOrder().map { it.id })
        assertEquals(listOf("big", "small", "mid"), store.downloadOrder(first = "big").map { it.id })
        assertEquals(listOf("small", "mid", "big"), store.downloadOrder(first = "unknown").map { it.id })
    }

    @Test fun `the pill reads its own file's progress, not the pack's`() {
        val transfer = HdTransfer(doneBytes = 60, totalBytes = 100, assetId = "mammoth", assetDoneBytes = 10, assetBytes = 40)
        assertEquals(0.6f, transfer.fraction)
        assertEquals(0.25f, transfer.fractionOf("mammoth"))
        // Another file is on the wire: this one is queued, not at 60 %.
        assertNull(transfer.fractionOf("perseverance"))
    }

    // ── Download ─────────────────────────────────────────────────────────

    @Test fun `a download is verified then renamed into place`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse.Builder().code(200).body(Buffer().write(payload)).build())
            server.start()
            val dir = tmp.newFolder("hd-pack")
            val store = store(server, dir)

            assertNull(store.readyFile(asset.id))
            store.downloadMissing()

            val file = store.readyFile(asset.id)!!
            assertEquals(File(dir, "$sha.glb"), file)
            assertTrue(file.readBytes().contentEquals(payload))
            assertFalse(File(dir, "$sha.glb.part").exists())
            assertTrue(store.isComplete)
            assertNull(store.transfer.value)
            assertEquals("/$sha.glb", server.takeRequest(5, TimeUnit.SECONDS)!!.url.encodedPath)
        }
    }

    @Test fun `an interrupted download resumes from the part file`() = runBlocking {
        MockWebServer().use { server ->
            val half = payload.size / 2
            server.enqueue(
                MockResponse.Builder().code(206)
                    .body(Buffer().write(payload, half, payload.size - half)).build(),
            )
            server.start()
            val dir = tmp.newFolder("hd-pack")
            File(dir, "$sha.glb.part").writeBytes(payload.copyOfRange(0, half))

            val store = store(server, dir)
            store.downloadMissing()

            assertEquals("bytes=$half-", server.takeRequest(5, TimeUnit.SECONDS)!!.headers["Range"])
            assertTrue(store.readyFile(asset.id)!!.readBytes().contentEquals(payload))
        }
    }

    @Test fun `a server that ignores the range restarts the file`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse.Builder().code(200).body(Buffer().write(payload)).build())
            server.start()
            val dir = tmp.newFolder("hd-pack")
            File(dir, "$sha.glb.part").writeBytes(ByteArray(1_000) { 7 })

            val store = store(server, dir)
            store.downloadMissing()

            assertTrue(store.readyFile(asset.id)!!.readBytes().contentEquals(payload))
        }
    }

    @Test fun `a corrupt download is deleted and never becomes ready`() = runBlocking {
        MockWebServer().use { server ->
            val corrupt = payload.copyOf().also { it[10] = (it[10] + 1).toByte() }
            server.enqueue(MockResponse.Builder().code(200).body(Buffer().write(corrupt)).build())
            server.start()
            val dir = tmp.newFolder("hd-pack")
            val store = store(server, dir)

            try {
                store.downloadMissing()
                fail("a hash mismatch must throw")
            } catch (expected: HdPackIntegrityException) {
                // The part file goes with it: the next attempt starts clean.
            }
            assertNull(store.readyFile(asset.id))
            assertEquals(0, dir.listFiles()!!.size)
        }
    }

    @Test fun `an HTTP error keeps the part file for the next attempt`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse.Builder().code(503).build())
            server.start()
            val dir = tmp.newFolder("hd-pack")
            File(dir, "$sha.glb.part").writeBytes(payload.copyOfRange(0, 1_000))
            val store = store(server, dir)

            try {
                store.downloadMissing()
                fail("a 503 must throw")
            } catch (expected: java.io.IOException) {
                // expected
            }
            assertEquals(1_000L, File(dir, "$sha.glb.part").length())
        }
    }

    // ── Storage ──────────────────────────────────────────────────────────

    @Test fun `prune deletes only hashes the manifest no longer lists`() {
        val dir = tmp.newFolder("hd-pack")
        File(dir, "$sha.glb").writeBytes(payload)
        File(dir, "$sha.glb.part").writeBytes(ByteArray(3))
        File(dir, "${"0".repeat(64)}.glb").writeBytes(ByteArray(3))

        val store = HdPackStore(manifest, dir, OkHttpClient(), "http://unused")
        store.prune()

        assertEquals(setOf("$sha.glb", "$sha.glb.part"), dir.list()!!.toSet())
        assertTrue(store.isComplete)
    }

    @Test fun `remove frees every byte and resets readiness`() = runBlocking {
        val dir = tmp.newFolder("hd-pack")
        File(dir, "$sha.glb").writeBytes(payload)
        val store = HdPackStore(manifest, dir, OkHttpClient(), "http://unused")
        assertTrue(store.isComplete)

        val freed = store.removeAll()

        assertEquals(payload.size.toLong(), freed)
        assertEquals(0L, store.bytesOnDisk())
        assertFalse(store.isComplete)
        assertNull(store.readyFile(asset.id))
    }

    // ── Status ───────────────────────────────────────────────────────────

    @Test fun `status reads the pending job's network constraint`() {
        assertEquals(HdPackStatus.Ready, HdPack.statusOf(true, null, null))
        assertEquals(HdPackStatus.NotDownloaded, HdPack.statusOf(false, null, null))
        assertEquals(
            HdPackStatus.Downloading(0.25f),
            HdPack.statusOf(false, HdTransfer(25, 100), work(WorkInfo.State.RUNNING, NetworkType.UNMETERED)),
        )
        assertEquals(
            HdPackStatus.WaitingForWifi,
            HdPack.statusOf(false, null, work(WorkInfo.State.ENQUEUED, NetworkType.UNMETERED)),
        )
        assertEquals(
            HdPackStatus.WaitingForNetwork,
            HdPack.statusOf(false, null, work(WorkInfo.State.ENQUEUED, NetworkType.CONNECTED)),
        )
    }

    @Test fun `a job that gave up reads as failed, a cancelled one as not downloaded`() {
        assertEquals(
            HdPackStatus.Failed,
            HdPack.statusOf(false, null, work(WorkInfo.State.FAILED, NetworkType.CONNECTED)),
        )
        assertEquals(
            HdPackStatus.NotDownloaded,
            HdPack.statusOf(false, null, work(WorkInfo.State.CANCELLED, NetworkType.UNMETERED)),
        )
        assertEquals(
            HdPackStatus.Ready,
            HdPack.statusOf(true, null, work(WorkInfo.State.FAILED, NetworkType.CONNECTED)),
        )
    }

    private fun work(state: WorkInfo.State, network: NetworkType) = WorkInfo(
        id = UUID.randomUUID(),
        state = state,
        tags = emptySet(),
        constraints = Constraints.Builder().setRequiredNetworkType(network).build(),
    )

    private fun store(server: MockWebServer, dir: File) = HdPackStore(
        manifest,
        dir,
        OkHttpClient(),
        server.url("/").toString().trimEnd('/'),
    )

    private fun assertParseFails(text: String) {
        try {
            HdPackManifest.parse(text)
            fail("expected the manifest to be refused: $text")
        } catch (expected: IllegalArgumentException) {
            // expected
        }
    }

    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "assets/hd-pack/android.json").isFile) dir = dir.parentFile
        return dir ?: error("repo root not found")
    }

    private fun sha256(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
