import 'package:flutter/foundation.dart' show TargetPlatform, defaultTargetPlatform;
import 'package:flutter/material.dart';
import 'package:flutter/services.dart' show PlatformException;
import 'package:flutter_sceneview/flutter_sceneview.dart';

/// AR tap-to-place (#3780): tap a detected surface to drop an anchored model,
/// then drag it with one finger, twist with two to rotate, pinch to scale.
///
/// The whole feature is two calls: [ARSceneView.onPlaneTap] hands over the
/// hit, [SceneViewController.placeModel] anchors a model there and returns a
/// [PlacedModel] handle. The one-liner `ARSceneView(placeOnTap: model)` does
/// the same without any code; this page uses the explicit form so it can
/// keep handles for Undo.
class TapToPlacePage extends StatefulWidget {
  const TapToPlacePage({super.key});

  @override
  State<TapToPlacePage> createState() => _TapToPlacePageState();
}

class _TapToPlacePageState extends State<TapToPlacePage> {
  final _controller = SceneViewController();
  final List<PlacedModel> _placed = [];
  bool _placing = false;
  int _selected = 0;

  // Android (Filament) loads glTF/GLB, straight from a URL. iOS (RealityKit)
  // loads USDZ bundled into Runner.app (ios/Runner/Models/).
  static final bool _isApple = defaultTargetPlatform == TargetPlatform.iOS ||
      defaultTargetPlatform == TargetPlatform.macOS;

  static const _khronos =
      'https://raw.githubusercontent.com/KhronosGroup/glTF-Sample-Assets/main/Models';

  static final List<_Placeable> _models = _isApple
      ? const [_Placeable('Fox', 'khronos_fox.usdz', 0.4, Icons.pets)]
      : const [
          _Placeable('Duck', '$_khronos/Duck/glTF-Binary/Duck.glb', 0.3,
              Icons.bathtub_outlined),
          _Placeable('Avocado', '$_khronos/Avocado/glTF-Binary/Avocado.glb',
              0.2, Icons.eco),
          _Placeable('Box', '$_khronos/Box/glTF-Binary/Box.glb', 0.25,
              Icons.check_box_outline_blank),
        ];

  Future<void> _onPlaneTap(ARHitResult hit) async {
    if (_placing) return;
    final model = _models[_selected];
    setState(() => _placing = true);
    try {
      final handle = await _controller.placeModel(
        hit,
        ModelNode(modelPath: model.path, scale: model.size),
      );
      if (mounted) setState(() => _placed.add(handle));
    } on PlatformException catch (e) {
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text('Could not place ${model.name}: ${e.message}')),
      );
    } finally {
      if (mounted) setState(() => _placing = false);
    }
  }

  Future<void> _undo() async {
    if (_placed.isEmpty) return;
    final last = _placed.removeLast();
    setState(() {});
    await _controller.removePlacedModel(last);
  }

  Future<void> _clear() async {
    setState(_placed.clear);
    await _controller.clearScene();
  }

  String get _hint {
    if (_placing) return 'Placing ${_models[_selected].name}…';
    if (_placed.isNotEmpty) {
      return '${_placed.length} placed · drag to move, twist to rotate, pinch to scale';
    }
    return 'Point at the floor or a table, then tap it to place a '
        '${_models[_selected].name}';
  }

  @override
  Widget build(BuildContext context) {
    final dark = Theme.of(context).brightness == Brightness.dark;
    // DESIGN.md AR overlay tokens: ar-scrim, border, text.
    final scrim = Colors.black.withValues(alpha: dark ? 0.88 : 0.94);
    final border = Colors.white.withValues(alpha: dark ? 0.10 : 0.16);
    const text = Colors.white;
    final dimText = Colors.white.withValues(alpha: 0.72);

    BoxDecoration pill() => BoxDecoration(
          color: scrim,
          borderRadius: BorderRadius.circular(24),
          border: Border.all(color: border),
        );

    return Scaffold(
      extendBodyBehindAppBar: true,
      appBar: AppBar(
        title: const Text('Tap to place'),
        backgroundColor: Colors.transparent,
        foregroundColor: text,
      ),
      body: Stack(
        fit: StackFit.expand,
        children: [
          ARSceneView(
            controller: _controller,
            onPlaneTap: _onPlaneTap,
          ),
          SafeArea(
            child: Align(
              alignment: Alignment.topCenter,
              child: Padding(
                padding: const EdgeInsets.only(top: 64, left: 16, right: 16),
                child: ConstrainedBox(
                  constraints: const BoxConstraints(maxWidth: 480),
                  child: Container(
                    padding:
                        const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
                    decoration: pill(),
                    child: Text(
                      _hint,
                      textAlign: TextAlign.center,
                      style: const TextStyle(color: text, fontSize: 14),
                    ),
                  ),
                ),
              ),
            ),
          ),
          SafeArea(
            child: Align(
              alignment: Alignment.bottomCenter,
              child: Padding(
                padding: const EdgeInsets.all(16),
                child: ConstrainedBox(
                  constraints: const BoxConstraints(maxWidth: 480),
                  child: Container(
                    padding: const EdgeInsets.all(12),
                    decoration: pill(),
                    child: Row(
                      children: [
                        Expanded(
                          child: SingleChildScrollView(
                            scrollDirection: Axis.horizontal,
                            child: Row(
                              children: [
                                for (var i = 0; i < _models.length; i++)
                                  Padding(
                                    padding: const EdgeInsets.only(right: 8),
                                    child: ChoiceChip(
                                      avatar: Icon(_models[i].icon,
                                          size: 18,
                                          color:
                                              i == _selected ? null : dimText),
                                      label: Text(_models[i].name),
                                      selected: i == _selected,
                                      onSelected: (_) =>
                                          setState(() => _selected = i),
                                    ),
                                  ),
                              ],
                            ),
                          ),
                        ),
                        IconButton(
                          tooltip: 'Undo',
                          color: text,
                          disabledColor: dimText.withValues(alpha: 0.3),
                          onPressed: _placed.isEmpty ? null : _undo,
                          icon: const Icon(Icons.undo),
                        ),
                        IconButton(
                          tooltip: 'Clear',
                          color: text,
                          disabledColor: dimText.withValues(alpha: 0.3),
                          onPressed: _placed.isEmpty ? null : _clear,
                          icon: const Icon(Icons.delete_sweep_outlined),
                        ),
                      ],
                    ),
                  ),
                ),
              ),
            ),
          ),
        ],
      ),
    );
  }
}

class _Placeable {
  final String name;

  /// GLB URL on Android, bundled USDZ resource name on iOS.
  final String path;

  /// Largest dimension once placed, in metres.
  final double size;
  final IconData icon;

  const _Placeable(this.name, this.path, this.size, this.icon);
}
