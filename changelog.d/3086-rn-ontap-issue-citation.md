<!-- category: Docs -->
<!-- breaking: false -->
<!-- Pure citation fix — no API, no behaviour. -->
- The React Native `onTap` "iOS is unverified" caveat pointed readers at
  [#3072], which tracks moving the module from SwiftPM to the root podspec — a
  different problem. The measurement itself now has its own issue, [#3086], and
  the caveat cites it on every surface that carries it: `llms.txt`, its
  `website-static/.well-known/` mirror, the regenerated `gpt/knowledge-*`, the
  React Native quickstart, the plugin README, `src/index.tsx` (with the
  `bob`-generated `.d.ts`), the MCP server's RN setup guide, and the demo app's
  Explore-tab help text and README bridge-status table. The #3072 citations in
  the plugin README's iOS section and in `react-native-sceneview.podspec` are
  about the podspec gap and are correct; they stay.

[#3072]: https://github.com/sceneview/sceneview/issues/3072
[#3086]: https://github.com/sceneview/sceneview/issues/3086
