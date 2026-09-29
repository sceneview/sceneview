// CosmosShaders.metal — RealityKit CustomMaterial shaders of the Cosmos demo.
//
// A port of the Android demo's three Filament materials (cosmos_sprite.mat,
// cosmos_ribbon.mat, cosmos_plasma.mat): same maths, same constants, so the two apps glow
// the same. Everything is unlit and additive except the plasma star, and every colour is
// linear HDR radiance — the bloom pass bleeds from whatever goes above 1.0.
//
// Vertex layouts (built by CosmosMeshes.swift, uploaded as a LowLevelMesh):
//   sprite — position, uv3 = colour, uv2 = (corner.x, corner.y, radius, twinkle phase)
//   ribbon — position, uv4 = colour, uv2 = (tangent.xyz, side), uv3 = (half width, t, seed, arc)
// custom_parameter, set every frame by CosmosDemo.swift:
//   sprite — (time, intensity, sizeScale, viewport height in pixels)
//   ribbon — (time, intensity × fade, head, viewport height in pixels)
//   plasma — (time, gain, look: 0 star / 1 nucleus, 0)
//
// Filament materials take their constants as parameters; RealityKit gives a custom material
// one float4, so the per-instance constants (twinkle, dash rhythm…) are baked into one
// entry point per instance instead.

#include <metal_stdlib>
#include <RealityKit/RealityKit.h>
using namespace metal;

// MARK: - Shared camera maths

struct CosmosView {
    float3 viewPosition;   // the vertex in view space
    float depth;           // distance along the view axis
    float focal;           // pixels per view unit at depth 1
    float scale;           // model → view uniform scale
    float3x3 modelToView;  // rotation × scale
};

static inline CosmosView cosmosView(realitykit::geometry_parameters params, float3 point, float viewportHeight) {
    CosmosView v;
    float4x4 mv = params.uniforms().model_to_view();
    float4x4 proj = params.uniforms().view_to_projection();
    v.viewPosition = (mv * float4(point, 1.0)).xyz;
    v.depth = max(-v.viewPosition.z, 1e-3);
    v.focal = proj[1][1] * 0.5 * viewportHeight;
    v.modelToView = float3x3(mv[0].xyz, mv[1].xyz, mv[2].xyz);
    v.scale = max(length(mv[0].xyz), 1e-6);
    return v;
}

/// A view-space offset brought back to model space (the inverse of rotation × uniform scale).
static inline float3 cosmosToModel(CosmosView v, float3 viewOffset) {
    return transpose(v.modelToView) * viewOffset / (v.scale * v.scale);
}

// MARK: - Sprites (cosmos_sprite.mat)

static inline void cosmosSprite(realitykit::geometry_parameters params, float twinkle, float minPixels) {
    float4 c0 = params.geometry().uv2();
    float4 k = params.uniforms().custom_parameter();
    float3 center = params.geometry().model_position();
    CosmosView v = cosmosView(params, center, k.w);

    float radius = c0.z * k.z * v.scale;
    float radiusPx = radius * v.focal / v.depth;
    float energy = 1.0;
    if (radiusPx < minPixels) {
        energy = (radiusPx * radiusPx) / (minPixels * minPixels);
        radius = minPixels * v.depth / v.focal;
    }
    float3 offset = float3(c0.x, c0.y, 0.0) * radius;
    params.geometry().set_model_position_offset(cosmosToModel(v, offset));

    float flicker = 1.0 + twinkle * sin(k.x * 2.7 + c0.w * 6.2831);
    params.geometry().set_uv2(float4(c0.xy, energy * flicker, 0.0));
}

[[visible]] void cosmosSpriteGeometry(realitykit::geometry_parameters params) { cosmosSprite(params, 0.0, 1.1); }
[[visible]] void cosmosStarFieldGeometry(realitykit::geometry_parameters params) { cosmosSprite(params, 0.3, 1.4); }
[[visible]] void cosmosGalaxyGeometry(realitykit::geometry_parameters params) { cosmosSprite(params, 0.12, 1.0); }
[[visible]] void cosmosSparksGeometry(realitykit::geometry_parameters params) { cosmosSprite(params, 0.6, 1.6); }
[[visible]] void cosmosDustGeometry(realitykit::geometry_parameters params) { cosmosSprite(params, 0.7, 1.3); }

[[visible]] void cosmosSpriteSurface(realitykit::surface_parameters params) {
    float4 sprite = params.geometry().uv2();
    float3 color = params.geometry().uv3().rgb;
    float intensity = params.uniforms().custom_parameter().y;
    float d2 = dot(sprite.xy, sprite.xy);
    // A tight gaussian core and a soft skirt, cut to zero at the quad edge.
    float glow = (exp(-d2 * 10.0) + 0.18 * exp(-d2 * 2.5)) * max(0.0, 1.0 - d2);
    float3 radiance = color * (glow * sprite.z * intensity);
    params.surface().set_base_color(half3(radiance));
    params.surface().set_opacity(1.0h);
}

// MARK: - Ribbons (cosmos_ribbon.mat)

static inline void cosmosRibbon(realitykit::geometry_parameters params, float minPixels) {
    float4 c0 = params.geometry().uv2();
    float4 c1 = params.geometry().uv3();
    float4 k = params.uniforms().custom_parameter();
    float3 point = params.geometry().model_position();
    CosmosView v = cosmosView(params, point, k.w);

    float3 tangent = v.modelToView * c0.xyz;
    float3 toEye = -v.viewPosition;
    float3 side = cross(tangent, toEye);
    float sideLength = length(side);
    side = sideLength > 1e-6 ? side / sideLength : float3(0.0);

    float halfWidth = c1.x * v.scale;
    float halfWidthPx = halfWidth * v.focal / v.depth;
    float energy = 1.0;
    if (halfWidthPx < minPixels) {
        energy = halfWidthPx / minPixels;
        halfWidth = minPixels * v.depth / v.focal;
    }
    params.geometry().set_model_position_offset(cosmosToModel(v, side * (c0.w * halfWidth)));
    params.geometry().set_uv2(float4(c0.w, c1.y, c1.z, c1.w));
    params.geometry().set_uv3(float4(energy, 0.0, 0.0, 0.0));
}

[[visible]] void cosmosRibbonGeometry(realitykit::geometry_parameters params) { cosmosRibbon(params, 1.0); }

static inline void cosmosRibbonShade(realitykit::surface_parameters params,
                                     float headGlow, float base, float dashAmp,
                                     float dashFreq, float dashSpeed, float tailTaper) {
    float4 stroke = params.geometry().uv2();
    float energy = params.geometry().uv3().x;
    float3 color = params.geometry().uv4().rgb;
    float4 k = params.uniforms().custom_parameter();
    float across = stroke.x;
    float t = stroke.y;
    float seed = stroke.z;
    float s = stroke.w;

    // Soft round profile across the stroke: a bright core and a feathered edge.
    float profile = exp(-across * across * 4.5) * (1.0 - across * across);
    // Each curve's head runs a little ahead of or behind the others.
    float head = k.z * (0.8 + 0.4 * seed);
    float drawn = step(t, head);
    float tip = headGlow * exp(-max(head - t, 0.0) * 16.0);
    float pulse = 0.5 + 0.5 * sin(s * dashFreq - k.x * dashSpeed + seed * 6.2831);
    pulse = pulse * pulse;
    pulse = pulse * pulse;
    pulse = pulse * pulse;
    float body = base + tip + dashAmp * pulse;
    float taper = mix(1.0, 1.0 - smoothstep(0.55, 1.0, t), tailTaper);

    float gain = drawn * body * taper * profile * energy * k.y;
    params.surface().set_base_color(half3(color * gain));
    params.surface().set_opacity(1.0h);
}

constant float kTwoPi = 6.28318530718;

[[visible]] void cosmosBurstSurface(realitykit::surface_parameters params) {
    cosmosRibbonShade(params, 3.0, 0.75, 0.5, kTwoPi * 3.0, 7.0, 0.35);
}
[[visible]] void cosmosFlowSurface(realitykit::surface_parameters params) {
    cosmosRibbonShade(params, 0.0, 0.5, 1.3, kTwoPi * 5.0, 2.5, 0.0);
}
[[visible]] void cosmosProminenceSurface(realitykit::surface_parameters params) {
    cosmosRibbonShade(params, 0.0, 0.6, 1.6, kTwoPi * 3.5, 1.6, 0.2);
}

// MARK: - Plasma (cosmos_plasma.mat)

static inline float plasmaHash(float3 p) {
    p = fract(p * 0.3183099 + 0.1);
    p *= 17.0;
    return fract(p.x * p.y * p.z * (p.x + p.y + p.z));
}

static inline float plasmaNoise(float3 x) {
    float3 i = floor(x);
    float3 f = fract(x);
    f = f * f * (3.0 - 2.0 * f);
    return mix(
        mix(mix(plasmaHash(i + float3(0, 0, 0)), plasmaHash(i + float3(1, 0, 0)), f.x),
            mix(plasmaHash(i + float3(0, 1, 0)), plasmaHash(i + float3(1, 1, 0)), f.x), f.y),
        mix(mix(plasmaHash(i + float3(0, 0, 1)), plasmaHash(i + float3(1, 0, 1)), f.x),
            mix(plasmaHash(i + float3(0, 1, 1)), plasmaHash(i + float3(1, 1, 1)), f.x), f.y),
        f.z);
}

static inline float plasmaFbm(float3 p) {
    float sum = 0.0;
    float amplitude = 0.5;
    for (int octave = 0; octave < 5; octave++) {
        sum += amplitude * plasmaNoise(p);
        p = p * 2.03 + float3(1.7, 9.2, 3.1);
        amplitude *= 0.5;
    }
    return sum;
}

[[visible]] void cosmosPlasmaSurface(realitykit::surface_parameters params) {
    float4 k = params.uniforms().custom_parameter();
    bool nucleus = k.z > 0.5;
    // The two looks of the Android demo: STAR_PLASMA and NUCLEUS_PLASMA.
    float3 deepColor = nucleus ? float3(0.004, 0.008, 0.02) : float3(0.09, 0.42, 0.95);
    float3 hotColor = nucleus ? float3(0.03, 0.07, 0.16) : float3(0.26, 0.8, 1.7);
    float3 rimColor = nucleus ? float3(0.25, 0.6, 1.6) : float3(0.3, 0.9, 2.2);
    float rimPower = 3.0;
    float noiseScale = nucleus ? 4.0 : 9.0;
    float flow = nucleus ? 0.2 : 0.22;

    float t = k.x * flow;
    float3 p = normalize(params.geometry().model_position()) * noiseScale;

    // Domain warp: the field is looked up through a second, slowly drifting field.
    float3 warp = float3(
        plasmaFbm(p + float3(0.0, t * 0.9, 0.0)),
        plasmaFbm(p + float3(5.2, 1.3 - t * 0.7, 2.8)),
        plasmaFbm(p + float3(t * 0.6, 3.7, 8.1)));
    float n = plasmaFbm(p + 2.4 * warp + float3(0.0, 0.0, t * 0.5));

    // Filaments: the ridges of the warped field, where it crosses its mid value.
    float ridge = 1.0 - abs(2.0 * n - 1.0);
    ridge = ridge * ridge * ridge * ridge;

    float3 N = normalize(params.geometry().normal());
    float3 V = normalize(params.geometry().view_direction());
    float NoV = clamp(dot(N, V), 0.0, 1.0);
    float rim = pow(1.0 - NoV, rimPower);

    float heat = smoothstep(0.3, 0.75, n);
    float3 radiance = mix(deepColor, hotColor, heat);
    radiance += hotColor * ridge * 0.9;
    // Limb brightening: the edge of a glowing ball is the brightest part of it.
    radiance *= 0.75 + 0.5 * (1.0 - NoV);
    radiance += rimColor * rim;
    params.surface().set_base_color(half3(radiance * k.y));
}
