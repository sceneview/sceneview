package io.github.erkko68.filament

import io.github.erkko68.filament.ffm.FilamentC
import java.lang.foreign.MemorySegment

actual class Camera internal constructor(
    internal var nativeHandle: MemorySegment?,
    actual val entity: Entity
) {
    actual enum class Projection { PERSPECTIVE, ORTHO }
    actual enum class Fov { VERTICAL, HORIZONTAL }

    actual fun setProjection(projection: Projection, left: Double, right: Double, bottom: Double, top: Double, near: Double, far: Double) {
        FilamentC.FilaCamera_setProjection(nativeHandle, projection.ordinal, left, right, bottom, top, near, far)
    }
    actual fun setProjection(fovInDegrees: Double, aspect: Double, near: Double, far: Double, direction: Fov) {
        FilamentC.FilaCamera_setProjectionFov(nativeHandle, fovInDegrees, aspect, near, far, direction.ordinal)
    }
    actual fun setLensProjection(focalLength: Double, aspect: Double, near: Double, far: Double) {
        FilamentC.FilaCamera_setLensProjection(nativeHandle, focalLength, aspect, near, far)
    }
    actual fun setCustomProjection(matrix: DoubleArray, near: Double, far: Double) {
        confined { arena ->
            val m = arena.doubles(matrix)
            FilamentC.FilaCamera_setCustomProjection(nativeHandle, m, m, near, far)
        }
    }
    actual fun setCustomProjection(matrix: DoubleArray, matrixForCulling: DoubleArray, near: Double, far: Double) {
        confined { arena ->
            FilamentC.FilaCamera_setCustomProjection(nativeHandle, arena.doubles(matrix), arena.doubles(matrixForCulling), near, far)
        }
    }

    actual fun setCustomEyeProjection(projection: DoubleArray, count: Int, projectionForCulling: DoubleArray, near: Double, far: Double) {
        confined { arena ->
            FilamentC.FilaCamera_setCustomEyeProjection(nativeHandle, arena.doubles(projection), count.toLong(), arena.doubles(projectionForCulling), near, far)
        }
    }

    actual fun setEyeModelMatrix(eyeId: Int, modelMatrix: DoubleArray) {
        confined { arena -> FilamentC.FilaCamera_setEyeModelMatrix(nativeHandle, eyeId, arena.doubles(modelMatrix)) }
    }

    actual fun setScaling(x: Double, y: Double) {
        FilamentC.FilaCamera_setScaling(nativeHandle, x, y)
    }
    actual fun getScaling(out: DoubleArray?): DoubleArray {
        val result = out ?: DoubleArray(4)
        confined { arena ->
            val seg = arena.doubleArr(4)
            FilamentC.FilaCamera_getScaling(nativeHandle, seg)
            System.arraycopy(seg.toDoubles(), 0, result, 0, 4)
        }
        return result
    }
    actual fun setShift(x: Double, y: Double) {
        FilamentC.FilaCamera_setShift(nativeHandle, x, y)
    }
    actual fun getShift(out: DoubleArray?): DoubleArray {
        val result = out ?: DoubleArray(2)
        confined { arena ->
            val seg = arena.doubleArr(2)
            FilamentC.FilaCamera_getShift(nativeHandle, seg)
            System.arraycopy(seg.toDoubles(), 0, result, 0, 2)
        }
        return result
    }

    actual fun lookAt(eyeX: Double, eyeY: Double, eyeZ: Double, centerX: Double, centerY: Double, centerZ: Double, upX: Double, upY: Double, upZ: Double) {
        FilamentC.FilaCamera_lookAt(nativeHandle, eyeX, eyeY, eyeZ, centerX, centerY, centerZ, upX, upY, upZ)
    }

    actual fun setModelMatrix(modelMatrix: FloatArray) {
        confined { arena -> FilamentC.FilaCamera_setModelMatrix(nativeHandle, arena.floats(modelMatrix)) }
    }
    actual fun setModelMatrix(modelMatrix: DoubleArray) {
        confined { arena -> FilamentC.FilaCamera_setModelMatrixFp64(nativeHandle, arena.doubles(modelMatrix)) }
    }

    actual fun getProjectionMatrix(out: DoubleArray?): DoubleArray {
        val result = out ?: DoubleArray(16)
        confined { arena ->
            val seg = arena.doubleArr(16)
            FilamentC.FilaCamera_getProjectionMatrix(nativeHandle, seg)
            System.arraycopy(seg.toDoubles(), 0, result, 0, 16)
        }
        return result
    }
    actual fun getCullingProjectionMatrix(out: DoubleArray?): DoubleArray {
        val result = out ?: DoubleArray(16)
        confined { arena ->
            val seg = arena.doubleArr(16)
            FilamentC.FilaCamera_getCullingProjectionMatrix(nativeHandle, seg)
            System.arraycopy(seg.toDoubles(), 0, result, 0, 16)
        }
        return result
    }

    actual fun getModelMatrix(out: FloatArray?): FloatArray {
        val result = out ?: FloatArray(16)
        confined { arena ->
            val seg = arena.floatArr(16)
            FilamentC.FilaCamera_getModelMatrix(nativeHandle, seg)
            System.arraycopy(seg.toFloats(), 0, result, 0, 16)
        }
        return result
    }
    actual fun getModelMatrix(out: DoubleArray?): DoubleArray {
        val result = out ?: DoubleArray(16)
        confined { arena ->
            val seg = arena.doubleArr(16)
            FilamentC.FilaCamera_getModelMatrixFp64(nativeHandle, seg)
            System.arraycopy(seg.toDoubles(), 0, result, 0, 16)
        }
        return result
    }

    actual fun getViewMatrix(out: FloatArray?): FloatArray {
        val result = out ?: FloatArray(16)
        confined { arena ->
            val seg = arena.floatArr(16)
            FilamentC.FilaCamera_getViewMatrix(nativeHandle, seg)
            System.arraycopy(seg.toFloats(), 0, result, 0, 16)
        }
        return result
    }
    actual fun getViewMatrix(out: DoubleArray?): DoubleArray {
        val result = out ?: DoubleArray(16)
        confined { arena ->
            val seg = arena.doubleArr(16)
            FilamentC.FilaCamera_getViewMatrixFp64(nativeHandle, seg)
            System.arraycopy(seg.toDoubles(), 0, result, 0, 16)
        }
        return result
    }

    actual fun getPosition(out: FloatArray?): FloatArray {
        val result = out ?: FloatArray(3)
        confined { arena ->
            val seg = arena.floatArr(3)
            FilamentC.FilaCamera_getPosition(nativeHandle, seg)
            System.arraycopy(seg.toFloats(), 0, result, 0, 3)
        }
        return result
    }

    actual fun getLeftVector(out: FloatArray?): FloatArray {
        val result = out ?: FloatArray(3)
        confined { arena ->
            val seg = arena.floatArr(3)
            FilamentC.FilaCamera_getLeftVector(nativeHandle, seg)
            System.arraycopy(seg.toFloats(), 0, result, 0, 3)
        }
        return result
    }
    actual fun getUpVector(out: FloatArray?): FloatArray {
        val result = out ?: FloatArray(3)
        confined { arena ->
            val seg = arena.floatArr(3)
            FilamentC.FilaCamera_getUpVector(nativeHandle, seg)
            System.arraycopy(seg.toFloats(), 0, result, 0, 3)
        }
        return result
    }
    actual fun getForwardVector(out: FloatArray?): FloatArray {
        val result = out ?: FloatArray(3)
        confined { arena ->
            val seg = arena.floatArr(3)
            FilamentC.FilaCamera_getForwardVector(nativeHandle, seg)
            System.arraycopy(seg.toFloats(), 0, result, 0, 3)
        }
        return result
    }

    actual val near: Float get() = FilamentC.FilaCamera_getNear(nativeHandle).toFloat()
    actual val cullingFar: Float get() = FilamentC.FilaCamera_getCullingFar(nativeHandle).toFloat()

    actual fun setExposure(aperture: Float, shutterSpeed: Float, sensitivity: Float) {
        FilamentC.FilaCamera_setExposure(nativeHandle, aperture, shutterSpeed, sensitivity)
    }
    actual fun setExposure(exposure: Float) {
        setExposure(1.0f, 1.2f, 100.0f * (1.0f / exposure))
    }
    actual val aperture: Float get() = FilamentC.FilaCamera_getAperture(nativeHandle)
    actual val shutterSpeed: Float get() = FilamentC.FilaCamera_getShutterSpeed(nativeHandle)
    actual val sensitivity: Float get() = FilamentC.FilaCamera_getSensitivity(nativeHandle)
    actual val focalLength: Double get() = FilamentC.FilaCamera_getFocalLength(nativeHandle)

    actual var focusDistance: Float
        get() = FilamentC.FilaCamera_getFocusDistance(nativeHandle)
        set(value) { FilamentC.FilaCamera_setFocusDistance(nativeHandle, value) }

    @PlatformGap(platforms = [FilamentPlatform.WEB], behavior = "always returns 0.0 — Camera::getFieldOfViewInDegrees is not bound in filament.js.")
    actual fun getFieldOfViewInDegrees(direction: Fov): Double = FilamentC.FilaCamera_getFieldOfViewInDegrees(nativeHandle, direction.ordinal)
}
