package io.github.erkko68.filament

import io.github.erkko68.filament.ffm.FilamentC
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment

actual class SurfaceOrientation internal constructor(internal val nativeHandle: MemorySegment) {
    actual class Builder actual constructor() {
        // Geometry pointers handed to the builder are read at build(); they must outlive each
        // setter call, so allocate them in a builder-scoped arena closed when build() runs.
        private val arena = Arena.ofConfined()
        private val nativeBuilder = FilamentC.FilaSurfaceOrientationBuilder_create()

        actual fun vertexCount(vertexCount: Int): Builder {
            FilamentC.FilaSurfaceOrientationBuilder_vertexCount(nativeBuilder, vertexCount)
            return this
        }

        actual fun normals(buffer: FloatArray, stride: Int): Builder {
            FilamentC.FilaSurfaceOrientationBuilder_normals(nativeBuilder, arena.floats(buffer), stride)
            return this
        }

        @PlatformGap(platforms = [FilamentPlatform.WEB], behavior = "silent no-op — filament.js's extension wrapper exposes no tangents(buffer, stride) entry point.")
        actual fun tangents(buffer: FloatArray, stride: Int): Builder {
            FilamentC.FilaSurfaceOrientationBuilder_tangents(nativeBuilder, arena.floats(buffer), stride)
            return this
        }

        actual fun uvs(buffer: FloatArray, stride: Int): Builder {
            FilamentC.FilaSurfaceOrientationBuilder_uvs(nativeBuilder, arena.floats(buffer), stride)
            return this
        }

        actual fun positions(buffer: FloatArray, stride: Int): Builder {
            FilamentC.FilaSurfaceOrientationBuilder_positions(nativeBuilder, arena.floats(buffer), stride)
            return this
        }

        actual fun triangleCount(triangleCount: Int): Builder {
            FilamentC.FilaSurfaceOrientationBuilder_triangleCount(nativeBuilder, triangleCount)
            return this
        }

        actual fun triangles16(buffer: ShortArray): Builder {
            FilamentC.FilaSurfaceOrientationBuilder_triangles16(nativeBuilder, arena.shorts(buffer))
            return this
        }

        actual fun triangles32(buffer: IntArray): Builder {
            FilamentC.FilaSurfaceOrientationBuilder_triangles32(nativeBuilder, arena.ints(buffer))
            return this
        }

        actual fun build(): SurfaceOrientation {
            val handle = FilamentC.FilaSurfaceOrientationBuilder_build(nativeBuilder)
            FilamentC.FilaSurfaceOrientationBuilder_destroy(nativeBuilder)
            arena.close()
            return SurfaceOrientation(handle)
        }
    }

    actual val vertexCount: Int get() = FilamentC.FilaSurfaceOrientation_getVertexCount(nativeHandle)

    actual fun getQuatsAsFloat(buffer: FloatArray, count: Int) {
        confined { arena ->
            val seg = arena.floatArr(buffer.size)
            FilamentC.FilaSurfaceOrientation_getQuatsAsFloat(nativeHandle, seg, count)
            System.arraycopy(seg.toFloats(), 0, buffer, 0, buffer.size)
        }
    }

    actual fun getQuatsAsHalf(buffer: ShortArray, count: Int) {
        confined { arena ->
            val seg = arena.shortArr(buffer.size)
            FilamentC.FilaSurfaceOrientation_getQuatsAsHalf(nativeHandle, seg, count)
            System.arraycopy(seg.toShorts(), 0, buffer, 0, buffer.size)
        }
    }

    actual fun getQuatsAsShort(buffer: ShortArray, count: Int) {
        confined { arena ->
            val seg = arena.shortArr(buffer.size)
            FilamentC.FilaSurfaceOrientation_getQuatsAsShort(nativeHandle, seg, count)
            System.arraycopy(seg.toShorts(), 0, buffer, 0, buffer.size)
        }
    }

    actual fun destroy() {
        FilamentC.FilaSurfaceOrientation_destroy(nativeHandle)
    }
}
