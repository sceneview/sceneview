#include "../c/MathUtils.h"
#include <math/mat3.h>
#include <math/quat.h>

using namespace filament::math;

extern "C" {

void FilaMathUtils_packTangentFrame(
    float tangentX, float tangentY, float tangentZ,
    float bitangentX, float bitangentY, float bitangentZ,
    float normalX, float normalY, float normalZ,
    float* outQuaternion) {

    float3 tangent{tangentX, tangentY, tangentZ};
    float3 bitangent{bitangentX, bitangentY, bitangentZ};
    float3 normal{normalX, normalY, normalZ};
    
    quatf q = mat3f::packTangentFrame({tangent, bitangent, normal});
    
    outQuaternion[0] = q.x;
    outQuaternion[1] = q.y;
    outQuaternion[2] = q.z;
    outQuaternion[3] = q.w;
}

}
