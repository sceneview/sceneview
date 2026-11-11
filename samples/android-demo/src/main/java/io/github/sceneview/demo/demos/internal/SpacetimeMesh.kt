package io.github.sceneview.demo.demos.internal

import com.google.android.filament.Engine
import com.google.android.filament.IndexBuffer
import com.google.android.filament.MaterialInstance
import com.google.android.filament.Texture
import com.google.android.filament.TextureSampler
import com.google.android.filament.VertexBuffer
import io.github.sceneview.EngineDestroyQueue
import io.github.sceneview.safeDestroyIndexBuffer
import io.github.sceneview.safeDestroyVertexBuffer
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.nio.ByteBuffer
import java.nio.ByteOrder
import com.google.android.filament.Box as FilamentBox

/** Shared upload path for Cosmos and Feel; the compiled material remains unchanged. */
internal class SheetBuffers(val vertexBuffer: VertexBuffer, val indexBuffer: IndexBuffer, val box: FilamentBox) {
    fun destroy(engine: Engine) {
        engine.safeDestroyVertexBuffer(vertexBuffer)
        engine.safeDestroyIndexBuffer(indexBuffer)
    }
}

/** A rectangular patch of the same XZ sheet. Nodes may translate it; wells use local XZ. */
internal fun spacetimeRectangle(halfWidth: Float, halfLength: Float, columns: Int = 64, rows: Int = 96): CosmosSpacetime.Grid {
    require(columns > 0 && rows > 0 && (columns + 1L) * (rows + 1L) <= 65536L)
    val vertices = FloatArray((columns + 1) * (rows + 1) * 3)
    var v = 0
    for (z in 0..rows) for (x in 0..columns) {
        vertices[v++] = halfWidth * (2f * x / columns - 1f)
        vertices[v++] = 0f
        vertices[v++] = halfLength * (2f * z / rows - 1f)
    }
    val indices = ShortArray(columns * rows * 6)
    var i = 0
    for (z in 0 until rows) for (x in 0 until columns) {
        val a = z * (columns + 1) + x
        val b = a + 1
        val c = a + columns + 1
        val d = c + 1
        indices[i++] = a.toShort(); indices[i++] = c.toShort(); indices[i++] = b.toShort()
        indices[i++] = b.toShort(); indices[i++] = c.toShort(); indices[i++] = d.toShort()
    }
    return CosmosSpacetime.Grid(vertices, indices)
}

/**
 * The sheet's grid and horizon map copied into direct buffers, ready for Filament — built off
 * the main thread.
 */
internal class StagedSheet(
    val vertices: ByteBuffer,
    val indices: ByteBuffer,
    val horizon: ByteBuffer,
    val horizonSize: Int,
    val halfWidth: Float,
    val halfLength: Float,
)

internal suspend fun CosmosSpacetime.Grid.stagedSheet(
    bakeHorizon: Boolean = true,
    halfWidth: Float = CosmosSpacetime.SHEET_RADIUS,
    halfLength: Float = CosmosSpacetime.SHEET_RADIUS,
): StagedSheet = coroutineScope {
    val v = ByteBuffer.allocateDirect(vertices.size * Float.SIZE_BYTES).order(ByteOrder.nativeOrder())
    v.asFloatBuffer().put(vertices)
    val i = ByteBuffer.allocateDirect(indices.size * Short.SIZE_BYTES).order(ByteOrder.nativeOrder())
    i.asShortBuffer().put(indices)
    // The horizon map is the long part (a march per texel): one band of rows per core.
    val n = if (bakeHorizon) CosmosSpacetime.HORIZON_MAP_SIZE else 1
    val map = ByteArray(n * n) { 255.toByte() }
    val bands = Runtime.getRuntime().availableProcessors().coerceIn(1, MAX_BAKE_BANDS)
    if (bakeHorizon) (0 until bands).map { band ->
        async { CosmosSpacetime.horizonRows(map, band * n / bands, (band + 1) * n / bands) }
    }.awaitAll()
    val h = ByteBuffer.allocateDirect(map.size).order(ByteOrder.nativeOrder())
    h.put(map).flip()
    StagedSheet(v, i, h, n, halfWidth, halfLength)
}

/** At most this many cores bake the horizon map at once. */
private const val MAX_BAKE_BANDS = 8

/** Spacetime's sheet on the GPU: its grid and the horizon map its material samples. */
internal class GpuSheet(val mesh: SheetBuffers, val horizon: Texture) {
    fun destroy(engine: Engine) {
        mesh.destroy(engine)
        // Queued, not destroyed here: the sheet's material instance still samples this map when
        // the sheet goes. It belongs to the material loader, which is remembered before the sheet
        // and so disposed after it. The sheet is built once and never rebuilt while its material
        // stays, so there is no replacement map to bind an instance to first (#4360).
        EngineDestroyQueue.of(engine).enqueueTexture(horizon)
    }
}

/**
 * Binds the horizon map: bilinear, clamped (past the map's edge the light is clear, as on its
 * border texels). The sampler is made here, not in a top-level val: it is a JNI call, and this
 * file's statics load before Filament's native library.
 */
internal fun MaterialInstance.bindHorizon(horizon: Texture) = setParameter(
    "horizon",
    horizon,
    TextureSampler(
        TextureSampler.MinFilter.LINEAR,
        TextureSampler.MagFilter.LINEAR,
        TextureSampler.WrapMode.CLAMP_TO_EDGE,
    ),
)

/**
 * Main thread only. Position (x, 0, z) per vertex, 16-bit indices; the horizon map as one R8
 * texture. Cosmos keeps its original polar grid, bounds and 512² map. Feel uses rectangular
 * patches and a single lit texel, since its moving wells have no baked star shadow.
 */
internal fun StagedSheet.uploadSheet(engine: Engine): GpuSheet {
    val strideBytes = CosmosSpacetime.GRID_STRIDE * Float.SIZE_BYTES
    val vertexBuffer = VertexBuffer.Builder()
        .bufferCount(1)
        .vertexCount(vertices.capacity() / strideBytes)
        .attribute(VertexBuffer.VertexAttribute.POSITION, 0, VertexBuffer.AttributeType.FLOAT3, 0, strideBytes)
        .build(engine)
    vertexBuffer.setBufferAt(engine, 0, vertices)
    val indexBuffer = IndexBuffer.Builder()
        .indexCount(indices.capacity() / Short.SIZE_BYTES)
        .bufferType(IndexBuffer.Builder.IndexType.USHORT)
        .build(engine)
    indexBuffer.setBuffer(engine, indices)
    val box = FilamentBox(
        0f, (CosmosSpacetime.BOX_MIN_Y + CosmosSpacetime.BOX_MAX_Y) / 2f, 0f,
        halfWidth, (CosmosSpacetime.BOX_MAX_Y - CosmosSpacetime.BOX_MIN_Y) / 2f, halfLength,
    )
    val n = horizonSize
    val texture = Texture.Builder()
        .width(n)
        .height(n)
        .levels(1)
        .sampler(Texture.Sampler.SAMPLER_2D)
        .format(Texture.InternalFormat.R8)
        .build(engine)
    texture.setImage(engine, 0, Texture.PixelBufferDescriptor(horizon, Texture.Format.R, Texture.Type.UBYTE, 1))
    return GpuSheet(SheetBuffers(vertexBuffer, indexBuffer, box), texture)
}
