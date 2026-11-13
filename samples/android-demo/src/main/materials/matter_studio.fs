// The floor and the gel see the same studio radiance in world space. The spot's
// oblique cone makes an ellipse on the floor; its penumbra has no hard boundary.
// These are pre-exposed scene colours, like the solid skybox, before PBR Neutral.
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
    return (background + vec3(keyPool.x * pool)) * (1.0 - shadow);
}
