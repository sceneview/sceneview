// The floor and the gel see the same studio radiance in world space. The spot's
// oblique cone makes an ellipse on the floor; its penumbra has no hard boundary.
// These are pre-exposed scene colours, like the solid skybox, before PBR Neutral.
// A finite overhead strip, shared by steel reflections and gel internal reflections.
// Intersect in world space: a distant direction-only environment stays uniform across
// a flat top. This strip moves across the surface as the camera orbits, without a clock.
vec3 matterStudioStrip(highp vec3 p, vec3 ray, float spread) {
    highp vec2 hit = p.xz + ray.xz * (max(4.0 - p.y, 0.0) / max(ray.y, 0.08));
    vec2 distanceToStrip = abs(hit - vec2(-3.3, -7.0));
    // A softbox, not a hard-edged card. At a grazing angle a mirror packs metres of
    // studio into a few pixels, so any step in the source reads as a crease or a bump
    // in the surface: Gaussian across the width, a long fade along it.
    float width = 0.6 + spread;
    float strip = exp(-distanceToStrip.x * distanceToStrip.x / (width * width)) *
        (1.0 - smoothstep(2.5, 8.0, distanceToStrip.y)) * smoothstep(0.02, 0.30, ray.y);
    float room = mix(0.18, 0.38, smoothstep(-0.25, 0.85, ray.y));
    return vec3(room + 1.2 * strip);
}

vec3 matterStudioFloor(highp vec3 p, vec3 background, highp vec3 keyPosition,
        highp vec3 keyTarget, vec2 keyCone, vec2 keyPool, vec3 contact, vec2 halfSize) {
    highp vec3 fromKey = p - keyPosition;
    highp vec3 axis = keyTarget - keyPosition;
    highp float distanceSquared = max(dot(fromKey, fromKey), 0.01);
    float cone = smoothstep(keyCone.y, keyCone.x,
        dot(fromKey * inversesqrt(distanceSquared), normalize(axis)));
    float range = distanceSquared / (keyPool.y * keyPool.y);
    float attenuation = max(1.0 - range * range, 0.0);
    float pool = cone * attenuation * attenuation * dot(axis, axis) / distanceSquared;

    highp vec2 q = abs(p.xz - contact.xy) - halfSize + 0.10;
    float outside = max(length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - 0.10, 0.0);
    float shadow = (0.38 * exp(-outside * outside / 0.09) +
        0.30 * exp(-outside * outside / 0.70)) * contact.z;
    // contact.z < 0 is the gel's caustic: the same footprint brightens instead.
    return (background + vec3(keyPool.x * pool)) * (1.0 - shadow);
}
