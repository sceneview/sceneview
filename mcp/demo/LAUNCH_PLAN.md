# SceneView MCP — Launch Plan

## Pre-launch checklist

- [ ] `npm run prepare` builds cleanly
- [ ] Test with MCP inspector: `npx @modelcontextprotocol/inspector node dist/index.js`
- [ ] Verify all 5 samples return valid Kotlin
- [ ] Verify `get_setup("3d")` and `get_setup("ar")` return correct snippets
- [ ] Test in Claude Code: `claude mcp add --scope project sceneview -- npx -y sceneview-mcp` (writes `.mcp.json` at the project root), confirm `claude mcp list` shows `✔ Connected`, run `/mcp`, ask for an AR app
- [ ] Test in Claude Desktop: add config, restart, ask for a 3D model viewer
- [ ] Record demo video (see storyboard.md)

## Publish sequence

### Step 1 — npm publish
```bash
cd mcp
npm run prepare
npm publish --access public
```
Verify at https://www.npmjs.com/package/sceneview-mcp

### Step 2 — GitHub release
```bash
gh release create mcp-v3.0.0 \
  --title "sceneview-mcp v3.0.0" \
  --notes-file mcp/demo/RELEASE_NOTES.md \
  --target main
```

### Step 3 — Upload demo video
Upload to YouTube with the description from `narration.md`.

---

## Launch day timeline

### Hour 0 — Publish
- npm publish
- GitHub release
- Push README changes to main

### Hour 1 — Twitter/X thread
Post the 3-tweet thread from `narration.md`. Pin the first tweet.

Timing: weekday, 9–10 AM PST (peak Android dev audience).

### Hour 2 — Communities

**Reddit** (pick 2–3):
- r/androiddev — "I built an MCP server that gives Claude the full SceneView 3D/AR SDK. Ask it to build an AR app and the Kotlin actually compiles."
- r/ClaudeAI — "sceneview-mcp: one config line and Claude can build working 3D/AR apps for Android and iOS"
- r/MachineLearning — only if you have the demo video, otherwise skip

**Discord:**
- SceneView Discord — announcement from `narration.md`
- Kotlin Slack #android — short mention + npm link
- Claude / MCP Discord (if exists) — "New MCP server for cross-platform 3D/AR development (Android + iOS)"

**Hacker News:**
- "Show HN: sceneview-mcp — MCP server that lets Claude build 3D/AR apps for Android and iOS"
- Link to GitHub, not npm
- Best time: 6–8 AM PST on a weekday

### Hour 3–4 — Dev.to / Medium post (optional)
Expand the narration into a short blog post. Structure:

1. **Hook** — "What if you could build an AR app by describing it?"
2. **The problem** — LLMs hallucinate APIs for new SDKs
3. **The solution** — MCP gives Claude the real docs + working samples
4. **Demo** — embed YouTube video
5. **How it works** — resource + 2 tools, 30 second setup
6. **Try it** — `npx sceneview-mcp` + config JSON
7. **What's next** — more scenarios, cursor support, etc.

---

## Messaging cheat sheet

**One-liner:**
> MCP server that gives Claude the full SceneView 3D/AR SDK. One config line → ask for an AR app → compilable Kotlin.

**The pitch (2 sentences):**
> SceneView 3.0.0 has a brand-new Jetpack Compose API that no LLM has in training data. sceneview-mcp fixes that — Claude reads the real API and writes correct Kotlin, first try.

**Hashtags:**
`#AndroidDev #AR #ARCore #JetpackCompose #Claude #MCP #SceneView #AI`

---

## Metrics to track

- npm weekly downloads (check at npmjs.com after 7 days)
- GitHub stars on sceneview (track delta)
- GitHub issues mentioning MCP
- Tweet impressions / engagement
- Reddit upvotes
- YouTube views on demo video

---

## Follow-up ideas (week 2+)

- Add more scenarios: AR cloud anchors, point cloud visualization, multi-model scene
- Cursor IDE support (if MCP support ships)
- Blog post: "How to build an MCP server for your SDK"
- Submit to MCP server directory / awesome-mcp lists
- Reach out to Android dev YouTubers for coverage
