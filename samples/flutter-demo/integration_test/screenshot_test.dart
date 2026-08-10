import 'package:flutter_test/flutter_test.dart';
import 'package:integration_test/integration_test.dart';

import 'package:sceneview_flutter_demo/app.dart';

/// Integration test that captures screenshots of each tab for visual verification.
///
/// ## What this file does NOT cover
///
/// Every test here drives the widget tree with `pumpWidget`, so **no platform
/// view is ever created**: `SceneView` renders as an empty placeholder and no
/// native code runs. That means these tests stay green while the 3D viewport is
/// blank, the model fails to load, or the bridge does not even compile — which
/// is exactly what happened. The iOS path shipped unbuildable and this suite
/// never noticed, because it only asserts on Flutter-side labels.
///
/// Treat it as a chrome/navigation smoke test. Anything about rendering, model
/// loading or `onTap` has to be verified on a real device or simulator, by
/// launching the app and interacting with the viewport.
///
/// ## Current state — measured 2026-08-07
///
/// No workflow in `.github/workflows/` invokes this file, and the command below
/// does not currently succeed in either mode:
///   - with no device connected, it exits immediately ("No supported devices");
///   - with an iOS simulator booted, `captures 3D Viewer tab` never completes
///     (still running after 9 minutes — `pumpAndSettle` does not converge).
/// So it is not a passing suite that proves little; it is a suite nothing runs.
/// Fixing that is worth its own change — do not read a green tick here.
///
/// Run with (needs a connected device or booted simulator):
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
      // Assert a version is shown, not which one. Pinning the exact string made
      // this test lock in a stale value: it kept passing on 'v4.13.0' while the
      // SDK moved to 4.26.0, so the assertion defended the drift instead of
      // catching it. `sync-versions.sh` now checks the About screen too (both
      // version slots, and flags them as INCONSISTENT when they disagree), so
      // this assertion no longer has to be the only thing looking at it — it
      // covers the runtime half: that a version renders at all.
      expect(find.textContaining(RegExp(r'^v\d+\.\d+\.\d+$')), findsOneWidget);
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
