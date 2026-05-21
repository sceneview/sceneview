import 'package:flutter/material.dart';
import 'package:sceneview_flutter/sceneview_flutter.dart';

void main() {
  runApp(const SceneViewExampleApp());
}

class SceneViewExampleApp extends StatelessWidget {
  const SceneViewExampleApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'SceneView Flutter Example',
      theme: ThemeData(
        colorSchemeSeed: Colors.deepPurple,
        useMaterial3: true,
      ),
      home: const ModelViewerPage(),
    );
  }
}

class ModelViewerPage extends StatefulWidget {
  const ModelViewerPage({super.key});

  @override
  State<ModelViewerPage> createState() => _ModelViewerPageState();
}

class _ModelViewerPageState extends State<ModelViewerPage> {
  final _controller = SceneViewController();

  @override
  void dispose() {
    // This page created the controller, so this page disposes it. The
    // SceneView widget no longer disposes caller-supplied controllers
    // (issue #2050).
    _controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('3D Model Viewer')),
      body: Column(
        children: [
          Expanded(
            child: SceneView(
              controller: _controller,
              onViewCreated: _onSceneCreated,
            ),
          ),
          Padding(
            padding: const EdgeInsets.all(16),
            child: Row(
              mainAxisAlignment: MainAxisAlignment.spaceEvenly,
              children: [
                ElevatedButton.icon(
                  onPressed: _loadHelmet,
                  icon: const Icon(Icons.view_in_ar),
                  label: const Text('Load Model'),
                ),
                ElevatedButton.icon(
                  onPressed: _addCube,
                  icon: const Icon(Icons.square_outlined),
                  label: const Text('Add Cube'),
                ),
                ElevatedButton.icon(
                  onPressed: () => _controller.clearScene(),
                  icon: const Icon(Icons.delete_outline),
                  label: const Text('Clear'),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }

  void _onSceneCreated() {
    // Set up default environment and lighting
    _controller.setEnvironment('environments/studio_small.hdr');
    _controller.addLight(const LightNode(
      type: 'directional',
      intensity: 100000,
    ));
  }

  void _loadHelmet() {
    _controller.loadModel(const ModelNode(
      modelPath: 'models/damaged_helmet.glb',
    ));
  }

  void _addCube() {
    _controller.addGeometry(const GeometryNode(
      type: 'cube',
      size: 0.5,
      color: 0xFF6750A4,
    ));
  }
}
