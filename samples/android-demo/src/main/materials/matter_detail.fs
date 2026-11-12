// Static channels are baked in MatterDetailAtlas; only contact response stays procedural.
vec3 matterBump(vec3 n, float height) {
    highp vec3 dx = dFdx(getWorldPosition()), dy = dFdy(getWorldPosition());
    highp vec3 r1 = cross(dy, n), r2 = cross(n, dx);
    highp float det = dot(dx, r1);
    if (abs(det) > 0.000000000001) {
        highp vec3 gradient = (dFdx(height) * r1 + dFdy(height) * r2) / det;
        return normalize(n - gradient);
    }
    return n;
}
vec4 matterRipple(highp vec3 p) {
    // Uniform early-out: resting and soft samples do no ring work.
    if (materialParams.ripple.w <= 0.0) return vec4(0.0);
    float distanceToRing = abs(length(p.xz - materialParams.ripple.xy) - materialParams.ripple.z);
    float aa = max(fwidth(distanceToRing), 0.0001);
    float ring = (1.0 - smoothstep(materialParams.rippleWidth * 0.5,
        materialParams.rippleWidth * 0.5 + aa, distanceToRing)) * materialParams.ripple.w;
    ring *= smoothstep(-materialParams.dimensions.w, -0.005, p.y);
    return vec4(materialParams.rippleColor * ring, ring);
}
