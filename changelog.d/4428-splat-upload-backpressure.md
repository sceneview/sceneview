<!-- category: Fixed -->
- **SplatNode upload backpressure ([#4428](https://github.com/sceneview/sceneview/issues/4428)).** Keep one draw-order texture upload in flight and coalesce later sorts into the latest order and filtered draw-slot count, preventing unbounded JNI references when frames are not consumed.
