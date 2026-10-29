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
 * Transparent canvas, camera, render on demand:
 *   const sv = await SceneView.create("canvas", { transparent: true, renderMode: "onDemand" });
 *   await sv.loadModel("model.glb");
 *   sv.frameModel({ fill: 0.8 }).setCameraOrbit({ angle: 0.6 });
 *   sv.onFrame((canvas) => mirrorCtx.drawImage(canvas, 0, 0));
 *
 * Powered by Filament.js v1.72.1 (Google's PBR renderer, WASM).
 * https://sceneview.github.io
 *
 * @version 4.63.0
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
    // e.g. ai-3d's label/badge, without covering them).
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

  // Minimal 2x2 white PNG (RGBA): the placeholder base texture of generated meshes,
  // replaced at runtime once gltfio has bound it.
  var _WHITE_PNG_2X2 = new Uint8Array([
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

    var pngBytes = _WHITE_PNG_2X2;

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

  /** What a load rejects with when dispose() overtook it: an `AbortError`, as fetch does. */
  function _disposedError() {
    var e = new Error('SceneView: the viewer was disposed');
    e.name = 'AbortError';
    return e;
  }

  /** The rejection of a loadModel() call that a later call replaced before it was shown. */
  function _supersededError(url) {
    var e = new Error('SceneView: loadModel(' + url + ') was replaced by a later loadModel() call');
    e.name = 'AbortError';
    return e;
  }

  /** Stop a hidden <video> created by createVideo() and take it out of the page. */
  function _releaseVideo(video) {
    video.pause();
    video.src = '';
    if (video.parentNode) video.parentNode.removeChild(video);
  }

  /** Same test as Filament.js: these external resources are fetched as textures. */
  function _isTextureUri(uri) {
    return uri.endsWith('.png') || uri.endsWith('.ktx2') ||
      uri.endsWith('.jpg') || uri.endsWith('.jpeg');
  }

  // ---------------------------------------------------------------
  // Background colour
  // ---------------------------------------------------------------

  // The opaque dark slate the viewer has always displayed when a page sets no background.
  var _DEFAULT_BACKGROUND = [0x33 / 255, 0x3C / 255, 0x57 / 255, 1.0];

  function _srgbToLinear(c) {
    return c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
  }

  /** Exact inverse of Filament's Filmic tone mapper (Narkowicz 2015), one linear channel. */
  function _inverseFilmic(y) {
    return (0.03 - 0.59 * y - Math.sqrt(0.0009 + 1.3702 * y - 1.0127 * y * y)) / (-5.02 + 4.86 * y);
  }

  /**
   * The clear colour that puts the background [r, g, b, a] (sRGB as displayed, 0-1) on screen.
   *
   * An opaque view clears inside the HDR pass, so its clear colour goes through the Filmic
   * tone mapper and the sRGB transfer before it reaches the canvas: both are undone here, the
   * way the Android demo paints its backdrop. A transparent view is composited over the raw
   * clear, which is already the colour on screen.
   */
  function _clearColorFor(color, transparent) {
    if (transparent) return color;
    function channel(c) {
      c = Math.min(Math.max(+c || 0, 0), 1);
      return _inverseFilmic(_srgbToLinear(c)) || 0;
    }
    var alpha = color[3] !== undefined ? color[3] : 1;
    return [channel(color[0]), channel(color[1]), channel(color[2]), alpha];
  }

  // ---------------------------------------------------------------
  // Studio showroom (setStudio)
  // ---------------------------------------------------------------
  //
  // A seamless photo studio around the model: a cyclorama dome whose colour runs from the
  // horizon up to the ceiling, a floor that fades into it with no visible horizon line, a
  // contact shadow and ambient occlusion baked from the model's own triangles, and a soft
  // planar reflection drawn by a second view. Every studio surface is unlit vertex colour
  // through gltfio's ubershader, the floor's times one shadow texture: no extra material
  // ships.

  // Theme defaults: the DESIGN.md `stage-studio-*` tokens, sRGB as displayed. From the
  // model outwards the studio only ever gets darker: pool, floor, horizon, then the wall
  // up to `top`. `vignette` is the corner multiplier, not a colour.
  var _STUDIO_THEMES = {
    light: {
      top: '#CDD3DB', horizon: '#E7EAEF', floor: '#F2F4F7', pool: '#FDFDFE',
      shadow: 0.85, reflection: 0.14, bloom: 0, vignette: 0.8,
      environment: '/environments/studio_showroom_light_ibl.ktx'
    },
    dark: {
      top: '#0B0E13', horizon: '#222833', floor: '#2B323E', pool: '#3A4250',
      shadow: 0.9, reflection: 0.26, bloom: 0.12, vignette: 0.6,
      environment: '/environments/studio_showroom_dark_ibl.ktx'
    }
  };
  var _STUDIO_IBL_INTENSITY = 40000;
  var _STUDIO_FOV = 30;            // a moderate telephoto: less perspective distortion
  var _STUDIO_REVEAL_MS = 1600;
  // The longest the move-in and the quality tiers wait for the model's textures: one
  // that never arrives must not hold them forever.
  var _STUDIO_DRESS_WAIT_MS = 4000;
  var _STUDIO_TURN_RATE = 0.2618;  // radians per second: 15 degrees, a turn in 24 s
  var _STUDIO_DOME_RAMP = 0.35;   // radians over which the wall turns from horizon to top
  // Cover values (the `u` field of _studioBakeShadow) over which the reflection gives way
  // under the body: from its outline to about 4 % of the model's size inside it.
  var _STUDIO_UNDERBODY = [0.45, 0.9];
  // Drawing-buffer pixel budget per quality tier (0 = lowest). See _studioApplyTier().
  var _STUDIO_BUDGETS = [0.5e6, 0.8e6, 1.2e6, 2.0e6];

  function _hexToRgb(hex) {
    var h = String(hex).replace('#', '');
    if (h.length === 3) h = h[0] + h[0] + h[1] + h[1] + h[2] + h[2];
    var n = parseInt(h, 16);
    return [((n >> 16) & 255) / 255, ((n >> 8) & 255) / 255, (n & 255) / 255];
  }

  /** '#RRGGBB', '#RGB' or [r, g, b] in 0-1 -> [r, g, b], or `fallback`. */
  function _studioColor(c, fallback) {
    function unit(v) { return Math.min(1, Math.max(0, +v || 0)); }
    if (Array.isArray(c) && c.length >= 3) return [unit(c[0]), unit(c[1]), unit(c[2])];
    if (typeof c === 'string' && /^#?([0-9a-f]{3}|[0-9a-f]{6})$/i.test(c)) return _hexToRgb(c);
    return fallback;
  }

  function _smoothstep(e0, e1, x) {
    var t = Math.min(1, Math.max(0, (x - e0) / (e1 - e0)));
    return t * t * (3 - 2 * t);
  }

  function _mixRgb(a, b, t) {
    return [a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t, a[2] + (b[2] - a[2]) * t];
  }

  /** The linear HDR value an unlit surface needs to show the display colour `c`. */
  function _hdrOf(c) {
    var v = _clearColorFor([c[0], c[1], c[2], 1], false);
    return [v[0], v[1], v[2]];
  }

  /** The sRGB encoding of one linear channel in 0-1. */
  function _linearToSrgb(y) {
    y = Math.min(1, Math.max(0, y));
    return y <= 0.0031308 ? 12.92 * y : 1.055 * Math.pow(y, 1 / 2.4) - 0.055;
  }

  function _isLikelyMobile() {
    try {
      if (typeof navigator !== 'undefined' && /Android|iPhone|iPad|Mobile/i.test(navigator.userAgent || '')) return true;
      return typeof matchMedia === 'function' && matchMedia('(pointer: coarse)').matches;
    } catch (_e) { return false; }
  }

  function _prefersReducedMotion() {
    try {
      return typeof matchMedia === 'function' && matchMedia('(prefers-reduced-motion: reduce)').matches;
    } catch (_e) { return false; }
  }

  /** setStudio() argument -> the full studio description, or null to turn it off. */
  function _studioConfig(options) {
    if (options === false || options === null) return null;
    var o = (options && typeof options === 'object') ? options : {};
    var name = typeof options === 'string' ? options : o.theme;
    var theme = name === 'dark' ? 'dark' : 'light';
    var base = _STUDIO_THEMES[theme];
    function unit(v, fallback, max) { return Number.isFinite(v) ? Math.min(max, Math.max(0, v)) : fallback; }
    return {
      theme: theme,
      top: _studioColor(o.top, _hexToRgb(base.top)),
      horizon: _studioColor(o.horizon, _hexToRgb(base.horizon)),
      floor: _studioColor(o.floor, _hexToRgb(base.floor)),
      pool: _studioColor(o.pool, _hexToRgb(base.pool)),
      shadow: unit(o.shadow, base.shadow, 1),
      reflection: unit(o.reflection, base.reflection, 0.6),
      bloom: base.bloom,
      vignette: base.vignette,
      environment: (typeof o.environment === 'string' && o.environment) ? o.environment : base.environment
    };
  }

  /**
   * Studio layout for a model's bounding box: the floor height and footprint, a low
   * three-quarter camera (9 degrees above the horizon for a car, 14 for a sofa, 22 for
   * a shoe or a watch) that keeps the swept bounding cylinder inside 78 % of the view
   * (up to 90 % of a portrait view's width, where the height is to spare), the dome
   * radius and how far from the footprint the reflection fades out.
   */
  function _studioLayoutFor(bbox, aspect) {
    var min = bbox.min, max = bbox.max;
    var dx = max[0] - min[0], dy = max[1] - min[1], dz = max[2] - min[2];
    var L = Math.max(dx, dy, dz);
    if (!(L > 0)) return null;
    var cx = (min[0] + max[0]) / 2, cy = (min[1] + max[1]) / 2, cz = (min[2] + max[2]) / 2;
    var elev = (L >= 3 ? 9 : L >= 0.6 ? 14 : 22) * Math.PI / 180;
    var c = Math.cos(elev), s = Math.sin(elev);
    var yt = cy - 0.1 * dy;
    var T = 0.78 * Math.tan(_STUDIO_FOV * Math.PI / 360);
    var sweep = 0.5 * Math.hypot(dx, dz);
    var R = 0;
    [-sweep, sweep].forEach(function(u) {
      [min[1] - yt, max[1] - yt].forEach(function(v) {
        R = Math.max(R, u * c + v * s + Math.abs(v * c - u * s) / T);
      });
    });
    var fillW = 0.78 + 0.12 * Math.min(1, Math.max(0, (1.2 - aspect) / 0.5));
    var tw = (T / 0.78) * fillW * Math.max(0.1, aspect);
    R = Math.max(R, sweep * Math.sqrt(1 + 1 / (tw * tw)) / c);
    var eyeAboveFloor = yt + R * s - min[1];
    var hf = 0.4 * dy;  // the reflection shows the lower 40 % of the model
    return {
      L: L, cx: cx, cz: cz, y: min[1], hx: dx / 2, hz: dz / 2,
      target: [cx, yt, cz], radius: R * c, height: yt + R * s, distance: R,
      domeRadius: Math.max(10 * L, 6 * R),
      fade: Math.max(0.05 * L, (R * c * hf) / (eyeAboveFloor + hf))
    };
  }

  /** Pack a glTF JSON object and its binary buffer into GLB bytes. */
  function _packGLB(gltf, bin) {
    var json = JSON.stringify(gltf);
    while (json.length % 4) json += ' ';
    var binLen = bin.byteLength + ((4 - bin.byteLength % 4) % 4);
    var total = 12 + 8 + json.length + 8 + binLen;
    var ab = new ArrayBuffer(total), dv = new DataView(ab), o = 0, i;
    dv.setUint32(o, 0x46546C67, true); o += 4;
    dv.setUint32(o, 2, true); o += 4;
    dv.setUint32(o, total, true); o += 4;
    dv.setUint32(o, json.length, true); o += 4;
    dv.setUint32(o, 0x4E4F534A, true); o += 4;
    for (i = 0; i < json.length; i++) dv.setUint8(o++, json.charCodeAt(i));
    dv.setUint32(o, binLen, true); o += 4;
    dv.setUint32(o, 0x004E4942, true); o += 4;
    new Uint8Array(ab, o, bin.byteLength).set(bin);
    return new Uint8Array(ab);
  }

  /**
   * An unlit, double-sided triangle mesh with linear RGBA vertex colours (HDR values
   * above 1 are kept). For BLEND, colours are premultiplied by their alpha. With `uv`,
   * it also gets TEXCOORD_0 and a white placeholder base colour texture, which gltfio
   * multiplies with the vertex colour and the caller swaps for its own.
   */
  function _buildColoredMeshGLB(pos, col, idx, blend, uv) {
    var n = pos.length / 3, i;
    var min = [Infinity, Infinity, Infinity], max = [-Infinity, -Infinity, -Infinity];
    for (i = 0; i < pos.length; i++) {
      var k = i % 3;
      if (pos[i] < min[k]) min[k] = pos[i];
      if (pos[i] > max[k]) max[k] = pos[i];
    }
    var wide = n > 65535;
    var P = new Float32Array(pos), C = new Float32Array(col), U = new Float32Array(uv || []);
    var I = wide ? new Uint32Array(idx) : new Uint16Array(idx);
    var png = uv ? _WHITE_PNG_2X2 : new Uint8Array(0);
    var oU = P.byteLength + C.byteLength, oI = oU + U.byteLength;
    var oPng = oI + I.byteLength + ((4 - I.byteLength % 4) % 4);
    var bin = new Uint8Array(oPng + png.byteLength);
    bin.set(new Uint8Array(P.buffer), 0);
    bin.set(new Uint8Array(C.buffer), P.byteLength);
    bin.set(new Uint8Array(U.buffer), oU);
    bin.set(new Uint8Array(I.buffer), oI);
    bin.set(png, oPng);
    var gltf = {
      asset: { version: '2.0' }, scene: 0, scenes: [{ nodes: [0] }], nodes: [{ mesh: 0 }],
      meshes: [{ primitives: [{ attributes: { POSITION: 0, COLOR_0: 1 }, indices: 2, material: 0, mode: 4 }] }],
      materials: [{
        pbrMetallicRoughness: { baseColorFactor: [1, 1, 1, 1], metallicFactor: 0, roughnessFactor: 1 },
        alphaMode: blend ? 'BLEND' : 'OPAQUE', doubleSided: true,
        extensions: { KHR_materials_unlit: {} }
      }],
      extensionsUsed: ['KHR_materials_unlit'],
      accessors: [
        { bufferView: 0, componentType: 5126, count: n, type: 'VEC3', min: min, max: max },
        { bufferView: 1, componentType: 5126, count: n, type: 'VEC4' },
        { bufferView: 2, componentType: wide ? 5125 : 5123, count: I.length, type: 'SCALAR' }
      ],
      bufferViews: [
        { buffer: 0, byteOffset: 0, byteLength: P.byteLength },
        { buffer: 0, byteOffset: P.byteLength, byteLength: C.byteLength },
        { buffer: 0, byteOffset: oI, byteLength: I.byteLength }
      ],
      buffers: [{ byteLength: bin.byteLength }]
    };
    if (uv) {
      gltf.meshes[0].primitives[0].attributes.TEXCOORD_0 = 3;
      gltf.accessors.push({ bufferView: 3, componentType: 5126, count: n, type: 'VEC2' });
      gltf.bufferViews.push({ buffer: 0, byteOffset: oU, byteLength: U.byteLength },
        { buffer: 0, byteOffset: oPng, byteLength: png.byteLength });
      gltf.materials[0].pbrMetallicRoughness.baseColorTexture = { index: 0 };
      gltf.textures = [{ source: 0, sampler: 0 }];
      gltf.images = [{ bufferView: 4, mimeType: 'image/png' }];
      gltf.samplers = [{ magFilter: 9729, minFilter: 9729, wrapS: 33071, wrapT: 33071 }];
    }
    return _packGLB(gltf, bin);
  }

  /**
   * The cyclorama: an upper hemisphere standing on the floor, rings packed near the
   * horizon where the colour turns from `horizon` to `top`. No part of it goes below
   * the floor, so the floor's own alpha decides what shows under it.
   */
  function _studioDomeGLB(lay, conf) {
    var NA = 64, NR = 24, R = lay.domeRadius, pos = [], col = [], idx = [], i, j;
    for (i = 0; i <= NR; i++) {
      var th = (Math.PI / 2) * Math.pow(i / NR, 1.6);
      var rgb = _hdrOf(_mixRgb(conf.horizon, conf.top, _smoothstep(0, _STUDIO_DOME_RAMP, th)));
      var rr = Math.cos(th) * R, y = lay.y + Math.sin(th) * R;
      for (j = 0; j < NA; j++) {
        var a = (j / NA) * Math.PI * 2;
        pos.push(lay.cx + Math.cos(a) * rr, y, lay.cz + Math.sin(a) * rr);
        col.push(rgb[0], rgb[1], rgb[2], 1);
      }
    }
    for (i = 0; i < NR; i++) {
      for (j = 0; j < NA; j++) {
        var a0 = i * NA + j, b0 = i * NA + (j + 1) % NA;
        var c0 = (i + 1) * NA + j, d0 = (i + 1) * NA + (j + 1) % NA;
        idx.push(a0, b0, d0, a0, d0, c0);
      }
    }
    return _buildColoredMeshGLB(pos, col, idx, false);
  }

  /** One rounded-rectangle ring at distance `d` around a (hx, hz) half-extent footprint. */
  function _studioRing(hx, hz, d, K, M) {
    var pts = [], sx = [1, -1, -1, 1], sz = [1, 1, -1, -1], ci, k, m;
    for (ci = 0; ci < 4; ci++) {
      var a0 = ci * Math.PI / 2;
      for (k = 0; k <= K; k++) {
        var a = a0 + (k / K) * Math.PI / 2;
        pts.push([sx[ci] * hx + d * Math.cos(a), sz[ci] * hz + d * Math.sin(a)]);
      }
      var cn = (ci + 1) % 4, an = cn * Math.PI / 2;
      var p0 = pts[pts.length - 1];
      var p1 = [sx[cn] * hx + d * Math.cos(an), sz[cn] * hz + d * Math.sin(an)];
      for (m = 1; m <= M; m++) {
        var t = m / (M + 1);
        pts.push([p0[0] + (p1[0] - p0[0]) * t, p0[1] + (p1[1] - p0[1]) * t]);
      }
    }
    return pts;
  }

  /**
   * The floor's colour by distance `d` from the model's footprint: `t`, the colour on
   * screen, runs pool -> floor -> horizon and never turns lighter again further out;
   * `rho`, how much of the reflection shows through, fades out with `lay.fade`; `hdr` is
   * the straight unlit colour that, over an empty reflection (the horizon colour), still
   * shows `t`, so only the reflected model changes the floor.
   */
  function _studioFloorShade(lay, conf, mirror) {
    var L = lay.L, rho0 = mirror ? conf.reflection : 0;
    return function(d) {
      var t = _mixRgb(_mixRgb(conf.pool, conf.floor, _smoothstep(0, 1.1 * L, d)),
        conf.horizon, _smoothstep(1.1 * L, 3.5 * L, d));
      var rho = rho0 > 0 ? rho0 * Math.pow(1 - Math.min(1, d / lay.fade), 2) : 0;
      var under = t;
      if (rho > 0) {
        under = [];
        for (var k = 0; k < 3; k++) under.push(Math.min(1, Math.max(0, (t[k] - rho * conf.horizon[k]) / (1 - rho))));
      }
      return { t: t, rho: rho, hdr: _hdrOf(under) };
    };
  }

  /**
   * The floor: rounded-rectangle rings around the footprint, dense near the model, out
   * to a circle just past the dome wall, coloured by _studioFloorShade() so it melts into
   * the wall with no visible line. Its vertices are opaque: the texture over the shadow
   * field `sh` (_studioShadowTexels), which spans the whole reach of the reflection,
   * carries the shadow and how much of the reflection shows, and clamps to white past it.
   */
  function _studioFloorGLB(lay, conf, mirror, sh) {
    var K = 8, M = 6, NV = 4 * (K + 1 + M);
    var L = lay.L, Rd = lay.domeRadius, diag = Math.hypot(lay.hx, lay.hz);
    var shade = _studioFloorShade(lay, conf, mirror);
    var su = 1 / (sh.w * sh.ts), sv = 1 / (sh.h * sh.ts);
    var steps = [0, 0.03, 0.06, 0.1, 0.15, 0.2, 0.27, 0.35, 0.45, 0.55, 0.7, 0.85, 1,
      1.2, 1.45, 1.75, 2.1, 2.5, 3, 3.6, 4.3, 5.2, 6.3, 7.6, 9.2, 11, 13];
    var pos = [], col = [], uv = [], idx = [], starts = [], ring = null, u = 0, i, j;
    function vertex(x, z, d) {
      var p = shade(d);
      pos.push(x, lay.y, z);
      col.push(p.hdr[0], p.hdr[1], p.hdr[2], 1);
      uv.push((x - sh.x0) * su, (z - sh.z0) * sv);
    }
    vertex(lay.cx, lay.cz, 0);
    for (i = 0; ; i++) {
      u = i < steps.length ? steps[i] : u * 1.2;
      var d = u * L;
      if (d + diag >= Rd) break;
      starts.push(pos.length / 3);
      ring = _studioRing(lay.hx, lay.hz, d, K, M);
      for (j = 0; j < NV; j++) vertex(lay.cx + ring[j][0], lay.cz + ring[j][1], d);
    }
    starts.push(pos.length / 3);
    for (j = 0; j < NV; j++) {
      var ang = Math.atan2(ring[j][1], ring[j][0]);
      vertex(lay.cx + Math.cos(ang) * 1.02 * Rd, lay.cz + Math.sin(ang) * 1.02 * Rd, 1.02 * Rd - diag);
    }
    for (j = 0; j < NV; j++) idx.push(0, starts[0] + j, starts[0] + (j + 1) % NV);
    for (i = 0; i + 1 < starts.length; i++) {
      var a0 = starts[i], b0 = starts[i + 1];
      for (j = 0; j < NV; j++) {
        var j1 = (j + 1) % NV;
        idx.push(a0 + j, a0 + j1, b0 + j1, a0 + j, b0 + j1, b0 + j);
      }
    }
    return _buildColoredMeshGLB(pos, col, idx, true, uv);
  }

  /** The JSON and BIN chunks of GLB bytes, or null (a .gltf, or not glTF at all). */
  function _glbChunks(bytes) {
    if (!bytes || bytes.byteLength < 20) return null;
    var dv = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
    if (dv.getUint32(0, true) !== 0x46546C67) return null;
    var json = null, bin = null, o = 12;
    while (o + 8 <= bytes.byteLength) {
      var len = dv.getUint32(o, true), type = dv.getUint32(o + 4, true), start = o + 8;
      if (start + len > bytes.byteLength) break;
      if (type === 0x4E4F534A && !json) json = JSON.parse(new TextDecoder().decode(bytes.subarray(start, start + len)));
      else if (type === 0x004E4942 && !bin) bin = bytes.subarray(start, start + len);
      o = start + len;
    }
    return json ? { json: json, bin: bin } : null;
  }

  /** Column-major 4x4 product A * B. */
  function _mat4Mul(A, B) {
    var C = new Array(16);
    for (var col = 0; col < 4; col++) {
      for (var row = 0; row < 4; row++) {
        C[col * 4 + row] = A[row] * B[col * 4] + A[4 + row] * B[col * 4 + 1] +
          A[8 + row] * B[col * 4 + 2] + A[12 + row] * B[col * 4 + 3];
      }
    }
    return C;
  }

  /** A glTF node's local matrix: `matrix`, or translation * rotation * scale. */
  function _nodeMatrix(node) {
    if (node.matrix && node.matrix.length === 16) return node.matrix;
    var t = node.translation || [0, 0, 0], q = node.rotation || [0, 0, 0, 1], s = node.scale || [1, 1, 1];
    var x = q[0], y = q[1], z = q[2], w = q[3];
    var xx = x * x, yy = y * y, zz = z * z, xy = x * y, xz = x * z, yz = y * z, wx = w * x, wy = w * y, wz = w * z;
    return [
      (1 - 2 * (yy + zz)) * s[0], 2 * (xy + wz) * s[0], 2 * (xz - wy) * s[0], 0,
      2 * (xy - wz) * s[1], (1 - 2 * (xx + zz)) * s[1], 2 * (yz + wx) * s[1], 0,
      2 * (xz + wy) * s[2], 2 * (yz - wx) * s[2], (1 - 2 * (xx + yy)) * s[2], 0,
      t[0], t[1], t[2], 1
    ];
  }

  /**
   * The mesh nodes of a glTF's scene with their world matrices, as [{ mesh, m, skin }].
   * At most four visits per node, so a cyclic file still ends.
   */
  function _glbMeshNodes(json) {
    var scene = json.scenes && json.scenes[json.scene || 0];
    var roots = scene && scene.nodes;
    if (!roots) {
      var isChild = {};
      json.nodes.forEach(function(n) { (n.children || []).forEach(function(c) { isChild[c] = true; }); });
      roots = json.nodes.map(function(_n, i) { return i; }).filter(function(i) { return !isChild[i]; });
    }
    var identity = [1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1];
    var stack = roots.map(function(i) { return { i: i, m: identity }; }), out = [];
    var budget = json.nodes.length * 4 + 16;
    while (stack.length && budget-- > 0) {
      var item = stack.pop(), node = json.nodes[item.i];
      if (!node) continue;
      var M = _mat4Mul(item.m, _nodeMatrix(node));
      if (node.mesh !== undefined && json.meshes[node.mesh]) {
        out.push({ mesh: json.meshes[node.mesh], m: M, skin: node.skin !== undefined });
      }
      (node.children || []).forEach(function(c) { stack.push({ i: c, m: M }); });
    }
    return out;
  }

  /**
   * A model's bounds from its vertices, in the space of getBoundingBox(), or null when
   * some of them cannot be read (compressed, external, skinned). gltfio's box is the
   * union of each primitive's accessor box once moved by its node, which a rotated node
   * inflates: a floor at its bottom can sit under the model (9 mm under a 7 cm watch).
   */
  function _studioTightBounds(bytes) {
    var g = _glbChunks(bytes);
    if (!g || !g.bin || !g.json.meshes || !g.json.nodes || !g.json.accessors) return null;
    var json = g.json, bin = g.bin;
    if (json.buffers && json.buffers[0] && json.buffers[0].uri) return null;
    var min = [Infinity, Infinity, Infinity], max = [-Infinity, -Infinity, -Infinity], ok = true;
    _glbMeshNodes(json).forEach(function(e) {
      if (e.skin) { ok = false; return; }
      var M = e.m;
      (e.mesh.primitives || []).forEach(function(prim) {
        var P = !prim.attributes || (prim.extensions && prim.extensions.KHR_draco_mesh_compression) ||
          (prim.targets && prim.targets.length) ? null : _readPositions(json, bin, prim.attributes.POSITION);
        if (!P) { ok = false; return; }
        for (var i = 0; i < P.length; i += 3) {
          var x = P[i], y = P[i + 1], z = P[i + 2];
          for (var k = 0; k < 3; k++) {
            var v = M[k] * x + M[4 + k] * y + M[8 + k] * z + M[12 + k];
            if (v < min[k]) min[k] = v;
            if (v > max[k]) max[k] = v;
          }
        }
      });
    });
    return ok && min[0] <= max[0] ? { min: min, max: max } : null;
  }

  /** The bufferView behind a plain (not sparse, not compressed, embedded) accessor. */
  function _glbPlainView(json, acc) {
    if (!acc || acc.sparse || acc.bufferView === undefined) return null;
    var bv = json.bufferViews && json.bufferViews[acc.bufferView];
    if (!bv || (bv.buffer || 0) !== 0) return null;
    if (bv.extensions && (bv.extensions.EXT_meshopt_compression || bv.extensions.KHR_meshopt_compression)) return null;
    return bv;
  }

  /** Float VEC3 positions of an accessor, or null when they cannot be read as such. */
  function _readPositions(json, bin, ai) {
    var acc = json.accessors && json.accessors[ai];
    if (!acc || acc.type !== 'VEC3' || acc.componentType !== 5126 || acc.normalized) return null;
    var bv = _glbPlainView(json, acc);
    if (!bv) return null;
    var n = acc.count, stride = bv.byteStride || 12, base = (bv.byteOffset || 0) + (acc.byteOffset || 0);
    if (!(n > 0) || base + (n - 1) * stride + 12 > bin.byteLength) return null;
    var out = new Float32Array(n * 3), i;
    if ((bin.byteOffset + base) % 4 === 0 && stride % 4 === 0) {
      var f = new Float32Array(bin.buffer, bin.byteOffset + base, ((n - 1) * stride + 12) / 4), s4 = stride / 4;
      for (i = 0; i < n; i++) { out[i * 3] = f[i * s4]; out[i * 3 + 1] = f[i * s4 + 1]; out[i * 3 + 2] = f[i * s4 + 2]; }
    } else {
      var dv = new DataView(bin.buffer, bin.byteOffset, bin.byteLength);
      for (i = 0; i < n; i++) {
        var o = base + i * stride;
        out[i * 3] = dv.getFloat32(o, true); out[i * 3 + 1] = dv.getFloat32(o + 4, true); out[i * 3 + 2] = dv.getFloat32(o + 8, true);
      }
    }
    return out;
  }

  /** Triangle-list indices of a primitive (0..n-1 when it has none), or null. */
  function _readIndices(json, bin, ai, nVerts) {
    var i;
    if (ai === undefined) {
      var seq = new Uint32Array(nVerts);
      for (i = 0; i < nVerts; i++) seq[i] = i;
      return seq;
    }
    var acc = json.accessors && json.accessors[ai];
    if (!acc || acc.type !== 'SCALAR') return null;
    var bv = _glbPlainView(json, acc);
    if (!bv) return null;
    var size = acc.componentType === 5125 ? 4 : acc.componentType === 5123 ? 2 : acc.componentType === 5121 ? 1 : 0;
    var n = acc.count, base = (bv.byteOffset || 0) + (acc.byteOffset || 0);
    if (!size || base + n * size > bin.byteLength) return null;
    var abs = bin.byteOffset + base;
    if (abs % size === 0) {
      return size === 4 ? new Uint32Array(bin.buffer, abs, n)
        : size === 2 ? new Uint16Array(bin.buffer, abs, n) : new Uint8Array(bin.buffer, abs, n);
    }
    var dv = new DataView(bin.buffer, bin.byteOffset, bin.byteLength), out = new Uint32Array(n);
    for (i = 0; i < n; i++) {
      out[i] = size === 4 ? dv.getUint32(base + i * 4, true) : size === 2 ? dv.getUint16(base + i * 2, true) : dv.getUint8(base + i);
    }
    return out;
  }

  // ---- Load-time rewrites of a glTF that Filament.js would not draw (#4566) ----

  var _COMPONENT_BYTES = { 5120: 1, 5121: 1, 5122: 2, 5123: 2, 5125: 4, 5126: 4 };
  var _TYPE_COMPONENTS = { SCALAR: 1, VEC2: 2, VEC3: 3, VEC4: 4, MAT4: 16 };

  /**
   * A GLB's JSON chunk as text and its BIN chunk (a view, not a copy), or null when the
   * bytes are not a GLB. Nothing is parsed, so a gate can look at the text first.
   */
  function _glbParts(bytes) {
    if (!bytes || bytes.byteLength < 20) return null;
    var dv = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
    if (dv.getUint32(0, true) !== 0x46546C67 || dv.getUint32(16, true) !== 0x4E4F534A) return null;
    var len = dv.getUint32(12, true);
    if (20 + len > bytes.byteLength) return null;
    var text = new TextDecoder().decode(bytes.subarray(20, 20 + len)), bin = null, o = 20 + len;
    if (o + 8 <= bytes.byteLength && dv.getUint32(o + 4, true) === 0x004E4942) {
      var binLen = dv.getUint32(o, true);
      if (o + 8 + binLen <= bytes.byteLength) bin = bytes.subarray(o + 8, o + 8 + binLen);
    }
    return { text: text, bin: bin };
  }

  /** GLB bytes from a glTF JSON object and a BIN chunk: JSON padded with spaces, BIN with zeros. */
  function _buildGlb(json, bin) {
    var text = new TextEncoder().encode(JSON.stringify(json));
    var jsonPad = (4 - text.length % 4) % 4;
    var binLen = bin ? bin.length : 0, binPad = (4 - binLen % 4) % 4;
    var total = 20 + text.length + jsonPad + (binLen ? 8 + binLen + binPad : 0);
    var out = new Uint8Array(total), dv = new DataView(out.buffer);
    dv.setUint32(0, 0x46546C67, true);
    dv.setUint32(4, 2, true);
    dv.setUint32(8, total, true);
    dv.setUint32(12, text.length + jsonPad, true);
    dv.setUint32(16, 0x4E4F534A, true);
    out.set(text, 20);
    out.fill(0x20, 20 + text.length, 20 + text.length + jsonPad);
    if (binLen) {
      var o = 20 + text.length + jsonPad;
      dv.setUint32(o, binLen + binPad, true);
      dv.setUint32(o + 4, 0x004E4942, true);
      out.set(bin, o + 8);
    }
    return out;
  }

  /** A growing BIN chunk, seeded with the original one so every existing offset holds. */
  function _binSink(seed) {
    var parts = [], size = 0;
    function write(b) { if (b && b.length) { parts.push(b); size += b.length; } }
    write(seed);
    return {
      /** Append `bytes` under a new, 4-byte-aligned view of buffer 0; returns its index. */
      appendView: function(json, bytes, extra) {
        var pad = (4 - size % 4) % 4;
        if (pad) write(new Uint8Array(pad));
        var view = { buffer: 0, byteOffset: size, byteLength: bytes.length };
        for (var k in extra || {}) view[k] = extra[k];
        json.bufferViews = json.bufferViews || [];
        json.bufferViews.push(view);
        write(bytes);
        return json.bufferViews.length - 1;
      },
      bytes: function() {
        var out = new Uint8Array(size), o = 0;
        parts.forEach(function(p) { out.set(p, o); o += p.length; });
        return out;
      }
    };
  }

  /** An accessor's raw component values (not normalized), or null when they cannot be read in place. */
  function _readAccessor(json, bin, ai) {
    var acc = json.accessors && json.accessors[ai];
    var bv = _glbPlainView(json, acc);
    var size = acc && _COMPONENT_BYTES[acc.componentType], comps = acc && _TYPE_COMPONENTS[acc.type];
    if (!bv || !bin || !size || !comps) return null;
    var n = acc.count, elem = size * comps, stride = bv.byteStride || elem;
    var base = (bv.byteOffset || 0) + (acc.byteOffset || 0);
    if (!(n > 0) || base + (n - 1) * stride + elem > bin.byteLength) return null;
    var dv = new DataView(bin.buffer, bin.byteOffset, bin.byteLength), out = new Float32Array(n * comps);
    var ct = acc.componentType;
    for (var i = 0; i < n; i++) {
      for (var c = 0, o = base + i * stride; c < comps; c++, o += size) {
        out[i * comps + c] = ct === 5126 ? dv.getFloat32(o, true) : ct === 5123 ? dv.getUint16(o, true)
          : ct === 5121 ? dv.getUint8(o) : ct === 5125 ? dv.getUint32(o, true)
            : ct === 5122 ? dv.getInt16(o, true) : dv.getInt8(o);
      }
    }
    return { count: n, comps: comps, data: out };
  }

  function _fromDataUri(uri) {
    try {
      var bin = atob(uri.slice(uri.indexOf('base64,') + 7)), out = new Uint8Array(bin.length);
      for (var i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
      return out;
    } catch (e) {
      return null;
    }
  }

  function _toBase64(bytes) {
    var s = '';
    // fromCharCode.apply overflows the argument limit on a whole image: 32 KB at a time.
    for (var o = 0; o < bytes.length; o += 0x8000) {
      s += String.fromCharCode.apply(null, bytes.subarray(o, Math.min(o + 0x8000, bytes.length)));
    }
    return btoa(s);
  }

  /** Largest WebP edge decoded: 8192 is where WebGL textures commonly stop anyway. */
  var _WEBP_MAX_EDGE = 8192;
  /** Most WebP images decoded per model: bounds what a few KB of crafted JSON can ask for. */
  var _WEBP_MAX_IMAGES = 256;

  /** PNG bytes of a WebP image, or null when the browser cannot decode it or it is too big. */
  function _webpToPng(webp) {
    if (typeof createImageBitmap !== 'function') return Promise.resolve(null);
    // No colour management: a texture holds data (normals, roughness), not a photo.
    return createImageBitmap(new Blob([webp], { type: 'image/webp' }), { colorSpaceConversion: 'none' })
      .then(function(bitmap) {
        var w = bitmap.width, h = bitmap.height;
        if (!(w >= 1 && h >= 1 && w <= _WEBP_MAX_EDGE && h <= _WEBP_MAX_EDGE)) { bitmap.close(); return null; }
        var canvas;
        if (typeof OffscreenCanvas === 'function') canvas = new OffscreenCanvas(w, h);
        else { canvas = document.createElement('canvas'); canvas.width = w; canvas.height = h; }
        var ctx = canvas.getContext('2d');
        if (!ctx) { bitmap.close(); return null; }
        ctx.drawImage(bitmap, 0, 0);
        bitmap.close();
        var blob = canvas.convertToBlob ? canvas.convertToBlob({ type: 'image/png' })
          : new Promise(function(resolve) { canvas.toBlob(resolve, 'image/png'); });
        return blob.then(function(b) { return b ? b.arrayBuffer() : null; })
          .then(function(ab) { return ab ? new Uint8Array(ab) : null; });
      })
      .catch(function() { return null; });
  }

  function _webpSource(texture) {
    var ext = texture && texture.extensions && texture.extensions.EXT_texture_webp;
    return ext && Number.isInteger(ext.source) ? ext.source : -1;
  }

  /**
   * The bytes of a glTF/GLB with every embedded WebP texture re-encoded to PNG.
   * Filament.js registers no image/webp decoder, so an EXT_texture_webp model drew
   * untextured, grey or black, with only "Missing texture provider for image/webp" on
   * the console (#4566). This is sceneview-web's WebPTextureTranscoder (#3133) for the
   * viewer pages: the images stored in the BIN chunk or in a data: URI are decoded by
   * the browser, re-encoded to PNG and appended to the BIN chunk (data: URIs stay data:
   * URIs), and EXT_texture_webp is dropped once nothing needs it. External image files
   * are left alone. A model with no WebP comes back as the same bytes, after a look at
   * its JSON text only. Never rejects: what cannot be rewritten goes to gltfio as it came.
   * PNG keeps alpha, but a 2D canvas premultiplies it: partly transparent texels round.
   * `stale()`, when given, is asked before each decode: once it returns an error (the
   * load was replaced or the viewer disposed), no further image is decoded and the
   * bytes come back as they came, for the caller to drop.
   */
  function _transcodeWebP(bytes, stale) {
    var parts, text, json;
    try {
      parts = _glbParts(bytes);
      // A .gltf is JSON text; a large binary that is not a GLB is not looked at.
      text = parts ? parts.text : (bytes.length < 64e6 ? new TextDecoder().decode(bytes) : '');
      if (!/webp/i.test(text)) return Promise.resolve(bytes);
      json = JSON.parse(text);
    } catch (e) {
      return Promise.resolve(bytes);
    }
    var images = json.images || [], textures = json.textures || [];
    var bin = parts ? parts.bin : null, buffer0 = json.buffers && json.buffers[0];
    // PNGs go into the BIN chunk only when buffer 0 is that chunk.
    var binBacked = !!(bin && bin.length && buffer0 && buffer0.uri === undefined);
    var wanted = {};
    textures.forEach(function(t) {
      var s = _webpSource(t);
      // A texture that keeps a non-WebP `source` already has its fallback.
      if (s >= 0 && t.source === undefined) wanted[s] = true;
    });
    images.forEach(function(im, i) { if (im && im.mimeType === 'image/webp') wanted[i] = true; });
    var list = Object.keys(wanted).map(Number).sort(function(a, b) { return a - b; });
    var budget = list.slice(0, _WEBP_MAX_IMAGES), failed = list.length - budget.length;

    function embedded(im) {
      var v = im && Number.isInteger(im.bufferView) && json.bufferViews && json.bufferViews[im.bufferView];
      if (v && bin && (v.buffer || 0) === 0 && buffer0 && buffer0.uri === undefined) {
        var o = v.byteOffset || 0, n = v.byteLength || 0;
        if (o >= 0 && n > 0 && o + n <= bin.length) return bin.subarray(o, o + n);
      }
      return im && typeof im.uri === 'string' && im.uri.indexOf('data:') === 0 ? _fromDataUri(im.uri) : null;
    }

    // Four decodes at a time; the PNGs are appended in image order, so the layout is fixed.
    var pngs = new Array(budget.length), next = 0;
    function dropped() { return !!(stale && stale()); }
    function worker() {
      // A replaced load stops decoding: a 30-texture model no longer runs to its end.
      if (next >= budget.length || dropped()) return Promise.resolve();
      var k = next++, src = embedded(images[budget[k]]);
      return (src ? _webpToPng(src) : Promise.resolve(null))
        .then(function(png) { pngs[k] = png; return worker(); });
    }
    var workers = [];
    for (var w = 0; w < Math.min(4, budget.length); w++) workers.push(worker());

    return Promise.all(workers).then(function() {
      if (dropped()) return bytes;
      var sink = binBacked ? _binSink(bin) : null, converted = {}, count = 0;
      budget.forEach(function(ii, k) {
        var im = images[ii], png = pngs[k];
        if (!png) { failed++; return; }
        if (sink) {
          im.bufferView = sink.appendView(json, png);
          delete im.uri;
        } else {
          im.uri = 'data:image/png;base64,' + _toBase64(png);
          delete im.bufferView;
        }
        im.mimeType = 'image/png';
        converted[ii] = true;
        count++;
      });
      textures.forEach(function(t) {
        var s = _webpSource(t);
        if (s >= 0 && (converted[s] || t.source !== undefined)) {
          if (t.source === undefined) t.source = s;
          delete t.extensions.EXT_texture_webp;
          if (!Object.keys(t.extensions).length) delete t.extensions;
        }
      });
      if (!failed) {
        ['extensionsUsed', 'extensionsRequired'].forEach(function(key) {
          if (!Array.isArray(json[key])) return;
          json[key] = json[key].filter(function(x) { return x !== 'EXT_texture_webp'; });
          if (!json[key].length) delete json[key];
        });
      } else {
        console.error('SceneView: ' + failed + ' WebP texture(s) of this model could not be decoded, so ' +
          'they render untextured. Only WebP images embedded in the file (GLB buffer view or data: URI) ' +
          'are converted at load time; re-encode the others to PNG or JPEG.');
      }
      if (!count) return bytes;
      if (!parts) return new TextEncoder().encode(JSON.stringify(json));
      var newBin = sink ? sink.bytes() : bin;
      // Buffer 0 now spans the old chunk (padding included) plus the PNGs.
      if (sink) buffer0.byteLength = newBin.length;
      return _buildGlb(json, newBin);
    }).catch(function(e) {
      console.warn('SceneView: WebP texture transcoding skipped', e);
      return bytes;
    });
  }

  /** Filament's bone limit per renderable (CONFIG_MAX_BONE_COUNT). */
  var _MAX_BONES = 256;

  /**
   * The bytes of a GLB whose skins all fit Filament's 256 bones. gltfio rejects a bigger
   * skin outright, with a bare number for an error (KindredLol's 1,523 joints, #4566),
   * though each mesh of such a model is often weighted to far fewer. A skin over the
   * limit is split into one skin per mesh it deforms, holding only the joints that mesh
   * is weighted to, and the mesh's JOINTS_n are rewritten to index it; the subsets keep
   * the original joint order and inverse bind matrices, so the pose is unchanged. The
   * same bytes when no skin is too big, or when the split cannot be made (a mesh
   * weighted to more than 256 joints, compressed or sparse skin data): gltfio then
   * rejects the model and _showModel says why.
   */
  function _splitOversizedSkins(bytes) {
    var parts = _glbParts(bytes);
    if (!parts || !parts.bin || parts.text.indexOf('"skins"') < 0) return bytes;
    var json = JSON.parse(parts.text), skins = json.skins || [], nodes = json.nodes || [];
    var big = skins.map(function(s) { return !!(s && s.joints && s.joints.length > _MAX_BONES); });
    if (big.indexOf(true) < 0) return bytes;
    var bin = parts.bin, buffer0 = json.buffers && json.buffers[0];
    function cannot(why) {
      console.warn('SceneView: a skin has more than ' + _MAX_BONES + ' joints and could not be split (' + why + ')');
      return bytes;
    }
    if (!buffer0 || buffer0.uri !== undefined) return cannot('its data is outside the GLB');

    // The meshes an oversized skin deforms, and the joints each one is weighted to.
    var meshes = {};
    nodes.forEach(function(n) {
      if (n && Number.isInteger(n.skin) && big[n.skin] && Number.isInteger(n.mesh)) meshes[n.mesh] = null;
    });
    var meshIds = Object.keys(meshes).map(Number);
    for (var m = 0; m < meshIds.length; m++) {
      var mesh = json.meshes && json.meshes[meshIds[m]], used = {};
      if (!mesh) return cannot('a node names a missing mesh');
      for (var p = 0; p < (mesh.primitives || []).length; p++) {
        var prim = mesh.primitives[p], attrs = prim.attributes || {};
        if (prim.extensions && prim.extensions.KHR_draco_mesh_compression) return cannot('Draco-compressed mesh');
        for (var key in attrs) {
          var set = /^JOINTS_(\d+)$/.exec(key);
          if (!set) continue;
          var J = _readAccessor(json, bin, attrs[key]), W = _readAccessor(json, bin, attrs['WEIGHTS_' + set[1]]);
          if (!J || !W || J.comps !== 4 || W.comps !== 4 || J.count !== W.count) return cannot('unreadable joints or weights');
          for (var i = 0; i < J.data.length; i++) if (W.data[i] > 0) used[J.data[i]] = true;
        }
      }
      var list = Object.keys(used).map(Number).sort(function(a, b) { return a - b; });
      if (list.length > _MAX_BONES) return cannot('mesh ' + meshIds[m] + ' is weighted to ' + list.length + ' joints');
      var map = {};
      list.forEach(function(j, k) { map[j] = k; });
      meshes[meshIds[m]] = { used: list, map: map };
    }
    // Every skin a remapped mesh is drawn with must hold the joints it indexes, and its
    // inverse bind matrices must be readable: a subset skin without them would draw the
    // mesh in its bind-space coordinates, so the split is given up instead.
    var ibms = {};
    for (var ni = 0; ni < nodes.length; ni++) {
      var nd = nodes[ni], r = nd && Number.isInteger(nd.mesh) ? meshes[nd.mesh] : null;
      if (r && Number.isInteger(nd.skin)) {
        var sk = skins[nd.skin];
        if (!sk || !sk.joints || (r.used.length && r.used[r.used.length - 1] >= sk.joints.length)) {
          return cannot('a joint index is past the end of its skin');
        }
        var ia = sk.inverseBindMatrices;
        if (ia !== undefined && ibms[ia] === undefined) {
          var M = Number.isInteger(ia) ? _readAccessor(json, bin, ia) : null;
          if (!M || M.comps !== 16 || M.count < sk.joints.length) return cannot('unreadable inverse bind matrices');
          ibms[ia] = M;
        }
      }
    }

    var sink = _binSink(bin);
    // JOINTS_n rewritten per mesh, as unsigned shorts; a joint the mesh never weights reads 0.
    meshIds.forEach(function(mi) {
      var r = meshes[mi], done = {};
      json.meshes[mi].primitives.forEach(function(prim) {
        Object.keys(prim.attributes).forEach(function(key) {
          if (!/^JOINTS_\d+$/.test(key)) return;
          var ai = prim.attributes[key];
          if (done[ai] === undefined) {
            var J = _readAccessor(json, bin, ai), out = new Uint16Array(J.data.length);
            for (var i = 0; i < J.data.length; i++) out[i] = r.map[J.data[i]] || 0;
            var view = sink.appendView(json, new Uint8Array(out.buffer), { byteStride: 8, target: 34962 });
            json.accessors.push({ bufferView: view, componentType: 5123, count: J.count, type: 'VEC4' });
            done[ai] = json.accessors.length - 1;
          }
          prim.attributes[key] = done[ai];
        });
      });
    });
    // The skins that fit stay; each (mesh, oversized skin) pair gets its subset.
    var out = [], kept = {}, made = {};
    skins.forEach(function(s, i) { if (!big[i]) { kept[i] = out.length; out.push(s); } });
    nodes.forEach(function(n) {
      if (!n || !Number.isInteger(n.skin)) return;
      var r = Number.isInteger(n.mesh) ? meshes[n.mesh] : null;
      if (!r) {
        // An oversized skin on a node with no mesh deforms nothing.
        if (big[n.skin]) delete n.skin;
        else n.skin = kept[n.skin];
        return;
      }
      var id = n.mesh + ':' + n.skin;
      if (made[id] === undefined) {
        var s = skins[n.skin], skin = { joints: r.used.map(function(j) { return s.joints[j]; }) };
        if (s.skeleton !== undefined) skin.skeleton = s.skeleton;
        if (s.name) skin.name = s.name + '_mesh' + n.mesh;
        if (s.inverseBindMatrices !== undefined) {
          // Read and checked above: a skin whose matrices could not be read was never split.
          var M = ibms[s.inverseBindMatrices];
          var sub = new Float32Array(r.used.length * 16);
          r.used.forEach(function(j, k) { sub.set(M.data.subarray(j * 16, j * 16 + 16), k * 16); });
          var view = sink.appendView(json, new Uint8Array(sub.buffer));
          json.accessors.push({ bufferView: view, componentType: 5126, count: r.used.length, type: 'MAT4' });
          skin.inverseBindMatrices = json.accessors.length - 1;
        }
        made[id] = out.length;
        out.push(skin);
      }
      n.skin = made[id];
    });
    json.skins = out;
    var newBin = sink.bytes();
    buffer0.byteLength = newBin.length;
    return _buildGlb(json, newBin);
  }

  /**
   * What gltfio would choke on in these bytes, for an error a person can act on: gltfio
   * throws a bare number (a C++ exception pointer from the WASM), not a message.
   */
  function _gltfioRejectionHint(bytes) {
    try {
      var parts = _glbParts(bytes), text = parts ? parts.text : '';
      if (text.indexOf('"skins"') < 0) return '';
      var most = 0;
      (JSON.parse(text).skins || []).forEach(function(s) { most = Math.max(most, (s.joints || []).length); });
      return most > _MAX_BONES ? ': a skin has ' + most + ' joints, and a mesh can be moved by at most ' + _MAX_BONES : '';
    } catch (e) {
      return '';
    }
  }

  /** gltfio's divisor for a normalized component type (KHR_mesh_quantization). */
  var _NORMALIZED_DIVISOR = { 5120: 127, 5121: 255, 5122: 32767, 5123: 65535 };

  /**
   * For each mesh with a normalized POSITION accessor: the box gltfio gives its renderable
   * (accessor min/max as stored, every primitive) and its real box. [] when none.
   */
  function _quantizedMeshBoxes(json) {
    var accessors = json.accessors || [], out = [];
    (json.meshes || []).forEach(function(mesh) {
      var raw = { min: [Infinity, Infinity, Infinity], max: [-Infinity, -Infinity, -Infinity] };
      var real = { min: raw.min.slice(), max: raw.max.slice() }, quantized = false, ok = true;
      (mesh.primitives || []).forEach(function(prim) {
        var a = prim.attributes && accessors[prim.attributes.POSITION];
        if (!a || !Array.isArray(a.min) || !Array.isArray(a.max) || a.min.length < 3 || a.max.length < 3) { ok = false; return; }
        var d = (a.normalized && _NORMALIZED_DIVISOR[a.componentType]) || 0;
        if (d) quantized = true;
        for (var k = 0; k < 3; k++) {
          raw.min[k] = Math.min(raw.min[k], a.min[k]);
          raw.max[k] = Math.max(raw.max[k], a.max[k]);
          // A signed normalized value is max(c / divisor, -1) (glTF 2.0, 3.11).
          real.min[k] = Math.min(real.min[k], d ? Math.max(a.min[k] / d, -1) : a.min[k]);
          real.max[k] = Math.max(real.max[k], d ? Math.max(a.max[k] / d, -1) : a.max[k]);
        }
      });
      if (quantized && ok) out.push({ raw: raw, real: real });
    });
    return out;
  }

  /**
   * The floor texel grid of a model's shadow: about 256 texels along its long side, and
   * at least `margin` (the reflection's reach) around the footprint.
   */
  function _studioShadowGrid(bbox, margin) {
    var dx = bbox.max[0] - bbox.min[0], dy = bbox.max[1] - bbox.min[1], dz = bbox.max[2] - bbox.min[2];
    var L = Math.max(dx, dy, dz), m = Math.max(0.35 * L, margin || 0);
    var ts = Math.max(dx + 2 * m, dz + 2 * m) / 256;
    var w = Math.max(8, Math.ceil((dx + 2 * m) / ts)), h = Math.max(8, Math.ceil((dz + 2 * m) / ts));
    var cx = (bbox.min[0] + bbox.max[0]) / 2, cz = (bbox.min[2] + bbox.max[2]) / 2;
    return { L: L, ts: ts, w: w, h: h, x0: cx - (w * ts) / 2, z0: cz - (h * ts) / 2, margin: m };
  }

  /** In-place separable Gaussian blur of a w x h field, zero outside. */
  function _blurField(a, w, h, sigma) {
    if (!(sigma > 0.3)) return a;
    var r = Math.ceil(3 * sigma), k = new Float32Array(2 * r + 1), sum = 0, i, x, y, j, acc;
    for (i = -r; i <= r; i++) { k[i + r] = Math.exp(-(i * i) / (2 * sigma * sigma)); sum += k[i + r]; }
    for (i = 0; i < k.length; i++) k[i] /= sum;
    var tmp = new Float32Array(w * h);
    for (y = 0; y < h; y++) {
      var row = y * w;
      for (x = 0; x < w; x++) {
        acc = 0;
        for (j = Math.max(-r, -x); j <= Math.min(r, w - 1 - x); j++) acc += a[row + x + j] * k[j + r];
        tmp[row + x] = acc;
      }
    }
    for (x = 0; x < w; x++) {
      for (y = 0; y < h; y++) {
        acc = 0;
        for (j = Math.max(-r, -y); j <= Math.min(r, h - 1 - y); j++) acc += tmp[(y + j) * w + x] * k[j + r];
        a[y * w + x] = acc;
      }
    }
    return a;
  }

  /** _blurField() into a new field, run on a grid coarse enough to keep a wide blur cheap. */
  function _blurFieldCoarse(a, w, h, sigma) {
    var f = Math.max(1, Math.floor(sigma / 3)), x, y;
    var cw = Math.ceil(w / f), ch = Math.ceil(h / f), c = new Float32Array(cw * ch);
    for (y = 0; y < h; y++) {
      for (x = 0; x < w; x++) c[Math.floor(y / f) * cw + Math.floor(x / f)] += a[y * w + x];
    }
    for (y = 0; y < ch; y++) {
      for (x = 0; x < cw; x++) c[y * cw + x] /= Math.min(f, w - x * f) * Math.min(f, h - y * f);
    }
    _blurField(c, cw, ch, sigma / f);
    var out = new Float32Array(w * h);
    for (y = 0; y < h; y++) {
      var gy = Math.min(ch - 1, Math.max(0, (y + 0.5) / f - 0.5)), y0 = Math.floor(gy);
      var y1 = Math.min(ch - 1, y0 + 1), ty = gy - y0;
      for (x = 0; x < w; x++) {
        var gx = Math.min(cw - 1, Math.max(0, (x + 0.5) / f - 0.5)), x0 = Math.floor(gx);
        var x1 = Math.min(cw - 1, x0 + 1), tx = gx - x0;
        out[y * w + x] = (c[y0 * cw + x0] * (1 - tx) + c[y0 * cw + x1] * tx) * (1 - ty) +
          (c[y1 * cw + x0] * (1 - tx) + c[y1 * cw + x1] * tx) * ty;
      }
    }
    return out;
  }

  /**
   * Bake the model's floor shadow from its own triangles. For every floor texel, the
   * height of the lowest surface above it; then a sharp contact term (surfaces within
   * 1.5 % of the model size, the tyres of a car) and an ambient-occlusion term: how low
   * a surface covers the texel (within a quarter of the model's size, the underbody),
   * blurred at two scales, the wider one for the open floor the body still hides sky
   * from. Returns the grid with `s`, the shadow in 0-1 per texel, and `u`, how covered
   * it is (under the body), or null when the triangles cannot be read (Draco, meshopt,
   * external buffers): the caller falls back to a soft ellipse.
   */
  function _studioBakeShadow(bytes, bbox, margin) {
    var g = _glbChunks(bytes);
    if (!g || !g.bin || !g.json.meshes || !g.json.nodes) return null;
    var json = g.json, bin = g.bin;
    if (json.buffers && json.buffers[0] && json.buffers[0].uri) return null;
    var grid = _studioShadowGrid(bbox, margin);
    var w = grid.w, h = grid.h, ts = grid.ts, L = grid.L, yf = bbox.min[1];
    var hmax = 0.25 * L;
    var H = new Float32Array(w * h).fill(hmax);
    var found = 0;

    function put(iu, iv, hv) {
      if (iu < 0 || iv < 0 || iu >= w || iv >= h) return;
      var k = iv * w + iu;
      if (hv < H[k]) H[k] = hv;
    }

    function drawEdge(u0, v0, h0, u1, v1, h1) {
      var n = Math.ceil(2 * Math.max(Math.abs(u1 - u0), Math.abs(v1 - v0))) + 1;
      for (var q = 0; q <= n; q++) {
        var t = q / n;
        put(Math.floor(u0 + (u1 - u0) * t), Math.floor(v0 + (v1 - v0) * t), h0 + (h1 - h0) * t);
      }
    }

    function drawMesh(mesh, M) {
      (mesh.primitives || []).forEach(function(prim) {
        if ((prim.mode !== undefined && prim.mode !== 4) || !prim.attributes) return;
        if (prim.extensions && prim.extensions.KHR_draco_mesh_compression) return;
        var P = _readPositions(json, bin, prim.attributes.POSITION);
        if (!P) return;
        var nv = P.length / 3;
        var I = _readIndices(json, bin, prim.indices, nv);
        if (!I) return;
        var U = new Float32Array(nv), V = new Float32Array(nv), Y = new Float32Array(nv), i;
        for (i = 0; i < nv; i++) {
          var x = P[i * 3], y = P[i * 3 + 1], z = P[i * 3 + 2];
          U[i] = (M[0] * x + M[4] * y + M[8] * z + M[12] - grid.x0) / ts;
          Y[i] = Math.max(0, M[1] * x + M[5] * y + M[9] * z + M[13] - yf);
          V[i] = (M[2] * x + M[6] * y + M[10] * z + M[14] - grid.z0) / ts;
        }
        for (var f = 0; f + 2 < I.length; f += 3) {
          var a = I[f], b = I[f + 1], c = I[f + 2];
          if (a >= nv || b >= nv || c >= nv) continue;
          var ha = Y[a], hb = Y[b], hc = Y[c];
          var hlo = Math.min(ha, hb, hc);
          if (hlo >= hmax) continue;
          found++;
          var ua = U[a], ub = U[b], uc = U[c], va = V[a], vb = V[b], vc = V[c];
          var iu0 = Math.max(0, Math.floor(Math.min(ua, ub, uc))), iu1 = Math.min(w - 1, Math.floor(Math.max(ua, ub, uc)));
          var iv0 = Math.max(0, Math.floor(Math.min(va, vb, vc))), iv1 = Math.min(h - 1, Math.floor(Math.max(va, vb, vc)));
          if (iu0 > iu1 || iv0 > iv1) continue;
          var iu, iv;
          if ((iu1 - iu0 + 1) * (iv1 - iv0 + 1) <= 4) {
            for (iv = iv0; iv <= iv1; iv++) for (iu = iu0; iu <= iu1; iu++) put(iu, iv, hlo);
            continue;
          }
          var area = (ub - ua) * (vc - va) - (uc - ua) * (vb - va);
          if (area < 0) {  // make the winding counter-clockwise
            var tu = ub, tv = vb, th = hb;
            ub = uc; vb = vc; hb = hc; uc = tu; vc = tv; hc = th; area = -area;
          }
          var lab = Math.hypot(ub - ua, vb - va), lbc = Math.hypot(uc - ub, vc - vb), lca = Math.hypot(ua - uc, va - vc);
          var longest = Math.max(lab, lbc, lca);
          if (!(area > 0) || area / longest < 0.5) {  // a sliver: walk its edges
            drawEdge(ua, va, ha, ub, vb, hb);
            drawEdge(ub, vb, hb, uc, vc, hc);
            drawEdge(uc, vc, hc, ua, va, ha);
            continue;
          }
          var hhi = Math.max(ha, hb, hc);
          for (iv = iv0; iv <= iv1; iv++) {
            var pv = iv + 0.5;
            for (iu = iu0; iu <= iu1; iu++) {
              var pu = iu + 0.5;
              var eab = (ub - ua) * (pv - va) - (vb - va) * (pu - ua);
              var ebc = (uc - ub) * (pv - vb) - (vc - vb) * (pu - ub);
              var eca = (ua - uc) * (pv - vc) - (va - vc) * (pu - uc);
              // Conservative: a texel the triangle grazes counts as covered.
              if (eab < -0.7 * lab || ebc < -0.7 * lbc || eca < -0.7 * lca) continue;
              var hv = (ebc * ha + eca * hb + eab * hc) / area;
              put(iu, iv, Math.min(hhi, Math.max(hlo, hv)));
            }
          }
        }
      });
    }

    _glbMeshNodes(json).forEach(function(e) { drawMesh(e.mesh, e.m); });
    if (!found) return null;

    var n = w * h, contact = new Float32Array(n), cover = new Float32Array(n), i;
    var cT = 0.015 * L;
    for (i = 0; i < n; i++) {
      contact[i] = H[i] < cT ? 1 : 0;
      var q = H[i] / hmax;
      cover[i] = q >= 1 ? 0 : 1 - q * q;
    }
    _blurField(contact, w, h, (0.01 * L) / ts);
    var wide = _blurFieldCoarse(cover, w, h, (0.16 * L) / ts);
    _blurField(cover, w, h, (0.05 * L) / ts);
    var s = new Float32Array(n);
    for (i = 0; i < n; i++) {
      s[i] = 1 - (1 - 0.95 * contact[i]) * (1 - 0.9 * (0.55 * cover[i] + 0.45 * wide[i]));
    }
    grid.s = _studioFadeEdges(s, w, h);
    grid.u = cover;
    return grid;
  }

  /** Fallback shadow: a soft ellipse under the footprint. */
  function _studioEllipseShadow(bbox, margin) {
    var g = _studioShadowGrid(bbox, margin), w = g.w, h = g.h, s = new Float32Array(w * h);
    var hx = (bbox.max[0] - bbox.min[0]) / 2 + 0.05 * g.L, hz = (bbox.max[2] - bbox.min[2]) / 2 + 0.05 * g.L;
    var cx = (bbox.min[0] + bbox.max[0]) / 2, cz = (bbox.min[2] + bbox.max[2]) / 2;
    for (var iv = 0; iv < h; iv++) {
      for (var iu = 0; iu < w; iu++) {
        var x = g.x0 + (iu + 0.5) * g.ts, z = g.z0 + (iv + 0.5) * g.ts;
        s[iv * w + iu] = 0.7 * (1 - _smoothstep(0.55, 1.25, Math.hypot((x - cx) / hx, (z - cz) / hz)));
      }
    }
    g.s = _studioFadeEdges(s, w, h);
    g.u = s.map(function(v) { return v / 0.7; });
    return g;
  }

  /** Take a shadow field to zero over its last texels, so the floor never shows its edge. */
  function _studioFadeEdges(s, w, h) {
    var band = 6;
    for (var iv = 0; iv < h; iv++) {
      for (var iu = 0; iu < w; iu++) {
        var e = Math.min(iu, iv, w - 1 - iu, h - 1 - iv);
        if (e < band) s[iv * w + iu] *= _smoothstep(0, band, e);
      }
    }
    return s;
  }

  /**
   * The floor's base colour texture over the shadow field (RGBA bytes for SRGB8_A8).
   * Alpha is how much of the reflection shows: 1 - rho, and none under the body, where
   * a real floor would mirror the unlit underbody (the reflection view lights it from
   * below and draws its gaps as empty floor). RGB is the factor the floor's colour is
   * multiplied by so that it shows t * (1 - s * shadow) on screen, through the tone
   * mapper and over an empty reflection. White past the field, where the texture clamps
   * to its edge: the field spans the whole reach of the reflection, so rho is 0 there.
   */
  function _studioShadowTexels(lay, conf, mirror, sh) {
    var w = sh.w, h = sh.h, ts = sh.ts, px = new Uint8Array(w * h * 4).fill(255);
    var shade = _studioFloorShade(lay, conf, mirror), H = conf.horizon, iu, iv, c;
    for (iv = 0; iv < h; iv++) {
      var dz = Math.max(0, Math.abs(sh.z0 + (iv + 0.5) * ts - lay.cz) - lay.hz);
      for (iu = 0; iu < w; iu++) {
        var i = iv * w + iu, raw = Math.min(1, Math.max(0, sh.s[i])), s = raw * conf.shadow;
        var dx = Math.max(0, Math.abs(sh.x0 + (iu + 0.5) * ts - lay.cx) - lay.hx);
        var d = Math.hypot(dx, dz);
        if (!(s > 0) && !(mirror && d < lay.fade)) continue;
        var p = shade(d);
        var rho = p.rho * (1 - _smoothstep(_STUDIO_UNDERBODY[0], _STUDIO_UNDERBODY[1], sh.u[i]));
        px[i * 4 + 3] = Math.round(255 * (1 - rho));
        if (!(s > 0) && rho === p.rho) continue;
        for (c = 0; c < 3; c++) {
          if (!(p.hdr[c] > 0)) continue;
          var shown = Math.min(1, Math.max(0, (p.t[c] * (1 - s) - rho * H[c]) / (1 - rho)));
          var f = (_inverseFilmic(_srgbToLinear(shown)) || 0) / p.hdr[c];
          px[i * 4 + c] = Math.round(255 * _linearToSrgb(f));
        }
      }
    }
    return px;
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
    constructor(canvas, engine, scene, renderer, view, swapChain, camera, cameraEntity, loader, materials) {
      this._canvas = canvas;
      this._engine = engine;
      this._scene = scene;
      this._renderer = renderer;
      this._view = view;
      this._swapChain = swapChain;
      this._camera = camera;
      this._cameraEntity = cameraEntity;
      this._loader = loader;
      this._materials = materials; // gltfio material provider behind the loader
      this._asset = null;

      // Native lifetime (#4366): everything dispose() has to release or cancel.
      this._disposed = false;
      this._abort = (typeof AbortController !== 'undefined') ? new AbortController() : null;
      this._assets = new Set();         // every live gltfio asset created by this viewer
      this._resourceLoads = new Map();  // asset -> its running resource load (see _loadResources)
      this._ibl = null;                 // { light, ktx } currently set on the scene
      this._loadingVideos = new Map();  // <video> still loading (createVideo) -> its reject()
      this._angle = 0.785; // Start at ~45° like model-viewer
      this._autoRotate = true;
      this._lastTickAt = null;          // the render loop's previous rAF time (studio turntable)
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
      this._primeRafId = null;     // pending off-screen redraw retry (#3690), if any
      this._listeners = [];        // {target, type, handler, options} for dispose() cleanup
      this._intersectionObserver = null;
      this._fallbackEl = null;     // load-failure placeholder overlay, if shown (#2509)
      this._isDragging = false;
      this._lastMouse = { x: 0, y: 0 };
      this._touchStart = null;     // where an undecided touch began (canvas with touch-action: pan-y)
      this._touchPansY = false;    // the current touch leaves the vertical swipe to the page
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
      this._lightEntities = new Set(); // light entities created via addLight()
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

      // Render on demand: 'continuous' draws every animation frame while visible (the
      // default); 'onDemand' parks the loop once nothing moves (see _isIdle) and
      // requestRender() wakes it. Both share the single loop below and its visibility gate.
      this._renderMode = "continuous";
      this._animationDone = false; // a non-looping animation reached its last frame
      this._frameCallbacks = new Set(); // onFrame() listeners
      this._renderRequested = false; // requestRender() landed while the loop was live

      // Studio showroom (setStudio): null while off
      this._studio = null;            // resolved theme and colours
      this._studioParts = {};         // dome, floor: gltfio assets owned by the studio
      this._studioLayout = null;      // floor, camera and dome sizes for the current model
      this._studioShadow = null;      // baked shadow field for the current model
      this._studioBox = null;         // the current model's bounds from its vertices
      this._studioTexture = null;     // that shadow on the floor, as its base colour texture
      this._studioSaved = null;       // camera settings to restore when the studio turns off
      this._studioTier = 3;           // quality tier, 3 (all effects) down to 0
      this._studioBudget = null;      // drawing-buffer pixel budget of that tier
      this._studioPerf = null;        // render-loop ticks that drop the tier when the GPU is short
      this._studioFramedAt = 0;       // when the studio last framed a model (performance.now())
      this._mirrorView = null;        // second view that draws the floor reflection
      this._mirrorCamera = null;
      this._mirrorCameraEntity = null;
      this._modelBytes = null;        // WeakRef to the loaded GLB, read once for the shadow bake
      this._reveal = null;            // camera move-in after a load, { start }
      this._dragHeightScale = 1;      // vertical drag speed, proportional to the camera distance
      this._envSerial = 0;            // bumped by every environment change: the last one wins
      this._envSource = null;         // the environment: { url, intensity, studio } or { bands, intensity }
      this._envLoading = null;        // that source while its KTX is on its way
      this._pageBackground = null;    // setBackgroundColor() value, restored without the studio
      this._defaultIblUrl = null;
      this._defaultIblIntensity = null;

      this._setupControls();
      this._setupResizeObserver();
      this._setupVisibilityGating();
      this._startRenderLoop();
    }

    // ---------------------------------------------------------------
    // Model loading (existing)
    // ---------------------------------------------------------------

    /**
     * Load a glTF/GLB model from URL. The model it replaces is destroyed.
     * Rejects with an `AbortError` when the viewer is disposed before the model arrives,
     * or when a later `loadModel()` call starts before this one is shown: the newest
     * call always wins, whichever file finishes downloading first.
     *
     * `options.frontYaw`: where the model's front faces, in degrees about the vertical
     * axis from glTF's +Z (0, the default). A file modelled facing -Z takes 180. The
     * camera then opens on the model's front three-quarter instead of its back.
     */
    loadModel(url, options) {
      var self = this;
      var frontYaw = options && Number.isFinite(options.frontYaw) ? options.frontYaw * Math.PI / 180 : null;
      // Each call takes a ticket; only the newest may touch the scene. Without it, a
      // slow file (the WebP pass adds seconds) landed after a newer, faster one and
      // replaced it: the playground's `?model=` showed the toy car it opened on (#4566).
      var ticket = self._loadTicket = (self._loadTicket || 0) + 1;
      function stale() {
        if (self._disposed) return _disposedError();
        if (ticket !== self._loadTicket) return _supersededError(url);
        return null;
      }
      return new Promise(function(resolve, reject) {
        // On any failure (network/404, fetch error, GLB parse/decode failure),
        // paint a graceful in-canvas fallback before rejecting (#2509). The
        // promise still rejects so existing callers that .catch() (index.html,
        // playground.html, web.html) keep their current behaviour — but bare
        // callers (the two showcase pages) no longer get a dead blank canvas.
        function fail(e) {
          // A disposed viewer has no canvas to paint on, and a superseded load must
          // not paint over the model that replaced it: just report the abort.
          var abort = stale();
          if (!abort) self._showLoadFallback(url);
          reject(abort || e);
        }
        self._fetch(url)
          .then(function(resp) {
            if (!resp.ok) throw new Error('HTTP ' + resp.status + ' loading ' + url);
            return resp.arrayBuffer();
          })
          .then(function(buffer) {
            // Disposed or superseded while the bytes were on their way: drop them (#4366).
            var abort = stale();
            if (abort) throw abort;
            // What Filament.js cannot draw as shipped is rewritten first (#4566): WebP
            // textures to PNG, skins over 256 joints split per mesh.
            return _transcodeWebP(new Uint8Array(buffer), stale).then(function(bytes) {
              var abort = stale();
              if (abort) throw abort;
              try {
                return _splitOversizedSkins(bytes);
              } catch (e) {
                console.warn('SceneView: oversized skin not split', e);
                return bytes;
              }
            });
          })
          .then(function(bytes) {
            var abort = stale();
            if (abort) throw abort;
            try {
              self._showModel(url, bytes, frontYaw);
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

    _showModel(url, data, frontYaw) {
      // Where the new model's front faces (radians from +Z): the studio opens on it.
      this._modelFrontYaw = frontYaw || 0;
      if (frontYaw != null && !this._studio) this._angle = 0.785 + frontYaw;
      // Destroy the previous model. Taking it off the scene alone kept its buffers,
      // textures and material instances alive for the life of the engine (#4366).
      if (this._asset) {
        var previous = this._asset;
        this._asset = null;
        this._animator = null;
        this._destroyAsset(previous);
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

      // The bytes come from loadModel(); a caller of this private method may still
      // have put them in the global Filament.assets map, as it had to before #4366.
      data = data || (Filament.assets && Filament.assets[url]);
      if (!data) throw new Error('Failed to fetch model: ' + url);

      // gltfio decodes textures asynchronously, one every 30 ms, after this call
      // returns: until onDone, a frame shows the geometry with no material
      // (a black silhouette). Redraw once they have all landed, so a viewer
      // parked off-screen does not keep that frame (#3690).
      // The running load keeps an 'onDemand' loop awake until the decode finishes.
      var asset;
      try {
        asset = this._createAsset(data, () => {
          if (this._asset === asset) this.requestRender();
        });
      } catch (e) {
        // gltfio throws a bare number (a C++ exception pointer), which read as "103971288".
        if (e instanceof Error) throw e;
        throw new Error('Failed to parse model: ' + url + _gltfioRejectionHint(data));
      }
      if (!asset) throw new Error('Failed to parse model: ' + url);

      this._scene.addEntity(asset.getRoot());
      this._scene.addEntities(asset.getRenderableEntities());
      this._asset = asset;
      this._quantizedBox = null;
      try {
        var realBox = this._fixQuantizedBounds(asset, data);
        if (realBox) this._quantizedBox = { asset: asset, box: realBox };
      } catch (e) {
        console.warn('SceneView: quantized model bounds not corrected', e);
      }

      // Cache animator for animation playback — reset any previous animation state.
      // In Filament.js gltfio, the Animator lives on the FilamentInstance,
      // not on the FilamentAsset itself.
      try {
        var inst = asset.getInstance ? asset.getInstance() : null;
        this._animator = (inst && inst.getAnimator) ? inst.getAnimator() : null;
      } catch (e) {
        this._animator = null;
      }
      // gltfio leaves every bone at identity until the animator runs, so a skinned model
      // shown still was drawn in its unskinned mesh pose: a head thrown back, a character
      // lying flat on its back (#4566). Pose the skin once from the rest
      // transforms, as Android's ModelNode does on every frame.
      if (this._animator && this._animator.updateBoneMatrices) {
        try { this._animator.updateBoneMatrices(); } catch (e) { /* no skin to pose */ }
      }
      this._animationIndex = -1;
      this._animationPauseTime = -1;

      this._animationDone = false;

      // Auto-frame the model (default fill, unchanged since #3690)
      this._frameAsset(asset);

      // The studio reads the triangles once, for the shadow bake; a weak reference so
      // the bytes are never kept alive for it.
      this._modelBytes = typeof WeakRef === 'function' ? new WeakRef(data) : null;
      if (this._studio) {
        try { this._studioRebuild(true); } catch (e) { console.warn('SceneView: studio staging failed', e); }
      }
      this.requestRender();
    }

    /**
     * Fit the orbit camera around an asset's bounding box: target its centre, set the
     * radius, and scale the near plane and zoom limits with the model.
     *
     * With `fill`, the radius is the one at which the cylinder the bounding box sweeps
     * over a full orbit (axis through the centre, radius = half the horizontal diagonal)
     * covers `fill` of the view, in height and in width, whichever binds first. The
     * swept volume, not the largest edge: the face nearest the camera is closer than the
     * centre, so sizing an edge at the centre plane cropped a tall, deep model from
     * fill ~0.7 up, and a long one on a portrait canvas.
     * Without `fill` the historical on-load framing is kept (1.8 x the largest
     * dimension, backed off on a portrait canvas so the width stays in frame too).
     *
     * @param {Object} asset - a gltfio FilamentAsset
     * @param {number} [fill] - largest fraction of the view the model may cover, (0, 1]
     * @returns {boolean} true when the asset had a non-empty bounding box
     * @private
     */
    _frameAsset(asset, fill) {
      try {
        const bbox = this._assetBox(asset);
        const cx = (bbox.min[0] + bbox.max[0]) / 2;
        const cy = (bbox.min[1] + bbox.max[1]) / 2;
        const cz = (bbox.min[2] + bbox.max[2]) / 2;
        const dx = bbox.max[0] - bbox.min[0];
        const dy = bbox.max[1] - bbox.min[1];
        const dz = bbox.max[2] - bbox.min[2];
        const maxDim = Math.max(dx, dy, dz);
        if (!(maxDim > 0)) return false;
        const canvas = this._canvas;
        const aspect = canvas && canvas.clientHeight > 0
          ? canvas.clientWidth / canvas.clientHeight : 1;
        let radius;
        if (fill > 0) {
          const t = Math.min(fill, 1) * Math.tan(((this._fov || 45) * Math.PI) / 360);
          const sweep = 0.5 * Math.hypot(dx, dz);
          // Height: the rim nearest the camera is `sweep` closer than the centre.
          const forHeight = sweep + dy / (2 * t);
          // Width: the outermost view rays are tangent to the cylinder, and the
          // horizontal half-angle is the vertical one scaled by the aspect ratio.
          const tw = t * Math.max(0.1, aspect);
          const forWidth = sweep * Math.sqrt(1 + 1 / (tw * tw));
          radius = Math.max(forHeight, forWidth);
        } else {
          radius = (maxDim * 1.8) / Math.max(0.5, Math.min(1, aspect));
        }
        this._orbitTarget = [cx, cy, cz];
        this._orbitRadius = radius;
        this._orbitHeight = cy;
        // Scale the near plane and zoom limits with the model, so a 5 cm part is
        // not clipped by a 10 cm near plane and a 100 m scene can still zoom out.
        this._nearPlane = Math.min(0.1, this._orbitRadius / 100);
        this._minRadius = Math.min(0.5, this._orbitRadius * 0.25);
        this._maxRadius = Math.max(50, this._orbitRadius * 5);
        this._applyProjection();
        return true;
      } catch (_e) {
        return false; // keep the current camera
      }
    }

    /**
     * The model's bounding box as { min, max }: gltfio's, or for a quantized model the
     * one _fixQuantizedBounds measured, gltfio's being 32767 times too big there.
     * @private
     */
    _assetBox(asset) {
      var q = this._quantizedBox;
      return q && q.asset === asset ? q.box : asset.getBoundingBox();
    }

    /**
     * Give the renderables of a KHR_mesh_quantization model their real boxes, and return
     * the model's box (null when it has no normalized POSITION). gltfio sizes a renderable
     * from its POSITION accessor's min/max as stored and ignores `normalized`: an int16
     * mesh came out 32767 times too big, so a 4 cm watch was framed from ~240 km away and
     * drew nothing, and its culling and shadow boxes were as far off (#4566). Each
     * renderable is matched to its mesh by the wrong box it was given; the model's box is
     * the union of the corrected ones through their world transforms.
     * @private
     */
    _fixQuantizedBounds(asset, data) {
      var parts = _glbParts(data), text = parts ? parts.text : '';
      if (text.indexOf('"normalized"') < 0) return null;
      var fixes = _quantizedMeshBoxes(JSON.parse(text));
      if (!fixes.length) return null;
      var rm = this._engine.getRenderableManager(), tm = this._engine.getTransformManager();
      var min = [Infinity, Infinity, Infinity], max = [-Infinity, -Infinity, -Infinity], fixed = 0;
      var matched = fixes.map(function() { return false; });
      function close(a, b) { return Math.abs(a - b) <= 1e-3 * Math.max(1, Math.abs(a), Math.abs(b)); }
      asset.getRenderableEntities().forEach(function(e) {
        var ri = rm.getInstance(e), box = rm.getAxisAlignedBoundingBox(ri);
        var c = [box.center[0], box.center[1], box.center[2]], h = [box.halfExtent[0], box.halfExtent[1], box.halfExtent[2]];
        // Meshes stored with the same raw box (to the tolerance) are told apart by nothing
        // gltfio keeps: this renderable takes the first one's real box and accounts for all
        // of them, or each one after the first would be counted as missed.
        var hit = null;
        for (var f = 0; f < fixes.length; f++) {
          var raw = fixes[f].raw, same = true;
          for (var k = 0; k < 3; k++) same = same && close(c[k] - h[k], raw.min[k]) && close(c[k] + h[k], raw.max[k]);
          if (!same) continue;
          matched[f] = true;
          if (!hit) hit = fixes[f].real;
        }
        if (hit) {
          for (k = 0; k < 3; k++) {
            c[k] = (hit.min[k] + hit.max[k]) / 2;
            h[k] = (hit.max[k] - hit.min[k]) / 2;
          }
          rm.setAxisAlignedBoundingBox(ri, { center: c, halfExtent: h });
          fixed++;
        }
        var M = tm.getWorldTransform(tm.getInstance(e));
        for (var corner = 0; corner < 8; corner++) {
          var x = c[0] + (corner & 1 ? h[0] : -h[0]), y = c[1] + (corner & 2 ? h[1] : -h[1]), z = c[2] + (corner & 4 ? h[2] : -h[2]);
          for (var a = 0; a < 3; a++) {
            var v = M[a] * x + M[4 + a] * y + M[8 + a] * z + M[12 + a];
            if (v < min[a]) min[a] = v;
            if (v > max[a]) max[a] = v;
          }
        }
      });
      // A quantized mesh no renderable was matched to keeps gltfio's raw box: its part of
      // the model can be culled, shadowed and framed as if it were 32767 times its size.
      var missed = matched.filter(function(m) { return !m; }).length;
      if (missed) {
        console.warn('SceneView: ' + missed + ' of ' + fixes.length + ' quantized meshes kept a wrong bounding box; '
          + 'framing and culling may be off for this model');
      }
      return fixed && min[0] <= max[0] ? { min: min, max: max } : null;
    }

    /**
     * Re-apply the vertical-FOV projection for the current canvas size, FOV and near
     * plane. Used by resize, framing and setCameraOrbit({ fov }).
     * @private
     */
    _applyProjection() {
      const canvas = this._canvas;
      if (!canvas || !(canvas.height > 0)) return;
      // The far plane only ever widens: 1000 up to a framed radius of ~133, then it
      // follows the zoom-out limit. A model authored in millimetres is framed
      // thousands of units away and was culled whole by a fixed 1000 (#3742).
      let far = Math.max(1000, (this._maxRadius || 50) * 1.5);
      const lay = this._studio && this._studioLayout;
      if (lay) {
        // The studio's wall stays whole from the farthest the eye can go, the move-in's
        // extra 12 % included: the far plane reaches past the dome's far side.
        const top = typeof this._maxHeight === 'number' ? this._maxHeight : lay.y + lay.domeRadius;
        const reach = 1.12 * Math.hypot(Math.max(this._maxRadius || 0, this._orbitRadius || 0),
          Math.max(top, this._orbitHeight || 0) - lay.y);
        far = Math.max(far, reach + 1.05 * lay.domeRadius);
      }
      this._camera.setProjectionFov(
        this._fov || 45, canvas.width / canvas.height, this._nearPlane || 0.1, far,
        Filament.Camera$Fov.VERTICAL
      );
      // The studio's reflection camera mirrors the main one: same lens.
      if (this._mirrorCamera) {
        this._mirrorCamera.setProjectionFov(
          this._fov || 45, canvas.width / canvas.height, this._nearPlane || 0.1, far,
          Filament.Camera$Fov.VERTICAL
        );
      }
    }

    setAutoRotate(enabled) {
      // A drag that just ended has a resume pending (3 s): drop it, or it would undo a pause.
      if (this._autoRotateTimer) { clearTimeout(this._autoRotateTimer); this._autoRotateTimer = null; }
      this._autoRotate = enabled; this._wantsAutoRotate = enabled; return this.requestRender();
    }
    setCameraDistance(d) { this._orbitRadius = d; return this.requestRender(); }

    /**
     * Read the orbit camera. The eye sits at
     * (target.x + sin(angle) * radius, height, target.z + cos(angle) * radius) and looks
     * at `target`; `height` is an absolute world Y.
     *
     * @returns {{angle: number, height: number, radius: number, target: number[], fov: number}}
     *   a copy: mutating it does not move the camera
     */
    getCameraOrbit() {
      return {
        angle: this._angle,
        height: this._orbitHeight,
        radius: this._orbitRadius,
        target: this._orbitTarget.slice(),
        fov: this._fov || 45,
      };
    }

    /**
     * Move the orbit camera. Every field is optional; omitted or invalid ones keep their
     * current value. Cancels drag inertia but leaves auto-rotate as it is (call
     * setAutoRotate(false) to hold a pose). Redraws even when the loop is parked.
     *
     * @param {Object} orbit
     * @param {number} [orbit.angle] - azimuth in radians around the target's Y axis
     * @param {number} [orbit.height] - eye height, absolute world Y
     * @param {number} [orbit.radius] - horizontal distance from the target, > 0
     * @param {number[]} [orbit.target] - [x, y, z] point the camera looks at
     * @param {number} [orbit.fov] - vertical field of view in degrees, (0, 180)
     * @returns {SceneViewInstance} this (for chaining)
     */
    setCameraOrbit(orbit) {
      const o = orbit || {};
      this._reveal = null;  // an explicit pose ends the studio's move-in
      if (Number.isFinite(o.angle)) this._angle = o.angle;
      if (Number.isFinite(o.height)) this._orbitHeight = o.height;
      if (Number.isFinite(o.radius) && o.radius > 0) this._orbitRadius = o.radius;
      if (Array.isArray(o.target) && o.target.length >= 3 && o.target.slice(0, 3).every(Number.isFinite)) {
        this._orbitTarget = [o.target[0], o.target[1], o.target[2]];
      }
      if (Number.isFinite(o.fov) && o.fov > 0 && o.fov < 180) {
        this._fov = o.fov;
        this._applyProjection();
      }
      this._velocityAngle = 0;
      this._velocityHeight = 0;
      return this.requestRender();
    }

    /**
     * Frame the loaded model: target its bounding-box centre and set the radius so the
     * model covers at most `fill` of the view, in height and in width, at every angle of
     * the orbit. The bounding box is what is measured, so a rounded model reads smaller
     * than `fill`. The camera height goes to the model centre; angle and auto-rotate are
     * untouched. Without `fill`, the on-load framing is reused. No-op before a model is
     * loaded.
     *
     * @param {Object} [options]
     * @param {number} [options.fill] - largest fraction of the view to cover, (0, 1]; e.g. 0.8
     * @returns {SceneViewInstance} this (for chaining)
     */
    frameModel(options) {
      const fill = options && Number.isFinite(options.fill) ? options.fill : undefined;
      this._reveal = null;
      if (this._asset) {
        // In the studio, the on-load framing is the studio's low three-quarter view.
        if (this._studio && this._studioLayout && fill === undefined) this._studioFrame(false);
        else {
          this._frameAsset(this._asset, fill);
          if (this._studio) this._studioClampLimits();
        }
      }
      this._velocityAngle = 0;
      this._velocityHeight = 0;
      return this.requestRender();
    }

    /**
     * Choose when frames are drawn.
     * - 'continuous' (default): every animation frame while the canvas is visible.
     * - 'onDemand': only while something moves (auto-rotate, drag, inertia, a playing
     *   animation, textures still decoding, a video quad), then the loop parks until
     *   requestRender(), an input event, a camera call or a scene change wakes it.
     * Both modes still stop off-screen and in hidden tabs.
     *
     * @param {'continuous'|'onDemand'} mode
     * @returns {SceneViewInstance} this (for chaining)
     */
    setRenderMode(mode) {
      if (mode !== 'continuous' && mode !== 'onDemand') {
        console.warn(`SceneView: unknown renderMode "${mode}", expected 'continuous' or 'onDemand'`);
        return this;
      }
      this._renderMode = mode;
      return this.requestRender();
    }

    /**
     * Ask for a redraw. In 'onDemand' mode this wakes the parked loop for at least one
     * frame; in 'continuous' mode it is a no-op while the loop runs. Off-screen, one
     * frame is still drawn so the canvas is current when it scrolls back in.
     * Coalesced: several calls in the same task draw once. Safe to call from an
     * onFrame() callback: the frame it asks for is the next one.
     *
     * @returns {SceneViewInstance} this (for chaining)
     */
    requestRender() {
      if (!this._running) return this;
      if (this._rafId !== null) {
        // The loop is live and draws the next frame anyway. A call made while a frame
        // is being drawn (from an onFrame callback) arrives after the camera was read:
        // flag it, so an 'onDemand' loop draws once more instead of parking on it.
        this._renderRequested = true;
        return this;
      }
      if (this._shouldRender()) this._startRenderLoop();
      else this._primeFrame(true);
      return this;
    }

    /**
     * Run `callback(canvas)` after every drawn frame, synchronously, while the WebGL
     * drawing buffer still holds the image: the place to copy the canvas elsewhere
     * (a reflection, a thumbnail) with drawImage. Errors are caught and logged.
     *
     * @param {function(HTMLCanvasElement): void} callback
     * @returns {function(): void} call it to unsubscribe
     */
    onFrame(callback) {
      if (typeof callback !== 'function') return () => {};
      this._frameCallbacks.add(callback);
      return () => { this._frameCallbacks.delete(callback); };
    }

    /**
     * True when an 'onDemand' loop has nothing left to animate.
     * @private
     */
    /** glTF resource loads (texture decodes) still running. @private */
    get _pendingLoads() {
      return this._resourceLoads.size;
    }

    _isIdle() {
      if (this._autoRotate || this._isDragging || this._reveal) return false;
      if (this._velocityAngle !== 0 || this._velocityHeight !== 0) return false;
      if (this._pendingLoads > 0) return false;
      // Video quads are not listed: each new video frame wakes the loop itself.
      if (this._animator && this._animationIndex >= 0 && this._animationPauseTime < 0
          && (this._animationLoop || !this._animationDone)) return false;
      return true;
    }

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
      return this.requestRender();
    }

    /**
     * Load a KTX IBL environment for PBR lighting. The last environment call wins; when
     * this one fails, the environment that was in place (or on its way) stays.
     *
     * @param {string} url - URL to a KTX IBL file
     * @param {number} [intensity=40000] - Light intensity
     * @returns {Promise<SceneViewInstance>} this (for chaining); rejects with an
     *   `AbortError` when the viewer is disposed before the environment arrives
     */
    loadEnvironment(url, intensity) {
      var self = this;
      // The last environment call wins: a default or studio environment still on its way
      // is dropped, and comes back if this one fails.
      var serial = ++this._envSerial;
      var before = this._envSource, dropped = this._envLoading;
      this._envSource = { url: url, intensity: intensity || 40000 };
      this._envLoading = null;
      function fallBack() {
        if (self._disposed || serial !== self._envSerial) return;
        self._envSource = before;
        if (before && before.studio && !self._studio) {
          // The studio closed while this one was on its way: the default, as outside it.
          self._loadDefaultEnvironment(self._defaultIblUrl || '/environments/neutral_ibl.ktx',
            self._defaultIblIntensity || 40000);
        } else if (dropped) {
          self._loadDefaultEnvironment(dropped.url, dropped.intensity, dropped.studio);
        }
      }
      return this._fetch(url)
        .then(function(r) {
          if (!r.ok) throw new Error('HTTP ' + r.status);
          return r.arrayBuffer().then(function(ab) { return new Uint8Array(ab); });
        })
        .then(function(buffer) {
          if (self._disposed) throw _disposedError();
          if (serial !== self._envSerial) return self;  // a later environment call won
          try {
            self._setKtxEnvironment(buffer, intensity || 40000);
            _log('SceneView: Environment loaded (' + Math.round(buffer.length / 1024) + 'KB)');
          } catch (e) {
            console.warn('SceneView: loadEnvironment failed', e);
            fallBack();
          }
          return self.requestRender();
        }, function(err) {
          fallBack();
          throw err;
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
      // A default or studio environment still on its way is dropped, and comes back if
      // these bands fail.
      var dropped = this._envLoading;
      this._envSerial++;
      this._envLoading = null;
      try {
        if (this._disposed) throw _disposedError();
        this._setShEnvironment(bands, intensity || 45000);
        this._envSource = { bands: Array.prototype.slice.call(bands), intensity: intensity || 45000 };
      } catch (e) {
        console.warn('SceneView: setEnvironmentSH failed', e);
        if (dropped && !this._disposed) {
          this._loadDefaultEnvironment(dropped.url, dropped.intensity, dropped.studio);
        }
      }
      return this.requestRender();
    }

    /**
     * Set the background colour, as displayed: sRGB components 0-1 (a hex byte / 255), so
     * `setBackgroundColor(0xEE / 255, 0xF0 / 255, 0xF3 / 255)` shows `#EEF0F3`.
     */
    setBackgroundColor(r, g, b, a) {
      this._pageBackground = [r, g, b, a !== undefined ? a : 1];
      // In the studio the clear colour is its horizon: this one waits for setStudio(false).
      if (this._studio) return this.requestRender();
      this._renderer.setClearOptions({
        clearColor: _clearColorFor(this._pageBackground, this._transparent),
        clear: true
      });
      return this.requestRender();
    }

    // ---------------------------------------------------------------
    // Studio showroom
    // ---------------------------------------------------------------

    /**
     * Stage the model in a lit photo studio: a seamless wall that curves into the floor
     * with no visible horizon, a contact shadow and ambient occlusion baked from the
     * model's own triangles, a faint floor reflection, and a studio environment of
     * softbox highlights with no sun (a sun's sharp glints read as stray reflections on
     * car paint). The camera moves to a low three-quarter view through a 30-degree lens
     * and eases in once the model is dressed, unless the visitor prefers reduced motion;
     * auto-rotate becomes a slow turntable, 15 degrees a second. The effects step down on
     * their own when the GPU falls behind the browser's frame rate (a 30 Hz battery saver
     * is not mistaken for it). Scales from a watch to a car. Pass it to create() as
     * `studio` to have it from the first frame; setBackgroundColor() takes effect again
     * once it is off, and so does the environment that was in place before it.
     *
     * @example viewer.setStudio('dark');
     * @example viewer.setStudio({ theme: 'light', reflection: 0.2 });
     * @example viewer.setStudio(false);   // the plain background again
     *
     * @param {string|boolean|Object} [options] - 'light' (the default, also `true`),
     *   'dark', `false` or `null` to leave the studio, or
     *   `{ theme, reflection, shadow, environment, top, horizon, floor, pool }`:
     *   `reflection` 0-0.6, `shadow` 0-1, `environment` a KTX1 IBL URL, and the four
     *   colours '#RRGGBB' or [r, g, b] in 0-1, as displayed
     * @returns {SceneViewInstance} this (for chaining)
     */
    setStudio(options) {
      if (this._disposed) return this;
      var conf = _studioConfig(options);
      if (!conf) {
        if (this._studio) this._studioOff();
        return this.requestRender();
      }
      var entering = !this._studio;
      var envChanged = entering || this._studio.environment !== conf.environment;
      if (entering) this._studioEnter();
      this._studio = conf;
      if (envChanged) this._loadDefaultEnvironment(conf.environment, _STUDIO_IBL_INTENSITY, true);
      try {
        this._studioRebuild(entering);
      } catch (e) {
        console.warn('SceneView: studio staging failed', e);
      }
      return this.requestRender();
    }

    /** Keep what the studio changes, and take the sun and fill lights off. @private */
    _studioEnter() {
      this._studioSaved = {
        fov: this._fov, nearPlane: this._nearPlane, minRadius: this._minRadius,
        maxRadius: this._maxRadius, minHeight: this._minHeight, maxHeight: this._maxHeight,
        env: this._envSource
      };
      this._studioTier = _isLikelyMobile() ? 2 : 3;
      this._studioPerf = { gaps: [], skips: 0, last: 0, warm: 0, dropping: false };
      // The studio environment does all the lighting: a sun's sharp highlight reads as a
      // stray reflection on glossy paint.
      var scene = this._scene;
      this._baseLights.forEach(function(e) {
        try { scene.remove(e); } catch (_e) { /* already gone */ }
      });
    }

    /** Back to the plain background, lights, environment and framing. @private */
    _studioOff() {
      this._studio = null;
      this._reveal = null;
      this._studioDestroyParts();
      this._studioSetMirror(false);
      this._studioLayout = null;
      this._studioBudget = null;
      this._studioPerf = null;
      this._dragHeightScale = 1;
      var scene = this._scene;
      this._baseLights.forEach(function(e) {
        try { scene.addEntity(e); } catch (_e) { /* destroyed by clearLights() */ }
      });
      this._studioRestorePost();
      var saved = this._studioSaved || {};
      this._studioSaved = null;
      this._fov = saved.fov || 45;
      this._nearPlane = saved.nearPlane;
      this._minRadius = saved.minRadius;
      this._maxRadius = saved.maxRadius;
      this._minHeight = saved.minHeight;
      this._maxHeight = saved.maxHeight;
      // The environment from before the studio, unless one was set while it was on.
      var env = this._envSource;
      if (!env || env.studio) {
        // The studio's environment, in place or on its way, never comes back once it is off.
        this._envLoading = null;
        env = saved.env;
        if (env && env.bands) this.setEnvironmentSH(env.bands, env.intensity);
        else if (env && env.url && !env.studio) this._loadDefaultEnvironment(env.url, env.intensity);
        else this._loadDefaultEnvironment(this._defaultIblUrl || '/environments/neutral_ibl.ktx',
          this._defaultIblIntensity || 40000);
      }
      var bg = this._pageBackground || (this._transparent ? [0, 0, 0, 0] : _DEFAULT_BACKGROUND);
      this._renderer.setClearOptions({ clearColor: _clearColorFor(bg, this._transparent), clear: true });
      if (!this._asset || !this._frameAsset(this._asset)) this._applyProjection();
      this._resizeCanvas();
    }

    /**
     * Rebuild the studio around the current model: its layout (once per model), the
     * quality tier's effects, the wall, the floor with the model's shadow, and with
     * `reframe`, the low three-quarter camera.
     * @private
     */
    _studioRebuild(reframe) {
      var conf = this._studio, asset = this._asset;
      if (!this._transparent) {
        this._renderer.setClearOptions({
          clearColor: _clearColorFor([conf.horizon[0], conf.horizon[1], conf.horizon[2], 1], false),
          clear: true
        });
      }
      if (asset && (!this._studioLayout || this._studioLayout.asset !== asset)) {
        var c = this._canvas, aspect = c && c.clientHeight > 0 ? c.clientWidth / c.clientHeight : 1;
        this._studioLayout = _studioLayoutFor(this._studioBounds(asset), aspect);
        if (this._studioLayout) this._studioLayout.asset = asset;
        reframe = true;
      }
      if (!asset) this._studioLayout = null;
      this._studioApplyTier();
      if (!this._studioLayout) {
        this._studioDestroyParts();
        return;
      }
      this._studioBuildBackdrop();
      if (reframe) this._studioFrame(true);
    }

    /**
     * The model's bounds for the studio: from its vertices when they can be read (once
     * per model) and lie inside gltfio's box, so the floor touches the model; gltfio's
     * box otherwise.
     * @private
     */
    _studioBounds(asset) {
      var bb = this._assetBox(asset), sb = this._studioBox;
      if (!sb || sb.asset !== asset) {
        var bytes = this._modelBytes && this._modelBytes.deref ? this._modelBytes.deref() : null, box = null;
        try {
          box = bytes ? _studioTightBounds(bytes) : null;
        } catch (e) {
          console.warn('SceneView: model bounds not read, using its bounding box', e);
        }
        var tol = 1e-3 * Math.max(bb.max[0] - bb.min[0], bb.max[1] - bb.min[1], bb.max[2] - bb.min[2]);
        for (var k = 0; box && k < 3; k++) {
          if (!(box.min[k] >= bb.min[k] - tol && box.max[k] <= bb.max[k] + tol)) box = null;
        }
        sb = this._studioBox = { asset: asset, box: box };
      }
      return sb.box || { min: [bb.min[0], bb.min[1], bb.min[2]], max: [bb.max[0], bb.max[1], bb.max[2]] };
    }

    /** The studio's camera: low three-quarter view, 30-degree lens, limits inside the room. @private */
    _studioFrame(withReveal) {
      var lay = this._studioLayout;
      if (!lay) return;
      this._fov = _STUDIO_FOV;
      this._orbitTarget = lay.target.slice();
      this._orbitRadius = lay.radius;
      this._orbitHeight = lay.height;
      this._nearPlane = Math.min(0.1, lay.distance / 100);
      // Never inside the model's footprint.
      this._minRadius = Math.max(0.05 * lay.L, 1.05 * Math.hypot(lay.hx, lay.hz));
      // A vertical drag moves the eye in proportion to its distance: as fast on a watch as on a car.
      this._dragHeightScale = Math.min(10, Math.max(0.05, lay.distance / 3.5));
      this._velocityAngle = 0;
      this._velocityHeight = 0;
      this._reveal = withReveal && !_prefersReducedMotion() ? { start: null } : null;
      // A new framing opens on the model's front three-quarter, whatever the turntable
      // did while the file was downloading.
      if (withReveal) this._angle = 0.785 + (this._modelFrontYaw || 0);
      this._studioFramedAt = performance.now();
      this._studioClampLimits();
    }

    /**
     * True while the framed model's textures are still decoding, for 4 s at most: a
     * texture that never arrives must not hold the move-in or the quality tiers forever.
     * @private
     */
    _studioDressing() {
      return this._resourceLoads.size > 0
        && performance.now() - this._studioFramedAt < _STUDIO_DRESS_WAIT_MS;
    }

    /** Keep the eye above the floor and inside the wall. @private */
    _studioClampLimits() {
      var lay = this._studioLayout;
      if (!lay) return;
      this._maxRadius = Math.min(Math.max(50, this._orbitRadius * 5), 0.55 * lay.domeRadius);
      this._orbitRadius = Math.min(this._orbitRadius, this._maxRadius);
      this._minHeight = lay.y + 0.02 * lay.L;
      this._maxHeight = lay.y + 0.5 * lay.domeRadius;
      this._applyProjection();
    }

    /**
     * Quality tiers, 3 down to 0. Tier 3 is MSAA, ambient occlusion, bloom (dark theme),
     * the reflection at half resolution and a 2-megapixel buffer; each tier below drops
     * the costliest of them and shrinks the buffer. A phone starts at 2.
     * @private
     */
    _studioApplyTier() {
      var tier = this._studioTier;
      this._studioBudget = _STUDIO_BUDGETS[tier];
      this._studioSetMirror(tier >= 2 && this._studio.reflection > 0 && !this._transparent &&
        !!this._studioLayout, tier >= 3 ? 0.5 : 0.4);
      this._studioApplyPost();
      this._resizeCanvas();
    }

    /** @private */
    _studioApplyPost() {
      var view = this._view, tier = this._studioTier, conf = this._studio, lay = this._studioLayout;
      var L = lay ? lay.L : 1;
      try {
        view.setAmbientOcclusionOptions(tier >= 2 ? {
          enabled: true, intensity: 1, quality: tier >= 3 ? 1 : 0,
          radius: Math.min(0.5, Math.max(0.005, 0.08 * L)),
          bias: Math.min(5e-4, Math.max(1e-5, 2e-4 * L))
        } : { enabled: false });
      } catch (_e) { /* older Filament */ }
      try { view.setAntiAliasing(Filament.View$AntiAliasing.FXAA); } catch (_e) { /* older Filament */ }
      try { view.setMultiSampleAntiAliasingOptions({ enabled: tier >= 3, sampleCount: 4 }); } catch (_e) { /* older Filament */ }
      try {
        view.setBloomOptions(conf.bloom > 0 && tier >= 3
          ? { enabled: true, strength: conf.bloom, resolution: 360, threshold: true, levels: 6 }
          : { enabled: false });
      } catch (_e) { /* older Filament */ }
      try {
        // Filament multiplies the corners by `color` before tone mapping: a grey takes
        // them down a step, where black would crush them.
        var v = conf.vignette;
        view.setVignetteOptions(tier >= 1 ? {
          enabled: true, midPoint: conf.theme === 'dark' ? 0.6 : 0.75,
          roundness: 0.6, feather: 0.8, color: [v, v, v, 1]
        } : { enabled: false });
      } catch (_e) { /* older Filament */ }
      try {
        view.setBlendMode(this._mirrorView || this._transparent
          ? Filament.View$BlendMode.TRANSLUCENT : Filament.View$BlendMode.OPAQUE);
      } catch (_e) { /* older Filament */ }
    }

    /** The post-processing _createEngine() sets up. @private */
    _studioRestorePost() {
      var view = this._view;
      try { view.setAmbientOcclusionOptions({ enabled: true, radius: 0.3, bias: 0.0005, intensity: 1.0, quality: 1 }); } catch (_e) { /* */ }
      try { view.setBloomOptions({ enabled: false }); } catch (_e) { /* */ }
      try { view.setVignetteOptions({ enabled: false }); } catch (_e) { /* */ }
      try { view.setMultiSampleAntiAliasingOptions({ enabled: false }); } catch (_e) { /* */ }
      try { view.setAntiAliasing(Filament.View$AntiAliasing.FXAA); } catch (_e) { /* */ }
      try {
        view.setBlendMode(this._transparent ? Filament.View$BlendMode.TRANSLUCENT : Filament.View$BlendMode.OPAQUE);
      } catch (_e) { /* */ }
    }

    /**
     * The floor reflection: a second view of the model alone (layer 2), seen by the main
     * camera mirrored in the floor plane, drawn first at reduced resolution, which also
     * softens it. The main view is blended over it, and the floor's alpha decides how
     * much of it shows.
     * @private
     */
    _studioSetMirror(on, scale) {
      if (!on) {
        if (this._mirrorView) {
          this._destroyMirrorView();
          this._studioMarkModelLayers(0x1);
        }
        return;
      }
      if (!this._mirrorView) {
        var engine = this._engine, ent = Filament.EntityManager.get().create();
        var cam = engine.createCamera(ent), mv = engine.createView();
        mv.setScene(this._scene);
        mv.setCamera(cam);
        if (this._colorGrading) mv.setColorGrading(this._colorGrading);
        try { mv.setFrontFaceWindingInverted(true); } catch (_e) { /* */ }
        try { mv.setVisibleLayers(0xff, 0x2); } catch (_e) { /* */ }
        try { mv.setAmbientOcclusionOptions({ enabled: false }); } catch (_e) { /* */ }
        try { mv.setBloomOptions({ enabled: false }); } catch (_e) { /* */ }
        try { mv.setVignetteOptions({ enabled: false }); } catch (_e) { /* */ }
        // Smooths the low-resolution edges before they are scaled up.
        try { mv.setAntiAliasing(Filament.View$AntiAliasing.FXAA); } catch (_e) { /* */ }
        try { mv.setDithering(Filament.View$Dithering.NONE); } catch (_e) { /* */ }
        try { mv.setShadowingEnabled(false); } catch (_e) { /* */ }
        this._mirrorView = mv;
        this._mirrorCamera = cam;
        this._mirrorCameraEntity = ent;
      }
      var s = scale || 0.5, view = this._mirrorView;
      try {
        view.setDynamicResolutionOptions(view.setDynamicResolutionOptionsDefaults({
          enabled: true, minScale: [s, s], maxScale: [s, s], homogeneousScaling: true
        }));
      } catch (_e) { /* full resolution then */ }
      view.setViewport([0, 0, this._canvas.width, this._canvas.height]);
      // Every time: a new model needs its layers too.
      this._studioMarkModelLayers(0x3);
      this._applyProjection();
    }

    /** Put the model on the main view's layer (1) and/or the reflection's (2). @private */
    _studioMarkModelLayers(layers) {
      if (!this._asset) return;
      var rm = this._engine.getRenderableManager();
      this._asset.getRenderableEntities().forEach(function(e) {
        try { rm.setLayerMask(rm.getInstance(e), 0xff, layers); } catch (_e) { /* older Filament */ }
      });
    }

    /** @private */
    _destroyMirrorView() {
      var mv = this._mirrorView, ent = this._mirrorCameraEntity, engine = this._engine;
      this._mirrorView = null;
      this._mirrorCamera = null;
      this._mirrorCameraEntity = null;
      if (!mv || !engine) return;
      try { mv.setColorGrading(null); } catch (_e) { /* */ }
      try { engine.destroyView(mv); } catch (_e) { /* */ }
      try {
        engine.destroyCameraComponent(ent);
        Filament.EntityManager.get().destroy(ent);
      } catch (_e) { /* */ }
    }

    /**
     * Add one studio surface as a gltfio asset, drawn in `channel` (0 the wall, 1 the
     * floor, both before the model in the default channel 2), casting and receiving no
     * shadow. Replaces the part of the same name.
     * @private
     */
    _studioAddPart(name, glb, channel, priority, onLoaded) {
      var old = this._studioParts[name];
      if (old) {
        this._studioParts[name] = null;
        this._destroyAsset(old);
      }
      var asset = this._createAsset(glb, onLoaded);
      if (!asset) return null;
      var rm = this._engine.getRenderableManager(), scene = this._scene;
      scene.addEntity(asset.getRoot());
      asset.getRenderableEntities().forEach(function(e) {
        try {
          var ri = rm.getInstance(e);
          rm.setChannel(ri, channel);
          if (priority !== undefined) rm.setPriority(ri, priority);
          rm.setCastShadows(ri, false);
          rm.setReceiveShadows(ri, false);
        } catch (_e) { /* older Filament: default order */ }
        scene.addEntity(e);
      });
      this._studioParts[name] = asset;
      return asset;
    }

    /** @private */
    _studioBuildBackdrop() {
      this._studioAddPart('dome', _studioDomeGLB(this._studioLayout, this._studio), 0);
      this._studioBuildFloor();
    }

    /**
     * The model's shadow field, baked once per model from its triangles (a soft ellipse
     * when they cannot be read).
     * @private
     */
    _studioShadowField() {
      var asset = this._asset, sh = this._studioShadow, lay = this._studioLayout;
      // Past the reflection's reach, so the texture can carry it.
      var margin = 1.05 * lay.fade + 0.02 * lay.L;
      if (sh && sh.asset === asset && sh.margin >= margin) return sh;
      var bytes = this._modelBytes && this._modelBytes.deref ? this._modelBytes.deref() : null;
      var bbox = this._studioBounds(asset), t0 = performance.now(), baked = null;
      try {
        baked = bytes ? _studioBakeShadow(bytes, bbox, margin) : null;
      } catch (e) {
        console.warn('SceneView: contact shadow bake failed, using a soft ellipse', e);
      }
      sh = baked || _studioEllipseShadow(bbox, margin);
      sh.asset = asset;
      sh.baked = !!baked;
      sh.bakeMs = Math.round(performance.now() - t0);
      this._studioShadow = sh;
      return sh;
    }

    /**
     * The floor, with the model's shadow as its base colour texture: the texture follows
     * the floor's colours and reflection, so both are rebuilt together.
     * @private
     */
    _studioBuildFloor() {
      var lay = this._studioLayout, conf = this._studio, engine = this._engine, mirror = !!this._mirrorView;
      var sh = this._studioShadowField();
      var old = this._studioParts.floor;
      if (old) {
        this._studioParts.floor = null;
        this._destroyAsset(old);
      }
      var tx = this._studioTexture;
      if (tx && (tx.w !== sh.w || tx.h !== sh.h)) {
        this._studioDestroyTexture();
        tx = null;
      }
      if (!tx) {
        var tex = Filament.Texture.Builder()
          .width(sh.w).height(sh.h).levels(1)
          .sampler(Filament.Texture$Sampler.SAMPLER_2D)
          .format(Filament.Texture$InternalFormat.SRGB8_A8)
          .build(engine);
        tx = this._studioTexture = { tex: tex, w: sh.w, h: sh.h };
      }
      tx.tex.setImage(engine, 0, Filament.PixelBuffer(_studioShadowTexels(lay, conf, mirror, sh),
        Filament.PixelDataFormat.RGBA, Filament.PixelDataType.UBYTE));
      var self = this, texture = tx, floor = null;
      // Bound now and again once gltfio has decoded the floor's placeholder PNG, which it
      // binds to the same parameter when it is done.
      var bind = function() {
        if (self._disposed || !floor || self._studioParts.floor !== floor || self._studioTexture !== texture) return;
        try {
          var rm = self._engine.getRenderableManager();
          var mi = rm.getMaterialInstanceAt(rm.getInstance(floor.getRenderableEntities()[0]), 0);
          mi.setTextureParameter('baseColorMap', texture.tex, new Filament.TextureSampler(
            Filament.MinFilter.LINEAR, Filament.MagFilter.LINEAR, Filament.WrapMode.CLAMP_TO_EDGE));
        } catch (e) {
          console.warn('SceneView: floor shadow texture not bound', e);
        }
        self.requestRender();
      };
      floor = this._studioAddPart('floor', _studioFloorGLB(lay, conf, mirror, sh), 1, 3, bind);
      bind();
    }

    /** Only once no floor uses it. @private */
    _studioDestroyTexture() {
      if (this._studioTexture) {
        try { this._engine.destroyTexture(this._studioTexture.tex); } catch (_e) { /* engine gone */ }
        this._studioTexture = null;
      }
    }

    /** @private */
    _studioDestroyParts() {
      var parts = this._studioParts, self = this;
      ['dome', 'floor'].forEach(function(k) {
        var a = parts[k];
        if (a) {
          parts[k] = null;
          self._destroyAsset(a);
        }
      });
      this._studioDestroyTexture();
    }

    /** dispose(): the parts went with every other asset; the texture and the view remain. @private */
    _destroyStudioNative() {
      this._studioParts = {};
      this._studioDestroyTexture();
      this._destroyMirrorView();
      this._studio = null;
      this._studioLayout = null;
      this._studioShadow = null;
      this._studioBox = null;
      this._reveal = null;
    }

    /**
     * Count one tick of the render loop, drawn or skipped by beginFrame() while the GPU
     * is behind, against the cadence the browser actually delivers: a 120 Hz screen, or
     * a battery saver that caps animation frames at 30 Hz, keeps its own pace and is not
     * read as a slow GPU. A tick is late when it was skipped, or when it took longer than
     * one and a half of that cadence (1/48 s at the least). Once the move-in and the
     * loads are over, a window of 60 ticks drops one quality tier when more than a
     * quarter of them came late, or when even the steady cadence is under 28 per second.
     * Never back up: a tier that recovers would flicker between the two.
     * @private
     */
    _studioTrackFrame(now, drawn) {
      var p = this._studioPerf;
      if (!p) return;
      var gap = p.last ? now - p.last : 0;
      p.last = now;
      if (this._reveal || this._studioTier <= 0 || this._studioDressing() || !(gap > 0) || gap > 250) {
        p.gaps.length = 0;
        p.skips = 0;
        p.warm = 0;
        return;
      }
      if (p.warm < 45) { p.warm++; return; }
      p.gaps.push(gap);
      if (!drawn) p.skips++;
      if (p.gaps.length < 60) return;
      // The cadence: the 10th percentile of the window, what a tick takes when on time.
      var cadence = p.gaps.slice().sort(function(a, b) { return a - b; })[5];
      var lateAfter = Math.max(1.5 * cadence, 1000 / 48);
      var late = p.skips;
      for (var i = 0; i < p.gaps.length; i++) {
        if (p.gaps[i] > lateAfter) late++;
      }
      p.gaps.length = 0;
      p.skips = 0;
      if ((late > 15 || cadence > 1000 / 28) && !p.dropping) {
        p.warm = 0;
        p.dropping = true;
        var self = this;
        // Outside the loop's tick: the drop resizes the canvas, which can draw synchronously.
        setTimeout(function() {
          p.dropping = false;
          if (!self._disposed && self._studio && self._studioPerf === p) self._studioDropTier();
        }, 0);
      }
    }

    /** @private */
    _studioDropTier() {
      var had = !!this._mirrorView;
      this._studioTier = Math.max(0, this._studioTier - 1);
      this._studioApplyTier();
      // Without the reflection the floor is opaque again, and its shadow texture changes.
      if (had !== !!this._mirrorView && this._studioLayout) this._studioBuildFloor();
      this.requestRender();
    }

    /** Fold the move-in into the camera, so a drag starts from what is on screen. @private */
    _finishReveal() {
      var rv = this._reveal;
      if (!rv) return;
      var k = rv.start === null ? 0 : Math.min(1, (performance.now() - rv.start) / _STUDIO_REVEAL_MS);
      var back = Math.pow(1 - k, 3), t1 = this._orbitTarget[1];
      this._orbitRadius *= 1 + 0.12 * back;
      this._orbitHeight = t1 + (this._orbitHeight - t1) * (1 + 0.12 * back);
      this._angle -= 0.436 * back;
      this._reveal = null;
    }

    // ---------------------------------------------------------------
    // Quality, Bloom, and Lighting controls
    // ---------------------------------------------------------------

    /**
     * Set rendering quality level: a preset, or the two switches it sets.
     *
     * 'low' is no ambient occlusion and no anti-aliasing, 'medium' and 'high' enable both
     * at two sizes. The object form picks them apart — `{ ambientOcclusion: false,
     * antiAliasing: true }` keeps FXAA on a night scene whose SSAO nobody can see — and
     * takes 'low' / 'high' for `ambientOcclusion` to choose its size.
     *
     * @param {string|Object} level - 'low', 'medium', or 'high' (default: 'medium'), or
     *   `{ ambientOcclusion: boolean|'low'|'high', antiAliasing: boolean }`
     * @returns {SceneViewInstance} this (for chaining)
     */
    setQuality(level) {
      try {
        if (level && typeof level === 'object') {
          var ao = level.ambientOcclusion;
          if (ao === false) this._view.setAmbientOcclusionOptions({ enabled: false });
          else if (ao === 'high') this._view.setAmbientOcclusionOptions({ enabled: true, radius: 0.4, bias: 0.0003, intensity: 1.2, quality: 2 });
          else if (ao) this._view.setAmbientOcclusionOptions({ enabled: true, radius: 0.3, bias: 0.0005, intensity: 1.0, quality: 1 });
          if (level.antiAliasing !== undefined) {
            this._view.setAntiAliasing(level.antiAliasing ? Filament.View$AntiAliasing.FXAA : Filament.View$AntiAliasing.NONE);
          }
        } else if (level === 'low') {
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
      return this.requestRender();
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
      return this.requestRender();
    }

    /**
     * Screen-space reflections: glossy surfaces (a wet road, a polished floor, car paint)
     * mirror what is on screen, on top of the environment reflections. Costs roughly one
     * extra pass; turn it off on slow GPUs.
     *
     * @param {Object|boolean} options - true for defaults, false to disable, or
     *   { thickness, bias, maxDistance, stride }: `thickness` (default 0.1) is how thick
     *   on-screen objects are assumed to be, `bias` (0.01) avoids self-reflection,
     *   `maxDistance` (3, world units) is how far a reflected ray travels, `stride` (2)
     *   trades precision for speed.
     * @returns {SceneViewInstance} this (for chaining)
     */
    setScreenSpaceReflections(options) {
      try {
        if (options === false) {
          this._view.setScreenSpaceReflectionsOptions({ enabled: false });
        } else {
          var o = (options === true || options === undefined) ? {} : options;
          this._view.setScreenSpaceReflectionsOptions({
            enabled: true,
            thickness: o.thickness !== undefined ? o.thickness : 0.1,
            bias: o.bias !== undefined ? o.bias : 0.01,
            maxDistance: o.maxDistance !== undefined ? o.maxDistance : 3,
            stride: o.stride !== undefined ? o.stride : 2
          });
        }
      } catch (e) { console.warn('SceneView: setScreenSpaceReflections not supported', e); }
      return this.requestRender();
    }

    /**
     * Distance fog (atmospheric haze). Applies to opaque geometry up to `cutOffDistance`,
     * so a sky dome placed farther than that keeps its own colours.
     *
     * @param {Object|boolean} options - true for defaults, false to disable, or
     *   { color, density, distance, height, heightFalloff, maximumOpacity, cutOffDistance,
     *     inScatteringStart, inScatteringSize }: `color` is linear RGB [0-1], `distance`
     *   (world units) is where the fog starts, `density` (default 0.1) its thickness,
     *   `heightFalloff` (1) how fast it thins with altitude above `height` (0).
     * @returns {SceneViewInstance} this (for chaining)
     */
    setFog(options) {
      try {
        if (options === false) {
          this._view.setFogOptions({ enabled: false });
        } else {
          var o = (options === true || options === undefined) ? {} : options;
          var fog = { enabled: true };
          ['color', 'density', 'distance', 'height', 'heightFalloff', 'maximumOpacity',
            'cutOffDistance', 'inScatteringStart', 'inScatteringSize'].forEach(function(k) {
            if (o[k] !== undefined) fog[k] = o[k];
          });
          this._view.setFogOptions(fog);
        }
      } catch (e) { console.warn('SceneView: setFog not supported', e); }
      return this.requestRender();
    }

    /**
     * Vignette: darken the corners of the picture, as a camera lens does.
     *
     * @param {Object|boolean} options - true for defaults, false to disable, or
     *   { midPoint, roundness, feather, color }: `midPoint` (default 0.5) is where the
     *   darkening starts, `feather` (0.5) how soft its edge is, `color` linear RGBA.
     * @returns {SceneViewInstance} this (for chaining)
     */
    setVignette(options) {
      try {
        if (options === false) {
          this._view.setVignetteOptions({ enabled: false });
        } else {
          var o = (options === true || options === undefined) ? {} : options;
          this._view.setVignetteOptions({
            enabled: true,
            midPoint: o.midPoint !== undefined ? o.midPoint : 0.5,
            roundness: o.roundness !== undefined ? o.roundness : 0.5,
            feather: o.feather !== undefined ? o.feather : 0.5,
            color: o.color || [0, 0, 0, 1]
          });
        }
      } catch (e) { console.warn('SceneView: setVignette not supported', e); }
      return this.requestRender();
    }

    /**
     * Cap the canvas resolution. The drawing buffer is `clientSize * min(devicePixelRatio,
     * ratio)`; the default cap is 2. Lower it (1.5, 1.25, 1) when a full-screen scene
     * with heavy effects drops frames: the picture gets softer, the frame rate goes up.
     *
     * @param {number} ratio - maximum device-pixel ratio, clamped to [0.5, 3]
     * @returns {SceneViewInstance} this (for chaining)
     */
    setPixelRatio(ratio) {
      if (!Number.isFinite(ratio)) return this;
      this._maxPixelRatio = Math.max(0.5, Math.min(3, ratio));
      this._resizeCanvas();
      return this.requestRender();
    }

    /**
     * Limit how far the visitor can move the orbit camera, so a drag cannot take it
     * under the floor of a scene or a wheel turn through a wall. Every field is optional;
     * `null` removes a limit. Heights are absolute world Y, like setCameraOrbit.
     *
     * @param {Object} limits
     * @param {number|null} [limits.minHeight] - lowest eye height
     * @param {number|null} [limits.maxHeight] - highest eye height
     * @param {number} [limits.minRadius] - closest zoom
     * @param {number} [limits.maxRadius] - farthest zoom
     * @returns {SceneViewInstance} this (for chaining)
     */
    setOrbitLimits(limits) {
      var l = limits || {};
      if (l.minHeight === null || Number.isFinite(l.minHeight)) this._minHeight = l.minHeight;
      if (l.maxHeight === null || Number.isFinite(l.maxHeight)) this._maxHeight = l.maxHeight;
      if (Number.isFinite(l.minRadius) && l.minRadius > 0) this._minRadius = l.minRadius;
      if (Number.isFinite(l.maxRadius) && l.maxRadius > 0) { this._maxRadius = l.maxRadius; this._applyProjection(); }
      return this.requestRender();
    }

    /** Keep the orbit inside setOrbitLimits(). @private */
    _clampOrbit() {
      if (typeof this._minHeight === 'number' && this._orbitHeight < this._minHeight) {
        this._orbitHeight = this._minHeight; this._velocityHeight = 0;
      }
      if (typeof this._maxHeight === 'number' && this._orbitHeight > this._maxHeight) {
        this._orbitHeight = this._maxHeight; this._velocityHeight = 0;
      }
    }

    /**
     * Cap the drawing buffer at a number of pixels, whatever the canvas size and the
     * display density. A full-bleed canvas on a 2x display is 4 to 6 megapixels; with
     * shadows, ambient occlusion and bloom every one of them costs, and when the GPU
     * falls behind Filament skips frames in steps (60 → 40 → 30 per second). Under a
     * budget the device-pixel ratio scales down (never up, never under 0.5) so the
     * buffer stays within it: the picture gets a little softer, the frame rate holds.
     * Combine with setPixelRatio(): the lower of the two wins. `null` removes the cap.
     *
     * @param {number|null} pixels - largest drawing buffer, in pixels (e.g. 1.2e6)
     * @returns {SceneViewInstance} this (for chaining)
     */
    setPixelBudget(pixels) {
      this._pixelBudget = (Number.isFinite(pixels) && pixels > 0) ? pixels : null;
      this._resizeCanvas();
      return this.requestRender();
    }

    /** Size the drawing buffer from the CSS size and the pixel-ratio cap. @private */
    _resizeCanvas() {
      var canvas = this._canvas;
      var dpr = Math.min(devicePixelRatio, this._maxPixelRatio || 2);
      // The studio's quality tier caps the buffer too (see _studioApplyTier).
      var budget = this._pixelBudget;
      if (this._studio && this._studioBudget) budget = budget ? Math.min(budget, this._studioBudget) : this._studioBudget;
      if (budget) {
        var px = canvas.clientWidth * canvas.clientHeight * dpr * dpr;
        if (px > budget) dpr = Math.max(0.5, dpr * Math.sqrt(budget / px));
      }
      var w = Math.max(1, Math.round(canvas.clientWidth * dpr));
      var h = Math.max(1, Math.round(canvas.clientHeight * dpr));
      if (canvas.width === w && canvas.height === h) return;
      canvas.width = w;
      canvas.height = h;
      this._view.setViewport([0, 0, w, h]);
      if (this._mirrorView) this._mirrorView.setViewport([0, 0, w, h]);
      this._applyProjection();
      // Resizing clears the drawing buffer: redraw now if the loop is parked
      // (off-screen, or idle in 'onDemand' mode), synchronously to avoid a blank flash.
      this._primeFrame();
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
     * @param {boolean} [options.castShadows=false] - Cast shadows (directional and spot
     *   lights; each shadow-casting light costs one extra depth pass)
     * @param {number} [options.innerCone] - Spot only: inner cone half-angle, radians
     * @param {number} [options.outerCone] - Spot only: outer cone half-angle, radians
     * @returns {Object} Filament Entity handle (pass it to removeLight or removeNode to delete)
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

      if (type === 'point' || type === 'spot') builder.falloff(falloff);
      if (type === 'spot' && Number.isFinite(options.outerCone)) {
        builder.spotLightCone(
          Number.isFinite(options.innerCone) ? options.innerCone : options.outerCone * 0.6,
          options.outerCone
        );
      }
      if (options.castShadows === true && type !== 'point') builder.castShadows(true);
      builder.build(this._engine, entity);

      if (type === 'point' || type === 'spot') {
        // Position point/spot lights via transform. A bare light entity has no
        // transform component: getInstance() on an entity without one returns an
        // invalid instance, so create the component first.
        var tm = this._engine.getTransformManager();
        if (!tm.hasComponent(entity)) tm.create(entity);
        var inst = tm.getInstance(entity);
        // Column-major translation matrix. The Filament.js binding has no
        // `Filament.mat4` namespace, so `Filament.mat4.translation()` threw.
        tm.setTransform(inst, [
          1, 0, 0, 0,
          0, 1, 0, 0,
          0, 0, 1, 0,
          position[0], position[1], position[2], 1
        ]);
      }

      this._scene.addEntity(entity);
      this._lightEntities.add(entity);
      this.requestRender();
      return entity;
    }

    /**
     * Change a light after addLight(): the same fields, every one optional, only what
     * you pass is touched. Cheap enough to call every frame, which is how a light gets
     * animated — a headlight flashing, a beacon blinking, a lamp warming up:
     *
     *     sv.setLight(headlight, { intensity: 3e7 });
     *     sv.setLight(beacon, { color: [1, 0.05, 0.02], intensity: on ? 2e6 : 0 });
     *
     * Shadow casting cannot change after creation: remove the light and add it again.
     *
     * @param {Object} entity - the handle addLight() returned
     * @param {Object} options - color, intensity, direction, position, falloff,
     *   innerCone, outerCone, as in addLight()
     * @returns {SceneViewInstance} this (for chaining)
     */
    setLight(entity, options) {
      options = options || {};
      var lm = this._engine.getLightManager();
      if (!lm.hasComponent(entity)) return this;
      var li = lm.getInstance(entity);
      if (options.color) lm.setColor(li, options.color);
      if (Number.isFinite(options.intensity)) lm.setIntensity(li, options.intensity);
      if (options.direction) lm.setDirection(li, options.direction);
      if (Number.isFinite(options.falloff)) lm.setFalloff(li, options.falloff);
      if (Number.isFinite(options.outerCone)) {
        lm.setSpotLightCone(li,
          Number.isFinite(options.innerCone) ? options.innerCone : options.outerCone * 0.6,
          options.outerCone);
      }
      if (options.position) {
        var tm = this._engine.getTransformManager();
        if (!tm.hasComponent(entity)) tm.create(entity);
        var p = options.position;
        tm.setTransform(tm.getInstance(entity), [1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, p[0], p[1], p[2], 1]);
      }
      return this.requestRender();
    }

    /**
     * The material names of the loaded model, as authored in the glTF (what
     * setMaterial() matches). Empty until loadModel() resolves.
     *
     * @returns {string[]} names, in the order the model lists them
     */
    getMaterialNames() {
      return Object.keys(this._materialIndex());
    }

    /**
     * Change a material of the loaded model at run time: its colour, emission,
     * roughness or metalness, on every primitive that uses it. The name is the glTF
     * material name, or a RegExp over it. Only the fields you pass change, so a call a
     * frame animates a material — a start light turning red, a neon flickering, a
     * screen fading in:
     *
     *     sv.setMaterial('Light startRed', { emissiveStrength: on ? 22 : 0 });
     *     sv.setMaterial(/^Light /, { emissive: [1, 0.7, 0.4], emissiveStrength: 6 });
     *
     * Emission is `emissive` (linear RGB, 0-1) times `emissiveStrength` (1 = display
     * white, above it blooms); the glTF's own KHR_materials_emissive_strength is the
     * starting value. A material that has no such parameter (an unlit one, for
     * instance) is skipped for that field.
     *
     * @param {string|RegExp} name - glTF material name, or a pattern over it
     * @param {Object} props
     * @param {number[]} [props.emissive] - emissive colour [r, g, b], linear
     * @param {number} [props.emissiveStrength] - emissive multiplier
     * @param {number[]} [props.baseColor] - base colour [r, g, b] or [r, g, b, a], linear
     * @param {number} [props.roughness] - 0 (mirror) to 1 (matte)
     * @param {number} [props.metallic] - 0 (dielectric) to 1 (metal)
     * @returns {SceneViewInstance} this (for chaining)
     */
    setMaterial(name, props) {
      props = props || {};
      var index = this._materialIndex(), keys = Object.keys(index), hits = [];
      for (var i = 0; i < keys.length; i++) {
        if (name instanceof RegExp ? name.test(keys[i]) : keys[i] === name) hits = hits.concat(index[keys[i]]);
      }
      if (hits.length === 0) return this;
      var set = function(mi, kind, param, value) {
        try {
          if (kind === 'f') mi.setFloatParameter(param, value);
          else if (kind === 'f3') mi.setFloat3Parameter(param, value);
          else mi.setFloat4Parameter(param, value);
        } catch (e) { /* the material has no such parameter (e.g. unlit) */ }
      };
      hits.forEach(function(mi) {
        if (props.emissive) set(mi, 'f3', 'emissiveFactor', props.emissive);
        if (Number.isFinite(props.emissiveStrength)) set(mi, 'f', 'emissiveStrength', props.emissiveStrength);
        if (props.baseColor) {
          var c = props.baseColor;
          set(mi, 'f4', 'baseColorFactor', [c[0], c[1], c[2], Number.isFinite(c[3]) ? c[3] : 1]);
        }
        if (Number.isFinite(props.roughness)) set(mi, 'f', 'roughnessFactor', props.roughness);
        if (Number.isFinite(props.metallic)) set(mi, 'f', 'metallicFactor', props.metallic);
      });
      return this.requestRender();
    }

    /**
     * Material instances of the loaded model, by glTF material name. Built once per
     * asset from the RenderableManager (the gltfio asset exposes no list of its own
     * in the JS binding) and dropped with the asset.
     * @private
     */
    _materialIndex() {
      var asset = this._asset;
      // Its own field: `_materials` is the gltfio material provider dispose() frees.
      if (this._materialCache && this._materialCacheAsset === asset) return this._materialCache;
      var index = {};
      if (asset) {
        try {
          var rm = this._engine.getRenderableManager();
          var ents = asset.getRenderableEntities();
          var n = ents.size ? ents.size() : ents.length;
          for (var i = 0; i < n; i++) {
            var e = ents.get ? ents.get(i) : ents[i];
            if (!rm.hasComponent(e)) continue;
            var inst = rm.getInstance(e), count = rm.getPrimitiveCount(inst);
            for (var p = 0; p < count; p++) {
              var mi = rm.getMaterialInstanceAt(inst, p), key = mi.getName();
              (index[key] = index[key] || []).push(mi);
            }
          }
        } catch (e) { /* no renderables yet */ }
      }
      this._materialCache = index;
      this._materialCacheAsset = asset;
      return index;
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

      return this._fetch(url)
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

        // Until its first frame is decoded the element belongs to no node: track it
        // so dispose() can stop the download and take it out of the page (#4366).
        self._loadingVideos.set(video, reject);

        video.addEventListener('loadeddata', function onLoaded() {
          video.removeEventListener('loadeddata', onLoaded);
          // dispose() already released the element and rejected the promise.
          if (!self._loadingVideos.delete(video)) return;

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

          // Update the Filament texture, and wake an 'onDemand' loop for it. Not while
          // the canvas is off-screen or the tab hidden: the gate (#2508) draws nothing
          // there, and a playing video must not turn into one full render per frame.
          // The loop redraws with the current texture when the canvas comes back.
          self._updateQuadTexture(entity, vi.canvas);
          if (self._shouldRender()) self.requestRender();
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
      return this.requestRender();
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
      this.requestRender();
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
        var rebound = false;
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
            rebound = true;
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
              rebound = true;
            }
          } catch (e2) { /* texture update failed silently */ }
        }

        // The material samples the new texture: the one it replaces is no longer
        // referenced and would otherwise live as long as the engine (#4366).
        if (rebound && nodeInfo.texture) {
          try { this._engine.destroyTexture(nodeInfo.texture); } catch (e3) { /* ignore */ }
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
        // An image or a video frame that arrives after dispose() has no engine left.
        if (self._disposed) {
          reject(_disposedError());
          return;
        }
        try {
          // Load the quad GLB through the asset loader
          var asset = self._createAsset(self._quadGLB);
          if (!asset) {
            reject(new Error('SceneView: Failed to create quad asset'));
            return;
          }

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

          self.requestRender();
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
      // Lights created via addLight() are not media nodes: handle them first.
      if (this._lightEntities.has(entity)) {
        this.removeLight(entity);
        return;
      }

      if (!this._mediaNodes.has(entity)) return;
      this._releaseMediaNode(entity);
      this.requestRender();
    }

    /**
     * Take a text, image or video node off the scene and free what it owns: the
     * `<video>` element, the quad asset, then the texture its material sampled.
     * @private
     */
    _releaseMediaNode(entity) {
      var nodeInfo = this._mediaNodes.get(entity);
      if (!nodeInfo) return;
      this._mediaNodes.delete(entity);
      this._billboards.delete(entity);

      var vInfo = this._videoElements.get(entity);
      if (vInfo) {
        _releaseVideo(vInfo.video);
        this._videoElements.delete(entity);
      }

      // The asset first: its material instance still references the texture.
      this._destroyAsset(nodeInfo.asset);
      if (nodeInfo.texture) {
        try { this._engine.destroyTexture(nodeInfo.texture); } catch (e) { /* ignore */ }
        nodeInfo.texture = null;
      }
    }

    // ---------------------------------------------------------------
    // Native resource lifetime (#4366)
    // ---------------------------------------------------------------

    /** fetch() tied to this viewer: dispose() aborts it. @private */
    _fetch(url) {
      return this._abort ? fetch(url, { signal: this._abort.signal }) : fetch(url);
    }

    /**
     * Parse a glTF/GLB into a gltfio asset, start loading its resources, and track it
     * so dispose() can destroy it. Returns null when the bytes do not parse.
     * @private
     */
    _createAsset(data, onLoaded) {
      var asset = this._loader.createAsset(data);
      if (!asset) return null;
      this._assets.add(asset);
      this._loadResources(asset, onLoaded);
      return asset;
    }

    /**
     * Load an asset's buffers and textures, as `asset.loadResources()` does in
     * Filament.js (same providers, same 30 ms decode tick, same `Filament.assets`
     * lookup for external URIs, resolved against the page), but with the loader and
     * its timer kept on the instance. Filament.js keeps both in a closure: nothing
     * could stop them, so they went on ticking on a destroyed engine after dispose().
     * @private
     */
    _loadResources(asset, onDone) {
      var self = this;
      var job = {
        cancelled: false,
        timer: null,
        loader: new Filament.gltfio$ResourceLoader(this._engine, true),
        stb: new Filament.gltfio$StbProvider(this._engine),
        ktx2: new Filament.gltfio$Ktx2Provider(this._engine)
      };
      job.loader.addStbProvider('image/jpeg', job.stb);
      job.loader.addStbProvider('image/png', job.stb);
      job.loader.addKtx2Provider('image/ktx2', job.ktx2);
      this._resourceLoads.set(asset, job);

      // External resources (a .gltf with separate .bin / images), keyed by absolute URI.
      var relativeUris = {};
      var textureUris = [];
      var bufferUris = [];
      asset.getResourceUris().forEach(function(relativeUri) {
        var absoluteUri = '' + new URL(relativeUri, document.location);
        if (!(absoluteUri in relativeUris)) {
          (_isTextureUri(relativeUri) ? textureUris : bufferUris).push(absoluteUri);
        }
        relativeUris[absoluteUri] = relativeUri;
      });

      function addResource(absoluteUri) {
        if (job.cancelled) return;
        var buffer = Filament.Buffer(Filament.assets[absoluteUri]);
        job.loader.addResourceData(relativeUris[absoluteUri], buffer);
        buffer.delete();
      }

      function decode() {
        if (job.cancelled) return;
        job.loader.asyncBeginLoad(asset);
        // One PNG/JPEG decode per tick, in the wasm layer.
        job.timer = setInterval(function() {
          job.loader.asyncUpdateLoad();
          if (job.loader.asyncGetLoadProgress() >= 1) {
            self._endResourceLoad(asset);
            if (onDone) onDone();
          }
        }, 30);
      }

      // Decoding starts once every buffer is there; textures may land while it runs.
      if (bufferUris.length === 0) decode();
      else Filament.fetch(bufferUris, decode, addResource);
      Filament.fetch(textureUris, null, addResource);
    }

    /**
     * Stop an asset's resource load, finished or not, and free its loader.
     * @private
     */
    _endResourceLoad(asset) {
      var job = this._resourceLoads.get(asset);
      if (!job) return;
      this._resourceLoads.delete(asset);
      job.cancelled = true;
      if (job.timer !== null) clearInterval(job.timer);
      job.loader.delete();
      job.stb.delete();
      job.ktx2.delete();
      if (this._resourceLoads.size === 0 && this._readyWaiters) {
        var waiters = this._readyWaiters;
        this._readyWaiters = null;
        waiters.forEach(function(done) { done(); });
      }
    }

    /**
     * Resolves once the picture is complete: every texture of the models loaded so far
     * is decoded and the environment given at creation (`iblUrl`) is in.
     * `loadModel()` resolves as soon as the geometry is on screen; gltfio decodes the
     * textures after that, one per 30 ms tick, and the environment has its own download,
     * so the first frames show the model partly dressed and unlit by its surroundings.
     * Await this before revealing the canvas (a poster hand-off, a screenshot).
     * Resolves at once when nothing is loading, and on dispose().
     * @returns {Promise<SceneViewInstance>}
     */
    whenReady() {
      var self = this;
      var textures = (this._disposed || this._resourceLoads.size === 0) ? Promise.resolve() :
        new Promise(function(resolve) { (self._readyWaiters = self._readyWaiters || []).push(resolve); });
      return Promise.all([textures, this._environmentReady || Promise.resolve()])
        .then(function() { return self; });
    }

    /**
     * Destroy a gltfio asset created by this viewer: stop its load, take its
     * entities off the scene, then free its entities, buffers, textures and material
     * instances. The JS handle is deleted too, so a caller that kept it gets a clean
     * exception instead of reading freed memory.
     * @private
     */
    _destroyAsset(asset) {
      if (!asset || !this._assets.delete(asset)) return;
      this._endResourceLoad(asset);
      try {
        var entities = asset.getEntities();
        this._scene.removeEntities(entities);
        entities.forEach(function(e) { e.delete(); });
        var root = asset.getRoot();
        this._scene.remove(root);
        root.delete();
      } catch (e) { /* not on the scene */ }
      this._loader.destroyAsset(asset);
      // Not a second free: Filament.js binds FilamentAsset with a no-op destructor,
      // so delete() only invalidates the JS handle. destroyAsset() freed the object.
      asset.delete();
    }

    /**
     * Put an IndirectLight on the scene (or none) and release the one it replaces.
     * `ktx` is the Ktx1Bundle its cubemap was read from, when there is one.
     * @private
     */
    _setIndirectLight(light, ktx) {
      var previous = this._ibl;
      this._scene.setIndirectLight(light || null);
      this._ibl = light ? { light: light, ktx: ktx || null } : null;
      if (previous) this._destroyIndirectLight(previous);
    }

    /** Build an IndirectLight from KTX1 bytes and make it the scene's. @private */
    _setKtxEnvironment(buffer, intensity) {
      // Filament.js hands the Ktx1Bundle it allocates back through this object and
      // never frees it: keep it, so it can go with the light.
      var options = {};
      var light = this._engine.createIblFromKtx1(buffer, options);
      light.setIntensity(intensity);
      this._setIndirectLight(light, options.ktx);
    }

    /**
     * Build an IndirectLight from 3 bands of spherical harmonics and make it the scene's.
     * @private
     */
    _setShEnvironment(bands, intensity) {
      // Filament.js skips an array of any other length and builds a light with no irradiance.
      if (!bands || bands.length !== 27) {
        throw new Error('expected 27 coefficients (9 x RGB), got ' + (bands ? bands.length : bands));
      }
      var light = Filament.IndirectLight.Builder()
        .irradianceSh(3, bands)
        .reflections(this._createShReflections(bands))
        .intensity(intensity)
        .build(this._engine);
      this._setIndirectLight(light);
    }

    /**
     * The cubemap an SH environment reflects: the harmonics themselves, evaluated per
     * direction. Without it the light has no specular term and a metal, which has no
     * diffuse response, renders black. One small level is enough: 3 bands carry
     * nothing sharper.
     * @private
     */
    _createShReflections(sh) {
      var size = 16;
      var pixels = new Float32Array(6 * size * size * 3);
      var o = 0;
      for (var face = 0; face < 6; face++) {
        for (var row = 0; row < size; row++) {
          for (var col = 0; col < size; col++) {
            var u = 2 * (col + 0.5) / size - 1;
            var v = 2 * (row + 0.5) / size - 1;
            // OpenGL cube faces, in upload order: +X, -X, +Y, -Y, +Z, -Z.
            var d = face === 0 ? [1, -v, -u] : face === 1 ? [-1, -v, u]
                  : face === 2 ? [u, 1, v] : face === 3 ? [u, -1, -v]
                  : face === 4 ? [u, -v, 1] : [-u, -v, -1];
            var length = Math.hypot(d[0], d[1], d[2]);
            var x = d[0] / length, y = d[1] / length, z = d[2] / length;
            for (var c = 0; c < 3; c++) {
              // Same basis, same order as Filament's Irradiance_SphericalHarmonics().
              pixels[o++] = Math.max(0,
                sh[c] + sh[3 + c] * y + sh[6 + c] * z + sh[9 + c] * x +
                sh[12 + c] * y * x + sh[15 + c] * y * z + sh[18 + c] * (3 * z * z - 1) +
                sh[21 + c] * z * x + sh[24 + c] * (x * x - y * y));
            }
          }
        }
      }
      var texture = Filament.Texture.Builder()
        .width(size)
        .height(size)
        .levels(1)
        .sampler(Filament.Texture$Sampler.SAMPLER_CUBEMAP)
        .format(Filament.Texture$InternalFormat.R11F_G11F_B10F)
        .build(this._engine);
      texture.setImageCube(this._engine, 0,
        Filament.PixelBuffer(pixels, Filament.PixelDataFormat.RGB, Filament.PixelDataType.FLOAT));
      return texture;
    }

    /**
     * Destroy an IndirectLight, its reflections cubemap and, for a KTX environment,
     * the Ktx1Bundle behind that cubemap.
     * @private
     */
    _destroyIndirectLight(ibl) {
      var reflections = ibl.light.getReflectionsTexture();
      this._engine.destroyIndirectLight(ibl.light);
      if (reflections) this._engine.destroyTexture(reflections);
      if (ibl.ktx) {
        // The cubemap upload reads the bundle's bytes when the command runs: flush
        // the queue before freeing them.
        this._engine.execute();
        ibl.ktx.delete();
      }
    }

    /**
     * Destroy a light entity created by this viewer: its components in the engine,
     * then the entity itself (the EntityManager is shared by every viewer of the page).
     * @private
     */
    _destroyLight(entity) {
      try { this._scene.remove(entity); } catch (e) { /* ignore */ }
      this._engine.destroyEntity(entity);
      Filament.EntityManager.get().destroy(entity);
    }

    /**
     * Initial environment: the KTX IBL when it can be fetched, synthetic SH otherwise.
     * @private
     */
    _loadDefaultEnvironment(url, intensity, studio) {
      var self = this;
      // Requests can overlap (setStudio() switching themes): the last one wins.
      var serial = ++this._envSerial;
      // What is (or will be) in place, kept for setStudio(false) and a failed loadEnvironment().
      var source = this._envSource = this._envLoading = { url: url, intensity: intensity, studio: !!studio };
      function settled() {
        if (self._envLoading === source) self._envLoading = null;
      }
      // Kept for whenReady(): settles (never rejects) once the environment is applied.
      this._environmentReady = this._fetch(url)
        .then(function(r) {
          if (!r.ok) throw new Error('HTTP ' + r.status);
          return r.arrayBuffer().then(function(ab) { return new Uint8Array(ab); });
        })
        .then(function(buffer) {
          // Disposed while the IBL was on its way: the engine is gone, drop it (#4366).
          if (self._disposed || serial !== self._envSerial) return;
          try {
            self._setKtxEnvironment(buffer, intensity);
            _log('SceneView: KTX IBL loaded (' + Math.round(buffer.length / 1024) + 'KB)');
          } catch (e) {
            console.warn('SceneView: createIblFromKtx1 failed, using SH fallback', e);
            _applySyntheticIBL(self, intensity);
          }
          self.requestRender();
        })
        .catch(function() {
          if (self._disposed || serial !== self._envSerial) return;
          _applySyntheticIBL(self, intensity);
          self.requestRender();
        })
        .then(settled);
    }

    // ---------------------------------------------------------------
    // Dispose
    // ---------------------------------------------------------------

    /**
     * Stop the viewer and release everything it created: the fetches still in flight,
     * its DOM listeners and elements, every Filament object, the engine, then the
     * canvas's WebGL context (the canvas fires `webglcontextlost`; create() on the same
     * canvas brings the context back).
     * Safe to call more than once. The instance cannot be used afterwards.
     */
    dispose() {
      // Idempotent (#4366): a second call used to walk the freed engine.
      if (this._disposed) return;
      this._disposed = true;
      this._running = false;

      // Fetches still in flight (IBL, model, image): abort them. A response that
      // already landed is dropped by its continuation, which checks _disposed.
      if (this._abort) this._abort.abort();

      // Stop any pending animation frame so the loop cannot draw after teardown.
      if (this._rafId !== null) {
        cancelAnimationFrame(this._rafId);
        this._rafId = null;
      }
      if (this._primeRafId) {
        cancelAnimationFrame(this._primeRafId);
        this._primeRafId = null;
      }
      if (this._autoRotateTimer) {
        clearTimeout(this._autoRotateTimer);
        this._autoRotateTimer = null;
      }

      // Clean up video elements, playing or still loading
      this._videoElements.forEach(function(vInfo) { _releaseVideo(vInfo.video); });
      this._videoElements.clear();
      this._loadingVideos.forEach(function(reject, video) {
        _releaseVideo(video);
        reject(_disposedError());
      });
      this._loadingVideos.clear();
      this._billboards.clear();
      // onFrame() callbacks capture page state (a mirror canvas, a component): drop them.
      this._frameCallbacks.clear();
      this._renderRequested = false;

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
      if (this._resizeObserver) {
        this._resizeObserver.disconnect();
        this._resizeObserver = null;
      }

      // Remove any load-failure fallback overlay we painted into the DOM.
      this._hideLoadFallback();

      this._releaseNative();
      // The engine is gone: hand the canvas's WebGL context back to the browser.
      _releaseContext(this._canvas);
      _activeCanvases.delete(this._canvas);
    }

    /**
     * Free every Filament object of this viewer, then the engine (#4366).
     *
     * Order matters. `Engine.destroy` refuses to shut down, with an exception, while
     * a material instance is alive, and gltfio assets own material instances: every
     * asset goes first. dispose() used to skip that and swallow the exception, which
     * left the whole engine allocated and half shut down.
     * @private
     */
    _releaseNative() {
      var self = this;
      var engine = this._engine;
      try {
        // Resource loads: their timers and loaders run on the engine.
        Array.from(this._resourceLoads.keys()).forEach(function(asset) {
          self._endResourceLoad(asset);
        });

        // Media nodes (asset + texture), then every other asset: the model, the
        // primitives, and the ones taken off the scene whose handle a caller may hold.
        Array.from(this._mediaNodes.keys()).forEach(function(entity) {
          self._releaseMediaNode(entity);
        });
        Array.from(this._assets).forEach(function(asset) { self._destroyAsset(asset); });
        this._destroyStudioNative();
        this._asset = null;
        this._animator = null;
        this._primitiveAssets = [];

        this._setIndirectLight(null);

        this._lightEntities.forEach(function(entity) { self._destroyLight(entity); });
        this._lightEntities.clear();
        this._baseLights.forEach(function(entity) { self._destroyLight(entity); });
        this._baseLights = [];

        // No asset is left: the loader's materials can go, then the loader.
        // Both delete() calls release JS-side wrappers only. Filament.js 1.72.1 binds
        // neither AssetLoader::destroy nor a destructor for the MaterialProvider, so
        // their order is free, and the provider keeps its ubershader archive (about
        // 15 MB per viewer) until the page goes: nothing in JS can free it.
        if (this._materials) {
          this._materials.destroyMaterials();
          this._materials.delete();
        }
        this._loader.delete();

        if (this._colorGrading) {
          this._view.setColorGrading(null);
          engine.destroyColorGrading(this._colorGrading);
        }
        engine.destroyView(this._view);
        engine.destroyScene(this._scene);
        engine.destroyRenderer(this._renderer);
        engine.destroyCameraComponent(this._cameraEntity);
        Filament.EntityManager.get().destroy(this._cameraEntity);
        engine.destroySwapChain(this._swapChain);
        // Run the destroy commands on this engine's WebGL context before it goes.
        engine.execute();
      } catch (e) {
        console.error('SceneView: dispose() could not release every Filament object', e);
      }

      try {
        Filament.Engine.destroy(engine);
      } catch (e) {
        console.error('SceneView: Engine.destroy failed, the engine is still allocated', e);
      }

      // The handles now point at freed memory in a heap every viewer of the page
      // shares: drop them, so a late call fails on null instead of writing there.
      this._engine = null;
      this._scene = null;
      this._renderer = null;
      this._view = null;
      this._swapChain = null;
      this._camera = null;
      this._cameraEntity = null;
      this._loader = null;
      this._materials = null;
      this._colorGrading = null;
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
        self._finishReveal();
        self._isDragging = true;
        self._lastMouse = { x: e.clientX, y: e.clientY };
        self._autoRotate = false;
        self._velocityAngle = 0;
        self._velocityHeight = 0;
        if (self._autoRotateTimer) { clearTimeout(self._autoRotateTimer); self._autoRotateTimer = null; }
        self.requestRender();
      });
      this._addListener(canvas, 'mousemove', function(e) {
        if (!self._isDragging) return;
        var dx = (e.clientX - self._lastMouse.x) * 0.005;
        var dy = (e.clientY - self._lastMouse.y) * 0.01 * self._dragHeightScale;
        self._velocityAngle = -dx;
        self._velocityHeight = dy;
        self._angle -= dx;
        self._orbitHeight += dy;
        self._lastMouse = { x: e.clientX, y: e.clientY };
        self.requestRender();
      });
      // One resume timer at a time, as in endTouchDrag below: a mouseup followed by the
      // mouseleave on the way to a pause button used to leave the first timer running,
      // out of reach of setAutoRotate(false), and the rotation came back 3 s later.
      this._addListener(canvas, 'mouseup', function() {
        self._isDragging = false;
        if (self._autoRotateTimer) { clearTimeout(self._autoRotateTimer); self._autoRotateTimer = null; }
        // Resume auto-rotate after 3s idle (like model-viewer)
        if (self._wantsAutoRotate) {
          self._autoRotateTimer = setTimeout(() => { self._autoRotate = true; self.requestRender(); }, 3000);
        }
      });
      this._addListener(canvas, 'mouseleave', function() {
        self._isDragging = false;
        if (self._autoRotateTimer) { clearTimeout(self._autoRotateTimer); self._autoRotateTimer = null; }
        if (self._wantsAutoRotate) {
          self._autoRotateTimer = setTimeout(() => { self._autoRotate = true; self.requestRender(); }, 3000);
        }
      });

      this._addListener(canvas, 'wheel', function(e) {
        e.preventDefault();
        self._finishReveal();
        self._orbitRadius *= (1 + e.deltaY * 0.001);
        self._orbitRadius = Math.max(self._minRadius || 0.5, Math.min(self._maxRadius || 50, self._orbitRadius));
        self.requestRender();
      }, { passive: false });

      // Touch: one finger orbits, and the page stays still under it.
      //
      // `touch-action: pan-y` on the canvas hands the vertical swipe back to the
      // page, for a viewer that sits in a scrolling page: the drag then starts
      // only once the finger has clearly moved sideways, and a vertical swipe
      // scrolls the page as it does anywhere else. Read at every touchstart, so a
      // page can change it at any time. Any other value keeps every move.
      function pagePansY() {
        var action = window.getComputedStyle(canvas).touchAction || '';
        return action.split(' ').indexOf('pan-y') !== -1;
      }
      function beginTouchDrag(touch) {
        self._finishReveal();
        self._isDragging = true;
        self._lastMouse = { x: touch.clientX, y: touch.clientY };
        self._autoRotate = false;
        self._velocityAngle = 0;
        self._velocityHeight = 0;
        if (self._autoRotateTimer) { clearTimeout(self._autoRotateTimer); self._autoRotateTimer = null; }
        self.requestRender();
      }
      // `coast` keeps the last velocity, so a flick carries on and slows down.
      function endTouchDrag(coast) {
        self._touchStart = null;
        if (!self._isDragging) return;
        self._isDragging = false;
        if (!coast) {
          self._velocityAngle = 0;
          self._velocityHeight = 0;
        }
        if (self._autoRotateTimer) { clearTimeout(self._autoRotateTimer); self._autoRotateTimer = null; }
        if (self._wantsAutoRotate) {
          self._autoRotateTimer = setTimeout(() => { self._autoRotate = true; self.requestRender(); }, 3000);
        }
      }

      this._addListener(canvas, 'touchstart', function(e) {
        if (e.touches.length !== 1) { self._touchStart = null; return; }
        var touch = e.touches[0];
        self._touchPansY = pagePansY();
        if (self._touchPansY) {
          // Whose gesture this is gets decided on the first real move.
          self._touchStart = { x: touch.clientX, y: touch.clientY };
        } else {
          beginTouchDrag(touch);
        }
      });
      this._addListener(canvas, 'touchmove', function(e) {
        if (e.touches.length !== 1) return;
        var touch = e.touches[0];
        if (self._touchStart) {
          var ax = Math.abs(touch.clientX - self._touchStart.x);
          var ay = Math.abs(touch.clientY - self._touchStart.y);
          if (ax < 8 && ay < 8) return;        // too short to tell
          self._touchStart = null;
          if (ax <= ay) return;                // a vertical swipe: the page scrolls
          beginTouchDrag(touch);
        }
        if (!self._isDragging) return;
        if (e.cancelable) {
          e.preventDefault();
        } else if (self._touchPansY) {
          // The browser is already scrolling with this touch: it is the page's.
          endTouchDrag(false);
          return;
        }
        var dx = (touch.clientX - self._lastMouse.x) * 0.005;
        var dy = (touch.clientY - self._lastMouse.y) * 0.01 * self._dragHeightScale;
        self._velocityAngle = -dx;
        self._velocityHeight = dy;
        self._angle -= dx;
        self._orbitHeight += dy;
        self._lastMouse = { x: touch.clientX, y: touch.clientY };
        self.requestRender();
      }, { passive: false });
      this._addListener(canvas, 'touchend', function() { endTouchDrag(true); });
      // The system took the touch away (a call, a system gesture, the browser's
      // own scroll): there is no touchend. Without this the drag never ended, and
      // the viewer neither resumed its rotation nor went back to idle.
      this._addListener(canvas, 'touchcancel', function() { endTouchDrag(false); });
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
        // Capped at 2x for performance, or at setPixelRatio()'s value.
        self._resizeCanvas();
      });
      this._resizeObserver.observe(this._canvas);
    }

    _startRenderLoop() {
      var self = this;
      // Guard against a double-start (e.g. _maybeResume racing the initial call).
      if (self._rafId !== null) return;
      function render(now) {
        // Suspend the loop when disposed, off-screen, or the tab is hidden
        // (#2508). Clearing _rafId and returning (without re-arming) stops all
        // GPU/CPU work; _maybeResume() restarts it when visibility returns.
        if (!self._shouldRender()) {
          self._rafId = null;
          return;
        }

        // Auto-rotate: 30°/sec ÷ 60fps (matches model-viewer). The studio is a slower
        // turntable, timed so a 120 Hz screen or a dropped frame does not change its pace.
        var lastTick = self._lastTickAt;
        self._lastTickAt = now;
        // The studio's turntable waits for the move-in, so it starts from the opening pose.
        if (self._autoRotate && !(self._reveal && self._reveal.start === null)) {
          self._angle += !self._studio ? 0.00873
            : _STUDIO_TURN_RATE * (lastTick ? Math.min(0.1, Math.max(0, now - lastTick) / 1000) : 1 / 60);
        }

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

        // 'onDemand': park once a frame is on screen and nothing moves any more.
        // requestRender() (input, camera and scene calls) re-arms the loop. A call
        // made during the draw itself, from an onFrame callback, keeps it going one
        // more frame: the flag is cleared here and re-read once the frame is out.
        self._renderRequested = false;
        var drawn = self._drawFrame();
        // The studio steps its quality down when the GPU falls behind. Every tick counts,
        // drawn or skipped: their timestamps carry the browser's own cadence.
        if (self._studio) self._studioTrackFrame(now, drawn);
        if (drawn && self._renderMode === 'onDemand' && self._isIdle()
            && !self._renderRequested) {
          self._rafId = null;
          return;
        }
        self._rafId = requestAnimationFrame(render);
      }
      self._rafId = requestAnimationFrame(render);
    }

    /**
     * Position the camera for the current mode and draw one frame. Called by the
     * render loop every tick, and by _primeFrame() while the loop is suspended.
     * Returns false when Filament skipped the frame (beginFrame() said no).
     */
    _drawFrame() {
      var self = this;
      self._clampOrbit();
      var t = self._orbitTarget;
      var r = self._orbitRadius;
      var h = self._orbitHeight;
      var mode = self._cameraMode || 'orbit';
      // Mutate the reusable eye/center/up scratch arrays in place instead of
      // allocating fresh arrays every frame (#2274). Camera.lookAt reads them
      // synchronously, so reuse is safe.
      var eye = self._eye;
      var center = self._center;
      // The studio's move-in is an orbit move: another mode drops it, or the loop never idles.
      if ((mode === 'map' || mode === 'freelook') && self._reveal) self._reveal = null;
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
        var angle = self._angle;
        if (self._reveal) {
          // The studio's move-in: starts 12 % farther out and 25 degrees around, then
          // eases onto the framed pose. Its clock starts once the textures are on, or
          // after 4 s without them.
          var reveal = self._reveal;
          if (reveal.start === null && !self._studioDressing()) reveal.start = performance.now();
          var k = reveal.start === null ? 0 : Math.min(1, (performance.now() - reveal.start) / _STUDIO_REVEAL_MS);
          var back = Math.pow(1 - k, 3);
          r *= 1 + 0.12 * back;
          h = t[1] + (h - t[1]) * (1 + 0.12 * back);
          angle -= 0.436 * back;
          if (k >= 1) self._reveal = null;
        }
        eye[0] = t[0] + Math.sin(angle) * r;
        eye[1] = h;
        eye[2] = t[2] + Math.cos(angle) * r;
        center[0] = t[0]; center[1] = t[1]; center[2] = t[2];
        self._camera.lookAt(eye, center, self._up);
      }

      // The studio floor's reflection: the same camera mirrored in the floor plane.
      if (self._mirrorView && self._studioLayout) {
        var mm = self._camera.getModelMatrix();
        mm[1] = -mm[1]; mm[5] = -mm[5]; mm[9] = -mm[9];
        mm[13] = 2 * self._studioLayout.y - mm[13];
        self._mirrorCamera.setModelMatrix(mm);
      }

      self._engine.execute();
      try {
        if (self._renderer.beginFrame(self._swapChain)) {
          if (self._mirrorView) self._renderer.renderView(self._mirrorView);
          self._renderer.renderView(self._view);
          self._renderer.endFrame();
          self._emitFrame();
          return true;
        }
      } catch (e) {
        // Filament 1.70 may need different render call
        console.error('SceneView render error:', e.message);
        self._running = false;
      }
      return false;
    }

    /**
     * Hand the just-drawn canvas to onFrame() listeners. Runs in the same task as the
     * draw, so drawImage(canvas) still sees the frame (no preserveDrawingBuffer).
     * @private
     */
    _emitFrame() {
      if (this._frameCallbacks.size === 0) return;
      for (const callback of this._frameCallbacks) {
        try {
          callback(this._canvas);
        } catch (e) {
          console.error('SceneView: onFrame callback failed', e);
        }
      }
    }

    /**
     * Draw a single frame while the loop is suspended off-screen (#3690).
     *
     * The visibility gate (#2508) stops the loop before a below-the-fold viewer
     * has ever drawn its model, so the canvas stays empty until the reader
     * scrolls to it: a full-page capture, a print, or any compositor that reads
     * the canvas while it is off-screen gets a blank frame, and the first real
     * frame pays for the shader compile and texture upload at the moment the
     * reader arrives. One frame once the model, the IBL or the canvas size
     * changes keeps the canvas current at the cost of a single draw. It never
     * re-arms the loop: the gate still decides when rendering runs. Also redraws a
     * loop parked by 'onDemand' mode after a resize.
     *
     * @param {boolean} [deferred=false] - draw on the next animation frame instead of
     *   now, so several requestRender() calls in one task draw once
     */
    _primeFrame(deferred) {
      var self = this;
      if (self._primeRafId) return;  // a retry is already queued
      var attempts = 0;
      function attempt() {
        self._primeRafId = null;
        // The running loop draws the next frame anyway; a disposed viewer never.
        if (self._rafId !== null || !self._running) return;
        // beginFrame() skips a frame while the GPU is still behind, and a parked
        // loop would never ask again: retry on the next few animation frames.
        if (!self._drawFrame() && ++attempts < 10) {
          self._primeRafId = requestAnimationFrame(attempt);
        }
      }
      if (deferred) self._primeRafId = requestAnimationFrame(attempt);
      else attempt();
    }

    // ---------------------------------------------------------------
    // Scene management & geometry primitives
    // ---------------------------------------------------------------

    /**
     * Remove all renderable entities (models, primitives) from the scene.
     * Lights and camera are preserved.
     */
    clearScene() {
      // Destroy the loaded glTF asset (off the scene alone, it stayed allocated: #4366)
      if (this._asset) {
        var model = this._asset;
        this._asset = null;
        this._destroyAsset(model);
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
      // Remove media nodes, with their quad, texture and <video> element
      if (this._mediaNodes && this._mediaNodes.size > 0) {
        var self = this;
        Array.from(this._mediaNodes.keys()).forEach(function(entity) {
          self._releaseMediaNode(entity);
        });
      }
      // The studio's wall, floor and shadow were sized for that model: rebuild without it.
      this._studioShadow = null;
      this._studioBox = null;
      if (this._studio) {
        try { this._studioRebuild(false); } catch (e) { console.warn('SceneView: studio staging failed', e); }
      }
      this.requestRender();
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
      this._animationDone = false;
      return this.requestRender();
    }

    /**
     * Stop animation playback and reset to the rest pose.
     * @returns {SceneViewInstance} this (for chaining)
     */
    stopAnimation() {
      this._animationIndex = -1;
      this._animationPauseTime = -1;
      return this.requestRender();
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
            this._animationDone = true; // lets an 'onDemand' loop park on the last pose
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
     * @param {Object} entity - Filament Entity handle returned by addLight()
     * @returns {SceneViewInstance} this (for chaining)
     */
    removeLight(entity) {
      // A light created by addLight() is owned by the viewer: untrack it and
      // free its light and transform components, then the entity. The Filament.js
      // LightManager binding has no destroy() of its own.
      if (this._lightEntities.delete(entity)) {
        try { this._destroyLight(entity); } catch (e) { /* ignore */ }
      } else {
        try { this._scene.remove(entity); } catch (e) { /* ignore */ }
      }
      return this.requestRender();
    }

    /**
     * Remove all lights from the scene (including the base 3-point studio
     * rig installed at engine creation). Useful when you want a truly custom
     * lighting setup with addLight().
     *
     * By default the IBL / environment goes too, so the lights you add are the
     * only ones left. Pass `{ keepEnvironment: true }` to keep the environment
     * given at creation (`iblUrl`) — the reflections stay, the studio rig goes.
     *
     * @param {Object} [options]
     * @param {boolean} [options.keepEnvironment=false] - Keep the IBL / environment
     * @returns {SceneViewInstance} this (for chaining)
     */
    clearLights(options) {
      if (this._baseLights && this._baseLights.length > 0) {
        var self = this;
        this._baseLights.forEach(function(entity) {
          try { self._destroyLight(entity); } catch (e) { /* ignore */ }
        });
        this._baseLights = [];
      }
      // Also drop IBL so the user-provided lights dominate
      if (!(options && options.keepEnvironment)) {
        try { this._setIndirectLight(null); } catch (e) { /* ignore */ }
      }
      return this.requestRender();
    }

    /**
     * Create a colored box primitive and add it to the scene.
     *
     * @param {number[]} center - [x, y, z] position
     * @param {number[]} size - [w, h, d] dimensions
     * @param {number[]} color - [r, g, b, a] linear color (0-1) — glTF baseColorFactor; convert an sRGB hex first or it renders pastel
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
     * @param {number[]} color - [r, g, b, a] linear color (0-1) — glTF baseColorFactor; convert an sRGB hex first or it renders pastel
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
     * @param {number[]} color - [r, g, b, a] linear color (0-1) — glTF baseColorFactor; convert an sRGB hex first or it renders pastel
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

      try {
        var asset = this._createAsset(glbData);
        if (!asset) { console.warn('SceneView: Failed to create primitive asset'); return null; }
        this._scene.addEntity(asset.getRoot());
        this._scene.addEntities(asset.getRenderableEntities());
        this._primitiveAssets.push(asset);
        this.requestRender();
        return asset;
      } catch (e) {
        console.warn('SceneView: createPrimitive error', e);
        return null;
      }
    }
  }

  // Singleton guard — prevent multiple engine creations on same canvas
  var _activeCanvases = new Set();

  // Canvases whose WebGL context dispose() released, with what create() needs to bring
  // it back: canvas -> { ext: WEBGL_lose_context, lost: Promise }.
  var _releasedContexts = new WeakMap();
  var CONTEXT_RESTORE_TIMEOUT_MS = 5000;

  /**
   * Give a disposed viewer's WebGL context back to the browser.
   *
   * Filament.js registers every context in Emscripten's GL table and never removes it,
   * so a context outlives its engine. Browsers cap the live contexts of a page (16 in
   * Chromium) and force-lose the least recently used one past the cap: after some 16
   * create/dispose cycles, an idle viewer still on the page went black. Losing the
   * context here frees its GPU memory and, in Chromium, takes it out of that count
   * (WebKit goes on counting a lost context: Safari still evicts past 16). The canvas
   * fires `webglcontextlost` when it happens.
   *
   * A canvas keeps its context for life, lost or not: create() restores it before it
   * builds a new engine on the same canvas (see _restoreContext).
   */
  function _releaseContext(canvas) {
    var gl = canvas.getContext('webgl2');
    if (!gl || gl.isContextLost()) return;
    // Taken now: getExtension() returns null once the context is lost.
    var ext = gl.getExtension('WEBGL_lose_context');
    if (!ext) return;
    var released = { ext: ext, lost: null };
    released.lost = new Promise(function(resolve) {
      canvas.addEventListener('webglcontextlost', function(event) {
        // restoreContext() is refused unless this event was cancelled, and the browser
        // reads that once the dispatch returns: resolve in a later task, not a microtask.
        event.preventDefault();
        setTimeout(resolve, 0);
      }, { once: true });
    });
    _releasedContexts.set(canvas, released);
    ext.loseContext();
  }

  /**
   * Bring back the context dispose() released on this canvas. Resolves once the
   * browser has fired `webglcontextrestored`, rejects when it does not come in time.
   */
  function _restoreContext(canvas, released) {
    return released.lost.then(function() {
      var gl = canvas.getContext('webgl2');
      // Already back (a restore that outlived an earlier timeout): nothing to wait for.
      if (gl && !gl.isContextLost()) {
        _releasedContexts.delete(canvas);
        return;
      }
      return new Promise(function(resolve, reject) {
        function onRestored() {
          clearTimeout(timer);
          _releasedContexts.delete(canvas);
          resolve();
        }
        // A failed restore fires no event at all.
        var timer = setTimeout(function() {
          canvas.removeEventListener('webglcontextrestored', onRestored);
          reject(new Error('SceneView: WebGL context not restored after ' +
            CONTEXT_RESTORE_TIMEOUT_MS + 'ms, this canvas cannot render'));
        }, CONTEXT_RESTORE_TIMEOUT_MS);
        canvas.addEventListener('webglcontextrestored', onRestored, { once: true });
        released.ext.restoreContext();
      });
    });
  }

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

    // transparent: an alpha WebGL context, so the page shows through cleared pixels.
    var transparent = options.transparent === true;
    var engine = transparent
      ? Filament.Engine.create(canvas, { alpha: true })
      : Filament.Engine.create(canvas);
    var scene = engine.createScene();
    var renderer = engine.createRenderer();
    var cameraEntity = Filament.EntityManager.get().create();
    var camera = engine.createCamera(cameraEntity);
    var view = engine.createView();
    // Tone mapping: Filmic, as on Android (SceneFactories.createView) and in the Kotlin/JS
    // bundle, instead of Filament's default ACES (legacy) — one model grades the same everywhere.
    var colorGrading = Filament.ColorGrading.Builder()
      .toneMapping(Filament.ColorGrading$ToneMapping.FILMIC)
      .build(engine);
    view.setColorGrading(colorGrading);
    var swapChain = engine.createSwapChain();

    view.setCamera(camera);
    view.setScene(scene);
    view.setViewport([0, 0, canvas.width, canvas.height]);
    if (transparent) {
      // Blend the view over a transparent clear instead of writing opaque pixels.
      try { view.setBlendMode(Filament.View$BlendMode.TRANSLUCENT); } catch (_e) { /* older Filament */ }
      // Temporal dithering writes noise into the alpha channel: a speckled halo on the page.
      try { view.setDithering(Filament.View$Dithering.NONE); } catch (_e) { /* older Filament */ }
    }

    var bg = options.backgroundColor || (transparent ? [0, 0, 0, 0] : _DEFAULT_BACKGROUND);
    renderer.setClearOptions({ clearColor: _clearColorFor(bg, transparent), clear: true });

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

    // What engine.createAssetLoader() does, with the material provider kept: the
    // loader does not own it, and dispose() needs it to free the materials.
    var materials = new Filament.gltfio$UbershaderProvider(engine);
    var loader = new Filament.gltfio$AssetLoader(engine, materials);
    var instance = new SceneViewInstance(canvas, engine, scene, renderer, view, swapChain, camera, cameraEntity, loader, materials);
    instance._fov = fov;
    // Track base 3-point lights so clearLights() can wipe them for custom setups
    instance._baseLights = [sun, fill, back];
    // Owned by the instance: dispose() destroys it before the engine.
    instance._colorGrading = colorGrading;
    instance._transparent = transparent;
    instance._pageBackground = bg;
    instance._defaultIblUrl = options.iblUrl || '/environments/neutral_ibl.ktx';
    instance._defaultIblIntensity = options.iblIntensity || 40000;

    // --- IBL: a studio brings its own; otherwise the KTX, synthetic SH as the fallback ---
    if (options.studio) instance.setStudio(options.studio);
    else instance._loadDefaultEnvironment(instance._defaultIblUrl, instance._defaultIblIntensity);

    if (options.autoRotate === false) instance.setAutoRotate(false);
    if (options.renderMode !== undefined) instance.setRenderMode(options.renderMode);

    return instance;
  }

  /** Fallback IBL from spherical harmonics when KTX not available */
  function _applySyntheticIBL(instance, intensity) {
    try {
      // The harmonics of /environments/neutral_ibl.ktx, the default environment: a viewer
      // that cannot fetch it keeps the same diffuse light, with blurred reflections.
      instance._setShEnvironment([
         0.977,  0.977,  0.977,   // L00  — ambient
         0.553,  0.553,  0.553,   // L1-1 — y: light from above
         0.022,  0.022,  0.022,   // L10  — z
        -0.032, -0.032, -0.032,   // L11  — x
        -0.059, -0.059, -0.059,   // L2-2
         0.063,  0.063,  0.063,   // L2-1
         0.028,  0.028,  0.028,   // L20
         0.178,  0.178,  0.178,   // L21
         0.086,  0.086,  0.086    // L22
      ], intensity);
      _log('SceneView: Using synthetic SH IBL');
    } catch (e) {
      console.warn('SceneView: synthetic SH IBL failed', e);
    }
  }

  /**
   * Create a viewer on a canvas.
   *
   * @param {string|HTMLCanvasElement} canvasOrId - canvas element or its id
   * @param {Object} [options]
   * @param {boolean} [options.transparent=false] - alpha canvas: cleared pixels show the
   *   page behind it (backgroundColor defaults to [0, 0, 0, 0])
   * @param {number[]} [options.backgroundColor] - background [r, g, b, a] as displayed:
   *   sRGB 0-1, a hex byte / 255 (default: the dark slate #333C57)
   * @param {'continuous'|'onDemand'} [options.renderMode='continuous'] - see setRenderMode()
   * @param {boolean} [options.autoRotate=true] - orbit slowly until the user drags
   * @param {number} [options.fov=45] - vertical field of view in degrees
   * @param {string} [options.iblUrl] - KTX1 environment for image-based lighting
   * @param {number} [options.iblIntensity=40000]
   * @param {number} [options.lightIntensity=110000] - key (sun) light
   * @param {number} [options.initTimeoutMs=15000] - engine-init watchdog, <= 0 disables it
   * @param {string|boolean|Object} [options.studio] - stage the model in a lit photo
   *   studio from the first frame: 'light', 'dark', or setStudio() options
   * @returns {Promise<SceneViewInstance>}
   */
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
        function instantiate() {
          var instance = _createEngine(canvasOrId, options);
          if (instance) return instance;
          throw new Error('SceneView: Canvas already initialized');
        }
        // A viewer was disposed on this canvas: its WebGL context comes back first.
        // The canvas is held meanwhile, so a second create() fails fast, as it does
        // on a live viewer, instead of racing this one.
        var canvas = _resolveCanvas(canvasOrId);
        var released = canvas && _releasedContexts.get(canvas);
        if (!released || _activeCanvases.has(canvas)) return instantiate();
        _activeCanvases.add(canvas);
        return _restoreContext(canvas, released).then(
          function() { _activeCanvases.delete(canvas); return instantiate(); },
          function(e) { _activeCanvases.delete(canvas); throw e; }
        );
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
    version: '4.63.0',
    create: create,
    modelViewer: modelViewer
  };

})(typeof globalThis !== 'undefined' ? globalThis : window);
