<!-- category: Fixed -->
<!-- breaking -->
<!-- RELEASE NOTE (maintainer-only):
     The breaking half is `PlaneDiscoveryGuide` / `PlaneDiscoveryGuideOverlay` in the
     published `arsceneview` artifact: a new `bottomClearance` parameter (source-compatible,
     it has a default) and the pill's anchor moves. The parameter's type is `Dp`, an inline
     value class, so the JVM name is mangled — `PlaneDiscoveryGuide` becomes
     `PlaneDiscoveryGuide-xKBSf-U` and `PlaneDiscoveryGuideOverlay` becomes
     `PlaneDiscoveryGuideOverlay-6qCrX9Q` in `arsceneview.api`. That is a HARD binary break:
     a consumer that only swaps the .aar without recompiling gets NoSuchMethodError, so this
     cannot ride a patch bump. Everything else in this fragment is `samples/android-demo`,
     which ships in no artifact. `apiDump` regenerated in this PR.

     The anchor move is the part a consumer can SEE without recompiling: a host that had
     compensated for the missing insets with its own negative/positive padding will now be
     over-compensating. That is why it is marked breaking rather than fixed-and-quiet. -->
- **`PlaneDiscoveryGuide`'s message pill now respects window insets, and takes its clearance from the host ([#3503](https://github.com/sceneview/sceneview/issues/3503)).** It was anchored 40 dp off the **raw** window edge with no inset handling of any kind, so on a modern device it sat partly under the gesture bar, and under *any* bottom chrome the host drew. It now applies `safeDrawing` and accepts `bottomClearance` — the room the host's own dock, toolbar or call-to-action takes, which the guide cannot measure for itself. Its side margin moves 24 dp → 16 dp, the Material gutter, so it lines up with the host's grid instead of sitting 8 dp inside it. **To migrate:** if you host the guide over your own bottom chrome, pass `bottomClearance = <your chrome's height> + <your gutter>` and delete whatever padding you were using to lift the pill; if you host it over a bare camera, pass nothing.

<!-- category: Fixed -->
- **Android demo — one bottom anchor on the AR placement screen, instead of four disagreeing ones ([#3503](https://github.com/sceneview/sceneview/issues/3503)).** The coaching line, the plane-discovery pill, the dock and the snackbar each measured from a different edge with a different gutter — 0 dp, 24 dp, 16 dp and 16 dp of side margin, and one of them ignored the navigation bar entirely. Everything the screen says now stacks above one measured line: the bottom of the safe area, plus the dock the scaffold parks there (published as `LocalDemoChromeBottomInset`, the mirror of the existing top inset), plus one 16 dp gutter. The coaching line is the child nearest the dock, so the transient pills above it grow upward and it no longer hops when you pinch. The scrim under the dock goes 60 % → 68 % black, which takes the dock's 11 sp captions from **4.20:1** to **5.37:1** over a white scene — the top scrim stays at 60 %, because the text it carries lands on the wash directly and already passes. (The dock's captions do not: they sit on the dock's own white-14 % glass fill, which lifts the ground back up before the caption lands on it. That step is what the old "the scrim gives ~5.6:1" note in the token had missed.)
- **Android demo — the model chooser's call-to-action bar is a container you can see ([#3503](https://github.com/sceneview/sceneview/issues/3503)).** It was `surface` on a `surface` page with no divider, so the catalogue appeared to be sliced by an edge nothing drew. It is now `surfaceContainerHigh` behind a 1 dp `outlineVariant` hairline, and the content padding moved inside the scroll so a card row slides *under* that bar instead of stopping dead against it. `surfaceContainerHigh` because it is the role every other container in the app already uses, and because it is the lowest role with a tone in both schemes: in the light scheme `surfaceContainerLowest`, `surfaceContainerLow` and `surfaceContainer` are all `0xFFFFFFFF`, so the obvious choice would have been visible in dark and invisible in light.
