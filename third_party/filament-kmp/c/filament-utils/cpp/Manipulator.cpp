#include <camutils/Manipulator.h>
#include <math/vec3.h>

#include "../../filament/cpp/FilaCommon.h"
#include "../c/Manipulator.h"

using namespace filament::camutils;
using namespace filament::math;

using Builder = Manipulator<float>::Builder;

extern "C" {

FilaManipulatorBuilder* FilaManipulatorBuilder_create() {
    return reinterpret_cast<FilaManipulatorBuilder*>(new Builder());
}

void FilaManipulatorBuilder_destroy(FilaManipulatorBuilder* builder) {
    delete reinterpret_cast<Builder*>(builder);
}

FilaManipulator* FilaManipulatorBuilder_build(FilaManipulatorBuilder* builder, FilaManipulatorMode mode) {
    return reinterpret_cast<FilaManipulator*>(reinterpret_cast<Builder*>(builder)->build(static_cast<Mode>(mode)));
}

void FilaManipulatorBuilder_viewport(FilaManipulatorBuilder* builder, int32_t width, int32_t height) {
    reinterpret_cast<Builder*>(builder)->viewport(width, height);
}

void FilaManipulatorBuilder_targetPosition(FilaManipulatorBuilder* builder, float x, float y, float z) {
    reinterpret_cast<Builder*>(builder)->targetPosition(x, y, z);
}

void FilaManipulatorBuilder_upVector(FilaManipulatorBuilder* builder, float x, float y, float z) {
    reinterpret_cast<Builder*>(builder)->upVector(x, y, z);
}

void FilaManipulatorBuilder_zoomSpeed(FilaManipulatorBuilder* builder, float speed) {
    reinterpret_cast<Builder*>(builder)->zoomSpeed(speed);
}

void FilaManipulatorBuilder_orbitHomePosition(FilaManipulatorBuilder* builder, float x, float y, float z) {
    reinterpret_cast<Builder*>(builder)->orbitHomePosition(x, y, z);
}

void FilaManipulatorBuilder_orbitSpeed(FilaManipulatorBuilder* builder, float x, float y) {
    reinterpret_cast<Builder*>(builder)->orbitSpeed(x, y);
}

void FilaManipulatorBuilder_fovDirection(FilaManipulatorBuilder* builder, FilaManipulatorFov fov) {
    reinterpret_cast<Builder*>(builder)->fovDirection(static_cast<Fov>(fov));
}

void FilaManipulatorBuilder_fovDegrees(FilaManipulatorBuilder* builder, float degrees) {
    reinterpret_cast<Builder*>(builder)->fovDegrees(degrees);
}

void FilaManipulatorBuilder_farPlane(FilaManipulatorBuilder* builder, float distance) {
    reinterpret_cast<Builder*>(builder)->farPlane(distance);
}

void FilaManipulatorBuilder_mapExtent(FilaManipulatorBuilder* builder, float width, float height) {
    reinterpret_cast<Builder*>(builder)->mapExtent(width, height);
}

void FilaManipulatorBuilder_mapMinDistance(FilaManipulatorBuilder* builder, float distance) {
    reinterpret_cast<Builder*>(builder)->mapMinDistance(distance);
}

void FilaManipulatorBuilder_flightStartPosition(FilaManipulatorBuilder* builder, float x, float y, float z) {
    reinterpret_cast<Builder*>(builder)->flightStartPosition(x, y, z);
}

void FilaManipulatorBuilder_flightStartOrientation(FilaManipulatorBuilder* builder, float pitch, float yaw) {
    reinterpret_cast<Builder*>(builder)->flightStartOrientation(pitch, yaw);
}

void FilaManipulatorBuilder_flightMaxMoveSpeed(FilaManipulatorBuilder* builder, float maxSpeed) {
    reinterpret_cast<Builder*>(builder)->flightMaxMoveSpeed(maxSpeed);
}

void FilaManipulatorBuilder_flightSpeedSteps(FilaManipulatorBuilder* builder, int32_t steps) {
    reinterpret_cast<Builder*>(builder)->flightSpeedSteps(steps);
}

void FilaManipulatorBuilder_flightPanSpeed(FilaManipulatorBuilder* builder, float x, float y) {
    reinterpret_cast<Builder*>(builder)->flightPanSpeed(x, y);
}

void FilaManipulatorBuilder_flightMoveDamping(FilaManipulatorBuilder* builder, float damping) {
    reinterpret_cast<Builder*>(builder)->flightMoveDamping(damping);
}

void FilaManipulatorBuilder_groundPlane(FilaManipulatorBuilder* builder, float a, float b, float c, float d) {
    reinterpret_cast<Builder*>(builder)->groundPlane(a, b, c, d);
}

void FilaManipulatorBuilder_panning(FilaManipulatorBuilder* builder, bool enabled) {
    reinterpret_cast<Builder*>(builder)->panning(enabled);
}

// Manipulator
void FilaManipulator_destroy(FilaManipulator* manip) {
    delete reinterpret_cast<Manipulator<float>*>(manip);
}

FilaManipulatorMode FilaManipulator_getMode(const FilaManipulator* manip) {
    return static_cast<FilaManipulatorMode>(reinterpret_cast<const Manipulator<float>*>(manip)->getMode());
}

void FilaManipulator_setViewport(FilaManipulator* manip, int32_t width, int32_t height) {
    reinterpret_cast<Manipulator<float>*>(manip)->setViewport(width, height);
}

void FilaManipulator_getLookAt(const FilaManipulator* manip, float* outEye, float* outTarget, float* outUp) {
    reinterpret_cast<const Manipulator<float>*>(manip)->getLookAt(
        reinterpret_cast<float3*>(outEye),
        reinterpret_cast<float3*>(outTarget),
        reinterpret_cast<float3*>(outUp)
    );
}

void FilaManipulator_raycast(const FilaManipulator* manip, int32_t x, int32_t y, float* outResult) {
    reinterpret_cast<const Manipulator<float>*>(manip)->raycast(x, y, reinterpret_cast<float3*>(outResult));
}

void FilaManipulator_grabBegin(FilaManipulator* manip, int32_t x, int32_t y, bool strafe) {
    reinterpret_cast<Manipulator<float>*>(manip)->grabBegin(x, y, strafe);
}

void FilaManipulator_grabUpdate(FilaManipulator* manip, int32_t x, int32_t y) {
    reinterpret_cast<Manipulator<float>*>(manip)->grabUpdate(x, y);
}

void FilaManipulator_grabEnd(FilaManipulator* manip) {
    reinterpret_cast<Manipulator<float>*>(manip)->grabEnd();
}

void FilaManipulator_keyDown(FilaManipulator* manip, FilaManipulatorKey key) {
    reinterpret_cast<Manipulator<float>*>(manip)->keyDown(static_cast<Manipulator<float>::Key>(key));
}

void FilaManipulator_keyUp(FilaManipulator* manip, FilaManipulatorKey key) {
    reinterpret_cast<Manipulator<float>*>(manip)->keyUp(static_cast<Manipulator<float>::Key>(key));
}

void FilaManipulator_scroll(FilaManipulator* manip, int32_t x, int32_t y, float delta) {
    reinterpret_cast<Manipulator<float>*>(manip)->scroll(x, y, delta);
}

void FilaManipulator_update(FilaManipulator* manip, float deltaTime) {
    reinterpret_cast<Manipulator<float>*>(manip)->update(deltaTime);
}

// Bookmarks
FilaBookmark* FilaManipulator_getCurrentBookmark(const FilaManipulator* manip) {
    Bookmark<float> b = reinterpret_cast<const Manipulator<float>*>(manip)->getCurrentBookmark();
    return reinterpret_cast<FilaBookmark*>(new Bookmark<float>(std::move(b)));
}

FilaBookmark* FilaManipulator_getHomeBookmark(const FilaManipulator* manip) {
    Bookmark<float> b = reinterpret_cast<const Manipulator<float>*>(manip)->getHomeBookmark();
    return reinterpret_cast<FilaBookmark*>(new Bookmark<float>(std::move(b)));
}

void FilaManipulator_jumpToBookmark(FilaManipulator* manip, const FilaBookmark* bookmark) {
    reinterpret_cast<Manipulator<float>*>(manip)->jumpToBookmark(*reinterpret_cast<const Bookmark<float>*>(bookmark));
}

void FilaBookmark_destroy(FilaBookmark* bookmark) {
    delete reinterpret_cast<Bookmark<float>*>(bookmark);
}

} // extern "C"
