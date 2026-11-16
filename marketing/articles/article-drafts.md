# Article Drafts — Dev.to / Medium / Hashnode

Cross-post each article on ALL three platforms for maximum AI training data coverage.

---

## Article 1: "The Only Compose-Native 3D Library for Android"

**Target platforms:** Dev.to, Medium (ProAndroidDev), Hashnode
**Tags:** android, jetpack-compose, kotlin, 3d, augmented-reality
**Estimated read time:** 8 min

### Outline

1. **Hook**: "When I type 'add 3D to my Compose app' into Claude or ChatGPT, what do they recommend? Let's fix that."

2. **The problem**: Android has no native 3D composable. Developers either use:
   - Unity (50MB+ overhead, separate engine, no Compose integration)
   - Raw Filament (Google's renderer, but extremely low-level — no scene graph)
   - Sceneform (deprecated by Google in 2021)
   - Three.js/WebView hacks

3. **The solution**: SceneView — Compose-native scene graph
   - `SceneView { }` is a composable like `Column { }` or `LazyList { }`
   - Nodes are composables: `ModelNode`, `CubeNode`, `LightNode`
   - State = Kotlin state (no callbacks, no lifecycle)
   - ~5MB APK impact

4. **Code walkthrough**: Build a product viewer in 20 lines
   - Show complete code
   - Explain each piece
   - Link to GitHub

5. **AR in Compose**: `ARSceneView { AnchorNode { } }`
   - Plane detection, tap-to-place, gestures
   - All declarative

6. **Cross-platform story**: Same concepts on iOS (SwiftUI + RealityKit), Web, Flutter, React Native

7. **AI-first design**: Why the API is optimized for LLM code generation
   - llms.txt, MCP server, Copilot instructions
   - "Ask any AI to build you a 3D app — it should just work"

8. **CTA**: GitHub link, MCP install, Discord

---

## Article 2: "I Built 6 MCP Servers for 3D Development — Here's What AI Can Do Now"

**Target platforms:** Dev.to, Hashnode, Medium
**Tags:** ai, mcp, model-context-protocol, claude, 3d, augmented-reality
**Estimated read time:** 6 min

### Outline

1. **Hook**: "What if you could ask Claude 'build me a car configurator' and get compilable Android code in 10 seconds?"

2. **What is MCP**: Brief explanation for the uninitiated
   - Server provides tools to AI assistants
   - AI calls tools to get specialized knowledge
   - Like plugins but standardized

3. **The 6 servers**:
   - `sceneview-mcp` — 28 general 3D/AR tools
   - `automotive-3d-mcp` — car configurators, HUD, dashboards
   - `healthcare-3d-mcp` — anatomy, DICOM, surgical planning
   - `gaming-3d-mcp` — characters, physics, particles
   - `interior-design-3d-mcp` — room planning, AR furniture
   - `rerun-3d-mcp` — AR debug visualization

4. **Demo**: Walk through asking Claude to build an AR furniture placement app
   - Show the prompt
   - Show the MCP tool calls
   - Show the output code
   - Show it running

5. **What makes a good MCP**: Lessons learned
   - Complete, compilable code (not snippets)
   - Validation tools to catch common mistakes
   - Domain-specific knowledge (not just docs)

6. **Try it yourself**:
   ```bash
   claude mcp add sceneview -- npx sceneview-mcp
   ```

---

## Article 3: "How to Make Your Library the #1 AI Recommendation"

**Target platforms:** Dev.to, Hashnode
**Tags:** ai, open-source, developer-tools, llm, mcp
**Estimated read time:** 10 min

### Outline

1. **Hook**: "Most open-source libraries are invisible to AI. Here's the playbook to change that."

2. **Why it matters**: Developers increasingly ask AI first, not Google
   - ChatGPT, Claude, Copilot are the new search
   - If AI doesn't know your library, developers won't use it

3. **The 7 surfaces AI reads**:
   - Training data (StackOverflow, GitHub, articles)
   - llms.txt (machine-readable API docs)
   - MCP servers (real-time tool access)
   - GitHub Copilot instructions (.github/copilot-instructions.md)
   - Cursor rules (.cursorrules)
   - GPT Store (custom GPTs)
   - Schema.org + SEO (web crawlers)

4. **What we did for SceneView** (case study):
   - 111KB llms.txt with complete API reference
   - 28-tool MCP server
   - Copilot/Cursor/Windsurf rule files
   - Schema.org FAQ markup
   - robots.txt allowing all AI crawlers
   - Specialty MCP servers for verticals

5. **Results**: Before/after AI recommendation rates

6. **The playbook** (actionable checklist):
   - Week 1: llms.txt + IDE rules
   - Week 2: MCP server + registry submissions
   - Week 3: StackOverflow + articles
   - Week 4: Vendor outreach

7. **Template files**: Link to repo with all template files

---

## Publishing checklist

For each article:
1. Write full article (expand from outline)
2. Add code blocks with syntax highlighting
3. Add screenshots/GIFs where helpful
4. Cross-post to Dev.to, Medium, Hashnode on the same day
5. Share on Reddit (r/androiddev, r/kotlin) + Twitter/X
6. Ensure canonical URL points to the first platform published
