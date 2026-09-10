<!-- category: Fixed -->
- **Tapping a demo card in the iOS Showcase now expands that card into the demo
  ([#3599](https://github.com/sceneview/sceneview/issues/3599)).** The full-screen demo used
  to appear with the stock cover slide, with nothing tying it to the card the thumb had just
  hit. It uses the same iOS 18 zoom transition the Explore gallery already uses: the tapped
  `DemoMediaCard` — or the hero, when the demo is opened from it — is the transition source,
  and the demo collapses back into it on close.
