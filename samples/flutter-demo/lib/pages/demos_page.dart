import 'package:flutter/material.dart';
import 'package:sceneview_flutter/sceneview_flutter.dart';

/// Demos tab — a catalog of runnable, per-feature SceneView demos.
///
/// Unlike the [FeaturesPage] reference checklist, each entry here is a live,
/// interactive scene exercising exactly one bridge capability:
///
/// - **Materials** — lit PBR vs `unlit` geometry materials.
/// - **Model Animation** — auto-playing glTF animation clips.
/// - **Environment** — HDR image-based lighting switching.
/// - **Camera Modes** — orbit / pan / first-person camera control.
///
/// Every demo uses only APIs the Flutter bridge actually exposes
/// (`SceneViewController.addGeometry` / `loadModel` / `setEnvironment` /
/// `setCameraControlMode`), so nothing here is dead UI.
class DemosPage extends StatelessWidget {
  const DemosPage({super.key});

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('Demos')),
      body: ListView(
        padding: const EdgeInsets.all(16),
        children: const [
          _MaterialsDemo(),
          SizedBox(height: 16),
          _AnimationDemo(),
          SizedBox(height: 16),
          _EnvironmentDemo(),
          SizedBox(height: 16),
          _CameraModesDemo(),
        ],
      ),
    );
  }
}

/// Shared card chrome for a demo: a titled header plus a fixed-height body.
class _DemoCard extends StatelessWidget {
  const _DemoCard({
    required this.icon,
    required this.title,
    required this.description,
    required this.child,
  });

  final IconData icon;
  final String title;
  final String description;
  final Widget child;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Icon(icon, color: theme.colorScheme.primary),
                const SizedBox(width: 8),
                Text(title, style: theme.textTheme.titleMedium),
              ],
            ),
            const SizedBox(height: 4),
            Text(description, style: theme.textTheme.bodySmall),
            const SizedBox(height: 12),
            child,
          ],
        ),
      ),
    );
  }
}

// ---------------------------------------------------------------------------
// Materials demo — lit PBR vs unlit
// ---------------------------------------------------------------------------

/// Demonstrates [GeometryNode.unlit] — the bridge's only material control.
/// A lit sphere reacts to the directional light; an unlit sphere renders its
/// flat colour straight to the framebuffer.
class _MaterialsDemo extends StatefulWidget {
  const _MaterialsDemo();

  @override
  State<_MaterialsDemo> createState() => _MaterialsDemoState();
}

class _MaterialsDemoState extends State<_MaterialsDemo> {
  final _controller = SceneViewController();
  bool _ready = false;
  bool _unlit = false;
  Color _color = const Color(0xFF1E88E5);

  static const _colors = [
    Color(0xFF1E88E5),
    Color(0xFFE53935),
    Color(0xFF43A047),
    Color(0xFFFDD835),
    Color(0xFF8E24AA),
  ];

  void _rebuild() {
    if (!_ready) return;
    _controller.clearScene();
    _controller.addLight(const LightNode(type: 'directional', intensity: 100000));
    // A row of three primitives sharing one colour; only the shading model
    // changes between lit and unlit.
    _controller.addGeometry(GeometryNode(
      type: 'sphere',
      size: 0.9,
      x: -1.4,
      color: _color.value,
      unlit: _unlit,
    ));
    _controller.addGeometry(GeometryNode(
      type: 'cube',
      size: 0.8,
      color: _color.value,
      unlit: _unlit,
    ));
    _controller.addGeometry(GeometryNode(
      type: 'cylinder',
      size: 0.8,
      x: 1.4,
      color: _color.value,
      unlit: _unlit,
    ));
  }

  @override
  Widget build(BuildContext context) {
    return _DemoCard(
      icon: Icons.palette,
      title: 'Materials — Lit vs Unlit',
      description: 'GeometryNode.unlit flag: PBR shading vs flat colour.',
      child: Column(
        children: [
          SizedBox(
            height: 200,
            child: Stack(
              children: [
                SceneView(
                  controller: _controller,
                  onViewCreated: () {
                    _controller.setEnvironment('environments/studio_small.hdr');
                    setState(() => _ready = true);
                    _rebuild();
                  },
                ),
                if (!_ready) const Center(child: CircularProgressIndicator()),
              ],
            ),
          ),
          const SizedBox(height: 8),
          SwitchListTile(
            contentPadding: EdgeInsets.zero,
            title: const Text('Unlit material'),
            subtitle: const Text('Ignore all scene lighting'),
            value: _unlit,
            onChanged: _ready
                ? (v) {
                    setState(() => _unlit = v);
                    _rebuild();
                  }
                : null,
          ),
          Row(
            children: [
              const Text('Color: '),
              const SizedBox(width: 8),
              for (final c in _colors)
                Padding(
                  padding: const EdgeInsets.only(right: 4),
                  child: GestureDetector(
                    onTap: _ready
                        ? () {
                            setState(() => _color = c);
                            _rebuild();
                          }
                        : null,
                    child: CircleAvatar(
                      radius: 14,
                      backgroundColor: c,
                      child: _color == c
                          ? const Icon(Icons.check, size: 14, color: Colors.white)
                          : null,
                    ),
                  ),
                ),
            ],
          ),
        ],
      ),
    );
  }
}

// ---------------------------------------------------------------------------
// Animation demo — auto-playing glTF clips
// ---------------------------------------------------------------------------

/// Loads glTF models that ship animation clips. The Android bridge
/// auto-plays a model's animation on load, so picking an animated model
/// shows it moving.
class _AnimationDemo extends StatefulWidget {
  const _AnimationDemo();

  @override
  State<_AnimationDemo> createState() => _AnimationDemoState();
}

class _AnimationDemoState extends State<_AnimationDemo> {
  final _controller = SceneViewController();
  bool _ready = false;
  int _modelIndex = 0;

  // Khronos sample assets that ship animation clips.
  static const _models = [
    (
      'Fox',
      'https://raw.githubusercontent.com/KhronosGroup/glTF-Sample-Assets/main/Models/Fox/glTF-Binary/Fox.glb',
      0.03,
    ),
    (
      'Box Animated',
      'https://raw.githubusercontent.com/KhronosGroup/glTF-Sample-Assets/main/Models/BoxAnimated/glTF-Binary/BoxAnimated.glb',
      0.6,
    ),
    (
      'BrainStem',
      'https://raw.githubusercontent.com/KhronosGroup/glTF-Sample-Assets/main/Models/BrainStem/glTF-Binary/BrainStem.glb',
      0.7,
    ),
  ];

  void _loadModel() {
    if (!_ready) return;
    final (_, url, scale) = _models[_modelIndex];
    _controller.clearScene();
    _controller.addLight(const LightNode(type: 'directional', intensity: 100000));
    _controller.loadModel(ModelNode(modelPath: url, scale: scale, y: -0.5));
  }

  @override
  Widget build(BuildContext context) {
    return _DemoCard(
      icon: Icons.movie,
      title: 'Model Animation',
      description: 'glTF animation clips auto-play on load (Android).',
      child: Column(
        children: [
          SizedBox(
            height: 200,
            child: Stack(
              children: [
                SceneView(
                  controller: _controller,
                  onViewCreated: () {
                    _controller.setEnvironment('environments/studio_small.hdr');
                    setState(() => _ready = true);
                    _loadModel();
                  },
                ),
                if (!_ready) const Center(child: CircularProgressIndicator()),
              ],
            ),
          ),
          const SizedBox(height: 8),
          SegmentedButton<int>(
            segments: [
              for (var i = 0; i < _models.length; i++)
                ButtonSegment(value: i, label: Text(_models[i].$1)),
            ],
            selected: {_modelIndex},
            onSelectionChanged: _ready
                ? (s) {
                    setState(() => _modelIndex = s.first);
                    _loadModel();
                  }
                : null,
          ),
        ],
      ),
    );
  }
}

// ---------------------------------------------------------------------------
// Environment demo — HDR image-based lighting
// ---------------------------------------------------------------------------

/// Switches between TWO distinct HDR environments on a fixed model so the change
/// in image-based lighting and skybox is the only variable. Toggling between two
/// non-null HDRs at runtime is what exercises the keyed-Environment swap path
/// (#2361) — the visual proof that the skybox/IBL actually rebuilds on a new HDR.
class _EnvironmentDemo extends StatefulWidget {
  const _EnvironmentDemo();

  @override
  State<_EnvironmentDemo> createState() => _EnvironmentDemoState();
}

class _EnvironmentDemoState extends State<_EnvironmentDemo> {
  final _controller = SceneViewController();
  bool _ready = false;
  bool _autoCenter = true;
  int _envIndex = 0;

  static const _helmetUrl =
      'https://raw.githubusercontent.com/KhronosGroup/glTF-Sample-Assets/main/Models/DamagedHelmet/glTF-Binary/DamagedHelmet.glb';

  /// Two visually distinct HDRs (bright studio ↔ dark rooftop night), so the
  /// toggle is unmistakable on a device and drives the keyed-swap path (#2361).
  static const _environments = [
    (label: 'Studio', path: 'environments/studio_small.hdr'),
    (label: 'Night', path: 'environments/rooftop_night_2k.hdr'),
  ];

  @override
  Widget build(BuildContext context) {
    return _DemoCard(
      icon: Icons.image,
      title: 'Environment — HDR Lighting',
      description: 'Toggle between two HDRs (Studio ↔ Night) at runtime.',
      child: Column(
        children: [
          SizedBox(
            height: 200,
            child: Stack(
              children: [
                SceneView(
                  controller: _controller,
                  autoCenterContent: _autoCenter,
                  onViewCreated: () {
                    _controller
                        .setEnvironment(_environments[_envIndex].path);
                    _controller.loadModel(const ModelNode(modelPath: _helmetUrl));
                    setState(() => _ready = true);
                  },
                ),
                if (!_ready) const Center(child: CircularProgressIndicator()),
              ],
            ),
          ),
          const SizedBox(height: 8),
          SegmentedButton<int>(
            segments: [
              for (var i = 0; i < _environments.length; i++)
                ButtonSegment(value: i, label: Text(_environments[i].label)),
            ],
            selected: {_envIndex},
            onSelectionChanged: _ready
                ? (selection) {
                    final next = selection.first;
                    setState(() => _envIndex = next);
                    _controller.setEnvironment(_environments[next].path);
                  }
                : null,
          ),
          SwitchListTile(
            contentPadding: EdgeInsets.zero,
            title: const Text('Auto-center content'),
            subtitle: const Text('Frame model on first stable frame (iOS-first)'),
            value: _autoCenter,
            onChanged: (v) {
              setState(() => _autoCenter = v);
              if (_ready) _controller.setAutoCenterContent(v);
            },
          ),
        ],
      ),
    );
  }
}

// ---------------------------------------------------------------------------
// Camera modes demo — orbit / pan / first-person
// ---------------------------------------------------------------------------

/// Switches the camera control mode at runtime via
/// [SceneViewController.setCameraControlMode]. On iOS all three modes are
/// wired; on Android non-orbit modes fall back to orbit (issue #1051).
class _CameraModesDemo extends StatefulWidget {
  const _CameraModesDemo();

  @override
  State<_CameraModesDemo> createState() => _CameraModesDemoState();
}

class _CameraModesDemoState extends State<_CameraModesDemo> {
  final _controller = SceneViewController();
  bool _ready = false;
  CameraControlMode _mode = CameraControlMode.orbit;

  static const _helmetUrl =
      'https://raw.githubusercontent.com/KhronosGroup/glTF-Sample-Assets/main/Models/DamagedHelmet/glTF-Binary/DamagedHelmet.glb';

  static const _modes = [
    (CameraControlMode.orbit, 'Orbit'),
    (CameraControlMode.pan, 'Pan'),
    (CameraControlMode.firstPerson, 'FPV'),
  ];

  @override
  Widget build(BuildContext context) {
    return _DemoCard(
      icon: Icons.videocam,
      title: 'Camera Modes',
      description: 'setCameraControlMode: orbit / pan / first-person.',
      child: Column(
        children: [
          SizedBox(
            height: 200,
            child: Stack(
              children: [
                SceneView(
                  controller: _controller,
                  cameraControlMode: _mode,
                  onViewCreated: () {
                    _controller.setEnvironment('environments/studio_small.hdr');
                    _controller.loadModel(const ModelNode(modelPath: _helmetUrl));
                    setState(() => _ready = true);
                  },
                ),
                if (!_ready) const Center(child: CircularProgressIndicator()),
              ],
            ),
          ),
          const SizedBox(height: 8),
          SegmentedButton<CameraControlMode>(
            segments: [
              for (final (mode, label) in _modes)
                ButtonSegment(value: mode, label: Text(label)),
            ],
            selected: {_mode},
            onSelectionChanged: _ready
                ? (s) {
                    setState(() => _mode = s.first);
                    _controller.setCameraControlMode(_mode);
                  }
                : null,
          ),
          const SizedBox(height: 4),
          Text(
            'Pan / FPV are iOS-first; Android falls back to orbit (#1051).',
            style: Theme.of(context).textTheme.bodySmall,
          ),
        ],
      ),
    );
  }
}
