<!-- category: Changed -->

- **iOS demo: native Liquid Glass on iOS 26 ([#3862](https://github.com/sceneview/sceneview/pull/3862)).**
  - The floating chrome over the 3D stage uses the system `glassEffect` and keeps its 1 pt border so it stays visible over dark scenes. This covers the dock, the option strip, the title pill and the circle buttons.
  - The dock accent is a `primary`-tinted `.glassProminent` button.
  - The settings and "coming soon" sheets are glass on their partial detents, so the scene stays visible behind them.
  - The tab bar uses the `Tab` API and minimizes on scroll down.
  - Dock, option-strip, accent and Reset haptics use SwiftUI `.sensoryFeedback`.
  - Below iOS 26 the demo keeps the material stack and themed sheets.
