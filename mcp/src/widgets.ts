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
 *
 * An explicit `mimeTypes: []` counts as silence, deliberately (#3485): the
 * spec makes `mimeTypes` REQUIRED, so an empty one is a malformed declaration
 * rather than a stated refusal, and reading a malformed declaration as "render
 * nothing" would dark-ship the widget on a client bug. Pinned by a test in
 * `widgets.test.ts` so the choice cannot drift silently.
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
/**
 * The compiled SceneView KMP core, loaded by the widget ONLY to convert a `.3mf` to GLB
 * (`sceneview.threeMfToGlb`) — never to render, which stays `sceneview.js` + the site's own
 * Filament build. Fetched lazily, so a GLB preview never pays for it (#3482).
 */
const SCENEVIEW_WEB_URL = `${CDN_BASE}/js/sceneview-web.js?v=${LATEST_SCENEVIEW_RELEASE}`;
const NEUTRAL_IBL_URL = `${CDN_BASE}/environments/neutral_ibl.ktx`;
/**
 * qrcode-generator 1.4.4 (MIT, © 2009 Kazuhiko Arase), the copy SceneView already vendors for
 * the website's scan-to-open QR (`website-static/js/qrcode-vendor.js`). Served from the same
 * origin as every other widget script, so the CSP needs no new domain, and fetched lazily on a
 * desktop-sized viewport only: a phone never downloads it. The QR is drawn in the browser —
 * no QR image service ever sees the link.
 */
const QRCODE_JS_URL = `${CDN_BASE}/js/qrcode-vendor.js?v=${LATEST_SCENEVIEW_RELEASE}`;

/**
 * AR Model Viewer's universal link for a remote model (`ar.sceneview.dev/open?url=`). A phone
 * with the app opens the model in AR; anything else gets a SceneView-rendered page with the
 * store badges (and a QR on desktop). The worker reads only `url`, `unit` and `name` and ignores
 * any other parameter, which is what makes the `utm_source` tag below harmless.
 */
export const AR_OPEN_URL = "https://ar.sceneview.dev/open";

/**
 * The widget's link builder, as JavaScript source. It is interpolated verbatim into the widget
 * script AND evaluated by `widgets.test.ts`, so the tests exercise the exact code the host runs
 * (the widget is plain browser JS, not a module the tests could import).
 *
 * `null` — no AR action — for anything the phone could not fetch: `http:`, `data:` and `blob:`
 * URLs (a `blob:` exists only inside this iframe), unparsable strings, and URLs longer than the
 * 1024 characters the `/open` route accepts.
 */
export const OPEN_IN_AR_JS = `function openInArUrl(modelUrl, source) {
        if (typeof modelUrl !== "string" || modelUrl.length > 1024) return null;
        var parsed;
        try { parsed = new URL(modelUrl); } catch (e) { return null; }
        if (parsed.protocol !== "https:") return null;
        return "${AR_OPEN_URL}?url=" + encodeURIComponent(modelUrl) +
          "&utm_source=" + encodeURIComponent(source);
      }`;

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
    /* Colour tokens from DESIGN.md, light first. The host picks the theme
       (MCP Apps \`hostContext.theme\`, ChatGPT \`window.openai.theme\`) and
       the script mirrors it onto <html data-theme>; without one the OS
       preference applies. The 3D stage stays dark in both themes — DESIGN.md
       \`stage-background-embedded\` — so everything drawn over it (loader,
       placeholder, the AR action) is styled for a dark ground. */
    :root {
      color-scheme: light;
      --sv-surface: #ffffff;
      --sv-on-surface: #1a1a2e;
      --sv-on-surface-dim: #3d4654;
      --sv-on-surface-faint: #5c6370;
      --sv-outline-subtle: #ebedf0;
      --sv-chip: #f1f3f5;
      --sv-link: #005bc1;
      --sv-primary: #005bc1;
      --sv-primary-hover: #0050aa;
      --sv-on-primary: #ffffff;
      --sv-card: #ffffff;
      --sv-stage: #0b0f16;
    }
    @media (prefers-color-scheme: dark) {
      :root:not([data-theme="light"]) {
        color-scheme: dark;
        --sv-surface: #0d1117;
        --sv-on-surface: #f3f4f6;
        --sv-on-surface-dim: #a4abb7;
        --sv-on-surface-faint: #6b7280;
        --sv-outline-subtle: #21262d;
        --sv-chip: #21262d;
        --sv-link: #a4c1ff;
        --sv-primary: #a4c1ff;
        --sv-primary-hover: #b8d0ff;
        --sv-on-primary: #0d1117;
        --sv-card: #232a39;
        --sv-stage: linear-gradient(180deg, #0d1117 0%, #161b22 100%);
      }
    }
    :root[data-theme="dark"] {
      color-scheme: dark;
      --sv-surface: #0d1117;
      --sv-on-surface: #f3f4f6;
      --sv-on-surface-dim: #a4abb7;
      --sv-on-surface-faint: #6b7280;
      --sv-outline-subtle: #21262d;
      --sv-chip: #21262d;
      --sv-link: #a4c1ff;
      --sv-primary: #a4c1ff;
      --sv-primary-hover: #b8d0ff;
      --sv-on-primary: #0d1117;
      --sv-card: #232a39;
      --sv-stage: linear-gradient(180deg, #0d1117 0%, #161b22 100%);
    }
    *, *::before, *::after { box-sizing: border-box; margin: 0; padding: 0; }
    html, body { height: 100%; }
    body {
      font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", system-ui, sans-serif;
      background: var(--sv-surface);
      color: var(--sv-on-surface);
      overflow: hidden;
    }
    #stage {
      width: 100%;
      height: 100vh;
      min-height: 400px;
      display: flex;
      flex-direction: column;
      background: var(--sv-surface);
    }
    #header {
      flex: 0 0 auto;
      padding: 12px 16px 8px;
      display: flex;
      align-items: center;
      gap: 8px;
      border-bottom: 1px solid var(--sv-outline-subtle);
    }
    #header strong { font-size: 0.95rem; font-weight: 600; }
    #brand {
      font-size: 0.75rem;
      color: var(--sv-on-surface-dim);
      margin-left: auto;
      white-space: nowrap;
    }
    #brand a { color: var(--sv-link); text-decoration: none; }
    #canvas-wrap {
      flex: 1 1 auto;
      position: relative;
      width: 100%;
      min-height: 0;
      background: var(--sv-stage);
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
      color: #a4abb7;
      pointer-events: none;
      background: var(--sv-stage);
    }
    [hidden] { display: none !important; }
    #loader .spinner {
      width: 28px;
      height: 28px;
      border: 3px solid #30363d;
      border-top-color: #a4c1ff;
      border-radius: 50%;
      animation: spin 0.8s linear infinite;
    }
    @keyframes spin { to { transform: rotate(360deg); } }
    #error { color: #ffb4ab; }
    #placeholder svg { opacity: 0.5; }
    #footer {
      flex: 0 0 auto;
      padding: 6px 16px;
      font-size: 0.7rem;
      color: var(--sv-on-surface-faint);
      border-top: 1px solid var(--sv-outline-subtle);
      display: flex;
      gap: 12px;
    }
    .pill {
      background: var(--sv-chip);
      color: var(--sv-on-surface-dim);
      padding: 2px 8px;
      border-radius: 4px;
      font-size: 0.7rem;
    }

    /* ── Open in AR ──────────────────────────────────────────────────────
       A phone gets one pill button. A desktop-sized viewport (\`.wide\`)
       also gets a QR code of the same link, because AR happens on the
       phone in a pocket, not on the monitor. */
    #ar {
      position: absolute;
      left: 12px;
      right: 12px;
      bottom: 12px;
      z-index: 1;
      display: flex;
      justify-content: center;
      pointer-events: none;
    }
    #ar-card { pointer-events: auto; display: flex; align-items: center; gap: 12px; }
    #ar-open {
      display: inline-flex;
      align-items: center;
      justify-content: center;
      gap: 8px;
      min-height: 44px;
      padding: 10px 20px;
      border-radius: 9999px;
      background: var(--sv-primary);
      color: var(--sv-on-primary);
      font-family: inherit;
      font-size: 0.875rem;
      font-weight: 600;
      line-height: 1.2;
      text-decoration: none;
      white-space: nowrap;
      box-shadow: 0 2px 8px rgba(0, 0, 0, 0.35);
      transition: transform 0.2s cubic-bezier(0.34, 1.56, 0.64, 1), background-color 0.15s ease;
    }
    #ar-open:hover { background: var(--sv-primary-hover); }
    #ar-open:active { transform: scale(0.97); }
    #ar-open:focus-visible { outline: 2px solid #ffffff; outline-offset: 3px; }
    #ar-open svg { flex: 0 0 auto; }
    #ar .long, #ar-qr, #ar-scan { display: none; }
    #ar.wide .long { display: inline; }
    #ar.wide.has-qr { justify-content: flex-end; }
    #ar.wide.has-qr #ar-card {
      padding: 12px;
      border-radius: 16px;
      background: var(--sv-card);
      color: var(--sv-on-surface);
      box-shadow: 0 4px 16px rgba(0, 0, 0, 0.35);
    }
    #ar.wide.has-qr #ar-qr {
      display: block;
      width: 104px;
      height: 104px;
      padding: 6px;
      border-radius: 8px;
      background: #ffffff;
    }
    #ar-qr svg { display: block; width: 100%; height: 100%; shape-rendering: crispEdges; }
    #ar.wide.has-qr #ar-scan { display: flex; flex-direction: column; gap: 4px; }
    #ar-copy { display: flex; flex-direction: column; align-items: flex-start; gap: 10px; }
    #ar-scan strong { font-size: 0.875rem; font-weight: 600; }
    #ar-scan span { font-size: 0.75rem; color: var(--sv-on-surface-dim); }
    #ar.wide.has-qr #ar-open { min-height: 36px; padding: 8px 14px; font-size: 0.8rem; box-shadow: none; }
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
        <div id="error-detail" style="font-size:0.75rem;color:#a4abb7"></div>
      </div>
      <div id="ar" hidden>
        <div id="ar-card">
          <div id="ar-qr" role="img" aria-label="QR code: scan with your phone to open this model in AR"></div>
          <div id="ar-copy">
            <div id="ar-scan"><strong>See it in your room</strong><span>Scan with your phone's camera</span></div>
            <a id="ar-open" href="${AR_OPEN_URL}" target="_blank" rel="noopener">
              <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
                <path d="M3 7V5a2 2 0 0 1 2-2h2M17 3h2a2 2 0 0 1 2 2v2M21 17v2a2 2 0 0 1-2 2h-2M7 21H5a2 2 0 0 1-2-2v-2"/>
                <path d="m12 7 4.5 2.5v5L12 17l-4.5-2.5v-5L12 7Z"/>
                <path d="m7.5 9.5 4.5 2.5 4.5-2.5M12 12v5"/>
              </svg>
              <span>Open in AR<span class="long"> on your phone</span></span>
            </a>
          </div>
        </div>
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
      // The 3MF converter (#3482), fetched only when a payload needs it.
      var CONVERTER_URL = "${SCENEVIEW_WEB_URL}";

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
      // Requests this widget sent to the host after the handshake, by id.
      var nextRequestId = INIT_ID + 1;
      var awaiting = {};
      // What the host said about itself in the \`ui/initialize\` result:
      // null until it answers, which also means "not an MCP Apps host yet".
      var hostCapabilities = null;

      function request(method, params, onDone) {
        var id = nextRequestId++;
        awaiting[id] = onDone;
        post({ jsonrpc: "2.0", id: id, method: method, params: params });
      }

      window.addEventListener("message", function (event) {
        var msg = event.data;
        if (!msg || typeof msg !== "object" || msg.jsonrpc !== "2.0") return;
        if (msg.method === "ui/notifications/tool-result" && msg.params) {
          var data = normalise(msg.params.structuredContent);
          if (data) render(data);
          return;
        }
        if (msg.method === "ui/notifications/host-context-changed" && msg.params) {
          applyTheme(msg.params.theme);
          return;
        }
        if (msg.id === INIT_ID && (msg.result || msg.error)) {
          var init = msg.result;
          if (init && typeof init === "object") {
            var caps = init.hostCapabilities;
            hostCapabilities = caps && typeof caps === "object" ? caps : {};
            applyTheme(init.hostContext && init.hostContext.theme);
          }
          post({ jsonrpc: "2.0", method: "ui/notifications/initialized" });
          return;
        }
        if (typeof msg.id === "number" && awaiting[msg.id]) {
          var done = awaiting[msg.id];
          delete awaiting[msg.id];
          done(!msg.error && !(msg.result && msg.result.isError));
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
        applyTheme(window.openai && window.openai.theme);
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

      // The host's theme wins over the OS preference; anything but the two
      // known values leaves the CSS \`prefers-color-scheme\` fallback in charge.
      function applyTheme(theme) {
        if (theme === "light" || theme === "dark") {
          document.documentElement.setAttribute("data-theme", theme);
        }
      }
      try { applyTheme(window.openai && window.openai.theme); } catch (e) { /* no host */ }

      // ── Open in AR ───────────────────────────────────────────────────────
      //
      // One link, three ways to follow it, in order: ChatGPT's
      // \`openai.openExternal\`, the MCP Apps \`ui/open-link\` request, and
      // the anchor's own \`target="_blank"\` for any other host. A desktop-sized
      // viewport also gets a QR code of the same link.

      var QRCODE_JS_URL = "${QRCODE_JS_URL}";
      var arEl = document.getElementById("ar");
      var arOpen = document.getElementById("ar-open");
      var arQr = document.getElementById("ar-qr");
      var arLink = null;
      var wideQuery = window.matchMedia
        ? window.matchMedia("(min-width: 600px) and (hover: hover) and (pointer: fine)")
        : null;

      ${OPEN_IN_AR_JS}

      // Which assistant sent the visitor, for the store's acquisition report.
      function linkSource() {
        return window.openai ? "chatgpt" : "claude";
      }

      function updateArAction(data) {
        arLink = data.ar === false ? null : openInArUrl(data.modelUrl, linkSource());
        arEl.classList.remove("has-qr");
        arQr.textContent = "";
        if (!arLink) { arEl.hidden = true; return; }
        arOpen.href = arLink;
        arEl.hidden = false;
        updateLayout();
      }

      function updateLayout() {
        var wide = !!(wideQuery && wideQuery.matches);
        arEl.classList.toggle("wide", wide);
        if (wide && arLink && !arEl.classList.contains("has-qr")) drawQr(arLink);
      }
      if (wideQuery) {
        if (wideQuery.addEventListener) wideQuery.addEventListener("change", updateLayout);
        else if (wideQuery.addListener) wideQuery.addListener(updateLayout);
      }

      var qrScriptPromise = null;
      function loadQrEncoder() {
        if (!qrScriptPromise) {
          qrScriptPromise = new Promise(function (resolve, reject) {
            var el = document.createElement("script");
            el.src = QRCODE_JS_URL;
            el.onload = function () { resolve(); };
            el.onerror = function () { reject(new Error("could not load the QR encoder")); };
            document.head.appendChild(el);
          });
        }
        return qrScriptPromise;
      }

      // A failure leaves the button on its own, which still works.
      function drawQr(link) {
        loadQrEncoder().then(function () {
          if (link !== arLink || typeof window.qrcode !== "function") return;
          var qr = window.qrcode(0, "M");
          qr.addData(link);
          qr.make();
          arQr.innerHTML = qr.createSvgTag({ scalable: true, margin: 0 });
          arEl.classList.add("has-qr");
        }).catch(function () { /* button only */ });
      }

      arOpen.addEventListener("click", function (event) {
        if (!arLink) { event.preventDefault(); return; }
        var link = arLink;
        var openai = window.openai;
        if (openai && typeof openai.openExternal === "function") {
          event.preventDefault();
          try { openai.openExternal({ href: link }); } catch (e) { window.open(link, "_blank", "noopener"); }
          return;
        }
        // An MCP Apps host that answered the handshake. \`openLinks\` is asked
        // for even when the host did not declare it — an undeclared but
        // working \`ui/open-link\` beats an anchor the iframe sandbox may block
        // — and a refusal falls back to a plain new window. Only a host that
        // explicitly set it falsy is left to the anchor.
        if (hostCapabilities && (!("openLinks" in hostCapabilities) || hostCapabilities.openLinks)) {
          event.preventDefault();
          request("ui/open-link", { url: link }, function (ok) {
            if (!ok) window.open(link, "_blank", "noopener");
          });
        }
        // Otherwise the anchor itself opens the link in a new tab.
      });

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
        // Before the model loads, and whether or not it does: a file this
        // browser cannot draw may still open in the app.
        updateArAction(data);
        canvas.setAttribute("aria-label", data.alt || data.title || "3D model");
        var ext = (data.modelUrl.split("?")[0].split(".").pop() || "GLB").toUpperCase();
        formatEl.textContent = ext === "GLTF" ? "glTF" : ext;
        whenSceneViewReady(function () { start(data); });
      }

      // ── 3MF (#3482) ──────────────────────────────────────────────────────
      //
      // ChatGPT emits a \`.3mf\` when it turns a drawing into a printable model.
      // The conversion is NOT reimplemented here: \`sceneview-web.js\` is the
      // compiled SceneView KMP core, the same \`ThreeMfLoader\` Android runs,
      // exposed as \`sceneview.threeMfToGlb(bytes)\`. It is loaded lazily and
      // only for that call, so a GLB preview never downloads it.

      var threeMfScriptPromise = null;
      function loadThreeMfConverter() {
        if (!threeMfScriptPromise) {
          threeMfScriptPromise = new Promise(function (resolve, reject) {
            var el = document.createElement("script");
            el.src = CONVERTER_URL;
            el.onload = function () { resolve(); };
            el.onerror = function () { reject(new Error("could not load the 3MF converter")); };
            document.head.appendChild(el);
          });
        }
        return threeMfScriptPromise;
      }

      /** \`PK\\x03\\x04\` — the ZIP magic every 3MF starts with. Four bytes, no parse. */
      function looksLikeZip(buffer) {
        if (!buffer || buffer.byteLength < 4) return false;
        var b = new Uint8Array(buffer, 0, 4);
        return b[0] === 0x50 && b[1] === 0x4b && b[2] === 0x03 && b[3] === 0x04;
      }

      /**
       * The URL the renderer should load: a \`blob:\` GLB for a 3MF, or the URL
       * unchanged for anything else.
       *
       * Only a \`.3mf\` URL — or one with no recognised model extension, which is
       * what a generated-file link usually looks like — is fetched and sniffed
       * here. A \`.glb\` / \`.gltf\` goes straight through, so the common case
       * still costs exactly one download, and a glTF keeps resolving its own
       * external buffers and textures against its own URL.
       */
      function resolveModelUrl(url) {
        var path = String(url).split("?")[0].split("#")[0].toLowerCase();
        var isThreeMfUrl = /.3mf$/.test(path);
        var isKnownGltf = /.(glb|gltf)$/.test(path);
        if (!isThreeMfUrl && isKnownGltf) return Promise.resolve(url);

        return fetch(url)
          .then(function (r) {
            if (!r.ok) throw new Error("HTTP " + r.status + " loading the model");
            var type = (r.headers.get("content-type") || "").toLowerCase();
            return r.arrayBuffer().then(function (buffer) {
              return { buffer: buffer, isThreeMfType: type.indexOf("model/3mf") !== -1 };
            });
          })
          .then(function (payload) {
            if (!looksLikeZip(payload.buffer) && !payload.isThreeMfType) return url;
            return loadThreeMfConverter().then(function () {
              if (!window.sceneview || typeof window.sceneview.threeMfToGlb !== "function") {
                throw new Error("the 3MF converter did not load");
              }
              if (!window.sceneview.isThreeMf(payload.buffer)) {
                if (isThreeMfUrl) throw new Error("this file is not a readable 3MF");
                return url;
              }
              var glb = window.sceneview.threeMfToGlb(payload.buffer);
              formatEl.textContent = "3MF";
              return URL.createObjectURL(new Blob([glb], { type: "model/gltf-binary" }));
            });
          });
      }

      function start(data) {
        if (pending !== data) return; // superseded by a newer payload
        resolveModelUrl(data.modelUrl).then(function (url) {
          if (pending !== data) return; // superseded while the 3MF was converting
          startWith(data, url);
        }).catch(fail);
      }

      function startWith(data, url) {
        if (viewer && typeof viewer.loadModel === "function") {
          viewer.loadModel(url).then(function () { reveal(data); }).catch(fail);
          return;
        }
        SceneView.modelViewer(canvas, url, {
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
