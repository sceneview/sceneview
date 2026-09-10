/**
 * Truthfulness guard for the tool counts advertised in prose.
 *
 * SceneView is AI-first: an AI (or a human) reads these numbers and repeats
 * them, so a stale count is a bug, not a typo. This suite derives every
 * number programmatically from the actual registry — never from grepping
 * source — and compares it against the documented claims:
 *
 *   - the absence of any gateway claim in the vertical package READMEs. The
 *     "NN tools total" number drifted twice ("63" was written when the
 *     registry mounted 63, then rerun tools + `generate_3d_model` /
 *     android-docs tools landed) before the section was removed outright:
 *     the URL it pointed at has answered 404 since the Worker was deleted;
 *   - the tier map in `mcp-gateway/src/mcp/tiers.ts`, every entry of which must resolve
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
import { getFreeToolNames, getProToolNames, getToolTier } from "../src/mcp/tiers.js";

const here = path.dirname(fileURLToPath(import.meta.url));
const readmePath = (pkg: string) =>
  path.join(here, "..", "..", "mcp", "packages", pkg, "README.md");

/** The vertical package READMEs that used to advertise the hosted gateway. */
const READMES_WITHOUT_GATEWAY_CLAIM = ["gaming", "interior", "rerun"];

const mountedNames = () => new Set(getAllTools().map((t) => t.name));

describe("package READMEs advertise no hosted gateway", () => {
  it.each(READMES_WITHOUT_GATEWAY_CLAIM)(
    "mcp/packages/%s/README.md points at no gateway URL or tool total",
    (pkg) => {
      const text = readFileSync(readmePath(pkg), "utf8");
      expect(
        [...text.matchAll(/—\s*\d+ tools total/g)].length,
        `mcp/packages/${pkg}/README.md advertises a gateway tool total again. ` +
          `The gateway it counted is deleted and its URL 404s.`,
      ).toBe(0);
      expect(text, `mcp/packages/${pkg}/README.md`).not.toMatch(/mcp-tools-lab/);
      expect(text, `mcp/packages/${pkg}/README.md`).not.toMatch(/Pro tier/i);
    },
  );
});

describe("tier map entries all resolve to mounted tools (no phantoms)", () => {
  it("every FREE_TOOLS entry is mounted by the gateway", () => {
    const mounted = mountedNames();
    const phantoms = getFreeToolNames().filter((n) => !mounted.has(n));
    expect(
      phantoms,
      `These FREE_TOOLS entries in mcp-gateway/src/mcp/tiers.ts exist in no tool library ` +
        `(the \`get_started\` bug class): ${phantoms.join(", ")}`,
    ).toEqual([]);
  });

  it("every PRO_TOOLS entry is mounted by the gateway", () => {
    const mounted = mountedNames();
    const phantoms = getProToolNames().filter((n) => !mounted.has(n));
    expect(
      phantoms,
      `These PRO_TOOLS entries in mcp-gateway/src/mcp/tiers.ts exist in no tool library: ` +
        phantoms.join(", "),
    ).toEqual([]);
  });

  it("view_3d_model (upstream MCP Apps widget tool) is mounted once and free", () => {
    // The widget tool now lives in `mcp/src/tools/definitions.ts` and is
    // mounted through the `sceneview` library — not a gateway-native copy.
    // The anonymous ChatGPT widget path relies on the tier gate resolving it
    // to "free".
    expect(mountedNames().has("view_3d_model")).toBe(true);
    expect(getAllTools().filter((t) => t.name === "view_3d_model")).toHaveLength(1);
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
      `These mounted tools have no explicit entry in mcp-gateway/src/mcp/tiers.ts and ` +
        `silently default to "pro": ${strays.join(", ")}`,
    ).toEqual([]);
  });
});
