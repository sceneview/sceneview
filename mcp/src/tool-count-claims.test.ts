/**
 * Truthfulness guard for the tool counts `mcpize.yaml` advertises.
 *
 * The manifest's free/Pro counts drifted repeatedly ("26 free", "35 Pro",
 * "30 free") because they were edited by hand and re-counted by eye. This
 * suite derives them programmatically from the same modules the server runs:
 *
 *   - "NN free tools"  = stdio TOOL_DEFINITIONS entries whose tier is "free"
 *     (NOT `FREE_TOOLS.length`: that list also carries gateway-only entries
 *     like `view_3d_model`, which this package never lists or serves);
 *   - "NN Pro tools"   = PRO_TOOLS length (the full Pro surface a key
 *     unlocks through the hosted gateway, verticals included).
 *
 * The gateway-side counterpart (`mcp-gateway/test/tool-count-claims.test.ts`)
 * checks the "NN tools total" README claims against the mounted registry and
 * that no tier entry is a phantom.
 */

import { describe, it, expect } from "vitest";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import * as path from "node:path";

import { TOOL_DEFINITIONS } from "./tools/index.js";
import { getFreeToolNames, getProToolNames, getToolTier } from "./tiers.js";

const here = path.dirname(fileURLToPath(import.meta.url));
const mcpizeYaml = readFileSync(path.join(here, "..", "mcpize.yaml"), "utf8");

/**
 * FREE_TOOLS entries that are deliberately NOT in this package's
 * TOOL_DEFINITIONS because they are implemented gateway-side
 * (`mcp-gateway/src/mcp/widget-tools.ts`). Keep this list minimal — an entry
 * here is exempted from the phantom guard below.
 */
const GATEWAY_ONLY_FREE_TOOLS = ["view_3d_model"];

const localNames = new Set(TOOL_DEFINITIONS.map((t) => t.name));
const localFreeCount = TOOL_DEFINITIONS.filter(
  (t) => getToolTier(t.name) === "free",
).length;

describe("mcpize.yaml free/Pro tool counts", () => {
  it("every 'NN free tools' claim matches the stdio package's free surface", () => {
    const matches = [...mcpizeYaml.matchAll(/(\d+)\s+free\s+tools/g)];
    expect(matches.length).toBeGreaterThan(0);
    for (const m of matches) {
      expect(
        Number(m[1]),
        `mcpize.yaml claims "${m[1]} free tools" but the stdio package ` +
          `serves ${localFreeCount} free tools (TOOL_DEFINITIONS ∩ free tier).`,
      ).toBe(localFreeCount);
    }
  });

  it("every 'NN Pro tools' claim matches PRO_TOOLS", () => {
    const matches = [...mcpizeYaml.matchAll(/(\d+)\s+Pro\s+tools/g)];
    expect(matches.length).toBeGreaterThan(0);
    for (const m of matches) {
      expect(
        Number(m[1]),
        `mcpize.yaml claims "${m[1]} Pro tools" but PRO_TOOLS has ` +
          `${getProToolNames().length} entries.`,
      ).toBe(getProToolNames().length);
    }
  });
});

describe("tier map vs stdio TOOL_DEFINITIONS", () => {
  it("every FREE_TOOLS entry exists locally, unless documented gateway-only", () => {
    const phantoms = getFreeToolNames().filter(
      (n) => !localNames.has(n) && !GATEWAY_ONLY_FREE_TOOLS.includes(n),
    );
    expect(
      phantoms,
      `These FREE_TOOLS entries are neither in TOOL_DEFINITIONS nor in the ` +
        `documented gateway-only list (the \`get_started\` bug class): ` +
        phantoms.join(", "),
    ).toEqual([]);
  });

  it("gateway-only exemptions stay honest (not implemented locally)", () => {
    const stale = GATEWAY_ONLY_FREE_TOOLS.filter((n) => localNames.has(n));
    expect(
      stale,
      `These tools are now in TOOL_DEFINITIONS — remove them from the ` +
        `GATEWAY_ONLY_FREE_TOOLS exemption list: ${stale.join(", ")}`,
    ).toEqual([]);
  });

  it("every stdio tool has an explicit tier entry", () => {
    const mapped = new Set([...getFreeToolNames(), ...getProToolNames()]);
    const unmapped = TOOL_DEFINITIONS.map((t) => t.name).filter(
      (n) => !mapped.has(n),
    );
    expect(
      unmapped,
      `These stdio tools have no entry in tiers.ts and would silently ` +
        `default to "pro": ${unmapped.join(", ")}`,
    ).toEqual([]);
  });
});
