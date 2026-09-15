<!-- category: Added -->
- **Tilt the tray in the `animation-physics` demo and the balls roll downhill
  ([#3621](https://github.com/sceneview/sceneview/issues/3621)).** A "Tilt with drag" toggle swaps
  what a one-finger drag does — camera orbit when it is off (unchanged), tipping the tray when it
  is on — with mirrored Pitch / Roll sliders for precise angles and screen readers, a ±20° clamp
  and a "Level" button that springs the tray back flat. Under the hood `PhysicsBody` /
  `PhysicsNode` (and the pure-Kotlin `PhysicsState` in `sceneview-core`) gained a `gravity` vector
  instead of a hardcoded -9.8 m/s² on Y: the tray hangs off one pivot node and the bodies get that
  same gravity rotated into the tray's frame, so the simulation keeps its flat floor plane and its
  axis-aligned rails. Bodies no longer fall asleep while gravity has a horizontal component.
