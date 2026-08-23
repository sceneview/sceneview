// ─── 3D Artifact Generator ───────────────────────────────────────────────────
//
// Generates complete, self-contained HTML artifacts with interactive 3D content
// that Claude can render directly in conversations via artifacts.
//
// Uses Filament.js (Google's PBR renderer, WASM) for real 3D rendering —
// the same engine as SceneView Android.
//
// Types:
//   - model-viewer: interactive 3D model viewer with orbit controls (Filament.js)
//   - chart-3d: 3D bar/pie charts for data visualization (CSS 3D)
//   - scene: multi-model 3D scene with lighting and environment (Filament.js)
//   - product-360: product turntable with hotspot annotations (Filament.js)

import { FILAMENT_WEB_NPM_VERSION } from "./generated/version.js";

// ─── Types ───────────────────────────────────────────────────────────────────

export type ArtifactType = "model-viewer" | "chart-3d" | "scene" | "product-360" | "geometry";

export interface ChartDataPoint {
  label: string;
  value: number;
  color?: string;
}

export interface ArtifactOptions {
  autoRotate?: boolean;
  ar?: boolean;
  backgroundColor?: string;
  cameraOrbit?: string;
}

export interface Hotspot {
  position: string; // e.g. "0.5 1.2 0.3"
  normal: string; // e.g. "0 1 0"
  label: string;
  description?: string;
}

export interface GeometryShape {
  type: "cube" | "sphere" | "cylinder" | "plane" | "line";
  position?: [number, number, number];
  scale?: [number, number, number];
  color?: [number, number, number];
  metallic?: number;
  roughness?: number;
}

export interface ArtifactInput {
  type: ArtifactType;
  modelUrl?: string;
  title?: string;
  data?: ChartDataPoint[];
  options?: ArtifactOptions;
  hotspots?: Hotspot[];
  /** For scene type: additional model URLs */
  models?: Array<{ url: string; position?: string; scale?: string }>;
  /** For geometry type: array of procedural shapes */
  shapes?: GeometryShape[];
}

export interface ArtifactResult {
  html: string;
  title: string;
  type: ArtifactType;
}

// ─── Constants ───────────────────────────────────────────────────────────────

// The npm `filament` pin from gradle/libs.versions.toml (`filamentWeb`),
// snapshotted at build time — never a hand-copied version (#3173).
const FILAMENT_CDN = `https://cdn.jsdelivr.net/npm/filament@${FILAMENT_WEB_NPM_VERSION}/filament.js`;

const DEFAULT_MODEL =
  "https://raw.githubusercontent.com/KhronosGroup/glTF-Sample-Assets/main/Models/DamagedHelmet/glTF-Binary/DamagedHelmet.glb";

const DEFAULT_COLORS = [
  "#4285F4",
  "#EA4335",
  "#FBBC04",
  "#34A853",
  "#FF6D01",
  "#46BDC6",
  "#7BAAF7",
  "#F07B72",
  "#FCD04F",
  "#57BB8A",
];

const BRANDING = `<div style="position:absolute;bottom:8px;right:12px;font-size:11px;color:#888;font-family:system-ui,sans-serif;pointer-events:none">Powered by SceneView</div>`;

// ─── Shared HTML skeleton ────────────────────────────────────────────────────

function htmlShell(title: string, body: string, extraHead: string = ""): string {
  return `<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>${escapeHtml(title)}</title>
<style>
*{margin:0;padding:0;box-sizing:border-box}
html,body{width:100%;height:100%;overflow:hidden;background:#0d1117;color:#e0e0e0;font-family:system-ui,-apple-system,sans-serif}
</style>
${extraHead}
</head>
<body>
${body}
</body>
</html>`;
}

function escapeHtml(s: string): string {
  return s
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;");
}

// ─── Validation ──────────────────────────────────────────────────────────────

export function validateArtifactInput(input: ArtifactInput): string | null {
  const validTypes: ArtifactType[] = [
    "model-viewer",
    "chart-3d",
    "scene",
    "product-360",
    "geometry",
  ];
  if (!validTypes.includes(input.type)) {
    return `Invalid type "${input.type}". Must be one of: ${validTypes.join(", ")}`;
  }

  if (input.type === "chart-3d") {
    if (!input.data || !Array.isArray(input.data) || input.data.length === 0) {
      return 'Type "chart-3d" requires a non-empty `data` array with {label, value} objects.';
    }
    for (const d of input.data) {
      if (typeof d.label !== "string" || typeof d.value !== "number") {
        return "Each data item must have a string `label` and numeric `value`.";
      }
    }
  }

  if (input.type === "geometry") {
    if (!input.shapes || !Array.isArray(input.shapes) || input.shapes.length === 0) {
      return 'Type "geometry" requires a non-empty `shapes` array.';
    }
    const validShapeTypes = ["cube", "sphere", "cylinder", "plane", "line"];
    for (const s of input.shapes) {
      if (!validShapeTypes.includes(s.type)) {
        return `Invalid shape type "${s.type}". Must be one of: ${validShapeTypes.join(", ")}`;
      }
      if (s.position && (!Array.isArray(s.position) || s.position.length !== 3)) {
        return "Shape position must be an array of 3 numbers [x, y, z].";
      }
      if (s.scale && (!Array.isArray(s.scale) || s.scale.length !== 3)) {
        return "Shape scale must be an array of 3 numbers [x, y, z].";
      }
      if (s.color && (!Array.isArray(s.color) || s.color.length !== 3)) {
        return "Shape color must be an array of 3 numbers [r, g, b] in 0-1 range.";
      }
    }
  }

  if (input.modelUrl) {
    if (!input.modelUrl.startsWith("https://") && !input.modelUrl.startsWith("http://")) {
      return "modelUrl must be an HTTP(S) URL.";
    }
  }

  return null;
}

// ─── Generators ──────────────────────────────────────────────────────────────

export function generateArtifact(input: ArtifactInput): ArtifactResult {
  switch (input.type) {
    case "model-viewer":
      return generateModelViewer(input);
    case "chart-3d":
      return generateChart3D(input);
    case "scene":
      return generateScene(input);
    case "product-360":
      return generateProduct360(input);
    case "geometry":
      return generateGeometry(input);
  }
}

// ─── Filament.js renderer core ──────────────────────────────────────────────
//
// Shared inline script that sets up the Filament engine, scene, camera,
// lights, orbit controls, and render loop. Used by model-viewer, scene,
// and product-360 artifact types.

function filamentRendererScript(options: {
  modelUrl: string;
  bgColor: [number, number, number, number];
  autoRotate: boolean;
  orbitRadius?: number;
  orbitHeight?: number;
  sunIntensity?: number;
  fillIntensity?: number;
}): string {
  const {
    modelUrl,
    bgColor,
    autoRotate,
    orbitRadius = 3.5,
    orbitHeight = 0.8,
    sunIntensity = 110000,
    fillIntensity = 30000,
  } = options;

  return `<script src="${FILAMENT_CDN}"></script>
<script>
Filament.init(['${modelUrl}'], function() {
  try {
    var canvas = document.getElementById('viewer');
    canvas.width = canvas.clientWidth * devicePixelRatio;
    canvas.height = canvas.clientHeight * devicePixelRatio;

    var engine = Filament.Engine.create(canvas);
    var scene = engine.createScene();
    var renderer = engine.createRenderer();
    var cam = engine.createCamera(Filament.EntityManager.get().create());
    var view = engine.createView();
    var sc = engine.createSwapChain();

    view.setCamera(cam);
    view.setScene(scene);
    view.setViewport([0, 0, canvas.width, canvas.height]);
    renderer.setClearOptions({ clearColor: [${bgColor.join(",")}], clear: true });
    cam.setProjectionFov(45, canvas.width / canvas.height, 0.1, 100, Filament.Camera$Fov.VERTICAL);

    // Sun light (warm key light)
    var sun = Filament.EntityManager.get().create();
    Filament.LightManager.Builder(Filament.LightManager$Type.SUN)
      .color([0.98, 0.92, 0.89]).intensity(${sunIntensity}).direction([0.6, -1, -0.8])
      .sunAngularRadius(1.9).sunHaloSize(10).sunHaloFalloff(80)
      .build(engine, sun);
    scene.addEntity(sun);

    // Fill light (cool)
    var fill = Filament.EntityManager.get().create();
    Filament.LightManager.Builder(Filament.LightManager$Type.DIRECTIONAL)
      .color([0.6, 0.65, 0.8]).intensity(${fillIntensity}).direction([-0.5, 0.5, 1])
      .build(engine, fill);
    scene.addEntity(fill);

    // Load model
    var loader = engine.createAssetLoader();
    var asset = loader.createAsset(Filament.assets['${modelUrl}']);
    if (asset) {
      asset.loadResources();
      scene.addEntity(asset.getRoot());
      scene.addEntities(asset.getRenderableEntities());
    }

    // Orbit controls
    var angle = 0, orbitR = ${orbitRadius}, orbitH = ${orbitHeight};
    var dragging = false, lastX = 0, lastY = 0, autoRotate = ${autoRotate};

    canvas.addEventListener('mousedown', function(e) { dragging = true; lastX = e.clientX; lastY = e.clientY; autoRotate = false; });
    canvas.addEventListener('mousemove', function(e) { if (!dragging) return; angle -= (e.clientX - lastX) * 0.005; orbitH += (e.clientY - lastY) * 0.01; lastX = e.clientX; lastY = e.clientY; });
    canvas.addEventListener('mouseup', function() { dragging = false; });
    canvas.addEventListener('mouseleave', function() { dragging = false; });
    canvas.addEventListener('wheel', function(e) { e.preventDefault(); orbitR *= (1 + e.deltaY * 0.001); orbitR = Math.max(0.5, Math.min(20, orbitR)); }, { passive: false });
    canvas.addEventListener('touchstart', function(e) { if (e.touches.length === 1) { dragging = true; lastX = e.touches[0].clientX; lastY = e.touches[0].clientY; autoRotate = false; } });
    canvas.addEventListener('touchmove', function(e) { if (!dragging) return; e.preventDefault(); angle -= (e.touches[0].clientX - lastX) * 0.005; orbitH += (e.touches[0].clientY - lastY) * 0.01; lastX = e.touches[0].clientX; lastY = e.touches[0].clientY; }, { passive: false });
    canvas.addEventListener('touchend', function() { dragging = false; });

    // Handle resize
    new ResizeObserver(function() {
      canvas.width = canvas.clientWidth * devicePixelRatio;
      canvas.height = canvas.clientHeight * devicePixelRatio;
      view.setViewport([0, 0, canvas.width, canvas.height]);
      cam.setProjectionFov(45, canvas.width / canvas.height, 0.1, 100, Filament.Camera$Fov.VERTICAL);
    }).observe(canvas);

    // Render loop
    function render() {
      if (autoRotate) angle += 0.006;
      cam.lookAt([Math.sin(angle) * orbitR, orbitH, Math.cos(angle) * orbitR], [0, 0, 0], [0, 1, 0]);
      if (renderer.beginFrame(sc)) { renderer.render(sc, view); renderer.endFrame(); }
      engine.execute();
      requestAnimationFrame(render);
    }
    render();

    // Update status
    var statusEl = document.getElementById('status');
    if (statusEl) { statusEl.textContent = 'Drag to rotate \\u00b7 Scroll to zoom'; statusEl.style.color = '#34a853'; }

  } catch(e) {
    var statusEl = document.getElementById('status');
    if (statusEl) { statusEl.textContent = 'Error: ' + e.message; statusEl.style.color = '#ea4335'; }
    console.error(e);
  }
});
</script>`;
}

/** Convert hex color to Filament RGBA [0-1] range */
function hexToBgColor(hex: string): [number, number, number, number] {
  const rgb = hexToRgb(hex);
  return [rgb.r / 255, rgb.g / 255, rgb.b / 255, 1.0];
}

// ── model-viewer ─────────────────────────────────────────────────────────────

function generateModelViewer(input: ArtifactInput): ArtifactResult {
  const model = input.modelUrl || DEFAULT_MODEL;
  const title = input.title || "3D Model Viewer";
  const opts = input.options || {};
  const bg = opts.backgroundColor || "#0d1117";
  const autoRotate = opts.autoRotate !== false;

  const body = `
<style>
canvas{width:100%;height:100%;cursor:grab;display:block}
canvas:active{cursor:grabbing}
.title{position:absolute;top:16px;left:16px;font-size:18px;font-weight:600;color:#fff;text-shadow:0 2px 8px rgba(0,0,0,0.5);z-index:10}
#status{position:absolute;top:44px;left:16px;font-size:12px;color:#8ab4f8;z-index:10}
</style>
<div class="title">${escapeHtml(title)}</div>
<div id="status">Loading Filament.js WASM...</div>
<canvas id="viewer"></canvas>
${BRANDING}
${filamentRendererScript({ modelUrl: model, bgColor: hexToBgColor(bg), autoRotate })}`;

  return {
    html: htmlShell(title, body),
    title,
    type: "model-viewer",
  };
}

// ── chart-3d ─────────────────────────────────────────────────────────────────

function generateChart3D(input: ArtifactInput): ArtifactResult {
  const title = input.title || "3D Data Visualization";
  const data = input.data!;
  const bg = input.options?.backgroundColor || "#0d1117";
  const maxVal = Math.max(...data.map((d) => d.value));

  const bars = data
    .map((d, i) => {
      const color = d.color || DEFAULT_COLORS[i % DEFAULT_COLORS.length];
      const heightPct = maxVal > 0 ? (d.value / maxVal) * 100 : 0;
      const height = Math.max(heightPct * 2.5, 8); // px scale, min 8px
      return `
      <div class="bar-group" style="--delay:${i * 0.08}s">
        <div class="bar-wrapper">
          <div class="bar-value">${formatNumber(d.value)}</div>
          <div class="bar" style="height:${height}px;background:linear-gradient(180deg,${color},${darken(color, 0.3)});box-shadow:4px 4px 0 ${darken(color, 0.5)}, 0 0 20px ${color}40">
            <div class="bar-face-top" style="background:${lighten(color, 0.15)}"></div>
            <div class="bar-face-right" style="background:${darken(color, 0.2)}"></div>
          </div>
        </div>
        <div class="bar-label">${escapeHtml(d.label)}</div>
      </div>`;
    })
    .join("\n");

  const body = `
<style>
body{background:${bg};display:flex;flex-direction:column;align-items:center;justify-content:center;height:100%;perspective:800px;overflow:hidden}
.chart-title{font-size:22px;font-weight:700;margin-bottom:24px;color:#fff;letter-spacing:-0.5px}
.chart-subtitle{font-size:13px;color:#888;margin-bottom:32px}
.chart-container{display:flex;align-items:flex-end;gap:clamp(8px,2vw,24px);padding:24px 32px;transform:rotateX(8deg) rotateY(-8deg);transform-style:preserve-3d;background:rgba(255,255,255,0.03);border-radius:16px;border:1px solid rgba(255,255,255,0.06);max-width:95vw;overflow-x:auto}
.bar-group{display:flex;flex-direction:column;align-items:center;animation:barIn 0.6s ease-out var(--delay) both}
.bar-wrapper{position:relative;display:flex;flex-direction:column;align-items:center}
.bar{width:clamp(28px,6vw,56px);border-radius:4px 4px 0 0;position:relative;transform-style:preserve-3d;transition:height 0.5s ease-out}
.bar-face-top{position:absolute;top:-6px;left:-2px;right:-2px;height:8px;border-radius:4px 4px 0 0;transform:rotateX(45deg);transform-origin:bottom}
.bar-face-right{position:absolute;top:0;right:-6px;bottom:0;width:8px;border-radius:0 4px 4px 0;transform:rotateY(45deg);transform-origin:left}
.bar-value{font-size:12px;font-weight:600;color:#fff;margin-bottom:6px;white-space:nowrap}
.bar-label{font-size:11px;color:#aaa;margin-top:8px;text-align:center;max-width:72px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
@keyframes barIn{from{opacity:0;transform:scaleY(0) translateY(20px)}to{opacity:1;transform:scaleY(1) translateY(0)}}
.total-row{margin-top:24px;font-size:14px;color:#aaa}
.total-row strong{color:#fff;font-size:16px}
</style>
<div class="chart-title">${escapeHtml(title)}</div>
<div class="chart-subtitle">${data.length} data points</div>
<div class="chart-container">
${bars}
</div>
<div class="total-row">Total: <strong>${formatNumber(data.reduce((s, d) => s + d.value, 0))}</strong></div>
${BRANDING}`;

  return {
    html: htmlShell(title, body),
    title,
    type: "chart-3d",
  };
}

// ── scene ────────────────────────────────────────────────────────────────────

function generateScene(input: ArtifactInput): ArtifactResult {
  const model = input.modelUrl || DEFAULT_MODEL;
  const title = input.title || "3D Scene";
  const opts = input.options || {};
  const bg = opts.backgroundColor || "#0d1117";
  const autoRotate = opts.autoRotate !== false;

  const body = `
<style>
canvas{width:100%;height:100%;cursor:grab;display:block}
canvas:active{cursor:grabbing}
.scene-title{position:absolute;top:16px;left:16px;font-size:20px;font-weight:700;color:#fff;text-shadow:0 2px 12px rgba(0,0,0,0.6);z-index:10;letter-spacing:-0.3px}
.scene-info{position:absolute;top:44px;left:16px;font-size:12px;color:#aaa;z-index:10}
#status{position:absolute;top:64px;left:16px;font-size:11px;color:#8ab4f8;z-index:10}
.controls-hint{position:absolute;bottom:16px;left:16px;font-size:11px;color:#666;z-index:10}
</style>
<div class="scene-title">${escapeHtml(title)}</div>
<div class="scene-info">Interactive 3D Scene</div>
<div id="status">Loading Filament.js WASM...</div>
<canvas id="viewer"></canvas>
<div class="controls-hint">Drag to orbit &bull; Scroll to zoom &bull; Two-finger to pan</div>
${BRANDING}
${filamentRendererScript({ modelUrl: model, bgColor: hexToBgColor(bg), autoRotate, sunIntensity: 120000, fillIntensity: 35000, orbitRadius: 4.0, orbitHeight: 1.0 })}`;

  return {
    html: htmlShell(title, body),
    title,
    type: "scene",
  };
}

// ── product-360 ──────────────────────────────────────────────────────────────

function generateProduct360(input: ArtifactInput): ArtifactResult {
  const model = input.modelUrl || DEFAULT_MODEL;
  const title = input.title || "Product 360\u00B0 View";
  const opts = input.options || {};
  const bg = opts.backgroundColor || "#0d1117";
  const hotspots = input.hotspots || [];

  // Product-360 always auto-rotates (with a slower speed handled in the core script)
  const autoRotate = true;

  const hotspotHtml =
    hotspots.length > 0
      ? `
<div class="hotspots-overlay" id="hotspots">
  ${hotspots
    .map(
      (h, i) => `
  <div class="hotspot" id="hotspot-${i}" data-position="${escapeHtml(h.position)}" data-normal="${escapeHtml(h.normal)}">
    <div class="hotspot-dot"></div>
    <div class="hotspot-annotation">
      <div class="hotspot-title">${escapeHtml(h.label)}</div>
      ${h.description ? `<div class="hotspot-desc">${escapeHtml(h.description)}</div>` : ""}
    </div>
  </div>`
    )
    .join("\n")}
</div>`
      : "";

  const body = `
<style>
canvas{width:100%;height:100%;cursor:grab;display:block}
canvas:active{cursor:grabbing}
.product-header{position:absolute;top:0;left:0;right:0;padding:16px 20px;background:linear-gradient(180deg,rgba(0,0,0,0.6),transparent);z-index:10}
.product-title{font-size:20px;font-weight:700;color:#fff}
.product-subtitle{font-size:12px;color:#aaa;margin-top:2px}
#status{position:absolute;top:70px;left:20px;font-size:11px;color:#8ab4f8;z-index:10}
.hotspots-overlay{position:absolute;top:0;left:0;right:0;bottom:0;pointer-events:none;z-index:5}
.hotspot{position:absolute;pointer-events:auto;cursor:pointer}
.hotspot-dot{width:24px;height:24px;border-radius:50%;border:2px solid #fff;background:rgba(66,133,244,0.8);box-shadow:0 2px 8px rgba(0,0,0,0.4);transition:transform 0.2s}
.hotspot:hover .hotspot-dot{transform:scale(1.3)}
.hotspot-annotation{position:absolute;bottom:32px;left:50%;transform:translateX(-50%);background:rgba(30,30,50,0.95);border:1px solid rgba(255,255,255,0.1);border-radius:8px;padding:8px 12px;white-space:nowrap;pointer-events:none;opacity:0;transition:opacity 0.2s}
.hotspot:hover .hotspot-annotation{opacity:1}
.hotspot-title{font-size:13px;font-weight:600;color:#fff}
.hotspot-desc{font-size:11px;color:#aaa;margin-top:2px}
.rotate-hint{position:absolute;bottom:16px;left:20px;font-size:11px;color:#666;z-index:10}
</style>
<div class="product-header">
  <div class="product-title">${escapeHtml(title)}</div>
  <div class="product-subtitle">Drag to rotate &bull; Pinch to zoom${hotspots.length > 0 ? " &bull; Tap hotspots for details" : ""}</div>
</div>
<div id="status">Loading Filament.js WASM...</div>
<canvas id="viewer"></canvas>
${hotspotHtml}
<div class="rotate-hint">360&deg; interactive view</div>
${BRANDING}
${filamentRendererScript({ modelUrl: model, bgColor: hexToBgColor(bg), autoRotate })}`;

  return {
    html: htmlShell(title, body),
    title,
    type: "product-360",
  };
}

// ── geometry ──────────────────────────────────────────────────────────────────
//
// Procedural 3D geometry renderer — zero dependencies, pure WebGL2 PBR.
// Claude can "draw" in 3D: cubes, spheres, cylinders, planes, lines.

function generateGeometry(input: ArtifactInput): ArtifactResult {
  const title = input.title || "3D Geometry";
  const opts = input.options || {};
  const bg = opts.backgroundColor || "#0d1117";
  const autoRotate = opts.autoRotate !== false;
  const shapes = input.shapes || [];

  // Compute camera distance from scene bounds
  let maxDist = 2;
  for (const s of shapes) {
    const p = s.position || [0, 0, 0];
    const sc = s.scale || [1, 1, 1];
    const d = Math.sqrt(p[0] * p[0] + p[1] * p[1] + p[2] * p[2]) + Math.max(sc[0], sc[1], sc[2]);
    if (d > maxDist) maxDist = d;
  }
  const camDist = Math.max(4, maxDist * 1.8);

  // Compute center-of-mass for the look-at target
  let cx = 0,
    cy = 0,
    cz = 0;
  for (const s of shapes) {
    const p = s.position || [0, 0, 0];
    cx += p[0];
    cy += p[1];
    cz += p[2];
  }
  if (shapes.length > 0) {
    cx /= shapes.length;
    cy /= shapes.length;
    cz /= shapes.length;
  }

  const shapesJson = JSON.stringify(
    shapes.map((s) => ({
      type: s.type,
      position: s.position || [0, 0, 0],
      scale: s.scale || [1, 1, 1],
      color: s.color || [0.8, 0.8, 0.8],
      metallic: s.metallic ?? 0.0,
      roughness: s.roughness ?? 0.5,
    }))
  );

  const bgRgb = hexToRgb(bg);
  const bgR = (bgRgb.r / 255).toFixed(3);
  const bgG = (bgRgb.g / 255).toFixed(3);
  const bgB = (bgRgb.b / 255).toFixed(3);

  const body = `
<style>
canvas{width:100%;height:100%;display:block;cursor:grab}
canvas:active{cursor:grabbing}
.geo-title{position:absolute;top:16px;left:16px;font-size:18px;font-weight:700;color:#fff;text-shadow:0 2px 8px rgba(0,0,0,0.5);z-index:10}
.geo-info{position:absolute;top:42px;left:16px;font-size:12px;color:#888;z-index:10}
.controls-hint{position:absolute;bottom:16px;left:16px;font-size:11px;color:#666;z-index:10}
</style>
<div class="geo-title">${escapeHtml(title)}</div>
<div class="geo-info">${shapes.length} shape${shapes.length !== 1 ? "s" : ""} &bull; Procedural geometry</div>
<canvas id="c"></canvas>
<div class="controls-hint">Drag to orbit &bull; Scroll to zoom</div>
${BRANDING}
<script>
(function(){
var canvas=document.getElementById('c');
var gl=canvas.getContext('webgl2');
if(!gl){document.body.innerHTML='<p style="color:#fff;padding:40px">WebGL2 not supported</p>';return;}

var VS=\`#version 300 es
precision highp float;
in vec3 aPos;
in vec3 aNormal;
uniform mat4 uMVP;
uniform mat4 uModel;
uniform mat3 uNormalMatrix;
out vec3 vNormal;
out vec3 vWorldPos;
void main(){
  vec4 wp=uModel*vec4(aPos,1.0);
  vWorldPos=wp.xyz;
  vNormal=normalize(uNormalMatrix*aNormal);
  gl_Position=uMVP*vec4(aPos,1.0);
}\`;

var FS=\`#version 300 es
precision highp float;
in vec3 vNormal;
in vec3 vWorldPos;
uniform vec3 uCamPos;
uniform vec3 uLightDir;
uniform vec3 uLightColor;
uniform vec3 uBaseColor;
uniform float uMetallic;
uniform float uRoughness;
out vec4 fragColor;
const float PI=3.14159265;
float D_GGX(float NdotH,float r){float a=r*r;float a2=a*a;float d=NdotH*NdotH*(a2-1.0)+1.0;return a2/(PI*d*d);}
vec3 F_Schlick(float ct,vec3 F0){return F0+(1.0-F0)*pow(1.0-ct,5.0);}
float G_Smith(float NdotV,float NdotL,float r){float k=((r+1.0)*(r+1.0))/8.0;return(NdotV/(NdotV*(1.0-k)+k))*(NdotL/(NdotL*(1.0-k)+k));}
void main(){
  vec3 N=normalize(vNormal);vec3 V=normalize(uCamPos-vWorldPos);vec3 L=normalize(uLightDir);vec3 H=normalize(V+L);
  float NdotL=max(dot(N,L),0.0);float NdotV=max(dot(N,V),0.001);float NdotH=max(dot(N,H),0.0);float HdotV=max(dot(H,V),0.0);
  vec3 F0=mix(vec3(0.04),uBaseColor,uMetallic);vec3 F=F_Schlick(HdotV,F0);
  float D=D_GGX(NdotH,uRoughness);float G=G_Smith(NdotV,NdotL,uRoughness);
  vec3 spec=(D*F*G)/(4.0*NdotV*NdotL+0.001);
  vec3 kD=(1.0-F)*(1.0-uMetallic);vec3 diff=kD*uBaseColor/PI;
  vec3 color=(diff+spec)*uLightColor*NdotL;
  float hem=dot(N,vec3(0,1,0))*0.5+0.5;
  color+=mix(vec3(0.05,0.04,0.03),vec3(0.15,0.2,0.35),hem)*uBaseColor*0.4;
  color+=vec3(0.1,0.15,0.3)*pow(1.0-NdotV,3.0)*0.5;
  color=color*(2.51*color+0.03)/(color*(2.43*color+0.59)+0.14);
  color=pow(color,vec3(1.0/2.2));
  fragColor=vec4(color,1.0);
}\`;

var GVS=\`#version 300 es
precision highp float;
in vec3 aPos;
uniform mat4 uMVP;
void main(){gl_Position=uMVP*vec4(aPos,1.0);}\`;
var GFS=\`#version 300 es
precision highp float;
out vec4 fragColor;
void main(){fragColor=vec4(1.0,1.0,1.0,0.06);}\`;

function cShader(t,s){var sh=gl.createShader(t);gl.shaderSource(sh,s);gl.compileShader(sh);return sh;}
function cProg(v,f){var p=gl.createProgram();gl.attachShader(p,v);gl.attachShader(p,f);gl.linkProgram(p);return p;}

var prog=cProg(cShader(gl.VERTEX_SHADER,VS),cShader(gl.FRAGMENT_SHADER,FS));
var L={aPos:gl.getAttribLocation(prog,'aPos'),aNormal:gl.getAttribLocation(prog,'aNormal'),
  uMVP:gl.getUniformLocation(prog,'uMVP'),uModel:gl.getUniformLocation(prog,'uModel'),
  uNormalMatrix:gl.getUniformLocation(prog,'uNormalMatrix'),uCamPos:gl.getUniformLocation(prog,'uCamPos'),
  uLightDir:gl.getUniformLocation(prog,'uLightDir'),uLightColor:gl.getUniformLocation(prog,'uLightColor'),
  uBaseColor:gl.getUniformLocation(prog,'uBaseColor'),uMetallic:gl.getUniformLocation(prog,'uMetallic'),
  uRoughness:gl.getUniformLocation(prog,'uRoughness')};

var gProg=cProg(cShader(gl.VERTEX_SHADER,GVS),cShader(gl.FRAGMENT_SHADER,GFS));
var gL={aPos:gl.getAttribLocation(gProg,'aPos'),uMVP:gl.getUniformLocation(gProg,'uMVP')};

function genSphere(r,ws,hs){var p=[],n=[],ix=[];
for(var y=0;y<=hs;y++)for(var x=0;x<=ws;x++){var u=x/ws,v=y/hs,th=u*Math.PI*2,ph=v*Math.PI;
var sp=Math.sin(ph),cp=Math.cos(ph),st=Math.sin(th),ct=Math.cos(th);
var nx=sp*ct,ny=cp,nz=sp*st;p.push(r*nx,r*ny,r*nz);n.push(nx,ny,nz);}
for(var y=0;y<hs;y++)for(var x=0;x<ws;x++){var a=y*(ws+1)+x,b=a+ws+1;ix.push(a,b,a+1,b,b+1,a+1);}
return{positions:new Float32Array(p),normals:new Float32Array(n),indices:new Uint16Array(ix)};}

function genCube(sz){var s=sz/2;var faces=[
{n:[0,0,1],v:[[-s,-s,s],[s,-s,s],[s,s,s],[-s,s,s]]},{n:[0,0,-1],v:[[s,-s,-s],[-s,-s,-s],[-s,s,-s],[s,s,-s]]},
{n:[0,1,0],v:[[-s,s,s],[s,s,s],[s,s,-s],[-s,s,-s]]},{n:[0,-1,0],v:[[-s,-s,-s],[s,-s,-s],[s,-s,s],[-s,-s,s]]},
{n:[1,0,0],v:[[s,-s,s],[s,-s,-s],[s,s,-s],[s,s,s]]},{n:[-1,0,0],v:[[-s,-s,-s],[-s,-s,s],[-s,s,s],[-s,s,-s]]}];
var p=[],n=[],ix=[];faces.forEach(function(f,i){f.v.forEach(function(vt){p.push(vt[0],vt[1],vt[2]);n.push(f.n[0],f.n[1],f.n[2]);});
var o=i*4;ix.push(o,o+1,o+2,o,o+2,o+3);});
return{positions:new Float32Array(p),normals:new Float32Array(n),indices:new Uint16Array(ix)};}

function genCyl(rT,rB,h,seg){var p=[],n=[],ix=[],hH=h/2;
for(var i=0;i<=seg;i++){var u=i/seg,a=u*Math.PI*2,c=Math.cos(a),s=Math.sin(a),sl=(rB-rT)/h,nl=Math.sqrt(1+sl*sl);
p.push(rT*c,hH,rT*s);n.push(c/nl,sl/nl,s/nl);p.push(rB*c,-hH,rB*s);n.push(c/nl,sl/nl,s/nl);}
for(var i=0;i<seg;i++){var a=i*2;ix.push(a,a+1,a+2,a+1,a+3,a+2);}
function addCap(y,r,ny){var ct=p.length/3;p.push(0,y,0);n.push(0,ny,0);
for(var i=0;i<=seg;i++){var a=(i/seg)*Math.PI*2;p.push(r*Math.cos(a),y,r*Math.sin(a));n.push(0,ny,0);}
for(var i=0;i<seg;i++){if(ny>0)ix.push(ct,ct+i+1,ct+i+2);else ix.push(ct,ct+i+2,ct+i+1);}}
addCap(hH,rT,1);addCap(-hH,rB,-1);
return{positions:new Float32Array(p),normals:new Float32Array(n),indices:new Uint16Array(ix)};}

function genPlane(w,d){var hw=w/2,hd=d/2;
return{positions:new Float32Array([-hw,0,-hd,hw,0,-hd,hw,0,hd,-hw,0,hd]),
normals:new Float32Array([0,1,0,0,1,0,0,1,0,0,1,0]),indices:new Uint16Array([0,1,2,0,2,3])};}

function genGrid(sz,div){var p=[],n=[],ix=[],st=sz/div,h=sz/2,vi=0;
for(var i=0;i<=div;i++){var t=i*st-h;p.push(-h,0,t,h,0,t);n.push(0,1,0,0,1,0);ix.push(vi,vi+1);vi+=2;
p.push(t,0,-h,t,0,h);n.push(0,1,0,0,1,0);ix.push(vi,vi+1);vi+=2;}
return{positions:new Float32Array(p),normals:new Float32Array(n),indices:new Uint16Array(ix)};}

function mkMesh(g){var vao=gl.createVertexArray();gl.bindVertexArray(vao);
var pb=gl.createBuffer();gl.bindBuffer(gl.ARRAY_BUFFER,pb);gl.bufferData(gl.ARRAY_BUFFER,g.positions,gl.STATIC_DRAW);
gl.enableVertexAttribArray(L.aPos);gl.vertexAttribPointer(L.aPos,3,gl.FLOAT,false,0,0);
var nb=gl.createBuffer();gl.bindBuffer(gl.ARRAY_BUFFER,nb);gl.bufferData(gl.ARRAY_BUFFER,g.normals,gl.STATIC_DRAW);
gl.enableVertexAttribArray(L.aNormal);gl.vertexAttribPointer(L.aNormal,3,gl.FLOAT,false,0,0);
var ib=gl.createBuffer();gl.bindBuffer(gl.ELEMENT_ARRAY_BUFFER,ib);gl.bufferData(gl.ELEMENT_ARRAY_BUFFER,g.indices,gl.STATIC_DRAW);
gl.bindVertexArray(null);return{vao:vao,count:g.indices.length};}

function mkGridMesh(g){var vao=gl.createVertexArray();gl.bindVertexArray(vao);
var pb=gl.createBuffer();gl.bindBuffer(gl.ARRAY_BUFFER,pb);gl.bufferData(gl.ARRAY_BUFFER,g.positions,gl.STATIC_DRAW);
gl.enableVertexAttribArray(gL.aPos);gl.vertexAttribPointer(gL.aPos,3,gl.FLOAT,false,0,0);
var ib=gl.createBuffer();gl.bindBuffer(gl.ELEMENT_ARRAY_BUFFER,ib);gl.bufferData(gl.ELEMENT_ARRAY_BUFFER,g.indices,gl.STATIC_DRAW);
gl.bindVertexArray(null);return{vao:vao,count:g.indices.length};}

var protos={cube:mkMesh(genCube(1)),sphere:mkMesh(genSphere(0.5,32,24)),
cylinder:mkMesh(genCyl(0.5,0.5,1,32)),plane:mkMesh(genPlane(1,1)),line:mkMesh(genCyl(0.02,0.02,1,8))};
var grid=mkGridMesh(genGrid(10,20));

var shapes=${shapesJson};

function m4Persp(fov,asp,n,f){var t=1/Math.tan(fov/2),nf=1/(n-f);return new Float32Array([t/asp,0,0,0,0,t,0,0,0,0,(f+n)*nf,-1,0,0,2*f*n*nf,0]);}
function vNorm(v){var l=Math.sqrt(v[0]*v[0]+v[1]*v[1]+v[2]*v[2]);return l>0?[v[0]/l,v[1]/l,v[2]/l]:[0,0,0];}
function vSub(a,b){return[a[0]-b[0],a[1]-b[1],a[2]-b[2]];}
function vCross(a,b){return[a[1]*b[2]-a[2]*b[1],a[2]*b[0]-a[0]*b[2],a[0]*b[1]-a[1]*b[0]];}
function vDot(a,b){return a[0]*b[0]+a[1]*b[1]+a[2]*b[2];}
function m4LookAt(e,c,u){var z=vNorm(vSub(e,c)),x=vNorm(vCross(u,z)),y=vCross(z,x);
return new Float32Array([x[0],y[0],z[0],0,x[1],y[1],z[1],0,x[2],y[2],z[2],0,-vDot(x,e),-vDot(y,e),-vDot(z,e),1]);}
function m4Mul(a,b){var r=new Float32Array(16);for(var i=0;i<4;i++)for(var j=0;j<4;j++){var s=0;for(var k=0;k<4;k++)s+=a[k*4+j]*b[i*4+k];r[i*4+j]=s;}return r;}
function m4Trans(x,y,z){return new Float32Array([1,0,0,0,0,1,0,0,0,0,1,0,x,y,z,1]);}
function m4Scale(x,y,z){return new Float32Array([x,0,0,0,0,y,0,0,0,0,z,0,0,0,0,1]);}
function m3Normal(m){return new Float32Array([m[0],m[1],m[2],m[4],m[5],m[6],m[8],m[9],m[10]]);}

var oTheta=0.4,oPhi=0.7,oDist=${camDist.toFixed(1)};
var autoR=${autoRotate},isDrag=false,lx=0,ly=0;
canvas.addEventListener('pointerdown',function(e){isDrag=true;lx=e.clientX;ly=e.clientY;autoR=false;canvas.setPointerCapture(e.pointerId);});
canvas.addEventListener('pointermove',function(e){if(!isDrag)return;oTheta-=(e.clientX-lx)*0.008;oPhi=Math.max(0.1,Math.min(Math.PI-0.1,oPhi-(e.clientY-ly)*0.008));lx=e.clientX;ly=e.clientY;});
canvas.addEventListener('pointerup',function(){isDrag=false;});
canvas.addEventListener('wheel',function(e){oDist=Math.max(2,Math.min(30,oDist+e.deltaY*0.005));e.preventDefault();},{passive:false});
var lpd=0;
canvas.addEventListener('touchstart',function(e){if(e.touches.length===2){var dx=e.touches[0].clientX-e.touches[1].clientX,dy=e.touches[0].clientY-e.touches[1].clientY;lpd=Math.sqrt(dx*dx+dy*dy);}},{passive:true});
canvas.addEventListener('touchmove',function(e){if(e.touches.length===2){var dx=e.touches[0].clientX-e.touches[1].clientX,dy=e.touches[0].clientY-e.touches[1].clientY;var d=Math.sqrt(dx*dx+dy*dy);oDist=Math.max(2,Math.min(30,oDist-(d-lpd)*0.02));lpd=d;}},{passive:true});

function resize(){var dpr=Math.min(window.devicePixelRatio||1,2);canvas.width=canvas.clientWidth*dpr;canvas.height=canvas.clientHeight*dpr;gl.viewport(0,0,canvas.width,canvas.height);}
resize();window.addEventListener('resize',resize);

function render(){
  requestAnimationFrame(render);
  if(autoR)oTheta+=0.004;
  var cx=oDist*Math.sin(oPhi)*Math.cos(oTheta),cy=oDist*Math.cos(oPhi),cz=oDist*Math.sin(oPhi)*Math.sin(oTheta);
  var camPos=[cx,cy,cz];
  var proj=m4Persp(Math.PI/4,canvas.width/canvas.height,0.1,100);
  var view=m4LookAt(camPos,[${cx.toFixed(2)},${cy.toFixed(2)},${cz.toFixed(2)}],[0,1,0]);
  var vp=m4Mul(proj,view);

  gl.clearColor(${bgR},${bgG},${bgB},1.0);
  gl.clear(gl.COLOR_BUFFER_BIT|gl.DEPTH_BUFFER_BIT);
  gl.enable(gl.DEPTH_TEST);
  gl.enable(gl.BLEND);
  gl.blendFunc(gl.SRC_ALPHA,gl.ONE_MINUS_SRC_ALPHA);

  gl.useProgram(gProg);
  gl.uniformMatrix4fv(gL.uMVP,false,vp);
  gl.bindVertexArray(grid.vao);
  gl.drawElements(gl.LINES,grid.count,gl.UNSIGNED_SHORT,0);

  gl.useProgram(prog);
  gl.uniform3fv(L.uCamPos,camPos);
  gl.uniform3f(L.uLightDir,0.5,0.8,0.3);
  gl.uniform3f(L.uLightColor,3.0,2.9,2.7);

  for(var i=0;i<shapes.length;i++){
    var sh=shapes[i],mesh=protos[sh.type];if(!mesh)continue;
    var p=sh.position,s=sh.scale;
    var model=m4Mul(m4Trans(p[0],p[1],p[2]),m4Scale(s[0],s[1],s[2]));
    var mvp=m4Mul(vp,model),nm=m3Normal(model);
    gl.uniformMatrix4fv(L.uMVP,false,mvp);
    gl.uniformMatrix4fv(L.uModel,false,model);
    gl.uniformMatrix3fv(L.uNormalMatrix,false,nm);
    gl.uniform3fv(L.uBaseColor,sh.color);
    gl.uniform1f(L.uMetallic,sh.metallic);
    gl.uniform1f(L.uRoughness,sh.roughness);
    gl.bindVertexArray(mesh.vao);
    gl.drawElements(gl.TRIANGLES,mesh.count,gl.UNSIGNED_SHORT,0);
  }
}
requestAnimationFrame(render);
})();
</script>`;

  return {
    html: htmlShell(title, body),
    title,
    type: "geometry",
  };
}

// ─── Utilities ───────────────────────────────────────────────────────────────

function formatNumber(n: number): string {
  if (n >= 1_000_000) return (n / 1_000_000).toFixed(1).replace(/\.0$/, "") + "M";
  if (n >= 1_000) return (n / 1_000).toFixed(1).replace(/\.0$/, "") + "K";
  return n.toLocaleString("en-US");
}

/** Darken a hex colour by a fraction (0..1). */
function darken(hex: string, amount: number): string {
  const rgb = hexToRgb(hex);
  return rgbToHex(
    Math.round(rgb.r * (1 - amount)),
    Math.round(rgb.g * (1 - amount)),
    Math.round(rgb.b * (1 - amount))
  );
}

/** Lighten a hex colour by a fraction (0..1). */
function lighten(hex: string, amount: number): string {
  const rgb = hexToRgb(hex);
  return rgbToHex(
    Math.min(255, Math.round(rgb.r + (255 - rgb.r) * amount)),
    Math.min(255, Math.round(rgb.g + (255 - rgb.g) * amount)),
    Math.min(255, Math.round(rgb.b + (255 - rgb.b) * amount))
  );
}

function hexToRgb(hex: string): { r: number; g: number; b: number } {
  const h = hex.replace("#", "");
  return {
    r: parseInt(h.substring(0, 2), 16),
    g: parseInt(h.substring(2, 4), 16),
    b: parseInt(h.substring(4, 6), 16),
  };
}

function rgbToHex(r: number, g: number, b: number): string {
  return "#" + [r, g, b].map((c) => c.toString(16).padStart(2, "0")).join("");
}

// ─── Format response ─────────────────────────────────────────────────────────

export function formatArtifactResponse(result: ArtifactResult): string {
  const lines: string[] = [
    `## ${result.title}`,
    ``,
    `Here is your interactive 3D ${result.type === "chart-3d" ? "chart" : result.type === "geometry" ? "scene" : "content"} powered by ${result.type === "geometry" ? "WebGL PBR" : "Filament.js"} (SceneView engine):`,
    ``,
    `\`\`\`html`,
    result.html,
    `\`\`\``,
    ``,
    `**How to use this:**`,
    `- Copy the HTML above into a file and open in a browser`,
    `- Or paste into any HTML preview tool`,
    `- Drag to orbit, scroll/pinch to zoom`,
    `- Uses Filament.js WASM for real-time PBR rendering`,
    ``,
    `---`,
    `*Powered by SceneView + Filament.js \u2014 3D & AR for every platform*`,
  ];
  return lines.join("\n");
}
