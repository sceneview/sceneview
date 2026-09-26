// AR tap-to-place (#3780): the Dart half of the method-channel contract.
//
// The Android half is pinned by `android/src/test/.../ARTapToPlaceTest.kt`
// (same keys, same defaults). The iOS bridge decodes the same payload in
// `ios/Classes/ARTapToPlace.swift`.

import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:flutter_sceneview/flutter_sceneview.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  const hitMap = <String, dynamic>{
    'id': 'hit-3',
    'x': 0.5,
    'y': -1.25,
    'z': -2.0,
    'qx': 0.0,
    'qy': 0.7071068,
    'qz': 0.0,
    'qw': 0.7071068,
    'planeType': 'horizontal_upward',
    'distance': 1.5,
  };

  group('ARHitResult', () {
    test('fromMap reads every key the native bridges send', () {
      final hit = ARHitResult.fromMap(hitMap);
      expect(hit.id, 'hit-3');
      expect(hit.x, 0.5);
      expect(hit.y, -1.25);
      expect(hit.z, -2.0);
      expect(hit.qy, 0.7071068);
      expect(hit.qw, 0.7071068);
      expect(hit.planeType, 'horizontal_upward');
      expect(hit.distance, 1.5);
    });

    test('toMap round-trips through fromMap', () {
      final hit = ARHitResult.fromMap(hitMap);
      expect(hit.toMap(), hitMap);
      expect(ARHitResult.fromMap(hit.toMap()).toMap(), hitMap);
    });

    test('fromMap accepts ints and fills defaults for missing keys', () {
      final hit = ARHitResult.fromMap(const <dynamic, dynamic>{'x': 1, 'y': 0});
      expect(hit.x, 1.0);
      expect(hit.x, isA<double>());
      expect(hit.id, '');
      expect(hit.z, 0.0);
      expect(hit.qw, 1.0, reason: 'a missing rotation is identity');
      expect(hit.planeType, 'unknown');
    });
  });

  group('PlacedModel', () {
    test('compares by id', () {
      expect(const PlacedModel('placed-1'), const PlacedModel('placed-1'));
      expect(const PlacedModel('placed-1') == const PlacedModel('placed-2'),
          isFalse);
    });
  });

  group('SceneViewController tap-to-place', () {
    const viewId = 130;
    const channel = MethodChannel('io.github.sceneview.flutter/scene_$viewId');
    final calls = <MethodCall>[];
    Object? placeReply = 'placed-0';

    setUp(() {
      calls.clear();
      placeReply = 'placed-0';
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(channel, (call) async {
        calls.add(call);
        return call.method == 'placeModel' ? placeReply : null;
      });
    });

    tearDown(() {
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(channel, null);
    });

    test('placeModel sends the hit, the model and the gesture flags', () async {
      final controller = SceneViewController()..attach(viewId);
      final placed = await controller.placeModel(
        ARHitResult.fromMap(hitMap),
        const ModelNode(modelPath: 'models/chair.glb', scale: 0.5),
        rotatable: false,
      );

      expect(placed, const PlacedModel('placed-0'));
      final call = calls.single;
      expect(call.method, 'placeModel');
      final args = call.arguments as Map<dynamic, dynamic>;
      expect(args['hit'], hitMap);
      final model = args['model'] as Map<dynamic, dynamic>;
      expect(model['modelPath'], 'models/chair.glb');
      expect(model['scale'], 0.5);
      expect(args['editable'], isTrue);
      expect(args['draggable'], isTrue);
      expect(args['rotatable'], isFalse);
      expect(args['scalable'], isTrue);
    });

    test('placeModel throws when the native side returns no id', () async {
      placeReply = null;
      final controller = SceneViewController()..attach(viewId);
      await expectLater(
        controller.placeModel(
          ARHitResult.fromMap(hitMap),
          const ModelNode(modelPath: 'models/chair.glb'),
        ),
        throwsA(isA<PlatformException>()),
      );
    });

    test('removePlacedModel sends the handle id', () async {
      final controller = SceneViewController()..attach(viewId);
      await controller.removePlacedModel(const PlacedModel('placed-4'));
      expect(calls.single.method, 'removePlacedModel');
      expect(calls.single.arguments, {'id': 'placed-4'});
    });

    test('placeModel before attach throws StateError', () {
      expect(
        () => SceneViewController().placeModel(
          ARHitResult.fromMap(hitMap),
          const ModelNode(modelPath: 'models/chair.glb'),
        ),
        throwsStateError,
      );
    });
  });

  group('SceneViewController onPlaneTap', () {
    test('decodes the native onPlaneTap event into an ARHitResult', () async {
      const viewId = 131;
      final controller = SceneViewController()..attach(viewId);
      ARHitResult? received;
      controller.onPlaneTap = (hit) => received = hit;

      const codec = StandardMethodCodec();
      await TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .handlePlatformMessage(
        'io.github.sceneview.flutter/scene_$viewId',
        codec.encodeMethodCall(const MethodCall('onPlaneTap', hitMap)),
        (_) {},
      );

      expect(received, isNotNull);
      expect(received!.toMap(), hitMap);
    });

    test('a malformed onPlaneTap payload is ignored', () async {
      const viewId = 132;
      final controller = SceneViewController()..attach(viewId);
      var fired = false;
      controller.onPlaneTap = (_) => fired = true;

      const codec = StandardMethodCodec();
      await TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .handlePlatformMessage(
        'io.github.sceneview.flutter/scene_$viewId',
        codec.encodeMethodCall(const MethodCall('onPlaneTap', 'not a map')),
        (_) {},
      );

      expect(fired, isFalse);
    });
  });
}
