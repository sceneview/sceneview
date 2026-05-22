<!-- category: Fixed -->
- **iOS demo** — renamed placeholder scenes `ArEisScene` → `ArImageStabilizationScene` and `ArPosePlacementScene` → `ArPoseScene` so their `@sceneId` directives match the canonical Android IDs (`ar-image-stabilization`, `ar-pose`) used by QR codes and deep links; closes the gap left by #2174 which fixed `allowedIds` but not the scene catalogue.
