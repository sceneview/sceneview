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
import { createSceneViewServer } from "./server.js";
import { MCP_APP_MIME_TYPE, UI_EXTENSION_ID, WIDGET_3D_VIEWER_URI } from "./widgets.js";

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
