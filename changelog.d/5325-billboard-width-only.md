<!-- category: Fixed -->
- Android `BillboardNode` now honors a lone `widthMeters` or `heightMeters` and derives the other dimension from the bitmap aspect ratio. Passing both still uses both values, while passing neither keeps the normalized default. ([#5325](https://github.com/sceneview/sceneview/issues/5325))
