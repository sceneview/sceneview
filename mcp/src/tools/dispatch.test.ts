import { describe, it, expect } from "vitest";
import { dispatchTool, getAllTools, getToolDefinition, TOOL_DEFINITIONS } from "./index.js";

describe("tools library — getAllTools", () => {
  it("exposes at least the 15 free tools", () => {
    const names = getAllTools().map((t) => t.name);
    expect(names.length).toBeGreaterThanOrEqual(15);
    for (const free of [
      "get_sample",
      "list_samples",
      "get_setup",
      "validate_code",
      "get_node_reference",
      "get_platform_roadmap",
      "get_best_practices",
      "get_troubleshooting",
      "debug_issue",
      "list_platforms",
      "get_animation_guide",
      "get_gesture_guide",
      "get_performance_tips",
      "get_material_guide",
      "get_collision_guide",
    ]) {
      expect(names).toContain(free);
    }
  });

  it("has no duplicate tool names", () => {
    const names = TOOL_DEFINITIONS.map((t) => t.name);
    const unique = new Set(names);
    expect(unique.size).toBe(names.length);
  });

  it("returns a copy — mutating the result does not affect internal state", () => {
    const a = getAllTools();
    a.pop();
    const b = getAllTools();
    expect(b.length).toBe(a.length + 1);
  });
});

describe("tools library — getToolDefinition", () => {
  it("finds a known tool by name", () => {
    const def = getToolDefinition("get_sample");
    expect(def).toBeDefined();
    expect(def?.description).toMatch(/compilable Kotlin sample/i);
  });

  it("returns undefined for unknown names", () => {
    expect(getToolDefinition("definitely_not_a_tool")).toBeUndefined();
  });
});

describe("tools library — dispatchTool", () => {
  it("runs a simple free tool without args", async () => {
    const result = await dispatchTool("get_migration_guide", undefined);
    expect(result.isError).toBeFalsy();
    expect(result.content[0]?.type).toBe("text");
    expect(result.content[0]?.text.length).toBeGreaterThan(0);
  });

  it("runs a tool that needs args", async () => {
    const result = await dispatchTool("get_setup", { type: "3d" });
    expect(result.isError).toBeFalsy();
    expect(result.content[0]?.text).toContain("sceneview");
  });

  it("returns an error result for unknown tools", async () => {
    const result = await dispatchTool("does_not_exist", {});
    expect(result.isError).toBe(true);
    expect(result.content[0]?.text).toMatch(/unknown tool/i);
  });

  it("returns an error result for missing required args", async () => {
    const result = await dispatchTool("validate_code", {});
    expect(result.isError).toBe(true);
    expect(result.content[0]?.text).toMatch(/missing required parameter/i);
  });

  it("resolves get_node_reference from the embedded llms.txt", async () => {
    const result = await dispatchTool("get_node_reference", { nodeType: "ModelNode" });
    expect(result.content[0]?.text).toMatch(/ModelNode/);
  });

  it("wires generate_3d_model and surfaces the BYOK missing-key guidance", async () => {
    // Ensure the BYOK env var is absent so no network is attempted.
    const originalKey = process.env.TRIPO_API_KEY;
    delete process.env.TRIPO_API_KEY;
    try {
      const result = await dispatchTool("generate_3d_model", { prompt: "a low-poly chair" });
      expect(result.isError).toBe(true);
      expect(result.content[0]?.text).toContain("TRIPO_API_KEY");
      expect(result.content[0]?.text).toContain("platform.tripo3d.ai/api-keys");
    } finally {
      if (originalKey === undefined) {
        delete process.env.TRIPO_API_KEY;
      } else {
        process.env.TRIPO_API_KEY = originalKey;
      }
    }
  });
});
