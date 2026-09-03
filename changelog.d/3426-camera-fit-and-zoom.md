<!-- category: Fixed -->

**Camera framing and zoom.** Three defects that shared one theme — the camera was told the wrong
thing about the subject in front of it.

- **Auto-fit framed the bounding sphere, not the subject.** `fitDistanceForBounds` charged every
  scene for half its AABB's *space diagonal*, then billed each field-of-view axis the *other*
  axis's distance. On a portrait viewport the horizontal FOV is the narrow one, so a subject bound
  purely by its height was pushed back by a width constraint it never hits, and the viewport could
  never be filled. The fit is now per FOV axis, in closed form, and frames the subject's sweep
  about world Y so an auto-rotating model still never clips at any yaw. It is never further than
  the old distance, and up to 2× closer for tall or compact subjects. This is the Android
  counterpart of the iOS fix in #3383. Pass `azimuthInvariant = false` for a static head-on scene
  that should not pay for a rotation it never performs. (#3426)

- **Pinch-to-zoom moved the camera a fixed number of metres.** Filament's orbit manipulator
  translates the eye by `zoomSpeed × scrolldelta` world units regardless of how far away it is, so
  one full-screen pinch moved it ~11 cm: on a scene framed 5 m away that is forty gestures to
  halve the distance, and on a 5 cm model the same gesture punched the eye straight through the
  orbit pivot — at which point Filament flips the manipulator and the next drag rebuilds the view
  from a negative distance, aiming the camera away from the subject. Zoom is now a *ratio* of the
  current camera-to-target distance and is clamped either side of the framed distance, so one
  pinch is one comfortable step at any scale and the camera can never cross its own pivot.
  (#3403, #3426)

- **The Model Viewer reset its camera whenever anything nearby changed.** Its manipulator was
  rebuilt from `remember(framing, modelCenter, recenterGeneration, sliderDistance)`, and a Filament
  manipulator carries the whole camera pose — so every step of the zoom slider threw the user's
  orbit away (#3403), and so did opening the animation bar, which is when the scaffold first
  measures its identity row and changes the framing insets (#3404). The manipulator is now keyed on
  the content alone and reads the framing, pivot and zoom live. Pinch and the "Camera distance"
  slider drive the same number, and that slider's range is now relative to the model's own fitted
  distance instead of a fixed `0.5–10 m`.

Demos re-framed off a shared, aspect-aware helper instead of hand-tuned literals: the Model Viewer
gallery (one fixed radius served models normalised from 0.20 to 0.85 units), Lighting Lab's sky
section and Secondary Camera's main view.
