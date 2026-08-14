<!-- category: Fixed -->
- **`Frame.hasUpdatedTrackable()` ignored its argument and returned a collection ([#3157](https://github.com/sceneview/sceneview/issues/3157)).** The AR helper discarded the `trackable` it was passed and returned `getUpdatedTrackables(T::class.java)`, so `if (frame.hasUpdatedTrackable(plane))` never compiled and the obvious reading of the name was wrong. It now returns `Boolean` — whether that exact trackable was updated this frame. Use `getUpdatedTrackables()` (or `getUpdatedPlanes()` and friends) when you want the collection.

<!-- category: Performance -->
- **Two allocation-heavy math conversions ([#3157](https://github.com/sceneview/sceneview/issues/3157)).** `Mat4.toColumnsDoubleArray()` went through a `FloatArray`, a boxed `List<Double>` and then a `DoubleArray`; `FloatArray.toLinearSpace()` went through a boxed `List<Float>`. Both now fill their result array directly — one allocation each instead of three and two.
