# SceneView iOS Demo — App Store Setup Guide

## Prerequisites

- [x] Apple Developer account ($99/year) — already active
- [ ] Xcode 16+ installed on Mac
- [ ] Physical iOS device for AR testing (simulator doesn't support ARKit camera)

## Step 1: Configure Xcode Project

1. Open `SceneViewSwift/Examples/SceneViewDemo/SceneViewDemo.xcodeproj` in Xcode
2. Select the **SceneViewDemo** target
3. Under **Signing & Capabilities**:
   - Set **Team** to your Apple Developer team
   - Set **Bundle Identifier** to `io.github.sceneview.demo.ios`
   - Enable **Automatically manage signing**
4. Add the **SceneViewSwift** local package:
   - File → Add Package Dependencies → Add Local → select `SceneViewSwift/` folder

## Step 2: App Store Connect Setup

### Create the App

1. Go to [App Store Connect](https://appstoreconnect.apple.com)
2. **My Apps** → **+** → **New App**
3. Fill in:
   - **Name**: `SceneView Demo`
   - **Primary Language**: English (U.S.)
   - **Bundle ID**: `io.github.sceneview.demo.ios`
   - **SKU**: `sceneview-demo-ios`
   - **Access**: Full Access

### App Information

| Field | Value |
|---|---|
| Subtitle | 3D & AR SDK for iOS |
| Category | Developer Tools |
| Secondary Category | Education |
| Content Rights | Does not contain third-party content |
| Age Rating | 4+ (no objectionable content) |

### App Privacy

Since the Firebase release (Analytics, Crashlytics, Cloud Messaging), the answers are
**Data collected**, every type **Not linked to the user** and **Not used for tracking**:

| Data type | Purposes |
|---|---|
| Location > Coarse Location | Analytics |
| Usage Data > Product Interaction | Analytics |
| Usage Data > Other Usage Data | Analytics, App Functionality |
| Diagnostics > Crash Data | App Functionality |
| Diagnostics > Performance Data | App Functionality |
| Diagnostics > Other Diagnostic Data | App Functionality |
| Identifiers > Device ID | Analytics, App Functionality |

The app's `PrivacyInfo.xcprivacy` must declare the same list. No IDFA (Firebase Analytics
is linked without `GoogleAppMeasurementIdentitySupport`), hence no ATT prompt. Privacy
policy: <https://sceneview.github.io/privacy.html>.

### Pricing & Availability

- **Price**: Free
- **Availability**: All territories

## Step 3: Store Listing Metadata

### Description (English)

```
SceneView Demo showcases the SceneView iOS SDK — a SwiftUI-native library
for building 3D and augmented reality experiences.

FEATURES:

Explore Tab
- Interactive 3D viewer with orbit camera controls
- 6 HDR environment presets (Studio, Outdoor, Sunset, Night, Warm, Autumn)
- Procedural geometry showcase with PBR materials

Shapes Tab
- Live previews of all primitive shapes (cube, sphere, cylinder, cone, plane)
- 3D text rendering with extrusion
- Axis gizmo line rendering
- Code snippets for each shape

AR Tab
- Tap-to-place objects on real-world surfaces
- Plane detection with coaching overlay
- Multiple object types (cube, sphere, cylinder)

Built with SceneView — the open-source 3D & AR SDK for iOS and Android.
```

### Description (French)

```
SceneView Demo présente le SDK SceneView iOS — une bibliothèque SwiftUI native
pour créer des expériences 3D et de réalité augmentée.

FONCTIONNALITÉS :

Onglet Explorer
- Visionneuse 3D interactive avec caméra orbitale
- 6 environnements HDR (Studio, Extérieur, Coucher de soleil, Nuit, Chaud, Automne)
- Démonstration de géométrie procédurale avec matériaux PBR

Onglet Formes
- Aperçu en direct de toutes les primitives (cube, sphère, cylindre, cône, plan)
- Rendu de texte 3D avec extrusion
- Rendu de lignes avec gizmo d'axes
- Extraits de code pour chaque forme

Onglet RA
- Placez des objets sur les surfaces réelles d'un simple tap
- Détection de plans avec overlay de coaching
- Plusieurs types d'objets (cube, sphère, cylindre)

Construit avec SceneView — le SDK 3D et RA open-source pour iOS et Android.
```

### Keywords

`3D,AR,augmented reality,SceneView,RealityKit,SwiftUI,model viewer,USDZ,SDK,developer`

### What's New (v1.0.0)

```
Initial release of SceneView Demo for iOS:
- 3D model viewer with orbit camera and HDR environments
- Procedural geometry showcase with PBR materials
- AR tap-to-place demo with plane detection
```

### Screenshots Required

| Device | Size | Count |
|---|---|---|
| iPhone 6.7" (15 Pro Max) | 1290 × 2796 | 3-10 |
| iPad 12.9" (6th gen) | 2048 × 2732 | 3-10 |

**Screenshot ideas:**
1. Explore tab — 3D shapes with Studio environment
2. Explore tab — metallic sphere with Sunset environment
3. Shapes tab — cube with code snippet
4. AR tab — objects placed on a table
5. AR tab — coaching overlay

### App Icon

- 1024 × 1024 px PNG (no transparency, no rounded corners — Apple applies mask)
- Should match the Android demo icon style: isometric 3D cube on purple background

## Step 4: Build & Upload

```bash
# In Xcode:
# 1. Select "Any iOS Device (arm64)" as destination
# 2. Product → Archive
# 3. Window → Organizer → Distribute App → App Store Connect
# 4. Upload

# Or via command line (after configuring signing):
xcodebuild -project SceneViewDemo.xcodeproj \
  -scheme SceneViewDemo \
  -destination 'generic/platform=iOS' \
  -archivePath SceneViewDemo.xcarchive \
  archive

xcodebuild -exportArchive \
  -archivePath SceneViewDemo.xcarchive \
  -exportOptionsPlist ExportOptions.plist \
  -exportPath ./build
```

## Step 5: TestFlight

1. After upload, the build appears in App Store Connect → TestFlight
2. Add internal testers (your team)
3. Add external testers (beta users) — requires Apple review (~24h)
4. Collect feedback before App Store submission

## Step 6: Submit for Review

1. App Store Connect → App Store → prepare submission
2. Attach screenshots and metadata
3. Select the uploaded build
4. Submit for review
5. Review typically takes 24-48 hours

## Notes

- Camera usage description already set in Xcode project: "Camera access is needed for augmented reality features."
- The app requires iOS 18+ for RealityKit features
- AR features only work on physical devices with LiDAR or TrueDepth camera
- The bundle ID `io.github.sceneview.demo.ios` should be registered in your Apple Developer account
