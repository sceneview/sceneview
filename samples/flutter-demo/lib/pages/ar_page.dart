import 'package:flutter/material.dart';
import 'package:flutter_sceneview/flutter_sceneview.dart';

/// AR tab — demonstrates ARSceneView with plane detection and model placement.
///
/// Showcases:
/// - [ARSceneView] widget (AR platform view with camera feed)
/// - [onPlaneDetected] callback (horizontal/vertical plane detection)
/// - [onTap] callback in AR mode
/// - AR/3D mode toggle
/// - Model loading in AR with position and scale
class ARPage extends StatefulWidget {
  const ARPage({super.key});

  @override
  State<ARPage> createState() => _ARPageState();
}

class _ARPageState extends State<ARPage> {
  final _arController = SceneViewController();
  final _3dController = SceneViewController();

  bool _arMode = true;
  bool _sceneReady = false;
  bool _planeDetection = true;
  final List<String> _detectedPlanes = [];
  String? _lastTappedNode;
  int _modelsPlaced = 0;

  // AR session recording (v4.3.0, iOS via ReplayKit).
  late final ARRecorder _recorder = ARRecorder(_arController);
  ARRecorderState _recorderState = ARRecorderState.idle;
  String? _lastRecordingPath;

  @override
  void dispose() {
    _recorder.dispose();
    super.dispose();
  }

  // Model to place in AR
  static const _arModels = [
    _ARModel('Avocado', 'https://raw.githubusercontent.com/KhronosGroup/glTF-Sample-Assets/main/Models/Avocado/glTF-Binary/Avocado.glb', 1.0),
    _ARModel('Duck', 'https://raw.githubusercontent.com/KhronosGroup/glTF-Sample-Assets/main/Models/Duck/glTF-Binary/Duck.glb', 0.5),
    _ARModel('Box', 'https://raw.githubusercontent.com/KhronosGroup/glTF-Sample-Assets/main/Models/Box/glTF-Binary/Box.glb', 0.3),
  ];

  int _selectedModelIndex = 0;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);

    return Scaffold(
      appBar: AppBar(
        title: Text(_arMode ? 'AR Mode' : '3D Mode'),
        actions: [
          // AR/3D toggle
          SegmentedButton<bool>(
            segments: const [
              ButtonSegment(value: false, icon: Icon(Icons.view_in_ar), label: Text('3D')),
              ButtonSegment(value: true, icon: Icon(Icons.camera), label: Text('AR')),
            ],
            selected: {_arMode},
            onSelectionChanged: (set) {
              setState(() {
                _arMode = set.first;
                _sceneReady = false;
                _detectedPlanes.clear();
                _modelsPlaced = 0;
              });
            },
          ),
          const SizedBox(width: 8),
        ],
      ),
      body: Column(
        children: [
          // Status bar
          if (_arMode && _detectedPlanes.isNotEmpty)
            Container(
              width: double.infinity,
              padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
              color: theme.colorScheme.primaryContainer,
              child: Row(
                children: [
                  Icon(Icons.check_circle, color: theme.colorScheme.primary, size: 16),
                  const SizedBox(width: 8),
                  Expanded(
                    child: Text(
                      '${_detectedPlanes.length} plane(s) detected: '
                      '${_detectedPlanes.take(3).join(", ")}',
                      style: theme.textTheme.bodySmall?.copyWith(
                        color: theme.colorScheme.onPrimaryContainer,
                      ),
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                    ),
                  ),
                ],
              ),
            ),

          // Tap feedback
          if (_lastTappedNode != null)
            Container(
              width: double.infinity,
              padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 6),
              color: theme.colorScheme.tertiaryContainer,
              child: Row(
                children: [
                  Icon(Icons.touch_app, size: 16, color: theme.colorScheme.onTertiaryContainer),
                  const SizedBox(width: 8),
                  Text(
                    'Tapped: $_lastTappedNode',
                    style: theme.textTheme.bodySmall?.copyWith(
                      color: theme.colorScheme.onTertiaryContainer,
                    ),
                  ),
                ],
              ),
            ),

          // Scene
          Expanded(
            child: Stack(
              children: [
                if (_arMode)
                  ARSceneView(
                    controller: _arController,
                    planeDetection: _planeDetection,
                    onViewCreated: _onARSceneCreated,
                    onTap: _onNodeTapped,
                    onPlaneDetected: _onPlaneDetected,
                  )
                else
                  SceneView(
                    controller: _3dController,
                    onViewCreated: _on3DSceneCreated,
                    onTap: _onNodeTapped,
                  ),
                if (!_sceneReady)
                  Container(
                    color: theme.colorScheme.surface.withOpacity(0.7),
                    child: Center(
                      child: Column(
                        mainAxisSize: MainAxisSize.min,
                        children: [
                          const CircularProgressIndicator(),
                          const SizedBox(height: 16),
                          Text(
                            _arMode
                                ? 'Initializing AR...\nPoint camera at a flat surface'
                                : 'Loading 3D scene...',
                            textAlign: TextAlign.center,
                          ),
                        ],
                      ),
                    ),
                  ),
              ],
            ),
          ),

          // Controls
          _buildControls(theme),
        ],
      ),
    );
  }

  Widget _buildControls(ThemeData theme) {
    return Container(
      padding: const EdgeInsets.all(16),
      decoration: BoxDecoration(
        color: theme.colorScheme.surfaceContainerHigh,
        borderRadius: const BorderRadius.vertical(top: Radius.circular(16)),
      ),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          // Model selector
          Row(
            children: [
              Text('Model:', style: theme.textTheme.titleSmall),
              const SizedBox(width: 12),
              Expanded(
                child: SegmentedButton<int>(
                  segments: _arModels
                      .asMap()
                      .entries
                      .map((e) => ButtonSegment(
                            value: e.key,
                            label: Text(e.value.name),
                          ))
                      .toList(),
                  selected: {_selectedModelIndex},
                  onSelectionChanged: (set) {
                    setState(() => _selectedModelIndex = set.first);
                  },
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
                  onPressed: _sceneReady ? _placeModel : null,
                  icon: const Icon(Icons.add_circle_outline),
                  label: Text('Place Model ($_modelsPlaced)'),
                ),
              ),
              const SizedBox(width: 8),
              Expanded(
                child: OutlinedButton.icon(
                  onPressed: _sceneReady ? _clearModels : null,
                  icon: const Icon(Icons.delete_outline),
                  label: const Text('Clear All'),
                ),
              ),
            ],
          ),

          if (_arMode) ...[
            const SizedBox(height: 8),
            SwitchListTile(
              title: const Text('Plane Detection'),
              subtitle: const Text('Detect horizontal and vertical surfaces'),
              value: _planeDetection,
              dense: true,
              onChanged: (v) => setState(() => _planeDetection = v),
            ),
            const SizedBox(height: 8),
            // AR session recording (v4.3.0) — iOS only via ReplayKit.
            Row(
              children: [
                Expanded(
                  child: FilledButton.tonalIcon(
                    onPressed: _sceneReady ? _toggleRecording : null,
                    icon: Icon(
                      _recorderState == ARRecorderState.recording
                          ? Icons.stop_circle
                          : Icons.fiber_manual_record,
                      color: _recorderState == ARRecorderState.recording
                          ? theme.colorScheme.error
                          : null,
                    ),
                    label: Text(
                      _recorderState == ARRecorderState.recording
                          ? 'Stop Recording'
                          : 'Record (iOS)',
                    ),
                  ),
                ),
                const SizedBox(width: 8),
                Expanded(
                  child: OutlinedButton.icon(
                    onPressed: _lastRecordingPath != null
                        ? _saveRecordingToPhotos
                        : null,
                    icon: const Icon(Icons.save_alt),
                    label: const Text('Save to Photos'),
                  ),
                ),
              ],
            ),
          ],
        ],
      ),
    );
  }

  Future<void> _toggleRecording() async {
    try {
      if (_recorderState == ARRecorderState.recording) {
        final path = await _recorder.stopRecording();
        setState(() {
          _recorderState = ARRecorderState.idle;
          _lastRecordingPath = path;
        });
        _showSnack('Recording saved to: $path');
      } else {
        await _recorder.startRecording();
        setState(() => _recorderState = ARRecorderState.recording);
      }
    } on UnsupportedError catch (e) {
      _showSnack(e.message ?? 'ARRecorder is iOS-only');
    } catch (e) {
      setState(() => _recorderState = ARRecorderState.error);
      _showSnack('Recording failed: $e');
    }
  }

  Future<void> _saveRecordingToPhotos() async {
    final path = _lastRecordingPath;
    if (path == null) return;
    try {
      await _recorder.saveToPhotoLibrary(path);
      _showSnack('Saved to Photos');
    } on UnsupportedError catch (e) {
      _showSnack(e.message ?? 'Save to Photos is iOS-only');
    } catch (e) {
      _showSnack('Save failed: $e');
    }
  }

  void _showSnack(String message) {
    if (!mounted) return;
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(content: Text(message)),
    );
  }

  // -- Callbacks --

  void _onARSceneCreated() {
    setState(() => _sceneReady = true);
  }

  void _on3DSceneCreated() {
    _3dController.setEnvironment('environments/studio_small.hdr');
    _3dController.addLight(const LightNode(
      type: 'directional',
      intensity: 100000,
    ));
    setState(() => _sceneReady = true);
    _placeModel();
  }

  void _onNodeTapped(String nodeName) {
    setState(() => _lastTappedNode = nodeName);
  }

  void _onPlaneDetected(String planeType) {
    if (!_detectedPlanes.contains(planeType)) {
      setState(() => _detectedPlanes.add(planeType));
    }
  }

  void _placeModel() {
    if (!_sceneReady) return;
    final model = _arModels[_selectedModelIndex];
    final controller = _arMode ? _arController : _3dController;

    controller.loadModel(ModelNode(
      modelPath: model.url,
      scale: model.scale,
      y: _arMode ? 0 : -0.5,
    ));

    setState(() => _modelsPlaced++);
  }

  void _clearModels() {
    final controller = _arMode ? _arController : _3dController;
    controller.clearScene();
    setState(() {
      _modelsPlaced = 0;
      _lastTappedNode = null;
    });
  }
}

class _ARModel {
  final String name;
  final String url;
  final double scale;

  const _ARModel(this.name, this.url, this.scale);
}
