/**
 * The shared MCP `Server` as both transports build it — specifically the MCP
 * Apps negotiation, which the HTTP gateway honoured and the stdio server did
 * not (#3485).
 *
 * These tests drive a real SDK `Client` against `createSceneViewServer()` over
 * a linked in-memory transport pair, so the client capabilities travel through
 * a genuine `initialize` and the server reads them back exactly as it would
 * from a host over stdio.
 */

import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { InMemoryTransport } from "@modelcontextprotocol/sdk/inMemory.js";
import type { ClientCapabilities } from "@modelcontextprotocol/sdk/types.js";
import { describe, expect, it } from "vitest";
import { buildDiscoverResult, SERVER_INSTRUCTIONS } from "./discover.js";
import { createSceneViewServer } from "./server.js";
import {
  MCP_APP_MIME_TYPE,
  UI_EXTENSION_ID,
  WIDGET_3D_VIEWER_URI,
  WIDGET_SHOW_IN_SPACE_URI,
} from "./widgets.js";

/** A connected client/server pair over in-memory transports. */
async function connect(capabilities: ClientCapabilities): Promise<Client> {
  const server = createSceneViewServer({ surface: "stdio" });
  const client = new Client({ name: "test-host", version: "0.0.1" }, { capabilities });
  const [clientTransport, serverTransport] = InMemoryTransport.createLinkedPair();
  await Promise.all([server.connect(serverTransport), client.connect(clientTransport)]);
  return client;
}

/** Client capabilities declaring the MCP Apps extension with `mimeTypes`. */
function uiCapabilities(mimeTypes: string[]): ClientCapabilities {
  return { extensions: { [UI_EXTENSION_ID]: { mimeTypes } } } as ClientCapabilities;
}

type ListedTool = { name: string; _meta?: { ui?: { resourceUri?: string } } };
type CallResult = { _meta?: { ui?: { resourceUri?: string } }; content?: unknown[] };

const VIEWER_ARGS = { modelUrl: "https://example.com/chair.glb" };

describe("stdio server: MCP Apps mime negotiation (#3485)", () => {
  it("withholds the widget pointer from a client whose mime types exclude ours", async () => {
    const client = await connect(uiCapabilities(["text/uri-list"]));

    const { tools } = (await client.listTools()) as { tools: ListedTool[] };
    const viewer = tools.find((t) => t.name === "view_3d_model");
    // The tool stays listed and callable — only the UI pointer is withheld,
    // which is the graceful degradation the extension spec asks for.
    expect(viewer).toBeDefined();
    expect(viewer?._meta?.ui?.resourceUri).toBeUndefined();

    const result = (await client.callTool({
      name: "view_3d_model",
      arguments: VIEWER_ARGS,
    })) as CallResult;
    // Declaration and result must agree, or a host that discovers widgets
    // from results still gets one it just said it cannot render.
    expect(result._meta?.ui?.resourceUri).toBeUndefined();
    expect(result.content).toBeDefined();
  });

  it("serves the widget to a client that declared our mime type", async () => {
    const client = await connect(uiCapabilities([MCP_APP_MIME_TYPE]));
    const { tools } = (await client.listTools()) as { tools: ListedTool[] };
    expect(tools.find((t) => t.name === "view_3d_model")?._meta?.ui?.resourceUri).toBe(
      WIDGET_3D_VIEWER_URI
    );
    const result = (await client.callTool({
      name: "view_3d_model",
      arguments: VIEWER_ARGS,
    })) as CallResult;
    expect(result._meta?.ui?.resourceUri).toBe(WIDGET_3D_VIEWER_URI);
  });

  it("keeps the widget for a client that declared no extension at all", async () => {
    // ChatGPT today declares nothing and drives the widget off the `openai/*`
    // keys: gating on silence would dark-ship the live listing (#3192).
    const client = await connect({});
    const { tools } = (await client.listTools()) as { tools: ListedTool[] };
    expect(tools.find((t) => t.name === "view_3d_model")?._meta?.ui?.resourceUri).toBe(
      WIDGET_3D_VIEWER_URI
    );
    const result = (await client.callTool({
      name: "view_3d_model",
      arguments: VIEWER_ARGS,
    })) as CallResult;
    expect(result._meta?.ui?.resourceUri).toBe(WIDGET_3D_VIEWER_URI);
  });

  it("leaves every non-widget tool declaration untouched by the negotiation", async () => {
    const strict = await connect(uiCapabilities([MCP_APP_MIME_TYPE]));
    const degraded = await connect(uiCapabilities(["text/uri-list"]));
    const a = ((await strict.listTools()) as { tools: ListedTool[] }).tools;
    const b = ((await degraded.listTools()) as { tools: ListedTool[] }).tools;
    expect(b.map((t) => t.name)).toEqual(a.map((t) => t.name));
    const widgetless = a.filter((t) => !t._meta?.ui?.resourceUri).map((t) => t.name);
    for (const name of widgetless) {
      expect(b.find((t) => t.name === name)).toEqual(a.find((t) => t.name === name));
    }
  });
});

describe("stdio server: instructions at connect time", () => {
  it("sends the server instructions in the initialize result", async () => {
    const client = await connect({});
    expect(client.getInstructions()).toBe(SERVER_INSTRUCTIONS);
    expect(buildDiscoverResult().instructions).toBe(SERVER_INSTRUCTIONS);
  });

  it("names only tools and resources this server really serves", async () => {
    const client = await connect({});
    const { tools } = (await client.listTools()) as { tools: ListedTool[] };
    const { resources } = await client.listResources();
    const toolNames = new Set(tools.map((t) => t.name));
    const uris = new Set(resources.map((r) => r.uri));

    const quoted = [...SERVER_INSTRUCTIONS.matchAll(/`([^`]+)`/g)].map((m) => m[1]);
    const referencedTools = quoted.filter((q) => /^[a-z]+(_[a-z0-9]+)+$/.test(q));
    const referencedUris = quoted.filter((q) => q.includes("://"));
    expect(referencedTools.length).toBeGreaterThanOrEqual(5);
    for (const name of referencedTools) expect(toolNames, name).toContain(name);
    for (const uri of referencedUris) expect(uris, uri).toContain(uri);
  });
});

describe("show_in_space MCP resource delivery", () => {
  it("serves the registered widget over the real MCP protocol", async () => {
    const client = await connect(uiCapabilities([MCP_APP_MIME_TYPE]));
    try {
      const { resources } = await client.listResources();
      expect(resources.some((r) => r.uri === WIDGET_SHOW_IN_SPACE_URI)).toBe(true);
      const read = await client.readResource({ uri: WIDGET_SHOW_IN_SPACE_URI });
      expect(read.contents[0]).toMatchObject({
        uri: WIDGET_SHOW_IN_SPACE_URI,
        mimeType: MCP_APP_MIME_TYPE,
        text: expect.stringContaining("data.sizeCm[0] / 100"),
      });
      const result = await client.callTool({
        name: "show_in_space",
        arguments: { widthCm: 80, heightCm: 75, depthCm: 40, label: "Desk" },
      });
      expect(result._meta).toMatchObject({ ui: { resourceUri: WIDGET_SHOW_IN_SPACE_URI } });
    } finally {
      await client.close();
    }
  });
  it("keeps text unchanged when the host declines widgets", async () => {
    const client = await connect(uiCapabilities(["text/uri-list"]));
    try {
      const { tools } = await client.listTools();
      expect(tools.find((t) => t.name === "show_in_space")?._meta?.ui).toBeUndefined();
      const result = await client.callTool({
        name: "show_in_space",
        arguments: { widthCm: 80, heightCm: 75, depthCm: 40, label: "Desk" },
      });
      expect(result._meta?.ui).toBeUndefined();
      expect(result.content).toEqual([
        {
          type: "text",
          text: expect.stringContaining(
            "Desk: 80 × 75 × 40 cm — https://sceneview.github.io/open?demo=spatial-answer&answer="
          ),
        },
      ]);
    } finally {
      await client.close();
    }
  });
});
