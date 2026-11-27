import { readFileSync } from "node:fs";
import { describe, expect, it } from "vitest";
import { generateCharacterViewer } from "./character-viewer.js";
import { generateInventory3D } from "./inventory-3d.js";
import { generateLevelEditor } from "./level-editor.js";
import { generateParticleEffects } from "./particle-effects.js";
import { generatePhysicsGame } from "./physics-game.js";
const sceneViewReference = readFileSync(new URL("../../../../llms.txt", import.meta.url), "utf8");
const templates = [
    ["character", (ar) => generateCharacterViewer({ style: "robot", ar })],
    ["inventory", (ar) => generateInventory3D({ layout: "grid", ar })],
    ["level editor", (ar) => generateLevelEditor({ theme: "forest", ar })],
    ["particles", (ar) => generateParticleEffects({ effect: "fire", ar })],
    ["physics", (ar) => generatePhysicsGame({ preset: "bouncing-balls", ar })],
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
