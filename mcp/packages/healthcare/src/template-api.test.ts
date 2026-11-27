import { readFileSync } from "node:fs";
import { describe, expect, it } from "vitest";
import { generateAnatomyViewer } from "./anatomy-viewer.js";
import { generateDentalViewer } from "./dental-viewer.js";
import { generateMedicalImaging } from "./medical-imaging.js";
import { generateMoleculeViewer } from "./molecule-viewer.js";
import { generateSurgicalPlanning } from "./surgical-planning.js";

const sceneViewReference = readFileSync(
  new URL("../../../../llms.txt", import.meta.url),
  "utf8",
);

const templates = [
  ["anatomy", (ar: boolean) => generateAnatomyViewer({ system: "skeleton", ar })],
  ["dental", (ar: boolean) => generateDentalViewer({ viewType: "full-arch", ar })],
  ["medical imaging", (ar: boolean) => generateMedicalImaging({ modality: "ct", ar })],
  ["molecule", (ar: boolean) => generateMoleculeViewer({ moleculeType: "protein", ar })],
  ["surgical planning", (ar: boolean) => generateSurgicalPlanning({ surgeryType: "cardiac", ar })],
] as const;

const forbidden4xPatterns = [
  /io\.github\.sceneview\.(?:ar\.)?node\./,
  /\b(?:ARScene|Scene|ArSceneView)\s*\(/,
  /\b(?:rememberModelNode|rememberNodes|createModelInstance|loadModelAsync)\b/,
  /\bchildNodes\s*=/,
];

const requiredTypeImports = [
  ["Anchor", "com.google.ar.core.Anchor"],
  ["Frame", "com.google.ar.core.Frame"],
  ["Size", "io.github.sceneview.math.Size"],
] as const;

function expectUsedTypesImported(code: string) {
  const body = code
    .replace(/^import .*$/gm, "")
    .replace(/\/\*[\s\S]*?\*\//g, "")
    .replace(/\/\/.*$/gm, "")
    .replace(/"(?:\\.|[^"\\])*"/g, "");

  for (const [symbol, importPath] of requiredTypeImports) {
    expect(sceneViewReference, `${symbol} is missing from llms.txt`).toMatch(
      new RegExp(`\\b${symbol}\\b`),
    );
    if (new RegExp(`\\b${symbol}\\b`).test(body)) {
      expect(code, `${symbol} is used without its import`).toContain(`import ${importPath}`);
    }
  }
}

describe("generated SceneView templates", () => {
  it.each(templates)("uses documented 4.x APIs in every %s template", (_, generate) => {
    for (const ar of [false, true]) {
      const code = generate(ar);
      for (const forbidden of forbidden4xPatterns) expect(code).not.toMatch(forbidden);
      expectUsedTypesImported(code);

      const composables = new Set(
        [...code.matchAll(/\b([A-Z][A-Za-z0-9]*(?:Node|SceneView))\s*\(/g)]
          .map((match) => match[1]),
      );
      expect(composables.size).toBeGreaterThan(0);
      for (const composable of composables) {
        expect(sceneViewReference, `${composable} is missing from llms.txt`).toContain(
          `fun ${composable}(`,
        );
      }
      if (ar) expect(code).toContain("AnchorNode(anchor =");
    }
  });
});
