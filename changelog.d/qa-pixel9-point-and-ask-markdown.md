<!-- category: Fixed -->
- Point & Ask demo (Android): the streamed answer rendered Markdown emphasis literally (users saw `**bold**` with the asterisks). Added a tiny dependency-free `renderMarkdownLite` (bold `**..**`, italic `*..*` / `_.._`, single left-to-right pass) that is streaming-safe — an unclosed marker mid-stream is rendered as a literal character.
