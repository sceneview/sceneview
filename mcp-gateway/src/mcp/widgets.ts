/**
 * OpenAI Apps SDK widget registry.
 *
 * Widgets turn structured tool results into a HTML/JS UI that ChatGPT
 * renders inline (in an iframe sandbox). The wire contract is:
 *
 * 1. The widget HTML is registered as an MCP resource with a unique URI
 *    (e.g. `ui://widget/3d-viewer.html`) and the canonical mime type
 *    `text/html;profile=mcp-app`. ChatGPT fetches it via `resources/read`
 *    when a tool result references the URI.
 * 2. The tool declaration in `tools/list` AND each tool result attach
 *    `_meta.ui.resourceUri` pointing at the widget URI; the result adds a
 *    `structuredContent` JSON payload that the widget reads from the MCP
 *    Apps bridge (postMessage).
 * 3. The widget runs inside the iframe, reads `window.openai?.structuredContent`
 *    (set by the host), and renders accordingly.
 *
 * For SceneView the natural widget is a 3D model viewer — leveraging the
 * `<model-viewer>` web component (Google) for the MVP because it is a
 * single CDN tag + zero build step. We can swap to the heavier
 * `sceneview-web` (Filament.js) bundle later if we want full SceneView
 * parity in the iframe; the resource URI stays stable so existing tool
 * results keep working.
 */

/** Canonical mime type required by the OpenAI Apps SDK / Skybridge runtime. */
export const APPS_SDK_MIME_TYPE = "text/html;profile=mcp-app";

/**
 * The 3D model viewer widget served at `ui://widget/3d-viewer.html`.
 *
 * Reads `structuredContent` of shape:
 *   { modelUrl: string, autoRotate?: boolean, ar?: boolean,
 *     title?: string, posterUrl?: string, alt?: string }
 *
 * Powered by **SceneView.js + Filament.js** (the same WebGL2/WASM renderer
 * that ships in the `sceneview-web` npm package and runs the official
 * sceneview.github.io playground). This is intentional: SceneView's whole
 * value proposition is its renderer — using a generic third-party viewer
 * here would defeat the purpose of the SceneView App Store listing.
 *
 * If `modelUrl` is missing we render a placeholder with the SceneView logo
 * so the widget never appears empty even when called with no args.
 */
const WIDGET_3D_VIEWER_HTML = `<!DOCTYPE html>
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
  <script src="https://sceneview.github.io/js/filament/filament.js"></script>
  <script src="https://sceneview.github.io/js/sceneview.js"></script>
</head>
<body>
  <div id="stage">
    <div id="header">
      <strong id="title">SceneView 3D Viewer</strong>
      <span id="brand">Powered by <a href="https://sceneview.github.io" target="_blank" rel="noopener">SceneView</a></span>
    </div>
    <div id="canvas-wrap">
      <canvas id="canvas" style="opacity:0"></canvas>
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
      // The MCP Apps bridge exposes the tool's structuredContent on
      // window.openai. Host implementations vary slightly during the beta
      // — we read every plausible location and fall back to query string
      // parameters for direct preview.
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
        if (!modelUrl) return null;
        return {
          modelUrl: modelUrl,
          title: p.get("title") || undefined,
          autoRotate: p.get("autoRotate") !== "false",
          alt: p.get("alt") || undefined,
        };
      };

      var data = fromHost() || fromQuery() || {};
      // Sanitise the title: trim, cap length, and de-dupe accidental
      // repetition like "Ferrari F40Ferrari F40 2" caused by URL bar
      // autocomplete or stacked navigations during preview.
      function cleanTitle(raw) {
        if (!raw) return "";
        var s = String(raw).trim();
        if (s.length > 60) s = s.slice(0, 60);
        // If the string is exactly its first half repeated, keep the half.
        var half = Math.floor(s.length / 2);
        if (half > 0 && s.slice(0, half) === s.slice(half, half * 2)) {
          s = s.slice(0, half).trim();
        }
        return s;
      }
      var titleEl = document.getElementById("title");
      var clean = cleanTitle(data.title);
      titleEl.textContent = clean || "SceneView 3D Viewer";

      var loader = document.getElementById("loader");
      var canvas = document.getElementById("canvas");
      var placeholder = document.getElementById("placeholder");
      var errorEl = document.getElementById("error");
      var errorDetail = document.getElementById("error-detail");

      function showError(msg) {
        loader.hidden = true;
        canvas.style.display = "none";
        errorDetail.textContent = msg;
        errorEl.hidden = false;
      }

      function showPlaceholder() {
        loader.hidden = true;
        canvas.style.display = "none";
        placeholder.hidden = false;
      }

      if (!data.modelUrl) { showPlaceholder(); return; }

      function start() {
        if (typeof SceneView === "undefined" || !SceneView.modelViewer) {
          showError("SceneView.js is not available on the page (CDN load failed).");
          return;
        }
        SceneView.modelViewer(canvas, data.modelUrl, {
          backgroundColor: [0, 0, 0, 0],
          // Match the sceneview.github.io playground hero settings.
          lightIntensity: 150000,
          fov: 35,
          // Without an absolute IBL URL the renderer probes a relative
          // /environments/neutral_ibl.ktx path which 404s on the worker
          // subdomain and falls back to a much darker synthetic SH lighting
          // (PBR materials end up almost black). Point at the canonical
          // KTX hosted on sceneview.github.io for the real PBR look.
          iblUrl: "https://sceneview.github.io/environments/neutral_ibl.ktx",
        }).then(function (viewer) {
          loader.hidden = true;
          canvas.style.opacity = "1";
          if (data.autoRotate === false && viewer && viewer.setAutoRotate) {
            viewer.setAutoRotate(false);
          }
          // Notify the host that the widget is ready.
          try {
            if (window.openai && typeof window.openai.notifyReady === "function") {
              window.openai.notifyReady();
            } else {
              window.parent.postMessage({ type: "mcp-app/ready" }, "*");
            }
          } catch (e) { /* swallow */ }
        }).catch(function (err) {
          showError((err && err.message) || String(err));
        });
      }

      // SceneView.js loads asynchronously after Filament's WASM ready promise.
      // Poll briefly for the global before deciding to fail.
      var tries = 0;
      var maxTries = 60; // ~6 s at 100 ms intervals
      var timer = setInterval(function () {
        tries++;
        if (typeof SceneView !== "undefined" && SceneView.modelViewer) {
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
 * Public catalog of widgets the gateway can serve.
 *
 * The URIs follow the `ui://widget/<name>.html` convention from the OpenAI
 * Apps SDK reference docs.
 */
export const WIDGETS: Record<string, { name: string; html: string }> = {
  "ui://widget/3d-viewer.html": {
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
  mimeType: string;
}

export function listWidgetResources(): ResourceDescriptor[] {
  return Object.entries(WIDGETS).map(([uri, w]) => ({
    uri,
    name: w.name,
    mimeType: APPS_SDK_MIME_TYPE,
  }));
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
    }
  | null {
  const w = WIDGETS[uri];
  if (!w) return null;
  return { uri, mimeType: APPS_SDK_MIME_TYPE, text: w.html };
}
