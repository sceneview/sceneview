# 3D Models for SceneView Capability Demos — Research

> Research compiled for SceneView demo app expansion.
> Models selected to showcase specific SceneView features.

## Top 10 Picks (covers the most features with fewest downloads)

| # | Model | Feature | License | ~Size |
|---|-------|---------|---------|-------|
| 1 | **DamagedHelmet** (Khronos) | PBR, emissive, lighting, tone mapping, SSAO | CC-BY | ~3.7 MB |
| 2 | **Fox** (Khronos) | Skeletal animation (3 clips), AR placement | CC-BY | ~1 MB |
| 3 | **ABeautifulGame** (Khronos) | Transmission, volume, SSAO, complex scene | CC-BY | ~8-12 MB |
| 4 | **SheenChair** (Khronos/Wayfair) | Sheen extension, AR furniture | CC-BY | ~4 MB |
| 5 | **SunglassesKhronos** | Iridescence + transmission | CC-BY | ~2-4 MB |
| 6 | **EmissiveStrengthTest** (Khronos) | Bloom post-processing | CC-BY | ~0.1 MB |
| 7 | **AnimatedMorphSphere** (Khronos) | Morph targets | CC0 | ~50 KB |
| 8 | **Kenney Physics Assets** | 215 physics-ready shapes | CC0 | ~5 MB pack |
| 9 | **CC0 Chair 8 + Table + Plant Pot** (plaggy) | AR furniture trio | CC0 | ~1.5 MB |
| 10 | **Brain with Labeled Parts** | TextNode annotation | CC-BY | ~5-10 MB |

---

## 1. PBR Materials (ModelNode)

### Metallic / Roughness Extremes
| Model | License | ~Size | Feature |
|-------|---------|-------|---------|
| **DamagedHelmet** (Khronos) | CC-BY | ~3.7 MB | Mixed metallic/roughness, emissive visor, normal maps |
| **FlightHelmet** (Khronos) | CC-BY | ~5 MB | Multiple materials: leather, glass, metal, rubber |
| **AnisotropyBarnLamp** (Khronos) | CC-BY | ~8.4 MB | Brushed copper with anisotropic reflections, 6 extensions |

### Iridescence / Sheen / Clearcoat
| Model | License | ~Size | Feature |
|-------|---------|-------|---------|
| **SunglassesKhronos** | CC-BY | ~2-4 MB | `KHR_materials_iridescence` + `KHR_materials_transmission` |
| **IridescenceLamp** (Wayfair) | CC-BY | ~3-5 MB | Iridescence with thickness texture |
| **SheenChair** (Wayfair) | CC-BY | ~4 MB | `KHR_materials_sheen` — damask fabric |
| **ClearCoatCarPaint** | CC0 | ~1-2 MB | `KHR_materials_clearcoat` — automotive paint |

### Transmission / Volume (Glass, Gems)
| Model | License | ~Size | Feature |
|-------|---------|-------|---------|
| **ABeautifulGame** (ASWF) | CC-BY | ~8-12 MB | Chess set with glass pawn tops |
| **GlassVaseFlowers** (Wayfair) | CC-BY | ~5-8 MB | Alpha blend vs. transmission comparison |
| **CommercialRefrigerator** | CC-BY | ~5-10 MB | Glass doors + animated door |

---

## 2. Animations

### Skeletal Animation
| Model | License | ~Size | Feature |
|-------|---------|-------|---------|
| **Fox** (Khronos) | CC-BY | ~1 MB | 3 animations (Survey, Walk, Run) — `playAnimation(index)` |
| **CesiumMan** (Khronos) | CC-BY | ~0.5 MB | Textured walking man |
| **Soldier.glb** (three.js) | not free: a Mixamo character, removed in #4586 | ~1 MB | Replaced by the CC0 Quaternius Ranger (idle/walk/run/sprint/dance) |
| **Quaternius Platformer Character** | CC0 | ~0.5-1 MB | 18 animations per character |

### Morph Targets
| Model | License | ~Size | Feature |
|-------|---------|-------|---------|
| **AnimatedMorphSphere** (Khronos) | CC0 | ~50 KB | Smooth morph transitions |
| **AnimatedMorphCube** (Khronos) | CC0 | ~10 KB | Two morph targets, minimal |
| **MorphStressTest** (Khronos) | CC-BY | ~1-2 MB | High morph-target count stress test |

### Animated Machinery
| Model | License | ~Size | Feature |
|-------|---------|-------|---------|
| **CesiumMilkTruck** (Khronos) | CC-BY | ~0.5 MB | Spinning wheels (rigid body) |
| **CC0 - Gear** (plaggy) | CC0 | ~0.3 MB | 384 polys, PBR metallic |

---

## 3. Lighting (LightNode)

### Dramatic Lighting Demos
| Model | License | ~Size | Feature |
|-------|---------|-------|---------|
| **BoomBox** (Khronos) | CC0 | ~10 MB | Emissive panel + reflective metal |
| **Corset** (Khronos) | CC0 | ~5 MB | Fine detail needing good lighting |
| **FlightHelmet** (Khronos) | CC-BY | ~5 MB | Glass reflects, leather absorbs |

### Emissive Materials
| Model | License | ~Size | Feature |
|-------|---------|-------|---------|
| **EmissiveStrengthTest** (Khronos) | CC-BY | ~0.1 MB | Progressive brightness cubes |
| **CC0 - Neon Sign Open** (plaggy) | CC0 | ~1 MB | Neon "OPEN" sign, 974 polys |

---

## 4. Physics (PhysicsNode)

| Model | License | ~Size | Feature |
|-------|---------|-------|---------|
| **Kenney Physics Assets** | CC0 | ~5 MB | 215 shapes: balls, cubes, planks, ramps |
| **Avocado** (Khronos) | CC0 | ~0.1 MB | Simple convex shape for drop/bounce |
| **Box** (Khronos) | CC-BY | ~1 KB | Minimal stacking primitive |
| **Low Poly Bowling Pins** (Sketchfab) | CC-BY | ~0.5 MB | 3 damage levels per pin |
| **Quaternius Platformer Pack** | CC0 | ~15 MB | Platforms, hazards, stackable shapes |

---

## 5. Text & Labels (TextNode)

| Model | License | ~Size | Feature |
|-------|---------|-------|---------|
| **Brain with Labeled Parts** (Sketchfab) | CC-BY | ~5-10 MB | Pre-labeled anatomical regions |
| **Male Skeleton** (Sketchfab) | CC-BY | ~5-10 MB | Labeled bones |
| **Anatomy of a Flower** (Sketchfab) | CC-BY | ~1-3 MB | Named parts (stamen, pistil) |

---

## 6. Path & Line Drawing (PathNode)

Best demonstrated procedurally (sine waves, Lissajous, helices). Context models:

| Model | License | ~Size | Feature |
|-------|---------|-------|---------|
| **DNA Helix** (Sketchfab) | CC-BY | ~1-3 MB | Extract path coords for helix demo |
| **Klein Bottle** (Sketchfab) | CC-BY | ~0.5-1 MB | Mathematical surface |

---

## 7. Post-Processing

### Bloom
| Model | License | ~Size | Feature |
|-------|---------|-------|---------|
| **EmissiveStrengthTest** (Khronos) | CC-BY | ~0.1 MB | Bloom makes flat white → glowing |
| **CC0 - Neon Sign Open** (plaggy) | CC0 | ~1 MB | Neon tubes glow with bloom |
| **BoomBox** (Khronos) | CC0 | ~10 MB | Emissive panel + bloom |

### SSAO
| Model | License | ~Size | Feature |
|-------|---------|-------|---------|
| **ABeautifulGame** (Khronos) | CC-BY | ~8-12 MB | Contact shadows between chess pieces |
| **FlightHelmet** (Khronos) | CC-BY | ~5 MB | Depth in goggle cavities |
| **Corset** (Khronos) | CC0 | ~5 MB | Rich AO in fabric folds |

### Tone Mapping
| Model | License | ~Size | Feature |
|-------|---------|-------|---------|
| **DamagedHelmet** (Khronos) | CC-BY | ~3.7 MB | Wide dynamic range: ACES vs Filmic vs Linear |
| **AnisotropyBarnLamp** (Khronos) | CC-BY | ~8.4 MB | Highlight rolloff on copper |

---

## 8. AR Placement (AnchorNode)

### Furniture (room scale)
| Model | License | ~Size | Feature |
|-------|---------|-------|---------|
| **CC0 - Chair 8** (plaggy) | CC0 | ~0.5 MB | 324 polys, PBR, glTF + USDZ |
| **CC0 - Table** (plaggy) | CC0 | ~0.5 MB | Pairs with Chair 8 |
| **SheenChair** (Khronos/Wayfair) | CC-BY | ~4 MB | Premium AR furniture preview |

### Plants / Decorative
| Model | License | ~Size | Feature |
|-------|---------|-------|---------|
| **CC0 - Plant Pot** (plaggy) | CC0 | ~0.5 MB | Tabletop-scale |
| **Avocado** (Khronos) | CC0 | ~0.1 MB | Tiny object, demonstrates scaleToUnits |

### Characters (human scale)
| Model | License | ~Size | Feature |
|-------|---------|-------|---------|
| **Fox** (Khronos) | CC-BY | ~1 MB | Animated walk, floor-scale placement |
| **CesiumMan** (Khronos) | CC-BY | ~0.5 MB | Walking human figure |
| **PS1 Character Walk Cycle** (Sketchfab) | CC0 | ~0.5 MB | Retro-style, CC0, walk loop |

---

## Key Repositories

| Repository | License | Description |
|---|---|---|
| [Khronos glTF-Sample-Assets](https://github.com/KhronosGroup/glTF-Sample-Assets) | CC0/CC-BY | Official reference models for all glTF extensions |
| [Poly Haven](https://polyhaven.com/models) | CC0 | 1700+ photoscanned assets, up to 8K textures |
| [Kenney 3D](https://kenney.nl/assets/category:3D) | CC0 | 40k+ game-ready GLB assets |
| [Quaternius](https://quaternius.com/) | CC0 | Themed packs with animated characters |
| [three.js models](https://github.com/mrdoob/three.js/tree/dev/examples/models/gltf) | MIT | Soldier, Horse, Flamingo, Parrot — lightweight animated GLBs |
| [plaggy CC0 collection](https://sketchfab.com/plaggy/models) | CC0 | Furniture, signs, gears, plants with PBR + glTF/USDZ |
| [awesome-cc0](https://github.com/madjin/awesome-cc0) | N/A | Master list of all CC0 3D asset sources |
