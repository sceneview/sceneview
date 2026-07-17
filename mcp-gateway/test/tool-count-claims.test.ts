/**
 * Truthfulness guard for the tool counts advertised in prose.
 *
 * SceneView is AI-first: an AI (or a human) reads these numbers and repeats
 * them, so a stale count is a bug, not a typo. This suite derives every
 * number programmatically from the actual registry — never from grepping
 * source — and compares it against the documented claims:
 *
 *   - the "NN tools total" gateway claim in the vertical package READMEs
 *     (drifted twice already: "63" was written when the registry mounted 63,
 *     then rerun tools + `generate_3d_model` / android-docs tools landed);
 *   - the tier map in `mcp/src/tiers.ts`, every entry of which must resolve
 *     to a tool the gateway actually mounts (the `get_started` phantom:
 *     listed as free for months while existing nowhere).
 *
 * The sibling suite `mcp/src/tool-count-claims.test.ts` covers the
 * `mcpize.yaml` numbers, which only depend on the stdio package surface.
 */

import { describe, it, expect } from "vitest";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import * as path from "node:path";

import { getAllTools } from "../src/mcp/registry.js";
import { getFreeToolNames, getProToolNames, getToolTier } from "../../mcp/src/tiers.js";

const here = path.dirname(fileURLToPath(import.meta.url));
const readmePath = (pkg: string) =>
  path.join(here, "..", "..", "mcp", "packages", pkg, "README.md");

/** The vertical package READMEs that advertise the gateway's total tool count. */
const READMES_WITH_TOTAL_CLAIM = ["gaming", "interior", "rerun"];

const mountedNames = () => new Set(getAllTools().map((t) => t.name));

describe("README 'NN tools total' claims match the registry", () => {
  it.each(READMES_WITH_TOTAL_CLAIM)(
    "mcp/packages/%s/README.md claims the real gateway total",
    (pkg) => {
      const text = readFileSync(readmePath(pkg), "utf8");
      const matches = [...text.matchAll(/—\s*(\d+) tools total/g)];
      expect(
        matches.length,
        `Expected a "— NN tools total" claim in mcp/packages/${pkg}/README.md`,
      ).toBeGreaterThan(0);
      for (const m of matches) {
        expect(
          Number(m[1]),
          `mcp/packages/${pkg}/README.md claims "${m[1]} tools total" but the ` +
            `gateway registry mounts ${getAllTools().length}. Update the README ` +
            `(and its siblings) to the real number.`,
        ).toBe(getAllTools().length);
      }
    },
  );
});

describe("tier map entries all resolve to mounted tools (no phantoms)", () => {
  it("every FREE_TOOLS entry is mounted by the gateway", () => {
    const mounted = mountedNames();
    const phantoms = getFreeToolNames().filter((n) => !mounted.has(n));
    expect(
      phantoms,
      `These FREE_TOOLS entries in mcp/src/tiers.ts exist in no tool library ` +
        `(the \`get_started\` bug class): ${phantoms.join(", ")}`,
    ).toEqual([]);
  });

  it("every PRO_TOOLS entry is mounted by the gateway", () => {
    const mounted = mountedNames();
    const phantoms = getProToolNames().filter((n) => !mounted.has(n));
    expect(
      phantoms,
      `These PRO_TOOLS entries in mcp/src/tiers.ts exist in no tool library: ` +
        phantoms.join(", "),
    ).toEqual([]);
  });

  it("view_3d_model (gateway-only widget tool) is mounted and free", () => {
    // Documents the intent behind keeping `view_3d_model` in FREE_TOOLS even
    // though the stdio package never serves it: the anonymous ChatGPT widget
    // path relies on the tier gate resolving it to "free".
    expect(mountedNames().has("view_3d_model")).toBe(true);
    expect(getToolTier("view_3d_model")).toBe("free");
  });

  it("every mounted tool has an explicit tier entry (no default-to-pro strays)", () => {
    // Reverse of the phantom guard, enabled by #2697 (the 11 stray tools —
    // 5 rerun, 2 automotive, 4 validators — are now explicitly mapped). The
    // unknown→pro fallback in getToolTier() stays as a safety net, but no
    // mounted tool may RELY on it: an unmapped tool is indistinguishable
    // from a forgotten mapping, which is how the get_started phantom class
    // starts.
    const mapped = new Set([...getFreeToolNames(), ...getProToolNames()]);
    const strays = getAllTools()
      .map((t) => t.name)
      .filter((n) => !mapped.has(n));
    expect(
      strays,
      `These mounted tools have no explicit entry in mcp/src/tiers.ts and ` +
        `silently default to "pro": ${strays.join(", ")}`,
    ).toEqual([]);
  });
});
