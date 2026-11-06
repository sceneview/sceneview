<!-- category: Fixed -->
- **AR emissive materials follow the room's light** (#4950). Under `ENVIRONMENTAL_HDR` light
  estimation, the glow of a glTF emissive material now scales with the room's ambient luminance —
  full in a daylit room, down to a fifth in a dark one, never brighter than its authored
  `KHR_materials_emissive_strength` — instead of shining as if in the dark next to a model the
  estimate already darkened. `ARSceneView(emissiveFollowsLightEstimate = false)` keeps the old
  behaviour for apps that set `emissiveStrength` themselves. 3D scenes are unchanged.
