/** Protocol and renderer lifecycle tests. These do not claim to test GPU drawing. */
import { runInNewContext } from "node:vm";
import { describe, expect, it, vi } from "vitest";
import { WIDGET_FALLBACK_JS } from "./vendor/widget-fallback.js";
import {
  readWidgetResource,
  WIDGET_3D_VIEWER_HTML,
  WIDGET_3D_VIEWER_URI,
  WIDGET_UI_META,
} from "./widgets.js";

const boot = [...WIDGET_3D_VIEWER_HTML.matchAll(/<script>([\s\S]*?)<\/script>/g)][0][1];
const model = {
  modelUrl: "https://modelviewer.dev/shared-assets/models/Astronaut.glb",
  title: "Astronaut",
};
const flush = async () => {
  for (let i = 0; i < 40; i++) await Promise.resolve();
};

function harness(
  options: { wasm?: boolean; primaryError?: boolean; fallbackError?: boolean } = {}
) {
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
  const primary = { loadModel: vi.fn(async () => {}), dispose: vi.fn(), setAutoRotate: vi.fn() };
  const fallback = { loadModel: vi.fn(async () => {}), dispose: vi.fn(), setAutoRotate: vi.fn() };
  const primaryFactory = vi.fn(async () => {
    if (options.primaryError) throw new Error("Filament initialization failed");
    return primary;
  });
  const fallbackFactory = vi.fn(async () => {
    if (options.fallbackError) throw new Error("WebGL2 is unavailable");
    return fallback;
  });
  const parent = { postMessage: (message: unknown) => messages.push(message) };
  const window: any = {
    parent,
    addEventListener: (name: string, listener: (event: any) => void) => {
      listeners[name] = listener;
    },
    matchMedia: () => ({ matches: false, addEventListener: vi.fn() }),
    SceneViewFallback: { modelViewer: fallbackFactory },
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
  runInNewContext(boot, {
    window,
    document,
    location: { search: "" },
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
    messages,
    scripts,
    primary,
    fallback,
    primaryFactory,
    fallbackFactory,
    wasm,
    send,
    deliver: (data = model) => send("ui/notifications/tool-result", { structuredContent: data }),
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
      expect(h.scripts).toEqual([]);
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
  it("ships the fallback in the resource, with a license and no invented WASM permission", () => {
    expect(WIDGET_3D_VIEWER_HTML).toContain('type="text/plain" id="fallback-source"');
    expect(WIDGET_3D_VIEWER_HTML).toContain("Permission is hereby granted");
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
