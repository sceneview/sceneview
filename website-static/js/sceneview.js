/**
 * SceneView Web — Simple 3D for the web.
 *
 * One line to render a 3D model:
 *   SceneView.modelViewer("canvas", "model.glb")
 *
 * Text, Image, and Video nodes:
 *   const sv = await SceneView.create("canvas");
 *   sv.createText({ text: "Hello 3D", position: [0, 2, 0] });
 *   sv.createImage({ url: "photo.jpg", position: [1, 1, 0] });
 *   sv.createVideo({ url: "clip.mp4", position: [-1, 1, 0] });
 *
 * Quality, Bloom, and Lighting:
 *   sv.setQuality("high");
 *   sv.setBloom(true);
 *   sv.addLight({ type: "point", position: [2, 3, 0], color: [1, 0.9, 0.8] });
 *
 * Powered by Filament.js v1.70.2 (Google's PBR renderer, WASM).
 * https://sceneview.github.io
 *
 * @version 4.18.0
 * @license MIT
 */
(function(global) {
  'use strict';

  // Filament.js is loaded via <script> tag in HTML (js/filament/filament.js)
  // This avoids dynamic script injection issues with WASM resolution.

  /**
   * Info-level logging, silent in production (#2568). Opt in from the console
   * with `window.SCENEVIEW_DEBUG = true`. Warnings/errors are NOT gated —
   * real degradations must stay visible.
   */
  function _log() {
    if (typeof global !== 'undefined' && global.SCENEVIEW_DEBUG && typeof console !== 'undefined') {
      console.log.apply(console, arguments);
    }
  }

  /**
   * Wait for Filament to be available (loaded by the script tag).
   */
  function _ensureFilament() {
    return new Promise(function(resolve, reject) {
      if (typeof Filament !== 'undefined') { resolve(); return; }
      // Poll briefly in case the script tag hasn't finished loading
      var attempts = 0;
      var check = setInterval(function() {
        if (typeof Filament !== 'undefined') { clearInterval(check); resolve(); }
        if (++attempts > 100) { clearInterval(check); reject(new Error('SceneView: Filament.js not loaded')); }
      }, 50);
    });
  }

  /** Resolve a canvas element from an element or an id string. */
  function _resolveCanvas(canvasOrId) {
    if (typeof document === 'undefined') return null;
    return typeof canvasOrId === 'string' ? document.getElementById(canvasOrId) : canvasOrId;
  }

  /**
   * Paint a subtle "3D preview unavailable" placeholder over a canvas (#2509,
   * #2563). Uses the site's DESIGN.md CSS custom properties so it adapts to
   * light/dark automatically — no hardcoded colors. Pass the previously created
   * overlay element (or null) — the same element is reused/re-aligned so the
   * painter stays idempotent. Returns the overlay element, or null if it could
   * not be painted.
   */
  function _paintFallbackOverlay(canvas, existingEl) {
    if (typeof document === 'undefined' || !canvas) return null;
    var parent = canvas.parentNode;
    if (!parent) return null;

    // Ensure the overlay can position itself over the canvas box.
    var parentPos = (window.getComputedStyle ? getComputedStyle(parent).position : '');
    if (parentPos === 'static') parent.style.position = 'relative';

    var el = existingEl;
    if (!el) {
      el = document.createElement('div');
      el.className = 'sceneview-fallback';
      el.setAttribute('role', 'img');
      el.setAttribute('aria-label', '3D preview unavailable');
      // Dimmed surface + subtle border + secondary text, all from design tokens
      // (styles.css :root / [data-theme="dark"]) so it themes automatically.
      el.style.cssText = [
        'position:absolute',
        'box-sizing:border-box',
        'display:flex',
        'align-items:center',
        'justify-content:center',
        'text-align:center',
        'pointer-events:none',
        'background:var(--color-surface-container, #1a1a2e)',
        'color:var(--color-on-surface-variant, #9fb2dd)',
        'border:1px solid var(--color-outline-variant, #2a3346)',
        'font-family:var(--font-body, system-ui, -apple-system, sans-serif)',
        'font-size:0.85rem',
        'line-height:1.4',
        'padding:8px 12px'
      ].join(';');
      var label = document.createElement('span');
      label.textContent = '3D preview unavailable';
      label.style.opacity = '0.75';
      el.appendChild(label);
      // Insert immediately after the canvas so it stacks above it.
      if (canvas.nextSibling) parent.insertBefore(el, canvas.nextSibling);
      else parent.appendChild(el);
    }
    // Align the overlay to the canvas box (handles parents with sibling content,
    // e.g. claude-3d's label/badge, without covering them).
    el.style.left = canvas.offsetLeft + 'px';
    el.style.top = canvas.offsetTop + 'px';
    el.style.width = (canvas.offsetWidth || canvas.clientWidth) + 'px';
    el.style.height = (canvas.offsetHeight || canvas.clientHeight) + 'px';
    // Match the canvas corner radius if the container rounds it.
    try {
      var br = getComputedStyle(canvas).borderRadius;
      if (br && br !== '0px') el.style.borderRadius = br;
      else {
        var pbr = getComputedStyle(parent).borderRadius;
        if (pbr && pbr !== '0px') el.style.borderRadius = pbr;
      }
    } catch (e) { /* ignore */ }
    return el;
  }

  /**
   * Paint the "3D preview unavailable" placeholder when the Filament engine
   * itself fails to initialize (#2563) — e.g. WASM blocked by CSP, asset 404,
   * or a stuck init. Happens before any SceneView instance exists, so the
   * overlay is tracked on the canvas element itself (idempotent per canvas).
   */
  function _showInitFallback(canvasOrId) {
    var canvas = _resolveCanvas(canvasOrId);
    if (!canvas) return;
    console.warn('SceneView: 3D engine failed to initialize, showing fallback');
    var el = _paintFallbackOverlay(canvas, canvas.__sceneviewInitFallbackEl || null);
    if (el) canvas.__sceneviewInitFallbackEl = el;
  }

  // ---------------------------------------------------------------
  // Minimal GLB generator — creates a 1x1 textured quad in memory
  // ---------------------------------------------------------------

  /**
   * Generate a minimal glTF-binary (GLB) containing a 1x1 unit quad plane
   * with an unlit material and a 2x2 white base texture.
   * This lets us use the existing gltfio UbershaderProvider for materials,
   * then swap the texture at runtime for text/image/video content.
   *
   * The quad is in the XY plane, centered at origin, 1 unit wide and tall.
   * Vertices: (-0.5,-0.5,0), (0.5,-0.5,0), (0.5,0.5,0), (-0.5,0.5,0)
   * UVs: (0,1), (1,1), (1,0), (0,0)
   * Triangles: [0,1,2], [0,2,3]
   */
  function _buildQuadGLB() {
    // ---- JSON chunk ----
    var gltf = {
      asset: { version: "2.0", generator: "SceneView.js" },
      scene: 0,
      scenes: [{ nodes: [0] }],
      nodes: [{ mesh: 0, name: "quad" }],
      meshes: [{
        primitives: [{
          attributes: { POSITION: 0, TEXCOORD_0: 1 },
          indices: 2,
          material: 0,
          mode: 4 // TRIANGLES
        }]
      }],
      materials: [{
        name: "unlit_tex",
        pbrMetallicRoughness: {
          baseColorTexture: { index: 0 },
          metallicFactor: 0.0,
          roughnessFactor: 1.0
        },
        alphaMode: "BLEND",
        doubleSided: true,
        extensions: { KHR_materials_unlit: {} }
      }],
      extensionsUsed: ["KHR_materials_unlit"],
      textures: [{ source: 0, sampler: 0 }],
      images: [{
        bufferView: 3,
        mimeType: "image/png"
      }],
      samplers: [{
        magFilter: 9729, // LINEAR
        minFilter: 9987, // LINEAR_MIPMAP_LINEAR
        wrapS: 33071,    // CLAMP_TO_EDGE
        wrapT: 33071
      }],
      accessors: [
        { bufferView: 0, componentType: 5126, count: 4, type: "VEC3",
          max: [0.5, 0.5, 0], min: [-0.5, -0.5, 0] },
        { bufferView: 1, componentType: 5126, count: 4, type: "VEC2" },
        { bufferView: 2, componentType: 5123, count: 6, type: "SCALAR" }
      ],
      bufferViews: [
        { buffer: 0, byteOffset: 0, byteLength: 48, target: 34962 },   // positions
        { buffer: 0, byteOffset: 48, byteLength: 32, target: 34962 },  // uvs
        { buffer: 0, byteOffset: 80, byteLength: 12, target: 34963 },  // indices
        { buffer: 0, byteOffset: 92 }  // png image (byteLength set below)
      ],
      buffers: [{ byteLength: 0 }] // set below
    };

    // ---- Binary data ----
    // Positions: 4 vertices * 3 floats * 4 bytes = 48 bytes
    var positions = new Float32Array([
      -0.5, -0.5, 0,
       0.5, -0.5, 0,
       0.5,  0.5, 0,
      -0.5,  0.5, 0
    ]);

    // UVs: 4 vertices * 2 floats * 4 bytes = 32 bytes
    var uvs = new Float32Array([
      0, 1,
      1, 1,
      1, 0,
      0, 0
    ]);

    // Indices: 6 uint16 = 12 bytes
    var indices = new Uint16Array([0, 1, 2, 0, 2, 3]);

    // Minimal 2x2 white PNG (RGBA)
    // This is a valid PNG: 2x2 pixels, all white with full alpha
    var pngBytes = new Uint8Array([
      0x89,0x50,0x4E,0x47,0x0D,0x0A,0x1A,0x0A, // PNG signature
      0x00,0x00,0x00,0x0D,0x49,0x48,0x44,0x52, // IHDR chunk
      0x00,0x00,0x00,0x02,0x00,0x00,0x00,0x02, // 2x2
      0x08,0x06,0x00,0x00,0x00,0x72,0xD1,0x0D, // 8-bit RGBA
      0x5F,
      0x00,0x00,0x00,0x1C,0x49,0x44,0x41,0x54, // IDAT chunk
      0x78,0x9C,0x62,0xF8,0x0F,0x00,0x01,0x01, // zlib compressed
      0x00,0x05,0x18,0xD8,0x4A,0x00,0x06,0x00,
      0x00,0x22,0x00,0x01,0xE7,0x40,0xA3,0x6E,
      0x00,0x00,0x00,0x00,0x49,0x45,0x4E,0x44, // IEND chunk
      0xAE,0x42,0x60,0x82
    ]);

    var geomSize = 48 + 32 + 12; // 92 bytes for positions + uvs + indices
    var binLength = geomSize + pngBytes.length;
    // Pad to 4-byte alignment
    var binPad = (4 - (binLength % 4)) % 4;
    var binLengthAligned = binLength + binPad;

    gltf.bufferViews[3].byteLength = pngBytes.length;
    gltf.buffers[0].byteLength = binLengthAligned;

    var jsonStr = JSON.stringify(gltf);
    // Pad JSON to 4-byte alignment
    while (jsonStr.length % 4 !== 0) jsonStr += ' ';

    var jsonLength = jsonStr.length;
    var totalLength = 12 + 8 + jsonLength + 8 + binLengthAligned; // GLB header + JSON chunk + BIN chunk

    var glb = new ArrayBuffer(totalLength);
    var view = new DataView(glb);
    var offset = 0;

    // GLB header
    view.setUint32(offset, 0x46546C67, true); offset += 4; // magic "glTF"
    view.setUint32(offset, 2, true); offset += 4;          // version 2
    view.setUint32(offset, totalLength, true); offset += 4; // total length

    // JSON chunk
    view.setUint32(offset, jsonLength, true); offset += 4;
    view.setUint32(offset, 0x4E4F534A, true); offset += 4; // "JSON"
    for (var i = 0; i < jsonStr.length; i++) {
      view.setUint8(offset++, jsonStr.charCodeAt(i));
    }

    // BIN chunk
    view.setUint32(offset, binLengthAligned, true); offset += 4;
    view.setUint32(offset, 0x004E4942, true); offset += 4; // "BIN\0"

    // Write positions
    var posView = new Float32Array(glb, offset, 12);
    posView.set(positions);
    offset += 48;

    // Write UVs
    var uvView = new Float32Array(glb, offset, 8);
    uvView.set(uvs);
    offset += 32;

    // Write indices
    var idxView = new Uint16Array(glb, offset, 6);
    idxView.set(indices);
    offset += 12;

    // Write PNG
    var pngView = new Uint8Array(glb, offset, pngBytes.length);
    pngView.set(pngBytes);
    offset += pngBytes.length;

    // Pad
    for (var p = 0; p < binPad; p++) {
      view.setUint8(offset++, 0);
    }

    return new Uint8Array(glb);
  }

  // ---------------------------------------------------------------
  // Canvas2D text rendering helper
  // ---------------------------------------------------------------

  /**
   * Render text to an off-screen canvas and return the canvas + dimensions.
   * Handles word wrapping, multi-line, font customization, colors.
   */
  function _renderTextToCanvas(options) {
    var text = options.text || '';
    var fontSize = options.fontSize || 48;
    var color = options.color || '#ffffff';
    var bgColor = options.backgroundColor || null;
    var fontFamily = options.fontFamily || 'system-ui, -apple-system, sans-serif';
    var fontWeight = options.fontWeight || 'normal';
    var fontStyle = options.fontStyle || 'normal';
    var maxWidth = options.maxWidth || 0;
    var padding = options.padding || 16;

    var font = fontStyle + ' ' + fontWeight + ' ' + fontSize + 'px ' + fontFamily;

    // Measure text first to determine canvas size
    var measureCanvas = document.createElement('canvas');
    var mCtx = measureCanvas.getContext('2d');
    mCtx.font = font;

    // Word wrap if maxWidth is specified
    var lines = [];
    var rawLines = text.split('\n');

    for (var r = 0; r < rawLines.length; r++) {
      var line = rawLines[r];
      if (maxWidth > 0) {
        var words = line.split(' ');
        var currentLine = '';
        for (var w = 0; w < words.length; w++) {
          var testLine = currentLine ? currentLine + ' ' + words[w] : words[w];
          var metrics = mCtx.measureText(testLine);
          if (maxWidth > 0 && metrics.width > maxWidth - padding * 2 && currentLine) {
            lines.push(currentLine);
            currentLine = words[w];
          } else {
            currentLine = testLine;
          }
        }
        if (currentLine) lines.push(currentLine);
      } else {
        lines.push(line);
      }
    }

    if (lines.length === 0) lines = [''];

    // Calculate dimensions
    var lineHeight = fontSize * 1.3;
    var textHeight = lines.length * lineHeight;
    var textWidth = 0;
    for (var l = 0; l < lines.length; l++) {
      var w = mCtx.measureText(lines[l]).width;
      if (w > textWidth) textWidth = w;
    }

    var canvasWidth = Math.ceil(textWidth + padding * 2);
    var canvasHeight = Math.ceil(textHeight + padding * 2);

    // Constrain to maxWidth if set
    if (maxWidth > 0 && canvasWidth > maxWidth) canvasWidth = maxWidth;

    // Round up to power-of-two for better GPU compatibility
    canvasWidth = _nextPow2(Math.max(canvasWidth, 4));
    canvasHeight = _nextPow2(Math.max(canvasHeight, 4));

    // Create the actual canvas
    var canvas = document.createElement('canvas');
    canvas.width = canvasWidth;
    canvas.height = canvasHeight;
    var ctx = canvas.getContext('2d');

    // Background
    if (bgColor) {
      ctx.fillStyle = bgColor;
      // Rounded rectangle for a polished look
      var radius = Math.min(fontSize * 0.3, 12);
      _roundRect(ctx, 0, 0, canvasWidth, canvasHeight, radius);
      ctx.fill();
    } else {
      // Transparent background
      ctx.clearRect(0, 0, canvasWidth, canvasHeight);
    }

    // Draw text
    ctx.font = font;
    ctx.fillStyle = color;
    ctx.textBaseline = 'top';
    ctx.textAlign = 'left';

    for (var i = 0; i < lines.length; i++) {
      ctx.fillText(lines[i], padding, padding + i * lineHeight);
    }

    return {
      canvas: canvas,
      width: canvasWidth,
      height: canvasHeight,
      // Aspect ratio for 3D plane sizing
      aspect: canvasWidth / canvasHeight
    };
  }

  /** Draw a rounded rectangle path */
  function _roundRect(ctx, x, y, w, h, r) {
    ctx.beginPath();
    ctx.moveTo(x + r, y);
    ctx.lineTo(x + w - r, y);
    ctx.quadraticCurveTo(x + w, y, x + w, y + r);
    ctx.lineTo(x + w, y + h - r);
    ctx.quadraticCurveTo(x + w, y + h, x + w - r, y + h);
    ctx.lineTo(x + r, y + h);
    ctx.quadraticCurveTo(x, y + h, x, y + h - r);
    ctx.lineTo(x, y + r);
    ctx.quadraticCurveTo(x, y, x + r, y);
    ctx.closePath();
  }

  /** Round up to next power of 2 */
  function _nextPow2(v) {
    v--;
    v |= v >> 1; v |= v >> 2; v |= v >> 4; v |= v >> 8; v |= v >> 16;
    return v + 1;
  }

  // ---------------------------------------------------------------
  // Chroma key processing (green screen removal on CPU)
  // ---------------------------------------------------------------

  /**
   * Process pixel data to remove chroma key color, setting those pixels transparent.
   * Operates on RGBA Uint8ClampedArray in-place.
   *
   * @param {ImageData} imageData - Canvas ImageData to process
   * @param {Array} keyColor - Normalized RGB [0..1] of the color to remove (e.g. [0,1,0] for green)
   * @param {number} threshold - Distance threshold (0..1), larger = more tolerance
   */
  function _applyChromaKey(imageData, keyColor, threshold) {
    var data = imageData.data;
    var kr = keyColor[0] * 255;
    var kg = keyColor[1] * 255;
    var kb = keyColor[2] * 255;
    var threshSq = (threshold * 255) * (threshold * 255) * 3; // squared distance threshold

    for (var i = 0; i < data.length; i += 4) {
      var dr = data[i] - kr;
      var dg = data[i + 1] - kg;
      var db = data[i + 2] - kb;
      var distSq = dr * dr + dg * dg + db * db;
      if (distSq < threshSq) {
        // Soft edge: fade alpha based on distance
        var t = distSq / threshSq;
        data[i + 3] = Math.floor(data[i + 3] * t);
      }
    }
  }

  // ---------------------------------------------------------------
  // SceneView instance
  // ---------------------------------------------------------------

  /**
   * SceneView instance — wraps Filament engine, scene, camera, renderer.
   */
  class SceneViewInstance {
    constructor(canvas, engine, scene, renderer, view, swapChain, camera, cameraEntity, loader) {
      this._canvas = canvas;
      this._engine = engine;
      this._scene = scene;
      this._renderer = renderer;
      this._view = view;
      this._swapChain = swapChain;
      this._camera = camera;
      this._cameraEntity = cameraEntity;
      this._loader = loader;
      this._asset = null;
      this._angle = 0.785; // Start at ~45° like model-viewer
      this._autoRotate = true;
      this._orbitRadius = 3.5;
      this._orbitHeight = 0.8;
      this._orbitTarget = [0, 0, 0];
      this._running = true;
      // Visibility gating (#2508): the render loop only draws when the canvas is
      // both on-screen (IntersectionObserver) and in a visible tab. Off-screen or
      // tab-hidden, the rAF loop self-suspends — no GPU/CPU/battery drain — and
      // resumes cleanly when visibility returns. The orbit animates by fixed
      // per-frame increments (not delta-time), so suspending simply freezes the
      // angle; there is no time-step jump on resume.
      this._onScreen = true;       // updated by IntersectionObserver
      this._tabVisible = (typeof document === 'undefined') ||
        document.visibilityState !== 'hidden';
      this._rafId = null;          // pending requestAnimationFrame handle, if any
      this._listeners = [];        // {target, type, handler, options} for dispose() cleanup
      this._intersectionObserver = null;
      this._fallbackEl = null;     // load-failure placeholder overlay, if shown (#2509)
      this._isDragging = false;
      this._lastMouse = { x: 0, y: 0 };
      // Inertia for smooth orbit deceleration
      this._velocityAngle = 0;
      this._velocityHeight = 0;
      this._dampingFactor = 0.95;
      this._wantsAutoRotate = true; // Remember initial preference for resume after drag
      this._autoRotateTimer = null;
      this._cameraMode = 'orbit'; // 'orbit', 'map', or 'freelook'

      // Media node tracking
      this._mediaNodes = new Map(); // entity -> { type, asset, texture, ... }
      this._billboards = new Set(); // entities that should always face camera
      this._videoElements = new Map(); // entity -> { video, canvas, ctx, rafId }
      this._quadGLB = null; // Cached quad GLB bytes

      // Per-frame allocation scratch (#2274) — eliminates the GC sawtooth, worst
      // on iOS Safari. Filament.js reads these arrays synchronously inside the
      // call, so mutating them in place every requestAnimationFrame tick is safe.
      this._billboardScratch = new Array(16); // shared column-major mat4 for billboards
      // lookAt scratch — one eye/center/up triple per camera mode, set once here
      // and mutated in place each frame. _upMap is the constant top-down up vector.
      this._eye = [0, 0, 0];
      this._center = [0, 0, 0];
      this._up = [0, 1, 0];
      this._upMap = [0, 0, -1];

      // Animation state — glTF skinning / keyframe playback
      this._animator = null;
      this._animationIndex = -1;
      this._animationLoop = false;
      this._animationStart = 0;
      this._animationPauseTime = -1;

      // Base lights created by _createEngine — tracked so clearLights() can remove them
      this._baseLights = [];

      this._setupControls();
      this._setupResizeObserver();
      this._setupVisibilityGating();
      this._startRenderLoop();
    }

    // ---------------------------------------------------------------
    // Model loading (existing)
    // ---------------------------------------------------------------

    /** Load a glTF/GLB model from URL */
    loadModel(url) {
      var self = this;
      return new Promise(function(resolve, reject) {
        // On any failure (network/404, fetch error, GLB parse/decode failure),
        // paint a graceful in-canvas fallback before rejecting (#2509). The
        // promise still rejects so existing callers that .catch() (index.html,
        // playground.html, web.html) keep their current behaviour — but bare
        // callers (the two showcase pages) no longer get a dead blank canvas.
        function fail(e) {
          self._showLoadFallback(url);
          reject(e);
        }
        fetch(url)
          .then(function(resp) {
            if (!resp.ok) throw new Error('HTTP ' + resp.status + ' loading ' + url);
            return resp.arrayBuffer();
          })
          .then(function(buffer) {
            Filament.assets = Filament.assets || {};
            Filament.assets[url] = new Uint8Array(buffer);
            try {
              self._showModel(url);
              self._hideLoadFallback();  // clear any prior-failure placeholder
              resolve(self);
            } catch (e) {
              fail(e);
            }
          })
          .catch(fail);
      });
    }

    /**
     * Paint a subtle "3D preview unavailable" placeholder over the canvas when a
     * model fails to load (#2509). Delegates to the shared module-level painter
     * (also used for engine-init failures, #2563). Idempotent: a second failure
     * reuses the existing overlay. Removed by dispose().
     */
    _showLoadFallback(url) {
      console.warn('SceneView: model failed to load, showing fallback (' + url + ')');
      var el = _paintFallbackOverlay(this._canvas, this._fallbackEl);
      if (el) this._fallbackEl = el;
    }

    /** Remove the load-failure placeholder, if one is showing (#2509). */
    _hideLoadFallback() {
      if (this._fallbackEl && this._fallbackEl.parentNode) {
        this._fallbackEl.parentNode.removeChild(this._fallbackEl);
      }
      this._fallbackEl = null;
    }

    _showModel(url) {
      // Remove previous model
      if (this._asset) {
        try {
          this._asset.getRenderableEntities().forEach(function(e) { this._scene.remove(e); }.bind(this));
          this._scene.remove(this._asset.getRoot());
        } catch (e) { /* ignore cleanup errors */ }
        this._asset = null;
      }
      // Also remove any primitives added by createBox/Sphere/Cylinder — otherwise
      // they'd linger on top of the newly loaded model.
      if (this._primitiveAssets && this._primitiveAssets.length > 0) {
        var self = this;
        this._primitiveAssets.forEach(function(pa) {
          try {
            pa.getRenderableEntities().forEach(function(e) { self._scene.remove(e); });
            self._scene.remove(pa.getRoot());
          } catch (e) { /* ignore */ }
        });
        this._primitiveAssets = [];
      }

      var data = Filament.assets[url];
      if (!data) throw new Error('Failed to fetch model: ' + url);

      var asset = this._loader.createAsset(data);
      if (!asset) throw new Error('Failed to parse model: ' + url);

      asset.loadResources();
      this._scene.addEntity(asset.getRoot());
      this._scene.addEntities(asset.getRenderableEntities());
      this._asset = asset;

      // Cache animator for animation playback — reset any previous animation state.
      // In Filament.js gltfio, the Animator lives on the FilamentInstance,
      // not on the FilamentAsset itself.
      try {
        var inst = asset.getInstance ? asset.getInstance() : null;
        this._animator = (inst && inst.getAnimator) ? inst.getAnimator() : null;
      } catch (e) {
        this._animator = null;
      }
      this._animationIndex = -1;
      this._animationPauseTime = -1;

      // Auto-frame the model
      try {
        var bbox = asset.getBoundingBox();
        var cx = (bbox.min[0] + bbox.max[0]) / 2;
        var cy = (bbox.min[1] + bbox.max[1]) / 2;
        var cz = (bbox.min[2] + bbox.max[2]) / 2;
        var sx = bbox.max[0] - bbox.min[0];
        var sy = bbox.max[1] - bbox.min[1];
        var sz = bbox.max[2] - bbox.min[2];
        var maxDim = Math.max(sx, sy, sz);
        if (maxDim > 0) {
          this._orbitTarget = [cx, cy, cz];
          // Tighter framing than before (1.8x instead of 2.5x)
          this._orbitRadius = maxDim * 1.8;
          this._orbitHeight = cy;
        }
      } catch (e) { /* use defaults */ }
    }

    setAutoRotate(enabled) { this._autoRotate = enabled; this._wantsAutoRotate = enabled; return this; }
    setCameraDistance(d) { this._orbitRadius = d; return this; }

    /**
     * Set camera manipulator type.
     *
     * @param {string} type - 'orbit' (default), 'map' (top-down), or 'freelook'
     * @returns {SceneViewInstance} this (for chaining)
     */
    setCameraManipulator(type) {
      this._cameraMode = type || 'orbit';
      if (type === 'map') {
        // Top-down view: look straight down from above
        this._orbitHeight = this._orbitTarget[1] + this._orbitRadius;
        this._angle = 0;
      }
      return this;
    }

    /**
     * Load a KTX IBL environment for PBR lighting.
     *
     * @param {string} url - URL to a KTX IBL file
     * @param {number} [intensity=40000] - Light intensity
     * @returns {Promise<SceneViewInstance>} this (for chaining)
     */
    loadEnvironment(url, intensity) {
      var self = this;
      return fetch(url)
        .then(function(r) {
          if (!r.ok) throw new Error('HTTP ' + r.status);
          return r.arrayBuffer().then(function(ab) { return new Uint8Array(ab); });
        })
        .then(function(buffer) {
          try {
            var ibl = self._engine.createIblFromKtx1(buffer);
            ibl.setIntensity(intensity || 40000);
            self._scene.setIndirectLight(ibl);
            _log('SceneView: Environment loaded (' + Math.round(buffer.length / 1024) + 'KB)');
          } catch (e) {
            console.warn('SceneView: loadEnvironment failed', e);
          }
          return self;
        });
    }

    /**
     * Set environment lighting from spherical harmonics coefficients.
     *
     * @param {number[]} bands - 27 SH coefficients (9 bands x RGB)
     * @param {number} [intensity=45000] - Light intensity
     * @returns {SceneViewInstance} this (for chaining)
     */
    setEnvironmentSH(bands, intensity) {
      try {
        var ibl = Filament.IndirectLight.Builder()
          .irradiance(3, bands)
          .intensity(intensity || 45000)
          .build(this._engine);
        this._scene.setIndirectLight(ibl);
      } catch (e) {
        console.warn('SceneView: setEnvironmentSH failed', e);
      }
      return this;
    }

    setBackgroundColor(r, g, b, a) {
      this._renderer.setClearOptions({ clearColor: [r, g, b, a !== undefined ? a : 1], clear: true });
      return this;
    }

    // ---------------------------------------------------------------
    // Quality, Bloom, and Lighting controls
    // ---------------------------------------------------------------

    /**
     * Set rendering quality level.
     *
     * @param {string} level - 'low', 'medium', or 'high' (default: 'medium')
     * @returns {SceneViewInstance} this (for chaining)
     */
    setQuality(level) {
      try {
        if (level === 'low') {
          this._view.setAmbientOcclusionOptions({ enabled: false });
          this._view.setAntiAliasing(Filament.View$AntiAliasing.NONE);
        } else if (level === 'high') {
          this._view.setAmbientOcclusionOptions({
            enabled: true, radius: 0.4, bias: 0.0003, intensity: 1.2, quality: 2
          });
          this._view.setAntiAliasing(Filament.View$AntiAliasing.FXAA);
        } else { // medium (default)
          this._view.setAmbientOcclusionOptions({
            enabled: true, radius: 0.3, bias: 0.0005, intensity: 1.0, quality: 1
          });
          this._view.setAntiAliasing(Filament.View$AntiAliasing.FXAA);
        }
      } catch (e) { console.warn('SceneView: setQuality not supported', e); }
      return this;
    }

    /**
     * Enable or configure bloom post-processing effect.
     *
     * @param {Object|boolean} options - true for defaults, false to disable,
     *   or { strength, resolution, threshold, levels }
     * @returns {SceneViewInstance} this (for chaining)
     */
    setBloom(options) {
      try {
        if (options === false) {
          this._view.setBloomOptions({ enabled: false });
        } else {
          var opts = (options === true || options === undefined) ? {} : options;
          this._view.setBloomOptions({
            enabled: true,
            strength: opts.strength !== undefined ? opts.strength : 0.1,
            resolution: opts.resolution !== undefined ? opts.resolution : 360,
            threshold: opts.threshold !== undefined ? opts.threshold : true,
            levels: opts.levels !== undefined ? opts.levels : 6
          });
        }
      } catch (e) { console.warn('SceneView: setBloom not supported', e); }
      return this;
    }

    /**
     * Add a custom light to the scene.
     *
     * @param {Object} options
     * @param {string} [options.type='directional'] - 'directional', 'point', or 'spot'
     * @param {number[]} [options.color=[1,1,1]] - RGB color [0-1]
     * @param {number} [options.intensity=100000] - Light intensity in lux
     * @param {number[]} [options.direction=[0,-1,0]] - Direction for directional/spot lights
     * @param {number[]} [options.position=[0,2,0]] - Position for point/spot lights
     * @param {number} [options.falloff=10] - Falloff radius for point/spot lights
     * @returns {number} Entity handle (use with removeNode to delete)
     */
    addLight(options) {
      options = options || {};
      var type = options.type || 'directional';
      var color = options.color || [1, 1, 1];
      var intensity = options.intensity !== undefined ? options.intensity : 100000;
      var direction = options.direction || [0, -1, 0];
      var position = options.position || [0, 2, 0];
      var falloff = options.falloff !== undefined ? options.falloff : 10;

      var entity = Filament.EntityManager.get().create();
      var lightType;

      if (type === 'point') {
        lightType = Filament.LightManager$Type.POINT;
      } else if (type === 'spot') {
        lightType = Filament.LightManager$Type.SPOT;
      } else {
        lightType = Filament.LightManager$Type.DIRECTIONAL;
      }

      var builder = Filament.LightManager.Builder(lightType)
        .color(color)
        .intensity(intensity)
        .direction(direction);

      if (type === 'point' || type === 'spot') {
        builder.falloff(falloff);
        // Position point/spot lights via transform
        var tm = this._engine.getTransformManager();
        var inst = tm.getInstance(entity);
        tm.setTransform(inst, Filament.mat4.translation(position));
      }

      builder.build(this._engine, entity);
      this._scene.addEntity(entity);
      return entity;
    }

    // ---------------------------------------------------------------
    // createText — Render text as a textured quad in the 3D scene
    // ---------------------------------------------------------------

    /**
     * Create a text node in the 3D scene.
     *
     * @param {Object} options
     * @param {string} options.text - The text to display
     * @param {number} [options.fontSize=48] - Font size in pixels for the canvas rendering
     * @param {string} [options.color='#ffffff'] - Text color (CSS color string)
     * @param {string} [options.backgroundColor=null] - Background color (null for transparent)
     * @param {Array} [options.position=[0,0,0]] - World position [x, y, z]
     * @param {boolean} [options.billboard=true] - Always face the camera
     * @param {number} [options.maxWidth=0] - Maximum width for word wrapping (0 = no wrap)
     * @param {string} [options.fontFamily] - CSS font family
     * @param {string} [options.fontWeight] - CSS font weight (e.g. 'bold', '600')
     * @param {string} [options.fontStyle] - CSS font style (e.g. 'italic')
     * @param {number} [options.scale=1] - Scale factor for the text plane in world units
     * @returns {Promise<number>} Entity handle
     */
    createText(options) {
      options = options || {};
      var self = this;

      // Render text to canvas
      var result = _renderTextToCanvas(options);
      var textCanvas = result.canvas;
      var aspect = result.aspect;

      var position = options.position || [0, 0, 0];
      var billboard = options.billboard !== undefined ? options.billboard : true;
      var scale = options.scale || 1;

      // Size the plane to preserve text aspect ratio
      var planeWidth = scale * aspect * 0.5;
      var planeHeight = scale * 0.5;

      return this._createTexturedQuad(textCanvas, position, [planeWidth, planeHeight], billboard)
        .then(function(entity) {
          var nodeInfo = self._mediaNodes.get(entity);
          if (nodeInfo) {
            nodeInfo.type = 'text';
            nodeInfo.textOptions = options;
          }
          return entity;
        });
    }

    // ---------------------------------------------------------------
    // createImage — Load an image and display as a textured quad
    // ---------------------------------------------------------------

    /**
     * Create an image node in the 3D scene.
     *
     * @param {Object} options
     * @param {string} options.url - Image URL to load
     * @param {Array} [options.position=[0,0,0]] - World position [x, y, z]
     * @param {Array} [options.size=[1,1]] - Width and height in world units
     * @param {boolean} [options.billboard=false] - Always face the camera
     * @param {number} [options.opacity=1.0] - Opacity (0..1)
     * @returns {Promise<number>} Entity handle
     */
    createImage(options) {
      options = options || {};
      var self = this;
      var url = options.url;
      if (!url) return Promise.reject(new Error('SceneView: createImage requires a url'));

      var position = options.position || [0, 0, 0];
      var size = options.size || [1, 1];
      var billboard = options.billboard || false;
      var opacity = options.opacity !== undefined ? options.opacity : 1.0;

      return fetch(url)
        .then(function(resp) {
          if (!resp.ok) throw new Error('Failed to load image: ' + url + ' (HTTP ' + resp.status + ')');
          return resp.blob();
        })
        .then(function(blob) {
          return createImageBitmap(blob);
        })
        .then(function(bitmap) {
          // Draw the image to a canvas (to get pixel data and handle power-of-two sizing)
          var cw = _nextPow2(bitmap.width);
          var ch = _nextPow2(bitmap.height);
          var canvas = document.createElement('canvas');
          canvas.width = cw;
          canvas.height = ch;
          var ctx = canvas.getContext('2d');

          // Apply opacity by setting global alpha
          ctx.globalAlpha = opacity;
          // Draw image at top-left, stretched to power-of-two size
          ctx.drawImage(bitmap, 0, 0, cw, ch);
          bitmap.close();

          return self._createTexturedQuad(canvas, position, size, billboard);
        })
        .then(function(entity) {
          var nodeInfo = self._mediaNodes.get(entity);
          if (nodeInfo) {
            nodeInfo.type = 'image';
            nodeInfo.imageOptions = options;
          }
          return entity;
        });
    }

    // ---------------------------------------------------------------
    // createVideo — Stream video frames to a textured quad
    // ---------------------------------------------------------------

    /**
     * Create a video node in the 3D scene.
     *
     * @param {Object} options
     * @param {string} options.url - Video URL
     * @param {Array} [options.position=[0,0,0]] - World position [x, y, z]
     * @param {Array} [options.size=[1.6,0.9]] - Width and height in world units (default 16:9)
     * @param {boolean} [options.loop=true] - Loop the video
     * @param {boolean} [options.autoplay=true] - Start playing automatically
     * @param {Object} [options.chromaKey=null] - Chroma key settings
     * @param {Array} [options.chromaKey.color=[0,1,0]] - Key color in normalized RGB
     * @param {number} [options.chromaKey.threshold=0.4] - Removal threshold (0..1)
     * @param {boolean} [options.billboard=false] - Always face the camera
     * @returns {Promise<number>} Entity handle
     */
    createVideo(options) {
      options = options || {};
      var self = this;
      var url = options.url;
      if (!url) return Promise.reject(new Error('SceneView: createVideo requires a url'));

      var position = options.position || [0, 0, 0];
      var size = options.size || [1.6, 0.9];
      var loop = options.loop !== undefined ? options.loop : true;
      var autoplay = options.autoplay !== undefined ? options.autoplay : true;
      var chromaKey = options.chromaKey || null;
      var billboard = options.billboard || false;

      return new Promise(function(resolve, reject) {
        // Create hidden video element
        var video = document.createElement('video');
        video.crossOrigin = 'anonymous';
        video.playsInline = true;
        video.muted = true; // Required for autoplay in most browsers
        video.loop = loop;
        video.preload = 'auto';
        video.style.display = 'none';
        document.body.appendChild(video);

        video.addEventListener('loadeddata', function onLoaded() {
          video.removeEventListener('loadeddata', onLoaded);

          // Create a canvas to capture video frames
          var vw = _nextPow2(video.videoWidth || 640);
          var vh = _nextPow2(video.videoHeight || 360);
          var frameCanvas = document.createElement('canvas');
          frameCanvas.width = vw;
          frameCanvas.height = vh;
          var frameCtx = frameCanvas.getContext('2d', { willReadFrequently: !!chromaKey });

          // Draw first frame as initial texture
          frameCtx.drawImage(video, 0, 0, vw, vh);

          if (chromaKey) {
            var imgData = frameCtx.getImageData(0, 0, vw, vh);
            _applyChromaKey(imgData, chromaKey.color || [0, 1, 0], chromaKey.threshold || 0.4);
            frameCtx.putImageData(imgData, 0, 0);
          }

          self._createTexturedQuad(frameCanvas, position, size, billboard)
            .then(function(entity) {
              var nodeInfo = self._mediaNodes.get(entity);
              if (nodeInfo) {
                nodeInfo.type = 'video';
                nodeInfo.videoOptions = options;
              }

              // Store video metadata for frame updates
              self._videoElements.set(entity, {
                video: video,
                canvas: frameCanvas,
                ctx: frameCtx,
                chromaKey: chromaKey,
                playing: false,
                lastFrameTime: -1
              });

              // Start frame streaming
              self._startVideoFrameLoop(entity);

              if (autoplay) {
                video.play().catch(function(e) {
                  console.warn('SceneView: Autoplay blocked, user interaction needed:', e.message);
                });
              }

              resolve(entity);
            })
            .catch(reject);
        });

        video.addEventListener('error', function() {
          reject(new Error('SceneView: Failed to load video: ' + url));
        });

        video.src = url;
        video.load();
      });
    }

    /**
     * Start the per-frame video texture update loop for an entity.
     * Uses requestVideoFrameCallback when available, falls back to requestAnimationFrame.
     */
    _startVideoFrameLoop(entity) {
      var self = this;
      var vInfo = this._videoElements.get(entity);
      if (!vInfo) return;

      function updateFrame() {
        if (!self._running || !self._videoElements.has(entity)) return;

        var vi = self._videoElements.get(entity);
        if (!vi || vi.video.paused || vi.video.ended) {
          // Schedule next check even when paused
          requestAnimationFrame(updateFrame);
          return;
        }

        // Only update if we have a new frame
        var currentTime = vi.video.currentTime;
        if (currentTime !== vi.lastFrameTime) {
          vi.lastFrameTime = currentTime;

          // Draw current video frame to canvas
          vi.ctx.drawImage(vi.video, 0, 0, vi.canvas.width, vi.canvas.height);

          // Apply chroma key if configured
          if (vi.chromaKey) {
            var imgData = vi.ctx.getImageData(0, 0, vi.canvas.width, vi.canvas.height);
            _applyChromaKey(imgData, vi.chromaKey.color || [0, 1, 0], vi.chromaKey.threshold || 0.4);
            vi.ctx.putImageData(imgData, 0, 0);
          }

          // Update the Filament texture
          self._updateQuadTexture(entity, vi.canvas);
        }

        // Use requestVideoFrameCallback if available (more efficient)
        if ('requestVideoFrameCallback' in vi.video) {
          vi.video.requestVideoFrameCallback(updateFrame);
        } else {
          requestAnimationFrame(updateFrame);
        }
      }

      // Start the loop
      if ('requestVideoFrameCallback' in vInfo.video) {
        vInfo.video.requestVideoFrameCallback(updateFrame);
      }
      // Also use rAF as a fallback kickstarter
      requestAnimationFrame(updateFrame);
    }

    // ---------------------------------------------------------------
    // Video playback controls
    // ---------------------------------------------------------------

    /** Play a video entity */
    playVideo(entity) {
      var vInfo = this._videoElements.get(entity);
      if (vInfo && vInfo.video) {
        return vInfo.video.play();
      }
      return Promise.reject(new Error('SceneView: Entity is not a video node'));
    }

    /** Pause a video entity */
    pauseVideo(entity) {
      var vInfo = this._videoElements.get(entity);
      if (vInfo && vInfo.video) {
        vInfo.video.pause();
        return;
      }
      throw new Error('SceneView: Entity is not a video node');
    }

    /** Seek a video entity to a specific time in seconds */
    seekVideo(entity, time) {
      var vInfo = this._videoElements.get(entity);
      if (vInfo && vInfo.video) {
        vInfo.video.currentTime = time;
        return;
      }
      throw new Error('SceneView: Entity is not a video node');
    }

    /** Get the current playback state of a video entity */
    getVideoState(entity) {
      var vInfo = this._videoElements.get(entity);
      if (vInfo && vInfo.video) {
        return {
          currentTime: vInfo.video.currentTime,
          duration: vInfo.video.duration,
          paused: vInfo.video.paused,
          ended: vInfo.video.ended,
          loop: vInfo.video.loop
        };
      }
      return null;
    }

    // ---------------------------------------------------------------
    // Billboard system
    // ---------------------------------------------------------------

    /**
     * Enable or disable billboard mode for an entity.
     * Billboard entities always face the camera.
     *
     * @param {number} entity - Entity handle
     * @param {boolean} enabled - Whether billboard mode is on
     */
    setBillboard(entity, enabled) {
      if (enabled) {
        this._billboards.add(entity);
      } else {
        this._billboards.delete(entity);
      }
      return this;
    }

    /**
     * Update billboard transforms — called each frame in the render loop.
     * Makes billboard entities face the camera by setting their rotation.
     */
    _updateBillboards() {
      if (this._billboards.size === 0) return;

      var tcm;
      try {
        tcm = this._engine.getTransformManager();
      } catch (e) {
        return; // TransformManager not available
      }

      // Camera position (from orbit params)
      var t = this._orbitTarget;
      var r = this._orbitRadius;
      var camX = t[0] + Math.sin(this._angle) * r;
      var camZ = t[2] + Math.cos(this._angle) * r;

      var self = this;
      var mat = this._billboardScratch; // shared scratch — fully overwritten per billboard
      // Batch the per-billboard setTransform calls in one local-transform
      // transaction so the TransformManager recomputes world transforms once.
      var inTransaction = false;
      if (typeof tcm.openLocalTransformTransaction === 'function') {
        tcm.openLocalTransformTransaction();
        inTransaction = true;
      }
      this._billboards.forEach(function(entity) {
        var nodeInfo = self._mediaNodes.get(entity);
        if (!nodeInfo || !nodeInfo.asset) return;

        var pos = nodeInfo.position || [0, 0, 0];

        // Calculate direction from entity to camera (Y-up world)
        var dx = camX - pos[0];
        var dz = camZ - pos[2];

        // Yaw angle (rotation around Y axis) to face camera
        var yaw = Math.atan2(dx, dz);

        // Build a transform matrix: Translation * RotationY * Scale
        var sx = nodeInfo.scaleX || 1;
        var sy = nodeInfo.scaleY || 1;

        // Column-major 4x4 matrix for Filament — fully written into the shared
        // scratch each iteration (no partial-state leak between billboards).
        var cosY = Math.cos(yaw);
        var sinY = Math.sin(yaw);
        mat[0] = cosY * sx; mat[1] = 0;  mat[2] = -sinY * sx; mat[3] = 0;
        mat[4] = 0;         mat[5] = sy; mat[6] = 0;          mat[7] = 0;
        mat[8] = sinY;      mat[9] = 0;  mat[10] = cosY;      mat[11] = 0;
        mat[12] = pos[0];   mat[13] = pos[1]; mat[14] = pos[2]; mat[15] = 1;

        try {
          // Cache the TransformManager instance per billboard (#2274) — getInstance
          // was previously called every frame for every billboard.
          var inst = nodeInfo.transformInstance;
          if (inst == null) {
            inst = tcm.getInstance(nodeInfo.asset.getRoot());
            nodeInfo.transformInstance = inst;
          }
          tcm.setTransform(inst, mat);
        } catch (e) {
          // Entity may have been destroyed
          nodeInfo.transformInstance = null;
          self._billboards.delete(entity);
        }
      });
      if (inTransaction) {
        tcm.commitLocalTransformTransaction();
      }
    }

    // ---------------------------------------------------------------
    // Texture utilities
    // ---------------------------------------------------------------

    /**
     * Create a Filament texture from a Canvas2D element.
     * Returns the Filament.Texture object.
     *
     * @param {HTMLCanvasElement} canvas - Source canvas
     * @returns {Object} Filament texture object
     */
    createTexture(canvas) {
      return this._createFilamentTexture(canvas);
    }

    /**
     * Update an existing entity's texture from a Canvas2D element.
     * Useful for dynamic content (live data, animated text, etc.)
     *
     * @param {number} entity - Entity handle (from createText/createImage/createVideo)
     * @param {HTMLCanvasElement} canvas - New canvas content
     */
    updateTexture(entity, canvas) {
      this._updateQuadTexture(entity, canvas);
    }

    // ---------------------------------------------------------------
    // Internal: Create Filament texture from canvas pixels
    // ---------------------------------------------------------------

    _createFilamentTexture(canvas) {
      var width = canvas.width;
      var height = canvas.height;
      var ctx = canvas.getContext('2d');
      var imageData = ctx.getImageData(0, 0, width, height);
      var pixels = new Uint8Array(imageData.data.buffer);

      var tex = Filament.Texture.Builder()
        .width(width)
        .height(height)
        .levels(1)
        .sampler(Filament.Texture$Sampler.SAMPLER_2D)
        .format(Filament.Texture$InternalFormat.SRGB8_A8)
        .build(this._engine);

      var pb = Filament.PixelBuffer(pixels, Filament.PixelDataFormat.RGBA, Filament.PixelDataType.UBYTE);
      tex.setImage(this._engine, 0, pb);

      return tex;
    }

    /**
     * Update the texture on an existing quad entity by re-uploading canvas pixels.
     */
    _updateQuadTexture(entity, canvas) {
      var nodeInfo = this._mediaNodes.get(entity);
      if (!nodeInfo || !nodeInfo.asset) return;

      var width = canvas.width;
      var height = canvas.height;
      var ctx = canvas.getContext('2d');
      var imageData = ctx.getImageData(0, 0, width, height);
      var pixels = new Uint8Array(imageData.data.buffer);

      // If the texture dimensions changed, we need a new texture
      if (nodeInfo.texWidth !== width || nodeInfo.texHeight !== height) {
        // Create new texture with updated dimensions
        var newTex = Filament.Texture.Builder()
          .width(width)
          .height(height)
          .levels(1)
          .sampler(Filament.Texture$Sampler.SAMPLER_2D)
          .format(Filament.Texture$InternalFormat.SRGB8_A8)
          .build(this._engine);

        var pb = Filament.PixelBuffer(pixels, Filament.PixelDataFormat.RGBA, Filament.PixelDataType.UBYTE);
        newTex.setImage(this._engine, 0, pb);

        // Update the material instance's texture
        try {
          var renderables = nodeInfo.asset.getRenderableEntities();
          if (renderables.length > 0) {
            var rm = this._engine.getRenderableManager();
            var ri = rm.getInstance(renderables[0]);
            var mi = rm.getMaterialInstanceAt(ri, 0);
            mi.setTextureParameter('baseColorMap', newTex,
              new Filament.TextureSampler(
                Filament.MinFilter.LINEAR_MIPMAP_LINEAR,
                Filament.MagFilter.LINEAR,
                Filament.WrapMode.CLAMP_TO_EDGE
              )
            );
          }
        } catch (e) {
          // Material parameter name may differ — try alternatives
          try {
            var renderables2 = nodeInfo.asset.getRenderableEntities();
            if (renderables2.length > 0) {
              var rm2 = this._engine.getRenderableManager();
              var ri2 = rm2.getInstance(renderables2[0]);
              var mi2 = rm2.getMaterialInstanceAt(ri2, 0);
              mi2.setTextureParameter('baseColor', newTex,
                new Filament.TextureSampler(
                  Filament.MinFilter.LINEAR_MIPMAP_LINEAR,
                  Filament.MagFilter.LINEAR,
                  Filament.WrapMode.CLAMP_TO_EDGE
                )
              );
            }
          } catch (e2) { /* texture update failed silently */ }
        }

        nodeInfo.texture = newTex;
        nodeInfo.texWidth = width;
        nodeInfo.texHeight = height;
      } else {
        // Same size — just re-upload pixels to existing texture
        try {
          var pb2 = Filament.PixelBuffer(pixels, Filament.PixelDataFormat.RGBA, Filament.PixelDataType.UBYTE);
          nodeInfo.texture.setImage(this._engine, 0, pb2);
        } catch (e) {
          // Fallback: create new texture
          this._updateQuadTexture(entity, canvas);
        }
      }
    }

    // ---------------------------------------------------------------
    // Internal: Create a textured quad using GLB loader
    // ---------------------------------------------------------------

    /**
     * Create a quad plane entity with a texture from a canvas.
     * Uses an in-memory GLB loaded through gltfio for proper materials.
     *
     * @param {HTMLCanvasElement} canvas - Source for the texture
     * @param {Array} position - [x, y, z] world position
     * @param {Array} size - [width, height] in world units
     * @param {boolean} billboard - Enable billboard mode
     * @returns {Promise<number>} Entity handle (actually the root entity of the quad asset)
     */
    _createTexturedQuad(canvas, position, size, billboard) {
      var self = this;

      // Cache the GLB bytes
      if (!this._quadGLB) {
        this._quadGLB = _buildQuadGLB();
      }

      // Create a unique key for this quad instance
      var quadKey = '__sv_quad_' + Date.now() + '_' + Math.random().toString(36).substr(2, 6);

      return new Promise(function(resolve, reject) {
        try {
          // Load the quad GLB through the asset loader
          var asset = self._loader.createAsset(self._quadGLB);
          if (!asset) {
            reject(new Error('SceneView: Failed to create quad asset'));
            return;
          }
          asset.loadResources();

          var rootEntity = asset.getRoot();
          self._scene.addEntity(rootEntity);
          self._scene.addEntities(asset.getRenderableEntities());

          // Create the Filament texture from canvas
          var texture = self._createFilamentTexture(canvas);

          // Apply the texture to the material
          try {
            var renderables = asset.getRenderableEntities();
            if (renderables.length > 0) {
              var rm = self._engine.getRenderableManager();
              var ri = rm.getInstance(renderables[0]);
              var mi = rm.getMaterialInstanceAt(ri, 0);

              // gltfio ubershader uses "baseColorMap" for the texture
              var sampler = new Filament.TextureSampler(
                Filament.MinFilter.LINEAR_MIPMAP_LINEAR,
                Filament.MagFilter.LINEAR,
                Filament.WrapMode.CLAMP_TO_EDGE
              );
              mi.setTextureParameter('baseColorMap', texture, sampler);
            }
          } catch (e) {
            console.warn('SceneView: Could not set texture parameter, trying alternative name:', e.message);
            // Try alternative parameter names used by different material versions
            try {
              var renderables2 = asset.getRenderableEntities();
              if (renderables2.length > 0) {
                var rm2 = self._engine.getRenderableManager();
                var ri2 = rm2.getInstance(renderables2[0]);
                var mi2 = rm2.getMaterialInstanceAt(ri2, 0);
                var sampler2 = new Filament.TextureSampler(
                  Filament.MinFilter.LINEAR_MIPMAP_LINEAR,
                  Filament.MagFilter.LINEAR,
                  Filament.WrapMode.CLAMP_TO_EDGE
                );
                mi2.setTextureParameter('baseColor', texture, sampler2);
              }
            } catch (e2) {
              console.warn('SceneView: Texture parameter fallback also failed:', e2.message);
            }
          }

          // Apply position and scale via TransformManager
          var tcm;
          try {
            tcm = self._engine.getTransformManager();
          } catch (e) {
            tcm = null;
          }

          if (tcm) {
            var sx = size[0];
            var sy = size[1];

            // Column-major 4x4 matrix: Scale * Translation
            var mat = [
              sx, 0, 0, 0,
              0, sy, 0, 0,
              0, 0, 1, 0,
              position[0], position[1], position[2], 1
            ];
            try {
              var inst = tcm.getInstance(rootEntity);
              tcm.setTransform(inst, mat);
            } catch (e) {
              console.warn('SceneView: Could not set transform:', e.message);
            }
          }

          // Use a synthetic entity ID based on the root entity for tracking
          var entityId = rootEntity;

          // Store node metadata
          self._mediaNodes.set(entityId, {
            type: 'quad',
            asset: asset,
            texture: texture,
            position: position,
            scaleX: size[0],
            scaleY: size[1],
            texWidth: canvas.width,
            texHeight: canvas.height,
            key: quadKey
          });

          // Enable billboard if requested
          if (billboard) {
            self._billboards.add(entityId);
          }

          resolve(entityId);
        } catch (e) {
          reject(e);
        }
      });
    }

    // ---------------------------------------------------------------
    // Remove a media node
    // ---------------------------------------------------------------

    /**
     * Remove a text, image, or video entity from the scene.
     *
     * @param {number} entity - Entity handle
     */
    removeNode(entity) {
      var nodeInfo = this._mediaNodes.get(entity);
      if (!nodeInfo) return;

      // Remove from scene
      if (nodeInfo.asset) {
        try {
          nodeInfo.asset.getRenderableEntities().forEach(function(e) {
            this._scene.remove(e);
          }.bind(this));
          this._scene.remove(nodeInfo.asset.getRoot());
        } catch (e) { /* ignore */ }
      }

      // Clean up video element if present
      var vInfo = this._videoElements.get(entity);
      if (vInfo) {
        vInfo.video.pause();
        vInfo.video.src = '';
        if (vInfo.video.parentNode) vInfo.video.parentNode.removeChild(vInfo.video);
        this._videoElements.delete(entity);
      }

      // Remove from tracking
      this._billboards.delete(entity);
      this._mediaNodes.delete(entity);
    }

    // ---------------------------------------------------------------
    // Dispose
    // ---------------------------------------------------------------

    dispose() {
      this._running = false;

      // Stop any pending animation frame so the loop cannot draw after teardown.
      if (this._rafId !== null) {
        cancelAnimationFrame(this._rafId);
        this._rafId = null;
      }

      // Clean up video elements
      var self = this;
      this._videoElements.forEach(function(vInfo) {
        vInfo.video.pause();
        vInfo.video.src = '';
        if (vInfo.video.parentNode) vInfo.video.parentNode.removeChild(vInfo.video);
      });
      this._videoElements.clear();
      this._mediaNodes.clear();
      this._billboards.clear();

      // Remove every tracked event listener (#2508 / #2507 LOW) — the 11 canvas
      // control listeners plus the document visibilitychange listener all capture
      // `self` → the Filament engine, so leaving them attached would keep the
      // disposed viewer (and its WebGL context) alive.
      this._listeners.forEach(function(l) {
        try { l.target.removeEventListener(l.type, l.handler, l.options); } catch (e) { /* ignore */ }
      });
      this._listeners = [];

      if (this._intersectionObserver) {
        this._intersectionObserver.disconnect();
        this._intersectionObserver = null;
      }
      if (this._resizeObserver) this._resizeObserver.disconnect();

      // Remove any load-failure fallback overlay we painted into the DOM.
      this._hideLoadFallback();

      _activeCanvases.delete(this._canvas);
      try { Filament.Engine.destroy(this._engine); } catch (e) { /* already destroyed */ }
    }

    // ---------------------------------------------------------------
    // Controls (existing)
    // ---------------------------------------------------------------

    /**
     * Register an event listener and remember it so dispose() can remove it
     * (#2508 / #2507 LOW). Without this, the anonymous control listeners capture
     * `self` → the Filament engine and keep a disposed viewer alive.
     */
    _addListener(target, type, handler, options) {
      target.addEventListener(type, handler, options);
      this._listeners.push({ target: target, type: type, handler: handler, options: options });
    }

    _setupControls() {
      var canvas = this._canvas;
      var self = this;

      this._addListener(canvas, 'mousedown', function(e) {
        self._isDragging = true;
        self._lastMouse = { x: e.clientX, y: e.clientY };
        self._autoRotate = false;
        self._velocityAngle = 0;
        self._velocityHeight = 0;
        if (self._autoRotateTimer) { clearTimeout(self._autoRotateTimer); self._autoRotateTimer = null; }
      });
      this._addListener(canvas, 'mousemove', function(e) {
        if (!self._isDragging) return;
        var dx = (e.clientX - self._lastMouse.x) * 0.005;
        var dy = (e.clientY - self._lastMouse.y) * 0.01;
        self._velocityAngle = -dx;
        self._velocityHeight = dy;
        self._angle -= dx;
        self._orbitHeight += dy;
        self._lastMouse = { x: e.clientX, y: e.clientY };
      });
      this._addListener(canvas, 'mouseup', function() {
        self._isDragging = false;
        // Resume auto-rotate after 3s idle (like model-viewer)
        if (self._wantsAutoRotate) {
          self._autoRotateTimer = setTimeout(function() { self._autoRotate = true; }, 3000);
        }
      });
      this._addListener(canvas, 'mouseleave', function() {
        self._isDragging = false;
        if (self._wantsAutoRotate) {
          self._autoRotateTimer = setTimeout(function() { self._autoRotate = true; }, 3000);
        }
      });

      this._addListener(canvas, 'wheel', function(e) {
        e.preventDefault();
        self._orbitRadius *= (1 + e.deltaY * 0.001);
        self._orbitRadius = Math.max(0.5, Math.min(50, self._orbitRadius));
      }, { passive: false });

      this._addListener(canvas, 'touchstart', function(e) {
        if (e.touches.length === 1) {
          self._isDragging = true;
          self._lastMouse = { x: e.touches[0].clientX, y: e.touches[0].clientY };
          self._autoRotate = false;
          self._velocityAngle = 0;
          self._velocityHeight = 0;
          if (self._autoRotateTimer) { clearTimeout(self._autoRotateTimer); self._autoRotateTimer = null; }
        }
      });
      this._addListener(canvas, 'touchmove', function(e) {
        if (!self._isDragging || e.touches.length !== 1) return;
        e.preventDefault();
        var dx = (e.touches[0].clientX - self._lastMouse.x) * 0.005;
        var dy = (e.touches[0].clientY - self._lastMouse.y) * 0.01;
        self._velocityAngle = -dx;
        self._velocityHeight = dy;
        self._angle -= dx;
        self._orbitHeight += dy;
        self._lastMouse = { x: e.touches[0].clientX, y: e.touches[0].clientY };
      }, { passive: false });
      this._addListener(canvas, 'touchend', function() {
        self._isDragging = false;
        if (self._wantsAutoRotate) {
          self._autoRotateTimer = setTimeout(function() { self._autoRotate = true; }, 3000);
        }
      });
    }

    /**
     * Pause the render loop when the canvas is scrolled off-screen and resume it
     * when it returns (#2508). An IntersectionObserver per canvas gates the loop
     * so off-screen viewers stop doing GPU/CPU work entirely; a shared
     * `visibilitychange` listener does the same when the whole tab is hidden.
     * The API is unchanged — pages get this for free, no call-site changes.
     */
    _setupVisibilityGating() {
      var self = this;

      if (typeof IntersectionObserver !== 'undefined') {
        this._intersectionObserver = new IntersectionObserver(function(entries) {
          for (var i = 0; i < entries.length; i++) {
            self._onScreen = entries[i].isIntersecting;
          }
          self._maybeResume();
        }, { rootMargin: '0px' });
        this._intersectionObserver.observe(this._canvas);
      }

      // Tab-hidden gate — shared across all viewers via document. Tracked so
      // dispose() removes it (the captured `self` would otherwise leak the engine).
      var onVisibility = function() {
        self._tabVisible = document.visibilityState !== 'hidden';
        self._maybeResume();
      };
      this._addListener(document, 'visibilitychange', onVisibility);
    }

    /** True only when the loop is allowed to draw this frame. */
    _shouldRender() {
      return this._running && this._onScreen && this._tabVisible;
    }

    /**
     * Re-arm the render loop if it suspended while it should now be running.
     * Idempotent — never schedules a second concurrent rAF.
     */
    _maybeResume() {
      if (this._rafId === null && this._shouldRender()) {
        this._startRenderLoop();
      }
    }

    _setupResizeObserver() {
      var self = this;
      this._resizeObserver = new ResizeObserver(function() {
        var canvas = self._canvas;
        var dpr = Math.min(devicePixelRatio, 2); // Cap at 2x for performance
        canvas.width = canvas.clientWidth * dpr;
        canvas.height = canvas.clientHeight * dpr;
        self._view.setViewport([0, 0, canvas.width, canvas.height]);
        self._camera.setProjectionFov(
          self._fov || 45, canvas.width / canvas.height, 0.1, 1000,
          Filament.Camera$Fov.VERTICAL
        );
      });
      this._resizeObserver.observe(this._canvas);
    }

    _startRenderLoop() {
      var self = this;
      // Guard against a double-start (e.g. _maybeResume racing the initial call).
      if (self._rafId !== null) return;
      function render() {
        // Suspend the loop when disposed, off-screen, or the tab is hidden
        // (#2508). Clearing _rafId and returning (without re-arming) stops all
        // GPU/CPU work; _maybeResume() restarts it when visibility returns.
        if (!self._shouldRender()) {
          self._rafId = null;
          return;
        }

        // Auto-rotate: 30°/sec ÷ 60fps (matches model-viewer)
        if (self._autoRotate) self._angle += 0.00873;

        // Inertia damping after drag release
        if (!self._isDragging) {
          self._angle += self._velocityAngle;
          self._orbitHeight += self._velocityHeight;
          self._velocityAngle *= self._dampingFactor;
          self._velocityHeight *= self._dampingFactor;
          if (Math.abs(self._velocityAngle) < 0.00005) self._velocityAngle = 0;
          if (Math.abs(self._velocityHeight) < 0.00005) self._velocityHeight = 0;
        }

        // Update billboard transforms before rendering
        self._updateBillboards();

        // Drive glTF animation playback (if any)
        self._updateAnimator();

        var t = self._orbitTarget;
        var r = self._orbitRadius;
        var h = self._orbitHeight;
        var mode = self._cameraMode || 'orbit';
        // Mutate the reusable eye/center/up scratch arrays in place instead of
        // allocating fresh arrays every frame (#2274). Camera.lookAt reads them
        // synchronously, so reuse is safe.
        var eye = self._eye;
        var center = self._center;
        if (mode === 'map') {
          // Top-down: camera above target, looking straight down
          eye[0] = t[0]; eye[1] = t[1] + r * 2; eye[2] = t[2];
          center[0] = t[0]; center[1] = t[1]; center[2] = t[2];
          self._camera.lookAt(eye, center, self._upMap);
        } else if (mode === 'freelook') {
          // Freelook: camera at orbit position but height responds to vertical drag
          var camX = t[0] + Math.sin(self._angle) * r * 0.5;
          var camZ = t[2] + Math.cos(self._angle) * r * 0.5;
          eye[0] = camX; eye[1] = h; eye[2] = camZ;
          center[0] = camX + Math.sin(self._angle + Math.PI);
          center[1] = h;
          center[2] = camZ + Math.cos(self._angle + Math.PI);
          self._camera.lookAt(eye, center, self._up);
        } else {
          // Default orbit
          eye[0] = t[0] + Math.sin(self._angle) * r;
          eye[1] = h;
          eye[2] = t[2] + Math.cos(self._angle) * r;
          center[0] = t[0]; center[1] = t[1]; center[2] = t[2];
          self._camera.lookAt(eye, center, self._up);
        }

        self._engine.execute();
        try {
          if (self._renderer.beginFrame(self._swapChain)) {
            self._renderer.renderView(self._view);
            self._renderer.endFrame();
          }
        } catch (e) {
          // Filament 1.70 may need different render call
          console.error('SceneView render error:', e.message);
          self._running = false;
        }
        self._rafId = requestAnimationFrame(render);
      }
      self._rafId = requestAnimationFrame(render);
    }

    // ---------------------------------------------------------------
    // Scene management & geometry primitives
    // ---------------------------------------------------------------

    /**
     * Remove all renderable entities (models, primitives) from the scene.
     * Lights and camera are preserved.
     */
    clearScene() {
      // Remove loaded glTF asset
      if (this._asset) {
        try {
          this._asset.getRenderableEntities().forEach(function(e) { this._scene.remove(e); }.bind(this));
          this._scene.remove(this._asset.getRoot());
        } catch (e) { /* ignore */ }
        this._asset = null;
      }
      // Reset animator state — no model, no animation
      this._animator = null;
      this._animationIndex = -1;
      this._animationPauseTime = -1;
      // Remove manually added primitive assets
      if (this._primitiveAssets) {
        var self = this;
        this._primitiveAssets.forEach(function(pa) {
          try {
            pa.getRenderableEntities().forEach(function(e) { self._scene.remove(e); });
            self._scene.remove(pa.getRoot());
          } catch (e) { /* ignore */ }
        });
        this._primitiveAssets = [];
      }
      // Remove media nodes
      if (this._mediaNodes && this._mediaNodes.size > 0) {
        var self = this;
        this._mediaNodes.forEach(function(info, entity) {
          try { self._scene.remove(entity); } catch (e) { /* ignore */ }
        });
        this._mediaNodes.clear();
      }
    }

    // ---------------------------------------------------------------
    // Animation playback — glTF keyframe & skinning
    // ---------------------------------------------------------------

    /**
     * Play a glTF animation on the currently loaded model.
     *
     * Call this after loadModel() resolves. Uses Filament gltfio's Animator,
     * which handles keyframes, skinning, morph targets, and bone matrices.
     *
     * @param {number} [index=0] - Animation index (see getAnimationCount via animator)
     * @param {boolean} [loop=true] - Loop the animation when it reaches the end
     * @returns {SceneViewInstance} this (for chaining)
     */
    playAnimation(index, loop) {
      if (!this._animator) {
        return this;
      }
      try {
        var count = this._animator.getAnimationCount
          ? this._animator.getAnimationCount()
          : 0;
        if (count === 0) {
          return this;
        }
      } catch (e) { /* some impls may not expose getAnimationCount */ }

      this._animationIndex = typeof index === 'number' ? index : 0;
      this._animationLoop = loop !== false; // default true
      this._animationStart = performance.now();
      this._animationPauseTime = -1;
      return this;
    }

    /**
     * Stop animation playback and reset to the rest pose.
     * @returns {SceneViewInstance} this (for chaining)
     */
    stopAnimation() {
      this._animationIndex = -1;
      this._animationPauseTime = -1;
      return this;
    }

    /** @private Drive the animator from the render loop. */
    _updateAnimator() {
      if (!this._animator || this._animationIndex < 0) return;
      try {
        var elapsedMs = performance.now() - this._animationStart;
        var t = elapsedMs / 1000;
        var dur = 0;
        try {
          dur = this._animator.getAnimationDuration(this._animationIndex) || 0;
        } catch (e) { /* ignore */ }
        if (dur > 0) {
          if (this._animationLoop) {
            t = t - Math.floor(t / dur) * dur;
          } else if (t > dur) {
            t = dur;
          }
        }
        this._animator.applyAnimation(this._animationIndex, t);
        if (this._animator.updateBoneMatrices) {
          this._animator.updateBoneMatrices();
        }
      } catch (e) { /* swallow — don't crash the render loop */ }
    }

    // ---------------------------------------------------------------
    // Light management
    // ---------------------------------------------------------------

    /**
     * Remove a single light from the scene by its entity handle (as returned
     * by addLight()). Use this to clean up lights between playground previews.
     *
     * @param {number} entity - Entity handle returned by addLight()
     * @returns {SceneViewInstance} this (for chaining)
     */
    removeLight(entity) {
      try { this._scene.remove(entity); } catch (e) { /* ignore */ }
      return this;
    }

    /**
     * Remove all lights from the scene (including the base 3-point studio
     * rig installed at engine creation). Useful when you want a truly custom
     * lighting setup with addLight().
     *
     * Note: the IBL / environment is not affected — use loadEnvironment(null)
     * or re-init the engine to clear indirect lighting.
     *
     * @returns {SceneViewInstance} this (for chaining)
     */
    clearLights() {
      if (this._baseLights && this._baseLights.length > 0) {
        var self = this;
        this._baseLights.forEach(function(entity) {
          try { self._scene.remove(entity); } catch (e) { /* ignore */ }
        });
        this._baseLights = [];
      }
      // Also drop IBL so the user-provided lights dominate
      try { this._scene.setIndirectLight(null); } catch (e) { /* ignore */ }
      return this;
    }

    /**
     * Create a colored box primitive and add it to the scene.
     *
     * @param {number[]} center - [x, y, z] position
     * @param {number[]} size - [w, h, d] dimensions
     * @param {number[]} color - [r, g, b, a] color (0-1)
     * @returns {Object|null} The glTF asset, or null on failure
     */
    createBox(center, size, color) {
      var hw = (size[0] || 1) / 2;
      var hh = (size[1] || 1) / 2;
      var hd = (size[2] || 1) / 2;
      // 24 vertices (4 per face, for proper normals)
      var positions = new Float32Array([
        // Front (+Z)
        -hw,-hh, hd,  hw,-hh, hd,  hw, hh, hd, -hw, hh, hd,
        // Back (-Z)
         hw,-hh,-hd, -hw,-hh,-hd, -hw, hh,-hd,  hw, hh,-hd,
        // Top (+Y)
        -hw, hh, hd,  hw, hh, hd,  hw, hh,-hd, -hw, hh,-hd,
        // Bottom (-Y)
        -hw,-hh,-hd,  hw,-hh,-hd,  hw,-hh, hd, -hw,-hh, hd,
        // Right (+X)
         hw,-hh, hd,  hw,-hh,-hd,  hw, hh,-hd,  hw, hh, hd,
        // Left (-X)
        -hw,-hh,-hd, -hw,-hh, hd, -hw, hh, hd, -hw, hh,-hd
      ]);
      var normals = new Float32Array([
        0,0,1, 0,0,1, 0,0,1, 0,0,1,
        0,0,-1, 0,0,-1, 0,0,-1, 0,0,-1,
        0,1,0, 0,1,0, 0,1,0, 0,1,0,
        0,-1,0, 0,-1,0, 0,-1,0, 0,-1,0,
        1,0,0, 1,0,0, 1,0,0, 1,0,0,
        -1,0,0, -1,0,0, -1,0,0, -1,0,0
      ]);
      var indices = new Uint16Array([
        0,1,2, 0,2,3,     4,5,6, 4,6,7,
        8,9,10, 8,10,11,  12,13,14, 12,14,15,
        16,17,18, 16,18,19, 20,21,22, 20,22,23
      ]);
      return this._addPrimitiveGLB(center, positions, normals, indices, color, 'box',
        [-hw,-hh,-hd], [hw,hh,hd]);
    }

    /**
     * Create a colored sphere primitive and add it to the scene.
     *
     * @param {number[]} center - [x, y, z] position
     * @param {number} radius - sphere radius
     * @param {number[]} color - [r, g, b, a] color (0-1)
     * @returns {Object|null} The glTF asset, or null on failure
     */
    createSphere(center, radius, color) {
      var r = radius || 0.5;
      var stacks = 16, slices = 24;
      var verts = [];
      var norms = [];
      var idxArr = [];

      for (var i = 0; i <= stacks; i++) {
        var phi = Math.PI * i / stacks;
        var sinP = Math.sin(phi), cosP = Math.cos(phi);
        for (var j = 0; j <= slices; j++) {
          var theta = 2 * Math.PI * j / slices;
          var sinT = Math.sin(theta), cosT = Math.cos(theta);
          var nx = sinP * cosT, ny = cosP, nz = sinP * sinT;
          verts.push(nx * r, ny * r, nz * r);
          norms.push(nx, ny, nz);
        }
      }
      for (var i = 0; i < stacks; i++) {
        for (var j = 0; j < slices; j++) {
          var a = i * (slices + 1) + j;
          var b = a + slices + 1;
          idxArr.push(a, b, a + 1, a + 1, b, b + 1);
        }
      }
      return this._addPrimitiveGLB(center, new Float32Array(verts), new Float32Array(norms),
        new Uint16Array(idxArr), color, 'sphere', [-r,-r,-r], [r,r,r]);
    }

    /**
     * Create a colored cylinder primitive and add it to the scene.
     *
     * @param {number[]} center - [x, y, z] position
     * @param {number} radius - cylinder radius
     * @param {number} height - cylinder height
     * @param {number[]} color - [r, g, b, a] color (0-1)
     * @returns {Object|null} The glTF asset, or null on failure
     */
    createCylinder(center, radius, height, color) {
      var r = radius || 0.5;
      var h = height || 1;
      var hh = h / 2;
      var segs = 24;
      var verts = [];
      var norms = [];
      var idxArr = [];
      var vi = 0;

      // Side faces
      for (var i = 0; i <= segs; i++) {
        var a = 2 * Math.PI * i / segs;
        var nx = Math.cos(a), nz = Math.sin(a);
        verts.push(nx * r, -hh, nz * r); norms.push(nx, 0, nz);
        verts.push(nx * r,  hh, nz * r); norms.push(nx, 0, nz);
      }
      for (var i = 0; i < segs; i++) {
        var b = i * 2;
        idxArr.push(b, b + 1, b + 3, b, b + 3, b + 2);
      }
      vi = (segs + 1) * 2;

      // Top cap
      var topCenter = vi;
      verts.push(0, hh, 0); norms.push(0, 1, 0); vi++;
      for (var i = 0; i <= segs; i++) {
        var a = 2 * Math.PI * i / segs;
        verts.push(Math.cos(a) * r, hh, Math.sin(a) * r); norms.push(0, 1, 0);
      }
      for (var i = 0; i < segs; i++) {
        idxArr.push(topCenter, topCenter + 1 + i, topCenter + 2 + i);
      }
      vi += segs + 1;

      // Bottom cap
      var botCenter = vi;
      verts.push(0, -hh, 0); norms.push(0, -1, 0); vi++;
      for (var i = 0; i <= segs; i++) {
        var a = 2 * Math.PI * i / segs;
        verts.push(Math.cos(a) * r, -hh, Math.sin(a) * r); norms.push(0, -1, 0);
      }
      for (var i = 0; i < segs; i++) {
        idxArr.push(botCenter, botCenter + 2 + i, botCenter + 1 + i);
      }

      return this._addPrimitiveGLB(center, new Float32Array(verts), new Float32Array(norms),
        new Uint16Array(idxArr), color, 'cylinder', [-r,-hh,-r], [r,hh,r]);
    }

    /**
     * Build a minimal GLB from geometry data, load it via gltfio, and add to scene.
     * @private
     */
    _addPrimitiveGLB(center, positions, normals, indices, color, name, bboxMin, bboxMax) {
      if (!this._primitiveAssets) this._primitiveAssets = [];
      color = color || [0.8, 0.8, 0.8, 1.0];
      center = center || [0, 0, 0];

      // Build glTF JSON
      var posBytes = positions.byteLength;
      var normBytes = normals.byteLength;
      var idxBytes = indices.byteLength;
      var vertCount = positions.length / 3;
      var idxCount = indices.length;

      var gltf = {
        asset: { version: "2.0", generator: "SceneView.js" },
        scene: 0,
        scenes: [{ nodes: [0] }],
        nodes: [{ mesh: 0, name: name, translation: center }],
        meshes: [{
          primitives: [{
            attributes: { POSITION: 0, NORMAL: 1 },
            indices: 2,
            material: 0,
            mode: 4
          }]
        }],
        materials: [{
          name: name + '_mat',
          pbrMetallicRoughness: {
            baseColorFactor: [color[0], color[1], color[2], color[3] !== undefined ? color[3] : 1],
            metallicFactor: 0.1,
            roughnessFactor: 0.6
          },
          doubleSided: false
        }],
        accessors: [
          { bufferView: 0, componentType: 5126, count: vertCount, type: "VEC3",
            min: [bboxMin[0], bboxMin[1], bboxMin[2]], max: [bboxMax[0], bboxMax[1], bboxMax[2]] },
          { bufferView: 1, componentType: 5126, count: vertCount, type: "VEC3" },
          { bufferView: 2, componentType: 5123, count: idxCount, type: "SCALAR" }
        ],
        bufferViews: [
          { buffer: 0, byteOffset: 0, byteLength: posBytes, target: 34962 },
          { buffer: 0, byteOffset: posBytes, byteLength: normBytes, target: 34962 },
          { buffer: 0, byteOffset: posBytes + normBytes, byteLength: idxBytes, target: 34963 }
        ],
        buffers: [{ byteLength: 0 }]
      };

      var binLength = posBytes + normBytes + idxBytes;
      var binPad = (4 - (binLength % 4)) % 4;
      var binLengthAligned = binLength + binPad;
      gltf.buffers[0].byteLength = binLengthAligned;

      var jsonStr = JSON.stringify(gltf);
      while (jsonStr.length % 4 !== 0) jsonStr += ' ';
      var jsonLength = jsonStr.length;
      var totalLength = 12 + 8 + jsonLength + 8 + binLengthAligned;

      var glb = new ArrayBuffer(totalLength);
      var view = new DataView(glb);
      var offset = 0;

      // GLB header
      view.setUint32(offset, 0x46546C67, true); offset += 4;
      view.setUint32(offset, 2, true); offset += 4;
      view.setUint32(offset, totalLength, true); offset += 4;

      // JSON chunk
      view.setUint32(offset, jsonLength, true); offset += 4;
      view.setUint32(offset, 0x4E4F534A, true); offset += 4;
      for (var i = 0; i < jsonStr.length; i++) {
        view.setUint8(offset++, jsonStr.charCodeAt(i));
      }

      // BIN chunk
      view.setUint32(offset, binLengthAligned, true); offset += 4;
      view.setUint32(offset, 0x004E4942, true); offset += 4;

      new Uint8Array(glb, offset, posBytes).set(new Uint8Array(positions.buffer));
      offset += posBytes;
      new Uint8Array(glb, offset, normBytes).set(new Uint8Array(normals.buffer));
      offset += normBytes;
      new Uint8Array(glb, offset, idxBytes).set(new Uint8Array(indices.buffer));
      offset += idxBytes;
      for (var p = 0; p < binPad; p++) view.setUint8(offset++, 0);

      // Load via gltfio
      var glbData = new Uint8Array(glb);
      var fakeUrl = '_prim_' + name + '_' + Date.now();
      Filament.assets = Filament.assets || {};
      Filament.assets[fakeUrl] = glbData;

      try {
        var asset = this._loader.createAsset(glbData);
        if (!asset) { console.warn('SceneView: Failed to create primitive asset'); return null; }
        asset.loadResources();
        this._scene.addEntity(asset.getRoot());
        this._scene.addEntities(asset.getRenderableEntities());
        this._primitiveAssets.push(asset);
        return asset;
      } catch (e) {
        console.warn('SceneView: createPrimitive error', e);
        return null;
      }
    }
  }

  // Singleton guard — prevent multiple engine creations on same canvas
  var _activeCanvases = new Set();

  /**
   * Set up Filament engine, scene, lights on a canvas.
   */
  function _createEngine(canvasOrId, options) {
    options = options || {};

    var canvas = typeof canvasOrId === 'string'
      ? document.getElementById(canvasOrId)
      : canvasOrId;
    if (!canvas) throw new Error('Canvas not found: ' + canvasOrId);

    // Prevent double initialization on the same canvas
    if (_activeCanvases.has(canvas)) {
      console.warn('SceneView: Canvas already initialized, skipping');
      return null;
    }
    _activeCanvases.add(canvas);

    var dpr = Math.min(devicePixelRatio, 2);
    // Ensure canvas has actual layout dimensions (not default 300x150)
    var cssW = canvas.clientWidth || canvas.offsetWidth || 500;
    var cssH = canvas.clientHeight || canvas.offsetHeight || 500;
    canvas.width = cssW * dpr;
    canvas.height = cssH * dpr;

    var engine = Filament.Engine.create(canvas);
    var scene = engine.createScene();
    var renderer = engine.createRenderer();
    var cameraEntity = Filament.EntityManager.get().create();
    var camera = engine.createCamera(cameraEntity);
    var view = engine.createView();
    var swapChain = engine.createSwapChain();

    view.setCamera(camera);
    view.setScene(scene);
    view.setViewport([0, 0, canvas.width, canvas.height]);

    var bg = options.backgroundColor || [0.05, 0.06, 0.1, 1.0];
    renderer.setClearOptions({ clearColor: bg, clear: true });

    var fov = options.fov || 45;
    camera.setProjectionFov(fov, canvas.width / canvas.height, 0.1, 1000, Filament.Camera$Fov.VERTICAL);
    camera.lookAt([0, 1, 5], [0, 0, 0], [0, 1, 0]);

    // --- Post-processing quality ---
    try {
      view.setAmbientOcclusionOptions({
        enabled: true, radius: 0.3, bias: 0.0005, intensity: 1.0, quality: 1
      });
    } catch (e) { /* skip */ }

    // --- 3-point studio lighting ---
    // Sun/key light — warm, strong
    var sun = Filament.EntityManager.get().create();
    Filament.LightManager.Builder(Filament.LightManager$Type.SUN)
      .color([0.98, 0.92, 0.89])
      .intensity(options.lightIntensity || 110000)
      .direction([0.6, -1.0, -0.8])
      .sunAngularRadius(1.9)
      .sunHaloSize(10.0)
      .sunHaloFalloff(80.0)
      .build(engine, sun);
    scene.addEntity(sun);

    // Fill light — cool, softer
    var fill = Filament.EntityManager.get().create();
    Filament.LightManager.Builder(Filament.LightManager$Type.DIRECTIONAL)
      .color([0.7, 0.75, 0.9])
      .intensity(60000)
      .direction([-0.5, 0.5, 1.0])
      .build(engine, fill);
    scene.addEntity(fill);

    // Back/rim light — edge highlight
    var back = Filament.EntityManager.get().create();
    Filament.LightManager.Builder(Filament.LightManager$Type.DIRECTIONAL)
      .color([0.5, 0.6, 0.9])
      .intensity(50000)
      .direction([0, 0.3, 1.0])
      .build(engine, back);
    scene.addEntity(back);

    // --- IBL: load real KTX if available, fallback to synthetic SH ---
    var iblUrl = options.iblUrl || '/environments/neutral_ibl.ktx';
    fetch(iblUrl)
      .then(function(r) {
        if (!r.ok) throw new Error('HTTP ' + r.status);
        return r.arrayBuffer().then(function(ab) { return new Uint8Array(ab); });
      })
      .then(function(buffer) {
        try {
          var ibl = engine.createIblFromKtx1(buffer);
          ibl.setIntensity(options.iblIntensity || 40000);
          scene.setIndirectLight(ibl);
          _log('SceneView: KTX IBL loaded (' + Math.round(buffer.length / 1024) + 'KB)');
        } catch (e) {
          console.warn('SceneView: createIblFromKtx1 failed, using SH fallback', e);
          _applySyntheticIBL(engine, scene);
        }
      })
      .catch(function() {
        _applySyntheticIBL(engine, scene);
      });

    var loader = engine.createAssetLoader();
    var instance = new SceneViewInstance(canvas, engine, scene, renderer, view, swapChain, camera, cameraEntity, loader);
    instance._fov = fov;
    // Track base 3-point lights so clearLights() can wipe them for custom setups
    instance._baseLights = [sun, fill, back];

    if (options.autoRotate === false) instance.setAutoRotate(false);

    return instance;
  }

  /** Fallback IBL from spherical harmonics when KTX not available */
  function _applySyntheticIBL(engine, scene) {
    try {
      // Studio-style IBL: warm key light from above-right, cool fill from left
      var ibl = Filament.IndirectLight.Builder()
        .irradiance(3, [
           1.20,  1.15,  1.10,   // L00  — bright neutral ambient
           0.25,  0.22,  0.18,   // L1-1 — warm fill from right
           0.35,  0.33,  0.30,   // L10  — top light (key)
          -0.08, -0.06, -0.04,   // L11  — slight side bias
           0.10,  0.10,  0.12,   // L2-2 — cool accent
           0.15,  0.14,  0.12,   // L2-1 — ground bounce
           0.02,  0.02,  0.02,   // L20  — minimal
          -0.04, -0.04, -0.03,   // L21
           0.06,  0.06,  0.05    // L22
        ])
        .intensity(45000)
        .build(engine);
      scene.setIndirectLight(ibl);
      _log('SceneView: Using synthetic SH IBL');
    } catch (e) { /* skip */ }
  }

  function create(canvasOrId, options) {
    options = options || {};
    // Engine-init watchdog (#2563): Filament.init only takes a success callback,
    // so a failed WASM init (CSP-blocked eval, asset 404, OOM…) would otherwise
    // hang every caller forever. Race init against a timeout so failure is
    // deterministic, and paint the "3D preview unavailable" placeholder instead
    // of leaving an infinite spinner. Override with options.initTimeoutMs
    // (<= 0 disables the watchdog).
    var timeoutMs = typeof options.initTimeoutMs === 'number' ? options.initTimeoutMs : 15000;

    var engineReady = _ensureFilament().then(function() {
      return new Promise(function(resolve) {
        if (typeof Filament.Engine !== 'undefined') { resolve(); return; }
        Filament.init([], function() { resolve(); });
      });
    });

    if (timeoutMs > 0) {
      var timer;
      engineReady = Promise.race([
        engineReady,
        new Promise(function(_, reject) {
          timer = setTimeout(function() {
            reject(new Error('SceneView: Filament engine init timed out after ' + timeoutMs +
              'ms — 3D disabled (WASM blocked or failed to load)'));
          }, timeoutMs);
        })
      ]).then(
        function(v) { clearTimeout(timer); return v; },
        function(e) { clearTimeout(timer); throw e; }
      );
    }

    return engineReady.then(
      function() {
        // Engine is up — instance-creation failures (canvas not found, canvas
        // already initialized) are NOT init failures: never paint the overlay
        // here, it could cover an already-live viewer.
        var instance = _createEngine(canvasOrId, options);
        if (instance) return instance;
        throw new Error('SceneView: Canvas already initialized');
      },
      function(e) {
        // Init-stage failure: degrade to the placeholder, then propagate so
        // existing .catch() callers keep their behaviour.
        _showInitFallback(canvasOrId);
        throw e;
      }
    );
  }

  function modelViewer(canvasOrId, modelUrl, options) {
    return create(canvasOrId, options).then(function(instance) {
      return instance.loadModel(modelUrl);
    });
  }

  global.SceneView = {
    version: '4.18.0',
    create: create,
    modelViewer: modelViewer
  };

})(typeof globalThis !== 'undefined' ? globalThis : window);
