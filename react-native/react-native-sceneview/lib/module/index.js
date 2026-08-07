// `tsconfig.json` sets `"jsx": "react"` — the CLASSIC runtime — so every JSX
// element below compiles to `React.createElement(...)` (verified in the
// published `lib/commonjs/index.js`). Biome's `useImportType` only sees the
// `React.FC` type annotations, not the JSX lowering, so it offers a "safe fix"
// that would erase this import and break the shipped bundle at runtime.
// biome-ignore lint/style/useImportType: React is a VALUE import — see above.
import React from "react";
import { NativeModules, Platform, requireNativeComponent, StyleSheet, Text, View } from "react-native";

// ---------------------------------------------------------------------------
// Node type interfaces
// ---------------------------------------------------------------------------

/** A 3D model loaded from a .glb / .gltf file. */

/**
 * A procedural geometry node (box, sphere, cylinder, plane).
 *
 * Platform support:
 * - **Android**: fully rendered.
 * - **iOS**: acknowledged but not yet rendered — the RealityKit bridge does
 *   not currently map procedural geometry nodes. Tracked under the
 *   cross-platform bridge-parity umbrella (#909). Use `modelNodes` on iOS.
 */

/**
 * A light source in the scene.
 *
 * Platform support:
 * - **Android**: fully rendered.
 * - **iOS**: acknowledged but not yet rendered — the RealityKit bridge does
 *   not currently map declarative light nodes. Tracked under the
 *   cross-platform bridge-parity umbrella (#909).
 */

// ---------------------------------------------------------------------------
// Event payloads
// ---------------------------------------------------------------------------

// ---------------------------------------------------------------------------
// Props
// ---------------------------------------------------------------------------

/**
 * Camera interaction mode for a {@link SceneView} (v4.3.0).
 *
 * Platform support:
 * - **iOS**: all three modes are wired through `.cameraControls(_:)`.
 * - **Android**: `'orbit'` is the default; `'pan'` / `'firstPerson'` fall
 *   back to orbit (the per-mode switch is an iOS-first v4.3.0 addition —
 *   the Android side is tracked in issue #1051).
 */

// ---------------------------------------------------------------------------
// Native components (only available on Android and iOS)
// ---------------------------------------------------------------------------

const isNativeAvailable = Platform.OS === "android" || Platform.OS === "ios";
const NativeSceneView = isNativeAvailable ? requireNativeComponent("RNSceneView") : null;
const NativeARSceneView = isNativeAvailable ? requireNativeComponent("RNARSceneView") : null;

// ---------------------------------------------------------------------------
// Fallback for unsupported platforms
// ---------------------------------------------------------------------------

const UnsupportedView = ({
  name
}) => /*#__PURE__*/React.createElement(View, {
  style: fallbackStyles.container
}, /*#__PURE__*/React.createElement(Text, {
  style: fallbackStyles.text
}, name, " is not supported on this platform"));
const fallbackStyles = StyleSheet.create({
  container: {
    flex: 1,
    justifyContent: "center",
    alignItems: "center",
    backgroundColor: "#1a1a2e"
  },
  text: {
    color: "#aaa",
    fontSize: 16
  }
});

// ---------------------------------------------------------------------------
// Public components
// ---------------------------------------------------------------------------

/**
 * A 3D scene rendered with Filament (Android) or RealityKit (iOS).
 *
 * ```tsx
 * <SceneView
 *   environment="environments/studio.hdr"
 *   modelNodes={[{ src: "models/robot.glb" }]}
 * />
 * ```
 */
export const SceneView = props => {
  if (!NativeSceneView) {
    return /*#__PURE__*/React.createElement(UnsupportedView, {
      name: "SceneView"
    });
  }
  return /*#__PURE__*/React.createElement(NativeSceneView, props);
};

/**
 * An augmented-reality scene using ARCore (Android) or ARKit (iOS).
 *
 * ```tsx
 * <ARSceneView
 *   planeDetection
 *   modelNodes={[{ src: "models/chair.glb", position: [0, 0, -1] }]}
 * />
 * ```
 */
export const ARSceneView = props => {
  if (!NativeARSceneView) {
    return /*#__PURE__*/React.createElement(UnsupportedView, {
      name: "ARSceneView"
    });
  }
  return /*#__PURE__*/React.createElement(NativeARSceneView, props);
};

// ---------------------------------------------------------------------------
// AR recording (v4.3.0 — iOS via ReplayKit, see issue #1053)
// ---------------------------------------------------------------------------

/** Native module backing {@link ARRecorder}. Present only on iOS. */

const NativeARRecorder = NativeModules.RNARRecorder;

/**
 * Records an AR session to a video file (v4.3.0).
 *
 * iOS port of SceneViewSwift's `ARRecorder` — record-only via ReplayKit,
 * producing a QuickTime `.mov`.
 *
 * ```ts
 * const recorder = new ARRecorder();
 * await recorder.start();
 * // ... later ...
 * const path = await recorder.stop();
 * await recorder.saveToPhotoLibrary(path);
 * ```
 *
 * Platform support:
 * - **iOS**: full support via `RPScreenRecorder`.
 * - **Android**: not yet bridged. ARCore session recording produces a
 *   replayable dataset (not a video) and needs deeper `Session`/`Frame`
 *   access than the Fabric bridge exposes. Every method rejects with an
 *   error on Android until issue #1051 lands the Android side.
 */
export class ARRecorder {
  /** `true` when {@link ARRecorder} is supported on the current platform. */
  static get isSupported() {
    return Platform.OS === "ios" && NativeARRecorder != null;
  }
  rejectUnsupported() {
    return Promise.reject(new Error("ARRecorder is currently only supported on iOS. Android AR session " + "recording is tracked in issue #1051."));
  }

  /** Starts an AR session recording. */
  start() {
    if (!ARRecorder.isSupported || !NativeARRecorder) {
      return this.rejectUnsupported();
    }
    return NativeARRecorder.start();
  }

  /**
   * Stops the in-progress recording and resolves with the path of the
   * written `.mov` file.
   *
   * @param outputPath optional destination path; when omitted the native
   *   side picks a temp location.
   */
  stop(outputPath) {
    if (!ARRecorder.isSupported || !NativeARRecorder) {
      return this.rejectUnsupported();
    }
    return NativeARRecorder.stop(outputPath ?? null);
  }

  /** Saves a recorded `.mov` file to the device's photo library (iOS). */
  saveToPhotoLibrary(movPath) {
    if (!ARRecorder.isSupported || !NativeARRecorder) {
      return this.rejectUnsupported();
    }
    return NativeARRecorder.saveToPhotoLibrary(movPath);
  }
}
//# sourceMappingURL=index.js.map