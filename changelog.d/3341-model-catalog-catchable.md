<!-- category: Fixed -->
- **The Orbital AR demo showed too many models to follow and offered nothing to catch
  ([#3341](https://github.com/sceneview/sceneview/issues/3341)).**
  The scene put eight objects on eight heights at eight speeds around a 1.5 m ring, each
  contributing its own screen-edge arrow: five to seven arrows pointed in five to seven
  directions at once and none of them was worth turning toward. Half the slots were static
  props — a helmet, a lantern, a toy car, a walking soldier stepping through empty space —
  so "models flying around you" was true of four slots out of eight, and the one the demo
  designated as the chase target was the toy car. And there was no catch mechanic at all:
  no tap handling, no hitbox, no success state, no feedback, so a user who did turn toward
  a model and tap got nothing back and read it as a mechanic they kept failing.
  The ring is now four streamed animated flyers, one per quadrant, at 0.10–0.18 rad/s
  (the old fast end, 0.30 rad/s, crossed a phone-width of view in about a second). Tapping
  catches: one projection pass per frame feeds both the arrows and the hit test, so the
  hitbox can never disagree with what is drawn; the disc is 72 dp, 1.5× Material's minimum
  touch target for a *stationary* control, because the target, the hand and the phone all
  drift between the start of a tap and its landing. A caught flyer freezes in place and
  grows 1.25×, the status pill keeps the running score, and a ring is drawn for hits *and*
  misses — a silent miss is indistinguishable from a dropped tap. Once all four are caught,
  a tap anywhere releases them, each resuming from its frozen angle rather than jumping.
  In keyless mode the four `solar` entries also all fell back to the same bundled
  character, putting one identical model at four points of the ring; they now fall back to
  four distinct GLBs, guarded by the same pairwise-distinctness test that already covered
  `ar_placement` (#2940).
