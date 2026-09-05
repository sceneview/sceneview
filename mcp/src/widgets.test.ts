/**
 * The MCP Apps widget resource: URI, mime type, CSP metadata, and the
 * payload-source order baked into the widget script.
 */

import { describe, expect, it } from "vitest";
import { LATEST_SCENEVIEW_RELEASE } from "./generated/version.js";
import { getToolTier } from "./tiers.js";
import { dispatchTool, TOOL_DEFINITIONS } from "./tools/index.js";
import {
  listWidgetResources,
  MCP_APP_MIME_TYPE,
  readUiExtension,
  readWidgetResource,
  serveWidgetsTo,
  UI_EXTENSION_ID,
  uiExtensionSettings,
  WIDGET_3D_VIEWER_HTML,
  WIDGET_3D_VIEWER_URI,
  WIDGET_UI_META,
} from "./widgets.js";

describe("widget resource registry", () => {
  it("lists the 3D viewer with the MCP Apps mime type and _meta.ui", () => {
    const resources = listWidgetResources();
    const viewer = resources.find((r) => r.uri === WIDGET_3D_VIEWER_URI);
    expect(viewer).toBeDefined();
    expect(viewer?.uri).toBe("ui://widget/3d-viewer.html");
    // The current MCP Apps value — NOT the older `text/html+skybridge`.
    expect(viewer?.mimeType).toBe("text/html;profile=mcp-app");
    expect(MCP_APP_MIME_TYPE).toBe("text/html;profile=mcp-app");
    expect(viewer?._meta.ui).toEqual(WIDGET_UI_META);
  });

  it("declares the CSP the host needs: SceneView CDN + the model hosts", () => {
    expect(WIDGET_UI_META.prefersBorder).toBe(true);
    expect(WIDGET_UI_META.csp.resourceDomains).toEqual(["https://sceneview.github.io"]);
    expect(WIDGET_UI_META.csp.connectDomains).toEqual([
      "https://sceneview.github.io",
      "https://raw.githubusercontent.com",
      "https://modelviewer.dev",
      "https://media.sketchfab.com",
      "https://cdn.jsdelivr.net",
      "https://arcamera-api.mcp-tools-lab.workers.dev",
    ]);
  });

  it("reads the 3D viewer back with the same _meta and the HTML", () => {
    const contents = readWidgetResource(WIDGET_3D_VIEWER_URI);
    expect(contents).not.toBeNull();
    expect(contents?.mimeType).toBe(MCP_APP_MIME_TYPE);
    expect(contents?.text).toBe(WIDGET_3D_VIEWER_HTML);
    expect(contents?._meta.ui.csp.resourceDomains).toContain("https://sceneview.github.io");
  });

  it("returns null for an unknown widget URI", () => {
    expect(readWidgetResource("ui://widget/nope.html")).toBeNull();
    expect(readWidgetResource("sceneview://api")).toBeNull();
  });
});

describe("3D viewer widget HTML", () => {
  it("pins SceneView.js and Filament.js to the released SDK version", () => {
    expect(WIDGET_3D_VIEWER_HTML).toContain(
      `src="https://sceneview.github.io/js/sceneview.js?v=${LATEST_SCENEVIEW_RELEASE}"`
    );
    expect(WIDGET_3D_VIEWER_HTML).toContain(
      `src="https://sceneview.github.io/js/filament/filament.js?v=${LATEST_SCENEVIEW_RELEASE}"`
    );
    // Every script tag on the page comes from the one CSP resource domain.
    const srcs = [...WIDGET_3D_VIEWER_HTML.matchAll(/<script src="([^"]+)"/g)].map((m) => m[1]);
    expect(srcs.length).toBeGreaterThan(0);
    for (const src of srcs) expect(src.startsWith("https://sceneview.github.io/")).toBe(true);
  });

  it("reads the payload from the bridge, then window.openai, then the query string", () => {
    const html = WIDGET_3D_VIEWER_HTML;
    const bridge = html.indexOf('"ui/notifications/tool-result"');
    const toolOutput = html.indexOf("o.toolOutput");
    const legacy = html.indexOf("o.structuredContent");
    const query = html.indexOf('p.get("modelUrl")');
    for (const idx of [bridge, toolOutput, legacy, query]) expect(idx).toBeGreaterThan(-1);
    expect(bridge).toBeLessThan(toolOutput);
    expect(toolOutput).toBeLessThan(legacy);
    expect(legacy).toBeLessThan(query);
    // The bridge handshake is the MCP Apps one.
    expect(html).toContain('method: "ui/initialize"');
    expect(html).toContain('"ui/notifications/initialized"');
  });

  it("renders with SceneView.modelViewer and an absolute IBL", () => {
    expect(WIDGET_3D_VIEWER_HTML).toContain("SceneView.modelViewer(canvas, data.modelUrl");
    expect(WIDGET_3D_VIEWER_HTML).toContain(
      'IBL_URL = "https://sceneview.github.io/environments/neutral_ibl.ktx"'
    );
  });
});

describe("view_3d_model tool", () => {
  const def = TOOL_DEFINITIONS.find((t) => t.name === "view_3d_model");

  it("is declared, free, and bound to the widget on the declaration", () => {
    expect(def).toBeDefined();
    expect(getToolTier("view_3d_model")).toBe("free");
    expect(def?.inputSchema.required).toEqual(["modelUrl"]);
    expect(def?.annotations).toMatchObject({
      readOnlyHint: true,
      openWorldHint: true,
      destructiveHint: false,
    });
    expect(def?._meta).toEqual({
      ui: { resourceUri: "ui://widget/3d-viewer.html" },
      "openai/outputTemplate": "ui://widget/3d-viewer.html",
      "openai/toolInvocation/invoking": "Rendering 3D model with SceneView",
      "openai/toolInvocation/invoked": "3D model rendered",
    });
  });

  it("returns structuredContent + the widget pointer on the result", async () => {
    const result = await dispatchTool("view_3d_model", {
      modelUrl: "https://sceneview.github.io/models/Astronaut.glb",
      title: "Astronaut",
      autoRotate: false,
    });
    expect(result.isError).toBeFalsy();
    expect(result.structuredContent).toEqual({
      modelUrl: "https://sceneview.github.io/models/Astronaut.glb",
      title: "Astronaut",
      autoRotate: false,
      ar: true,
      alt: "Astronaut",
    });
    expect(result._meta).toEqual({ ui: { resourceUri: "ui://widget/3d-viewer.html" } });
    expect(result.content[0]?.text).toContain("Astronaut");
    expect(result.content[0]?.text).toContain("AR mode");
  });

  it("keeps posterUrl only when given (outputSchema forbids extra keys)", async () => {
    const result = await dispatchTool("view_3d_model", {
      modelUrl: "https://example.com/chair.glb",
      posterUrl: "https://example.com/chair.webp",
    });
    expect(result.structuredContent).toMatchObject({
      posterUrl: "https://example.com/chair.webp",
      title: "3D model",
    });
    expect("posterUrl" in (result.structuredContent ?? {})).toBe(true);
    const without = await dispatchTool("view_3d_model", { modelUrl: "https://example.com/x.glb" });
    expect("posterUrl" in (without.structuredContent ?? {})).toBe(false);
  });

  it("refuses a missing modelUrl", async () => {
    const result = await dispatchTool("view_3d_model", {});
    expect(result.isError).toBe(true);
    expect(result.content[0]?.text).toContain("modelUrl");
    expect(result.structuredContent).toBeUndefined();
  });
});

describe("MCP Apps extension negotiation (#3192)", () => {
  it("uses the spec's extension identifier", () => {
    // SEP-1724 / ext-apps 2026-01-26. A typo here is invisible at runtime —
    // the host simply never sees a widget — so it is pinned literally.
    expect(UI_EXTENSION_ID).toBe("io.modelcontextprotocol/ui");
  });

  it("advertises the mime type it actually serves", () => {
    const settings = uiExtensionSettings();
    expect(settings.mimeTypes).toEqual([MCP_APP_MIME_TYPE]);
    // The value the widget resource is really served with, so the declaration
    // and the resource cannot promise different content types.
    expect(settings.mimeTypes).toContain(readWidgetResource(WIDGET_3D_VIEWER_URI)?.mimeType);
  });

  it("hands out a fresh settings object each call", () => {
    uiExtensionSettings().mimeTypes.push("text/plain");
    expect(uiExtensionSettings().mimeTypes).toEqual([MCP_APP_MIME_TYPE]);
  });

  it("reads a peer's declaration out of its capabilities", () => {
    const settings = readUiExtension({
      roots: {},
      extensions: { [UI_EXTENSION_ID]: { mimeTypes: [MCP_APP_MIME_TYPE] } },
    });
    expect(settings).toEqual({ mimeTypes: [MCP_APP_MIME_TYPE] });
  });

  it("returns null when the peer named no extension", () => {
    expect(readUiExtension(undefined)).toBeNull();
    expect(readUiExtension({})).toBeNull();
    expect(readUiExtension({ extensions: {} })).toBeNull();
    expect(readUiExtension({ extensions: { "io.modelcontextprotocol/tasks": {} } })).toBeNull();
  });

  it("survives a malformed declaration instead of throwing", () => {
    expect(readUiExtension({ extensions: { [UI_EXTENSION_ID]: {} } })).toEqual({ mimeTypes: [] });
    expect(readUiExtension({ extensions: { [UI_EXTENSION_ID]: { mimeTypes: "html" } } })).toEqual({
      mimeTypes: [],
    });
    expect(readUiExtension({ extensions: { [UI_EXTENSION_ID]: { mimeTypes: [1, "a"] } } })).toEqual(
      {
        mimeTypes: ["a"],
      }
    );
    expect(readUiExtension("nonsense")).toBeNull();
  });

  it("keeps serving widgets to a peer that declared nothing", () => {
    // ChatGPT today: no `extensions` block, drives the widget off `openai/*`.
    // Gating on silence would dark-ship the live listing.
    expect(serveWidgetsTo(null)).toBe(true);
    expect(serveWidgetsTo(undefined)).toBe(true);
    expect(serveWidgetsTo({ mimeTypes: [] })).toBe(true);
  });

  it("serves widgets to a peer that negotiated our mime type", () => {
    expect(serveWidgetsTo({ mimeTypes: [MCP_APP_MIME_TYPE] })).toBe(true);
    expect(serveWidgetsTo({ mimeTypes: ["text/uri-list", MCP_APP_MIME_TYPE] })).toBe(true);
  });

  it("degrades to text only when the peer excluded our mime type itself", () => {
    expect(serveWidgetsTo({ mimeTypes: ["text/uri-list"] })).toBe(false);
    // `text/html+skybridge` is the WITHDRAWN OpenAI spelling, not ours (#3189).
    expect(serveWidgetsTo({ mimeTypes: ["text/html+skybridge"] })).toBe(false);
  });
});
