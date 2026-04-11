import { describe, it, expect } from "vitest";
import { FURNITURE_MODELS, FURNITURE_MODEL_IDS, listFurnitureModels, getFurnitureModel, formatFurnitureModelList, } from "./furniture-models.js";
describe("FURNITURE_MODELS database", () => {
    it("has at least 20 models", () => {
        expect(FURNITURE_MODEL_IDS.length).toBeGreaterThanOrEqual(20);
    });
    it("FURNITURE_MODEL_IDS matches FURNITURE_MODELS keys", () => {
        expect(FURNITURE_MODEL_IDS.sort()).toEqual(Object.keys(FURNITURE_MODELS).sort());
    });
    it("has at least 6 categories represented", () => {
        const categories = new Set(Object.values(FURNITURE_MODELS).map((m) => m.category));
        expect(categories.size).toBeGreaterThanOrEqual(6);
    });
    it("every model has all required fields", () => {
        for (const id of FURNITURE_MODEL_IDS) {
            const model = FURNITURE_MODELS[id];
            expect(model.id).toBe(id);
            expect(model.name).toBeTruthy();
            expect(model.category).toBeTruthy();
            expect(model.source).toBeTruthy();
            expect(model.sourceUrl).toBeTruthy();
            expect(model.format).toBeTruthy();
            expect(model.license).toBeTruthy();
            expect(model.description).toBeTruthy();
            expect(model.tags.length).toBeGreaterThan(0);
        }
    });
    it("every model has a valid source URL", () => {
        for (const model of Object.values(FURNITURE_MODELS)) {
            expect(model.sourceUrl).toMatch(/^https?:\/\//);
        }
    });
    it("every model description is at least 20 chars", () => {
        for (const model of Object.values(FURNITURE_MODELS)) {
            expect(model.description.length).toBeGreaterThanOrEqual(20);
        }
    });
    it("has Poly Haven models", () => {
        const ph = Object.values(FURNITURE_MODELS).filter((m) => m.source.includes("Poly Haven"));
        expect(ph.length).toBeGreaterThanOrEqual(5);
    });
    it("has Khronos models", () => {
        const khr = Object.values(FURNITURE_MODELS).filter((m) => m.source.includes("Khronos"));
        expect(khr.length).toBeGreaterThanOrEqual(3);
    });
    it("has Sketchfab models", () => {
        const sf = Object.values(FURNITURE_MODELS).filter((m) => m.source.includes("Sketchfab"));
        expect(sf.length).toBeGreaterThanOrEqual(4);
    });
    it("all models have CC0, CC BY, or Apache license", () => {
        for (const model of Object.values(FURNITURE_MODELS)) {
            expect(model.license).toMatch(/CC0|CC BY|Apache/);
        }
    });
});
describe("getFurnitureModel", () => {
    it("returns model by valid ID", () => {
        const model = getFurnitureModel("ph-modern-sofa");
        expect(model).toBeDefined();
        expect(model.name).toBe("Modern Sofa");
    });
    it("returns undefined for unknown ID", () => {
        expect(getFurnitureModel("nonexistent")).toBeUndefined();
    });
});
describe("listFurnitureModels", () => {
    it("returns all models when no filter", () => {
        const models = listFurnitureModels();
        expect(models.length).toBe(FURNITURE_MODEL_IDS.length);
    });
    it("filters by category", () => {
        const seating = listFurnitureModels("seating");
        expect(seating.length).toBeGreaterThan(0);
        for (const m of seating) {
            expect(m.category).toBe("seating");
        }
    });
    it("filters by tag", () => {
        const modern = listFurnitureModels(undefined, "modern");
        expect(modern.length).toBeGreaterThan(0);
        for (const m of modern) {
            expect(m.tags).toContain("modern");
        }
    });
    it("filters by both category and tag", () => {
        const lightingLamp = listFurnitureModels("lighting", "lamp");
        expect(lightingLamp.length).toBeGreaterThan(0);
        for (const m of lightingLamp) {
            expect(m.category).toBe("lighting");
            expect(m.tags).toContain("lamp");
        }
    });
    it("returns empty array for non-matching filter", () => {
        const result = listFurnitureModels("seating", "nonexistent-tag");
        expect(result).toEqual([]);
    });
});
describe("formatFurnitureModelList", () => {
    it("formats model list with header and count", () => {
        const models = listFurnitureModels("seating");
        const text = formatFurnitureModelList(models);
        expect(text).toContain("Free Furniture 3D Models");
        expect(text).toContain(`(${models.length})`);
    });
    it("includes model details in output", () => {
        const models = [getFurnitureModel("ph-modern-sofa")];
        const text = formatFurnitureModelList(models);
        expect(text).toContain("Modern Sofa");
        expect(text).toContain("Poly Haven");
        expect(text).toContain("CC0");
    });
    it("returns 'no models found' for empty list", () => {
        const text = formatFurnitureModelList([]);
        expect(text).toContain("No furniture models found");
    });
    it("includes source URLs as links", () => {
        const models = listFurnitureModels("lighting");
        const text = formatFurnitureModelList(models);
        expect(text).toContain("https://");
    });
});
