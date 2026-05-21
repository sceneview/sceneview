import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:integration_test/integration_test.dart';

import 'package:sceneview_flutter_demo/app.dart';

/// Integration test that captures screenshots of each tab for visual verification.
///
/// Run with:
/// ```bash
/// cd samples/flutter-demo
/// flutter test integration_test/screenshot_test.dart
/// ```
void main() {
  final binding = IntegrationTestWidgetsFlutterBinding.ensureInitialized();

  group('SceneView Flutter Demo Screenshots', () {
    testWidgets('captures 3D Viewer tab', (tester) async {
      await tester.pumpWidget(const SceneViewDemoApp());
      await tester.pumpAndSettle();

      // Verify 3D Viewer tab is showing
      expect(find.text('3D Viewer'), findsOneWidget);
      expect(find.text('Search Sketchfab models...'), findsOneWidget);

      await binding.takeScreenshot('01_viewer_tab');
    });

    testWidgets('captures AR tab', (tester) async {
      await tester.pumpWidget(const SceneViewDemoApp());
      await tester.pumpAndSettle();

      // Navigate to AR tab
      await tester.tap(find.text('AR'));
      await tester.pumpAndSettle();

      expect(find.text('AR Mode'), findsOneWidget);
      expect(find.text('Plane Detection'), findsOneWidget);

      await binding.takeScreenshot('02_ar_tab');
    });

    testWidgets('captures Features tab', (tester) async {
      await tester.pumpWidget(const SceneViewDemoApp());
      await tester.pumpAndSettle();

      // Navigate to Features tab
      await tester.tap(find.text('Features'));
      await tester.pumpAndSettle();

      expect(find.text('Bridge Features'), findsOneWidget);
      expect(find.text('SceneView Flutter Bridge'), findsOneWidget);

      await binding.takeScreenshot('03_features_tab');
    });

    testWidgets('captures About tab', (tester) async {
      await tester.pumpWidget(const SceneViewDemoApp());
      await tester.pumpAndSettle();

      // Navigate to About tab
      await tester.tap(find.text('About'));
      await tester.pumpAndSettle();

      expect(find.text('SceneView Flutter'), findsOneWidget);
      expect(find.text('v4.13.0'), findsOneWidget);
      // The About tab's "Bridge Coverage" section honestly labels which
      // features the bridge exposes — see issue #909.
      expect(find.text('Bridge Coverage'), findsOneWidget);

      await binding.takeScreenshot('04_about_tab');
    });

    testWidgets('AR mode toggle works', (tester) async {
      await tester.pumpWidget(const SceneViewDemoApp());
      await tester.pumpAndSettle();

      // Navigate to AR tab
      await tester.tap(find.text('AR'));
      await tester.pumpAndSettle();

      // Toggle to 3D mode
      await tester.tap(find.text('3D'));
      await tester.pumpAndSettle();

      expect(find.text('3D Mode'), findsOneWidget);

      await binding.takeScreenshot('05_3d_mode_toggle');
    });

    testWidgets('features page shows all bridge features', (tester) async {
      await tester.pumpWidget(const SceneViewDemoApp());
      await tester.pumpAndSettle();

      // Navigate to Features tab
      await tester.tap(find.text('Features'));
      await tester.pumpAndSettle();

      // Verify all feature cards are present
      expect(find.text('SceneView (3D)'), findsOneWidget);
      expect(find.text('ARSceneView (AR)'), findsOneWidget);
      expect(find.text('SceneViewController'), findsOneWidget);
      expect(find.text('ModelNode'), findsOneWidget);
      expect(find.text('onTap Callback'), findsOneWidget);
      expect(find.text('onPlaneDetected Callback'), findsOneWidget);
      expect(find.text('GeometryNode'), findsOneWidget);
      expect(find.text('LightNode'), findsOneWidget);
      expect(find.text('Environment (HDR)'), findsOneWidget);

      await binding.takeScreenshot('06_all_features');
    });
  });
}
