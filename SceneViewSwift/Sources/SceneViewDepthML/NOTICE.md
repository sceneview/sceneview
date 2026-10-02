# SceneViewDepthML — third-party model

This product runs **Depth Anything V2 Small** (Yang et al., 2024,
https://github.com/DepthAnything/Depth-Anything-V2) through Apple's Core ML
conversion `DepthAnythingV2SmallF16.mlpackage`
(https://huggingface.co/apple/coreml-depth-anything-v2-small, revision
`cfef6f6f2a70783dedc0bfae40cecbc2052285d3`), licensed under the
**Apache License 2.0**.

The weights (about 50 MB) are not part of this package. `DepthModelStore`
downloads them only when the app asks for `.pinnedDownload()`, from the pinned
revision above, and verifies each file's SHA-256 before compiling the model.
An app that ships the model itself (bundle resource or On-Demand Resources tag)
must carry the Apache-2.0 licence and this attribution.

Only the Small variant is supported. Depth Anything V2 Base and Large are
licensed CC-BY-NC-4.0 and must not be used with this product.
