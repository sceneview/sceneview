// Smoke tests for the SceneView Flutter plugin bridge.
//
// The features page (`samples/flutter-demo/lib/pages/features_page.dart`)
// shows status badges for every public method on `SceneViewController`. The
// #909 umbrella discovered that some of those badges used to be green for
// methods whose iOS native paths are no-ops. These smoke tests pin the
// **observable** half of the bridge — that each "implemented" method, when
// invoked on an attached controller, returns a Future that completes with a
// non-error result (via a mocked platform channel). The tests do **not**
// assert any rendering — that requires a real device.
//
// If a method is later promoted from "Android only" to "Implemented" in the
// features page, this file should grow an assertion for the iOS path too.

import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:flutter_sceneview/flutter_sceneview.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  group('SceneViewController bridge smoke tests', () {
    const viewId = 99;
    const channel = MethodChannel('io.github.sceneview.flutter/scene_$viewId');
    final recordedCalls = <MethodCall>[];

    setUp(() {
      recordedCalls.clear();
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(channel, (MethodCall call) async {
        recordedCalls.add(call);
        // Mirror the Android plugin's `result.success(null)` for every
        // implemented method — this is what production code observes.
        return null;
      });
    });

    tearDown(() {
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(channel, null);
    });

    test('loadModel resolves and forwards every transform arg', () async {
      final controller = SceneViewController()..attach(viewId);
      await controller.loadModel(const ModelNode(
        modelPath: 'models/helmet.glb',
        x: 1.0,
        y: -0.5,
        z: -2.0,
        rotationY: 45.0,
        scale: 1.5,
      ));
      expect(recordedCalls, hasLength(1));
      final call = recordedCalls.single;
      expect(call.method, 'loadModel');
      final args = call.arguments as Map<dynamic, dynamic>;
      expect(args['modelPath'], 'models/helmet.glb');
      expect(args['x'], 1.0);
      expect(args['y'], -0.5);
      expect(args['z'], -2.0);
      expect(args['rotationY'], 45.0);
      expect(args['scale'], 1.5);
    });

    test('clearScene resolves to non-error', () async {
      final controller = SceneViewController()..attach(viewId);
      await expectLater(controller.clearScene(), completes);
      expect(recordedCalls.single.method, 'clearScene');
    });

    test('addGeometry forwards the GeometryNode payload', () async {
      final controller = SceneViewController()..attach(viewId);
      await controller.addGeometry(const GeometryNode(
        type: 'sphere',
        size: 0.25,
        color: 0xFF005BC1,
        unlit: true,
      ));
      expect(recordedCalls, hasLength(1));
      final args = recordedCalls.single.arguments as Map<dynamic, dynamic>;
      expect(args['type'], 'sphere');
      expect(args['size'], 0.25);
      expect(args['color'], 0xFF005BC1);
      expect(args['unlit'], isTrue);
    });

    test('addLight forwards the LightNode payload', () async {
      final controller = SceneViewController()..attach(viewId);
      await controller.addLight(const LightNode(
        type: 'point',
        intensity: 75000.0,
        color: 0xFFFFAA00,
      ));
      expect(recordedCalls, hasLength(1));
      final args = recordedCalls.single.arguments as Map<dynamic, dynamic>;
      expect(args['type'], 'point');
      expect(args['intensity'], 75000.0);
      expect(args['color'], 0xFFFFAA00);
    });

    test('setEnvironment carries the hdrPath', () async {
      final controller = SceneViewController()..attach(viewId);
      await controller.setEnvironment('environments/studio_small.hdr');
      expect(recordedCalls.single.method, 'setEnvironment');
      final args = recordedCalls.single.arguments as Map<dynamic, dynamic>;
      expect(args['hdrPath'], 'environments/studio_small.hdr');
    });
  });

  group('SceneViewController inbound callbacks', () {
    test('onTap fires when the native side invokes the channel', () async {
      const viewId = 101;
      final controller = SceneViewController()..attach(viewId);
      String? lastNode;
      controller.onTap = (name) => lastNode = name;

      const channel = MethodChannel('io.github.sceneview.flutter/scene_$viewId');
      const codec = StandardMethodCodec();
      final binding =
          TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
      await binding.handlePlatformMessage(
        channel.name,
        codec.encodeMethodCall(const MethodCall('onTap', 'damaged_helmet')),
        (_) {},
      );
      expect(lastNode, 'damaged_helmet');
    });

    test('onPlaneDetected fires when the native side invokes the channel',
        () async {
      const viewId = 102;
      final controller = SceneViewController()..attach(viewId);
      String? lastPlane;
      controller.onPlaneDetected = (type) => lastPlane = type;

      const channel = MethodChannel('io.github.sceneview.flutter/scene_$viewId');
      const codec = StandardMethodCodec();
      final binding =
          TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
      await binding.handlePlatformMessage(
        channel.name,
        codec.encodeMethodCall(
          const MethodCall('onPlaneDetected', 'horizontal_upward'),
        ),
        (_) {},
      );
      expect(lastPlane, 'horizontal_upward');
    });
  });
}
