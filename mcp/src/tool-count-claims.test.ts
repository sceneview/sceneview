/**
 * Truthfulness guard for the tool count `mcpize.yaml` advertises.
 *
 * The manifest's counts drifted repeatedly ("26 free", "35 Pro", "30 free")
 * because they were edited by hand and re-counted by eye. The claim is now
 * derived programmatically from the registry the server actually runs.
 *
 * Since the paid tier was removed there is one number left: every tool in
 * `TOOL_DEFINITIONS` is free. A name in `surfaces.ts` with no definition is
 * a phantom, full stop.
 */

import { readFileSync } from "node:fs";
import * as path from "node:path";
import { fileURLToPath } from "node:url";
import { describe, expect, it } from "vitest";
import { getLocalOnlyToolNames } from "./surfaces.js";
import { TOOL_DEFINITIONS } from "./tools/index.js";

const here = path.dirname(fileURLToPath(import.meta.url));
const mcpizeYaml = readFileSync(path.join(here, "..", "mcpize.yaml"), "utf8");

const localNames = new Set(TOOL_DEFINITIONS.map((t) => t.name));

describe("mcpize.yaml tool counts", () => {
  it("every 'NN free tools' claim matches the package's tool surface", () => {
    const matches = [...mcpizeYaml.matchAll(/(\d+)\s+free\s+tools/g)];
    expect(matches.length).toBeGreaterThan(0);
    for (const m of matches) {
      expect(
        Number(m[1]),
        `mcpize.yaml claims "${m[1]} free tools" but the package serves ` +
          `${TOOL_DEFINITIONS.length}.`
      ).toBe(TOOL_DEFINITIONS.length);
    }
  });

  it("advertises no paid tier", () => {
    expect(mcpizeYaml).not.toMatch(/Pro tools/i);
    expect(mcpizeYaml).not.toMatch(/SCENEVIEW_API_KEY/);
    expect(mcpizeYaml).not.toMatch(/mcp-tools-lab/);
  });
});

describe("surfaces.ts vs TOOL_DEFINITIONS", () => {
  it("every local-only entry exists in TOOL_DEFINITIONS", () => {
    const phantoms = getLocalOnlyToolNames().filter((n) => !localNames.has(n));
    expect(
      phantoms,
      `These local-only entries are not in TOOL_DEFINITIONS: ${phantoms.join(", ")}`
    ).toEqual([]);
  });
});
