import React, { useState, useCallback } from 'react';
import {
  SafeAreaView,
  StyleSheet,
  Text,
  View,
  Switch,
  TouchableOpacity,
  FlatList,
  TextInput,
  ActivityIndicator,
  ScrollView,
  Alert,
  StatusBar,
  Platform,
} from 'react-native';
import {
  SceneView,
  ARSceneView,
  ARRecorder,
  type ModelNode,
  type GeometryNode,
  type LightNode,
  type CameraControlMode,
} from '@sceneview-sdk/react-native';
import { UpdateChecker } from './UpdateChecker';
import { DoublePendulumTab } from './DoublePendulumTab';

// ---------------------------------------------------------------------------
// Types
// ---------------------------------------------------------------------------

interface SketchfabResult {
  uid: string;
  name: string;
  thumbnailUrl: string;
  user: { displayName: string };
}

type TabId =
  | 'search'
  | 'geometry'
  | 'materials'
  | 'lights'
  | 'animation'
  | 'environment'
  | 'physics'
  | 'ar';

interface PlaygroundShape {
  id: string;
  type: GeometryNode['type'];
  color: string;
  position: [number, number, number];
  size: [number, number, number];
}

// ---------------------------------------------------------------------------
// Constants
// ---------------------------------------------------------------------------

const VERSION = '4.15.3';

const ENVIRONMENT = 'environments/studio_small.hdr';

const TABS: { id: TabId; label: string; icon: string }[] = [
  { id: 'search', label: 'Search', icon: 'Q' },
  { id: 'geometry', label: 'Geometry', icon: 'G' },
  { id: 'materials', label: 'Materials', icon: 'M' },
  { id: 'lights', label: 'Lights', icon: 'L' },
  { id: 'animation', label: 'Animation', icon: '▶' },
  { id: 'environment', label: 'Environ.', icon: 'E' },
  { id: 'physics', label: 'Physics', icon: 'P' },
  { id: 'ar', label: 'AR', icon: 'A' },
];

/**
 * Animated glTF sample models (Khronos sample assets). The RN native bridge
 * auto-plays a loaded model's animation clip, so any model that ships one
 * will animate without extra wiring.
 */
const ANIMATED_MODELS: { label: string; src: string; scale: number }[] = [
  {
    label: 'Fox (run)',
    src: 'https://raw.githubusercontent.com/KhronosGroup/glTF-Sample-Assets/main/Models/Fox/glTF-Binary/Fox.glb',
    scale: 0.03,
  },
  {
    label: 'Box Animated',
    src: 'https://raw.githubusercontent.com/KhronosGroup/glTF-Sample-Assets/main/Models/BoxAnimated/glTF-Binary/BoxAnimated.glb',
    scale: 0.6,
  },
  {
    label: 'BrainStem',
    src: 'https://raw.githubusercontent.com/KhronosGroup/glTF-Sample-Assets/main/Models/BrainStem/glTF-Binary/BrainStem.glb',
    scale: 0.7,
  },
];

/** HDR environments available to the Environment demo. */
const ENVIRONMENTS: { label: string; path: string | null }[] = [
  { label: 'Studio (small)', path: 'environments/studio_small.hdr' },
  { label: 'None (neutral)', path: null },
];

const SHAPE_TYPES: GeometryNode['type'][] = ['cube', 'sphere', 'cylinder', 'plane'];

const CAMERA_MODES: { id: CameraControlMode; label: string }[] = [
  { id: 'orbit', label: 'Orbit' },
  { id: 'pan', label: 'Pan' },
  { id: 'firstPerson', label: 'FPV' },
];

const PRESET_COLORS = [
  '#E53935', '#D81B60', '#8E24AA', '#5E35B1',
  '#3949AB', '#1E88E5', '#039BE5', '#00ACC1',
  '#00897B', '#43A047', '#7CB342', '#C0CA33',
  '#FDD835', '#FFB300', '#FB8C00', '#F4511E',
];

const LIGHT_TYPES: LightNode['type'][] = ['directional', 'point', 'spot'];

const LIGHT_PRESETS: { label: string; nodes: LightNode[] }[] = [
  {
    label: 'Warm Sunset',
    nodes: [
      { type: 'directional', intensity: 80000, color: '#FF8C00', direction: [-1, -1, -1] },
      { type: 'point', intensity: 50000, color: '#FFD700', position: [2, 2, 0] },
    ],
  },
  {
    label: 'Cool Studio',
    nodes: [
      { type: 'directional', intensity: 100000, color: '#E0E8FF', direction: [0, -1, -1] },
      { type: 'point', intensity: 40000, color: '#B0C4FF', position: [-2, 1, 2] },
      { type: 'point', intensity: 40000, color: '#FFE0B0', position: [2, 1, 2] },
    ],
  },
  {
    label: 'Dramatic Spot',
    nodes: [
      { type: 'spot', intensity: 200000, color: '#FFFFFF', position: [0, 3, 0], direction: [0, -1, 0] },
    ],
  },
  {
    label: 'RGB Party',
    nodes: [
      { type: 'point', intensity: 80000, color: '#FF0000', position: [-2, 1, 0] },
      { type: 'point', intensity: 80000, color: '#00FF00', position: [2, 1, 0] },
      { type: 'point', intensity: 80000, color: '#0000FF', position: [0, 1, -2] },
    ],
  },
];

// ---------------------------------------------------------------------------
// Sketchfab API
// ---------------------------------------------------------------------------

async function searchSketchfab(query: string): Promise<SketchfabResult[]> {
  const url = `https://api.sketchfab.com/v3/search?type=models&downloadable=true&q=${encodeURIComponent(query)}&count=20`;
  const response = await fetch(url);
  if (!response.ok) throw new Error(`Sketchfab API error: ${response.status}`);
  const data = await response.json();
  return (data.results || []).map((r: any) => ({
    uid: r.uid,
    name: r.name,
    thumbnailUrl: r.thumbnails?.images?.[0]?.url || '',
    user: { displayName: r.user?.displayName || 'Unknown' },
  }));
}

// ---------------------------------------------------------------------------
// Helper: unique ID
// ---------------------------------------------------------------------------

let _idCounter = 0;
function uniqueId(): string {
  return `shape_${++_idCounter}_${Date.now()}`;
}

// ---------------------------------------------------------------------------
// Tab: Sketchfab Search
// ---------------------------------------------------------------------------

function SearchTab() {
  const [query, setQuery] = useState('');
  const [results, setResults] = useState<SketchfabResult[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [selectedModel, setSelectedModel] = useState<ModelNode | null>(null);
  const [tapInfo, setTapInfo] = useState<string | null>(null);

  const handleSearch = useCallback(async () => {
    if (!query.trim()) return;
    setLoading(true);
    setError(null);
    try {
      const data = await searchSketchfab(query.trim());
      setResults(data);
      if (data.length === 0) setError('No downloadable models found. Try another query.');
    } catch (e: any) {
      setError(e.message || 'Search failed');
    } finally {
      setLoading(false);
    }
  }, [query]);

  return (
    <View style={styles.tabContent}>
      {/* Search bar */}
      <View style={styles.searchBar}>
        <TextInput
          style={styles.searchInput}
          placeholder="Search Sketchfab models..."
          placeholderTextColor="#6B7280"
          value={query}
          onChangeText={setQuery}
          onSubmitEditing={handleSearch}
          returnKeyType="search"
          autoCapitalize="none"
          autoCorrect={false}
        />
        <TouchableOpacity
          style={[styles.searchButton, loading && styles.searchButtonDisabled]}
          onPress={handleSearch}
          disabled={loading}
          activeOpacity={0.7}
        >
          {loading ? (
            <ActivityIndicator size="small" color="#fff" />
          ) : (
            <Text style={styles.searchButtonText}>Search</Text>
          )}
        </TouchableOpacity>
      </View>

      {error && <Text style={styles.errorText}>{error}</Text>}

      {/* 3D viewer when a model is selected */}
      {selectedModel && (
        <View style={styles.viewerContainer}>
          <SceneView
            style={styles.scene}
            environment={ENVIRONMENT}
            modelNodes={[selectedModel]}
            cameraOrbit
            onTap={(e) => {
              const { x, y, z, nodeName } = e.nativeEvent;
              setTapInfo(
                nodeName
                  ? `Tapped: ${nodeName} at (${x.toFixed(2)}, ${y.toFixed(2)}, ${z.toFixed(2)})`
                  : `Tapped at (${x.toFixed(2)}, ${y.toFixed(2)}, ${z.toFixed(2)})`
              );
            }}
          />
          {tapInfo && (
            <View style={styles.tapBadge}>
              <Text style={styles.tapBadgeText}>{tapInfo}</Text>
            </View>
          )}
          <TouchableOpacity
            style={styles.closeButton}
            onPress={() => { setSelectedModel(null); setTapInfo(null); }}
          >
            <Text style={styles.closeButtonText}>Close</Text>
          </TouchableOpacity>
        </View>
      )}

      {/* Results list */}
      {!selectedModel && (
        <FlatList
          data={results}
          keyExtractor={(item) => item.uid}
          contentContainerStyle={styles.resultsList}
          ListEmptyComponent={
            !loading && !error ? (
              <View style={styles.emptyState}>
                <Text style={styles.emptyIcon}>Q</Text>
                <Text style={styles.emptyTitle}>Search Sketchfab</Text>
                <Text style={styles.emptySubtitle}>
                  Find downloadable 3D models and view them in SceneView
                </Text>
              </View>
            ) : null
          }
          renderItem={({ item }) => (
            <TouchableOpacity
              style={styles.resultCard}
              activeOpacity={0.7}
              onPress={() => {
                // Sketchfab download requires auth, so show info
                Alert.alert(
                  item.name,
                  `By ${item.user.displayName}\n\nSketchfab model downloads require authentication. Use a local GLB file path or URL for the SceneView modelNodes prop.`,
                  [{ text: 'OK' }]
                );
              }}
            >
              <View style={styles.resultInfo}>
                <Text style={styles.resultName} numberOfLines={2}>{item.name}</Text>
                <Text style={styles.resultAuthor}>by {item.user.displayName}</Text>
                <Text style={styles.resultUid}>uid: {item.uid}</Text>
              </View>
            </TouchableOpacity>
          )}
        />
      )}
    </View>
  );
}

// ---------------------------------------------------------------------------
// Tab: Geometry Playground
// ---------------------------------------------------------------------------

function GeometryTab() {
  const [shapes, setShapes] = useState<PlaygroundShape[]>([
    { id: 'default_cube', type: 'cube', color: '#1E88E5', position: [0, 0, -2], size: [0.8, 0.8, 0.8] },
    { id: 'default_sphere', type: 'sphere', color: '#E53935', position: [1.5, 0, -2], size: [0.8, 0.8, 0.8] },
  ]);
  const [selectedColor, setSelectedColor] = useState('#1E88E5');
  const [selectedType, setSelectedType] = useState<GeometryNode['type']>('cube');
  const [tapInfo, setTapInfo] = useState<string | null>(null);
  // Camera control mode (v4.3.0). pan/firstPerson are iOS-only.
  const [cameraMode, setCameraMode] = useState<CameraControlMode>('orbit');

  const addShape = useCallback(() => {
    const xOffset = (Math.random() - 0.5) * 4;
    const yOffset = (Math.random() - 0.5) * 2;
    const newShape: PlaygroundShape = {
      id: uniqueId(),
      type: selectedType,
      color: selectedColor,
      position: [xOffset, yOffset, -2.5],
      size: [0.6, 0.6, 0.6],
    };
    setShapes((prev) => [...prev, newShape]);
  }, [selectedColor, selectedType]);

  const removeLastShape = useCallback(() => {
    setShapes((prev) => prev.slice(0, -1));
  }, []);

  const clearShapes = useCallback(() => {
    setShapes([]);
  }, []);

  const geometryNodes: GeometryNode[] = shapes.map((s) => ({
    type: s.type,
    color: s.color,
    position: s.position,
    size: s.size,
  }));

  return (
    <View style={styles.tabContent}>
      {/* 3D Scene */}
      <View style={styles.viewerContainer}>
        <SceneView
          style={styles.scene}
          environment={ENVIRONMENT}
          geometryNodes={geometryNodes}
          cameraOrbit
          cameraControlMode={cameraMode}
          onTap={(e) => {
            const { x, y, z, nodeName } = e.nativeEvent;
            setTapInfo(
              `Tap: (${x.toFixed(1)}, ${y.toFixed(1)}, ${z.toFixed(1)})${nodeName ? ` [${nodeName}]` : ''}`
            );
          }}
        />
        {tapInfo && (
          <View style={styles.tapBadge}>
            <Text style={styles.tapBadgeText}>{tapInfo}</Text>
          </View>
        )}
        <View style={styles.shapeCountBadge}>
          <Text style={styles.shapeCountText}>{shapes.length} shapes</Text>
        </View>
      </View>

      {/* Controls */}
      <ScrollView style={styles.controls} contentContainerStyle={styles.controlsContent}>
        {/* Camera control mode (v4.3.0) */}
        <Text style={styles.controlLabel}>Camera Mode</Text>
        <View style={styles.chipRow}>
          {CAMERA_MODES.map((mode) => (
            <TouchableOpacity
              key={mode.id}
              style={[styles.typeChip, cameraMode === mode.id && styles.typeChipSelected]}
              onPress={() => setCameraMode(mode.id)}
              activeOpacity={0.7}
            >
              <Text style={[styles.typeChipText, cameraMode === mode.id && styles.typeChipTextSelected]}>
                {mode.label}
              </Text>
            </TouchableOpacity>
          ))}
        </View>

        {/* Shape type selector */}
        <Text style={styles.controlLabel}>Shape Type</Text>
        <View style={styles.chipRow}>
          {SHAPE_TYPES.map((type) => (
            <TouchableOpacity
              key={type}
              style={[styles.typeChip, selectedType === type && styles.typeChipSelected]}
              onPress={() => setSelectedType(type)}
              activeOpacity={0.7}
            >
              <Text style={[styles.typeChipText, selectedType === type && styles.typeChipTextSelected]}>
                {type.charAt(0).toUpperCase() + type.slice(1)}
              </Text>
            </TouchableOpacity>
          ))}
        </View>

        {/* Color picker */}
        <Text style={styles.controlLabel}>Color</Text>
        <View style={styles.colorGrid}>
          {PRESET_COLORS.map((color) => (
            <TouchableOpacity
              key={color}
              style={[
                styles.colorSwatch,
                { backgroundColor: color },
                selectedColor === color && styles.colorSwatchSelected,
              ]}
              onPress={() => setSelectedColor(color)}
              activeOpacity={0.7}
            />
          ))}
        </View>

        {/* Action buttons */}
        <View style={styles.actionRow}>
          <TouchableOpacity style={styles.actionButton} onPress={addShape} activeOpacity={0.7}>
            <Text style={styles.actionButtonText}>+ Add Shape</Text>
          </TouchableOpacity>
          <TouchableOpacity
            style={[styles.actionButton, styles.actionButtonSecondary]}
            onPress={removeLastShape}
            activeOpacity={0.7}
          >
            <Text style={styles.actionButtonSecondaryText}>- Remove</Text>
          </TouchableOpacity>
          <TouchableOpacity
            style={[styles.actionButton, styles.actionButtonDanger]}
            onPress={clearShapes}
            activeOpacity={0.7}
          >
            <Text style={styles.actionButtonDangerText}>Clear</Text>
          </TouchableOpacity>
        </View>
      </ScrollView>
    </View>
  );
}

// ---------------------------------------------------------------------------
// Tab: Lights
// ---------------------------------------------------------------------------

function LightsTab() {
  const [activeLights, setActiveLights] = useState<LightNode[]>(LIGHT_PRESETS[0].nodes);
  const [activePreset, setActivePreset] = useState(0);

  // Show geometry shapes so we can see the lighting effect
  const demoGeometry: GeometryNode[] = [
    { type: 'sphere', color: '#CCCCCC', position: [0, 0, -2], size: [1, 1, 1] },
    { type: 'cube', color: '#CCCCCC', position: [-1.8, 0, -2.5], size: [0.7, 0.7, 0.7] },
    { type: 'cylinder', color: '#CCCCCC', position: [1.8, 0, -2.5], size: [0.6, 1, 0.6] },
    { type: 'plane', color: '#888888', position: [0, -0.8, -2], size: [6, 6, 1] },
  ];

  return (
    <View style={styles.tabContent}>
      {/* 3D Scene */}
      <View style={styles.viewerContainer}>
        <SceneView
          style={styles.scene}
          environment={ENVIRONMENT}
          geometryNodes={demoGeometry}
          lightNodes={activeLights}
          cameraOrbit
        />
        <View style={styles.lightInfoBadge}>
          <Text style={styles.lightInfoText}>
            {activeLights.length} light{activeLights.length !== 1 ? 's' : ''} active
          </Text>
        </View>
      </View>

      {/* Presets */}
      <ScrollView style={styles.controls} contentContainerStyle={styles.controlsContent}>
        <Text style={styles.controlLabel}>Light Presets</Text>
        {LIGHT_PRESETS.map((preset, index) => (
          <TouchableOpacity
            key={preset.label}
            style={[styles.presetCard, activePreset === index && styles.presetCardActive]}
            onPress={() => {
              setActiveLights(preset.nodes);
              setActivePreset(index);
            }}
            activeOpacity={0.7}
          >
            <Text style={[styles.presetLabel, activePreset === index && styles.presetLabelActive]}>
              {preset.label}
            </Text>
            <Text style={styles.presetDetail}>
              {preset.nodes.map((n) => `${n.type}(${n.color})`).join(' + ')}
            </Text>
          </TouchableOpacity>
        ))}

        <Text style={[styles.controlLabel, { marginTop: 16 }]}>Custom Light</Text>
        <View style={styles.chipRow}>
          {LIGHT_TYPES.map((type) => (
            <TouchableOpacity
              key={type}
              style={styles.typeChip}
              onPress={() => {
                const newLight: LightNode = {
                  type,
                  intensity: type === 'spot' ? 200000 : 100000,
                  color: PRESET_COLORS[Math.floor(Math.random() * PRESET_COLORS.length)],
                  position: [
                    (Math.random() - 0.5) * 4,
                    1 + Math.random() * 2,
                    (Math.random() - 0.5) * 4,
                  ],
                  direction: type !== 'point' ? [0, -1, 0] : undefined,
                };
                setActiveLights((prev) => [...prev, newLight]);
                setActivePreset(-1);
              }}
              activeOpacity={0.7}
            >
              <Text style={styles.typeChipText}>+ {type}</Text>
            </TouchableOpacity>
          ))}
        </View>

        <View style={styles.actionRow}>
          <TouchableOpacity
            style={[styles.actionButton, styles.actionButtonDanger]}
            onPress={() => { setActiveLights([]); setActivePreset(-1); }}
            activeOpacity={0.7}
          >
            <Text style={styles.actionButtonDangerText}>Clear All Lights</Text>
          </TouchableOpacity>
        </View>
      </ScrollView>
    </View>
  );
}

// ---------------------------------------------------------------------------
// Tab: AR Mode
// ---------------------------------------------------------------------------

function ARTab() {
  const [planeDetection, setPlaneDetection] = useState(true);
  const [depthOcclusion, setDepthOcclusion] = useState(false);
  const [instantPlacement, setInstantPlacement] = useState(false);
  const [detectedPlanes, setDetectedPlanes] = useState(0);
  const [tapInfo, setTapInfo] = useState<string | null>(null);
  // AR session recording (v4.3.0, iOS via ReplayKit).
  const [recorder] = useState(() => new ARRecorder());
  const [isRecording, setIsRecording] = useState(false);
  const [lastRecordingPath, setLastRecordingPath] = useState<string | null>(null);

  const toggleRecording = useCallback(async () => {
    try {
      if (isRecording) {
        const path = await recorder.stop();
        setIsRecording(false);
        setLastRecordingPath(path);
        Alert.alert('Recording stopped', `Saved to:\n${path}`);
      } else {
        await recorder.start();
        setIsRecording(true);
      }
    } catch (e: any) {
      setIsRecording(false);
      Alert.alert('AR Recorder', e?.message ?? 'Recording failed');
    }
  }, [isRecording, recorder]);

  const saveRecording = useCallback(async () => {
    if (!lastRecordingPath) return;
    try {
      await recorder.saveToPhotoLibrary(lastRecordingPath);
      Alert.alert('Saved', 'Recording added to Photos');
    } catch (e: any) {
      Alert.alert('AR Recorder', e?.message ?? 'Save failed');
    }
  }, [lastRecordingPath, recorder]);

  const arGeometry: GeometryNode[] = [
    { type: 'cube', color: '#1E88E5', position: [0, 0, -1], size: [0.2, 0.2, 0.2] },
    { type: 'sphere', color: '#E53935', position: [0.3, 0.1, -1], size: [0.15, 0.15, 0.15] },
  ];

  const arLights: LightNode[] = [
    { type: 'directional', intensity: 100000, color: '#FFFFFF', direction: [0, -1, -1] },
  ];

  return (
    <View style={styles.tabContent}>
      {/* AR Scene */}
      <View style={styles.viewerContainer}>
        <ARSceneView
          style={styles.scene}
          planeDetection={planeDetection}
          depthOcclusion={depthOcclusion}
          instantPlacement={instantPlacement}
          geometryNodes={arGeometry}
          lightNodes={arLights}
          onTap={(e) => {
            const { x, y, z, nodeName } = e.nativeEvent;
            setTapInfo(
              nodeName
                ? `Tapped: ${nodeName} at (${x.toFixed(2)}, ${y.toFixed(2)}, ${z.toFixed(2)})`
                : `Tapped at (${x.toFixed(2)}, ${y.toFixed(2)}, ${z.toFixed(2)})`
            );
          }}
          onPlaneDetected={(e) => {
            setDetectedPlanes((prev) => prev + 1);
          }}
        />
        {tapInfo && (
          <View style={styles.tapBadge}>
            <Text style={styles.tapBadgeText}>{tapInfo}</Text>
          </View>
        )}
        <View style={styles.arStatusBadge}>
          <Text style={styles.arStatusText}>
            {detectedPlanes} plane{detectedPlanes !== 1 ? 's' : ''} detected
          </Text>
        </View>
      </View>

      {/* AR Controls */}
      <ScrollView style={styles.controls} contentContainerStyle={styles.controlsContent}>
        <Text style={styles.controlLabel}>AR Features</Text>

        <View style={styles.switchRow}>
          <Text style={styles.switchLabel}>Plane Detection</Text>
          <Switch
            value={planeDetection}
            onValueChange={setPlaneDetection}
            trackColor={{ false: '#3A3F4B', true: '#1E88E5' }}
            thumbColor="#fff"
          />
        </View>
        <View style={styles.switchRow}>
          <View style={styles.switchLabelGroup}>
            <Text style={styles.switchLabel}>Depth Occlusion</Text>
            <Text style={styles.switchSublabel}>Not yet bridged (#909)</Text>
          </View>
          <Switch
            value={depthOcclusion}
            onValueChange={setDepthOcclusion}
            trackColor={{ false: '#3A3F4B', true: '#1E88E5' }}
            thumbColor="#fff"
          />
        </View>
        <View style={styles.switchRow}>
          <View style={styles.switchLabelGroup}>
            <Text style={styles.switchLabel}>Instant Placement</Text>
            <Text style={styles.switchSublabel}>Not yet bridged (#909)</Text>
          </View>
          <Switch
            value={instantPlacement}
            onValueChange={setInstantPlacement}
            trackColor={{ false: '#3A3F4B', true: '#1E88E5' }}
            thumbColor="#fff"
          />
        </View>

        {/* AR session recording (v4.3.0) — iOS only via ReplayKit */}
        <Text style={[styles.controlLabel, { marginTop: 16 }]}>AR Recording</Text>
        <View style={styles.actionRow}>
          <TouchableOpacity
            style={[styles.actionButton, isRecording && styles.actionButtonDanger]}
            onPress={toggleRecording}
            activeOpacity={0.7}
          >
            <Text style={isRecording ? styles.actionButtonDangerText : styles.actionButtonText}>
              {isRecording ? 'Stop' : 'Record (iOS)'}
            </Text>
          </TouchableOpacity>
          <TouchableOpacity
            style={[
              styles.actionButton,
              styles.actionButtonSecondary,
              !lastRecordingPath && styles.searchButtonDisabled,
            ]}
            onPress={saveRecording}
            disabled={!lastRecordingPath}
            activeOpacity={0.7}
          >
            <Text style={styles.actionButtonSecondaryText}>Save to Photos</Text>
          </TouchableOpacity>
        </View>

        <View style={styles.arInfoCard}>
          <Text style={styles.arInfoTitle}>AR Bridge Coverage</Text>
          <Text style={styles.arInfoBody}>
            {'What the RN bridge exposes today (issue #909):\n'}
            {'\u2022 Plane detection \u2014 wired into the ARCore session\n'}
            {'\u2022 Geometry nodes in AR \u2014 Android only\n'}
            {'\u2022 Light nodes in AR \u2014 Android only\n'}
            {'\nNot yet bridged \u2014 the controls below are present so the '}
            {'API is stable, but they have no native effect:\n'}
            {'\u2022 Depth occlusion \u2014 prop not applied to the AR Config\n'}
            {'\u2022 Instant placement \u2014 prop not applied to the AR Config\n'}
            {'\u2022 onTap / onPlaneDetected \u2014 events not dispatched yet'}
          </Text>
        </View>
      </ScrollView>
    </View>
  );
}

// ---------------------------------------------------------------------------
// Tab: Materials (lit PBR vs unlit)
// ---------------------------------------------------------------------------

/**
 * Demonstrates the `GeometryNode.unlit` material flag — the only material
 * control the RN bridge currently exposes. A lit PBR sphere reacts to scene
 * lighting (shading, IBL); an unlit sphere renders its flat colour straight
 * to the framebuffer. Both rows share the same colour so the difference is
 * purely the shading model.
 */
function MaterialsTab() {
  const [unlit, setUnlit] = useState(false);
  const [color, setColor] = useState('#1E88E5');

  // A single row of three primitives, re-rendered lit or unlit on toggle.
  const geometryNodes: GeometryNode[] = [
    { type: 'sphere', color, position: [-1.4, 0, -2.5], size: [0.9, 0.9, 0.9], unlit },
    { type: 'cube', color, position: [0, 0, -2.5], size: [0.8, 0.8, 0.8], unlit },
    { type: 'cylinder', color, position: [1.4, 0, -2.5], size: [0.7, 1.1, 0.7], unlit },
  ];

  // A directional light makes the lit/unlit contrast obvious.
  const lightNodes: LightNode[] = [
    { type: 'directional', intensity: 100000, color: '#FFFFFF', direction: [-1, -1, -1] },
  ];

  return (
    <View style={styles.tabContent}>
      <View style={styles.viewerContainer}>
        <SceneView
          style={styles.scene}
          environment={ENVIRONMENT}
          geometryNodes={geometryNodes}
          lightNodes={lightNodes}
          cameraOrbit
        />
        <View style={styles.lightInfoBadge}>
          <Text style={styles.lightInfoText}>{unlit ? 'Unlit' : 'Lit PBR'}</Text>
        </View>
      </View>

      <ScrollView style={styles.controls} contentContainerStyle={styles.controlsContent}>
        <View style={styles.switchRow}>
          <Text style={styles.switchLabel}>Unlit material</Text>
          <Switch
            value={unlit}
            onValueChange={setUnlit}
            trackColor={{ false: '#3A3F4B', true: '#1E88E5' }}
            thumbColor="#fff"
          />
        </View>

        <Text style={[styles.controlLabel, { marginTop: 16 }]}>Color</Text>
        <View style={styles.colorGrid}>
          {PRESET_COLORS.map((c) => (
            <TouchableOpacity
              key={c}
              style={[
                styles.colorSwatch,
                { backgroundColor: c },
                color === c && styles.colorSwatchSelected,
              ]}
              onPress={() => setColor(c)}
              activeOpacity={0.7}
            />
          ))}
        </View>

        <View style={styles.arInfoCard}>
          <Text style={styles.arInfoTitle}>Material Modes</Text>
          <Text style={styles.arInfoBody}>
            {'•'} Lit PBR — reacts to lights, IBL and shadows{'\n'}
            {'•'} Unlit — flat colour, ignores all lighting{'\n'}
            {'\n'}Toggle the switch and watch the same shapes go from shaded to
            flat. Unlit is ideal for HUD overlays, gizmos and AR face meshes.
          </Text>
        </View>
      </ScrollView>
    </View>
  );
}

// ---------------------------------------------------------------------------
// Tab: Animation (animated glTF models)
// ---------------------------------------------------------------------------

/**
 * Demonstrates auto-playing glTF animation clips. The native bridge
 * auto-animates a loaded model whose glTF ships an animation clip, so picking
 * an animated model shows it moving with no extra wiring.
 */
function AnimationTab() {
  const [modelIndex, setModelIndex] = useState(0);

  const model = ANIMATED_MODELS[modelIndex];
  const modelNode: ModelNode = {
    src: model.src,
    scale: model.scale,
    position: [0, -0.5, -2.5],
  };

  return (
    <View style={styles.tabContent}>
      <View style={styles.viewerContainer}>
        <SceneView
          style={styles.scene}
          environment={ENVIRONMENT}
          modelNodes={[modelNode]}
          cameraOrbit
        />
        <View style={styles.lightInfoBadge}>
          <Text style={styles.lightInfoText}>{model.label}</Text>
        </View>
      </View>

      <ScrollView style={styles.controls} contentContainerStyle={styles.controlsContent}>
        <Text style={styles.controlLabel}>Animated Model</Text>
        <View style={styles.chipRow}>
          {ANIMATED_MODELS.map((m, index) => (
            <TouchableOpacity
              key={m.label}
              style={[styles.typeChip, modelIndex === index && styles.typeChipSelected]}
              onPress={() => setModelIndex(index)}
              activeOpacity={0.7}
            >
              <Text
                style={[
                  styles.typeChipText,
                  modelIndex === index && styles.typeChipTextSelected,
                ]}
              >
                {m.label}
              </Text>
            </TouchableOpacity>
          ))}
        </View>

        <View style={styles.arInfoCard}>
          <Text style={styles.arInfoTitle}>Model Animation</Text>
          <Text style={styles.arInfoBody}>
            glTF models that ship an animation clip (Fox, BoxAnimated,
            BrainStem) auto-play it on the native renderer. Selecting a
            specific clip by name, or pausing playback, needs new bridge
            surface — tracked as a bridge-gap follow-up under issue #1362.
          </Text>
        </View>
      </ScrollView>
    </View>
  );
}

// ---------------------------------------------------------------------------
// Tab: Environment (HDR image-based lighting + auto-center)
// ---------------------------------------------------------------------------

/**
 * Demonstrates the `environment` (HDR IBL) and `autoCenterContent` props.
 * Switching the environment changes the skybox and image-based lighting on a
 * fixed model; the auto-center toggle frames the content on the first stable
 * frame (iOS-first, see #1051 for the Android side).
 */
function EnvironmentTab() {
  const [envIndex, setEnvIndex] = useState(0);
  const [autoCenter, setAutoCenter] = useState(true);

  const env = ENVIRONMENTS[envIndex];

  // A neutral model so the environment lighting is what reads visually.
  const modelNode: ModelNode = {
    src: 'https://raw.githubusercontent.com/KhronosGroup/glTF-Sample-Assets/main/Models/DamagedHelmet/glTF-Binary/DamagedHelmet.glb',
    scale: 1.0,
    position: [0, 0, -2.5],
  };

  return (
    <View style={styles.tabContent}>
      <View style={styles.viewerContainer}>
        <SceneView
          style={styles.scene}
          environment={env.path ?? undefined}
          modelNodes={[modelNode]}
          autoCenterContent={autoCenter}
          cameraOrbit
        />
        <View style={styles.lightInfoBadge}>
          <Text style={styles.lightInfoText}>{env.label}</Text>
        </View>
      </View>

      <ScrollView style={styles.controls} contentContainerStyle={styles.controlsContent}>
        <Text style={styles.controlLabel}>HDR Environment</Text>
        <View style={styles.chipRow}>
          {ENVIRONMENTS.map((e, index) => (
            <TouchableOpacity
              key={e.label}
              style={[styles.typeChip, envIndex === index && styles.typeChipSelected]}
              onPress={() => setEnvIndex(index)}
              activeOpacity={0.7}
            >
              <Text
                style={[
                  styles.typeChipText,
                  envIndex === index && styles.typeChipTextSelected,
                ]}
              >
                {e.label}
              </Text>
            </TouchableOpacity>
          ))}
        </View>

        <View style={styles.switchRow}>
          <Text style={styles.switchLabel}>Auto-center content</Text>
          <Switch
            value={autoCenter}
            onValueChange={setAutoCenter}
            trackColor={{ false: '#3A3F4B', true: '#1E88E5' }}
            thumbColor="#fff"
          />
        </View>

        <View style={styles.arInfoCard}>
          <Text style={styles.arInfoTitle}>Image-Based Lighting</Text>
          <Text style={styles.arInfoBody}>
            The `environment` prop loads an HDR file for image-based lighting
            and the skybox. `autoCenterContent` frames the model on the first
            stable frame — an iOS-first v4.3.0 feature; the Android side is
            tracked in issue #1051.
          </Text>
        </View>
      </ScrollView>
    </View>
  );
}

// ---------------------------------------------------------------------------
// Main App
// ---------------------------------------------------------------------------

export default function App() {
  const [activeTab, setActiveTab] = useState<TabId>('geometry');

  const renderTab = () => {
    switch (activeTab) {
      case 'search': return <SearchTab />;
      case 'geometry': return <GeometryTab />;
      case 'materials': return <MaterialsTab />;
      case 'lights': return <LightsTab />;
      case 'animation': return <AnimationTab />;
      case 'environment': return <EnvironmentTab />;
      case 'physics': return <DoublePendulumTab />;
      case 'ar': return <ARTab />;
    }
  };

  return (
    <SafeAreaView style={styles.container}>
      <StatusBar barStyle="light-content" backgroundColor="#0F1218" />

      {/* Header */}
      <View style={styles.header}>
        <View>
          <Text style={styles.title}>SceneView</Text>
          <Text style={styles.subtitle}>React Native Demo</Text>
        </View>
        <View style={styles.versionBadge}>
          <Text style={styles.versionText}>v{VERSION}</Text>
        </View>
      </View>

      {/* Tab content */}
      <View style={styles.tabContainer}>
        {renderTab()}
      </View>

      {/* Bottom tab bar — horizontally scrollable so the catalog can grow
          past the ~5 tabs that fit a phone width without cramping. */}
      <View style={styles.tabBar}>
        <ScrollView
          horizontal
          showsHorizontalScrollIndicator={false}
          contentContainerStyle={styles.tabBarContent}
        >
          {TABS.map((tab) => (
            <TouchableOpacity
              key={tab.id}
              style={[styles.tabItem, activeTab === tab.id && styles.tabItemActive]}
              onPress={() => setActiveTab(tab.id)}
              activeOpacity={0.7}
            >
              <View style={[styles.tabIconContainer, activeTab === tab.id && styles.tabIconContainerActive]}>
                <Text style={[styles.tabIcon, activeTab === tab.id && styles.tabIconActive]}>
                  {tab.icon}
                </Text>
              </View>
              <Text style={[styles.tabLabel, activeTab === tab.id && styles.tabLabelActive]}>
                {tab.label}
              </Text>
            </TouchableOpacity>
          ))}
        </ScrollView>
      </View>

      {/* Auto-update banner — overlays the top of the screen when a newer
          build is on the store. Hooks `AppState` internally so a resume
          re-runs the check; no-op on dev / unsupported platforms. */}
      <UpdateChecker />
    </SafeAreaView>
  );
}

// ---------------------------------------------------------------------------
// Styles
// ---------------------------------------------------------------------------

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: '#0F1218',
  },

  // Header
  header: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    paddingHorizontal: 20,
    paddingVertical: 14,
    borderBottomWidth: StyleSheet.hairlineWidth,
    borderBottomColor: '#1E2430',
  },
  title: {
    color: '#FFFFFF',
    fontSize: 24,
    fontWeight: '800',
    letterSpacing: -0.5,
  },
  subtitle: {
    color: '#6B7280',
    fontSize: 13,
    fontWeight: '500',
    marginTop: 1,
  },
  versionBadge: {
    backgroundColor: '#1A2332',
    paddingHorizontal: 12,
    paddingVertical: 6,
    borderRadius: 12,
    borderWidth: 1,
    borderColor: '#1E88E5',
  },
  versionText: {
    color: '#1E88E5',
    fontSize: 13,
    fontWeight: '700',
  },

  // Tab bar
  tabBar: {
    backgroundColor: '#0F1218',
    borderTopWidth: 1,
    borderTopColor: '#1E2430',
    paddingBottom: Platform.OS === 'ios' ? 20 : 8,
    paddingTop: 8,
  },
  tabBarContent: {
    flexDirection: 'row',
    paddingHorizontal: 8,
  },
  tabItem: {
    minWidth: 72,
    alignItems: 'center',
    justifyContent: 'center',
    paddingVertical: 4,
    paddingHorizontal: 4,
  },
  tabItemActive: {},
  tabIconContainer: {
    width: 36,
    height: 36,
    borderRadius: 18,
    backgroundColor: '#1A1F28',
    alignItems: 'center',
    justifyContent: 'center',
    marginBottom: 4,
  },
  tabIconContainerActive: {
    backgroundColor: '#1A2332',
  },
  tabIcon: {
    color: '#6B7280',
    fontSize: 16,
    fontWeight: '800',
  },
  tabIconActive: {
    color: '#1E88E5',
  },
  tabLabel: {
    color: '#6B7280',
    fontSize: 11,
    fontWeight: '600',
  },
  tabLabelActive: {
    color: '#1E88E5',
  },

  // Tab content
  tabContainer: {
    flex: 1,
  },
  tabContent: {
    flex: 1,
  },

  // Scene viewer
  viewerContainer: {
    flex: 1,
    minHeight: 260,
  },
  scene: {
    flex: 1,
  },

  // Search
  searchBar: {
    flexDirection: 'row',
    paddingHorizontal: 16,
    paddingVertical: 12,
    gap: 10,
  },
  searchInput: {
    flex: 1,
    backgroundColor: '#1A1F28',
    borderRadius: 12,
    paddingHorizontal: 16,
    paddingVertical: 12,
    color: '#FFFFFF',
    fontSize: 15,
    borderWidth: 1,
    borderColor: '#2A3040',
  },
  searchButton: {
    backgroundColor: '#1E88E5',
    borderRadius: 12,
    paddingHorizontal: 20,
    alignItems: 'center',
    justifyContent: 'center',
  },
  searchButtonDisabled: {
    opacity: 0.6,
  },
  searchButtonText: {
    color: '#FFFFFF',
    fontSize: 15,
    fontWeight: '700',
  },
  errorText: {
    color: '#EF4444',
    fontSize: 14,
    paddingHorizontal: 16,
    paddingBottom: 8,
  },
  resultsList: {
    paddingHorizontal: 16,
    paddingBottom: 16,
  },
  resultCard: {
    backgroundColor: '#1A1F28',
    borderRadius: 12,
    padding: 16,
    marginBottom: 10,
    borderWidth: 1,
    borderColor: '#2A3040',
  },
  resultInfo: {},
  resultName: {
    color: '#FFFFFF',
    fontSize: 16,
    fontWeight: '600',
    marginBottom: 4,
  },
  resultAuthor: {
    color: '#9CA3AF',
    fontSize: 13,
    marginBottom: 2,
  },
  resultUid: {
    color: '#4B5563',
    fontSize: 11,
    fontFamily: Platform.OS === 'ios' ? 'Menlo' : 'monospace',
  },
  emptyState: {
    alignItems: 'center',
    justifyContent: 'center',
    paddingVertical: 60,
  },
  emptyIcon: {
    color: '#2A3040',
    fontSize: 48,
    fontWeight: '800',
    marginBottom: 16,
  },
  emptyTitle: {
    color: '#9CA3AF',
    fontSize: 18,
    fontWeight: '600',
    marginBottom: 8,
  },
  emptySubtitle: {
    color: '#4B5563',
    fontSize: 14,
    textAlign: 'center',
    paddingHorizontal: 40,
  },

  // Controls
  controls: {
    maxHeight: 280,
    borderTopWidth: 1,
    borderTopColor: '#1E2430',
  },
  controlsContent: {
    padding: 16,
  },
  controlLabel: {
    color: '#9CA3AF',
    fontSize: 13,
    fontWeight: '700',
    textTransform: 'uppercase',
    letterSpacing: 1,
    marginBottom: 10,
  },

  // Shape type chips
  chipRow: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    gap: 8,
    marginBottom: 16,
  },
  typeChip: {
    paddingHorizontal: 14,
    paddingVertical: 8,
    borderRadius: 10,
    backgroundColor: '#1A1F28',
    borderWidth: 1,
    borderColor: '#2A3040',
  },
  typeChipSelected: {
    backgroundColor: '#1A2332',
    borderColor: '#1E88E5',
  },
  typeChipText: {
    color: '#9CA3AF',
    fontSize: 14,
    fontWeight: '600',
  },
  typeChipTextSelected: {
    color: '#1E88E5',
  },

  // Color picker
  colorGrid: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    gap: 8,
    marginBottom: 16,
  },
  colorSwatch: {
    width: 32,
    height: 32,
    borderRadius: 8,
    borderWidth: 2,
    borderColor: 'transparent',
  },
  colorSwatchSelected: {
    borderColor: '#FFFFFF',
    transform: [{ scale: 1.1 }],
  },

  // Action buttons
  actionRow: {
    flexDirection: 'row',
    gap: 10,
    marginTop: 4,
  },
  actionButton: {
    flex: 1,
    backgroundColor: '#1E88E5',
    borderRadius: 10,
    paddingVertical: 12,
    alignItems: 'center',
  },
  actionButtonText: {
    color: '#FFFFFF',
    fontSize: 15,
    fontWeight: '700',
  },
  actionButtonSecondary: {
    backgroundColor: '#1A1F28',
    borderWidth: 1,
    borderColor: '#2A3040',
  },
  actionButtonSecondaryText: {
    color: '#9CA3AF',
    fontSize: 15,
    fontWeight: '700',
  },
  actionButtonDanger: {
    backgroundColor: '#1A1F28',
    borderWidth: 1,
    borderColor: '#7F1D1D',
  },
  actionButtonDangerText: {
    color: '#EF4444',
    fontSize: 15,
    fontWeight: '700',
  },

  // Badges
  tapBadge: {
    position: 'absolute',
    bottom: 12,
    left: 12,
    right: 12,
    backgroundColor: 'rgba(15, 18, 24, 0.9)',
    borderRadius: 10,
    paddingHorizontal: 14,
    paddingVertical: 8,
    borderWidth: 1,
    borderColor: '#2A3040',
  },
  tapBadgeText: {
    color: '#D1D5DB',
    fontSize: 13,
    fontFamily: Platform.OS === 'ios' ? 'Menlo' : 'monospace',
  },
  shapeCountBadge: {
    position: 'absolute',
    top: 12,
    right: 12,
    backgroundColor: 'rgba(15, 18, 24, 0.85)',
    borderRadius: 8,
    paddingHorizontal: 10,
    paddingVertical: 5,
    borderWidth: 1,
    borderColor: '#2A3040',
  },
  shapeCountText: {
    color: '#9CA3AF',
    fontSize: 12,
    fontWeight: '600',
  },
  lightInfoBadge: {
    position: 'absolute',
    top: 12,
    right: 12,
    backgroundColor: 'rgba(15, 18, 24, 0.85)',
    borderRadius: 8,
    paddingHorizontal: 10,
    paddingVertical: 5,
    borderWidth: 1,
    borderColor: '#2A3040',
  },
  lightInfoText: {
    color: '#FFB300',
    fontSize: 12,
    fontWeight: '600',
  },
  arStatusBadge: {
    position: 'absolute',
    top: 12,
    right: 12,
    backgroundColor: 'rgba(15, 18, 24, 0.85)',
    borderRadius: 8,
    paddingHorizontal: 10,
    paddingVertical: 5,
    borderWidth: 1,
    borderColor: '#2A3040',
  },
  arStatusText: {
    color: '#43A047',
    fontSize: 12,
    fontWeight: '600',
  },

  // Close button
  closeButton: {
    position: 'absolute',
    top: 12,
    left: 12,
    backgroundColor: 'rgba(15, 18, 24, 0.9)',
    borderRadius: 8,
    paddingHorizontal: 14,
    paddingVertical: 8,
    borderWidth: 1,
    borderColor: '#2A3040',
  },
  closeButtonText: {
    color: '#D1D5DB',
    fontSize: 14,
    fontWeight: '600',
  },

  // Light presets
  presetCard: {
    backgroundColor: '#1A1F28',
    borderRadius: 12,
    padding: 14,
    marginBottom: 8,
    borderWidth: 1,
    borderColor: '#2A3040',
  },
  presetCardActive: {
    borderColor: '#FFB300',
    backgroundColor: '#1A2020',
  },
  presetLabel: {
    color: '#FFFFFF',
    fontSize: 16,
    fontWeight: '600',
    marginBottom: 4,
  },
  presetLabelActive: {
    color: '#FFB300',
  },
  presetDetail: {
    color: '#6B7280',
    fontSize: 12,
    fontFamily: Platform.OS === 'ios' ? 'Menlo' : 'monospace',
  },

  // AR switches
  switchRow: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    paddingVertical: 10,
    borderBottomWidth: StyleSheet.hairlineWidth,
    borderBottomColor: '#1E2430',
  },
  switchLabelGroup: {
    flexShrink: 1,
    paddingRight: 12,
  },
  switchLabel: {
    color: '#D1D5DB',
    fontSize: 15,
    fontWeight: '500',
  },
  switchSublabel: {
    color: '#6B7280',
    fontSize: 12,
    fontWeight: '500',
    marginTop: 1,
  },

  // AR info card
  arInfoCard: {
    backgroundColor: '#1A1F28',
    borderRadius: 12,
    padding: 16,
    marginTop: 16,
    borderWidth: 1,
    borderColor: '#2A3040',
  },
  arInfoTitle: {
    color: '#FFFFFF',
    fontSize: 16,
    fontWeight: '700',
    marginBottom: 8,
  },
  arInfoBody: {
    color: '#9CA3AF',
    fontSize: 14,
    lineHeight: 22,
  },
});
