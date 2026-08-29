<!-- category: Fixed -->
- **The AR camera background no longer gets an extra contrast boost ([#3338](https://github.com/sceneview/sceneview/pull/3338)).** The
  three camera-stream materials decoded the camera texel with Filament's
  `inverseTonemapSRGB()`, whose transfer leg is `pow(c, 2.2)`, while Filament re-encodes the
  frame with the exact piecewise sRGB OETF (its `Rec709-sRGB-D65` color-grading output
  stage). The two do not cancel: the round trip crushed the shadows by up to 8.5/255 at code
  16 and lifted the highlights by ~1.5/255, applied to the camera feed only — so real and
  virtual content were graded differently. The materials now decode with the exact
  IEC 61966-2-1 EOTF, making the round trip bit-exact. The YUV→RGB conversion was never at
  fault: ARCore delivers the buffer as `STANDARD_BT709 | TRANSFER_SRGB | RANGE_FULL`, and the
  EGL external sampler already handles it correctly.
