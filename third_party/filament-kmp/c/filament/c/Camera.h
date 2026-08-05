#ifndef FILAMENT_C_CAMERA_H
#define FILAMENT_C_CAMERA_H

#include "FilaTypes.h"

#ifdef __cplusplus
extern "C" {
#endif

typedef enum FilaCameraProjection {
    FILA_CAMERA_PROJECTION_PERSPECTIVE = 0,
    FILA_CAMERA_PROJECTION_ORTHO = 1,
} FilaCameraProjection;

typedef enum FilaCameraFov {
    FILA_CAMERA_FOV_VERTICAL = 0,
    FILA_CAMERA_FOV_HORIZONTAL = 1,
} FilaCameraFov;

void FilaCamera_setProjection(FilaCamera* camera, FilaCameraProjection projection,
        double left, double right, double bottom, double top, double nearPlane, double farPlane);

void FilaCamera_setProjectionFov(FilaCamera* camera, double fovInDegrees, double aspect,
        double nearPlane, double farPlane, FilaCameraFov direction);

double FilaCamera_getFieldOfViewInDegrees(const FilaCamera* camera, FilaCameraFov direction);

void FilaCamera_setLensProjection(FilaCamera* camera, double focalLength, double aspect,
        double nearPlane, double farPlane);

void FilaCamera_setCustomProjection(FilaCamera* camera, const double matrix[16],
        const double matrixForCulling[16], double nearPlane, double farPlane);

void FilaCamera_setCustomEyeProjection(FilaCamera* camera, const double* projectionMatrices,
        size_t count, const double matrixForCulling[16], double nearPlane, double farPlane);

void FilaCamera_setScaling(FilaCamera* camera, double x, double y);
void FilaCamera_setShift(FilaCamera* camera, double x, double y);
void FilaCamera_getShift(const FilaCamera* camera, double out[2]);

void FilaCamera_lookAt(FilaCamera* camera,
        double eyeX, double eyeY, double eyeZ,
        double centerX, double centerY, double centerZ,
        double upX, double upY, double upZ);

double FilaCamera_getNear(const FilaCamera* camera);
double FilaCamera_getCullingFar(const FilaCamera* camera);

void FilaCamera_setModelMatrix(FilaCamera* camera, const float matrix[16]);
void FilaCamera_setModelMatrixFp64(FilaCamera* camera, const double matrix[16]);
void FilaCamera_setEyeModelMatrix(FilaCamera* camera, int eyeId, const double matrix[16]);

void FilaCamera_getProjectionMatrix(const FilaCamera* camera, double out[16]);
void FilaCamera_getCullingProjectionMatrix(const FilaCamera* camera, double out[16]);
void FilaCamera_getScaling(const FilaCamera* camera, double out[4]);

void FilaCamera_getModelMatrix(const FilaCamera* camera, float out[16]);
void FilaCamera_getModelMatrixFp64(const FilaCamera* camera, double out[16]);
void FilaCamera_getViewMatrix(const FilaCamera* camera, float out[16]);
void FilaCamera_getViewMatrixFp64(const FilaCamera* camera, double out[16]);

void FilaCamera_getPosition(const FilaCamera* camera, float out[3]);
void FilaCamera_getLeftVector(const FilaCamera* camera, float out[3]);
void FilaCamera_getUpVector(const FilaCamera* camera, float out[3]);
void FilaCamera_getForwardVector(const FilaCamera* camera, float out[3]);

void FilaCamera_setExposure(FilaCamera* camera, float aperture, float shutterSpeed, float sensitivity);
float FilaCamera_getAperture(const FilaCamera* camera);
float FilaCamera_getShutterSpeed(const FilaCamera* camera);
float FilaCamera_getSensitivity(const FilaCamera* camera);

void FilaCamera_setFocusDistance(FilaCamera* camera, float focusDistance);
float FilaCamera_getFocusDistance(const FilaCamera* camera);
double FilaCamera_getFocalLength(const FilaCamera* camera);

double FilaCamera_computeEffectiveFocalLength(double focalLength, double focusDistance);
double FilaCamera_computeEffectiveFov(double fovInDegrees, double focusDistance);

FilaEntity FilaCamera_getEntity(const FilaCamera* camera);

#ifdef __cplusplus
}
#endif

#endif // FILAMENT_C_CAMERA_H
