package io.github.sceneview.loaders

import com.google.android.filament.Box
import io.github.sceneview.model.AnimationEnvelope
import io.github.sceneview.model.AnimationEnvelopes
import io.github.sceneview.toAabb
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.cos
import kotlin.math.sin
import kotlin.system.measureNanoTime

@RunWith(RobolectricTestRunner::class)
class GltfAnimationEnvelopeTest {
    private val rest = Box(0.5f, 0f, 0f, 0.75f, 0.25f, 0.25f)

    @Test fun `linear translation samples and flesh margins match analytic values`() {
        val data = rig().animationData()!!
        assertArrayEquals(floatArrayOf(1f, 0f, 0f), data.jointPositions(0, 1f)[0], 1e-5f)
        assertArrayEquals(floatArrayOf(2f, 0f, 0f), data.jointPositions(0, 1f)[1], 1e-5f)
        val envelope = data.sample(0, 3, rest)!!
        assertEquals(4, envelope.poses.size) // Rest, t=0, t=1, t=2.
        assertEquals(1.5f, envelope.poses[2].center[0], 1e-5f)
        assertEquals(0.75f, envelope.poses[2].halfExtent[0], 1e-5f)
        assertContains(envelope.bounds, rest)
        assertEquals(-0.25f, envelope.bounds.toAabb().min.x, 1e-5f)
        assertEquals(3.25f, envelope.bounds.toAabb().max.x, 1e-5f)
        assertTrue(envelope.poses.all { it.halfExtent[0] < envelope.bounds.halfExtent[0] })
    }

    @Test fun `fixture rest box is computed from its skinned vertices`() {
        assertArrayEquals(floatArrayOf(-0.25f, -0.25f, -0.25f, 1.25f, 0.25f, 0.25f),
            rig().restPoseBounds(), 1e-5f)
    }

    @Test fun `single keys and tracks starting after zero hold their endpoint values`() {
        val data = rig(singleKey = true).animationData()!!
        assertArrayEquals(floatArrayOf(1f, 0f, 0f), data.jointPositions(0, 0f)[1], 1e-5f)
        assertArrayEquals(floatArrayOf(1f, 0f, 0f), data.jointPositions(0, 5f)[1], 1e-5f)
    }

    @Test fun `step holds until the exact next key and clamps outside the track`() {
        val data = rig(interpolation = "STEP").animationData()!!
        assertArrayEquals(floatArrayOf(1f, 0f, 0f), data.jointPositions(0, 1.999f)[1], 1e-5f)
        assertArrayEquals(floatArrayOf(3f, 0f, 0f), data.jointPositions(0, 2f)[1], 1e-5f)
        assertArrayEquals(floatArrayOf(1f, 0f, 0f), data.jointPositions(0, -1f)[1], 1e-5f)
        assertArrayEquals(floatArrayOf(3f, 0f, 0f), data.jointPositions(0, 4f)[1], 1e-5f)
    }

    @Test fun `linear quaternion interpolation is shortest arc slerp not nlerp`() {
        for (negate in listOf(false, true)) {
            val data = rig(negateRotation = negate).animationData()!!
            for (time in listOf(0.5f, 1f, 1.5f, 2f)) {
                val angle = Math.toRadians(60.0 * time)
                assertArrayEquals(floatArrayOf(cos(angle).toFloat(), sin(angle).toFloat(), 0f),
                    data.jointPositions(1, time)[1], 1e-5f)
            }
        }
    }

    @Test fun `cubic spline tangents are multiplied by segment duration`() {
        val data = rig(interpolation = "CUBICSPLINE").animationData()!!
        // dt=2, t=0.25: h10*dt*2 + h01*2 = 0.875, then child offset +1.
        assertEquals(1.875f, data.jointPositions(0, 0.5f)[1][0], 1e-5f)
        assertEquals(2.5f, data.jointPositions(0, 1f)[1][0], 1e-5f)
    }

    @Test fun `cubic rotations are normalized after Hermite interpolation`() {
        val data = rig(cubicRotation = true).animationData()!!
        val p = data.jointPositions(1, 0.5f)[1]
        val mix = 0.15625 // smoothstep(0.25), zero tangents
        val z = mix * sin(Math.PI / 3)
        val w = (1 - mix) + mix * cos(Math.PI / 3)
        assertEquals(((w*w - z*z) / (w*w + z*z)).toFloat(), p[0], 1e-5f)
        assertEquals((2*w*z / (w*w + z*z)).toFloat(), p[1], 1e-5f)
    }

    @Test fun `static matrix parent and animated scale compose in model space`() {
        val data = rig(matrixParent = true).animationData()!!
        assertArrayEquals(floatArrayOf(14f, 0f, 0f), data.jointPositions(0, 1f)[1], 1e-5f)
        assertArrayEquals(floatArrayOf(13f, 0f, 0f), data.jointPositions(2, 1f)[1], 1e-5f)
    }

    @Test fun `missing or unreadable animation inputs fall back without throwing`() {
        assertNull(rig(edit = { it.remove("skins") }).animationData())
        assertNull(rig(edit = { it.remove("animations") }).animationData())
        assertNull(GltfLimits.parse(ByteBuffer.wrap("bad JSON".toByteArray())).animationData())
        assertNull(rig(edit = {
            it.getJSONArray("accessors").getJSONObject(0).put("sparse", JSONObject())
        }).animationData())
        assertNull(rig(edit = {
            it.getJSONArray("nodes").getJSONObject(3).put("children", JSONArray("[2]"))
        }).animationData())
        assertNull(rig(edit = {
            it.getJSONArray("animations").getJSONObject(0).getJSONArray("samplers")
                .getJSONObject(0).put("output", 999)
        }).animationData())
        assertNull(rig().animationData()!!.sample(999, 24, rest))
        val fallback = AnimationEnvelope(listOf(rest))
        assertSame(fallback, AnimationEnvelopes.Entry(null, fallback).envelope(0, 24))
    }

    @Test fun `cache is per clip and bounded sample count and boxes cannot corrupt it`() {
        val data = rig().animationData()!!
        val fallback = AnimationEnvelope(listOf(rest))
        val entry = AnimationEnvelopes.Entry(data, fallback)
        val envelope = entry.envelope(data.clipIndex("Walk"), 3)
        assertSame(envelope, entry.envelope(0, 3))
        assertNotSame(envelope, entry.envelope(data.clipIndex("Turn"), 3))
        assertSame(fallback, entry.envelope(data.clipIndex("missing"), 3))
        assertEquals(3, entry.envelope(0, Int.MIN_VALUE).poses.size)
        assertEquals(121, entry.envelope(0, Int.MAX_VALUE).poses.size)
        val box = envelope.poses.first()
        box.setCenter(999f, 999f, 999f)
        assertEquals(0.5f, envelope.poses.first().center[0], 0f)
        val results = java.util.concurrent.Executors.newFixedThreadPool(4)
        try {
            val calls = results.invokeAll(List(12) { java.util.concurrent.Callable { entry.envelope(0, 24) } })
            calls.forEach { assertSame(calls.first().get(), it.get()) }
        } finally { results.shutdown() }
        for (n in 4..20) entry.envelope(0, n)
        assertNotSame(envelope, entry.envelope(0, 3)) // Evicted, recomputed.
    }

    @Test fun `fox run and walk exceed the rest width and report retained cost when asset exists`() {
        val file = listOf(
            File("src/androidTest/assets/khronos_fox.glb"),
            File("sceneview/src/androidTest/assets/khronos_fox.glb"),
            File("../samples/android-demo/src/main/assets/models/khronos_fox.glb"),
        ).firstOrNull { it.isFile }
        assumeTrue("Fox asset is optional", file != null)
        val bytes = file!!.readBytes()
        val source = ByteBuffer.wrap(bytes)
        val parsed = GltfLimits.parse(source)
        val b = parsed.restPoseBounds()!!
        val box = Box((b[0]+b[3])/2, (b[1]+b[4])/2, (b[2]+b[5])/2,
            (b[3]-b[0])/2, (b[4]-b[1])/2, (b[5]-b[2])/2)
        val data = parsed.animationData()!!
        for (name in listOf("Walk", "Run")) {
            val envelope = data.sample(data.clipIndex(name), 24, box)!!
            assertContains(envelope.bounds, box)
            assertTrue("$name must increase horizontal extent", listOf(0, 2).any {
                envelope.bounds.halfExtent[it] > box.halfExtent[it] + 1e-3f
            })
            println("Fox $name: rest=${box.toAabb().extents}, envelope=${envelope.bounds.toAabb().extents}, poses=${envelope.poses.size}")
        }
        // Warm-up followed by medians; parsing is existing load work, preparation is new load work.
        repeat(5) { GltfLimits.parse(source).animationData(); data.sample(0, 24, box) }
        fun medianMs(block: () -> Unit) = List(21) { measureNanoTime(block) }.sorted()[10] / 1e6
        val entry = AnimationEnvelopes.Entry(data, AnimationEnvelope(listOf(box)))
        entry.envelope(0, 24)
        println("Fox bytes=${bytes.size}, retainedArrayBytes=${data.retainedArrayBytes}, " +
            "parseMedianMs=${medianMs { GltfLimits.parse(source) }}, " +
            "prepareMedianMs=${medianMs { parsed.animationData() }}, " +
            "sample24MedianMs=${medianMs { data.sample(0, 24, box) }}, " +
            "cachedMedianMs=${medianMs { entry.envelope(0, 24) }}")
    }

    private fun assertContains(outer: Box, inner: Box) {
        for (axis in 0..2) {
            assertTrue(outer.center[axis] - outer.halfExtent[axis] <= inner.center[axis] - inner.halfExtent[axis] + 1e-4f)
            assertTrue(outer.center[axis] + outer.halfExtent[axis] >= inner.center[axis] + inner.halfExtent[axis] - 1e-4f)
        }
    }

    /** Two joints under a static armature, with independent translation, rotation and scale clips. */
    private fun rig(
        interpolation: String = "LINEAR", negateRotation: Boolean = false,
        cubicRotation: Boolean = false, matrixParent: Boolean = false, singleKey: Boolean = false,
        edit: (JSONObject) -> Unit = {},
    ): GltfLimits.Parsed {
        val bin = ByteBuffer.allocate(2048).order(ByteOrder.LITTLE_ENDIAN)
        val accessors = JSONArray()
        val views = JSONArray()
        fun accessor(values: FloatArray, type: String): Int {
            val width = if (type == "SCALAR") 1 else if (type == "VEC3") 3 else 4
            views.put(JSONObject().put("buffer", 0).put("byteOffset", bin.position()).put("byteLength", values.size*4))
            values.forEach(bin::putFloat)
            accessors.put(JSONObject().put("bufferView", views.length()-1).put("componentType", 5126)
                .put("type", type).put("count", values.size/width))
            return accessors.length()-1
        }
        val time = accessor(if (singleKey) floatArrayOf(1f) else floatArrayOf(0f, 2f), "SCALAR")
        val translation = accessor(if (singleKey) floatArrayOf(0f, 0f, 0f) else if (interpolation == "CUBICSPLINE") floatArrayOf(
            0f,0f,0f, 0f,0f,0f, 2f,0f,0f,
            0f,0f,0f, 2f,0f,0f, 0f,0f,0f,
        ) else floatArrayOf(0f,0f,0f, 2f,0f,0f), "VEC3")
        val q0 = floatArrayOf(0f,0f,0f,1f)
        val sign = if (negateRotation) -1f else 1f
        val q1 = floatArrayOf(0f,0f, sin(Math.PI/3).toFloat()*sign, 0.5f*sign)
        val rotation = accessor(if (singleKey) q0 else if (cubicRotation) FloatArray(4)+q0+FloatArray(8)+q1+FloatArray(4) else q0+q1, "VEC4")
        val scale = accessor(if (singleKey) floatArrayOf(1f,1f,1f) else floatArrayOf(1f,1f,1f, 2f,2f,2f), "VEC3")
        val positions = accessor(floatArrayOf(-0.25f,-0.25f,-0.25f, 1.25f,0.25f,-0.25f, 0.5f,0f,0.25f), "VEC3")
        accessors.getJSONObject(positions).put("min", JSONArray("[-0.25,-0.25,-0.25]"))
            .put("max", JSONArray("[1.25,0.25,0.25]"))
        val weights = accessor(floatArrayOf(1f,0f,0f,0f, 1f,0f,0f,0f, 1f,0f,0f,0f), "VEC4")
        val jointIndices = accessors.length()
        views.put(JSONObject().put("buffer", 0).put("byteOffset", bin.position()).put("byteLength", 24))
        repeat(12) { bin.putShort(0) }
        accessors.put(JSONObject().put("bufferView", views.length()-1).put("componentType", 5123)
            .put("type", "VEC4").put("count", 3))
        fun clip(name: String, path: String, output: Int, mode: String) = JSONObject("""
            {"name":"$name","channels":[{"sampler":0,"target":{"node":2,"path":"$path"}}],
             "samplers":[{"input":$time,"output":$output,"interpolation":"$mode"}]}
        """)
        val json = JSONObject("""{
            "asset":{"version":"2.0"},"scene":0,"scenes":[{"nodes":[0,1]}],
            "nodes":[{"mesh":0,"skin":0},{"children":[2]},
              {"children":[3]},{"translation":[1,0,0]}],"skins":[{"joints":[2,3]}],
            "meshes":[{"primitives":[{"attributes":{"POSITION":$positions,"JOINTS_0":$jointIndices,"WEIGHTS_0":$weights}}]}]
        }""").put("accessors", accessors).put("bufferViews", views)
            .put("buffers", JSONArray().put(JSONObject().put("byteLength", bin.position())))
            .put("animations", JSONArray().put(clip("Walk", "translation", translation, interpolation))
                .put(clip("Turn", "rotation", rotation, if (cubicRotation) "CUBICSPLINE" else "LINEAR"))
                .put(clip("Grow", "scale", scale, "LINEAR")))
        if (matrixParent) json.getJSONArray("nodes").getJSONObject(1)
            .put("matrix", JSONArray("[2,0,0,0,0,2,0,0,0,0,2,0,10,0,0,1]"))
        edit(json)
        val raw = json.toString().toByteArray()
        val jsonBytes = raw + ByteArray((4 - raw.size%4)%4) { 32 }
        val size = 28 + jsonBytes.size + bin.position()
        return GltfLimits.parse(ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(0x46546C67); putInt(2); putInt(size)
            putInt(jsonBytes.size); putInt(0x4E4F534A); put(jsonBytes)
            putInt(bin.position()); putInt(0x004E4942); put(bin.array(), 0, bin.position()); flip()
        })
    }
}
