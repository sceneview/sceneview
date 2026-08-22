<!-- category: Added -->
<!-- breaking: false -->
The framing lever iOS gained with `.framingMargin(_:)` now exists at the same level on
the other two platforms. Android's `SceneView` / `Scene` composable takes a
`framingPadding: Float = DEFAULT_FRAMING_PADDING` parameter so `autoFitContent` leaves a
per-scene amount of air — the same additive fraction `CameraNode.frameToContent(padding = …)`
already used, and changing it re-arms the auto-fit pass. Web's `fitToModels()` accepts an
optional `margin` multiplier (`1.0` keeps the previous `2.5 × radius` dolly, clamped to
`0.2…10` like iOS), typed in `sceneview-web.d.ts`. The cross-platform note in `llms.txt`
now names the Web lever and spells out at every call site that Android's `padding` is
additive (`0.15`) while iOS and Web take a multiplier (`1.15`), so a snippet ported
between platforms does not land at 2.15× the distance.
