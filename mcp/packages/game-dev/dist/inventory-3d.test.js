import { describe, it, expect } from "vitest";
import { generateInventory3D, ITEM_CATEGORIES, INVENTORY_LAYOUTS, } from "./inventory-3d.js";
describe("ITEM_CATEGORIES", () => {
    it("has at least 8 categories", () => {
        expect(ITEM_CATEGORIES.length).toBeGreaterThanOrEqual(8);
    });
    it("includes core categories", () => {
        expect(ITEM_CATEGORIES).toContain("weapon");
        expect(ITEM_CATEGORIES).toContain("armor");
        expect(ITEM_CATEGORIES).toContain("potion");
        expect(ITEM_CATEGORIES).toContain("gem");
        expect(ITEM_CATEGORIES).toContain("quest");
    });
});
describe("INVENTORY_LAYOUTS", () => {
    it("has at least 3 layouts", () => {
        expect(INVENTORY_LAYOUTS.length).toBeGreaterThanOrEqual(3);
    });
    it("includes grid and carousel", () => {
        expect(INVENTORY_LAYOUTS).toContain("grid");
        expect(INVENTORY_LAYOUTS).toContain("carousel");
        expect(INVENTORY_LAYOUTS).toContain("list");
    });
});
describe("generateInventory3D", () => {
    it("generates valid Kotlin code for grid layout", () => {
        const code = generateInventory3D({ layout: "grid" });
        expect(code).toContain("package com.example.gaming.inventory");
        expect(code).toContain("import io.github.sceneview.SceneView");
        expect(code).toContain("@Composable");
        expect(code).toContain("rememberEngine()");
        expect(code).toContain("rememberModelInstance");
        expect(code).toContain("ModelNode");
    });
    it("includes item data model", () => {
        const code = generateInventory3D({ layout: "grid" });
        expect(code).toContain("GameItem");
        expect(code).toContain("getSampleItems");
        expect(code).toContain("category");
        expect(code).toContain("rarity");
    });
    it("includes 3D preview with ModelNode", () => {
        const code = generateInventory3D({ layout: "grid" });
        expect(code).toContain("ModelNode(");
        expect(code).toContain("scaleToUnits");
        expect(code).toContain("models/items/");
    });
    it("includes category filter tabs", () => {
        const code = generateInventory3D({ layout: "grid" });
        expect(code).toContain("categoryFilter");
        expect(code).toContain("Tab(");
        expect(code).toContain("All");
    });
    it("includes auto-rotation when autoRotate=true", () => {
        const code = generateInventory3D({ layout: "grid", autoRotate: true });
        expect(code).toContain("previewRotation");
        expect(code).toContain("Rotation");
    });
    it("excludes auto-rotation when autoRotate=false", () => {
        const code = generateInventory3D({ layout: "grid", autoRotate: false });
        expect(code).not.toContain("previewRotation");
    });
    it("includes stats when showStats=true", () => {
        const code = generateInventory3D({ layout: "grid", showStats: true });
        expect(code).toContain("stats");
        expect(code).toContain("Attack");
        expect(code).toContain("Defense");
    });
    it("excludes stats panel when showStats=false", () => {
        const code = generateInventory3D({ layout: "grid", showStats: false });
        expect(code).not.toContain("item.stats.isNotEmpty");
    });
    it("uses custom column count", () => {
        const code = generateInventory3D({ layout: "grid", columns: 6 });
        expect(code).toContain("GridCells.Fixed(6)");
    });
    it("generates AR code when ar=true", () => {
        const code = generateInventory3D({ layout: "grid", ar: true });
        expect(code).toContain("ARSceneView(");
        expect(code).toContain("android.permission.CAMERA");
        expect(code).toContain("arsceneview:3.6.0");
    });
    it("includes LightNode with named apply parameter", () => {
        const code = generateInventory3D({ layout: "grid" });
        expect(code).toContain("LightNode(");
        expect(code).toContain("apply = {");
        expect(code).toContain("intensity(");
    });
    it("includes loading indicator", () => {
        const code = generateInventory3D({ layout: "grid" });
        expect(code).toContain("CircularProgressIndicator");
    });
    it("handles null modelInstance", () => {
        const code = generateInventory3D({ layout: "grid" });
        expect(code).toContain("modelInstance?.let");
    });
    it("includes item selection state", () => {
        const code = generateInventory3D({ layout: "grid" });
        expect(code).toContain("selectedItem");
        expect(code).toContain("isSelected");
    });
    it("generates code for every layout", () => {
        for (const layout of INVENTORY_LAYOUTS) {
            const code = generateInventory3D({ layout });
            expect(code).toContain("@Composable");
        }
    });
    it("includes item quantity display", () => {
        const code = generateInventory3D({ layout: "grid" });
        expect(code).toContain("quantity");
    });
    it("sample items cover multiple categories", () => {
        const code = generateInventory3D({ layout: "grid" });
        expect(code).toContain('"weapon"');
        expect(code).toContain('"armor"');
        expect(code).toContain('"potion"');
    });
});
