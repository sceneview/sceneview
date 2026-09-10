/**
 * Guards the one surface distinction left after the paid tier was removed:
 * a handful of tools run locally only, everything else is served everywhere,
 * and nothing anywhere points at a price.
 */

import { readFileSync } from "node:fs";
import * as path from "node:path";
import { fileURLToPath } from "node:url";
import { describe, expect, it } from "vitest";
import { getLocalOnlyToolNames, isLocalOnlyTool } from "./surfaces.js";
import { TOOL_DEFINITIONS } from "./tools/index.js";

const here = path.dirname(fileURLToPath(import.meta.url));

describe("isLocalOnlyTool", () => {
  it("is true for the three generation tools", () => {
    expect(isLocalOnlyTool("render_3d_preview")).toBe(true);
    expect(isLocalOnlyTool("create_3d_artifact")).toBe(true);
    expect(isLocalOnlyTool("generate_scene")).toBe(true);
  });

  it("is false for every other declared tool", () => {
    const localOnly = new Set(getLocalOnlyToolNames());
    for (const tool of TOOL_DEFINITIONS) {
      if (localOnly.has(tool.name)) continue;
      expect(isLocalOnlyTool(tool.name), tool.name).toBe(false);
    }
  });

  it("defaults an unknown name to available, not withheld", () => {
    // A typo must reach the dispatcher and get an honest "Unknown tool",
    // never a refusal that implies the tool exists behind something.
    expect(isLocalOnlyTool("get_started")).toBe(false);
  });
});

describe("no paid tier survives in the shipped source", () => {
  const srcDir = path.join(here);

  it("no source file references the deleted Pro gateway", () => {
    const files = ["surfaces.ts", "server.ts", "index.ts", "telemetry.ts", "http.ts"];
    for (const file of files) {
      const text = readFileSync(path.join(srcDir, file), "utf8");
      expect(text, file).not.toMatch(/sceneview-mcp\.mcp-tools-lab\.workers\.dev/);
      expect(text, file).not.toMatch(/SCENEVIEW_API_KEY/);
    }
  });

  it("no tool description is prefixed with a paywall marker", () => {
    for (const tool of TOOL_DEFINITIONS) {
      expect(tool.description.startsWith("[PRO]"), tool.name).toBe(false);
    }
  });
});
