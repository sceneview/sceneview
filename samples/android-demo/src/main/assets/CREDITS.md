# Asset Credits

All assets used in this demo app are free and distributed under permissive licenses.

> **Optimization note (#934, #2305).** The bundled GLBs and HDRs are compressed
> for a lean APK while preserving on-device visual quality:
> - **GLB models** — geometry is `KHR_draco_mesh_compression`. Textures are
>   **PNG/JPEG**: Filament's Android prebuilt ships `gltfio` with WebP support
>   compiled out (`isWebpSupported() == false`), so `EXT_texture_webp` textures
>   render untextured on Android (#2305). The models are therefore bundled with
>   PNG/JPEG textures the `gltfio` `StbProvider` decodes natively (Draco geometry
>   is decoded natively too). KTX2/Basis is a worthwhile future optimization to
>   reclaim the size PNG costs vs WebP.
> - **HDR environments** — equirect maps are downsampled 2048×1024 → 1024×512
>   in linear-radiance space (2×2 box average, energy-preserving). The `_2k`
>   suffix is kept as a stable filename only; resolution is now 1K.

## 3D Models (.glb)

### From KhronosGroup/glTF-Sample-Assets (GitHub)
All Khronos glTF Sample Assets are licensed under permissive licenses (see individual model licenses).
Repository: https://github.com/KhronosGroup/glTF-Sample-Assets

| File | Original Name | Category | Size (compressed) | License |
|---|---|---|---|---|
| `khronos_damaged_helmet.glb` | DamagedHelmet | Sci-fi helmet | 3.5 MB | CC-BY 4.0 (theblueturtle_) |
| `khronos_fox.glb` | Fox | Animated character | 94 KB | CC-BY 4.0 (PixelMannen) |
| `khronos_lantern.glb` | Lantern | Decor / Architectural | 9.0 MB | CC-BY 4.0 (Microsoft) |
| `khronos_toy_car.glb` | ToyCar | Vehicle / Toy car | 2.1 MB | CC-BY 4.0 (Khronos) |

### Other sources (CC0 / CC-BY)
| File | Category | Size (compressed) |
|---|---|---|
| `shiba.glb` | Animated character (Shiba dog) | 669 KB |
| `threejs_soldier.glb` | Animated character (4 clips) | 2.3 MB |

## HDR Environments (.hdr)

### From Poly Haven (polyhaven.com)
All Poly Haven assets are CC0 (public domain). No attribution required.
Website: https://polyhaven.com

| File | Original Name | Category | Size (1K, compressed) |
|---|---|---|---|
| `chinese_garden_2k.hdr` | Chinese Garden | Nature / Garden | 1.7 MB |
| `night_sky_2k.hdr` | Dikhololo Night | Outdoor / Night sky (Milky Way) | 1.7 MB |
| `outdoor_cloudy_2k.hdr` | Outdoor Cloudy | Outdoor / Cloudy | 1.6 MB |
| `rooftop_night_2k.hdr` | Rooftop Night | Outdoor / Night | 1.6 MB |
| `studio_2k.hdr` | Studio | Studio | 1.6 MB |
| `studio_warm_2k.hdr` | Studio Warm | Studio (warm) | 1.5 MB |
| `sunset_2k.hdr` | Sunset | Outdoor / Sunset | 1.2 MB |

## License Summary

- **Khronos glTF Sample Assets**: CC-BY 4.0 International
  - Attribution: Khronos Group, Wayfair, Microsoft, and individual contributors
  - https://github.com/KhronosGroup/glTF-Sample-Assets/blob/main/LICENSE.md
- **Poly Haven HDRIs**: CC0 1.0 Universal (Public Domain)
  - No attribution required
  - https://polyhaven.com/license
