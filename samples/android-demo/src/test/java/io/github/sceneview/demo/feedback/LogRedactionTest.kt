package io.github.sceneview.demo.feedback

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bug report masks what a user chose before the log leaves the device (#4526),
 * and keeps everything a maintainer reads a log for.
 */
class LogRedactionTest {

    private val prefix = "10-19 12:00:00.123  6543  6560 E SceneView: "

    private fun redact(message: String, names: Collection<String> = emptyList()) =
        LogRedaction.redactLine(prefix + message, names).removePrefix(prefix)

    @After
    fun tearDown() = UserChosenNames.clear()

    // ── The line from the issue ──────────────────────────────────────────────

    @Test
    fun `the splat load failure of the issue keeps its authority and extension only`() {
        val line = prefix + "rememberSplatCloud: cannot load " +
            "content://com.android.externalstorage.documents/document/primary%3ADownload%2FMy%20flat.spz"
        assertEquals(
            prefix + "rememberSplatCloud: cannot load " +
                "content://com.android.externalstorage.documents/<redacted>.spz",
            LogRedaction.redactLine(line),
        )
    }

    // ── URIs ─────────────────────────────────────────────────────────────────

    @Test
    fun `content uri without extension`() {
        assertEquals(
            "open content://media/<redacted>",
            redact("open content://media/external/file/1234"),
        )
    }

    @Test
    fun `content uri with only an authority is left alone`() {
        assertEquals("authority content://com.example.provider", redact("authority content://com.example.provider"))
    }

    @Test
    fun `file uri is masked, extension kept`() {
        assertEquals(
            "Could not play file://<redacted>.mp4",
            redact("Could not play file:///storage/emulated/0/Movies/holiday%20clip.mp4"),
        )
    }

    @Test
    fun `single slash file uri is masked`() {
        assertEquals("load file://<redacted>.glb", redact("load file:/sdcard/Download/kitchen.glb"))
    }

    @Test
    fun `bundled asset file uri stays`() {
        val message = "load file:///android_asset/models/damaged_helmet.glb"
        assertEquals(message, redact(message))
    }

    @Test
    fun `http url keeps host and path, loses credentials query and fragment`() {
        assertEquals(
            "GET https://cdn.example.com/models/chair.glb?<redacted>",
            redact("GET https://alice:hunter2@cdn.example.com/models/chair.glb?X-Amz-Signature=abc&user=bob#top"),
        )
    }

    @Test
    fun `http url without query is untouched`() {
        val message = "fetch https://github.com/sceneview/sceneview/releases/download/assets-v1/robot.glb done"
        assertEquals(message, redact(message))
    }

    // ── Paths ────────────────────────────────────────────────────────────────

    @Test
    fun `every user storage root is masked and named`() {
        val cases = mapOf(
            "/storage/emulated/0/Download/plan.obj" to "/storage/<redacted>.obj",
            "/sdcard/DCIM/IMG_0001.jpg" to "/sdcard/<redacted>.jpg",
            "/mnt/media_rw/1A2B-3C4D/x.stl" to "/mnt/<redacted>.stl",
            "/data/user/0/io.github.sceneview.demo/cache/opened-models/opened" to "/data/user/<redacted>",
            "/data/user_de/0/io.github.sceneview.demo/files" to "/data/user_de/<redacted>",
            "/data/data/io.github.sceneview.demo/files/scan.ply" to "/data/data/<redacted>.ply",
            "/data/media/0/Documents/notes.3mf" to "/data/media/<redacted>.3mf",
        )
        cases.forEach { (path, expected) ->
            assertEquals(path, "load $expected", redact("load $path"))
        }
    }

    @Test
    fun `file names with spaces and parentheses are masked whole`() {
        assertEquals(
            "cannot load /storage/<redacted>.glb",
            redact("cannot load /storage/emulated/0/Download/Tom's living room (1).glb"),
        )
    }

    @Test
    fun `a path stops at the colon of the exception message`() {
        assertEquals(
            "java.io.FileNotFoundException: /storage/<redacted>.glb: open failed: ENOENT (No such file or directory)",
            redact(
                "java.io.FileNotFoundException: /storage/emulated/0/Download/kitchen.glb: " +
                    "open failed: ENOENT (No such file or directory)",
            ),
        )
    }

    @Test
    fun `a quoted path stops at its closing quote`() {
        assertEquals(
            "path=\"/storage/<redacted>.usdz\" size=12",
            redact("path=\"/storage/emulated/0/a b.usdz\" size=12"),
        )
    }

    @Test
    fun `system and app code paths stay, they are what a native crash names`() {
        val message = "#00 pc 0004 /data/app/~~x==/io.github.sceneview.demo-y==/lib/arm64/libfilament-jni.so " +
            "/system/lib64/libc.so /apex/com.android.art/lib64/libart.so"
        assertEquals(message, redact(message))
    }

    @Test
    fun `a storage word inside a longer path is not read as a root`() {
        val message = "assets/storage/sample.glb and models/data/user/x"
        assertEquals(message, redact(message))
    }

    // ── Document ids, e-mails, names ─────────────────────────────────────────

    @Test
    fun `saf document ids are masked`() {
        assertEquals("id <redacted document>.pdf", redact("id primary:Documents/Lease.pdf"))
        assertEquals("id <redacted document>", redact("id msf:42"))
        assertEquals("id <redacted document>.glb", redact("id 1A2B-3C4D:Models/car.glb"))
        assertEquals("id <redacted document>", redact("id primary%3ADownload%2Fsecret"))
    }

    @Test
    fun `a word followed by a colon and a space is not a document id`() {
        val message = "home: Showcase tab, raw: 12 frames"
        assertEquals(message, redact(message))
    }

    @Test
    fun `e-mail addresses are masked`() {
        assertEquals(
            "account <redacted email> signed in, cc <redacted email>",
            redact("account jane.doe+ar@example.co.uk signed in, cc a@b.io"),
        )
    }

    @Test
    fun `an object identity hash is not an e-mail`() {
        val message = "android.view.View@4f2a1c attached to io.github.sceneview.SceneView@1b2c3d"
        assertEquals(message, redact(message))
    }

    @Test
    fun `user-chosen names are masked wherever they appear, extension kept`() {
        assertEquals(
            "Opened <redacted name>.stl as STL, title=<redacted name>.stl",
            redact("Opened My Garage Shelf.stl as STL, title=my garage shelf.stl", listOf("My Garage Shelf.stl")),
        )
    }

    @Test
    fun `too-short names are ignored and longer names win`() {
        assertEquals(
            "a <redacted name>.glb by ab",
            redact("a robot arm.glb by ab", listOf("ab", "arm.glb", "robot arm.glb")),
        )
    }

    @Test
    fun `the name registry keeps the latest names and drops blank ones`() {
        UserChosenNames.remember("  ")
        UserChosenNames.remember(null)
        UserChosenNames.remember("kitchen.glb")
        repeat(40) { UserChosenNames.remember("file-$it.glb") }
        val names = UserChosenNames.snapshot()
        assertEquals(32, names.size)
        assertFalse("kitchen.glb" in names)
        assertTrue("file-39.glb" in names)
    }

    // ── What has to survive ──────────────────────────────────────────────────

    @Test
    fun `a stack trace is left byte for byte intact`() {
        val trace = listOf(
            "10-19 12:00:00.123  6543  6560 E AndroidRuntime: FATAL EXCEPTION: main",
            "10-19 12:00:00.123  6543  6560 E AndroidRuntime: Process: io.github.sceneview.demo, PID: 6543",
            "10-19 12:00:00.123  6543  6560 E AndroidRuntime: java.lang.IllegalStateException: Engine destroyed",
            "10-19 12:00:00.123  6543  6560 E AndroidRuntime: \tat io.github.sceneview.SplatKt" +
                ".rememberSplatCloud\$lambda\$2(Splat.kt:77)",
            "10-19 12:00:00.123  6543  6560 E AndroidRuntime: \tat com.google.android.filament" +
                ".Engine.createMaterial(Engine.java:812)",
            "10-19 12:00:00.123  6543  6560 E AndroidRuntime: \tat kotlinx.coroutines" +
                ".DispatchedTask.run(DispatchedTask.kt:108)",
            "10-19 12:00:00.123  6543  6560 E AndroidRuntime: \t... 12 more",
            "10-19 12:00:00.123  6543  6560 E AndroidRuntime: Caused by: java.lang.NullPointerException: " +
                "Attempt to invoke virtual method 'int java.lang.String.length()' on a null object reference",
            "10-19 12:00:01.500  6543  6543 I SceneView: AR tracking: state=PAUSED reason=NONE for=20s",
            "10-19 12:00:02.000  6543  6601 W Filament: Material 'lit' has no shadow pass",
        )
        assertEquals(trace, LogRedaction.redactLines(trace, listOf("kitchen.glb")))
    }

    @Test
    fun `a stack trace keeps its frames when its message carries a path`() {
        val lines = listOf(
            prefix + "rememberSplatCloud: cannot load /storage/emulated/0/Download/flat.spz",
            prefix + "java.io.FileNotFoundException: /storage/emulated/0/Download/flat.spz: " +
                "open failed: EACCES (Permission denied)",
            prefix + "\tat libcore.io.IoBridge.open(IoBridge.java:574)",
            prefix + "\tat io.github.sceneview.SplatKt.rememberSplatCloud(Splat.kt:77)",
        )
        assertEquals(
            listOf(
                prefix + "rememberSplatCloud: cannot load /storage/<redacted>.spz",
                prefix + "java.io.FileNotFoundException: /storage/<redacted>.spz: " +
                    "open failed: EACCES (Permission denied)",
                lines[2],
                lines[3],
            ),
            LogRedaction.redactLines(lines),
        )
    }

    @Test
    fun `redaction keeps line count and order`() {
        val lines = List(50) { prefix + "line $it /sdcard/x$it.png" }
        val out = LogRedaction.redactLines(lines)
        assertEquals(50, out.size)
        out.forEachIndexed { i, line -> assertEquals(prefix + "line $i /sdcard/<redacted>.png", line) }
    }

    @Test
    fun `the formatted report carries no masked location once redacted`() {
        val info = BugReportInfo(
            metadata = linkedMapOf("Screen" to "Demo · splat"),
            logcat = LogRedaction.redactLines(
                listOf(prefix + "cannot load content://com.android.providers.downloads.documents/document/msf%3A18"),
            ),
        )
        val file = formatReportFile(info, "")
        assertFalse(file.contains("msf"))
        assertTrue(file.contains("content://com.android.providers.downloads.documents/<redacted>"))
        assertFalse(buildGitHubIssueUrl(info, "").contains("msf"))
    }
}
