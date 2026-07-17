import 'package:flutter/material.dart';
import 'package:flutter_sceneview/flutter_sceneview.dart';

/// Features tab — interactive showcase of all Flutter bridge capabilities.
///
/// Each card demonstrates a specific bridge feature with live controls.
class FeaturesPage extends StatefulWidget {
  const FeaturesPage({super.key});

  @override
  State<FeaturesPage> createState() => _FeaturesPageState();
}

class _FeaturesPageState extends State<FeaturesPage> {
  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);

    return Scaffold(
      appBar: AppBar(
        title: const Text('Bridge Features'),
      ),
      body: ListView(
        padding: const EdgeInsets.all(16),
        children: [
          // Header
          Card(
            color: theme.colorScheme.primaryContainer,
            child: Padding(
              padding: const EdgeInsets.all(16),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Row(
                    children: [
                      Icon(Icons.extension, color: theme.colorScheme.onPrimaryContainer),
                      const SizedBox(width: 8),
                      Text(
                        'SceneView Flutter Bridge',
                        style: theme.textTheme.titleMedium?.copyWith(
                          color: theme.colorScheme.onPrimaryContainer,
                        ),
                      ),
                    ],
                  ),
                  const SizedBox(height: 8),
                  Text(
                    'This page demonstrates every feature exposed by the '
                    'sceneview_flutter plugin. Each section below maps to a '
                    'specific bridge API.',
                    style: theme.textTheme.bodyMedium?.copyWith(
                      color: theme.colorScheme.onPrimaryContainer,
                    ),
                  ),
                ],
              ),
            ),
          ),
          const SizedBox(height: 16),

          // Feature cards
          _FeatureCard(
            icon: Icons.view_in_ar,
            title: 'SceneView (3D)',
            subtitle: 'io.github.sceneview.flutter/sceneview',
            description:
                'Platform view widget that embeds a native 3D renderer. '
                'Uses Filament on Android and RealityKit on iOS.',
            codeSnippet: '''SceneView(
  controller: controller,
  onViewCreated: () => controller.loadModel(
    ModelNode(modelPath: 'models/helmet.glb'),
  ),
  onTap: (nodeName) => print('Tapped: \$nodeName'),
)''',
            status: FeatureStatus.implemented,
          ),

          _FeatureCard(
            icon: Icons.camera,
            title: 'ARSceneView (AR)',
            subtitle: 'io.github.sceneview.flutter/arsceneview',
            description:
                'AR platform view with camera feed, plane detection, '
                'and model anchoring. Requires camera permission.',
            codeSnippet: '''ARSceneView(
  controller: controller,
  planeDetection: true,
  onViewCreated: () => controller.loadModel(
    ModelNode(modelPath: 'models/andy.glb'),
  ),
  onTap: (nodeName) => print('Tapped: \$nodeName'),
  onPlaneDetected: (type) => print('Plane: \$type'),
)''',
            status: FeatureStatus.implemented,
          ),

          _FeatureCard(
            icon: Icons.gamepad,
            title: 'SceneViewController',
            subtitle: 'Imperative scene control',
            description:
                'Controller for loading models, clearing the scene, '
                'setting environments, and adding nodes after creation. '
                'Android wires every method to a real Filament call; on iOS, '
                '`loadModel` and `clearScene` work today while `addGeometry`, '
                '`addLight` and `setEnvironment` are accepted but not yet '
                'rendered — tracked in the #909 umbrella.',
            codeSnippet: '''final controller = SceneViewController();

// After onViewCreated:
controller.loadModel(ModelNode(modelPath: url));
controller.setEnvironment('environments/studio_small.hdr');
controller.addLight(LightNode(type: 'directional'));
controller.addGeometry(GeometryNode(type: 'cube'));
controller.clearScene();''',
            status: FeatureStatus.implemented,
          ),

          _FeatureCard(
            icon: Icons.threed_rotation,
            title: 'ModelNode',
            subtitle: 'Position, rotation, scale',
            description:
                'Load glTF/GLB models with full transform control. '
                'Supports both asset paths and URLs. '
                'iOS currently honours path + scale only; position and rotation '
                'are tracked in the #909 bridge-parity umbrella.',
            codeSnippet: '''ModelNode(
  modelPath: 'https://example.com/model.glb',
  x: 0.0, y: -0.5, z: -2.0,
  rotationX: 0.0, rotationY: 45.0, rotationZ: 0.0,
  scale: 1.5,
)''',
            status: FeatureStatus.androidOnly,
          ),

          _FeatureCard(
            icon: Icons.touch_app,
            title: 'onTap Callback',
            subtitle: 'Node tap detection',
            description:
                'Receive callbacks when users tap on model nodes. '
                'The node name is derived from the model file name. '
                'iOS does not yet forward node taps through the bridge — '
                'tracked in the #909 umbrella.',
            codeSnippet: '''SceneView(
  onTap: (String nodeName) {
    showDialog(
      context: context,
      builder: (_) => AlertDialog(
        title: Text('Tapped: \$nodeName'),
      ),
    );
  },
)''',
            status: FeatureStatus.androidOnly,
          ),

          _FeatureCard(
            icon: Icons.grid_on,
            title: 'onPlaneDetected Callback',
            subtitle: 'AR plane detection events',
            description:
                'Receive callbacks when ARCore/ARKit detects planes. '
                'Plane types: horizontal_upward, horizontal_downward, vertical. '
                'iOS does not yet forward plane events through the bridge — '
                'tracked in the #909 umbrella.',
            codeSnippet: '''ARSceneView(
  onPlaneDetected: (String planeType) {
    // planeType: 'horizontal_upward', 'horizontal_downward',
    //            'vertical', or 'unknown'
    setState(() => detectedPlanes.add(planeType));
  },
)''',
            status: FeatureStatus.androidOnly,
          ),

          _FeatureCard(
            icon: Icons.square_outlined,
            title: 'GeometryNode',
            subtitle: 'Primitive shapes',
            description:
                'Add geometry primitives (cube, sphere, cylinder, plane) '
                'to the scene. Rendered natively on Android; the iOS '
                'RealityKit port is still pending (issue #909).',
            codeSnippet: '''controller.addGeometry(GeometryNode(
  type: 'cube',     // or 'sphere', 'cylinder', 'plane'
  size: 0.5,
  color: 0xFF005BC1,
  x: 0.0, y: 0.0, z: 0.0,
))''',
            status: FeatureStatus.androidOnly,
          ),

          _FeatureCard(
            icon: Icons.light_mode,
            title: 'LightNode',
            subtitle: 'Scene lighting',
            description:
                'Configure directional, point, or spot lights with '
                'intensity and color. Rendered natively on Android; the iOS '
                'RealityKit port is still pending (issue #909).',
            codeSnippet: '''controller.addLight(LightNode(
  type: 'directional',  // or 'point', 'spot'
  intensity: 100000,
  color: 0xFFFFFFFF,
  x: 0.0, y: 4.0, z: 0.0,
))''',
            status: FeatureStatus.androidOnly,
          ),

          _FeatureCard(
            icon: Icons.image,
            title: 'Environment (HDR)',
            subtitle: 'Image-based lighting',
            description:
                'Set an HDR environment for image-based lighting and skybox. '
                'AR scenes use camera feed instead. '
                'The iOS bridge currently acknowledges the call but does not '
                'apply the HDR to the RealityKit scene — tracked in the #909 '
                'umbrella.',
            codeSnippet: '''controller.setEnvironment(
  'environments/studio_small.hdr',
)''',
            status: FeatureStatus.androidOnly,
          ),

          // Live demo section
          const SizedBox(height: 24),
          Text(
            'Live Demos',
            style: theme.textTheme.headlineSmall,
          ),
          const SizedBox(height: 8),

          _LiveGeometryDemo(),
          const SizedBox(height: 12),
          _LiveLightDemo(),
        ],
      ),
    );
  }
}

// -- Feature card widget --

enum FeatureStatus { implemented, androidOnly, placeholder, planned }

class _FeatureCard extends StatelessWidget {
  final IconData icon;
  final String title;
  final String subtitle;
  final String description;
  final String codeSnippet;
  final FeatureStatus status;

  const _FeatureCard({
    required this.icon,
    required this.title,
    required this.subtitle,
    required this.description,
    required this.codeSnippet,
    required this.status,
  });

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);

    final (statusLabel, statusColor) = switch (status) {
      FeatureStatus.implemented => ('Implemented', Colors.green),
      FeatureStatus.androidOnly => ('Android only', Colors.blue),
      FeatureStatus.placeholder => ('Placeholder', Colors.orange),
      FeatureStatus.planned => ('Planned', Colors.grey),
    };

    return Card(
      margin: const EdgeInsets.only(bottom: 12),
      child: ExpansionTile(
        leading: Icon(icon, color: theme.colorScheme.primary),
        title: Row(
          children: [
            Expanded(child: Text(title)),
            Container(
              padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 2),
              decoration: BoxDecoration(
                color: statusColor.withOpacity(0.15),
                borderRadius: BorderRadius.circular(12),
              ),
              child: Text(
                statusLabel,
                style: theme.textTheme.labelSmall?.copyWith(color: statusColor),
              ),
            ),
          ],
        ),
        subtitle: Text(subtitle, style: theme.textTheme.bodySmall),
        children: [
          Padding(
            padding: const EdgeInsets.fromLTRB(16, 0, 16, 16),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(description),
                const SizedBox(height: 12),
                Container(
                  width: double.infinity,
                  padding: const EdgeInsets.all(12),
                  decoration: BoxDecoration(
                    color: theme.colorScheme.surfaceContainerHighest,
                    borderRadius: BorderRadius.circular(8),
                  ),
                  child: SelectableText(
                    codeSnippet,
                    style: theme.textTheme.bodySmall?.copyWith(
                      fontFamily: 'monospace',
                      fontSize: 12,
                    ),
                  ),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }
}

// -- Live geometry demo --

class _LiveGeometryDemo extends StatefulWidget {
  @override
  State<_LiveGeometryDemo> createState() => _LiveGeometryDemoState();
}

class _LiveGeometryDemoState extends State<_LiveGeometryDemo> {
  final _controller = SceneViewController();
  bool _ready = false;
  String _selectedType = 'cube';
  double _size = 0.5;
  Color _color = const Color(0xFF005BC1);
  int _nodeCount = 0;

  static const _types = ['cube', 'sphere', 'cylinder', 'plane'];

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);

    return Card(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Padding(
            padding: const EdgeInsets.fromLTRB(16, 16, 16, 8),
            child: Row(
              children: [
                Icon(Icons.square_outlined, color: theme.colorScheme.primary),
                const SizedBox(width: 8),
                Text('Geometry Nodes', style: theme.textTheme.titleMedium),
                const Spacer(),
                Text('$_nodeCount added', style: theme.textTheme.bodySmall),
              ],
            ),
          ),

          SizedBox(
            height: 200,
            child: Stack(
              children: [
                SceneView(
                  controller: _controller,
                  onViewCreated: () {
                    _controller.setEnvironment('environments/studio_small.hdr');
                    _controller.addLight(const LightNode(type: 'directional', intensity: 100000));
                    setState(() => _ready = true);
                  },
                ),
                if (!_ready)
                  const Center(child: CircularProgressIndicator()),
              ],
            ),
          ),

          Padding(
            padding: const EdgeInsets.all(16),
            child: Column(
              children: [
                // Type selector
                SegmentedButton<String>(
                  segments: _types
                      .map((t) => ButtonSegment(value: t, label: Text(t)))
                      .toList(),
                  selected: {_selectedType},
                  onSelectionChanged: (s) => setState(() => _selectedType = s.first),
                ),
                const SizedBox(height: 8),

                // Size slider
                Row(
                  children: [
                    const Text('Size:'),
                    Expanded(
                      child: Slider(
                        value: _size,
                        min: 0.1,
                        max: 2.0,
                        onChanged: (v) => setState(() => _size = v),
                      ),
                    ),
                    Text(_size.toStringAsFixed(1)),
                  ],
                ),

                // Color chips
                Row(
                  children: [
                    const Text('Color: '),
                    const SizedBox(width: 8),
                    for (final c in [
                      const Color(0xFF005BC1),
                      const Color(0xFFE53935),
                      const Color(0xFF43A047),
                      const Color(0xFFFDD835),
                      const Color(0xFF8E24AA),
                    ])
                      Padding(
                        padding: const EdgeInsets.only(right: 4),
                        child: GestureDetector(
                          onTap: () => setState(() => _color = c),
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
                const SizedBox(height: 12),

                // Action buttons
                Row(
                  children: [
                    Expanded(
                      child: FilledButton.icon(
                        onPressed: _ready ? _addGeometry : null,
                        icon: const Icon(Icons.add),
                        label: Text('Add $_selectedType'),
                      ),
                    ),
                    const SizedBox(width: 8),
                    OutlinedButton.icon(
                      onPressed: _ready
                          ? () {
                              _controller.clearScene();
                              setState(() => _nodeCount = 0);
                            }
                          : null,
                      icon: const Icon(Icons.delete_outline),
                      label: const Text('Clear'),
                    ),
                  ],
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }

  void _addGeometry() {
    _controller.addGeometry(GeometryNode(
      type: _selectedType,
      size: _size,
      color: _color.value,
    ));
    setState(() => _nodeCount++);
  }
}

// -- Live light demo --

class _LiveLightDemo extends StatefulWidget {
  @override
  State<_LiveLightDemo> createState() => _LiveLightDemoState();
}

class _LiveLightDemoState extends State<_LiveLightDemo> {
  final _controller = SceneViewController();
  bool _ready = false;
  String _lightType = 'directional';
  double _intensity = 100000;
  bool _modelLoaded = false;

  static const _lightTypes = ['directional', 'point', 'spot'];

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);

    return Card(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Padding(
            padding: const EdgeInsets.fromLTRB(16, 16, 16, 8),
            child: Row(
              children: [
                Icon(Icons.light_mode, color: theme.colorScheme.primary),
                const SizedBox(width: 8),
                Text('Light Nodes', style: theme.textTheme.titleMedium),
              ],
            ),
          ),

          SizedBox(
            height: 200,
            child: Stack(
              children: [
                SceneView(
                  controller: _controller,
                  onViewCreated: () {
                    _controller.setEnvironment('environments/studio_small.hdr');
                    setState(() => _ready = true);
                    _loadSampleModel();
                  },
                ),
                if (!_ready)
                  const Center(child: CircularProgressIndicator()),
              ],
            ),
          ),

          Padding(
            padding: const EdgeInsets.all(16),
            child: Column(
              children: [
                // Light type selector
                SegmentedButton<String>(
                  segments: _lightTypes
                      .map((t) => ButtonSegment(value: t, label: Text(t)))
                      .toList(),
                  selected: {_lightType},
                  onSelectionChanged: (s) {
                    setState(() => _lightType = s.first);
                    _applyLight();
                  },
                ),
                const SizedBox(height: 8),

                // Intensity slider
                Row(
                  children: [
                    const Text('Intensity:'),
                    Expanded(
                      child: Slider(
                        value: _intensity,
                        min: 0,
                        max: 300000,
                        onChanged: (v) {
                          setState(() => _intensity = v);
                          _applyLight();
                        },
                      ),
                    ),
                    SizedBox(
                      width: 64,
                      child: Text(
                        '${(_intensity / 1000).toStringAsFixed(0)}k',
                        textAlign: TextAlign.end,
                      ),
                    ),
                  ],
                ),
                const SizedBox(height: 8),

                FilledButton.icon(
                  onPressed: _ready ? _applyLight : null,
                  icon: const Icon(Icons.lightbulb),
                  label: Text('Apply $_lightType Light'),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }

  void _loadSampleModel() {
    if (!_ready || _modelLoaded) return;
    _controller.loadModel(const ModelNode(
      modelPath: 'https://raw.githubusercontent.com/KhronosGroup/glTF-Sample-Assets/main/Models/DamagedHelmet/glTF-Binary/DamagedHelmet.glb',
    ));
    _modelLoaded = true;
    _applyLight();
  }

  void _applyLight() {
    if (!_ready) return;
    _controller.addLight(LightNode(
      type: _lightType,
      intensity: _intensity,
    ));
  }
}
