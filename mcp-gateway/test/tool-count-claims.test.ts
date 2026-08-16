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
 *
 * The public dashboard is covered here too, and it is the reason this file
 * grew: /, /pricing and /docs printed five hardcoded counts between them
 * (27 free, 27 Pro, 4 packages, 29-vs-28 for the same npm package, "31
 * tools, no signup" for a package whose last 3 tools refuse to run without
 * a key) and nothing compared any of them to the registry. The one test
 * that looked like it did — `dashboard.test.ts` asserting "27 free tools" —
 * pinned the stale number instead of deriving it, so the drift was green.
 */

import { describe, it, expect } from "vitest";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import * as path from "node:path";

import { getAllTools } from "../src/mcp/registry.js";
import { getFreeToolNames, getProToolNames, getToolTier } from "../../mcp/src/tiers.js";
import { renderLanding } from "../src/dashboard/landing.js";
import { renderPricing } from "../src/dashboard/pricing.js";
import { renderDocs } from "../src/dashboard/docs.js";
import {
  HOSTED_FREE_TOOL_COUNT,
  PRO_TOOL_COUNT,
  STDIO_FREE_TOOL_COUNT,
  STDIO_TOTAL_TOOL_COUNT,
  VERTICAL_PACKAGE_COUNT,
  VERTICAL_TOOL_COUNT,
} from "../src/dashboard/counts.js";

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

  it("the derived counts describe two distinct surfaces, not one", () => {
    // The 28-vs-29 disagreement between two paragraphs of /docs was not a
    // typo: the hosted gateway serves the widget tool and the stdio package
    // cannot. If these two ever become equal, one of the pages is about to
    // start lying again and the guard below would stop discriminating.
    expect(HOSTED_FREE_TOOL_COUNT).toBeGreaterThan(STDIO_FREE_TOOL_COUNT);
    expect(STDIO_TOTAL_TOOL_COUNT).toBeGreaterThan(STDIO_FREE_TOOL_COUNT);
    expect(VERTICAL_TOOL_COUNT).toBeLessThan(PRO_TOOL_COUNT);
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

describe("public dashboard prose states only derived tool counts", () => {
  /** The pages a prospective or paying customer reads before spending money. */
  const PAGES: Array<[string, () => Promise<string>]> = [
    ["/", renderLanding],
    ["/pricing", renderPricing],
    ["/docs", renderDocs],
  ];

  /** Words that mark a sentence as being about the local stdio package. */
  const STDIO_MARKERS = /npm package|local package|sceneview-mcp|stdio/i;

  /**
   * Every "NN free tools" phrase must match the surface its sentence names.
   *
   * Checking membership in {hosted, stdio} is NOT enough, and that weaker
   * assertion was written here first and let a deliberate mutant through:
   * the shipped bug was "29 free tools of the npm package" — a number that
   * is real for the hosted gateway, attached to a sentence about the local
   * package, one paragraph away from the correct 28. So the surrounding
   * ±90 characters decide which constant applies, and an occurrence with no
   * surface marker is read as the hosted gateway, which is what an
   * unqualified number on these pages means.
   */
  it.each(PAGES)("%s states free-tool counts that match the surface named", async (_p, render) => {
    const html = await render();
    for (const m of html.matchAll(/(\d+) free\s+tools/g)) {
      const at = m.index ?? 0;
      const window = html.slice(Math.max(0, at - 90), at + m[0].length + 90);
      const isStdio = STDIO_MARKERS.test(window);
      const expected = isStdio ? STDIO_FREE_TOOL_COUNT : HOSTED_FREE_TOOL_COUNT;
      expect(
        Number(m[1]),
        `This page claims "${m[0]}" in a sentence about the ` +
          `${isStdio ? "local stdio package" : "hosted gateway"}, which serves ` +
          `${expected}. Import the matching constant from ` +
          `src/dashboard/counts.ts — the two surfaces differ by the ` +
          `gateway-only widget tool and swapping them is the drift that shipped.`,
      ).toBe(expected);
    }
  });

  it.each(PAGES)("%s states no Pro-tool count but the derived one", async (_p, render) => {
    const html = await render();
    for (const m of html.matchAll(/(\d+) Pro\s+tools/g)) {
      expect(
        Number(m[1]),
        `This page claims "${m[1]} Pro tools" but the gateway gates ` +
          `${PRO_TOOL_COUNT}. Import PRO_TOOL_COUNT from src/dashboard/counts.ts.`,
      ).toBe(PRO_TOOL_COUNT);
    }
  });

  it("/ stat cards match the registry", async () => {
    // The landing needs its own assertion: it prints the label and the number
    // in two sibling <div>s, so the prose regexes above match NOTHING on it
    // and would pass vacuously over the exact three numbers (27 / 27 / 4)
    // that were wrong the longest.
    const html = await renderLanding();
    const card = (label: string) => {
      const m = html.match(
        new RegExp(`>${label}</div>\\s*<div class="value">(\\d+)<`),
      );
      expect(m, `No "${label}" stat card found on the landing page`).not.toBeNull();
      return Number(m![1]);
    };
    expect(card("Free tools")).toBe(HOSTED_FREE_TOOL_COUNT);
    expect(card("Pro tools")).toBe(PRO_TOOL_COUNT);
    expect(card("Specialized packages")).toBe(VERTICAL_PACKAGE_COUNT);
  });

  it("/pricing describes the vertical packages with the registry's own shape", async () => {
    const html = await renderPricing();
    expect(html).toContain(`${VERTICAL_PACKAGE_COUNT} vertical packages`);
    expect(html).toContain(`${VERTICAL_TOOL_COUNT} specialised tools`);
    // The self-host FAQ promised "31 tools, no signup". 31 is what the
    // package DECLARES; three of them are Pro-gated and error out without a
    // key, so the free-install promise must be the free count.
    expect(html).toContain(`${STDIO_FREE_TOOL_COUNT} free`);
    expect(html).not.toMatch(
      new RegExp(`${STDIO_TOTAL_TOOL_COUNT} tools,\\s*no signup`),
    );
  });

  it("both free surfaces are actually printed somewhere (constants still wired)", async () => {
    // Guards the failure mode the derivation itself introduces: an import
    // dropped during an edit leaves the pages silent rather than wrong, and
    // every per-page assertion above passes vacuously on prose that says
    // nothing.
    const all = (await Promise.all(PAGES.map(([, r]) => r()))).join("\n");
    expect(all).toContain(`${HOSTED_FREE_TOOL_COUNT} free tools`);
    expect(all).toContain(`${STDIO_FREE_TOOL_COUNT} free tools`);
    expect(all).toContain(`${PRO_TOOL_COUNT} Pro tools`);
  });
});
