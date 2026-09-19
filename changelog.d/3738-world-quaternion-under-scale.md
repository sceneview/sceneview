<!-- category: Fixed -->
<!-- breaking: false -->
<!-- RELEASE NOTE (maintainer-only):
     No signature changes — `apiCheck` is green on all three modules without an `apiDump`.
     But this is a VISIBLE BEHAVIOUR change: `Node.worldQuaternion` and `Node.worldRotation`
     return different numbers than they did, for every node that has a scale or sits under a
     scaled ancestor. The old numbers were wrong, so anyone who compensated for them in
     application code — a hand-tuned offset on a billboard under a scaled parent, an AR anchor
     alignment nudged until it looked right — will see their compensation become the new error.
     That is worth a line in the release notes rather than a silent patch.

     Unscaled hierarchies are bit-identical: `rotation()` divides by column lengths of exactly
     1.0. Scenes that only ever use a pure yaw under a uniform scale are also unaffected at
     0° and 180°, which is why this survived so long.

     The `Node.getLocalQuaternion` scaled branch is deleted, not repaired: it was added under
     #2294 on the premise that `inverse(M).toQuaternion()` was the exact path the quaternion
     shortcut diverged from. The opposite was true. -->
- **`Node.worldQuaternion` ignores scale again — it had been folding any ancestor's scale into the rotation it reported ([#3738](https://github.com/sceneview/sceneview/issues/3738)).** The world cache derived the quaternion with kotlin-math's `Mat4.toQuaternion()`, which is Shepperd's trace method run straight on the basis: correct only when that basis is orthonormal. A world matrix is `T·R·S`, so the scale entered the trace and the renormalised result was a **different rotation** — and not only for a non-uniform scale, which is the part that hid it. A node pitched 90° under a parent scaled 2 reported 106.26°; a tilted axis under a scale of 10 was 32.38° out, and under `(0.25, 2, 10)`, 78.11°. Everything downstream inherited it: billboards under a scaled parent faced the wrong way, `lookAt` aimed off-target, AR anchor alignment drifted, and `node.worldQuaternion = x` followed by reading it back returned something else (`|dot| = 0.98` under a parent scaled 2). Both world-space reads now normalise the basis columns first, and the round trip is exact. Two scenes were immune and that is why this lasted: an unscaled hierarchy, and a turntable — a pure yaw keeps the quaternion *axis* exactly and corrupts only the angle, so an axis-only assertion sees nothing, and a yaw of exactly 0° or 180° comes through untouched.
- **A zero scale no longer turns a node's world rotation into NaN.** Normalising a collapsed basis column is `0 / 0`, and the NaN then propagated into every child transform and into any physics or animation driver integrating the value — the failure mode the `Scale` KDoc already warned about for `Scale(x = 1f)`. One collapsed axis is now rebuilt from the other two, which recovers the rotation exactly; with two or three axes gone the identity is returned. Finite, either way.
- **`normalToTangent` accepts a normal that is not unit-length.** It built its tangent frame with the caller's vector as the forward column, so a normal of length 5 skewed the extracted quaternion by 18.92°. Every in-tree caller happens to pass a unit normal; anyone deriving one from unnormalised geometry did not.
- **Two cases that scale cannot fix are now documented on `Node.worldQuaternion` rather than papered over.** A **non-uniformly** scaled *ancestor* with a rotation below it shears the world basis, so no quaternion equals the rotation you set — the value returned is the closest orthonormal frame, measured within a few degrees, and setter round trips are correspondingly approximate; keep an ancestor's scale uniform if you need exactness there. A **negative** scale mirrors the basis, which is not a rotation at all: the value stays finite and unit but means nothing, and nothing can detect the case from `worldScale`, which reports column lengths (a scale of `-2` reads back as `2`).
