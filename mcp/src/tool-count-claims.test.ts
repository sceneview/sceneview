/**
 * Truthfulness guard for the tool counts `mcpize.yaml` advertises.
 *
 * The manifest's free/Pro counts drifted repeatedly ("26 free", "35 Pro",
 * "30 free") because they were edited by hand and re-counted by eye. This
 * suite derives them programmatically from the same modules the server runs:
 *
 *   - "NN free tools"  = TOOL_DEFINITIONS entries whose tier is "free"
 *     (derived from the registry, never from `FREE_TOOLS.length`, so a tier
 *     entry with no implementation cannot inflate the claim);
 *   - "NN Pro tools"   = PRO_TOOLS length (the full Pro surface a key
 *     unlocks through the hosted gateway, verticals included).
 *
 * Since the hosted gateway was deleted (2026-08-31) every free tool —
 * `view_3d_model` included — is implemented in this package, so there is no
 * "gateway-only" exemption any more: a FREE_TOOLS entry that is not in
 * TOOL_DEFINITIONS is a phantom, full stop.
 */

import { readFileSync } from "node:fs";
import * as path from "node:path";
import { fileURLToPath } from "node:url";
import { describe, expect, it } from "vitest";
import { getFreeToolNames, getProToolNames, getToolTier } from "./tiers.js";
import { TOOL_DEFINITIONS } from "./tools/index.js";

const here = path.dirname(fileURLToPath(import.meta.url));
const mcpizeYaml = readFileSync(path.join(here, "..", "mcpize.yaml"), "utf8");

const localNames = new Set(TOOL_DEFINITIONS.map((t) => t.name));
const localFreeCount = TOOL_DEFINITIONS.filter((t) => getToolTier(t.name) === "free").length;

describe("mcpize.yaml free/Pro tool counts", () => {
  it("every 'NN free tools' claim matches the stdio package's free surface", () => {
    const matches = [...mcpizeYaml.matchAll(/(\d+)\s+free\s+tools/g)];
    expect(matches.length).toBeGreaterThan(0);
    for (const m of matches) {
      expect(
        Number(m[1]),
        `mcpize.yaml claims "${m[1]} free tools" but the stdio package ` +
          `serves ${localFreeCount} free tools (TOOL_DEFINITIONS ∩ free tier).`
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
          `${getProToolNames().length} entries.`
      ).toBe(getProToolNames().length);
    }
  });
});

describe("tier map vs stdio TOOL_DEFINITIONS", () => {
  it("every FREE_TOOLS entry exists in TOOL_DEFINITIONS", () => {
    const phantoms = getFreeToolNames().filter((n) => !localNames.has(n));
    expect(
      phantoms,
      `These FREE_TOOLS entries are not in TOOL_DEFINITIONS (the \`get_started\` ` +
        `bug class): ${phantoms.join(", ")}`
    ).toEqual([]);
  });

  it("every stdio tool has an explicit tier entry", () => {
    const mapped = new Set([...getFreeToolNames(), ...getProToolNames()]);
    const unmapped = TOOL_DEFINITIONS.map((t) => t.name).filter((n) => !mapped.has(n));
    expect(
      unmapped,
      `These stdio tools have no entry in tiers.ts and would silently ` +
        `default to "pro": ${unmapped.join(", ")}`
    ).toEqual([]);
  });
});
