import { describe, it, expect } from "vitest";
import { generateMaterialSwitcher, MATERIAL_SURFACES, PAINT_FINISHES, } from "./material-switcher.js";
describe("MATERIAL_SURFACES", () => {
    it("has at least 8 surfaces", () => {
        expect(MATERIAL_SURFACES.length).toBeGreaterThanOrEqual(8);
    });
    it("includes core surfaces", () => {
        expect(MATERIAL_SURFACES).toContain("wall-paint");
        expect(MATERIAL_SURFACES).toContain("floor");
        expect(MATERIAL_SURFACES).toContain("fabric");
        expect(MATERIAL_SURFACES).toContain("countertop");
    });
});
describe("PAINT_FINISHES", () => {
    it("has at least 4 finishes", () => {
        expect(PAINT_FINISHES.length).toBeGreaterThanOrEqual(4);
    });
    it("includes matte and gloss", () => {
        expect(PAINT_FINISHES).toContain("matte");
        expect(PAINT_FINISHES).toContain("gloss");
        expect(PAINT_FINISHES).toContain("satin");
    });
});
describe("generateMaterialSwitcher", () => {
    it("generates valid Kotlin code for wall-paint", () => {
        const code = generateMaterialSwitcher({ surface: "wall-paint" });
        expect(code).toContain("package com.example.interior.materials");
        expect(code).toContain("import io.github.sceneview.SceneView");
        expect(code).toContain("@Composable");
        expect(code).toContain("rememberEngine()");
    });
    it("includes color palette", () => {
        const code = generateMaterialSwitcher({ surface: "wall-paint" });
        expect(code).toContain("ColorPalette");
        expect(code).toContain("selectedColorIndex");
    });
    it("includes finish selector", () => {
        const code = generateMaterialSwitcher({ surface: "floor" });
        expect(code).toContain("FinishSelector");
        expect(code).toContain("selectedFinish");
    });
    it("uses custom colors when provided", () => {
        const code = generateMaterialSwitcher({
            surface: "wall-paint",
            colors: ["#FF0000", "#00FF00"],
        });
        expect(code).toContain("#FF0000");
        expect(code).toContain("#00FF00");
    });
    it("includes before/after slider when enabled", () => {
        const code = generateMaterialSwitcher({
            surface: "floor",
            beforeAfter: true,
        });
        expect(code).toContain("Before / After");
        expect(code).toContain("splitPosition");
    });
    it("excludes before/after by default", () => {
        const code = generateMaterialSwitcher({ surface: "floor" });
        expect(code).not.toContain("splitPosition");
    });
    it("generates AR code when ar=true", () => {
        const code = generateMaterialSwitcher({ surface: "wall-paint", ar: true });
        expect(code).toContain("ARSceneView(");
        expect(code).toContain("import io.github.sceneview.ar.ARSceneView");
        expect(code).toContain("android.permission.CAMERA");
    });
    it("includes LightNode with named apply parameter", () => {
        const code = generateMaterialSwitcher({ surface: "countertop" });
        expect(code).toContain("LightNode(");
        expect(code).toContain("apply = {");
        expect(code).toContain("intensity(");
    });
    it("handles loading state", () => {
        const code = generateMaterialSwitcher({ surface: "fabric" });
        expect(code).toContain("CircularProgressIndicator");
        expect(code).toContain("modelInstance == null");
    });
    it("generates code for every surface type", () => {
        for (const surface of MATERIAL_SURFACES) {
            const code = generateMaterialSwitcher({ surface });
            expect(code).toContain("@Composable");
        }
    });
    it("generates correct model path for surface", () => {
        const code = generateMaterialSwitcher({ surface: "wall-paint" });
        expect(code).toContain("models/room/wall_paint_scene.glb");
    });
    it("uses default colors for each surface", () => {
        const code = generateMaterialSwitcher({ surface: "countertop" });
        expect(code).toContain("#2F4F4F");
    });
});
