<!-- category: Added -->
- iOS AR People Occlusion demo (`ar-people-occlusion`): toggle ARKit `personSegmentationWithDepth` to hide virtual cubes behind real people walking in front; requires A12+ chip (#910).
- iOS AR Body Tracker demo (`ar-body-tracker`): `ARBodyTrackingConfiguration` + RealityKit `BodyTrackedEntity` marks the detected skeleton root joint in real time; requires A12+ chip (#910).
- iOS AR Scene Mesh demo (`ar-scene-mesh`): `ARWorldTrackingConfiguration.sceneReconstruction = .meshWithClassification` builds a live LiDAR mesh with a debug wireframe toggle; requires LiDAR device (#910).
