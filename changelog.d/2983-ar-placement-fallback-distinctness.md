<!-- category: Fixed -->
- **android-demo:** the six `ar_placement` slugs no longer share bundled fallbacks
  — `khronos_lantern.glb` was claimed by three of them and
  `khronos_damaged_helmet.glb` by two, so on a keyless build `ARPlacementDemo` /
  `ARInstantPlacementDemo` (which accumulate placed models) rendered several
  differently-labelled chips as the identical asset in one frame. Potted Monstera,
  Wooden End Table and Picture Frame now point at distinct already-bundled GLBs,
  making the six-slug → six-GLB mapping a bijection with no new binary. Guarded by
  a new `SampleAssetsTest` case that derives the slug set from the registry by
  category, mirroring the iOS guard (#2940, #2355, #2973).
