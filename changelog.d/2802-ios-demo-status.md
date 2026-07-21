<!-- category: Added -->
- iOS demo: `DemoStatus` grows from 2 states to 4 — `.working` / `.knownIssue`
  / `.inReview` / `.comingSoon` — mirroring Android's `DemoStatus`
  (`Working`/`KnownIssue`/`ComingSoon`/`InReview`). Before this, iOS could not
  express a known bug on an already-implemented demo or a newly-shipped demo
  awaiting review sign-off, even though Android uses both states today (5
  `KnownIssue` + 2 `InReview` demos, verified by grep). The collator
  (`collate-ios-demos.sh`) gains an optional `@status` directive alongside
  `@sceneId`/`@available`, defaulting sensibly when omitted (`working` for an
  `@available true` scene, `comingSoon` for one that isn't) so none of the 51
  existing `*Scene.swift` files needed an edit, and cross-validates `@status`
  against `@available` so the two can't contradict each other. `SamplesTab`
  renders a small `StatusBadge` capsule per status ("Preview" / "In review" /
  "Soon"; `.working` shows no badge) — the iOS mirror of Android's
  `DemoListScreen.kt` status chip. L0.4 of the iOS/Android catalog-ISO effort
  (#2798); depends on the generated registry (#2800).
