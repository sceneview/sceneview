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

  /** Release Filament resources. The viewer is unusable after this. */
  dispose(): void;
}

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
