import 'dart:io' show Platform;

import 'package:flutter/material.dart';
import 'package:flutter_sceneview/flutter_sceneview.dart';

import '../services/sketchfab_service.dart';

/// 3D Viewer tab — loads models from Sketchfab search or built-in URLs.
///
/// Demonstrates:
/// - [SceneView] widget (3D platform view)
/// - [SceneViewController] (loadModel, clearScene, setEnvironment, addLight)
/// - [ModelNode] with position, rotation, scale
/// - [onTap] callback when a model is tapped
/// - Model rotation via sliders
class ViewerPage extends StatefulWidget {
  const ViewerPage({super.key});

  @override
  State<ViewerPage> createState() => _ViewerPageState();
}

class _ViewerPageState extends State<ViewerPage> {
  final _controller = SceneViewController();
  final _searchController = TextEditingController();
  final _sketchfab = SketchfabService();

  bool _sceneReady = false;
  bool _isSearching = false;
  String? _loadedModelName;
  String? _lastTappedNode;
  List<SketchfabModel> _searchResults = [];

  // Rotation state (degrees)
  double _rotationX = 0;
  double _rotationY = 0;
  double _rotationZ = 0;
  double _scale = 1.0;

  // Camera control mode (v4.3.0) — orbit / pan / firstPerson.
  CameraControlMode _cameraMode = CameraControlMode.orbit;

  // Built-in sample models.
  //
  // Each entry carries a source per platform because the two renderers do not
  // read the same formats. Android (Filament) loads `.glb`, including straight
  // from an `https://` URL. Apple (RealityKit) loads only `.usdz` / `.reality`
  // and `ModelNode.load(_:)` resolves a *bundle resource name*, not a URL — so
  // a remote `.glb` fails twice over there.
  //
  // Passing the GLB URL on both platforms is what left the iOS viewer empty:
  // every `loadModel` threw, the bridge logged it and moved on, and no model
  // ever appeared — which also made `onTap` unreachable from this demo.
  //
  // `usdzResource` names a file bundled into Runner.app (see
  // ios/Runner/Models/, wired into the Runner target's Resources build phase).
  // A null means no USDZ is bundled for that model: the entry stays visible and
  // is disabled on iOS rather than silently loading nothing.
  static const _sampleModels = [
    _SampleModel(
      'Fox',
      'https://raw.githubusercontent.com/KhronosGroup/glTF-Sample-Assets/main/Models/Fox/glTF-Binary/Fox.glb',
      Icons.pets,
      usdzResource: 'khronos_fox.usdz',
    ),
    _SampleModel('Damaged Helmet', 'https://raw.githubusercontent.com/KhronosGroup/glTF-Sample-Assets/main/Models/DamagedHelmet/glTF-Binary/DamagedHelmet.glb', Icons.sports_motorsports),
    _SampleModel('Avocado', 'https://raw.githubusercontent.com/KhronosGroup/glTF-Sample-Assets/main/Models/Avocado/glTF-Binary/Avocado.glb', Icons.eco),
    _SampleModel('Flight Helmet', 'https://raw.githubusercontent.com/KhronosGroup/glTF-Sample-Assets/main/Models/FlightHelmet/glTF/FlightHelmet.gltf', Icons.flight),
    _SampleModel('Water Bottle', 'https://raw.githubusercontent.com/KhronosGroup/glTF-Sample-Assets/main/Models/WaterBottle/glTF-Binary/WaterBottle.glb', Icons.water_drop),
  ];

  /// The sample models this platform can actually render.
  static List<_SampleModel> get _loadableSampleModels =>
      _sampleModels.where((m) => m.isLoadableOnThisPlatform).toList();

  @override
  void dispose() {
    _searchController.dispose();
    _sketchfab.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);

    return Scaffold(
      appBar: AppBar(
        title: const Text('3D Viewer'),
        actions: [
          if (_loadedModelName != null)
            Padding(
              padding: const EdgeInsets.only(right: 8),
              child: Chip(label: Text(_loadedModelName!)),
            ),
          IconButton(
            onPressed: _sceneReady ? _clearScene : null,
            icon: const Icon(Icons.delete_outline),
            tooltip: 'Clear scene',
          ),
        ],
      ),
      body: Column(
        children: [
          // Tap feedback banner
          if (_lastTappedNode != null)
            MaterialBanner(
              content: Text('Tapped: $_lastTappedNode'),
              leading: const Icon(Icons.touch_app),
              backgroundColor: theme.colorScheme.tertiaryContainer,
              actions: [
                TextButton(
                  onPressed: () => setState(() => _lastTappedNode = null),
                  child: const Text('Dismiss'),
                ),
              ],
            ),

          // 3D Scene
          Expanded(
            flex: 3,
            child: Stack(
              children: [
                SceneView(
                  controller: _controller,
                  cameraControlMode: _cameraMode,
                  onViewCreated: _onSceneCreated,
                  onTap: _onNodeTapped,
                ),
                if (!_sceneReady)
                  const Center(child: CircularProgressIndicator()),
              ],
            ),
          ),

          // Camera control mode picker (v4.3.0)
          _buildCameraModePicker(theme),

          const Divider(height: 1),

          // Rotation controls
          _buildRotationControls(theme),

          const Divider(height: 1),

          // Search bar
          Padding(
            padding: const EdgeInsets.fromLTRB(16, 8, 16, 4),
            child: SearchBar(
              controller: _searchController,
              hintText: 'Search Sketchfab models...',
              leading: const Icon(Icons.search),
              trailing: [
                if (_isSearching)
                  const SizedBox(
                    width: 20,
                    height: 20,
                    child: CircularProgressIndicator(strokeWidth: 2),
                  )
                else
                  IconButton(
                    onPressed: _performSearch,
                    icon: const Icon(Icons.send),
                  ),
              ],
              onSubmitted: (_) => _performSearch(),
            ),
          ),

          // Results or sample models
          Expanded(
            flex: 2,
            child: _searchResults.isNotEmpty
                ? _buildSearchResults()
                : _buildSampleModels(theme),
          ),
        ],
      ),
    );
  }

  Widget _buildCameraModePicker(ThemeData theme) {
    return Padding(
      padding: const EdgeInsets.fromLTRB(16, 8, 16, 8),
      child: Row(
        children: [
          Text('Camera:', style: theme.textTheme.titleSmall),
          const SizedBox(width: 12),
          Expanded(
            child: SegmentedButton<CameraControlMode>(
              segments: const [
                ButtonSegment(
                  value: CameraControlMode.orbit,
                  icon: Icon(Icons.threesixty),
                  label: Text('Orbit'),
                ),
                ButtonSegment(
                  value: CameraControlMode.pan,
                  icon: Icon(Icons.pan_tool_alt),
                  label: Text('Pan'),
                ),
                ButtonSegment(
                  value: CameraControlMode.firstPerson,
                  icon: Icon(Icons.visibility),
                  label: Text('FPV'),
                ),
              ],
              selected: {_cameraMode},
              onSelectionChanged: (set) {
                setState(() => _cameraMode = set.first);
                if (_sceneReady) {
                  _controller.setCameraControlMode(_cameraMode);
                }
              },
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildRotationControls(ThemeData theme) {
    return ExpansionTile(
      title: const Text('Model Transform'),
      leading: const Icon(Icons.threed_rotation),
      initiallyExpanded: false,
      children: [
        Padding(
          padding: const EdgeInsets.symmetric(horizontal: 16),
          child: Column(
            children: [
              _buildSlider('Rotation X', _rotationX, -180, 180, (v) {
                setState(() => _rotationX = v);
                _reloadWithTransform();
              }),
              _buildSlider('Rotation Y', _rotationY, -180, 180, (v) {
                setState(() => _rotationY = v);
                _reloadWithTransform();
              }),
              _buildSlider('Rotation Z', _rotationZ, -180, 180, (v) {
                setState(() => _rotationZ = v);
                _reloadWithTransform();
              }),
              _buildSlider('Scale', _scale, 0.1, 5.0, (v) {
                setState(() => _scale = v);
                _reloadWithTransform();
              }),
              const SizedBox(height: 8),
              OutlinedButton.icon(
                onPressed: () {
                  setState(() {
                    _rotationX = 0;
                    _rotationY = 0;
                    _rotationZ = 0;
                    _scale = 1.0;
                  });
                  _reloadWithTransform();
                },
                icon: const Icon(Icons.restart_alt),
                label: const Text('Reset'),
              ),
              const SizedBox(height: 8),
            ],
          ),
        ),
      ],
    );
  }

  Widget _buildSlider(
    String label,
    double value,
    double min,
    double max,
    ValueChanged<double> onChanged,
  ) {
    return Row(
      children: [
        SizedBox(
          width: 80,
          child: Text(label, style: Theme.of(context).textTheme.bodySmall),
        ),
        Expanded(
          child: Slider(
            value: value,
            min: min,
            max: max,
            onChanged: onChanged,
          ),
        ),
        SizedBox(
          width: 48,
          child: Text(
            value.toStringAsFixed(1),
            style: Theme.of(context).textTheme.bodySmall,
            textAlign: TextAlign.end,
          ),
        ),
      ],
    );
  }

  Widget _buildSampleModels(ThemeData theme) {
    return ListView(
      padding: const EdgeInsets.all(8),
      children: [
        Padding(
          padding: const EdgeInsets.all(8),
          child: Text(
            'Sample Models (Khronos glTF)',
            style: theme.textTheme.titleSmall,
          ),
        ),
        ..._sampleModels.map((model) {
          final path = model.modelPath;
          // An entry with no source for this platform stays listed but is
          // disabled and says why, rather than looking tappable and loading
          // nothing.
          final unavailable = path == null;
          return ListTile(
            enabled: !unavailable,
            leading: Icon(model.icon),
            title: Text(model.name),
            subtitle: Text(
              unavailable
                  ? 'No USDZ bundled — RealityKit cannot read .glb'
                  : 'glTF Sample Asset',
            ),
            trailing: Icon(unavailable ? Icons.block : Icons.download),
            onTap: unavailable ? null : () => _loadModel(path, model.name),
          );
        }),
      ],
    );
  }

  Widget _buildSearchResults() {
    return ListView.builder(
      padding: const EdgeInsets.all(8),
      itemCount: _searchResults.length,
      itemBuilder: (context, index) {
        final model = _searchResults[index];
        return Card(
          clipBehavior: Clip.antiAlias,
          child: ListTile(
            leading: model.thumbnailUrl != null
                ? ClipRRect(
                    borderRadius: BorderRadius.circular(8),
                    child: Image.network(
                      model.thumbnailUrl!,
                      width: 56,
                      height: 56,
                      fit: BoxFit.cover,
                      errorBuilder: (_, __, ___) => const Icon(Icons.broken_image),
                    ),
                  )
                : const Icon(Icons.view_in_ar),
            title: Text(model.name, maxLines: 1, overflow: TextOverflow.ellipsis),
            subtitle: Text(
              '${model.authorName ?? "Unknown"} -- '
              '${model.viewCount} views, ${model.likeCount} likes',
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
            ),
            trailing: const Icon(Icons.open_in_new),
            onTap: () {
              // Show info dialog -- actual download requires Sketchfab auth
              _showModelInfoDialog(model);
            },
          ),
        );
      },
    );
  }

  void _showModelInfoDialog(SketchfabModel model) {
    showDialog(
      context: context,
      builder: (ctx) => AlertDialog(
        title: Text(model.name),
        content: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            if (model.thumbnailUrl != null)
              ClipRRect(
                borderRadius: BorderRadius.circular(12),
                child: Image.network(
                  model.thumbnailUrl!,
                  height: 200,
                  width: double.infinity,
                  fit: BoxFit.cover,
                  errorBuilder: (_, __, ___) => const SizedBox.shrink(),
                ),
              ),
            const SizedBox(height: 12),
            Text('Author: ${model.authorName ?? "Unknown"}'),
            Text('Views: ${model.viewCount}'),
            Text('Likes: ${model.likeCount}'),
            const SizedBox(height: 8),
            Text(
              'Downloading requires Sketchfab authentication. '
              'Use the sample models below for instant 3D preview.',
              style: Theme.of(ctx).textTheme.bodySmall,
            ),
          ],
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(ctx),
            child: const Text('Close'),
          ),
        ],
      ),
    );
  }

  // -- Scene callbacks --

  void _onSceneCreated() {
    _controller.setEnvironment('environments/studio_small.hdr');
    _controller.addLight(const LightNode(
      type: 'directional',
      intensity: 100000,
    ));
    setState(() => _sceneReady = true);
    // Load the first sample this platform can actually render.
    final first = _loadableSampleModels.firstOrNull;
    if (first != null) {
      _loadModel(first.modelPath!, first.name);
    }
  }

  void _onNodeTapped(String nodeName) {
    setState(() => _lastTappedNode = nodeName);
  }

  void _clearScene() {
    _controller.clearScene();
    setState(() {
      _loadedModelName = null;
      _lastTappedNode = null;
    });
  }

  /// The source currently loaded — a remote `.glb` URL on Android, a bundled
  /// `.usdz` resource name on iOS. Kept so the rotation/scale sliders can
  /// reload the same model.
  String? _currentModelPath;

  void _loadModel(String path, String name) {
    if (!_sceneReady) return;
    _currentModelPath = path;
    _controller.clearScene();
    _controller.loadModel(ModelNode(
      modelPath: path,
      rotationX: _rotationX,
      rotationY: _rotationY,
      rotationZ: _rotationZ,
      scale: _scale,
    ));
    setState(() {
      _loadedModelName = name;
      _lastTappedNode = null;
    });
  }

  void _reloadWithTransform() {
    if (!_sceneReady || _currentModelPath == null) return;
    _controller.clearScene();
    _controller.loadModel(ModelNode(
      modelPath: _currentModelPath!,
      rotationX: _rotationX,
      rotationY: _rotationY,
      rotationZ: _rotationZ,
      scale: _scale,
    ));
  }

  Future<void> _performSearch() async {
    final query = _searchController.text.trim();
    if (query.isEmpty) return;

    setState(() => _isSearching = true);
    try {
      final result = await _sketchfab.search(query);
      setState(() => _searchResults = result.models);
    } on SketchfabException catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text(e.message)),
        );
      }
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('Search failed: $e')),
        );
      }
    } finally {
      if (mounted) setState(() => _isSearching = false);
    }
  }
}

class _SampleModel {
  final String name;

  /// Remote `.glb` — the Android (Filament) source.
  final String url;

  final IconData icon;

  /// Bundled `.usdz` resource name — the iOS (RealityKit) source. Null when no
  /// USDZ is bundled for this model, which makes it unloadable on Apple
  /// platforms.
  final String? usdzResource;

  const _SampleModel(this.name, this.url, this.icon, {this.usdzResource});

  /// The path handed to `SceneViewController.loadModel` on the running
  /// platform. Returns null when this platform has no usable source.
  String? get modelPath => Platform.isIOS || Platform.isMacOS ? usdzResource : url;

  bool get isLoadableOnThisPlatform => modelPath != null;
}
