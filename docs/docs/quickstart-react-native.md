# React Native Quickstart

SceneView provides a React Native module that bridges to native SceneView rendering on both Android (Filament) and iOS (RealityKit).

## Install

```bash
npm install @sceneview-sdk/react-native
cd ios && pod install
```

## Usage

### 3D Scene

```tsx
import { SceneView } from '@sceneview-sdk/react-native';

export default function ModelViewer() {
  return (
    <SceneView
      style={{ flex: 1 }}
      modelNodes={[
        { src: 'models/damaged_helmet.glb', scale: 1.0 }
      ]}
      environment="environments/sky_2k.hdr"
      cameraOrbit={true}
    />
  );
}
```

### AR Scene

```tsx
import { ARSceneView } from '@sceneview-sdk/react-native';

export default function ARViewer() {
  return (
    <ARSceneView
      style={{ flex: 1 }}
      modelNodes={[
        { src: 'models/chair.glb', scale: 0.5 }
      ]}
      planeDetection={true}
      onPlaneDetected={(event) => {
        console.log('Plane detected:', event.nativeEvent);
      }}
    />
  );
}
```

## How It Works

```
React Native (TypeScript)
  └── Native Component
        ├── Android → SimpleViewManager → ComposeView → SceneView { }
        └── iOS → RCTViewManager → UIHostingController → SceneView { }
```

## Props

### SceneView

| Prop | Type | Description |
|---|---|---|
| `modelNodes` | `ModelNode[]` | Array of models to display |
| `environment` | `string` | HDR environment path |
| `cameraOrbit` | `boolean` | Enable orbit camera controls |
| `onTap` | `(event) => void` | Tap event: `{ x, y, z, nodeName }` — the tapped model's world position and its file base name without extension. `nodeName` is `null` when no model was hit; the key is always present, so one `nodeName == null` check covers every view and platform |

### ARSceneView (extends SceneView)

| Prop | Type | Description |
|---|---|---|
| `planeDetection` | `boolean` | Enable plane detection |
| `depthOcclusion` | `boolean` | Enable LiDAR depth occlusion |
| `onPlaneDetected` | `(event) => void` | Plane detection event |

`onTap` is inherited from `SceneView`. On `ARSceneView` it reports the tapped
surface point, so `nodeName` is always `null` — a plane hit is not a model hit.

## Type Definitions

```typescript
interface ModelNode {
  src: string;           // glTF/GLB path
  position?: [number, number, number];
  rotation?: [number, number, number];
  scale?: number;
  animation?: boolean;
}

interface TapEvent {
  x: number;
  y: number;
  z: number;
  // The tapped model's file base name without extension, or `null` when the
  // tap hit no model. Never `undefined` — every dispatch path writes the key.
  nodeName: string | null;
}
```
