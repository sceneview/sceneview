<!-- category: Changed -->
- **Materials, Animation & Physics, Video Recording, Secondary Camera and Debug Overlay now
  show what they demonstrate
  ([#3572](https://github.com/sceneview/sceneview/issues/3572),
  [#3573](https://github.com/sceneview/sceneview/issues/3573),
  [#3574](https://github.com/sceneview/sceneview/issues/3574)).** All five rendered
  something correct and explained none of it. **Materials** was nine unlabelled spheres: each
  now carries a projected caption naming its preset — *Polished chrome*, *Car paint —
  clearcoat*, *Crystal — transmission*, *Neon sign — emissive* — and tapping one opens it
  side by side with the same sphere in matte plastic, so the parameter that changed is
  visible rather than asserted. **Animation & Physics** demonstrated neither half: the
  animation side now names the playing clip, shows its playhead in seconds, lets you scrub
  the pose and cross-fade into a second named clip with a live blend percentage, and the
  physics side answers a drop with sphere-to-sphere collision response and an impact
  counter, plus a deterministic Replay. **Video Recording** now states what it is doing and
  lets you play back or share the MP4 it produced, instead of writing a file the user never
  sees. **Secondary Camera** labels both views and explains the second `Camera` feeding the
  picture-in-picture. **Debug Overlay** names each metric it prints and reports the first
  sustained drop below 55 fps as the stress test spawns nodes. Every string is a resource,
  and each sample carries a one-paragraph explainer naming the SDK API it exercises.
