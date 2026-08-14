import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { InMemoryTransport } from "@modelcontextprotocol/sdk/inMemory.js";
import { Server } from "@modelcontextprotocol/sdk/server/index.js";
import { CallToolRequestSchema, ListToolsRequestSchema } from "@modelcontextprotocol/sdk/types.js";
import { afterAll, beforeAll, describe, expect, it } from "vitest";
import {
  COLLISION_GUIDE,
  MATERIAL_GUIDE,
  MODEL_OPTIMIZATION_GUIDE,
  WEB_RENDERING_GUIDE,
} from "./extra-guides.js";

// ─── Disclaimer (same as index.ts) ──────────────────────────────────────────

const DISCLAIMER =
  "\n\n---\n*Generated code suggestion. Review before use in production. See [TERMS.md](https://github.com/sceneview/sceneview/blob/main/mcp/TERMS.md).*";

function withDisclaimer<T extends { type: string; text: string }>(content: T[]): T[] {
  if (content.length === 0) return content;
  const last = content[content.length - 1];
  return [...content.slice(0, -1), { ...last, text: last.text + DISCLAIMER }];
}

// ─── Tool definitions ───────────────────────────────────────────────────────

const TOOL_CONFIGS: Record<string, { guide: string; description: string }> = {
  get_material_guide: {
    guide: MATERIAL_GUIDE,
    description: "Returns a comprehensive guide for PBR materials in SceneView.",
  },
  get_collision_guide: {
    guide: COLLISION_GUIDE,
    description: "Returns a comprehensive guide for collision detection, hit testing, and physics.",
  },
  get_model_optimization_guide: {
    guide: MODEL_OPTIMIZATION_GUIDE,
    description: "Returns a complete guide for optimizing 3D models for SceneView.",
  },
  get_web_rendering_guide: {
    guide: WEB_RENDERING_GUIDE,
    description: "Returns a comprehensive guide for SceneView Web (Filament.js WASM).",
  },
};

// ─── Server + client setup ──────────────────────────────────────────────────

let client: Client;
let server: Server;
let clientTransport: InMemoryTransport;
let serverTransport: InMemoryTransport;

beforeAll(async () => {
  server = new Server(
    { name: "sceneview-mcp-test", version: "0.0.1" },
    { capabilities: { tools: {} } }
  );

  // Register tools list
  server.setRequestHandler(ListToolsRequestSchema, async () => ({
    tools: Object.entries(TOOL_CONFIGS).map(([name, cfg]) => ({
      name,
      description: cfg.description,
      inputSchema: { type: "object" as const, properties: {}, required: [] },
    })),
  }));

  // Register call handler — mirrors the switch cases in index.ts
  server.setRequestHandler(CallToolRequestSchema, async (request) => {
    const toolName = request.params.name;
    const cfg = TOOL_CONFIGS[toolName];
    if (cfg) {
      return { content: withDisclaimer([{ type: "text", text: cfg.guide }]) };
    }
    return {
      content: [{ type: "text", text: `Unknown tool: ${toolName}` }],
      isError: true,
    };
  });

  [clientTransport, serverTransport] = InMemoryTransport.createLinkedPair();

  await server.connect(serverTransport);

  client = new Client({ name: "test-client", version: "0.0.1" });
  await client.connect(clientTransport);
});

afterAll(async () => {
  await client.close();
  await server.close();
});

// ─── Helper ─────────────────────────────────────────────────────────────────

async function callTool(name: string, args: Record<string, unknown> = {}) {
  return client.callTool({ name, arguments: args });
}

// ─── Integration tests ──────────────────────────────────────────────────────

describe("Extra guide tools — integration via MCP handler", () => {
  // ── Tool listing ────────────────────────────────────────────────────────

  it("lists all 4 extra guide tools", async () => {
    const result = await client.listTools();
    const names = result.tools.map((t) => t.name);
    expect(names).toContain("get_material_guide");
    expect(names).toContain("get_collision_guide");
    expect(names).toContain("get_model_optimization_guide");
    expect(names).toContain("get_web_rendering_guide");
  });

  // ── get_material_guide ──────────────────────────────────────────────────

  describe("get_material_guide", () => {
    it("returns a text response with PBR material content", async () => {
      const result = await callTool("get_material_guide");
      const text = (result.content as Array<{ type: string; text: string }>)[0].text;
      expect(text).toContain("PBR Material");
      expect(text).toContain("baseColor");
      expect(text).toContain("metallic");
      expect(text).toContain("roughness");
    });

    it("includes material recipes", async () => {
      const result = await callTool("get_material_guide");
      const text = (result.content as Array<{ type: string; text: string }>)[0].text;
      expect(text).toContain("Glass");
      expect(text).toContain("Chrome");
      expect(text).toContain("Gold");
      expect(text).toContain("Car Paint");
    });

    it("includes Kotlin code samples", async () => {
      const result = await callTool("get_material_guide");
      const text = (result.content as Array<{ type: string; text: string }>)[0].text;
      expect(text).toContain("@Composable");
      expect(text).toContain("setBaseColor");
    });

    it("appends the disclaimer", async () => {
      const result = await callTool("get_material_guide");
      const text = (result.content as Array<{ type: string; text: string }>)[0].text;
      expect(text).toContain("TERMS.md");
      expect(text).toContain("Generated code suggestion");
    });

    it("returns exactly one content block", async () => {
      const result = await callTool("get_material_guide");
      expect((result.content as Array<unknown>).length).toBe(1);
    });
  });

  // ── get_collision_guide ─────────────────────────────────────────────────

  describe("get_collision_guide", () => {
    it("returns collision and hit testing content", async () => {
      const result = await callTool("get_collision_guide");
      const text = (result.content as Array<{ type: string; text: string }>)[0].text;
      expect(text).toContain("Hit Testing");
      expect(text).toContain("onTouchEvent");
      expect(text).toContain("isTouchable");
    });

    it("includes KMP collision primitives", async () => {
      const result = await callTool("get_collision_guide");
      const text = (result.content as Array<{ type: string; text: string }>)[0].text;
      expect(text).toContain("Ray");
      expect(text).toContain("Box");
      expect(text).toContain("Sphere");
      expect(text).toContain("Intersections");
    });

    it("includes physics section", async () => {
      const result = await callTool("get_collision_guide");
      const text = (result.content as Array<{ type: string; text: string }>)[0].text;
      expect(text).toContain("PhysicsWorld");
      expect(text).toContain("RigidBody");
    });

    it("appends the disclaimer", async () => {
      const result = await callTool("get_collision_guide");
      const text = (result.content as Array<{ type: string; text: string }>)[0].text;
      expect(text).toContain("TERMS.md");
      expect(text).toContain("Generated code suggestion");
    });
  });

  // ── get_model_optimization_guide ────────────────────────────────────────

  describe("get_model_optimization_guide", () => {
    it("returns optimization content with polygon budgets", async () => {
      const result = await callTool("get_model_optimization_guide");
      const text = (result.content as Array<{ type: string; text: string }>)[0].text;
      expect(text).toContain("Polygon Budgets");
      expect(text).toContain("triangles");
      expect(text).toContain("High-end");
      expect(text).toContain("Mid-range");
    });

    it("includes compression techniques", async () => {
      const result = await callTool("get_model_optimization_guide");
      const text = (result.content as Array<{ type: string; text: string }>)[0].text;
      expect(text).toContain("Draco");
      expect(text).toContain("KTX2");
      expect(text).toContain("gltf-transform");
    });

    it("includes texture optimization section", async () => {
      const result = await callTool("get_model_optimization_guide");
      const text = (result.content as Array<{ type: string; text: string }>)[0].text;
      expect(text).toContain("Texture Optimization");
      expect(text).toContain("1024x1024");
    });

    it("includes LOD strategy", async () => {
      const result = await callTool("get_model_optimization_guide");
      const text = (result.content as Array<{ type: string; text: string }>)[0].text;
      expect(text).toContain("LOD");
      expect(text).toContain("distanceToCamera");
    });

    it("appends the disclaimer", async () => {
      const result = await callTool("get_model_optimization_guide");
      const text = (result.content as Array<{ type: string; text: string }>)[0].text;
      expect(text).toContain("TERMS.md");
      expect(text).toContain("Generated code suggestion");
    });
  });

  // ── get_web_rendering_guide ─────────────────────────────────────────────

  describe("get_web_rendering_guide", () => {
    it("returns Filament.js web rendering content", async () => {
      const result = await callTool("get_web_rendering_guide");
      const text = (result.content as Array<{ type: string; text: string }>)[0].text;
      expect(text).toContain("Filament.js");
      expect(text).toContain("WebAssembly");
      expect(text).toContain("WebGL2");
    });

    it("includes sceneview.js quick start", async () => {
      const result = await callTool("get_web_rendering_guide");
      const text = (result.content as Array<{ type: string; text: string }>)[0].text;
      expect(text).toContain("sceneview.js");
      expect(text).toContain("modelViewer");
    });

    it("includes quality settings", async () => {
      const result = await callTool("get_web_rendering_guide");
      const text = (result.content as Array<{ type: string; text: string }>)[0].text;
      expect(text).toContain("SSAO");
      expect(text).toContain("Bloom");
      expect(text).toContain("TAA");
    });

    it("includes Filament.js vs model-viewer comparison", async () => {
      const result = await callTool("get_web_rendering_guide");
      const text = (result.content as Array<{ type: string; text: string }>)[0].text;
      expect(text).toContain("model-viewer");
      expect(text).toContain("Procedural geometry");
    });

    it("appends the disclaimer", async () => {
      const result = await callTool("get_web_rendering_guide");
      const text = (result.content as Array<{ type: string; text: string }>)[0].text;
      expect(text).toContain("TERMS.md");
      expect(text).toContain("Generated code suggestion");
    });
  });

  // ── Error case: unknown tool ────────────────────────────────────────────

  describe("unknown tool", () => {
    it("returns an error for an unknown tool name", async () => {
      const result = await callTool("get_nonexistent_guide");
      const text = (result.content as Array<{ type: string; text: string }>)[0].text;
      expect(text).toContain("Unknown tool");
      expect(text).toContain("get_nonexistent_guide");
      expect(result.isError).toBe(true);
    });
  });

  // ── Structural checks across all tools ─────────────────────────────────

  describe("structural checks", () => {
    const toolNames = [
      "get_material_guide",
      "get_collision_guide",
      "get_model_optimization_guide",
      "get_web_rendering_guide",
    ];

    it.each(toolNames)("%s returns content with type 'text'", async (toolName) => {
      const result = await callTool(toolName);
      const content = result.content as Array<{ type: string; text: string }>;
      expect(content).toHaveLength(1);
      expect(content[0].type).toBe("text");
      expect(content[0].text.length).toBeGreaterThan(200);
    });

    it.each(toolNames)("%s does not flag isError", async (toolName) => {
      const result = await callTool(toolName);
      expect(result.isError).toBeUndefined();
    });

    it.each(toolNames)("%s ends with the disclaimer", async (toolName) => {
      const result = await callTool(toolName);
      const text = (result.content as Array<{ type: string; text: string }>)[0].text;
      expect(text).toMatch(/TERMS\.md.*\*$/s);
    });
  });
});
