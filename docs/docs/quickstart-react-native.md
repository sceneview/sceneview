# React Native Quickstart

SceneView provides a React Native module that bridges to native SceneView rendering on both Android (Filament) and iOS (RealityKit).

## Install

```bash
npm install @sceneview-sdk/react-native
cd ios && pod install
```

### iOS: raise the Podfile deployment target first

`SceneViewSwift`'s floor is iOS 18.0, so the module's podspec declares
`:ios => "18.0"`. A stock React Native `Podfile` uses
`min_ios_version_supported` (13.4), and `pod install` then fails with
*"Specs satisfying the `react-native-sceneview` dependency were found, but they
required a higher minimum deployment target"*. Edit `ios/Podfile` before
installing:

```ruby
platform :ios, '18.0'
```

Xcode 16+ is required to build against that target.

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
      cameraControlMode="orbit"
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
        └── iOS → RCTViewManager → SceneViewerHostView → SceneView { }
```

## Props

### SceneView

| Prop | Type | Description |
|---|---|---|
| `modelNodes` | `ModelNode[]` | Array of models to display |
| `environment` | `string` | HDR environment path |
| `cameraControlMode` | `'orbit' \| 'pan' \| 'firstPerson'` | Camera mode. `pan`/`firstPerson` are iOS-only |
| `cameraOrbit` | `boolean` | **Deprecated**, inert on iOS — use `cameraControlMode` |
| `onTap` | `(event) => void` | Tap event: `{ x, y, z, nodeName }` — the tapped model's world position and its file base name without extension (`null` when no model was hit) |

### ARSceneView (extends SceneView)

| Prop | Type | Description |
|---|---|---|
| `planeDetection` | `boolean` | Enable plane detection |
| `depthOcclusion` | `boolean` | Enable LiDAR depth occlusion |
| `onPlaneDetected` | `(event) => void` | Plane detection event |

## Type Definitions

```typescript
interface ModelNode {
  src: string;           // glTF/GLB path
  position?: [number, number, number];
  rotation?: [number, number, number];
  scale?: number;
  animation?: boolean;
}
```
