package io.github.sceneview.demo.demos.internal

import io.github.sceneview.math.Position
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs

/**
 * Pins the Rerun demo's bundled replay: the manifest and its media index, the lens, the photo
 * mapping, the point-colour atlas, the filmstrip's frame picks, the entrance shot, the frame-rate
 * meter, the textured geometry — and that the three bundled files agree with each other.
 */
class RerunReplayTest {

    // ── Manifest ──────────────────────────────────────────────────────────────

    @Test
    fun `the manifest reads the lens, the plane textures and the media index`() {
        val manifest = ReplayManifest.parse(
            """
            {"intrinsics":{"width":480,"height":640,"fx":400,"fy":400},
             "frameRate":12,"frames":3,"floorY":-1.25,
             "textures":[{"plane":7,"path":"planes/a.webp","origin":[0,0,0],"u":[2,0,0],"v":[0,0,4]}],
             "media":[{"path":"frames/000.webp","offset":0,"length":10},
                      {"path":"planes/a.webp","offset":10,"length":5}],
             "unknown":true}
            """.trimIndent()
        )
        assertNotNull(manifest!!)
        assertEquals(0.6f, manifest.lens.halfWidthPerDepth, 1e-6f)
        assertEquals(0.8f, manifest.lens.halfHeightPerDepth, 1e-6f)
        assertEquals(12f, manifest.frameRate, 0f)
        assertEquals(3, manifest.frameCount)
        assertEquals(-1.25f, manifest.floorY!!, 1e-6f)
        assertEquals("planes/a.webp", manifest.textureFor(7)!!.path)
        assertNull(manifest.textureFor(8))
        assertEquals(MediaSpan(10, 5), manifest.media["planes/a.webp"])
        assertEquals(2, manifest.media.size)
    }

    @Test
    fun `a manifest with nothing in it falls back instead of failing`() {
        val manifest = ReplayManifest.parse("{}")!!
        assertEquals(ReplayLens.Default, manifest.lens)
        assertTrue(manifest.frameRate > 0f)
        assertNull(manifest.floorY)
        assertTrue(manifest.textures.isEmpty())
        assertTrue(manifest.media.isEmpty())
    }

    @Test
    fun `malformed entries are skipped, not trusted`() {
        val manifest = ReplayManifest.parse(
            """
            {"textures":[{"plane":1,"path":"p.webp","origin":[0,0],"u":[1,0,0],"v":[0,0,1]}],
             "media":[{"path":"a","offset":-1,"length":4},{"path":"b","offset":0,"length":0},
                      {"path":"c","offset":2},{"path":"d","offset":4,"length":4}]}
            """.trimIndent()
        )!!
        assertTrue(manifest.textures.isEmpty())
        assertEquals(mapOf("d" to MediaSpan(4, 4)), manifest.media)
    }

    @Test
    fun `text that is not a manifest parses to null`() {
        assertNull(ReplayManifest.parse("not json"))
        assertNull(ReplayManifest.parse("[1, 2]"))
    }

    @Test
    fun `a lens needs positive intrinsics`() {
        assertNull(ReplayLens.of(0f, 640f, 400f, 400f))
        assertNull(ReplayLens.of(480f, 640f, -1f, 400f))
        assertEquals(ReplayLens(0.5f, 1f), ReplayLens.of(400f, 800f, 400f, 400f))
    }

    // ── Photo mapping ─────────────────────────────────────────────────────────

    @Test
    fun `a plane texture maps its corners to the unit square`() {
        val texture = ReplayPlaneTexture(
            planeId = 1, path = "p", origin = Vec3(1f, 0f, 2f), u = Vec3(4f, 0f, 0f), v = Vec3(0f, 0f, 2f),
        )
        assertUv(0f, 0f, texture.uvOf(1f, 0f, 2f))
        assertUv(1f, 1f, texture.uvOf(5f, 0f, 4f))
        assertUv(0.5f, 0.25f, texture.uvOf(3f, 9f, 2.5f)) // height off the plane is ignored
        assertUv(-0.25f, 0f, texture.uvOf(0f, 0f, 2f)) // unclamped
    }

    @Test
    fun `the atlas gives each point its own texel, in order`() {
        // V from the last row: the image material reads the raw atlas upload bottom-up (#4095).
        assertUv(0.5f / 128, 1f - 0.5f / 128, PointColorAtlas.uvOf(0))
        assertUv(1.5f / 128, 1f - 0.5f / 128, PointColorAtlas.uvOf(1))
        assertUv(0.5f / 128, 1f - 1.5f / 128, PointColorAtlas.uvOf(128))
        assertEquals(PointColorAtlas.uvOf(128 * 128 - 1), PointColorAtlas.uvOf(1_000_000))
    }

    @Test
    fun `atlas pixels carry the colours, and the fallback where there is none`() {
        val pixels = PointColorAtlas.pixels(intArrayOf(0xFF102030.toInt(), 0), fallback = 0xFFAABBCC.toInt())
        assertEquals(PointColorAtlas.SIZE * PointColorAtlas.SIZE * 4, pixels.size)
        assertArrayEquals(byteArrayOf(0x10, 0x20, 0x30, 0xFF.toByte()), pixels.copyOfRange(0, 4))
        assertArrayEquals(
            byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte(), 0xFF.toByte()),
            pixels.copyOfRange(4, 8),
        )
    }

    // ── Filmstrip ─────────────────────────────────────────────────────────────

    @Test
    fun `the filmstrip spreads its slots over the whole session, ends included`() {
        assertArrayEquals(intArrayOf(0, 46, 92, 137, 183), filmstripFrames(184, 5))
        assertArrayEquals(intArrayOf(0, 1, 2), filmstripFrames(3, 8))
        assertArrayEquals(intArrayOf(0), filmstripFrames(184, 1))
        assertEquals(0, filmstripFrames(0, 5).size)
        assertEquals(0, filmstripFrames(10, 0).size)
    }

    @Test
    fun `filmstrip frames never repeat nor go backwards`() {
        for (slots in 2..40) {
            val frames = filmstripFrames(184, slots)
            assertEquals(slots, frames.size)
            for (i in 1 until frames.size) assertTrue(frames[i] > frames[i - 1])
        }
    }

    // ── Entrance ──────────────────────────────────────────────────────────────

    @Test
    fun `the entrance eases from rest to rest`() {
        assertEquals(0f, ReplayIntro.ease(0f), 0f)
        assertEquals(1f, ReplayIntro.ease(1f), 0f)
        assertEquals(0f, ReplayIntro.ease(-3f), 0f)
        assertEquals(1f, ReplayIntro.ease(3f), 0f)
        var last = 0f
        for (i in 1..100) {
            val y = ReplayIntro.ease(i / 100f)
            assertTrue("ease must not go backwards at $i", y >= last - 1e-5f)
            last = y
        }
        // ease-expressive lands softly: most of the way there by half time.
        assertTrue(ReplayIntro.ease(0.5f) > 0.8f)
    }

    @Test
    fun `the entrance starts high, a little wide and turned, and lands on home`() {
        val home = OrbitPose(Position(1f, 0f, 2f), azimuthDegrees = 30f, elevationDegrees = 28f, distance = 3f)
        val start = ReplayIntro.startFor(home)
        assertEquals(home.target, start.target)
        // Wide enough to read as a crane, near enough that the room is whole from the first frame
        // and inside what a pinch may reach (#4306).
        assertTrue(start.distance > home.distance * 1.2f)
        assertTrue(start.distance <= home.distance * ArDebugFraming.MAX_ZOOM_OUT)
        assertTrue("it opens on the room's front", kotlin.math.abs(ReplayIntro.TURN_DEGREES) < 60f)
        assertTrue(start.elevationDegrees > home.elevationDegrees)
        assertEquals(home.azimuthDegrees + ReplayIntro.TURN_DEGREES, start.azimuthDegrees, 1e-4f)
        assertEquals(start, ReplayIntro.pose(start, home, 0f))
        assertEquals(home.distance, ReplayIntro.pose(start, home, 1f).distance, 1e-4f)
    }

    @Test
    fun `the orbit camera plays the entrance, then follows home`() {
        val camera = ArDebugOrbitCamera(drift = false)
        camera.setViewport(1080, 2400)
        camera.home = OrbitPose(azimuthDegrees = 40f, elevationDegrees = 28f, distance = 3f)
        camera.playIntro(ReplayIntro.startFor(camera.home))
        assertTrue(camera.introPlaying)
        assertTrue(camera.pose.distance > 3.5f)

        repeat(((ReplayIntro.DURATION_S + 0.5f) * 60).toInt()) { camera.update(1f / 60f) }
        assertFalse(camera.introPlaying)
        assertEquals(3f, camera.pose.distance, 1e-2f)
    }

    @Test
    fun `a held entrance waits on its first pose until released`() {
        val camera = ArDebugOrbitCamera(drift = false)
        camera.setViewport(1080, 2400)
        camera.home = OrbitPose(azimuthDegrees = 40f, elevationDegrees = 28f, distance = 3f)
        val start = ReplayIntro.startFor(camera.home)
        camera.playIntro(start, held = true)

        // Seconds behind the loading cover: the shot has not moved.
        repeat(3 * 60) { camera.update(1f / 60f) }
        assertTrue(camera.introPlaying)
        assertEquals(start.distance, camera.pose.distance, 1e-4f)

        camera.releaseIntro()
        repeat(((ReplayIntro.DURATION_S + 0.5f) * 60).toInt()) { camera.update(1f / 60f) }
        assertFalse(camera.introPlaying)
        assertEquals(3f, camera.pose.distance, 1e-2f)
    }

    @Test
    fun `a touch cuts the entrance short`() {
        val camera = ArDebugOrbitCamera(drift = false)
        camera.home = OrbitPose(distance = 3f)
        camera.playIntro(ReplayIntro.startFor(camera.home))
        camera.grabBegin(0, 0, strafe = false)
        assertFalse(camera.introPlaying)
        assertFalse(camera.following)
    }

    @Test
    fun `the map view frames the room from overhead`() {
        val camera = ArDebugOrbitCamera(drift = false)
        assertEquals(ArDebugFraming.HOME_ELEVATION, camera.homeElevation, 0f)
        camera.grabBegin(0, 0, strafe = false)
        camera.grabEnd()
        assertFalse(camera.following)

        camera.overhead = true
        assertEquals(ArDebugFraming.MAP_ELEVATION, camera.homeElevation, 0f)
        assertTrue("switching to the map hands the camera back", camera.following)
    }

    // ── Frame rate ────────────────────────────────────────────────────────────

    @Test
    fun `the fps meter reports a whole window's rate`() {
        val meter = FpsMeter(windowNanos = 500_000_000L)
        val frame = 1_000_000_000L / 60
        var changed = false
        for (i in 0..31) changed = meter.tick(1_000L + i * frame) || changed
        assertTrue(changed)
        assertEquals(60, meter.fps)
    }

    @Test
    fun `the fps meter reads zero before a window elapsed`() {
        val meter = FpsMeter()
        assertFalse(meter.tick(1_000L))
        assertFalse(meter.tick(2_000L))
        assertEquals(0, meter.fps)
    }

    // ── Session ───────────────────────────────────────────────────────────────

    @Test
    fun `a looping replay holds its last frame, then starts over`() {
        val session = ArDebugSession(twoSeconds()).apply { loops = true }
        session.playFromStart()
        assertFalse(session.live)
        assertEquals(0f, session.time, 0f)

        repeat(21) { session.tick(0.1f) } // two seconds, and the first tick of the hold
        assertFalse("a looping replay never goes live", session.live)
        assertEquals(session.trace.duration, session.time, 1e-4f)

        repeat(20) { session.tick(0.1f) } // still within the hold
        assertEquals(session.trace.duration, session.time, 1e-4f)

        repeat(5) { session.tick(0.1f) } // past it: back to the start, still playing
        assertTrue(session.playing)
        assertTrue(session.time < 0.5f)
    }

    @Test
    fun `a replay that does not loop catches up to live`() {
        val session = ArDebugSession(twoSeconds())
        session.playFromStart()
        repeat(30) { session.tick(0.1f) }
        assertTrue(session.live)
    }

    @Test
    fun `compact counts fit a narrow figure`() {
        assertEquals("0", ArDebugFormat.compactCount(-4))
        assertEquals("812", ArDebugFormat.compactCount(812))
        assertEquals("4.8k", ArDebugFormat.compactCount(4_812))
        assertEquals("9.9k", ArDebugFormat.compactCount(9_999))
        assertEquals("12k", ArDebugFormat.compactCount(12_400))
    }

    // ── Geometry ──────────────────────────────────────────────────────────────

    @Test
    fun `a photo quad sits on the frustum's image plane, top row along the top edge`() {
        val mesh = DebugMesh()
        val lens = ReplayLens(0.5f, 0.75f)
        ReplayGeometry.addImageQuad(mesh, DebugPose(0f, 0f, 0f), depth = 2f, lens = lens)
        assertEquals(4, mesh.vertexCount)
        assertEquals(2, mesh.triangleCount)
        val corners = ArDebugGeometry.frustumCorners(DebugPose(0f, 0f, 0f), 2f, lens)
        for (i in 0 until 4) {
            assertEquals(corners[i].x, mesh.positions[i * 3], 1e-5f)
            assertEquals(corners[i].y, mesh.positions[i * 3 + 1], 1e-5f)
            assertEquals(corners[i].z, mesh.positions[i * 3 + 2], 1e-5f)
        }
        assertArrayEquals(floatArrayOf(0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f), mesh.uvs.copyOf(8), 0f)
        assertTrue("the top edge is above the bottom one", mesh.positions[1] > mesh.positions[3 * 3 + 1])
    }

    @Test
    fun `a floor's photo is laid flat at the height asked, textured by the world`() {
        val texture = ReplayPlaneTexture(1, "p", Vec3(0f, 0f, 0f), Vec3(2f, 0f, 0f), Vec3(0f, 0f, 2f))
        val square = floatArrayOf(0f, 0.1f, 0f, 2f, -0.1f, 0f, 2f, 0f, 2f, 0f, 0.05f, 2f)
        val mesh = DebugMesh()
        val flat = square.copyOf().also { for (i in 0 until 4) it[i * 3 + 1] = -1f }
        ReplayGeometry.addTexturedPlane(mesh, square, texture, placed = flat)
        assertEquals(5, mesh.vertexCount) // the centre, then the four corners
        assertEquals(4, mesh.triangleCount)
        for (i in 0 until mesh.vertexCount) assertEquals(-1f, mesh.positions[i * 3 + 1], 0f)
        assertEquals(0.5f, mesh.uvs[0], 1e-5f)
        assertEquals(0.5f, mesh.uvs[1], 1e-5f)
        assertEquals(1f, mesh.uvs[2 * 2], 1e-5f) // corner (2, _, 0)
    }

    @Test
    fun `a sliver of a plane draws nothing`() {
        val mesh = DebugMesh()
        val texture = ReplayPlaneTexture(1, "p", Vec3(0f, 0f, 0f), Vec3(1f, 0f, 0f), Vec3(0f, 0f, 1f))
        ReplayGeometry.addTexturedPlane(mesh, floatArrayOf(0f, 0f, 0f, 1f, 0f, 0f), texture)
        assertTrue(mesh.isEmpty)
    }

    @Test
    fun `each coloured point is a tetrahedron on its own texel`() {
        val mesh = DebugMesh()
        ReplayGeometry.addColoredPoints(mesh, floatArrayOf(0f, 0f, 0f, 1f, 1f, 1f), radius = 0.01f)
        assertEquals(8, mesh.vertexCount)
        assertEquals(8, mesh.triangleCount)
        val (u1, v1) = PointColorAtlas.uvOf(1)
        assertEquals(u1, mesh.uvs[4 * 2], 0f)
        assertEquals(v1, mesh.uvs[4 * 2 + 1], 0f)
    }

    // ── The bundled files ─────────────────────────────────────────────────────

    @Test
    fun `the bundled replay's three files agree`() {
        val dir = bundledDir()
        val manifest = ReplayManifest.parse(File(dir, "showcase-manifest.json").readText())!!
        val archive = File(dir, "showcase-media.bin").length()
        val events = File(dir, "showcase-session.jsonl").useLines { parseArDebugLog(it) }
        val trace = ArDebugTrace.of(events)

        assertTrue(trace.duration > 5f)
        assertEquals(manifest.frameCount, trace.imageCount)
        for (i in 0 until trace.imageCount) {
            assertNotNull("frame ${trace.imagePath(i)} is not in the archive", manifest.media[trace.imagePath(i)])
        }
        for (texture in manifest.textures) assertNotNull(texture.path, manifest.media[texture.path])
        var end = 0L
        for ((path, span) in manifest.media.entries.sortedBy { it.value.offset }) {
            assertEquals("$path must follow the previous photo", end, span.offset.toLong())
            end += span.length
        }
        assertEquals("the index covers the whole archive", archive, end)
        val last = trace.frameAt(trace.duration)
        assertTrue(last.mapPointCount > 1_000)
        assertEquals(2, last.anchors.size)
        assertTrue(last.planes.isNotEmpty())
        assertTrue(last.planes.all { manifest.textureFor(it.id) != null })
    }

    private fun bundledDir(): File = listOf(
        File("src/main/assets/${RerunReplayAssets.DIR}"),
        File("samples/android-demo/src/main/assets/${RerunReplayAssets.DIR}"),
    ).first { it.isDirectory }

    private fun twoSeconds() = ArDebugTrace().apply {
        for (i in 0..20) addPose(1_000_000_000L + i * 100_000_000L, DebugPose(i * 0.02f, 0f, 0f))
    }

    private fun assertUv(u: Float, v: Float, actual: Pair<Float, Float>) {
        assertTrue("u ${actual.first} != $u", abs(actual.first - u) < 1e-5f)
        assertTrue("v ${actual.second} != $v", abs(actual.second - v) < 1e-5f)
    }
}
