#include <stdint.h>
#include <stddef.h>
#include <geometry/SurfaceOrientation.h>
#include "../c/SurfaceOrientation.h"

using namespace filament;
using namespace filament::geometry;

struct FilaSurfaceOrientationBuilder {
    SurfaceOrientation::Builder builder;
};

struct FilaSurfaceOrientation {
    SurfaceOrientation* orientation;
};

extern "C" {

FilaSurfaceOrientationBuilder* FilaSurfaceOrientationBuilder_create() {
    return new FilaSurfaceOrientationBuilder();
}

void FilaSurfaceOrientationBuilder_destroy(FilaSurfaceOrientationBuilder* builder) {
    delete builder;
}

void FilaSurfaceOrientationBuilder_vertexCount(FilaSurfaceOrientationBuilder* builder, uint32_t vertexCount) {
    builder->builder.vertexCount(vertexCount);
}

void FilaSurfaceOrientationBuilder_normals(FilaSurfaceOrientationBuilder* builder, const float* buffer, uint32_t stride) {
    builder->builder.normals(reinterpret_cast<const math::float3*>(buffer), stride);
}

void FilaSurfaceOrientationBuilder_tangents(FilaSurfaceOrientationBuilder* builder, const float* buffer, uint32_t stride) {
    builder->builder.tangents(reinterpret_cast<const math::float4*>(buffer), stride);
}

void FilaSurfaceOrientationBuilder_uvs(FilaSurfaceOrientationBuilder* builder, const float* buffer, uint32_t stride) {
    builder->builder.uvs(reinterpret_cast<const math::float2*>(buffer), stride);
}

void FilaSurfaceOrientationBuilder_positions(FilaSurfaceOrientationBuilder* builder, const float* buffer, uint32_t stride) {
    builder->builder.positions(reinterpret_cast<const math::float3*>(buffer), stride);
}

void FilaSurfaceOrientationBuilder_triangleCount(FilaSurfaceOrientationBuilder* builder, uint32_t triangleCount) {
    builder->builder.triangleCount(triangleCount);
}

void FilaSurfaceOrientationBuilder_triangles16(FilaSurfaceOrientationBuilder* builder, const uint16_t* buffer) {
    builder->builder.triangles(reinterpret_cast<const math::ushort3*>(buffer));
}

void FilaSurfaceOrientationBuilder_triangles32(FilaSurfaceOrientationBuilder* builder, const uint32_t* buffer) {
    builder->builder.triangles(reinterpret_cast<const math::uint3*>(buffer));
}

FilaSurfaceOrientation* FilaSurfaceOrientationBuilder_build(FilaSurfaceOrientationBuilder* builder) {
    SurfaceOrientation* orientation = builder->builder.build();
    if (orientation == nullptr) return nullptr;
    return new FilaSurfaceOrientation{orientation};
}

void FilaSurfaceOrientation_destroy(FilaSurfaceOrientation* orientation) {
    delete orientation->orientation;
    delete orientation;
}

uint32_t FilaSurfaceOrientation_getVertexCount(const FilaSurfaceOrientation* orientation) {
    return orientation->orientation->getVertexCount();
}

void FilaSurfaceOrientation_getQuatsAsFloat(const FilaSurfaceOrientation* orientation, float* buffer, uint32_t count) {
    orientation->orientation->getQuats(reinterpret_cast<math::quatf*>(buffer), count);
}

void FilaSurfaceOrientation_getQuatsAsHalf(const FilaSurfaceOrientation* orientation, uint16_t* buffer, uint32_t count) {
    orientation->orientation->getQuats(reinterpret_cast<math::quath*>(buffer), count);
}

void FilaSurfaceOrientation_getQuatsAsShort(const FilaSurfaceOrientation* orientation, int16_t* buffer, uint32_t count) {
    orientation->orientation->getQuats(reinterpret_cast<math::short4*>(buffer), count);
}

}
