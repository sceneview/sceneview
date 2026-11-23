// view_tone_map_inverse.fs — the exact inverse of what the View does to a colour after the
// material: its tone mapper, then the sRGB OETF. Shared by every material that has to display a
// colour unchanged (#4595, #5102, #5219), through matc's `#include`, relative to the .mat:
//
//   sceneview/src/main/materials/   video_texture.mat, video_texture_chroma_key.mat,
//                                   view_texture_lit.mat, view_texture_unlit.mat,
//                                   semantics_overlay.mat, splat.mat (vertex stage)
//   arsceneview/src/main/materials/ camera_stream_flat.mat, camera_stream_depth.mat,
//                                   camera_stream_person_occlusion.mat
//
// matc resolves the include when it compiles the blob, so editing this file changes all nine
// blobs: regenerate them with tools/GenerateFilamat.sh. ToneMapInverseContractTest holds the
// colour model, and checks every material includes this file instead of a copy.
//
// Plain GLSL with no Filament built-in, so it compiles in a vertex block as well (splat.mat
// pre-distorts one colour per splat there).

// Exact sRGB EOTF (IEC 61966-2-1). The View's output stage is the exact OETF; Filament's
// inverseTonemapSRGB() decodes with pow(c, 2.2), which left an S-curve on the round trip (#3338).
vec3 viewInverseSrgbToLinear(vec3 srgb) {
    vec3 s = clamp(srgb, 0.0, 1.0);
    return mix(s * (1.0 / 12.92), pow((s + 0.055) * (1.0 / 1.055), vec3(2.4)),
            step(vec3(0.04045), s));
}

// Filament's ColorGrading tone-maps in its Rec.2020 working space, not in Rec.709: the View
// converts the scene colour to Rec.2020, applies the tone mapper there and converts back.
// Inverted channel by channel in Rec.709, the curve is exact for greys only: a #66B0F5 sky came
// out #93B2FA under Filmic (#5102, #5219). Column-major, the constants of Filament's
// sRGB_to_REC2020 / REC2020_to_sRGB.
const mat3 VIEW_INVERSE_REC709_TO_REC2020 = mat3(
    0.6274040, 0.0690970, 0.0163916,
    0.3292820, 0.9195400, 0.0880132,
    0.0433136, 0.0113612, 0.8955950
);
const mat3 VIEW_INVERSE_REC2020_TO_REC709 = mat3(
     1.6604910, -0.1245505, -0.0181508,
    -0.5876411,  1.1328999, -0.1005789,
    -0.0728499, -0.0083494,  1.1187297
);

// Exact analytic inverse of ToneMapper.Filmic (the Narkowicz ACES fit), per channel: Filament's
// Inverse_Tonemap_Filmic, spelled out so the vertex stage has it.
vec3 viewInverseFilmic(vec3 x) {
    return (0.03 - 0.59 * x - sqrt(0.0009 + 1.3702 * x - 1.0127 * x * x)) / (-5.02 + 4.86 * x);
}

// ---- Inverse of ToneMapper.PBRNeutralToneMapper (Khronos PBR Neutral, #4595) ----

// The forward curve's highlight compression at scene peak p: (new peak, desaturation).
vec2 pbrNeutralCompression(float p) {
    float np = 1.0 - 0.0576 / (p - 0.52);
    return vec2(np, 1.0 - 1.0 / (0.15 * (p - np) + 1.0));
}

// Undoes the toe offset the forward curve subtracts from every channel.
vec3 inversePbrNeutralToe(vec3 c) {
    float m = min(c.r, min(c.g, c.b));
    float offset = m < 0.04 ? sqrt(max(m, 0.0) / 6.25) - m : 0.04;
    return c + offset;
}

// Display-linear colour -> scene-linear colour that PBR Neutral maps back onto it.
// Exact for every colour the curve can produce. The peak is capped at 0.99143 (252.8/255),
// whose inverse is 7.24, the value Filmic's inverse gives white: PBR Neutral's inverse is
// unbounded as the peak nears 1. A bright saturated colour (#FF0000) has no preimage, since
// the curve desaturates highlights: the scene peak is then bisected so the peak shortfall
// equals the unavoidable desaturation lift in display (gamma) space, red -> (235, 13, 13)
// instead of a pink (255, 212, 212).
vec3 inversePbrNeutral(vec3 o) {
    float peak = max(o.r, max(o.g, o.b));
    if (peak < 0.76) {
        return inversePbrNeutralToe(o);
    }
    float np = min(peak, 0.99143);
    float p = 0.0576 / (1.0 - np) + 0.52;
    vec2 ng = pbrNeutralCompression(p);
    vec3 c = (o - ng.x * ng.y) / (1.0 - ng.y) * (p / ng.x);
    if (min(c.r, min(c.g, c.b)) >= -1e-4) {
        return inversePbrNeutralToe(c);
    }
    float omin = max(min(o.r, min(o.g, o.b)), 0.0);
    float encPeak = pow(peak, 1.0 / 2.2);
    float encMin = pow(omin, 1.0 / 2.2);
    float lo = 0.76;
    float hi = p;
    for (int i = 0; i < 12; i++) {
        float mid = 0.5 * (lo + hi);
        ng = pbrNeutralCompression(mid);
        if (encPeak - pow(ng.x, 1.0 / 2.2) > pow(omin + ng.x * ng.y, 1.0 / 2.2) - encMin) {
            lo = mid;
        } else {
            hi = mid;
        }
    }
    p = 0.5 * (lo + hi);
    ng = pbrNeutralCompression(p);
    vec3 ratio = clamp((o - ng.x * ng.y) / (ng.x * (1.0 - ng.y)), 0.0, 1.0);
    return inversePbrNeutralToe(ratio * p);
}

// sRGB-encoded colour -> scene-linear Rec.709 colour that the View displays as that colour.
// `filmic`: the View tone-maps with ToneMapper.Filmic (createARView), otherwise PBR Neutral
// (createView, the default).
//
// Exact for in-gamut colours. A vivid colour whose preimage needs a negative Rec.709 channel
// cannot be shown as it is: the colour buffer holds no negative value.
//  - Filmic: that channel is clipped at zero. A strong blue's or a cyan's red then lands a few
//    steps above its source, and the most vivid cyans and azures further: #00FFFF shows about
//    (223, 249, 254). Never further than the per-channel Rec.709 inverse it replaced.
//  - PBR Neutral: clipping alone did worse than the per-channel Rec.709 inverse there, which
//    falls back on #4595's bisection for those colours (#00FFFF showed (150, 254, 254) instead
//    of (0, 233, 233)). So the result blends toward that inverse, fully once the negative channel
//    reaches a tenth of the peak: in-gamut colours are untouched, vivid ones match it.
vec3 inverseViewTonemapSRGB(vec3 srgb, bool filmic) {
    vec3 linear = viewInverseSrgbToLinear(srgb);
    vec3 working = VIEW_INVERSE_REC709_TO_REC2020 * linear;
    if (filmic) {
        return max(VIEW_INVERSE_REC2020_TO_REC709 * viewInverseFilmic(working), vec3(0.0));
    }
    vec3 scene = VIEW_INVERSE_REC2020_TO_REC709 * inversePbrNeutral(working);
    float lowest = min(scene.r, min(scene.g, scene.b));
    if (lowest >= 0.0) {
        return scene;
    }
    float highest = max(scene.r, max(scene.g, scene.b));
    float toward709 = clamp(-lowest / (0.1 * max(highest, 1e-4)), 0.0, 1.0);
    return mix(max(scene, vec3(0.0)), inversePbrNeutral(linear), toward709);
}
