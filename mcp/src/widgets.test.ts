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
  readWidgetResource,
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
