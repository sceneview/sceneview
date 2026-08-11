<!-- category: Added -->
<!-- breaking -->
- **`SceneView(isRendering = false)` parks the frame loop on an idle scene ([#3108](https://github.com/sceneview/sceneview/issues/3108)).** A static 3D screen kept calling `withFrameNanos` at display rate forever, because Compose's frame clock does not idle on its own — on devices whose Choreographer keeps ticking a visually static UI (Samsung foldables in the report) that is a continuously rendered frame per vsync with nothing to show, and it reads to the user as battery drain and thermal throttling. The new parameter defaults to `true`, so nothing changes for existing callers.
- The paused loop **suspends rather than spins**: it waits on the snapshot rather than polling on a timer, so an idle scene schedules no work at all instead of trading 60 GPU frames a second for 60 CPU wake-ups a second — and rendering resumes on the snapshot apply itself, not on the next poll tick. `SceneIsRenderingTest` discriminates the two on virtual time, which is the only way to tell them apart from the outside.
- The parameter is also forwarded by the deprecated `Scene` alias, and documented with the one thing that makes it easy to misuse: **while it is `false` nothing is presented at all** — a moved node, a camera change, a finished model load and a viewport resize all leave the last drawn frame on screen. It has to be driven from an "is anything dirty" signal that outlives the last mutation by a frame, not from "is an animation running", which is already `false` at the instant a one-shot change is published.

<!-- RELEASE NOTE (maintainer-only):
     Marked breaking because the parameter changes the public `SceneView` / `Scene` JVM
     signatures (sceneview.api updated in this PR), so pre-compiled consumers must recompile
     even though every source call site keeps working. Per the repo's policy that ships
     breaking changes as a MINOR bump, this cannot ride a patch tag.
     Placement follows the existing precedent set by renderQuality / autoCenterContent /
     autoFitContent — grouped with isOpaque rather than appended at the end of the list. -->
