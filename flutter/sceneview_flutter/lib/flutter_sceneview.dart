/// Flutter plugin for SceneView -- 3D and AR scenes.
///
/// Uses native platform views:
/// - Android: SceneView (Filament renderer via Jetpack Compose)
/// - iOS: SceneViewSwift (RealityKit renderer via SwiftUI)
library flutter_sceneview;

import 'dart:async';
import 'package:flutter/foundation.dart';
import 'package:flutter/gestures.dart';
import 'package:flutter/material.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter/services.dart';

// ---------------------------------------------------------------------------
// Camera controls (v4.3.0 — iOS-first, see issue #1053)
// ---------------------------------------------------------------------------

/// Camera interaction mode for a [SceneView].
///
/// Mirrors SceneViewSwift's `CameraControlMode` and Android's camera
/// manipulator modes.
///
/// Platform support:
/// - **iOS**: all three modes are wired through `.cameraControls(_:)`.
/// - **Android**: [orbit] is the default Compose behaviour; [pan] and
///   [firstPerson] currently fall back to orbit (the per-mode switch is
///   an iOS-first v4.3.0 addition — tracked for the Android side in #1051).
enum CameraControlMode {
  /// Orbit around a target point. Drag rotates; pinch dollies in/out.
  orbit,

  /// Pan the camera in the view plane. Drag translates the target.
  pan,

  /// First-person look-around. Drag rotates; pinch adjusts field of view.
  firstPerson,
}

/// Wire name sent across the method/platform channel for a [CameraControlMode].
String _cameraControlModeName(CameraControlMode mode) {
  switch (mode) {
    case CameraControlMode.orbit:
      return 'orbit';
    case CameraControlMode.pan:
      return 'pan';
    case CameraControlMode.firstPerson:
      return 'firstPerson';
  }
}

// ---------------------------------------------------------------------------
// AR recording (v4.3.0 — iOS via ReplayKit, see issue #1053)
// ---------------------------------------------------------------------------

/// Lifecycle state of an [ARRecorder].
enum ARRecorderState {
  /// No recording in progress.
  idle,

  /// A recording is actively capturing.
  recording,

  /// The last start/stop call failed. See [ARRecorder.lastError].
  error,
}

/// Records an AR session to a video file.
///
/// iOS port of SceneViewSwift's `ARRecorder` (record-only via ReplayKit —
/// produces a QuickTime `.mov`). Attach it to a [SceneViewController] bound
/// to an [ARSceneView]:
///
/// ```dart
/// final controller = SceneViewController();
/// final recorder = ARRecorder(controller);
///
/// await recorder.startRecording();
/// // ... later ...
/// final path = await recorder.stopRecording();
/// await recorder.saveToPhotoLibrary(path);
/// ```
///
/// Platform support:
/// - **iOS**: full support — screen capture via `RPScreenRecorder`.
/// - **Android**: not yet bridged. ARCore session recording is a different
///   artifact (a replayable dataset, not a video) and needs deeper access
///   to the AR `Session`/`Frame` than the platform-view bridge exposes.
///   Calls throw an [UnsupportedError] on Android until #1051 lands the
///   Android side.
class ARRecorder {
  /// The controller of the [ARSceneView] this recorder drives.
  final SceneViewController controller;

  ARRecorderState _state = ARRecorderState.idle;
  String? _lastError;

  final StreamController<ARRecorderState> _stateController =
      StreamController<ARRecorderState>.broadcast();

  ARRecorder(this.controller);

  /// Current recorder state.
  ARRecorderState get state => _state;

  /// Human-readable message for the last failure, or `null` if none.
  String? get lastError => _lastError;

  /// `true` between a successful [startRecording] and [stopRecording].
  bool get isRecording => _state == ARRecorderState.recording;

  /// Emits a new value every time the recorder state changes.
  Stream<ARRecorderState> get stateChanges => _stateController.stream;

  void _setState(ARRecorderState next, {String? error}) {
    _state = next;
    _lastError = error;
    if (!_stateController.isClosed) _stateController.add(next);
  }

  /// Starts an AR session recording.
  ///
  /// Throws [UnsupportedError] on Android, [StateError] if the controller
  /// is not attached, and a [PlatformException] if the native recorder
  /// rejects the request (e.g. screen-record permission denied).
  Future<void> startRecording() async {
    if (defaultTargetPlatform != TargetPlatform.iOS) {
      throw UnsupportedError(
        'ARRecorder is currently only supported on iOS. '
        'Android AR session recording is tracked in issue #1051.',
      );
    }
    controller._ensureAttached();
    try {
      await controller._channel!.invokeMethod('startRecording');
      _setState(ARRecorderState.recording);
    } on PlatformException catch (e) {
      _setState(ARRecorderState.error, error: e.message);
      rethrow;
    }
  }

  /// Stops the in-progress recording and returns the path to the `.mov` file.
  ///
  /// [outputPath] optionally specifies where the file is written; when
  /// omitted the native side picks a temp location.
  Future<String> stopRecording({String? outputPath}) async {
    if (defaultTargetPlatform != TargetPlatform.iOS) {
      throw UnsupportedError(
        'ARRecorder is currently only supported on iOS. '
        'Android AR session recording is tracked in issue #1051.',
      );
    }
    controller._ensureAttached();
    try {
      final path = await controller._channel!.invokeMethod<String>(
        'stopRecording',
        {if (outputPath != null) 'outputPath': outputPath},
      );
      _setState(ARRecorderState.idle);
      return path ?? '';
    } on PlatformException catch (e) {
      _setState(ARRecorderState.error, error: e.message);
      rethrow;
    }
  }

  /// Saves a recorded `.mov` file to the device's photo library (iOS).
  Future<void> saveToPhotoLibrary(String movPath) async {
    if (defaultTargetPlatform != TargetPlatform.iOS) {
      throw UnsupportedError(
        'ARRecorder.saveToPhotoLibrary is currently only supported on iOS.',
      );
    }
    controller._ensureAttached();
    await controller._channel!.invokeMethod('saveRecordingToPhotoLibrary', {
      'movPath': movPath,
    });
  }

  /// Releases the state-change stream. Call when the recorder is no longer
  /// needed (e.g. in the host widget's `dispose`).
  void dispose() {
    _stateController.close();
  }
}

// ---------------------------------------------------------------------------
// Data classes
// ---------------------------------------------------------------------------

/// Describes a 3D model to load into the scene.
class ModelNode {
  /// Asset path or URL to the glTF/GLB model file.
  final String modelPath;

  /// X position in world space.
  final double x;

  /// Y position in world space.
  final double y;

  /// Z position in world space.
  final double z;

  /// Uniform scale factor applied to the model.
  final double scale;

  /// X rotation in degrees (Euler angles).
  final double rotationX;

  /// Y rotation in degrees (Euler angles).
  final double rotationY;

  /// Z rotation in degrees (Euler angles).
  final double rotationZ;

  const ModelNode({
    required this.modelPath,
    this.x = 0.0,
    this.y = 0.0,
    this.z = 0.0,
    this.scale = 1.0,
    this.rotationX = 0.0,
    this.rotationY = 0.0,
    this.rotationZ = 0.0,
  });

  Map<String, dynamic> toMap() => {
        'modelPath': modelPath,
        'x': x,
        'y': y,
        'z': z,
        'scale': scale,
        'rotationX': rotationX,
        'rotationY': rotationY,
        'rotationZ': rotationZ,
      };
}

/// Describes a geometry primitive in the scene.
///
/// Platform support:
/// - **Android**: fully rendered — `cube`/`box`, `sphere`, `cylinder` and
///   `plane` are drawn as `CubeNode` / `SphereNode` / `CylinderNode` /
///   `PlaneNode` with a colour material (issue #909).
/// - **iOS**: acknowledged by the native bridge but not yet rendered. The
///   RealityKit geometry port is tracked in the #909 umbrella.
class GeometryNode {
  /// Geometry type: 'cube' (alias 'box'), 'sphere', 'cylinder', or 'plane'.
  final String type;
  final double x;
  final double y;
  final double z;
  final double size;

  /// Fill color as an ARGB integer (e.g. 0xFF6750A4).
  final int color;

  /// When `true` the material ignores all scene lighting (no PBR shading,
  /// no IBL, no shadows) and renders the flat [color] straight to the
  /// framebuffer. Use for HUD overlays, gizmos, axes, lines, or AR face/body
  /// meshes — anywhere lighting would fight the use case.
  ///
  /// Defaults to `false` (lit PBR). Forwarded to the Android bridge as
  /// `MaterialLoader.createUnlitColorInstance(color)` when `true`.
  final bool unlit;

  const GeometryNode({
    required this.type,
    this.x = 0.0,
    this.y = 0.0,
    this.z = 0.0,
    this.size = 1.0,
    this.color = 0xFF888888,
    this.unlit = false,
  });

  Map<String, dynamic> toMap() => {
        'type': type,
        'x': x,
        'y': y,
        'z': z,
        'size': size,
        'color': color,
        'unlit': unlit,
      };
}

/// Describes a light source in the scene.
///
/// Platform support:
/// - **Android**: fully rendered — adds a `LightNode` of the requested
///   `directional` / `point` / `spot` type with the given [intensity],
///   [color] and position (issue #909).
/// - **iOS**: acknowledged by the native bridge but not yet rendered; scenes
///   use sensible default lighting. The RealityKit light port is tracked in
///   the #909 umbrella.
class LightNode {
  /// Light type: 'directional', 'point', or 'spot'.
  final String type;
  final double intensity;

  /// Light color as an ARGB integer.
  final int color;
  final double x;
  final double y;
  final double z;

  const LightNode({
    this.type = 'directional',
    this.intensity = 100000.0,
    this.color = 0xFFFFFFFF,
    this.x = 0.0,
    this.y = 4.0,
    this.z = 0.0,
  });

  Map<String, dynamic> toMap() => {
        'type': type,
        'intensity': intensity,
        'color': color,
        'x': x,
        'y': y,
        'z': z,
      };
}

// ---------------------------------------------------------------------------
// Controller
// ---------------------------------------------------------------------------

/// Controls a [SceneView] or [ARSceneView] after creation.
///
/// Attach a controller to a scene widget, then call methods on it
/// after [onViewCreated] fires:
///
/// ```dart
/// final controller = SceneViewController();
///
/// SceneView(
///   controller: controller,
///   onViewCreated: () {
///     controller.loadModel(ModelNode(modelPath: 'models/helmet.glb'));
///   },
/// );
/// ```
class SceneViewController {
  MethodChannel? _channel;
  bool _disposed = false;

  /// Called when a model node is tapped. Receives the model file's base name
  /// without extension (`models/helmet.glb` → `helmet`), identical on Android
  /// and iOS — never the name of a mesh inside the asset.
  ///
  /// **A tap that lands on nothing does not call this** on either platform:
  /// Android fires it per model, and iOS's hit-test gesture only fires when it
  /// hits an entity. The empty string is reachable only for a tap on something
  /// in the scene that is not a loaded model.
  ///
  /// Wired on both Android and iOS for [SceneView] (3D). On iOS [ARSceneView]
  /// this callback is not yet delivered — SceneViewSwift's `ARSceneView` does
  /// not expose an entity hit-test hook (tracked in #2051).
  void Function(String nodeName)? onTap;

  /// Called when an AR plane is detected. Receives the plane type
  /// ('horizontal_upward', 'horizontal_downward', 'vertical', or 'unknown').
  ///
  /// Delivered on Android only. On iOS, SceneViewSwift's `ARSceneView` does
  /// not yet expose a plane-detection callback, so this is never invoked
  /// there (tracked in #2051).
  void Function(String planeType)? onPlaneDetected;

  /// Whether this controller is attached to a platform view.
  bool get isAttached => _channel != null && !_disposed;

  /// Called internally when the platform view is created.
  void attach(int viewId) {
    _channel = MethodChannel('io.github.sceneview.flutter/scene_$viewId');
    _channel!.setMethodCallHandler(_handleMethodCall);
    _disposed = false;
  }

  /// Called internally when the platform view is disposed.
  void dispose() {
    _channel?.setMethodCallHandler(null);
    _disposed = true;
    _channel = null;
  }

  Future<dynamic> _handleMethodCall(MethodCall call) async {
    switch (call.method) {
      case 'onTap':
        final nodeName = call.arguments as String? ?? '';
        onTap?.call(nodeName);
        break;
      case 'onPlaneDetected':
        final planeType = call.arguments as String? ?? 'unknown';
        onPlaneDetected?.call(planeType);
        break;
    }
  }

  /// Load a glTF/GLB model into the scene.
  ///
  /// Throws [StateError] if the controller is not yet attached.
  Future<void> loadModel(ModelNode node) async {
    _ensureAttached();
    await _channel!.invokeMethod('loadModel', node.toMap());
  }

  /// Add a geometry node to the scene.
  ///
  /// Rendered natively on Android (issue #909); acknowledged but not yet
  /// rendered on iOS. See [GeometryNode].
  Future<void> addGeometry(GeometryNode node) async {
    _ensureAttached();
    await _channel!.invokeMethod('addGeometry', node.toMap());
  }

  /// Add a light node to the scene.
  ///
  /// Rendered natively on Android (issue #909); acknowledged but not yet
  /// rendered on iOS. See [LightNode].
  Future<void> addLight(LightNode node) async {
    _ensureAttached();
    await _channel!.invokeMethod('addLight', node.toMap());
  }

  /// Clear all nodes from the scene.
  Future<void> clearScene() async {
    _ensureAttached();
    await _channel!.invokeMethod('clearScene');
  }

  /// Set the environment HDR for image-based lighting.
  ///
  /// [hdrPath] should be an asset path like `'environments/studio_small.hdr'`.
  Future<void> setEnvironment(String hdrPath) async {
    _ensureAttached();
    await _channel!.invokeMethod('setEnvironment', {'hdrPath': hdrPath});
  }

  /// Change the camera interaction mode at runtime (v4.3.0).
  ///
  /// On iOS this re-applies `.cameraControls(_:)`. On Android only
  /// [CameraControlMode.orbit] is honoured; other modes are acknowledged
  /// but fall back to orbit (see [CameraControlMode]).
  Future<void> setCameraControlMode(CameraControlMode mode) async {
    _ensureAttached();
    await _channel!.invokeMethod('setCameraControlMode', {
      'mode': _cameraControlModeName(mode),
    });
  }

  /// Toggle content auto-centring at runtime (v4.3.0).
  ///
  /// iOS-first; the Android side is tracked in #1051.
  Future<void> setAutoCenterContent(bool enabled) async {
    _ensureAttached();
    await _channel!.invokeMethod('setAutoCenterContent', {'enabled': enabled});
  }

  void _ensureAttached() {
    if (!isAttached) {
      throw StateError(
        'SceneViewController is not attached to a view. '
        'Wait for onViewCreated before calling methods.',
      );
    }
  }
}

// ---------------------------------------------------------------------------
// SceneView widget (3D)
// ---------------------------------------------------------------------------

/// Embeds a native 3D SceneView as a platform view.
///
/// ```dart
/// SceneView(
///   controller: controller,
///   onViewCreated: () => controller.loadModel(
///     ModelNode(modelPath: 'models/damaged_helmet.glb'),
///   ),
/// )
/// ```
class SceneView extends StatefulWidget {
  /// Optional controller for imperative commands (loadModel, clearScene, etc).
  final SceneViewController? controller;

  /// Called when the native platform view has been created and is ready.
  final VoidCallback? onViewCreated;

  /// Models to load immediately when the view is created.
  final List<ModelNode> initialModels;

  /// Called when a model node is tapped. Receives the model file's base name
  /// without extension (`models/helmet.glb` → `helmet`), identical on Android
  /// and iOS — never the name of a mesh inside the asset.
  ///
  /// **A tap that lands on nothing does not call this** on either platform:
  /// Android fires it per model, and iOS's hit-test gesture only fires when it
  /// hits an entity. The empty string is reachable only for a tap on something
  /// in the scene that is not a loaded model.
  ///
  /// Wired on both Android and iOS.
  final void Function(String nodeName)? onTap;

  /// Camera interaction mode. Defaults to [CameraControlMode.orbit].
  ///
  /// [CameraControlMode.pan] and [CameraControlMode.firstPerson] are iOS-only
  /// in v4.3.0; on Android they fall back to orbit (see [CameraControlMode]).
  final CameraControlMode cameraControlMode;

  /// Whether the scene auto-centres its content on the first stable frame.
  ///
  /// Defaults to `true`. Set to `false` to keep models at their authored
  /// positions. iOS-first in v4.3.0; the Android side is tracked in #1051.
  final bool autoCenterContent;

  const SceneView({
    super.key,
    this.controller,
    this.onViewCreated,
    this.initialModels = const [],
    this.onTap,
    this.cameraControlMode = CameraControlMode.orbit,
    this.autoCenterContent = true,
  });

  @override
  State<SceneView> createState() => _SceneViewState();
}

class _SceneViewState extends State<SceneView> {
  static const String _viewType = 'io.github.sceneview.flutter/sceneview';

  /// Internal controller created when the widget has callbacks but no
  /// explicit controller. This ensures onTap is never silently dropped.
  SceneViewController? _internalController;

  SceneViewController get _effectiveController {
    if (widget.controller != null) return widget.controller!;
    _internalController ??= SceneViewController();
    return _internalController!;
  }

  void _onPlatformViewCreated(int id) {
    final controller = _effectiveController;
    controller.attach(id);
    controller.onTap = widget.onTap;
    widget.onViewCreated?.call();
  }

  @override
  void didUpdateWidget(covariant SceneView oldWidget) {
    super.didUpdateWidget(oldWidget);
    final controller = widget.controller ?? _internalController;
    if (controller != null && controller.isAttached) {
      controller.onTap = widget.onTap;
    }
  }

  @override
  void dispose() {
    // Dispose ONLY the controller this widget created itself. A
    // caller-supplied [widget.controller] is owned by the caller — disposing
    // it here would kill a controller the caller still holds (issue #2050).
    _internalController?.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final creationParams = <String, dynamic>{
      'models': widget.initialModels.map((m) => m.toMap()).toList(),
      'cameraControlMode': _cameraControlModeName(widget.cameraControlMode),
      'autoCenterContent': widget.autoCenterContent,
    };

    switch (defaultTargetPlatform) {
      case TargetPlatform.android:
        return AndroidView(
          viewType: _viewType,
          creationParams: creationParams,
          creationParamsCodec: const StandardMessageCodec(),
          onPlatformViewCreated: _onPlatformViewCreated,
          gestureRecognizers: <Factory<OneSequenceGestureRecognizer>>{
            Factory<PanGestureRecognizer>(() => PanGestureRecognizer()),
            Factory<ScaleGestureRecognizer>(() => ScaleGestureRecognizer()),
          },
        );
      case TargetPlatform.iOS:
        return UiKitView(
          viewType: _viewType,
          creationParams: creationParams,
          creationParamsCodec: const StandardMessageCodec(),
          onPlatformViewCreated: _onPlatformViewCreated,
          gestureRecognizers: <Factory<OneSequenceGestureRecognizer>>{
            Factory<PanGestureRecognizer>(() => PanGestureRecognizer()),
            Factory<ScaleGestureRecognizer>(() => ScaleGestureRecognizer()),
          },
        );
      default:
        return const Center(
          child: Text('SceneView is not supported on this platform'),
        );
    }
  }
}

// ---------------------------------------------------------------------------
// ARSceneView widget (AR)
// ---------------------------------------------------------------------------

/// Embeds a native AR SceneView as a platform view.
///
/// Requires camera permission on both Android and iOS.
///
/// ```dart
/// ARSceneView(
///   controller: controller,
///   onViewCreated: () => controller.loadModel(
///     ModelNode(modelPath: 'models/andy.glb'),
///   ),
/// )
/// ```
class ARSceneView extends StatefulWidget {
  /// Optional controller for imperative commands.
  final SceneViewController? controller;

  /// Called when the native platform view has been created and is ready.
  final VoidCallback? onViewCreated;

  /// Whether to enable plane detection and rendering.
  final bool planeDetection;

  /// Called when a model node is tapped. Receives the model file's base name
  /// without extension (`models/helmet.glb` → `helmet`) — never the name of a
  /// mesh inside the asset.
  ///
  /// Delivered on Android only. On iOS, SceneViewSwift's `ARSceneView` does
  /// not yet expose an entity hit-test hook, so this is never invoked there
  /// (tracked in #2051).
  final void Function(String nodeName)? onTap;

  /// Called when an AR plane is detected. Receives the plane type
  /// ('horizontal_upward', 'horizontal_downward', 'vertical', or 'unknown').
  ///
  /// Delivered on Android only. On iOS, SceneViewSwift's `ARSceneView` does
  /// not yet expose a plane-detection callback, so this is never invoked
  /// there (tracked in #2051).
  final void Function(String planeType)? onPlaneDetected;

  const ARSceneView({
    super.key,
    this.controller,
    this.onViewCreated,
    this.planeDetection = true,
    this.onTap,
    this.onPlaneDetected,
  });

  @override
  State<ARSceneView> createState() => _ARSceneViewState();
}

class _ARSceneViewState extends State<ARSceneView> {
  static const String _viewType = 'io.github.sceneview.flutter/arsceneview';

  /// Internal controller created when the widget has callbacks but no
  /// explicit controller. This ensures onTap/onPlaneDetected are never
  /// silently dropped.
  SceneViewController? _internalController;

  SceneViewController get _effectiveController {
    if (widget.controller != null) return widget.controller!;
    _internalController ??= SceneViewController();
    return _internalController!;
  }

  void _onPlatformViewCreated(int id) {
    final controller = _effectiveController;
    controller.attach(id);
    controller.onTap = widget.onTap;
    controller.onPlaneDetected = widget.onPlaneDetected;
    widget.onViewCreated?.call();
  }

  @override
  void didUpdateWidget(covariant ARSceneView oldWidget) {
    super.didUpdateWidget(oldWidget);
    final controller = widget.controller ?? _internalController;
    if (controller != null && controller.isAttached) {
      controller.onTap = widget.onTap;
      controller.onPlaneDetected = widget.onPlaneDetected;
    }
  }

  @override
  void dispose() {
    // Dispose ONLY the controller this widget created itself. A
    // caller-supplied [widget.controller] is owned by the caller — disposing
    // it here would kill a controller the caller still holds (issue #2050).
    _internalController?.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final creationParams = <String, dynamic>{
      'planeDetection': widget.planeDetection,
    };

    switch (defaultTargetPlatform) {
      case TargetPlatform.android:
        return AndroidView(
          viewType: _viewType,
          creationParams: creationParams,
          creationParamsCodec: const StandardMessageCodec(),
          onPlatformViewCreated: _onPlatformViewCreated,
          gestureRecognizers: <Factory<OneSequenceGestureRecognizer>>{
            Factory<PanGestureRecognizer>(() => PanGestureRecognizer()),
            Factory<ScaleGestureRecognizer>(() => ScaleGestureRecognizer()),
          },
        );
      case TargetPlatform.iOS:
        return UiKitView(
          viewType: _viewType,
          creationParams: creationParams,
          creationParamsCodec: const StandardMessageCodec(),
          onPlatformViewCreated: _onPlatformViewCreated,
          gestureRecognizers: <Factory<OneSequenceGestureRecognizer>>{
            Factory<PanGestureRecognizer>(() => PanGestureRecognizer()),
            Factory<ScaleGestureRecognizer>(() => ScaleGestureRecognizer()),
          },
        );
      default:
        return const Center(
          child: Text('ARSceneView is not supported on this platform'),
        );
    }
  }
}
