<!-- category: Fixed -->
- **Materials demo — a drag that started on a material name moved nothing ([#3609](https://github.com/sceneview/sceneview/issues/3609)).** The floating
  labels were `clickable`, and a Compose node that accepts pointer input wins the hit test
  outright, so the `SceneView` underneath was never offered the gesture. The labels are now
  pure decoration and the wall orbits from anywhere, labels included. Tapping a sphere no
  longer teleports to *Inspect* either: the camera flies onto the picked ball on an eased
  dolly and flies back out to the wall when you leave *Inspect*. The sheet and the peek
  header now say the spheres are tappable, the tap answers with a selection haptic, and the
  focused label lights up while the camera travels.
