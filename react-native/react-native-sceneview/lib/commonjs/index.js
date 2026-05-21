"use strict";

Object.defineProperty(exports, "__esModule", {
  value: true
});
exports.SceneView = exports.ARSceneView = exports.ARRecorder = void 0;
var _react = _interopRequireDefault(require("react"));
var _reactNative = require("react-native");
function _interopRequireDefault(e) { return e && e.__esModule ? e : { default: e }; }
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

const isNativeAvailable = _reactNative.Platform.OS === 'android' || _reactNative.Platform.OS === 'ios';
const NativeSceneView = isNativeAvailable ? (0, _reactNative.requireNativeComponent)('RNSceneView') : null;
const NativeARSceneView = isNativeAvailable ? (0, _reactNative.requireNativeComponent)('RNARSceneView') : null;

// ---------------------------------------------------------------------------
// Fallback for unsupported platforms
// ---------------------------------------------------------------------------

const UnsupportedView = ({
  name
}) => /*#__PURE__*/_react.default.createElement(_reactNative.View, {
  style: fallbackStyles.container
}, /*#__PURE__*/_react.default.createElement(_reactNative.Text, {
  style: fallbackStyles.text
}, name, " is not supported on this platform"));
const fallbackStyles = _reactNative.StyleSheet.create({
  container: {
    flex: 1,
    justifyContent: 'center',
    alignItems: 'center',
    backgroundColor: '#1a1a2e'
  },
  text: {
    color: '#aaa',
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
const SceneView = props => {
  if (!NativeSceneView) {
    return /*#__PURE__*/_react.default.createElement(UnsupportedView, {
      name: "SceneView"
    });
  }
  return /*#__PURE__*/_react.default.createElement(NativeSceneView, props);
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
exports.SceneView = SceneView;
const ARSceneView = props => {
  if (!NativeARSceneView) {
    return /*#__PURE__*/_react.default.createElement(UnsupportedView, {
      name: "ARSceneView"
    });
  }
  return /*#__PURE__*/_react.default.createElement(NativeARSceneView, props);
};

// ---------------------------------------------------------------------------
// AR recording (v4.3.0 — iOS via ReplayKit, see issue #1053)
// ---------------------------------------------------------------------------

/** Native module backing {@link ARRecorder}. Present only on iOS. */
exports.ARSceneView = ARSceneView;
const NativeARRecorder = _reactNative.NativeModules.RNARRecorder;

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
class ARRecorder {
  /** `true` when {@link ARRecorder} is supported on the current platform. */
  static get isSupported() {
    return _reactNative.Platform.OS === 'ios' && NativeARRecorder != null;
  }
  rejectUnsupported() {
    return Promise.reject(new Error('ARRecorder is currently only supported on iOS. Android AR session ' + 'recording is tracked in issue #1051.'));
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
exports.ARRecorder = ARRecorder;
//# sourceMappingURL=index.js.map