// Shared by all six Matter materials. Keep the deformation and its analytic normal together.
// A field and its analytic XZ gradient, evaluated ONCE per vertex (including the trail).
// Components: displacement, d/dx, d/dz, crushed pile. The supported bottom stays fixed.
#ifndef MATTER_RIGID
highp vec4 dentField(highp vec2 p) {
    vec2 q = (p - materialParams.finger) / materialParams.radius;
    float r = max(length(q), 0.0001);
    vec2 radial = q / r / materialParams.radius;
    float power = pow(r, materialParams.profile.x);
    float bowl = exp(-2.0 * power);
    float db = -2.0 * materialParams.profile.x * power / r * bowl;
    float gateT = clamp((r - 0.20) / 0.35, 0.0, 1.0);
    float gate = gateT * gateT * (3.0 - 2.0 * gateT);
    float dg = 6.0 * gateT * (1.0 - gateT) / 0.35;
    float rimQ = (r - materialParams.rimShape.x) / materialParams.rimShape.y;
    float rim = exp(-rimQ * rimQ);
    float dr = rim * (dg - gate * 2.0 * rimQ / materialParams.rimShape.y);
    float positiveDepth = max(materialParams.depth, 0.0);
    float field = -materialParams.depth * bowl + positiveDepth * materialParams.profile.y * rim * gate;
    vec2 gradient = (-materialParams.depth * db + positiveDepth * materialParams.profile.y * dr) * radial;
    #ifdef MATTER_FOLDS
    if (materialParams.profile.z > 0.0 && positiveDepth > 0.0001) {
        float angle = r > 0.0001 ? atan(q.y, q.x) : 0.0;
        float phase = angle * materialParams.profile.w + r * 0.35;
        float fold = cos(phase), slope = -sin(phase);
        // Seven narrow leather creases; eighteen rounded velvet folds.
        if (materialParams.profile.w < 10.0) {
            slope *= 3.0 * fold * fold;
            fold = fold * fold * fold;
        }
        float envelopeQ = (r - 0.9) / 0.55;
        float envelope = exp(-envelopeQ * envelopeQ);
        float de = envelope * (dg - gate * 2.0 * envelopeQ / 0.55);
        float amplitude = positiveDepth * materialParams.profile.z;
        vec2 angular = vec2(-q.y, q.x) / (r * r * materialParams.radius);
        field += amplitude * envelope * gate * fold;
        gradient += amplitude * (de * fold * radial + envelope * gate * slope *
            (materialParams.profile.w * angular + 0.35 * radial));
    }
    #endif
    float pile = 0.0;
    #ifdef MATTER_PILE_TRAIL
    // Velvet only changes nap, so it needs neither the groove gradient nor the foam branch.
    // Gaussian tails beyond sqrt(9) widths contribute < 0.001 in total for all eight slots.
    // Evaluate at vertices and interpolate bentNormal.w; never repeat this per fragment.
    if (materialParams.trailMode > 0.5) {
        mediump float memory = 0.0;
        for (int i = 0; i < 8; i++) {
            highp vec4 samplePoint = materialParams.trail[i];
            if (samplePoint.z <= 0.0) continue;
            highp vec2 delta = p - samplePoint.xy;
            highp float distanceSquared = dot(delta, delta) * samplePoint.w;
            if (distanceSquared >= 9.0) continue;
            memory += samplePoint.z * exp(-distanceSquared);
        }
        pile = 1.0 - exp(-memory);
    }
    #endif
    #ifdef MATTER_TRAIL
    if (materialParams.trailMode > 0.5) {
        float memory = 0.0;
        vec2 memoryGradient = vec2(0.0);
        for (int i = 0; i < 8; i++) {
            vec4 samplePoint = materialParams.trail[i];
            if (samplePoint.z <= 0.0) continue;
            vec2 delta = p - samplePoint.xy;
            float cell = samplePoint.z * exp(-dot(delta, delta) * samplePoint.w);
            memory += cell;
            memoryGradient -= 2.0 * delta * samplePoint.w * cell;
        }
        float remaining = exp(-memory);
        pile = 1.0 - remaining;
        if (materialParams.trailMode < 1.5) {
            // Smooth bounded union: grooves cannot stack up or deepen the main well.
            float groove = 0.22 * pile;
            field -= groove * (1.0 - bowl);
            gradient += groove * db * radial - 0.22 * remaining * memoryGradient * (1.0 - bowl);
            pile = 0.0;
        }
    }
    #endif
    #ifdef MATTER_RELEASE_WAVE
    if (materialParams.releaseWave.w > 0.00001) {
        vec2 delta = p - materialParams.releaseWave.xy;
        float distance = max(length(delta), 0.0001);
        float offset = distance - materialParams.releaseWave.z * 1.35;
        float envelope = exp(-offset * offset / 0.16);
        float gateT = clamp(distance / 0.25, 0.0, 1.0);
        float gate = gateT * gateT * (3.0 - 2.0 * gateT);
        float dg = 6.0 * gateT * (1.0 - gateT) / 0.25;
        float wave = sin(offset * 10.0);
        float amplitude = materialParams.releaseWave.w;
        field += amplitude * envelope * gate * wave;
        gradient += amplitude * envelope * ((dg - gate * offset * 12.5) * wave +
            gate * 10.0 * cos(offset * 10.0)) * delta / distance;
    }
    #endif
    return vec4(field, gradient, pile);
}
#endif

void materialVertex(inout MaterialVertexInputs material) {
    highp vec3 p = getPosition().xyz;
    vec3 halfSize = vec3(materialParams.dimensions.x, materialParams.dimensions.y * 0.5,
        materialParams.dimensions.z);
    vec3 centred = p + vec3(0.0, halfSize.y, 0.0);
    vec3 core = clamp(centred, -halfSize + materialParams.dimensions.w,
        halfSize - materialParams.dimensions.w);
    vec3 n = normalize(centred - core);
    vec3 axis = abs(n.x) < 0.9 ? vec3(1.0, 0.0, 0.0) : vec3(0.0, 0.0, 1.0);
    vec3 t = normalize(axis - n * dot(axis, n));
    float weight = clamp(1.0 + p.y / materialParams.dimensions.y, 0.0, 1.0);
    vec4 deformation = vec4(0.0);
    #ifndef MATTER_RIGID
    // The supported bottom has zero displacement and is never seen from above.
    if (weight > 0.0 && (abs(materialParams.depth) > 0.000001 || materialParams.trailMode > 0.5 ||
        materialParams.releaseWave.w > 0.00001 || abs(materialParams.squash) > 0.000001)) {
        deformation = dentField(p.xz);
        deformation.x -= materialParams.squash;
        float gx = deformation.y * weight, gz = deformation.z * weight;
        float gy = max(0.15, 1.0 + deformation.x / materialParams.dimensions.y);
        // Inverse-transpose Jacobian for N, Jacobian for the brushing tangent.
        n = normalize(vec3(n.x - gx * n.y / gy, n.y / gy, n.z - gz * n.y / gy));
        t = normalize(vec3(t.x, gx * t.x + gy * t.y + gz * t.z, t.z));
    }
    #endif
    material.worldPosition.xyz += mat3(getWorldFromModelMatrix()) *
        vec3(0.0, weight * deformation.x + materialParams.bounce, 0.0);
    material.rest = vec4(p, clamp(-deformation.x / materialParams.dimensions.y, 0.0, 1.0));
    material.bentNormal = vec4(normalize(getWorldFromModelNormalMatrix() * n), deformation.w * weight);
    material.bentTangent = vec4(normalize(mat3(getWorldFromModelMatrix()) * t), 0.0);
    // Filament's original TBN maps the deformed world-space frame back in the fragment.
}
