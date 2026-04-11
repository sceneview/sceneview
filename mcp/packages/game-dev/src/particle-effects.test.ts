import { describe, it, expect } from "vitest";
import {
  generateParticleEffects,
  getEffectConfig,
  PARTICLE_EFFECTS,
  BLEND_MODES,
} from "./particle-effects.js";

describe("PARTICLE_EFFECTS", () => {
  it("has at least 8 effects", () => {
    expect(PARTICLE_EFFECTS.length).toBeGreaterThanOrEqual(8);
  });

  it("includes core effects", () => {
    expect(PARTICLE_EFFECTS).toContain("fire");
    expect(PARTICLE_EFFECTS).toContain("smoke");
    expect(PARTICLE_EFFECTS).toContain("sparkles");
    expect(PARTICLE_EFFECTS).toContain("rain");
    expect(PARTICLE_EFFECTS).toContain("explosion");
  });
});

describe("BLEND_MODES", () => {
  it("has at least 3 modes", () => {
    expect(BLEND_MODES.length).toBeGreaterThanOrEqual(3);
  });

  it("includes additive and alpha", () => {
    expect(BLEND_MODES).toContain("additive");
    expect(BLEND_MODES).toContain("alpha");
  });
});

describe("getEffectConfig", () => {
  it("returns config for every effect", () => {
    for (const effect of PARTICLE_EFFECTS) {
      const config = getEffectConfig(effect);
      expect(config.description).toBeTruthy();
      expect(config.emitterShape).toBeTruthy();
      expect(config.velocityRange).toBeTruthy();
      expect(config.lifetime).toBeTruthy();
      expect(config.colorStart).toBeTruthy();
      expect(config.colorEnd).toBeTruthy();
      expect(config.sizeStart).toBeGreaterThan(0);
    }
  });

  it("fire has additive blend", () => {
    const config = getEffectConfig("fire");
    expect(config.defaultBlend).toBe("additive");
  });

  it("smoke has alpha blend", () => {
    const config = getEffectConfig("smoke");
    expect(config.defaultBlend).toBe("alpha");
  });

  it("rain has downward gravity", () => {
    const config = getEffectConfig("rain");
    expect(config.gravity).toBeGreaterThan(0);
  });

  it("fire has negative gravity (rises)", () => {
    const config = getEffectConfig("fire");
    expect(config.gravity).toBeLessThan(0);
  });

  it("sparkles have zero gravity", () => {
    const config = getEffectConfig("sparkles");
    expect(config.gravity).toBe(0);
  });
});

describe("generateParticleEffects", () => {
  it("generates valid Kotlin code for fire effect", () => {
    const code = generateParticleEffects({ effect: "fire" });
    expect(code).toContain("package com.example.gaming.particles");
    expect(code).toContain("import io.github.sceneview.SceneView");
    expect(code).toContain("@Composable");
    expect(code).toContain("rememberEngine()");
    expect(code).toContain("SphereNode");
  });

  it("includes particle system with Particle data class", () => {
    const code = generateParticleEffects({ effect: "fire" });
    expect(code).toContain("Particle");
    expect(code).toContain("createParticles");
    expect(code).toContain("updateParticles");
  });

  it("uses custom particle count", () => {
    const code = generateParticleEffects({ effect: "sparkles", particleCount: 200 });
    expect(code).toContain("200");
  });

  it("includes play/pause controls", () => {
    const code = generateParticleEffects({ effect: "smoke" });
    expect(code).toContain("isPlaying");
    expect(code).toContain("Pause");
    expect(code).toContain("Play");
    expect(code).toContain("Restart");
  });

  it("generates AR code when ar=true", () => {
    const code = generateParticleEffects({ effect: "fire", ar: true });
    expect(code).toContain("ARSceneView(");
    expect(code).toContain("android.permission.CAMERA");
    expect(code).toContain("arsceneview:3.6.0");
  });

  it("includes LightNode with named apply parameter", () => {
    const code = generateParticleEffects({ effect: "fire" });
    expect(code).toContain("LightNode(");
    expect(code).toContain("apply = {");
    expect(code).toContain("intensity(");
  });

  it("handles loop=true with respawn", () => {
    const code = generateParticleEffects({ effect: "fire", loop: true });
    expect(code).toContain("Respawn");
    expect(code).toContain("loop");
  });

  it("generates code for every effect", () => {
    for (const effect of PARTICLE_EFFECTS) {
      const code = generateParticleEffects({ effect });
      expect(code).toContain("@Composable");
      expect(code).toContain("SceneView(");
    }
  });

  it("includes lifetime and size interpolation", () => {
    const code = generateParticleEffects({ effect: "smoke" });
    expect(code).toContain("life");
    expect(code).toContain("size");
    expect(code).toContain("maxLife");
  });

  it("displays active particle count", () => {
    const code = generateParticleEffects({ effect: "fire" });
    expect(code).toContain("Active:");
  });

  it("AR version includes placement flow", () => {
    const code = generateParticleEffects({ effect: "sparkles", ar: true });
    expect(code).toContain("placed");
    expect(code).toContain("Tap");
  });
});
