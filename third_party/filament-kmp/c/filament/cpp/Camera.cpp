#include <filament/Camera.h>
#include <math/mat4.h>
#include <math/vec3.h>
#include <math/vec2.h>
#include <algorithm>

#include "FilaCommon.h"
#include "../c/Camera.h"

using namespace filament;

extern "C" {

void FilaCamera_setProjection(FilaCamera* camera, FilaCameraProjection projection,
        double left, double right, double bottom, double top, double nearPlane, double farPlane) {
    FILA_CAST(Camera, camera)->setProjection(static_cast<Camera::Projection>(projection),
            left, right, bottom, top, nearPlane, farPlane);
}

void FilaCamera_setProjectionFov(FilaCamera* camera, double fovInDegrees, double aspect,
        double nearPlane, double farPlane, FilaCameraFov direction) {
    FILA_CAST(Camera, camera)->setProjection(fovInDegrees, aspect, nearPlane, farPlane,
            static_cast<Camera::Fov>(direction));
}

double FilaCamera_getFieldOfViewInDegrees(const FilaCamera* camera, FilaCameraFov direction) {
    return FILA_CONST_CAST(Camera, camera)->getFieldOfViewInDegrees(static_cast<Camera::Fov>(direction));
}

void FilaCamera_setLensProjection(FilaCamera* camera, double focalLength, double aspect,
        double nearPlane, double farPlane) {
    FILA_CAST(Camera, camera)->setLensProjection(focalLength, aspect, nearPlane, farPlane);
}

void FilaCamera_setCustomProjection(FilaCamera* camera, const double matrix[16],
        const double matrixForCulling[16], double nearPlane, double farPlane) {
    FILA_CAST(Camera, camera)->setCustomProjection(
            *reinterpret_cast<const filament::math::mat4*>(matrix),
            *reinterpret_cast<const filament::math::mat4*>(matrixForCulling),
            nearPlane, farPlane);
}

void FilaCamera_setCustomEyeProjection(FilaCamera* camera, const double* projectionMatrices,
        size_t count, const double matrixForCulling[16], double nearPlane, double farPlane) {
    FILA_CAST(Camera, camera)->setCustomEyeProjection(
            reinterpret_cast<const filament::math::mat4*>(projectionMatrices), count,
            *reinterpret_cast<const filament::math::mat4*>(matrixForCulling),
            nearPlane, farPlane);
}

void FilaCamera_setScaling(FilaCamera* camera, double x, double y) {
    FILA_CAST(Camera, camera)->setScaling({x, y});
}

void FilaCamera_setShift(FilaCamera* camera, double x, double y) {
    FILA_CAST(Camera, camera)->setShift({x, y});
}

void FilaCamera_getShift(const FilaCamera* camera, double out[2]) {
    filament::math::double2 s = FILA_CONST_CAST(Camera, camera)->getShift();
    out[0] = s.x;
    out[1] = s.y;
}

void FilaCamera_lookAt(FilaCamera* camera,
        double eyeX, double eyeY, double eyeZ,
        double centerX, double centerY, double centerZ,
        double upX, double upY, double upZ) {
    FILA_CAST(Camera, camera)->lookAt({eyeX, eyeY, eyeZ}, {centerX, centerY, centerZ}, {upX, upY, upZ});
}

double FilaCamera_getNear(const FilaCamera* camera) {
    return FILA_CONST_CAST(Camera, camera)->getNear();
}

double FilaCamera_getCullingFar(const FilaCamera* camera) {
    return FILA_CONST_CAST(Camera, camera)->getCullingFar();
}

void FilaCamera_setModelMatrix(FilaCamera* camera, const float matrix[16]) {
    FILA_CAST(Camera, camera)->setModelMatrix(
            static_cast<math::mat4>(*reinterpret_cast<const filament::math::mat4f*>(matrix)));
}

void FilaCamera_setModelMatrixFp64(FilaCamera* camera, const double matrix[16]) {
    FILA_CAST(Camera, camera)->setModelMatrix(*reinterpret_cast<const filament::math::mat4*>(matrix));
}

void FilaCamera_setEyeModelMatrix(FilaCamera* camera, int eyeId, const double matrix[16]) {
    FILA_CAST(Camera, camera)->setEyeModelMatrix((uint8_t)eyeId,
            *reinterpret_cast<const filament::math::mat4*>(matrix));
}

void FilaCamera_getProjectionMatrix(const FilaCamera* camera, double out[16]) {
    const filament::math::mat4& m = FILA_CONST_CAST(Camera, camera)->getProjectionMatrix();
    std::copy_n(&m[0][0], 16, out);
}

void FilaCamera_getCullingProjectionMatrix(const FilaCamera* camera, double out[16]) {
    const filament::math::mat4& m = FILA_CONST_CAST(Camera, camera)->getCullingProjectionMatrix();
    std::copy_n(&m[0][0], 16, out);
}

void FilaCamera_getScaling(const FilaCamera* camera, double out[4]) {
    const filament::math::double4& s = FILA_CONST_CAST(Camera, camera)->getScaling();
    std::copy_n(&s[0], 4, out);
}

void FilaCamera_getModelMatrix(const FilaCamera* camera, float out[16]) {
    const filament::math::mat4f& m = static_cast<math::mat4f>(FILA_CONST_CAST(Camera, camera)->getModelMatrix());
    std::copy_n(&m[0][0], 16, out);
}

void FilaCamera_getModelMatrixFp64(const FilaCamera* camera, double out[16]) {
    const filament::math::mat4& m = FILA_CONST_CAST(Camera, camera)->getModelMatrix();
    std::copy_n(&m[0][0], 16, out);
}

void FilaCamera_getViewMatrix(const FilaCamera* camera, float out[16]) {
    const filament::math::mat4f& m = static_cast<math::mat4f>(FILA_CONST_CAST(Camera, camera)->getViewMatrix());
    std::copy_n(&m[0][0], 16, out);
}

void FilaCamera_getViewMatrixFp64(const FilaCamera* camera, double out[16]) {
    const filament::math::mat4& m = FILA_CONST_CAST(Camera, camera)->getViewMatrix();
    std::copy_n(&m[0][0], 16, out);
}

void FilaCamera_getPosition(const FilaCamera* camera, float out[3]) {
    *reinterpret_cast<filament::math::float3*>(out) = FILA_CONST_CAST(Camera, camera)->getPosition();
}

void FilaCamera_getLeftVector(const FilaCamera* camera, float out[3]) {
    *reinterpret_cast<filament::math::float3*>(out) = FILA_CONST_CAST(Camera, camera)->getLeftVector();
}

void FilaCamera_getUpVector(const FilaCamera* camera, float out[3]) {
    *reinterpret_cast<filament::math::float3*>(out) = FILA_CONST_CAST(Camera, camera)->getUpVector();
}

void FilaCamera_getForwardVector(const FilaCamera* camera, float out[3]) {
    *reinterpret_cast<filament::math::float3*>(out) = FILA_CONST_CAST(Camera, camera)->getForwardVector();
}

void FilaCamera_setExposure(FilaCamera* camera, float aperture, float shutterSpeed, float sensitivity) {
    FILA_CAST(Camera, camera)->setExposure(aperture, shutterSpeed, sensitivity);
}

float FilaCamera_getAperture(const FilaCamera* camera) {
    return FILA_CONST_CAST(Camera, camera)->getAperture();
}

float FilaCamera_getShutterSpeed(const FilaCamera* camera) {
    return FILA_CONST_CAST(Camera, camera)->getShutterSpeed();
}

float FilaCamera_getSensitivity(const FilaCamera* camera) {
    return FILA_CONST_CAST(Camera, camera)->getSensitivity();
}

void FilaCamera_setFocusDistance(FilaCamera* camera, float focusDistance) {
    FILA_CAST(Camera, camera)->setFocusDistance(focusDistance);
}

float FilaCamera_getFocusDistance(const FilaCamera* camera) {
    return FILA_CONST_CAST(Camera, camera)->getFocusDistance();
}

double FilaCamera_getFocalLength(const FilaCamera* camera) {
    return FILA_CONST_CAST(Camera, camera)->getFocalLength();
}

double FilaCamera_computeEffectiveFocalLength(double focalLength, double focusDistance) {
    return Camera::computeEffectiveFocalLength(focalLength, focusDistance);
}

double FilaCamera_computeEffectiveFov(double fovInDegrees, double focusDistance) {
    return Camera::computeEffectiveFov(fovInDegrees, focusDistance);
}

FilaEntity FilaCamera_getEntity(const FilaCamera* camera) {
    return FILA_CONST_CAST(Camera, camera)->getEntity().getId();
}

} // extern "C"
