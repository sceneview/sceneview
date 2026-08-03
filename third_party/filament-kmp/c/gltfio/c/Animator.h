#ifndef GLTFIO_C_ANIMATOR_H
#define GLTFIO_C_ANIMATOR_H

#include "GltfioTypes.h"

#ifdef __cplusplus
extern "C" {
#endif

void FilaAnimator_applyAnimation(FilaAnimator* animator, size_t animationIndex, float time);
void FilaAnimator_updateBoneMatrices(FilaAnimator* animator);
size_t FilaAnimator_getAnimationCount(FilaAnimator* animator);
float FilaAnimator_getAnimationDuration(FilaAnimator* animator, size_t animationIndex);
const char* FilaAnimator_getAnimationName(FilaAnimator* animator, size_t animationIndex);

void FilaAnimator_applyCrossFade(FilaAnimator* animator, size_t previousAnimationIndex, float previousAnimationTime, float alpha);
void FilaAnimator_resetBoneMatrices(FilaAnimator* animator);

#ifdef __cplusplus
}
#endif

#endif // GLTFIO_C_ANIMATOR_H
