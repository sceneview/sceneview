import { describe, it, expect } from "vitest";
import { AjvJsonSchemaValidator } from "@modelcontextprotocol/sdk/validation/ajv";
import type { JsonSchemaType } from "@modelcontextprotocol/sdk/validation";
import {
  dispatch,
  getAllTools,
  getRegistrySummary,
  getToolDefinition,
  __internals,
} from "../src/mcp/registry.js";

describe("registry — getAllTools", () => {
  it("merges tools from all 5 upstream libraries", () => {
    const names = getAllTools().map((t) => t.name);
    // sceneview-mcp sample
    expect(names).toContain("get_sample");
    expect(names).toContain("validate_code");
    // automotive sample
    expect(names).toContain("get_car_configurator");
    // gaming sample
    expect(names).toContain("get_character_viewer");
    // healthcare sample
    expect(names).toContain("get_surgical_planning");
    // interior sample
    expect(names).toContain("get_room_planner");
  });

  it("has no duplicate tool names across packages", () => {
    const names = getAllTools().map((t) => t.name);
    const unique = new Set(names);
    expect(unique.size).toBe(names.length);
  });

  it("returns a fresh copy — mutating the result does not affect internal state", () => {
    const a = getAllTools();
    const before = a.length;
    a.pop();
    const b = getAllTools();
    expect(b.length).toBe(before);
  });

  it("every multiplexed tool ships MCP behaviour annotations", () => {
    // Without these, the OpenAI App Store + Smithery review demand a manual
    // free-form justification per tool — with 60+ multiplexed tools that is
    // unmanageable. Type is optional for forward-compatibility, so this
    // runtime assertion is the actual gate.
    const missing = getAllTools()
      .filter((t) => {
        const a = (t as { annotations?: unknown }).annotations as
          | {
              readOnlyHint?: unknown;
              openWorldHint?: unknown;
              destructiveHint?: unknown;
            }
          | undefined;
        return (
          !a ||
          typeof a.readOnlyHint !== "boolean" ||
          typeof a.openWorldHint !== "boolean" ||
          typeof a.destructiveHint !== "boolean"
        );
      })
      .map((t) => t.name);
    expect(
      missing,
      `These tools are missing readOnlyHint / openWorldHint / destructiveHint annotations: ${missing.join(", ")}`,
    ).toEqual([]);
  });
});

describe("registry — getRegistrySummary", () => {
  it("reports all 7 libraries with a non-zero tool count each", () => {
    const summary = getRegistrySummary();
    expect(summary.libraries.map((l) => l.id).sort()).toEqual([
      "automotive",
      "gaming",
      "healthcare",
      "interior",
      "rerun",
      "sceneview",
      "widgets",
    ]);
    for (const lib of summary.libraries) {
      expect(lib.toolCount).toBeGreaterThan(0);
    }
    expect(summary.totalTools).toBe(
      summary.libraries.reduce((n, l) => n + l.toolCount, 0),
    );
    // sceneview-mcp alone exposes 25+ tools.
    expect(summary.totalTools).toBeGreaterThanOrEqual(40);
  });

  it("builds the owner map eagerly during import", () => {
    // If the module loaded without throwing and OWNERS is a Map, the eager
    // collision check ran to completion.
    expect(__internals.OWNERS).toBeInstanceOf(Map);
    expect(__internals.OWNERS.size).toBe(getAllTools().length);
  });
});

describe("registry — getToolDefinition", () => {
  it("finds a sceneview-mcp tool", () => {
    const def = getToolDefinition("get_sample");
    expect(def).toBeDefined();
    expect(def?.name).toBe("get_sample");
  });

  it("finds a vertical tool", () => {
    const def = getToolDefinition("get_car_configurator");
    expect(def).toBeDefined();
    expect(def?.description.toLowerCase()).toContain("car");
  });

  it("returns undefined for unknown names", () => {
    expect(getToolDefinition("definitely_not_a_tool")).toBeUndefined();
  });
});

describe("registry — dispatch", () => {
  it("every declared outputSchema validates its handler's sample result", async () => {
    const samples: Record<string, Record<string, unknown>> = {
      render_3d_preview: {
        modelUrl: "https://example.com/chair.glb",
        title: "Chair",
      },
      create_3d_artifact: {
        type: "model-viewer",
        modelUrl: "https://example.com/chair.glb",
        title: "Chair",
      },
      list_platforms: {},
      setup_rerun_project: { platform: "python" },
      embed_web_viewer: { rrdUrl: "https://example.com/session.rrd" },
      list_car_models: {},
      validate_automotive_code: { code: "@Composable fun Car() {}" },
      list_game_models: {},
      validate_game_code: { code: "@Composable fun Game() {}" },
      list_medical_models: {},
      validate_medical_code: { code: "@Composable fun Anatomy() {}" },
      list_furniture_models: {},
      validate_interior_code: { code: "@Composable fun Room() {}" },
      view_3d_model: {
        modelUrl: "https://example.com/chair.glb",
        title: "Chair",
        autoRotate: false,
        ar: true,
        alt: "A chair",
        posterUrl: "https://example.com/chair.webp",
      },
    };
    const validatorProvider = new AjvJsonSchemaValidator();
    const definitions = getAllTools().filter((tool) => tool.outputSchema);

    expect(definitions.map((tool) => tool.name).sort()).toEqual(
      Object.keys(samples).sort(),
    );
    for (const definition of definitions) {
      const result = await dispatch(definition.name, samples[definition.name]);
      expect(result.isError, definition.name).toBeFalsy();
      expect(result.structuredContent, definition.name).toBeDefined();
      const validate = validatorProvider.getValidator(
        definition.outputSchema! as JsonSchemaType,
      );
      const validation = validate(result.structuredContent);
      expect(
        validation.valid,
        `${definition.name}: ${validation.errorMessage}`,
      ).toBe(true);
    }
  });

  it("routes a sceneview-mcp call to the sceneview library", async () => {
    const result = await dispatch("get_migration_guide", undefined);
    expect(result.isError).toBeFalsy();
    expect(result.content[0]?.type).toBe("text");
    expect(result.content[0]?.text.length).toBeGreaterThan(0);
  });

  it("routes a call needing args", async () => {
    const result = await dispatch("get_setup", { type: "3d" });
    expect(result.isError).toBeFalsy();
    expect(result.content[0]?.text).toMatch(/sceneview/i);
  });

  it("routes an automotive call", async () => {
    const result = await dispatch("list_car_models", {});
    expect(result.isError).toBeFalsy();
    expect(result.content[0]?.text.length).toBeGreaterThan(0);
  });

  it("routes a gaming call", async () => {
    const result = await dispatch("list_game_models", {});
    expect(result.isError).toBeFalsy();
    expect(result.content[0]?.text.length).toBeGreaterThan(0);
  });

  it("routes a healthcare call", async () => {
    const result = await dispatch("list_medical_models", {});
    expect(result.isError).toBeFalsy();
    expect(result.content[0]?.text.length).toBeGreaterThan(0);
  });

  it("routes an interior call", async () => {
    const result = await dispatch("list_furniture_models", {});
    expect(result.isError).toBeFalsy();
    expect(result.content[0]?.text.length).toBeGreaterThan(0);
  });

  it("returns an error result for unknown tool names", async () => {
    const result = await dispatch("does_not_exist", {});
    expect(result.isError).toBe(true);
    expect(result.content[0]?.text.toLowerCase()).toContain("unknown tool");
  });

  it("accepts a dispatch context", async () => {
    const result = await dispatch(
      "get_setup",
      { type: "ar" },
      { userId: "usr_test", tier: "pro" },
    );
    expect(result.isError).toBeFalsy();
    expect(result.content[0]?.text).toMatch(/ar/i);
  });
});
