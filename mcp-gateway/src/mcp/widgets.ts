/**
 * MCP Apps widget registry for the hosted gateway.
 *
 * The 3D viewer (`ui://widget/3d-viewer.html`) — the widget the ChatGPT /
 * Codex listing renders — is IMPORTED from the upstream package
 * (`mcp/src/widgets.ts`), which also serves it over stdio and over its own
 * Streamable HTTP entrypoint (`npx sceneview-mcp --http`). One source for
 * the HTML, the mime type, and the `_meta.ui` CSP block; the gateway adds
 * nothing to it.
 *
 * The scene showcase (`ui://widget/scene-showcase.html`) stays gateway-only:
 * it is the marketing "hero" page behind `/showcase` (see `../index.ts`),
 * not something a tool result points at.
 *
 * Wire contract, for both:
 *
 * 1. The widget HTML is an MCP resource with a `ui://` URI and the mime type
 *    `text/html;profile=mcp-app`. Hosts fetch it via `resources/read`.
 * 2. The tool declaration in `tools/list` AND each tool result attach
 *    `_meta.ui.resourceUri`; the result adds the `structuredContent` the
 *    widget renders.
 */

import {
  MCP_APP_MIME_TYPE,
  UI_EXTENSION_ID,
  WIDGET_3D_VIEWER_HTML,
  WIDGET_3D_VIEWER_URI,
  listWidgetResources as listUpstreamWidgetResources,
  readWidgetResource as readUpstreamWidgetResource,
  readUiExtension,
  serveWidgetsTo,
  uiExtensionSettings,
  type UiExtensionSettings,
} from "../../../mcp/src/widgets.js";

/** Canonical mime type for MCP Apps widget resources (re-exported upstream value). */
export const APPS_SDK_MIME_TYPE: string = MCP_APP_MIME_TYPE;

// The MCP Apps extension identity and its negotiation helpers come from the
// same upstream module as the widget HTML, so the gateway and `npx
// sceneview-mcp` can never disagree about what MCP Apps means here (#3192).
export {
  UI_EXTENSION_ID,
  readUiExtension,
  serveWidgetsTo,
  uiExtensionSettings,
  type UiExtensionSettings,
};

/**
 * Scene showcase widget — exercises the FULL SceneView.js API, not just
 * the one-liner `modelViewer` shortcut.
 *
 * Demonstrates (in a single canvas) the features that justify a custom
 * Filament-backed renderer over a generic third-party viewer:
 *   - Multi-light setup (3 coloured point lights forming a 3-point lighting rig)
 *   - Post-processing: bloom on, high quality, neutral KTX IBL
 *   - 3D Text node ("SceneView" floating above the model)
 *   - Real PBR materials on the loaded GLB
 *   - Custom camera framing (fov, target offset)
 *
 * Reads the same `structuredContent` shape as the model viewer but layers
 * additional optional fields:
 *   { modelUrl, title?, label?, primaryColor?, accentColor?, bloomStrength? }
 *
 * The whole point of this widget is to be the "hero" screenshot for the
 * OpenAI App Store listing — every visual cue (coloured rim light, bloom
 * glow, 3D label) tells the reviewer that this is not a vanilla viewer.
 */
const WIDGET_SCENE_SHOWCASE_HTML = `<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1" />
  <title>SceneView Scene Showcase</title>
  <style>
    *, *::before, *::after { box-sizing: border-box; margin: 0; padding: 0; }
    html, body { height: 100%; }
    body {
      font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", system-ui, sans-serif;
      background: #05060a;
      color: #e6edf3;
      overflow: hidden;
    }
    #stage {
      width: 100%;
      height: 100vh;
      min-height: 400px;
      display: flex;
      flex-direction: column;
      background: radial-gradient(circle at 30% 20%, #18172a 0%, #05060a 70%);
    }
    #header {
      flex: 0 0 auto;
      padding: 12px 16px 8px;
      display: flex;
      align-items: center;
      gap: 8px;
      border-bottom: 1px solid rgba(255, 255, 255, 0.05);
    }
    #header strong { font-size: 0.95rem; font-weight: 600; }
    #brand { font-size: 0.75rem; color: #8b949e; margin-left: auto; }
    #brand a { color: #58a6ff; text-decoration: none; }
    #canvas-wrap { flex: 1 1 auto; position: relative; min-height: 0; }
    #canvas { display: block; width: 100%; height: 100%; transition: opacity 0.4s; }
    #features {
      position: absolute;
      bottom: 12px;
      left: 12px;
      display: flex;
      flex-wrap: wrap;
      gap: 6px;
      pointer-events: none;
    }
    .feature {
      background: rgba(13, 17, 23, 0.85);
      backdrop-filter: blur(8px);
      padding: 4px 10px;
      border-radius: 999px;
      font-size: 0.7rem;
      color: #c9d1d9;
      border: 1px solid rgba(255, 255, 255, 0.06);
    }
    .feature::before {
      content: "";
      display: inline-block;
      width: 6px;
      height: 6px;
      border-radius: 50%;
      margin-right: 6px;
      vertical-align: middle;
      background: var(--dot, #58a6ff);
      box-shadow: 0 0 8px var(--dot, #58a6ff);
    }
    #loader, #error {
      position: absolute; inset: 0;
      display: flex; align-items: center; justify-content: center;
      flex-direction: column; gap: 12px;
      color: #8b949e; pointer-events: none;
    }
    #loader .spinner {
      width: 28px; height: 28px;
      border: 3px solid #21262d; border-top-color: #58a6ff;
      border-radius: 50%; animation: spin 0.8s linear infinite;
    }
    @keyframes spin { to { transform: rotate(360deg); } }
    #error { color: #f85149; }
    [hidden] { display: none !important; }
    #footer {
      flex: 0 0 auto; padding: 6px 16px;
      font-size: 0.7rem; color: #6e7681;
      border-top: 1px solid rgba(255, 255, 255, 0.05);
      display: flex; gap: 12px;
    }
    .pill { background: rgba(255, 255, 255, 0.05); padding: 2px 8px; border-radius: 4px; }
  </style>
  <script src="https://sceneview.github.io/js/filament/filament.js"></script>
  <script src="https://sceneview.github.io/js/sceneview.js"></script>
</head>
<body>
  <div id="stage">
    <div id="header">
      <strong id="title">SceneView Scene Showcase</strong>
      <span id="brand">Powered by <a href="https://sceneview.github.io" target="_blank" rel="noopener">SceneView</a></span>
    </div>
    <div id="canvas-wrap">
      <canvas id="canvas" style="opacity:0"></canvas>
      <div id="features">
        <span class="feature" style="--dot:#ff5f6d">3-point lighting</span>
        <span class="feature" style="--dot:#ffd166">Bloom post-FX</span>
        <span class="feature" style="--dot:#58a6ff">PBR materials</span>
        <span class="feature" style="--dot:#9b8cff">Filament IBL</span>
      </div>
      <div id="loader"><div class="spinner"></div><div>Composing 3D scene…</div></div>
      <div id="error" hidden>
        <div>Could not compose scene</div>
        <div id="error-detail" style="font-size:0.75rem;color:#8b949e"></div>
      </div>
    </div>
    <div id="footer">
      <span class="pill">Filament.js · WebGL2 · WASM</span>
      <span class="pill">Multi-light · Bloom · IBL · PBR</span>
      <span style="margin-left:auto" id="hint">Drag to orbit · Scroll to zoom</span>
    </div>
  </div>
  <script>
    (function () {
      var fromHost = function () {
        try {
          return (
            (window.openai && window.openai.structuredContent) ||
            (window.__mcp && window.__mcp.structuredContent) ||
            null
          );
        } catch (e) { return null; }
      };
      var fromQuery = function () {
        var p = new URLSearchParams(location.search);
        var modelUrl = p.get("modelUrl") || p.get("src");
        return {
          modelUrl: modelUrl || "https://sceneview.github.io/models/platforms/DamagedHelmet.glb",
          title: p.get("title") || undefined,
          bloomStrength: parseFloat(p.get("bloomStrength") || "0.4"),
        };
      };

      var data = fromHost() || fromQuery() || {};
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
      var clean = cleanTitle(data.title);
      if (clean) document.getElementById("title").textContent = clean;

      var loader = document.getElementById("loader");
      var canvas = document.getElementById("canvas");
      var errorEl = document.getElementById("error");
      var errorDetail = document.getElementById("error-detail");

      function showError(msg) {
        loader.hidden = true;
        canvas.style.display = "none";
        errorDetail.textContent = msg;
        errorEl.hidden = false;
      }

      function start() {
        if (typeof SceneView === "undefined" || !SceneView.create) {
          showError("SceneView.js is not available on the page (CDN load failed).");
          return;
        }
        SceneView.create(canvas, {
          backgroundColor: [0.02, 0.025, 0.04, 1],
          fov: 32,
          iblUrl: "https://sceneview.github.io/environments/neutral_ibl.ktx",
        }).then(function (sv) {
          // High quality + bloom for the hero look.
          if (sv.setQuality) sv.setQuality("high");
          if (sv.setBloom) {
            sv.setBloom({
              strength: typeof data.bloomStrength === "number" ? data.bloomStrength : 0.4,
              threshold: 0.85,
            });
          }
          // Three-point lighting rig: warm key (top-right), cool fill
          // (left), magenta rim (back).
          if (sv.addLight) {
            sv.addLight({ type: "point", position: [2, 3, 1.5], color: [1, 0.85, 0.7], intensity: 80000 });
            sv.addLight({ type: "point", position: [-2.5, 1.5, 1], color: [0.5, 0.7, 1], intensity: 60000 });
            sv.addLight({ type: "point", position: [0, 1, -3], color: [1, 0.4, 0.8], intensity: 70000 });
          }
          // Floating 3D label above the model.
          if (sv.createText) {
            sv.createText({
              text: clean || "SceneView",
              position: [0, 1.6, 0],
              size: 0.18,
              color: [1, 1, 1],
            });
          }
          // Load the model and frame it.
          var loadPromise = sv.loadModel ? sv.loadModel(data.modelUrl) : null;
          if (!loadPromise) { reveal(); return; }
          loadPromise.then(function () { reveal(); }).catch(function (err) {
            // Even if model load fails, we still have lights + text.
            reveal();
            console.warn("Model load warning:", err);
          });

          function reveal() {
            loader.hidden = true;
            canvas.style.opacity = "1";
            try {
              if (window.openai && typeof window.openai.notifyReady === "function") {
                window.openai.notifyReady();
              } else {
                window.parent.postMessage({ type: "mcp-app/ready" }, "*");
              }
            } catch (e) { /* swallow */ }
          }
        }).catch(function (err) {
          showError((err && err.message) || String(err));
        });
      }

      var tries = 0;
      var maxTries = 60;
      var timer = setInterval(function () {
        tries++;
        if (typeof SceneView !== "undefined" && SceneView.create) {
          clearInterval(timer);
          start();
        } else if (tries >= maxTries) {
          clearInterval(timer);
          showError("Timed out loading SceneView.js + Filament.js from the CDN.");
        }
      }, 100);
    })();
  </script>
</body>
</html>`;

/**
 * Public catalog of widgets the gateway can serve. The 3D viewer comes from
 * upstream; the showcase is local. Both are addressable at
 * `/widget/<name>.html` for live preview (see `../index.ts`).
 */
export const WIDGETS: Record<string, { name: string; html: string }> = {
  [WIDGET_3D_VIEWER_URI]: {
    name: "SceneView 3D Viewer",
    html: WIDGET_3D_VIEWER_HTML,
  },
  "ui://widget/scene-showcase.html": {
    name: "SceneView Scene Showcase",
    html: WIDGET_SCENE_SHOWCASE_HTML,
  },
};

/** MCP resource shape returned by `resources/list`. */
export interface ResourceDescriptor {
  uri: string;
  name: string;
  description?: string;
  mimeType: string;
  /** MCP Apps `ui` block (CSP, border preference) when the widget declares one. */
  _meta?: Record<string, unknown>;
}

export function listWidgetResources(): ResourceDescriptor[] {
  return [
    // Upstream first: it carries the `_meta.ui` CSP block hosts need.
    ...listUpstreamWidgetResources(),
    ...Object.entries(WIDGETS)
      .filter(([uri]) => uri !== WIDGET_3D_VIEWER_URI)
      .map(([uri, w]) => ({ uri, name: w.name, mimeType: APPS_SDK_MIME_TYPE })),
  ];
}

/**
 * Returns the resource contents for a given URI, or `null` if unknown.
 * The contents block matches the MCP `resources/read` response schema.
 */
export function readWidgetResource(uri: string):
  | {
      uri: string;
      mimeType: string;
      text: string;
      _meta?: Record<string, unknown>;
    }
  | null {
  const upstream = readUpstreamWidgetResource(uri);
  if (upstream) return upstream;
  const w = WIDGETS[uri];
  if (!w) return null;
  return { uri, mimeType: APPS_SDK_MIME_TYPE, text: w.html };
}
