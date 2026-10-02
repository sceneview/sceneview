<!-- category: Fixed -->
- **iOS demo app: the tab bar stays open after a Featured demo ([#4266](https://github.com/sceneview/sceneview/pull/4266)).** On iOS 26 the tab bar folded away when Home scrolled into Featured and stayed folded after the demo closed, so the tabs looked gone. It now always stays open, and Home keeps its scroll position and filter.

<!-- category: Added -->
- **iOS demo app: see what's new on Home ([#4266](https://github.com/sceneview/sceneview/pull/4266)).** Each demo declares `// @addedIn` (required) and `// @updatedIn` in its Scene file. Home shows a "What's new" row under the hero and a "What's new" chip, and puts New / Updated badges above the titles of demos added or reworked in the last two minor versions, as on Android. Tapping the row selects the chip and scrolls the chips to just under the header; the filtered list and its scroll position are still there on return from a sample.
