import { describe, it, expect } from "vitest";
import { GAME_MODELS, GAME_MODEL_IDS, listGameModels, getGameModel, formatModelList, } from "./game-models.js";
describe("GAME_MODELS database", () => {
    it("has at least 20 models", () => {
        expect(GAME_MODEL_IDS.length).toBeGreaterThanOrEqual(20);
    });
    it("GAME_MODEL_IDS matches GAME_MODELS keys", () => {
        expect(GAME_MODEL_IDS.sort()).toEqual(Object.keys(GAME_MODELS).sort());
    });
    it("has at least 6 categories represented", () => {
        const categories = new Set(Object.values(GAME_MODELS).map((m) => m.category));
        expect(categories.size).toBeGreaterThanOrEqual(6);
    });
    it("every model has all required fields", () => {
        for (const id of GAME_MODEL_IDS) {
            const model = GAME_MODELS[id];
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
        for (const model of Object.values(GAME_MODELS)) {
            expect(model.sourceUrl).toMatch(/^https?:\/\//);
        }
    });
    it("every model description is at least 20 chars", () => {
        for (const model of Object.values(GAME_MODELS)) {
            expect(model.description.length).toBeGreaterThanOrEqual(20);
        }
    });
    it("has Khronos sample models", () => {
        const khronos = Object.values(GAME_MODELS).filter((m) => m.source.includes("Khronos"));
        expect(khronos.length).toBeGreaterThanOrEqual(4);
    });
    it("has Kenney models", () => {
        const kenney = Object.values(GAME_MODELS).filter((m) => m.source.includes("Kenney"));
        expect(kenney.length).toBeGreaterThanOrEqual(4);
    });
    it("has Sketchfab models", () => {
        const sf = Object.values(GAME_MODELS).filter((m) => m.source.includes("Sketchfab"));
        expect(sf.length).toBeGreaterThanOrEqual(4);
    });
    it("has Quaternius models", () => {
        const q = Object.values(GAME_MODELS).filter((m) => m.source.includes("Quaternius"));
        expect(q.length).toBeGreaterThanOrEqual(2);
    });
    it("includes character models", () => {
        const chars = Object.values(GAME_MODELS).filter((m) => m.category === "character");
        expect(chars.length).toBeGreaterThanOrEqual(2);
    });
    it("includes weapon models", () => {
        const weapons = Object.values(GAME_MODELS).filter((m) => m.category === "weapon");
        expect(weapons.length).toBeGreaterThanOrEqual(1);
    });
});
describe("getGameModel", () => {
    it("returns model by valid ID", () => {
        const model = getGameModel("khronos-damaged-helmet");
        expect(model).toBeDefined();
        expect(model.name).toBe("Damaged Helmet");
    });
    it("returns undefined for unknown ID", () => {
        expect(getGameModel("nonexistent")).toBeUndefined();
    });
});
describe("listGameModels", () => {
    it("returns all models when no filter", () => {
        const models = listGameModels();
        expect(models.length).toBe(GAME_MODEL_IDS.length);
    });
    it("filters by category", () => {
        const characters = listGameModels("character");
        expect(characters.length).toBeGreaterThan(0);
        for (const m of characters) {
            expect(m.category).toBe("character");
        }
    });
    it("filters by tag", () => {
        const animated = listGameModels(undefined, "animated");
        expect(animated.length).toBeGreaterThan(0);
        for (const m of animated) {
            expect(m.tags).toContain("animated");
        }
    });
    it("filters by both category and tag", () => {
        const result = listGameModels("creature", "animated");
        expect(result.length).toBeGreaterThan(0);
        for (const m of result) {
            expect(m.category).toBe("creature");
            expect(m.tags).toContain("animated");
        }
    });
    it("returns empty array for non-matching filter", () => {
        const result = listGameModels("character", "nonexistent-tag");
        expect(result).toEqual([]);
    });
});
describe("formatModelList", () => {
    it("formats model list with header and count", () => {
        const models = listGameModels("character");
        const text = formatModelList(models);
        expect(text).toContain("Free Game-Ready 3D Models");
        expect(text).toContain(`(${models.length})`);
    });
    it("includes model details in output", () => {
        const models = [getGameModel("khronos-damaged-helmet")];
        const text = formatModelList(models);
        expect(text).toContain("Damaged Helmet");
        expect(text).toContain("Khronos");
        expect(text).toContain("CC BY");
    });
    it("returns 'no models found' for empty list", () => {
        const text = formatModelList([]);
        expect(text).toContain("No models found");
    });
    it("includes source URLs as links", () => {
        const models = listGameModels("creature");
        const text = formatModelList(models);
        expect(text).toContain("https://");
    });
    it("shows all required fields per model", () => {
        const models = listGameModels();
        const text = formatModelList(models);
        expect(text).toContain("Source:");
        expect(text).toContain("Format:");
        expect(text).toContain("License:");
        expect(text).toContain("Tags:");
    });
});
