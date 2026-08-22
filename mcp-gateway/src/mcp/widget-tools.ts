/**
 * Gateway-native MCP tools that return OpenAI Apps SDK widgets.
 *
 * These do NOT live in the upstream `mcp/` packages because:
 * 1. They are tightly coupled to the `widgets.ts` registry (resource URIs).
 * 2. They are only meaningful when used through the hosted HTTP gateway —
 *    the stdio npm package does not host widget HTML at all.
 * 3. The OpenAI Apps SDK contract (`_meta.ui.resourceUri`, `structuredContent`)
 *    is gateway-side; the upstream packages stay transport-neutral.
 *
 * The library plugs into `registry.ts` exactly like the five vertical
 * libraries — same `TOOL_DEFINITIONS` + `dispatchTool` signature, so the
 * tier gate, rate limit, and usage logging in `routes/mcp.ts` apply
 * automatically.
 */

import type {
  DispatchContext,
  ToolDefinition,
  ToolResult,
} from "./types.js";

/**
 * Tool definitions for the widget-bearing tools.
 *
 * `view_3d_model` is intentionally FREE so anonymous callers on
 * `/mcp/public` can also see the widget — that's the path ChatGPT users
 * will hit when they install the connector. Pro variants (e.g. an AR
 * session viewer) can be added later as paid-tier widgets.
 */
export const TOOL_DEFINITIONS: ToolDefinition[] = [
  {
    name: "view_3d_model",
    description:
      "Render an interactive 3D model viewer inline in ChatGPT. Pass a public GLB / GLTF URL and the assistant will display it in a SceneView-branded widget with orbit controls, auto-rotate, and AR mode (where supported). Use this when the user asks to PREVIEW a 3D model, asks 'what does X look like in 3D?', or after `search_models` to render the chosen result.",
    inputSchema: {
      type: "object",
      properties: {
        modelUrl: {
          type: "string",
          description:
            "Public HTTPS URL to a .glb or .gltf 3D model file. Must be CORS-enabled. Works with assets from sceneview.github.io, model-viewer-textured CDN, Sketchfab download URLs, or any CORS-friendly host.",
        },
        title: {
          type: "string",
          description: "Optional title shown above the viewer (e.g. the model name).",
        },
        autoRotate: {
          type: "boolean",
          description:
            "Whether the model should slowly auto-rotate when idle. Default: true.",
        },
        ar: {
          type: "boolean",
          description:
            "Whether to expose the AR button on supported mobile devices. Default: true.",
        },
        alt: {
          type: "string",
          description:
            "Accessibility text describing what the model represents.",
        },
        posterUrl: {
          type: "string",
          description:
            "Optional preview image URL shown while the model is loading.",
        },
      },
      required: ["modelUrl"],
    },
    outputSchema: {
      type: "object",
      properties: {
        modelUrl: { type: "string" },
        title: { type: "string" },
        autoRotate: { type: "boolean" },
        ar: { type: "boolean" },
        alt: { type: "string" },
        posterUrl: { type: "string" },
      },
      required: ["modelUrl", "title", "autoRotate", "ar", "alt"],
      additionalProperties: false,
    },
    annotations: {
      readOnlyHint: true,
      // The model URL is fetched by the user's browser inside the widget
      // iframe, not by the gateway server itself. Still, the widget pulls
      // a third-party asset, so we mark openWorldHint = true to be honest.
      openWorldHint: true,
      destructiveHint: false,
      title: "View 3D model",
    },
  },
];

/**
 * Marker the gateway transport looks for to attach the widget pointer
 * (`_meta.ui.resourceUri`) on both the `tools/list` declaration and the
 * JSON-RPC tool result. Kept here so the tool definition and the marker
 * live next to each other.
 */
export const WIDGET_TOOL_RESOURCE: Record<string, string> = {
  view_3d_model: "ui://widget/3d-viewer.html",
};

/** Returns the resource URI a tool should expose, or `null` if none. */
export function widgetResourceFor(toolName: string): string | null {
  return WIDGET_TOOL_RESOURCE[toolName] ?? null;
}

/** Dispatcher for widget-bearing tools. */
export async function dispatchTool(
  toolName: string,
  args: Record<string, unknown> | undefined,
  _ctx?: DispatchContext,
): Promise<ToolResult> {
  if (toolName === "view_3d_model") {
    const a = args ?? {};
    const modelUrl = typeof a.modelUrl === "string" ? a.modelUrl : "";
    if (!modelUrl) {
      return {
        content: [
          {
            type: "text",
            text:
              "view_3d_model requires a `modelUrl` argument (a public HTTPS URL to a .glb or .gltf file).",
          },
        ],
        isError: true,
      };
    }
    const title = typeof a.title === "string" ? a.title : "3D model";
    const autoRotate = a.autoRotate !== false;
    const ar = a.ar !== false;
    const alt = typeof a.alt === "string" ? a.alt : title;
    const posterUrl = typeof a.posterUrl === "string" ? a.posterUrl : undefined;

    // The `content` block carries a model-readable description so the
    // assistant has something to talk about even when the widget is
    // hidden (e.g. ChatGPT clients that don't render the iframe).
    const summary = `Loading 3D viewer for ${title} (${modelUrl}). ${
      ar ? "AR mode is available on supported mobile devices." : ""
    }`.trim();

    return {
      content: [{ type: "text", text: summary }],
      // structuredContent is what the widget reads via the MCP Apps bridge.
      // Adding it as a custom field on ToolResult — non-breaking because
      // every consumer treats unknown ToolResult keys as opaque.
      structuredContent: {
        modelUrl,
        title,
        autoRotate,
        ar,
        alt,
        ...(posterUrl === undefined ? {} : { posterUrl }),
      },
    };
  }

  return {
    content: [{ type: "text", text: `Unknown widget tool: ${toolName}` }],
    isError: true,
  };
}
