/**
 * Contract test for tool annotations — the gate Anthropic's connectors
 * directory applies to every submitted server:
 *
 *   "All tools must include a `title` and the applicable `readOnlyHint` or
 *    `destructiveHint`."
 *   https://claude.com/docs/connectors/building/submission
 *
 * The submission portal syncs the tool list from the live server and flags
 * anything missing a title or an annotation *before* the submission can be
 * sent, so a tool added without a title is not a style nit — it blocks the
 * listing. `types.ts` has always claimed this file exists; it does now.
 *
 * `ToolAnnotations.title` stays optional in the type because the gateway
 * package shares that interface; the requirement is enforced here, at the
 * boundary that actually ships tools.
 */

import { describe, expect, it } from "vitest";
import { getFreeToolNames } from "../tiers.js";
import { TOOL_DEFINITIONS } from "./index.js";

describe("tool annotations", () => {
  it("declares annotations on every tool", () => {
    const missing = TOOL_DEFINITIONS.filter((tool) => !tool.annotations).map((t) => t.name);
    expect(missing).toEqual([]);
  });

  it("declares a non-empty title on every tool", () => {
    const missing = TOOL_DEFINITIONS.filter(
      (tool) => !tool.annotations?.title || tool.annotations.title.trim() === ""
    ).map((t) => t.name);
    expect(missing).toEqual([]);
  });

  it("declares readOnlyHint and destructiveHint on every tool", () => {
    const missing = TOOL_DEFINITIONS.filter(
      (tool) =>
        typeof tool.annotations?.readOnlyHint !== "boolean" ||
        typeof tool.annotations?.destructiveHint !== "boolean"
    ).map((t) => t.name);
    expect(missing).toEqual([]);
  });

  it("gives every tool a distinct title, so a picker can tell them apart", () => {
    const titles = TOOL_DEFINITIONS.map((t) => t.annotations?.title);
    expect(new Set(titles).size).toBe(titles.length);
  });

  it("covers every free tool that the remote surface publishes", () => {
    // The free tier is what an anonymous connector sees. A free tool with no
    // definition here would be advertised by `tiers.ts` and never listed.
    const defined = new Set(TOOL_DEFINITIONS.map((t) => t.name));
    const undefinedFree = getFreeToolNames().filter((name) => !defined.has(name));
    expect(undefinedFree).toEqual([]);
  });
});
