<!-- category: Fixed -->
- **AR placement: gestures now work the way Google Scene Viewer and AR Quick Look do.**
  - **Pinch follows your fingers exactly.** A two-finger pinch on a placed object now follows the fingers one to one from where they landed, so spreading them twice as far doubles the object. Bringing them back returns it to exactly the size it started at. Before, each pinch event multiplied a factor that was already cumulative, so the size ran away from the fingers.
  - **Selection shows.** A selected object now shows a thin white ring on the floor (or wall) around its footprint. The ring fades out when you tap empty space.
  - **Double-tap resets the size.** Double-tapping the object animates it back to its real-world size (100 %). Double-tapping again returns it to the size you had pinched it to.
  - **New API.** In `arsceneview`, `AutoPlacementModel` draws the ring, and `AutoPlacementScene` handles the double-tap. There are two new public members: `AutoPlacementState.toggleBaseScale()` and `AutoPlacementState.showsSelectionRing`. Set `showsSelectionRing = false` to opt out of the ring.
  - **Demo.** The AR Placement demo and "View in your room" use all three.
