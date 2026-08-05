#ifndef FILAMENT_UTILS_C_MANIPULATOR_H
#define FILAMENT_UTILS_C_MANIPULATOR_H

#include "FilamentUtilsTypes.h"

#ifdef __cplusplus
extern "C" {
#endif

typedef enum FilaManipulatorMode {
    FILA_MANIPULATOR_MODE_ORBIT = 0,
    FILA_MANIPULATOR_MODE_MAP = 1,
    FILA_MANIPULATOR_MODE_FLIGHT = 2,
} FilaManipulatorMode;

typedef enum FilaManipulatorFov {
    FILA_MANIPULATOR_FOV_VERTICAL = 0,
    FILA_MANIPULATOR_FOV_HORIZONTAL = 1,
} FilaManipulatorFov;

// Key codes matching filament::camutils::Manipulator::Key
typedef enum FilaManipulatorKey {
    FILA_MANIPULATOR_KEY_FORWARD = 0,
    FILA_MANIPULATOR_KEY_LEFT = 1,
    FILA_MANIPULATOR_KEY_BACKWARD = 2,
    FILA_MANIPULATOR_KEY_RIGHT = 3,
    FILA_MANIPULATOR_KEY_UP = 4,
    FILA_MANIPULATOR_KEY_DOWN = 5,
} FilaManipulatorKey;

// Builder
FilaManipulatorBuilder* FilaManipulatorBuilder_create(void);
void FilaManipulatorBuilder_destroy(FilaManipulatorBuilder* builder);
FilaManipulator* FilaManipulatorBuilder_build(FilaManipulatorBuilder* builder, FilaManipulatorMode mode);

void FilaManipulatorBuilder_viewport(FilaManipulatorBuilder* builder, int32_t width, int32_t height);
void FilaManipulatorBuilder_targetPosition(FilaManipulatorBuilder* builder, float x, float y, float z);
void FilaManipulatorBuilder_upVector(FilaManipulatorBuilder* builder, float x, float y, float z);
void FilaManipulatorBuilder_zoomSpeed(FilaManipulatorBuilder* builder, float speed);
void FilaManipulatorBuilder_orbitHomePosition(FilaManipulatorBuilder* builder, float x, float y, float z);
void FilaManipulatorBuilder_orbitSpeed(FilaManipulatorBuilder* builder, float x, float y);
void FilaManipulatorBuilder_fovDirection(FilaManipulatorBuilder* builder, FilaManipulatorFov fov);
void FilaManipulatorBuilder_fovDegrees(FilaManipulatorBuilder* builder, float degrees);
void FilaManipulatorBuilder_farPlane(FilaManipulatorBuilder* builder, float distance);
void FilaManipulatorBuilder_mapExtent(FilaManipulatorBuilder* builder, float width, float height);
void FilaManipulatorBuilder_mapMinDistance(FilaManipulatorBuilder* builder, float distance);
void FilaManipulatorBuilder_flightStartPosition(FilaManipulatorBuilder* builder, float x, float y, float z);
void FilaManipulatorBuilder_flightStartOrientation(FilaManipulatorBuilder* builder, float pitch, float yaw);
void FilaManipulatorBuilder_flightMaxMoveSpeed(FilaManipulatorBuilder* builder, float maxSpeed);
void FilaManipulatorBuilder_flightSpeedSteps(FilaManipulatorBuilder* builder, int32_t steps);
void FilaManipulatorBuilder_flightPanSpeed(FilaManipulatorBuilder* builder, float x, float y);
void FilaManipulatorBuilder_flightMoveDamping(FilaManipulatorBuilder* builder, float damping);
void FilaManipulatorBuilder_groundPlane(FilaManipulatorBuilder* builder, float a, float b, float c, float d);
void FilaManipulatorBuilder_panning(FilaManipulatorBuilder* builder, bool enabled);

// Manipulator methods
void FilaManipulator_destroy(FilaManipulator* manip);
FilaManipulatorMode FilaManipulator_getMode(const FilaManipulator* manip);
void FilaManipulator_setViewport(FilaManipulator* manip, int32_t width, int32_t height);
void FilaManipulator_getLookAt(const FilaManipulator* manip, float* outEye, float* outTarget, float* outUp);
void FilaManipulator_raycast(const FilaManipulator* manip, int32_t x, int32_t y, float* outResult);
void FilaManipulator_grabBegin(FilaManipulator* manip, int32_t x, int32_t y, bool strafe);
void FilaManipulator_grabUpdate(FilaManipulator* manip, int32_t x, int32_t y);
void FilaManipulator_grabEnd(FilaManipulator* manip);
void FilaManipulator_keyDown(FilaManipulator* manip, FilaManipulatorKey key);
void FilaManipulator_keyUp(FilaManipulator* manip, FilaManipulatorKey key);
void FilaManipulator_scroll(FilaManipulator* manip, int32_t x, int32_t y, float delta);
void FilaManipulator_update(FilaManipulator* manip, float deltaTime);

// Bookmarks
FilaBookmark* FilaManipulator_getCurrentBookmark(const FilaManipulator* manip);
FilaBookmark* FilaManipulator_getHomeBookmark(const FilaManipulator* manip);
void FilaManipulator_jumpToBookmark(FilaManipulator* manip, const FilaBookmark* bookmark);
void FilaBookmark_destroy(FilaBookmark* bookmark);

#ifdef __cplusplus
}
#endif

#endif // FILAMENT_UTILS_C_MANIPULATOR_H
