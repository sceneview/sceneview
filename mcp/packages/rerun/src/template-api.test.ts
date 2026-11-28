import { readFileSync } from "node:fs";
import { describe, expect, it } from "vitest";
import { generateArLogger } from "./ar-logger.js";
import { generateSetupProject } from "./setup-project.js";

const sceneViewReference = readFileSync(
  new URL("../../../../llms.txt", import.meta.url),
  "utf8",
);

describe("generated SceneView templates", () => {
  it("uses documented 4.x Android composables", () => {
    const templates = [
      generateArLogger({ language: "kotlin", dataTypes: ["pose", "hit_results"] }),
      ...generateSetupProject({ platform: "android" }).files
        .filter((file) => file.language === "kotlin")
        .map((file) => file.contents),
    ];

    for (const code of templates) {
      expect(code).not.toMatch(/io\.github\.sceneview\.(?:ar\.)?node\./);
      expect(code).not.toMatch(/\b(?:ARScene|Scene|ArSceneView)\s*\(/);
      const composables = new Set(
        [...code.matchAll(/\b([A-Z][A-Za-z0-9]*(?:Node|SceneView))\s*\(/g)]
          .map((match) => match[1]),
      );
      for (const composable of composables) {
        expect(sceneViewReference, `${composable} is missing from llms.txt`).toContain(
          `fun ${composable}(`,
        );
      }
    }
  });
});
