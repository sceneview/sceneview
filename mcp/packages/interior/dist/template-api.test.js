import { readFileSync } from "node:fs";
import { describe, expect, it } from "vitest";
import { generateFurniturePlacement } from "./furniture-placement.js";
import { generateLightingDesign } from "./lighting-design.js";
import { generateMaterialSwitcher } from "./material-switcher.js";
import { generateRoomPlanner } from "./room-planner.js";
import { generateRoomTour } from "./room-tour.js";
const sceneViewReference = readFileSync(new URL("../../../../llms.txt", import.meta.url), "utf8");
const templates = [
    ["room planner", (ar) => generateRoomPlanner({ roomType: "living-room", ar })],
    ["furniture", (ar) => generateFurniturePlacement({ category: "chair", ar })],
    ["materials", (ar) => generateMaterialSwitcher({ surface: "wall-paint", ar })],
    ["lighting", (ar) => generateLightingDesign({ lights: ["spot", "pendant", "natural"], ar })],
    ["room tour", (ar) => generateRoomTour({ tourStyle: "orbit", ar })],
];
const forbidden4xPatterns = [
    /io\.github\.sceneview\.(?:ar\.)?node\./,
    /\b(?:ARScene|Scene|ArSceneView)\s*\(/,
    /\b(?:rememberModelNode|rememberNodes|createModelInstance|loadModelAsync)\b/,
    /\bchildNodes\s*=/,
];
describe("generated SceneView templates", () => {
    it.each(templates)("uses documented 4.x APIs in every %s template", (_, generate) => {
        for (const ar of [false, true]) {
            const code = generate(ar);
            for (const forbidden of forbidden4xPatterns)
                expect(code).not.toMatch(forbidden);
            const composables = new Set([...code.matchAll(/\b([A-Z][A-Za-z0-9]*(?:Node|SceneView))\s*\(/g)]
                .map((match) => match[1]));
            expect(composables.size).toBeGreaterThan(0);
            for (const composable of composables) {
                expect(sceneViewReference, `${composable} is missing from llms.txt`).toContain(`fun ${composable}(`);
            }
            if (ar)
                expect(code).toContain("AnchorNode(anchor =");
        }
    });
});
