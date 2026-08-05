package io.github.erkko68.filament.utils

import io.github.erkko68.filament.confined
import io.github.erkko68.filament.ensureFilamentLoaded
import io.github.erkko68.filament.ffm.FilamentC
import io.github.erkko68.filament.getFloatAt
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout

actual class Manipulator internal constructor(internal val nativeHandle: MemorySegment) {
    actual enum class Mode { ORBIT, MAP, FLIGHT }
    actual enum class Fov { VERTICAL, HORIZONTAL }
    actual enum class Key { FORWARD, LEFT, BACKWARD, RIGHT, UP, DOWN }

    actual class Builder actual constructor() {
        // A Manipulator can be built without ever creating an Engine, so make sure the combined
        // libfilament-c image is loaded before the first downcall.
        private val nativeBuilder: MemorySegment = run {
            ensureFilamentLoaded()
            FilamentC.FilaManipulatorBuilder_create()
        }

        actual fun viewport(width: Int, height: Int): Builder = apply { FilamentC.FilaManipulatorBuilder_viewport(nativeBuilder, width, height) }
        actual fun targetPosition(x: Float, y: Float, z: Float): Builder = apply { FilamentC.FilaManipulatorBuilder_targetPosition(nativeBuilder, x, y, z) }
        actual fun upVector(x: Float, y: Float, z: Float): Builder = apply { FilamentC.FilaManipulatorBuilder_upVector(nativeBuilder, x, y, z) }
        actual fun zoomSpeed(speed: Float): Builder = apply { FilamentC.FilaManipulatorBuilder_zoomSpeed(nativeBuilder, speed) }
        actual fun orbitHomePosition(x: Float, y: Float, z: Float): Builder = apply { FilamentC.FilaManipulatorBuilder_orbitHomePosition(nativeBuilder, x, y, z) }
        actual fun orbitSpeed(x: Float, y: Float): Builder = apply { FilamentC.FilaManipulatorBuilder_orbitSpeed(nativeBuilder, x, y) }
        actual fun fovDirection(fov: Fov): Builder = apply { FilamentC.FilaManipulatorBuilder_fovDirection(nativeBuilder, fov.ordinal) }
        actual fun fovDegrees(degrees: Float): Builder = apply { FilamentC.FilaManipulatorBuilder_fovDegrees(nativeBuilder, degrees) }
        actual fun farPlane(distance: Float): Builder = apply { FilamentC.FilaManipulatorBuilder_farPlane(nativeBuilder, distance) }
        actual fun mapExtent(width: Float, height: Float): Builder = apply { FilamentC.FilaManipulatorBuilder_mapExtent(nativeBuilder, width, height) }
        actual fun mapMinDistance(distance: Float): Builder = apply { FilamentC.FilaManipulatorBuilder_mapMinDistance(nativeBuilder, distance) }
        actual fun flightStartPosition(x: Float, y: Float, z: Float): Builder = apply { FilamentC.FilaManipulatorBuilder_flightStartPosition(nativeBuilder, x, y, z) }
        actual fun flightStartOrientation(pitch: Float, yaw: Float): Builder = apply { FilamentC.FilaManipulatorBuilder_flightStartOrientation(nativeBuilder, pitch, yaw) }
        actual fun flightMaxMoveSpeed(maxSpeed: Float): Builder = apply { FilamentC.FilaManipulatorBuilder_flightMaxMoveSpeed(nativeBuilder, maxSpeed) }
        actual fun flightSpeedSteps(steps: Int): Builder = apply { FilamentC.FilaManipulatorBuilder_flightSpeedSteps(nativeBuilder, steps) }
        actual fun flightPanSpeed(x: Float, y: Float): Builder = apply { FilamentC.FilaManipulatorBuilder_flightPanSpeed(nativeBuilder, x, y) }
        actual fun flightMoveDamping(damping: Float): Builder = apply { FilamentC.FilaManipulatorBuilder_flightMoveDamping(nativeBuilder, damping) }
        actual fun groundPlane(a: Float, b: Float, c: Float, d: Float): Builder = apply { FilamentC.FilaManipulatorBuilder_groundPlane(nativeBuilder, a, b, c, d) }
        actual fun panning(enabled: Boolean): Builder = apply { FilamentC.FilaManipulatorBuilder_panning(nativeBuilder, enabled) }

        actual fun build(mode: Mode): Manipulator {
            val handle = FilamentC.FilaManipulatorBuilder_build(nativeBuilder, mode.ordinal)
            FilamentC.FilaManipulatorBuilder_destroy(nativeBuilder)
            return Manipulator(handle)
        }
    }

    actual fun destroy() = FilamentC.FilaManipulator_destroy(nativeHandle)

    actual fun getMode(): Mode = Mode.entries[FilamentC.FilaManipulator_getMode(nativeHandle)]

    actual fun setViewport(width: Int, height: Int) = FilamentC.FilaManipulator_setViewport(nativeHandle, width, height)

    actual fun getLookAt(outEye: FloatArray, outTarget: FloatArray, outUp: FloatArray): Unit = confined { a ->
        val eye = a.allocate(ValueLayout.JAVA_FLOAT, 3)
        val target = a.allocate(ValueLayout.JAVA_FLOAT, 3)
        val up = a.allocate(ValueLayout.JAVA_FLOAT, 3)
        FilamentC.FilaManipulator_getLookAt(nativeHandle, eye, target, up)
        for (i in 0 until 3) {
            outEye[i] = eye.getFloatAt(i)
            outTarget[i] = target.getFloatAt(i)
            outUp[i] = up.getFloatAt(i)
        }
    }

    actual fun raycast(x: Int, y: Int, outResult: FloatArray): Unit = confined { a ->
        val result = a.allocate(ValueLayout.JAVA_FLOAT, 3)
        FilamentC.FilaManipulator_raycast(nativeHandle, x, y, result)
        for (i in 0 until 3) outResult[i] = result.getFloatAt(i)
    }

    actual fun grabBegin(x: Int, y: Int, strafe: Boolean) = FilamentC.FilaManipulator_grabBegin(nativeHandle, x, y, strafe)
    actual fun grabUpdate(x: Int, y: Int) = FilamentC.FilaManipulator_grabUpdate(nativeHandle, x, y)
    actual fun grabEnd() = FilamentC.FilaManipulator_grabEnd(nativeHandle)
    actual fun keyDown(key: Key) = FilamentC.FilaManipulator_keyDown(nativeHandle, key.ordinal)
    actual fun keyUp(key: Key) = FilamentC.FilaManipulator_keyUp(nativeHandle, key.ordinal)
    actual fun scroll(x: Int, y: Int, delta: Float) = FilamentC.FilaManipulator_scroll(nativeHandle, x, y, delta)
    actual fun update(deltaTime: Float) = FilamentC.FilaManipulator_update(nativeHandle, deltaTime)

    actual fun getCurrentBookmark(): Bookmark = Bookmark(FilamentC.FilaManipulator_getCurrentBookmark(nativeHandle))
    actual fun getHomeBookmark(): Bookmark = Bookmark(FilamentC.FilaManipulator_getHomeBookmark(nativeHandle))
    actual fun jumpToBookmark(bookmark: Bookmark) = FilamentC.FilaManipulator_jumpToBookmark(nativeHandle, bookmark.nativeHandle)

    actual class Bookmark internal constructor(internal val nativeHandle: MemorySegment)
}
