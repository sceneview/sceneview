import { describe, it, expect } from "vitest";
import { generateLightingDesign, LIGHT_TYPES, COLOR_TEMPERATURES, } from "./lighting-design.js";
describe("LIGHT_TYPES", () => {
    it("has at least 10 light types", () => {
        expect(LIGHT_TYPES.length).toBeGreaterThanOrEqual(10);
    });
    it("includes core light types", () => {
        expect(LIGHT_TYPES).toContain("ambient");
        expect(LIGHT_TYPES).toContain("spot");
        expect(LIGHT_TYPES).toContain("accent");
        expect(LIGHT_TYPES).toContain("pendant");
        expect(LIGHT_TYPES).toContain("recessed");
        expect(LIGHT_TYPES).toContain("natural");
    });
});
describe("COLOR_TEMPERATURES", () => {
    it("has at least 4 color temperatures", () => {
        expect(COLOR_TEMPERATURES.length).toBeGreaterThanOrEqual(4);
    });
    it("includes warm-white and daylight", () => {
        expect(COLOR_TEMPERATURES).toContain("warm-white");
        expect(COLOR_TEMPERATURES).toContain("daylight");
        expect(COLOR_TEMPERATURES).toContain("candlelight");
    });
});
describe("generateLightingDesign", () => {
    it("generates valid Kotlin code for ambient + spot", () => {
        const code = generateLightingDesign({ lights: ["ambient", "spot"] });
        expect(code).toContain("package com.example.interior.lighting");
        expect(code).toContain("import io.github.sceneview.SceneView");
        expect(code).toContain("@Composable");
        expect(code).toContain("rememberEngine()");
    });
    it("generates LightNode for each light type", () => {
        const code = generateLightingDesign({
            lights: ["ambient", "spot", "accent"],
        });
        expect(code).toContain("Ambient light");
        expect(code).toContain("Spot light");
        expect(code).toContain("Accent light");
    });
    it("uses LightNode with named apply parameter", () => {
        const code = generateLightingDesign({ lights: ["pendant"] });
        expect(code).toContain("LightNode(");
        expect(code).toContain("apply = {");
        expect(code).toContain("intensity(");
    });
    it("includes dimmer sliders when dimmable=true", () => {
        const code = generateLightingDesign({
            lights: ["ambient", "spot"],
            dimmable: true,
        });
        expect(code).toContain("Slider");
        expect(code).toContain("light0Intensity");
        expect(code).toContain("light1Intensity");
    });
    it("excludes dimmer sliders when dimmable=false", () => {
        const code = generateLightingDesign({
            lights: ["ambient"],
            dimmable: false,
        });
        expect(code).not.toContain("light0Intensity");
    });
    it("uses warm-white color by default", () => {
        const code = generateLightingDesign({ lights: ["ambient"] });
        expect(code).toContain("warm-white");
        expect(code).toContain("2700K");
    });
    it("uses candlelight color when specified", () => {
        const code = generateLightingDesign({
            lights: ["table-lamp"],
            colorTemperature: "candlelight",
        });
        expect(code).toContain("candlelight");
        expect(code).toContain("1900K");
    });
    it("uses daylight color when specified", () => {
        const code = generateLightingDesign({
            lights: ["natural"],
            colorTemperature: "daylight",
        });
        expect(code).toContain("daylight");
        expect(code).toContain("6500K");
    });
    it("generates AR code when ar=true", () => {
        const code = generateLightingDesign({
            lights: ["ambient", "spot"],
            ar: true,
        });
        expect(code).toContain("ARSceneView(");
        expect(code).toContain("import io.github.sceneview.ar.ARSceneView");
        expect(code).toContain("android.permission.CAMERA");
    });
    it("handles loading state", () => {
        const code = generateLightingDesign({ lights: ["pendant"] });
        expect(code).toContain("CircularProgressIndicator");
        expect(code).toContain("modelInstance == null");
    });
    it("uses custom room model path", () => {
        const code = generateLightingDesign({
            lights: ["ambient"],
            roomModel: "models/my_room.glb",
        });
        expect(code).toContain("models/my_room.glb");
    });
    it("generates code for every light type individually", () => {
        for (const light of LIGHT_TYPES) {
            const code = generateLightingDesign({ lights: [light] });
            expect(code).toContain("@Composable");
            expect(code).toContain("LightNode");
        }
    });
});
