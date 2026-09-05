/**
 * MCP Apps widget registry — the SceneView 3D viewer served inline by hosts
 * such as ChatGPT (Apps SDK / Plugins Directory), Claude, and any MCP Apps
 * aware client.
 *
 * The wire contract (MCP Apps extension, `io.modelcontextprotocol/ui`):
 *
 * 1. The widget HTML is an MCP resource with a `ui://` URI
 *    (`ui://widget/3d-viewer.html`) and the mime type
 *    `text/html;profile=mcp-app`. Hosts fetch it via `resources/read` when
 *    a tool declaration or result points at it.
 * 2. The tool (`view_3d_model`, see `tools/definitions.ts`) carries
 *    `_meta.ui.resourceUri` on its DECLARATION so the host can prefetch the
 *    widget from `tools/list` alone, and again on the RESULT next to a
 *    `structuredContent` payload the widget renders.
 * 3. Inside the sandboxed iframe the widget reads the payload from the MCP
 *    Apps bridge (`ui/notifications/tool-result` over `postMessage`), falls
 *    back to ChatGPT's `window.openai.toolOutput` / legacy
 *    `window.openai.structuredContent`, and finally to query-string
 *    parameters so the page can be opened directly for a manual preview.
 *
 * The renderer is **SceneView.js + Filament.js**, the same WebGL2/WASM stack
 * that powers `sceneview-web` and the sceneview.github.io playground. Using a
 * generic third-party viewer here would defeat the purpose of a SceneView
 * listing, so the script tags are pinned to the released SDK version.
 *
 * This file used to live in the hosted gateway (`mcp-gateway/src/mcp/widgets.ts`,
 * deleted 2026-08-31). It now ships inside the npm package so the stdio server
 * and the Streamable HTTP server (`http.ts`) serve the exact same widget.
 */

import { LATEST_SCENEVIEW_RELEASE, PACKAGE_VERSION } from "./generated/version.js";

/** Canonical MCP Apps mime type for widget resources. */
export const MCP_APP_MIME_TYPE = "text/html;profile=mcp-app";

/**
 * Extension identifier under which MCP Apps is negotiated (SEP-1724, `ext-apps`
 * spec 2026-01-26).
 *
 * MCP Apps is an *extension*, not core protocol: a party that never names it in
 * `capabilities.extensions` has, as far as the other side can tell, no widget
 * support at all. Declaring the widget resource and hanging `_meta.ui` off tool
 * declarations is necessary but not sufficient — the extension has to be
 * advertised, or a spec-following host has no reason to look for either (#3192).
 */
export const UI_EXTENSION_ID = "io.modelcontextprotocol/ui";

/** Settings object for the MCP Apps extension, as declared by a party. */
export interface UiExtensionSettings {
  /** Content types this party can serve or render. REQUIRED by the spec. */
  mimeTypes: string[];
}

/**
 * The settings SceneView advertises under
 * `capabilities.extensions["io.modelcontextprotocol/ui"]`.
 *
 * A fresh object every call: the value is spread into handshake results that
 * callers are free to mutate, and a shared array would let one of them corrupt
 * every later handshake.
 */
export function uiExtensionSettings(): UiExtensionSettings {
  return { mimeTypes: [MCP_APP_MIME_TYPE] };
}

/**
 * Reads the peer's MCP Apps settings out of a `capabilities` object, or `null`
 * when it declared none.
 *
 * `null` means "did not say", NOT "does not support": hosts predating the
 * extension framework (ChatGPT today, which drives the widget off the
 * `openai/*` `_meta` keys) declare nothing and still render widgets. Callers
 * must treat `null` as unknown and keep their pre-extension behaviour — see
 * `serveWidgetsTo`.
 */
export function readUiExtension(capabilities: unknown): UiExtensionSettings | null {
  if (!capabilities || typeof capabilities !== "object") return null;
  const extensions = (capabilities as { extensions?: unknown }).extensions;
  if (!extensions || typeof extensions !== "object") return null;
  const settings = (extensions as Record<string, unknown>)[UI_EXTENSION_ID];
  if (!settings || typeof settings !== "object") return null;
  const mimeTypes = (settings as { mimeTypes?: unknown }).mimeTypes;
  return {
    mimeTypes: Array.isArray(mimeTypes) ? mimeTypes.filter((m) => typeof m === "string") : [],
  };
}

/**
 * Whether to attach widget pointers for a peer that declared `settings`.
 *
 * The spec asks servers to check client capabilities before advertising
 * UI-enabled tools and to degrade to text otherwise. The only case that
 * degrades here is the one the client stated itself: it negotiated MCP Apps
 * *and* listed mime types that exclude ours. Silence stays permissive, because
 * the live ChatGPT listing is silent and withholding the pointer from it would
 * turn a spec conformance fix into an outage.
 */
export function serveWidgetsTo(settings: UiExtensionSettings | null | undefined): boolean {
  if (!settings) return true;
  if (settings.mimeTypes.length === 0) return true;
  return settings.mimeTypes.includes(MCP_APP_MIME_TYPE);
}

/** Resource URI of the 3D viewer widget (`ui://widget/<name>.html` convention). */
export const WIDGET_3D_VIEWER_URI = "ui://widget/3d-viewer.html";

/**
 * MCP Apps `_meta.ui` block attached to the widget resource. `csp` is what the
 * host uses to build the iframe's Content-Security-Policy:
 *
 *  - `resourceDomains` — where `<script>`, styles, fonts and images may load
 *    from (SceneView.js, Filament.js and the neutral IBL all live on
 *    sceneview.github.io).
 *  - `connectDomains` — where `fetch`/XHR may go: the model URLs users are
 *    most likely to pass (SceneView sample assets, raw GitHub, model-viewer's
 *    public GLBs, Sketchfab downloads, jsDelivr) plus the AR camera API.
 */
export const WIDGET_UI_META = {
  prefersBorder: true,
  csp: {
    resourceDomains: ["https://sceneview.github.io"],
    connectDomains: [
      "https://sceneview.github.io",
      "https://raw.githubusercontent.com",
      "https://modelviewer.dev",
      "https://media.sketchfab.com",
      "https://cdn.jsdelivr.net",
      "https://arcamera-api.mcp-tools-lab.workers.dev",
    ],
  },
} as const;

/**
 * Full `_meta` for the widget resource: the MCP Apps `ui` block plus the
 * OpenAI Apps SDK spellings of the same two facts, for hosts that still read
 * the legacy keys. Both describe one policy, so they are derived from
 * `WIDGET_UI_META` rather than written twice.
 */
export const WIDGET_RESOURCE_META = {
  ui: WIDGET_UI_META,
  "openai/widgetPrefersBorder": WIDGET_UI_META.prefersBorder,
  "openai/widgetCSP": {
    resource_domains: WIDGET_UI_META.csp.resourceDomains,
    connect_domains: WIDGET_UI_META.csp.connectDomains,
  },
} as const;

const CDN_BASE = "https://sceneview.github.io";
const FILAMENT_JS_URL = `${CDN_BASE}/js/filament/filament.js?v=${LATEST_SCENEVIEW_RELEASE}`;
const SCENEVIEW_JS_URL = `${CDN_BASE}/js/sceneview.js?v=${LATEST_SCENEVIEW_RELEASE}`;
const NEUTRAL_IBL_URL = `${CDN_BASE}/environments/neutral_ibl.ktx`;

/**
 * The 3D model viewer widget served at `ui://widget/3d-viewer.html`.
 *
 * Reads a `structuredContent` payload of shape
 *   { modelUrl: string, title?: string, autoRotate?: boolean, ar?: boolean,
 *     alt?: string, posterUrl?: string }
 * from, in order: the MCP Apps bridge, `window.openai.toolOutput`,
 * `window.openai.structuredContent`, then the query string.
 *
 * With no `modelUrl` from any source the widget shows a placeholder with the
 * SceneView mark, so it never renders as an empty box.
 */
export const WIDGET_3D_VIEWER_HTML = `<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1" />
  <title>SceneView 3D Viewer</title>
  <style>
    *, *::before, *::after { box-sizing: border-box; margin: 0; padding: 0; }
    html, body { height: 100%; }
    body {
      font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", system-ui, sans-serif;
      background: #0d1117;
      color: #e6edf3;
      overflow: hidden;
    }
    #stage {
      width: 100%;
      height: 100vh;
      min-height: 400px;
      display: flex;
      flex-direction: column;
      background: linear-gradient(180deg, #0d1117 0%, #161b22 100%);
    }
    #header {
      flex: 0 0 auto;
      padding: 12px 16px 8px;
      display: flex;
      align-items: center;
      gap: 8px;
      border-bottom: 1px solid #21262d;
    }
    #header strong { font-size: 0.95rem; font-weight: 600; }
    #brand {
      font-size: 0.75rem;
      color: #7d8590;
      margin-left: auto;
    }
    #brand a { color: #58a6ff; text-decoration: none; }
    #canvas-wrap {
      flex: 1 1 auto;
      position: relative;
      width: 100%;
      min-height: 0;
    }
    #canvas {
      display: block;
      width: 100%;
      height: 100%;
      background: transparent;
      transition: opacity 0.4s ease;
    }
    #placeholder, #loader, #error {
      position: absolute;
      inset: 0;
      display: flex;
      align-items: center;
      justify-content: center;
      flex-direction: column;
      gap: 12px;
      padding: 40px 24px;
      text-align: center;
      color: #7d8590;
      pointer-events: none;
      background: linear-gradient(180deg, #0d1117 0%, #161b22 100%);
    }
    [hidden] { display: none !important; }
    #loader .spinner {
      width: 28px;
      height: 28px;
      border: 3px solid #30363d;
      border-top-color: #58a6ff;
      border-radius: 50%;
      animation: spin 0.8s linear infinite;
    }
    @keyframes spin { to { transform: rotate(360deg); } }
    #error { color: #f85149; }
    #placeholder svg { opacity: 0.5; }
    #footer {
      flex: 0 0 auto;
      padding: 6px 16px;
      font-size: 0.7rem;
      color: #6e7681;
      border-top: 1px solid #21262d;
      display: flex;
      gap: 12px;
    }
    .pill {
      background: #21262d;
      padding: 2px 8px;
      border-radius: 4px;
      font-size: 0.7rem;
    }
  </style>
  <script src="${FILAMENT_JS_URL}"></script>
  <script src="${SCENEVIEW_JS_URL}"></script>
</head>
<body>
  <div id="stage">
    <div id="header">
      <strong id="title">SceneView 3D Viewer</strong>
      <span id="brand">Powered by <a href="https://sceneview.github.io" target="_blank" rel="noopener">SceneView</a></span>
    </div>
    <div id="canvas-wrap">
      <canvas id="canvas" style="opacity:0" role="img" aria-label="3D model"></canvas>
      <div id="loader"><div class="spinner"></div><div>Loading SceneView renderer…</div></div>
      <div id="placeholder" hidden>
        <svg width="64" height="64" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
          <path d="M12 2 4 6v12l8 4 8-4V6l-8-4Z"/>
          <path d="m4 6 8 4 8-4"/>
          <path d="M12 22V10"/>
        </svg>
        <div>No 3D model URL provided</div>
        <div style="font-size:0.8rem">Pass <code style="background:#21262d;padding:2px 6px;border-radius:3px">modelUrl</code> in structuredContent or as a query parameter.</div>
      </div>
      <div id="error" hidden>
        <div>Could not load model</div>
        <div id="error-detail" style="font-size:0.75rem;color:#7d8590"></div>
      </div>
    </div>
    <div id="footer">
      <span class="pill" id="format">GLB</span>
      <span class="pill" id="engine">Filament.js · WebGL2 · WASM</span>
      <span style="margin-left:auto" id="hint">Drag to orbit · Scroll to zoom</span>
    </div>
  </div>
  <script>
    (function () {
      var WIDGET_VERSION = "${PACKAGE_VERSION}";
      var IBL_URL = "${NEUTRAL_IBL_URL}";

      var titleEl = document.getElementById("title");
      var loader = document.getElementById("loader");
      var canvas = document.getElementById("canvas");
      var placeholder = document.getElementById("placeholder");
      var errorEl = document.getElementById("error");
      var errorDetail = document.getElementById("error-detail");
      var formatEl = document.getElementById("format");

      // What is on screen right now, and the payload waiting for the renderer.
      var rendered = null;
      var pending = null;
      var viewer = null;

      // ── Payload sources, in priority order ───────────────────────────────
      //
      // 1. MCP Apps bridge: the host posts JSON-RPC over postMessage. The
      //    tool result arrives as \`ui/notifications/tool-result\` carrying the
      //    same \`structuredContent\` the tool returned.
      // 2. ChatGPT Apps SDK: \`window.openai.toolOutput\` (current) and
      //    \`window.openai.structuredContent\` (legacy, pre-MCP-Apps hosts).
      // 3. Query string (\`?modelUrl=…\`) for direct preview outside any host.

      function normalise(sc) {
        if (!sc || typeof sc !== "object") return null;
        if (typeof sc.modelUrl !== "string" || !sc.modelUrl) return null;
        return sc;
      }

      function post(message) {
        try { window.parent.postMessage(message, "*"); } catch (e) { /* no host */ }
      }

      var INIT_ID = 1;
      window.addEventListener("message", function (event) {
        var msg = event.data;
        if (!msg || typeof msg !== "object" || msg.jsonrpc !== "2.0") return;
        if (msg.method === "ui/notifications/tool-result" && msg.params) {
          var data = normalise(msg.params.structuredContent);
          if (data) render(data);
          return;
        }
        if (msg.id === INIT_ID && (msg.result || msg.error)) {
          post({ jsonrpc: "2.0", method: "ui/notifications/initialized" });
        }
      });
      if (window.parent && window.parent !== window) {
        post({
          jsonrpc: "2.0",
          id: INIT_ID,
          method: "ui/initialize",
          params: {
            protocolVersion: "2026-01-26",
            appInfo: { name: "sceneview-3d-viewer", version: WIDGET_VERSION },
            appCapabilities: {},
          },
        });
      }

      function fromOpenAI() {
        try {
          var o = window.openai;
          if (!o) return null;
          return normalise(o.toolOutput) || normalise(o.structuredContent) || null;
        } catch (e) { return null; }
      }
      window.addEventListener("openai:set_globals", function () {
        var data = fromOpenAI();
        if (data) render(data);
      });

      function fromQuery() {
        var p = new URLSearchParams(location.search);
        var modelUrl = p.get("modelUrl") || p.get("src");
        if (!modelUrl) return null;
        return {
          modelUrl: modelUrl,
          title: p.get("title") || undefined,
          autoRotate: p.get("autoRotate") !== "false",
          alt: p.get("alt") || undefined,
        };
      }

      // ── UI helpers ───────────────────────────────────────────────────────

      // Trim, cap length, and de-dupe accidental repetition like
      // "Ferrari F40Ferrari F40" caused by stacked navigations during preview.
      function cleanTitle(raw) {
        if (!raw) return "";
        var s = String(raw).trim();
        if (s.length > 60) s = s.slice(0, 60);
        var half = Math.floor(s.length / 2);
        if (half > 0 && s.slice(0, half) === s.slice(half, half * 2)) {
          s = s.slice(0, half).trim();
        }
        return s;
      }

      function showError(msg) {
        loader.hidden = true;
        placeholder.hidden = true;
        canvas.style.opacity = "0";
        errorDetail.textContent = msg;
        errorEl.hidden = false;
      }

      function showPlaceholder() {
        loader.hidden = true;
        errorEl.hidden = true;
        canvas.style.opacity = "0";
        placeholder.hidden = false;
      }

      function notifyReady() {
        try {
          if (window.openai && typeof window.openai.notifyReady === "function") {
            window.openai.notifyReady();
          } else {
            post({ type: "mcp-app/ready" });
          }
        } catch (e) { /* swallow */ }
      }

      // ── Rendering ────────────────────────────────────────────────────────

      function render(data) {
        if (rendered && rendered.modelUrl === data.modelUrl) return;
        pending = data;
        placeholder.hidden = true;
        errorEl.hidden = true;
        loader.hidden = false;
        titleEl.textContent = cleanTitle(data.title) || "SceneView 3D Viewer";
        canvas.setAttribute("aria-label", data.alt || data.title || "3D model");
        var ext = (data.modelUrl.split("?")[0].split(".").pop() || "GLB").toUpperCase();
        formatEl.textContent = ext === "GLTF" ? "glTF" : ext;
        whenSceneViewReady(function () { start(data); });
      }

      function start(data) {
        if (pending !== data) return; // superseded by a newer payload
        if (viewer && typeof viewer.loadModel === "function") {
          viewer.loadModel(data.modelUrl).then(function () { reveal(data); }).catch(fail);
          return;
        }
        SceneView.modelViewer(canvas, data.modelUrl, {
          backgroundColor: [0, 0, 0, 0],
          // Match the sceneview.github.io playground hero settings.
          lightIntensity: 150000,
          fov: 35,
          // An absolute IBL URL: a relative one resolves against the host's
          // sandbox origin, 404s, and leaves PBR materials almost black.
          iblUrl: IBL_URL,
        }).then(function (v) {
          viewer = v;
          reveal(data);
        }).catch(fail);
      }

      function reveal(data) {
        rendered = data;
        pending = null;
        loader.hidden = true;
        canvas.style.opacity = "1";
        if (data.autoRotate === false && viewer && viewer.setAutoRotate) {
          viewer.setAutoRotate(false);
        } else if (viewer && viewer.setAutoRotate) {
          viewer.setAutoRotate(true);
        }
        notifyReady();
      }

      function fail(err) {
        pending = null;
        showError((err && err.message) || String(err));
      }

      // SceneView.js registers its global after Filament's WASM ready
      // promise, so poll briefly for it before deciding the CDN failed.
      var readyCallbacks = [];
      var polling = false;
      function whenSceneViewReady(cb) {
        if (typeof SceneView !== "undefined" && SceneView.modelViewer) { cb(); return; }
        readyCallbacks.push(cb);
        if (polling) return;
        polling = true;
        var tries = 0;
        var maxTries = 60; // ~6 s at 100 ms intervals
        var timer = setInterval(function () {
          tries++;
          if (typeof SceneView !== "undefined" && SceneView.modelViewer) {
            clearInterval(timer);
            var cbs = readyCallbacks; readyCallbacks = [];
            cbs.forEach(function (fn) { fn(); });
          } else if (tries >= maxTries) {
            clearInterval(timer);
            polling = false;
            readyCallbacks = [];
            showError("Timed out loading SceneView.js + Filament.js from the CDN.");
          }
        }, 100);
      }

      // ── Boot ─────────────────────────────────────────────────────────────
      var initial = fromOpenAI() || fromQuery();
      if (initial) {
        render(initial);
      } else {
        // Give the MCP Apps bridge a moment to deliver the tool result before
        // declaring that no model was provided.
        setTimeout(function () {
          if (!rendered && !pending) showPlaceholder();
        }, 4000);
      }
    })();
  </script>
</body>
</html>`;

/** Resource descriptor as returned by `resources/list`. */
export interface WidgetResourceDescriptor {
  uri: string;
  name: string;
  description: string;
  mimeType: string;
  _meta: typeof WIDGET_RESOURCE_META;
}

/** Resource contents block as returned by `resources/read`. */
export interface WidgetResourceContents {
  uri: string;
  mimeType: string;
  text: string;
  _meta: typeof WIDGET_RESOURCE_META;
}

const WIDGETS: Record<string, { name: string; description: string; html: string }> = {
  [WIDGET_3D_VIEWER_URI]: {
    name: "SceneView 3D Viewer",
    description:
      "MCP Apps widget: interactive SceneView.js + Filament.js viewer for a GLB / glTF model, rendered inline by the host when `view_3d_model` is called.",
    html: WIDGET_3D_VIEWER_HTML,
  },
};

/** Every widget resource, in `resources/list` shape. */
export function listWidgetResources(): WidgetResourceDescriptor[] {
  return Object.entries(WIDGETS).map(([uri, w]) => ({
    uri,
    name: w.name,
    description: w.description,
    mimeType: MCP_APP_MIME_TYPE,
    _meta: WIDGET_RESOURCE_META,
  }));
}

/** Contents for a widget URI in `resources/read` shape, or `null` if unknown. */
export function readWidgetResource(uri: string): WidgetResourceContents | null {
  const w = WIDGETS[uri];
  if (!w) return null;
  return { uri, mimeType: MCP_APP_MIME_TYPE, text: w.html, _meta: WIDGET_RESOURCE_META };
}
