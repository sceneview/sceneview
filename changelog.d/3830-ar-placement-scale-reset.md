<!-- category: Fixed -->
AR Placement demo: the "Pick a model" tray showed a generic cube glyph for every
streamed item instead of its real thumbnail, and pinching a model away from its
real-world size left no way back to 100% once the fingers lifted. The tray now
resolves thumbnails from the resolved asset path instead of gating on the source
type, and the scale read-out persists briefly after a pinch and can be tapped to
reset the model to its real-world size.
