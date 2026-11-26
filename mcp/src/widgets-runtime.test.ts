/** Protocol and renderer lifecycle tests. These do not claim to test GPU drawing. */

import { readFileSync } from "node:fs";
import { runInNewContext } from "node:vm";
import { describe, expect, it, vi } from "vitest";
import { LATEST_SCENEVIEW_RELEASE } from "./generated/version.js";

const WIDGET_FALLBACK_JS = readFileSync(
  new URL("../../website-static/js/widget-fallback.js", import.meta.url),
  "utf8"
);
const fallbackUrl = `https://sceneview.github.io/js/widget-fallback.js?v=${LATEST_SCENEVIEW_RELEASE}`;

import {
  readWidgetResource,
  WIDGET_3D_VIEWER_HTML,
  WIDGET_3D_VIEWER_URI,
  WIDGET_SHOW_IN_SPACE_HTML,
  WIDGET_UI_META,
} from "./widgets.js";

const scriptOf = (html: string) => [...html.matchAll(/<script>([\s\S]*?)<\/script>/g)][0][1];
const model = {
  modelUrl: "https://modelviewer.dev/shared-assets/models/Astronaut.glb",
  title: "Astronaut",
};
const flush = async () => {
  for (let i = 0; i < 40; i++) await Promise.resolve();
};

// Exercise the website renderer's real camera math without creating a GPU context.
const rendererSource = readFileSync(
  new URL("../../website-static/js/sceneview.js", import.meta.url),
  "utf8"
);
type CameraOrbit = { target: number[]; radius: number; height: number; angle: number; fov: number };
const cameraPrototype = runInNewContext(
  `${rendererSource.replace(
    "global.SceneView = {",
    "global.cameraPrototype = SceneViewInstance.prototype; global.SceneView = {"
  )}; globalThis.cameraPrototype;`
) as {
  _frameAsset: (asset: unknown, fill: number) => boolean;
  _frameBox: (box: unknown, fill: number) => boolean;
  frameModel: (options: { fill: number }) => void;
  getCameraOrbit: () => CameraOrbit;
  setCameraOrbit: (orbit: { radius: number; height: number }) => void;
};

function harness(
  options: {
    wasm?: boolean;
    primaryError?: boolean;
    fallbackError?: boolean;
    fallbackDownloadError?: boolean;
    space?: boolean;
    wide?: boolean;
    reducedMotion?: boolean;
    size?: number[];
    cameraMath?: boolean;
    width?: number;
    height?: number;
    openai?: Record<string, unknown>;
    query?: string;
  } = {}
) {
  const paths: { points: number[][]; fill?: string; stroke?: string }[] = [];
  let points: number[][] = [];
  const context = Object.fromEntries(
    [
      "setTransform",
      "clearRect",
      "beginPath",
      "lineTo",
      "moveTo",
      "closePath",
      "fill",
      "stroke",
      "fillRect",
      "fillText",
      "setLineDash",
      "ellipse",
    ].map((key) => [key, vi.fn()])
  );
  context.measureText = vi.fn((text: string) => ({ width: text.length * 6 }));
  context.beginPath.mockImplementation(() => {
    points = [];
  });
  context.moveTo.mockImplementation((x, y) => {
    points.push([x, y]);
  });
  context.lineTo.mockImplementation((x, y) => {
    points.push([x, y]);
  });
  context.fill.mockImplementation(() => {
    paths.push({ points: [...points], fill: context.fillStyle as unknown as string });
  });
  context.stroke.mockImplementation(() => {
    paths.push({ points: [...points], stroke: context.strokeStyle as unknown as string });
  });
  const observers: { callback: () => void; disconnect: ReturnType<typeof vi.fn> }[] = [];
  const elements = new Map<string, any>();
  function element(id: string) {
    if (elements.has(id)) return elements.get(id);
    const classes = new Set();
    const el: any = {
      id,
      hidden: true,
      textContent: "",
      style: {},
      dataset: {},
      href: "",
      innerHTML: "",
      clientWidth: options.width || 640,
      clientHeight: options.height || 340,
      getContext: () => context,
      setAttribute: vi.fn(),
      addEventListener: vi.fn(),
      remove: vi.fn(),
      classList: {
        add: (name: string) => classes.add(name),
        remove: (name: string) => classes.delete(name),
        contains: (name: string) => classes.has(name),
        toggle: (name: string, on: boolean) => (on ? classes.add(name) : classes.delete(name)),
      },
      cloneNode: () => ({ ...el, style: {} }),
      replaceWith: (replacement: unknown) => elements.set(id, replacement),
    };
    elements.set(id, el);
    return el;
  }
  const listeners: Record<string, (event: any) => void> = {};
  const messages: any[] = [];
  const scripts: string[] = [];
  const primary = {
    loadModel: vi.fn(async () => {}),
    dispose: vi.fn(),
    setAutoRotate: vi.fn(),
    frameModel: vi.fn<(options: { fill: number }) => void>(),
    getCameraOrbit: () => ({ target: [0, 0.9, 0], radius: 3, height: 0.9, angle: 0.785, fov: 30 }),
    setCameraOrbit: vi.fn<(orbit: { radius: number; height: number }) => void>(),
    _asset: {},
    _assetBox: () => ({ min: [0, 0, 0], max: options.size || [0.8, 1.8, 0.5] }),
  };
  if (options.cameraMath) {
    Object.assign(primary, {
      _canvas: element("canvas"),
      _fov: 30,
      _angle: 0.785,
      _frameAsset: cameraPrototype._frameAsset,
      _frameBox: cameraPrototype._frameBox,
      _applyProjection: () => {},
      requestRender: () => primary,
      getCameraOrbit: () => cameraPrototype.getCameraOrbit.call(primary),
    });
    primary.frameModel.mockImplementation((options) =>
      cameraPrototype.frameModel.call(primary, options)
    );
    primary.setCameraOrbit.mockImplementation((orbit) =>
      cameraPrototype.setCameraOrbit.call(primary, orbit)
    );
  }
  const fallback = {
    loadModel: vi.fn(async () => {}),
    dispose: vi.fn(),
    setAutoRotate: vi.fn(),
    frameModel: vi.fn(),
    getModelSize: () => options.size || [0.8, 1.8, 0.5],
  };
  const primaryFactory = vi.fn(
    async (_canvas?: unknown, _url?: string, _options?: { studio: { pool: string } }) => {
      if (options.primaryError) throw new Error("Filament initialization failed");
      return primary;
    }
  );
  const fallbackFactory = vi.fn(async () => {
    if (options.fallbackError) throw new Error("WebGL2 is unavailable");
    return fallback;
  });
  const parent = { postMessage: (message: unknown) => messages.push(message) };
  const queries: { query: string; matches: boolean; addEventListener: ReturnType<typeof vi.fn> }[] =
    [];
  const window: any = {
    parent,
    addEventListener: (name: string, listener: (event: any) => void) => {
      listeners[name] = listener;
    },
    openai: options.openai,
    matchMedia: (query: string) => {
      const media = {
        query,
        matches: query.includes("reduced-motion") ? !!options.reducedMotion : !!options.wide,
        addEventListener: vi.fn(),
      };
      queries.push(media);
      return media;
    },
  };
  const document: any = {
    getElementById: element,
    documentElement: element("html"),
    createElement: (tag: string) => ({ tag, remove: vi.fn() }),
    head: {
      appendChild: (script: any) => {
        scripts.push(script.src);
        if (script.src?.includes("sceneview.js"))
          window.SceneView = { modelViewer: primaryFactory };
        if (script.src === fallbackUrl) {
          if (options.fallbackDownloadError) {
            queueMicrotask(() => script.onerror?.());
            return;
          }
          window.SceneViewFallback = { modelViewer: fallbackFactory };
        }
        if (script.src?.includes("qrcode-vendor"))
          window.qrcode = () => ({
            addData: vi.fn(),
            make: vi.fn(),
            createSvgTag: () => "<svg></svg>",
          });
        queueMicrotask(() => script.onload?.());
      },
    },
  };
  const timers = new Set<ReturnType<typeof setTimeout>>();
  const timer = (fn: () => void, ms: number) => {
    const handle = setTimeout(fn, ms);
    timers.add(handle);
    return handle;
  };
  const wasm = vi.fn(async () => {
    if (options.wasm === false) throw new WebAssembly.CompileError("WASM blocked by CSP");
  });
  runInNewContext(scriptOf(options.space ? WIDGET_SHOW_IN_SPACE_HTML : WIDGET_3D_VIEWER_HTML), {
    window,
    document,
    location: { search: options.query || "" },
    ResizeObserver: class {
      disconnect = vi.fn();
      constructor(callback: () => void) {
        observers.push({ callback, disconnect: this.disconnect });
      }
      observe() {}
    },
    URL,
    URLSearchParams,
    Uint8Array,
    WebAssembly: { compile: wasm },
    console: { info: vi.fn(), warn: vi.fn() },
    setTimeout: timer,
    clearTimeout,
    setInterval,
    clearInterval,
  });
  const send = (method: string, params: unknown, source = parent) =>
    listeners.message({ source, data: { jsonrpc: "2.0", method, params } });
  return {
    elements,
    context,
    paths,
    observers,
    interact: (event: string) =>
      elements
        .get("canvas-wrap")
        .addEventListener.mock.calls.find((call: unknown[]) => call[0] === event)[1](),
    messages,
    scripts,
    primary,
    fallback,
    primaryFactory,
    fallbackFactory,
    wasm,
    send,
    setWide: (wide: boolean) => {
      const media = queries.find((media) => media.query.includes("min-width"));
      if (!media) throw new Error("The widget did not register its desktop media query");
      media.matches = wide;
      media.addEventListener.mock.calls[0][1]();
    },
    deliver: (data: Record<string, unknown> = model) =>
      send("ui/notifications/tool-result", { structuredContent: data }),
    init: () =>
      listeners.message({
        source: parent,
        data: { jsonrpc: "2.0", id: 1, result: { hostContext: { theme: "dark" } } },
      }),
    close: () => {
      listeners.pagehide({});
      for (const handle of timers) clearTimeout(handle);
    },
  };
}

describe("widget renderer selection", () => {
  it("handshakes before any renderer download and reports a visible height", () => {
    const h = harness();
    try {
      expect(h.messages[0].method).toBe("ui/initialize");
      expect(h.scripts).toEqual([]);
      h.init();
      expect(h.messages).toContainEqual({
        jsonrpc: "2.0",
        method: "ui/notifications/size-changed",
        params: { height: 560 },
      });
    } finally {
      h.close();
    }
  });
  it("uses Filament when WASM and initialization work", async () => {
    const h = harness();
    try {
      h.deliver();
      await flush();
      expect(h.wasm).toHaveBeenCalledOnce();
      expect(h.scripts).toHaveLength(2);
      expect(h.scripts).not.toContain(fallbackUrl);
      expect(h.primaryFactory).toHaveBeenCalledOnce();
      expect(h.fallbackFactory).not.toHaveBeenCalled();
      expect(h.elements.get("html").dataset.renderer).toBe("filament");
      expect(h.primary.setAutoRotate).toHaveBeenCalledWith(true);
      expect(h.elements.get("ar-open").href).toContain("https://ar.sceneview.dev/open?url=");
    } finally {
      h.close();
    }
    expect(h.primary.dispose).toHaveBeenCalledOnce();
  });
  it("does not download Filament after a CSP-denied WASM probe", async () => {
    const h = harness({ wasm: false });
    try {
      h.deliver();
      await flush();
      expect(h.scripts).toEqual([fallbackUrl]);
      expect(h.primaryFactory).not.toHaveBeenCalled();
      expect(h.fallbackFactory).toHaveBeenCalledOnce();
      expect(h.elements.get("html").dataset.renderer).toBe("fallback");
      expect(h.elements.get("error").hidden).toBe(true);
    } finally {
      h.close();
    }
  });
  it("also recovers from Filament initialization failure with a fresh canvas", async () => {
    const h = harness({ primaryError: true });
    try {
      const first = h.elements.get("canvas");
      h.deliver();
      await flush();
      expect(h.primaryFactory).toHaveBeenCalledOnce();
      expect(h.fallbackFactory).toHaveBeenCalledOnce();
      expect(h.elements.get("canvas")).not.toBe(first);
      expect(h.elements.get("html").dataset.renderer).toBe("fallback");
    } finally {
      h.close();
    }
  });
  it("keeps the phone action and shows an error if WebGL is unavailable too", async () => {
    const h = harness({ wasm: false, fallbackError: true });
    try {
      h.deliver();
      await flush();
      expect(h.elements.get("loader").hidden).toBe(true);
      expect(h.elements.get("error").hidden).toBe(false);
      expect(h.elements.get("error-detail").textContent).toBe("WebGL2 is unavailable");
      expect(h.elements.get("ar").hidden).toBe(false);
    } finally {
      h.close();
    }
  });
  it("ignores messages from windows other than its parent", async () => {
    const h = harness();
    try {
      h.send("ui/notifications/tool-result", { structuredContent: model }, {} as any);
      await flush();
      expect(h.wasm).not.toHaveBeenCalled();
    } finally {
      h.close();
    }
  });
  it("renders only the latest queued payload", async () => {
    const h = harness();
    try {
      h.deliver();
      h.deliver({ ...model, modelUrl: "https://sceneview.github.io/new.glb", title: "New model" });
      await flush();
      expect(h.primaryFactory).toHaveBeenCalledOnce();
      expect(h.primaryFactory.mock.calls[0]).toContain("https://sceneview.github.io/new.glb");
      expect(h.elements.get("title").textContent).toBe("New model");
    } finally {
      h.close();
    }
  });
});

describe("fallback resource packaging", () => {
  it("boots the real bundled dependency without dynamic code generation", () => {
    const window: { SceneViewFallback?: { modelViewer: unknown } } = {};
    runInNewContext(
      WIDGET_FALLBACK_JS,
      { window, console },
      {
        contextCodeGeneration: { strings: false, wasm: false },
      }
    );
    expect(window.SceneViewFallback?.modelViewer).toBeTypeOf("function");
  });
  it("keeps resource HTML small and loads a licensed fallback from the allowed website", () => {
    expect(Buffer.byteLength(WIDGET_3D_VIEWER_HTML)).toBeLessThan(60_000);
    expect(WIDGET_3D_VIEWER_HTML).toContain(fallbackUrl);
    expect(WIDGET_3D_VIEWER_HTML).not.toContain('id="fallback-source"');
    expect(WIDGET_3D_VIEWER_HTML).not.toContain("Permission is hereby granted");
    expect(WIDGET_FALLBACK_JS).toContain("Permission is hereby granted");
    expect(WIDGET_UI_META.csp).not.toHaveProperty("wasmUnsafeEval");
    expect(WIDGET_UI_META.csp.resourceDomains).toEqual(["https://sceneview.github.io"]);
  });
  it("declares blob and data fetches for converted GLBs and embedded textures in both CSP spellings", () => {
    const meta = readWidgetResource(WIDGET_3D_VIEWER_URI)?._meta;
    expect(meta?.ui.csp.connectDomains).toContain("blob:");
    expect(meta?.ui.csp.connectDomains).toContain("data:");
    expect(meta?.["openai/widgetCSP"].connect_domains).toEqual(meta?.ui.csp.connectDomains);
  });
});

describe("model presentation", () => {
  it.each([800, 390])("fits the Astronaut at about 80 percent at %s px", async (width) => {
    // Bounds and visible crown/sole vertices from modelviewer.dev's Astronaut.glb,
    // translated to a zero minimum like this harness's asset. No model download in CI.
    const h = harness({
      cameraMath: true,
      width,
      height: 460,
      size: [1.1196980476, 2.0108470917, 0.7235120237],
    });
    try {
      h.deliver();
      await flush();
      const orbit = h.primary.getCameraOrbit();
      const elevation = Math.atan2(orbit.height - orbit.target[1], orbit.radius);
      const distance = Math.hypot(orbit.radius, orbit.height - orbit.target[1]);
      const projectY = (p: number[]) => {
        const [x, y, z] = p.map((v, axis) => v - orbit.target[axis]);
        const horizontalDepth = x * Math.sin(orbit.angle) + z * Math.cos(orbit.angle);
        return (
          (y * Math.cos(elevation) - horizontalDepth * Math.sin(elevation)) /
          ((distance - horizontalDepth * Math.cos(elevation) - y * Math.sin(elevation)) *
            Math.tan(Math.PI / 12))
        );
      };
      const fill =
        (projectY([0.5598490238, 2.0108470917, 0.493486017]) -
          projectY([0.7534195185, 0, 0.6240090132])) /
        2;
      expect(fill).toBeGreaterThan(0.79);
      expect(fill).toBeLessThan(0.83);
      expect(h.elements.get("real-size").textContent).toBe("≈ 2 m tall");
      expect(orbit.angle).toBe(0.785);
      expect(h.primaryFactory.mock.calls[0][2]?.studio.pool).toBe("#232d40");
    } finally {
      h.close();
    }
  });
  it.each([800, 390])(
    "limits a wide model to about 80 percent width at %s px, including after resize",
    async (width) => {
      const h = harness({ cameraMath: true, width, height: 460, size: [4, 0.5, 1] });
      try {
        h.deliver();
        await flush();
        for (const stageWidth of [width, 320]) {
          h.elements.get("canvas").clientWidth = stageWidth;
          for (const observer of h.observers) observer.callback();
          const orbit = h.primary.getCameraOrbit();
          const elevation = Math.atan2(orbit.height - orbit.target[1], orbit.radius);
          const distance = Math.hypot(orbit.radius, orbit.height - orbit.target[1]);
          let fill = 0;
          // Independently project every box corner through a complete turntable orbit.
          for (let angle = 0; angle < Math.PI * 2; angle += Math.PI / 90) {
            for (const x of [-2, 2])
              for (const y of [-0.25, 0.25])
                for (const z of [-0.5, 0.5]) {
                  const depth =
                    distance -
                    (x * Math.sin(angle) + z * Math.cos(angle)) * Math.cos(elevation) -
                    y * Math.sin(elevation);
                  fill = Math.max(
                    fill,
                    Math.abs(x * Math.cos(angle) - z * Math.sin(angle)) /
                      ((depth * Math.tan(Math.PI / 12) * stageWidth) / 460)
                  );
                }
          }
          expect(fill).toBeGreaterThan(0.77);
          expect(fill).toBeLessThan(0.82);
        }
      } finally {
        h.close();
      }
    }
  );
  it("frames at 80 percent, raises the camera slightly and shows the original metre scale", async () => {
    const h = harness();
    try {
      h.deliver();
      expect(h.elements.get("canvas").style.opacity).toBe("0");
      expect(h.elements.get("loader").hidden).toBe(false);
      await flush();
      expect(h.primary.frameModel).toHaveBeenCalledWith({ fill: 0.9 });
      expect(h.primary.setCameraOrbit).toHaveBeenCalledWith({ radius: 3, height: 1.26 });
      expect(h.elements.get("real-size").textContent).toBe("≈ 1.8 m tall");
      expect(h.elements.get("real-size").hidden).toBe(false);
      expect(h.elements.get("canvas").style.opacity).toBe("1");
      expect(h.elements.get("loader").hidden).toBe(true);
    } finally {
      h.close();
    }
    expect(h.observers.every((observer) => observer.disconnect.mock.calls.length === 1)).toBe(true);
  });
  it.each([
    [100, 180, 50],
    [0, 1.8, 0.5],
    [1, NaN, 1],
    [0.001, 0.001, 0.001],
  ])("hides implausible scale %j", async (...size) => {
    const h = harness({ size });
    try {
      h.deliver();
      await flush();
      expect(h.elements.get("real-size").hidden).toBe(true);
    } finally {
      h.close();
    }
  });
  it("uses centimetres for small models and also frames the fallback", async () => {
    const h = harness({ wasm: false, size: [0.2, 0.35, 0.1] });
    try {
      h.deliver();
      await flush();
      expect(h.elements.get("real-size").textContent).toBe("≈ 35 cm tall");
      expect(h.fallback.frameModel).toHaveBeenCalledWith({ fill: 0.8 });
    } finally {
      h.close();
    }
  });
  it.each(["pointerdown", "wheel", "keydown"])(
    "stops rotation permanently after %s, including while loading",
    async (event) => {
      const h = harness();
      try {
        h.deliver();
        h.interact(event);
        await flush();
        expect(h.primary.setAutoRotate).toHaveBeenLastCalledWith(false);
        const frames = h.primary.frameModel.mock.calls.length;
        for (const observer of h.observers) observer.callback();
        expect(h.primary.frameModel).toHaveBeenCalledTimes(frames);
        h.deliver({ ...model, title: "Updated" });
        await flush();
        expect(h.primary.setAutoRotate).toHaveBeenLastCalledWith(false);
      } finally {
        h.close();
      }
    }
  );
  it.each([true, false])("stops an already-running turntable (WASM=%s)", async (wasm) => {
    const h = harness({ wasm });
    try {
      h.deliver();
      await flush();
      const renderer = wasm ? h.primary : h.fallback;
      expect(renderer.setAutoRotate).toHaveBeenLastCalledWith(true);
      h.interact("pointerdown");
      expect(renderer.setAutoRotate).toHaveBeenLastCalledWith(false);
      h.deliver();
      await flush();
      expect(renderer.setAutoRotate).toHaveBeenLastCalledWith(false);
    } finally {
      h.close();
    }
  });
  it("honours reduced motion", async () => {
    const h = harness({ reducedMotion: true });
    try {
      h.deliver();
      await flush();
      expect(h.primary.setAutoRotate).toHaveBeenCalledWith(false);
    } finally {
      h.close();
    }
  });
  it("survives a missing fallback website asset and retains the phone link", async () => {
    const h = harness({ wasm: false, fallbackDownloadError: true });
    try {
      h.deliver();
      await flush();
      expect(h.fallbackFactory).not.toHaveBeenCalled();
      expect(h.elements.get("error").hidden).toBe(false);
      expect(h.elements.get("ar").hidden).toBe(false);
    } finally {
      h.close();
    }
  });
});

const space = {
  label: "Desk",
  sizeCm: [80, 75, 40],
  placement: "floor",
  dimensionSource: "user",
  url: "https://sceneview.github.io/open?demo=spatial-answer&answer=example",
};
describe("show_in_space drawing and host lifecycle", () => {
  it.each([
    { sizeCm: [80, 75, 40], placement: "floor" },
    { sizeCm: [220, 85, 95], placement: "floor" },
    { sizeCm: [30, 20, 25], placement: "table" },
  ])("keeps the box and table clear of the desktop QR for %j", async (data) => {
    const h = harness({ space: true, wide: true, width: 800, height: 460 });
    try {
      h.deliver({ ...space, ...data });
      await flush();
      const objectPaths = h.paths.filter(
        (p) => p.fill?.startsWith("rgba(0,91,193") || p.fill === "#263348" || p.stroke === "#64748b"
      );
      for (const path of objectPaths) {
        expect(
          Math.max(...path.points.map((p) => p[0])) < 508 ||
            Math.max(...path.points.map((p) => p[1])) < 316
        ).toBe(true);
      }
    } finally {
      h.close();
    }
  });
  it.each(["floor", "table"])(
    "grounds the person beside the width line at the box's scale on %s",
    async (placement) => {
      const h = harness({ space: true, width: 800, height: 460 });
      try {
        h.deliver({ ...space, placement });
        await flush();
        const body =
          h.paths.find((p) => p.stroke === "#8d9bb4" && p.points.length === 17)?.points ?? [];
        expect(body).toHaveLength(17);
        const feet = [6, 7, 9, 10].map((index) => body[index]);
        const floorLine =
          h.paths.find(
            (p) =>
              p.stroke === "#64748b" &&
              p.points.length === 2 &&
              Math.abs(p.points[0][1] - p.points[1][1]) < 1e-6
          )?.points ?? [];
        expect(floorLine).toHaveLength(2);
        for (const p of feet) expect(p[1]).toBeCloseTo(floorLine[0][1], 8);
        const widthLine =
          h.paths
            .find((p) => p.stroke === "#adc6ff" && p.points.length === 6)
            ?.points.slice(0, 2) ?? [];
        expect(widthLine).toHaveLength(2);
        expect(Math.max(...body.map((p) => p[0]))).toBeLessThan(
          Math.min(...widthLine.map((p) => p[0]))
        );
        const front = h.paths.find((p) => p.fill === "rgba(0,91,193,0.32)")?.points ?? [];
        expect(front).toHaveLength(4);
        const pixelsPerMetre = (front[0][1] - front[3][1]) / 0.75;
        const head = h.context.ellipse.mock.calls[0];
        expect((feet[0][1] - (head[1] - head[3])) / pixelsPerMetre).toBeCloseTo(1.75, 8);
        expect((feet[0][1] - front[0][1]) / pixelsPerMetre).toBeCloseTo(
          placement === "table" ? 0.75 : 0,
          8
        );
        const tabletop = h.paths.find((p) => p.fill === "#263348");
        expect(!!tabletop).toBe(placement === "table");
        if (tabletop) {
          const legs = h.paths.filter(
            (p) =>
              p.stroke === "#64748b" && p.points.length === 2 && p.points[0][1] !== p.points[1][1]
          );
          expect(legs).toHaveLength(4);
          for (const leg of legs) {
            expect((leg.points[0][1] - leg.points[1][1]) / pixelsPerMetre).toBeCloseTo(0.75, 8);
          }
        }
      } finally {
        h.close();
      }
    }
  );
  it.each([false, true])(
    "keeps the QR desktop-only through width changes (space=%s)",
    async (isSpace) => {
      const h = harness({ space: isSpace });
      try {
        h.deliver(isSpace ? space : model);
        await flush();
        expect(h.scripts.some((url) => url.includes("qrcode-vendor"))).toBe(false);
        expect(h.elements.get("ar").classList.contains("wide")).toBe(false);
        h.setWide(true);
        await flush();
        expect(h.elements.get("ar").classList.contains("has-qr")).toBe(true);
        h.setWide(false);
        expect(h.elements.get("ar").classList.contains("wide")).toBe(false);
        expect(h.elements.get("ar").hidden).toBe(false);
      } finally {
        h.close();
      }
    }
  );
  it.each(["floor", "table"])(
    "draws dimensions and the human reference on %s without renderer downloads",
    async (placement) => {
      const h = harness({ space: true });
      try {
        h.deliver({ ...space, placement });
        await flush();
        expect(h.scripts).toEqual([]);
        expect(h.wasm).not.toHaveBeenCalled();
        expect(h.primaryFactory).not.toHaveBeenCalled();
        expect(h.fallbackFactory).not.toHaveBeenCalled();
        expect(h.context.fillText.mock.calls.map((call) => call[0])).toEqual([
          "1.75 m",
          "80 cm wide",
          "75 cm tall",
          "40 cm deep",
        ]);
        expect(h.elements.get("engine").textContent).toBe(
          placement === "table" ? "Table · 75 cm reference" : "Floor placement"
        );
        expect(h.elements.get("ar-open").href).toBe(space.url);
        expect(h.elements.get("html").dataset.renderer).toBe("canvas2d");
        expect(h.elements.get("canvas").style.opacity).toBe("1");
      } finally {
        h.close();
      }
    }
  );
  it("labels estimates, treats labels as text, and redraws the latest dimensions on resize", async () => {
    const h = harness({ space: true });
    try {
      h.deliver({ ...space, dimensionSource: "estimated", label: "<img src=x onerror=alert(1)>" });
      expect(h.elements.get("title").textContent).toBe("<img src=x onerror=alert(1)>");
      expect(h.elements.get("real-size").textContent).toBe("80 × 75 × 40 cm · estimated size");
      h.deliver({ ...space, sizeCm: [120, 90, 60] });
      h.context.fillText.mockClear();
      for (const observer of h.observers) observer.callback();
      expect(h.context.fillText.mock.calls.map((call) => call[0])).toContain("120 cm wide");
      expect(h.elements.get("real-size").textContent).toBe("120 × 90 × 60 cm");
    } finally {
      h.close();
    }
    expect(h.observers.every((observer) => observer.disconnect.mock.calls.length === 1)).toBe(true);
  });
  it("draws a QR only on desktop and applies host themes", async () => {
    const h = harness({ space: true, wide: true });
    try {
      h.init();
      h.deliver(space);
      await flush();
      expect(h.scripts).toEqual([expect.stringContaining("/qrcode-vendor.js?v=")]);
      expect(h.elements.get("ar").classList.contains("has-qr")).toBe(true);
      expect(h.elements.get("html").setAttribute).toHaveBeenCalledWith("data-theme", "dark");
      h.send("ui/notifications/host-context-changed", { theme: "light" });
      expect(h.elements.get("html").setAttribute).toHaveBeenLastCalledWith("data-theme", "light");
    } finally {
      h.close();
    }
  });
  it.each([
    { ...space, sizeCm: [80, 0, 40] },
    { ...space, sizeCm: [80, 75] },
    { ...space, sizeCm: [80, "75", 40] },
    { ...space, url: "javascript:alert(1)" },
    { ...space, placement: "wall" },
    { ...space, url: "https://example.com/open" },
  ])("ignores malformed host payload %j", (data) => {
    const h = harness({ space: true });
    try {
      h.deliver(data);
      expect(h.context.fillText).not.toHaveBeenCalled();
    } finally {
      h.close();
    }
  });
  it("accepts ChatGPT toolOutput at startup", () => {
    const h = harness({ space: true, openai: { toolOutput: space, theme: "light" } });
    try {
      expect(h.elements.get("ar-open").href).toBe(space.url);
      expect(h.context.fillText).toHaveBeenCalled();
    } finally {
      h.close();
    }
  });
});
