// TypeScript declarations for `sceneview-web` — the Kotlin/JS browser
// build of SceneView (Filament.js under the hood).
//
// Hand-written because Kotlin/JS 2.x doesn't emit a `.d.ts` for plain
// `@JsExport` classes by default. Shape mirrors the runtime surface
// registered on `window.sceneview` by Main.kt and the `SceneViewJS`
// class (registered as `SceneViewer`). Keep this file in sync with
// `src/jsMain/kotlin/io/github/sceneview/web/SceneViewJS.kt` and
// `src/jsMain/kotlin/io/github/sceneview/web/Main.kt` — they're a
// small, stable surface so a hand-written declaration costs less than
// wiring `kotlin.js.dtsGenerator`. See #946.
//
// Sync is MACHINE-CHECKED since #2736: `.claude/scripts/check-web-dts.sh`
// (quality-gate + repo-hygiene CI) fails when the `api[...]` namespace
// registry in Main.kt or the public members of SceneViewJS / NodeHandle /
// SceneViewHaptic diverge from these declarations, in either direction.

export as namespace sceneview;

/** Compile-time version string (e.g. `"4.14.0"`). Same value the
 *  Kotlin sources advertise via `SCENEVIEW_VERSION`. */
export const version: string;

/** Configurable factory options for {@link createViewerFull}. Each
 *  field is `Double` on the Kotlin side and `number` here. */
export interface ViewerOptions {
  /** Start auto-rotating the camera around the model. Default `true`. */
  autoRotate?: boolean;
  /** Enable orbit/pinch camera controls. Default `true`. */
  cameraControls?: boolean;
  /** Initial camera position (world space). Default `(0, 1, 3)`. */
  cameraX?: number;
  cameraY?: number;
  cameraZ?: number;
  /** Vertical field of view in degrees. Default `45`. */
  fov?: number;
  /** Main-light intensity in lux. Default `60_000`. */
  lightIntensity?: number;
}

/**
 * An opaque handle to a retained scene-graph node — the first `@JsExport`
 * node surface of `sceneview-web` (issue #2024, slice 3 / P4). Obtain one from
 * a {@link SceneViewer} node factory (`addNode` / `addCubeNode` /
 * `addSphereNode` / `addLightNode` / `addModelNode`) and use it to address the
 * node after creation — move, rotate, scale, show/hide, re-parent, or destroy
 * it. Method names mirror the Kotlin / Android `Node` API 1:1.
 *
 * Opaque by design: the wrapped Kotlin node and its Filament internals are not
 * exposed. This is a minimal first slice — richer surfaces (a transform
 * object, collision, gestures) are additive later and won't break these.
 */
export interface NodeHandle {
  /** Local position in the parent's space (world space for a root node).
   *  +x right, +y up, -z forward — Android `Node.position` axes. */
  setPosition(x: number, y: number, z: number): void;

  /** Local rotation as Euler angles in **degrees** (ZYX order) — Android
   *  `Node.rotation`. */
  setRotation(x: number, y: number, z: number): void;

  /** Non-uniform local scale per axis. Prefer {@link setScaleUniform} for the
   *  common uniform case. */
  setScale(x: number, y: number, z: number): void;

  /** Uniform local scale on all three axes — the common case. */
  setScaleUniform(s: number): void;

  /** Show/hide the node's renderable content. For a model/geometry node this
   *  adds/removes its asset entities from the scene, so it actually
   *  disappears/reappears. An empty pivot node only flips the stored flag. */
  setVisible(v: boolean): void;

  /** Whether the node is currently marked visible. */
  readonly visible: boolean;

  /** Parent `child` under this node (child keeps its local transform, so its
   *  world transform recomposes under this node). */
  addChild(child: NodeHandle): void;

  /** Detach `child` from this node (no-op if it is not a child). */
  removeChild(child: NodeHandle): void;

  /** The node's world-space position as a fresh `[x, y, z]` array, composed
   *  through the parent chain. */
  getWorldPosition(): [number, number, number];

  /** Remove the node (and its subtree) from the graph and free its Filament
   *  entity. Idempotent; the handle must not be used afterwards. */
  destroy(): void;
}

/**
 * The viewer instance returned by every `sceneview.*` factory.
 *
 * Method names mirror Kotlin's `SceneViewJS` 1:1. All numbers are
 * standard JS `number`s; the original `Double` / `Int` distinction
 * doesn't surface in the JS runtime.
 */
export interface SceneViewer {
  /** Load a glTF / GLB model from a URL. Resolves with the URL when
   *  the model has finished decoding. Rejects if the viewer is
   *  uninitialised or the network fetch fails. */
  loadModel(url: string): Promise<string>;

  /** Apply environment-map lighting (IBL only). `iblUrl` points at a
   *  Filament KTX1 environment file. */
  setEnvironment(iblUrl: string): void;

  /** Apply environment-map lighting AND the matching skybox. */
  setEnvironmentWithSkybox(iblUrl: string, skyboxUrl: string): void;

  /** Set camera orbit in spherical coordinates.
   *  @param theta horizontal angle, radians
   *  @param phi vertical angle, radians
   *  @param distance metres from target */
  setCameraOrbit(theta: number, phi: number, distance: number): void;

  /** Look-at point in world space. */
  setCameraTarget(x: number, y: number, z: number): void;

  /** Turn auto-rotation on/off at runtime. */
  setAutoRotate(enabled: boolean): void;

  /** Auto-rotate angular speed in **radians per frame** — the controller
   *  advances the orbit angle by this amount once per `requestAnimationFrame`
   *  tick. At 60 fps the default is `30° / 60 ≈ 0.00873` rad/frame. */
  setAutoRotateSpeed(speed: number): void;

  /** Constrain pinch-zoom to `[min, max]` metres from the target. */
  setZoomLimits(min: number, max: number): void;

  /** Begin the render loop. Idempotent. */
  startRendering(): void;

  /** Stop the render loop. Idempotent. */
  stopRendering(): void;

  /** Resize the underlying canvas. */
  resize(width: number, height: number): void;

  /** Clear-colour for the framebuffer. Components are `0..1`. */
  setBackgroundColor(r: number, g: number, b: number, a: number): void;

  /** Frame the camera so every loaded model is fully visible. */
  fitToModels(): void;

  /** Toggle library-level auto-centring of loaded content. When enabled
   *  (the default), content is translated so its bounding box is centred
   *  on the orbit-camera target the first frame it finishes loading.
   *  Pass `false` for narrative scenes with intentional off-centre
   *  placement. Port of iOS `autoCenterContent` (#1026). */
  setAutoCenterContent(enabled: boolean): void;

  // --- Node scene-graph (#2024 slice 3 / P4) --------------------------------
  // The first exported node surface: create a node, keep the returned
  // NodeHandle, and address it after `create()`. Node-created content is
  // framed via its own node transform (excluded from library auto-centring).

  /** Add an empty pivot node and return a handle to it. Parent other nodes
   *  under it (`handle.addChild`) to move them together. */
  addNode(): NodeHandle;

  /** Load a glTF/GLB model as a node. The Promise resolves with the handle
   *  once the model has finished loading (its content already in the scene).
   *  Rejects if the viewer is uninitialised. */
  addModelNode(url: string): Promise<NodeHandle>;

  /** Load a 3D Gaussian Splatting capture (`.ply` INRIA or `.spz` Niantic) as a
   *  node and return its handle. The Promise resolves once the splat cloud has
   *  been fetched, parsed, and rendered into the scene. Rejects on a fetch/parse
   *  failure or an uninitialised viewer (#2646 P2). */
  addSplatNode(url: string): Promise<NodeHandle>;

  /** Add a cube primitive of the given edge size and return its handle. */
  addCubeNode(size: number): NodeHandle;

  /** Add a sphere primitive of the given radius and return its handle. */
  addSphereNode(radius: number): NodeHandle;

  /** Add a light node and return its handle. `type` is `"directional"`,
   *  `"point"`, or `"spot"`. */
  addLightNode(type: 'directional' | 'point' | 'spot'): NodeHandle;

  /** Remove a node (and its subtree) and free its Filament entity —
   *  equivalent to `handle.destroy()`. */
  removeNode(node: NodeHandle): void;

  /** Release Filament resources. The viewer is unusable after this. */
  dispose(): void;
}

/**
 * Cross-platform haptic facade (mirrors Android `SceneViewHaptic` and iOS
 * `SceneViewSwift.SceneViewHaptic`), backed by the Web Vibration API.
 * Browsers without `navigator.vibrate` (Safari, all iOS browsers) no-op
 * silently. Available as `sceneview.haptic.*`.
 */
export interface SceneViewHaptic {
  /** Light tap (10 ms) — taps, button presses, selections. */
  light(): void;
  /** Medium tap (20 ms) — placing an anchor, mode-change confirmation. */
  medium(): void;
  /** Heavy tap (40 ms) — boundary hit, drag-lock engagement. */
  heavy(): void;
  /** Success notification pattern (`[10, 50, 20]`). */
  success(): void;
  /** Warning notification pattern (`[30, 30, 30]`). */
  warning(): void;
  /** Error notification pattern (`[50, 30, 50]`). */
  error(): void;
  /** Selection tick (5 ms) — drag tick, picker scroll. */
  selection(): void;
  /** Continuous vibration for `durationMs` milliseconds. `intensity` is
   *  accepted for cross-platform API parity (Android/iOS) but **ignored** —
   *  the Web Vibration API exposes durations only, no amplitude. */
  continuous(intensity: number, durationMs: number): void;
  /** Custom vibrate/pause pattern in milliseconds, alternating on/off
   *  starting with on (e.g. `[100, 50, 100]`). Some browsers cap total
   *  duration or require a user-gesture handler. */
  pattern(durationsMs: number[] | Int32Array): void;
}

/** Shared haptic facade instance — `sceneview.haptic.light()` etc. */
export const haptic: SceneViewHaptic;

/** Create a viewer attached to the canvas with the given DOM id.
 *  Defaults: `autoRotate=true`, `cameraControls=true`. */
export function createViewer(canvasId: string): Promise<SceneViewer>;

/** Like {@link createViewer} with explicit auto-rotate override. */
export function createViewerAutoRotate(canvasId: string, autoRotate: boolean): Promise<SceneViewer>;

/** Full factory — sets every option in one call. See {@link ViewerOptions}.
 *  Note: this Kotlin signature takes positional args so the type
 *  reflects that exactly. Prefer object-spread syntax at the call site
 *  if you want named options. */
export function createViewerFull(
  canvasId: string,
  autoRotate: boolean,
  cameraControls: boolean,
  cameraX: number,
  cameraY: number,
  cameraZ: number,
  fov: number,
  lightIntensity: number
): Promise<SceneViewer>;

/** One-call helper: create a viewer AND load a model. */
export function modelViewer(canvasId: string, modelUrl: string): Promise<SceneViewer>;

/** Like {@link modelViewer} with explicit auto-rotate override. */
export function modelViewerAutoRotate(
  canvasId: string,
  modelUrl: string,
  autoRotate: boolean
): Promise<SceneViewer>;
