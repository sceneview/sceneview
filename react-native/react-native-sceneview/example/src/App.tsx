import React, { useState } from 'react';
import {
  SafeAreaView,
  StyleSheet,
  Text,
  View,
  Switch,
  TouchableOpacity,
  FlatList,
} from 'react-native';
import { SceneView, ARSceneView, type ModelNode } from 'react-native-sceneview';

// ---------------------------------------------------------------------------
// Model catalog
// ---------------------------------------------------------------------------

interface ModelEntry {
  label: string;
  node: ModelNode;
}

const MODELS: ModelEntry[] = [
  {
    label: 'Damaged Helmet',
    node: {
      src: 'models/damaged_helmet.glb',
      position: [0, 0, -2],
      scale: [1, 1, 1],
    },
  },
  {
    label: 'Robot',
    node: {
      src: 'models/robot.glb',
      position: [0, 0, -2],
      scale: [0.5, 0.5, 0.5],
      animation: 'Idle',
    },
  },
];

const ENVIRONMENT = 'environments/studio_small.hdr';

export default function App() {
  const [arMode, setArMode] = useState(false);
  const [selectedIndex, setSelectedIndex] = useState(0);

  const currentModel = MODELS[selectedIndex];

  return (
    <SafeAreaView style={styles.container}>
      <View style={styles.header}>
        <Text style={styles.title}>SceneView Example</Text>
        <View style={styles.toggle}>
          <Text style={styles.toggleLabel}>3D</Text>
          <Switch value={arMode} onValueChange={setArMode} />
          <Text style={styles.toggleLabel}>AR</Text>
        </View>
      </View>

      {arMode ? (
        <ARSceneView
          style={styles.scene}
          planeDetection
          modelNodes={[currentModel.node]}
          onTap={(e) => console.log('Tapped:', e.nativeEvent)}
          onPlaneDetected={(e) => console.log('Plane detected:', e.nativeEvent)}
        />
      ) : (
        <SceneView
          style={styles.scene}
          environment={ENVIRONMENT}
          modelNodes={[currentModel.node]}
          cameraControlMode="orbit"
          onTap={(e) => console.log('Tapped:', e.nativeEvent)}
        />
      )}

      {MODELS.length > 1 && (
        <View style={styles.picker}>
          <FlatList
            data={MODELS}
            horizontal
            showsHorizontalScrollIndicator={false}
            keyExtractor={(_, i) => String(i)}
            contentContainerStyle={styles.pickerContent}
            renderItem={({ item, index }) => (
              <TouchableOpacity
                style={[
                  styles.chip,
                  index === selectedIndex && styles.chipSelected,
                ]}
                onPress={() => setSelectedIndex(index)}
                activeOpacity={0.7}
              >
                <Text
                  style={[
                    styles.chipText,
                    index === selectedIndex && styles.chipTextSelected,
                  ]}
                >
                  {item.label}
                </Text>
              </TouchableOpacity>
            )}
          />
        </View>
      )}
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: '#1a1a2e',
  },
  header: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    padding: 16,
  },
  title: {
    color: '#fff',
    fontSize: 20,
    fontWeight: '600',
  },
  toggle: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 8,
  },
  toggleLabel: {
    color: '#ccc',
    fontSize: 14,
  },
  scene: {
    flex: 1,
  },
  picker: {
    paddingVertical: 12,
    borderTopWidth: StyleSheet.hairlineWidth,
    borderTopColor: '#333',
  },
  pickerContent: {
    paddingHorizontal: 12,
    gap: 8,
  },
  chip: {
    paddingHorizontal: 16,
    paddingVertical: 8,
    borderRadius: 20,
    backgroundColor: '#2a2a4e',
    borderWidth: 1,
    borderColor: '#444',
  },
  chipSelected: {
    backgroundColor: '#4a90d9',
    borderColor: '#4a90d9',
  },
  chipText: {
    color: '#aaa',
    fontSize: 14,
  },
  chipTextSelected: {
    color: '#fff',
  },
});
