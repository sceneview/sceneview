## Summary
<!-- Short description of the change -->

## Type
<!-- Check one -->
- [ ] Bug fix
- [ ] New feature
- [ ] Refactor / cleanup
- [ ] Documentation
- [ ] Build / CI
- [ ] Other

## Testing done
<!-- How was this tested? Device/emulator, test cases, manual verification -->

## Checklist

- [ ] `/review` passed — no threading, Compose API, or style issues
- [ ] `/document` run — KDoc updated for changed public APIs, `llms.txt` updated if signatures changed
- [ ] `/review --coverage` run — new tests added for new behaviour
- [ ] `./gradlew :sceneview:assembleDebug :arsceneview:assembleDebug` passes
- [ ] Filament materials recompiled (if `.mat` files changed)
- [ ] Minimal diff — no unrelated reformatting

> **AI-assisted contributions welcome.**
> Run `/contribute` in Claude Code for a guided workflow that handles review, docs, and test generation automatically.
> MCP server: `npx -y sceneview-mcp` — gives Claude full SceneView API context.
