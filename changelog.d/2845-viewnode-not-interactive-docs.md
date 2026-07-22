- **Docs:** `ViewNode` now documents that its rendered view is **not interactive** — the hosting
  window is `FLAG_NOT_TOUCHABLE` and no touch is dispatched into it, so an embedded
  `Button.onClick` never fires. KDoc and `llms.txt` both show the supported alternative
  (pick the node from the scene via `onSingleTapUp`), so an AI reading the docs stops
  generating clickable-button-in-3D samples that silently do nothing. (#2845)
- **Docs:** removed the three surfaces that asserted the opposite. `README.md` described
  `ViewNode` as "buttons, lists, animations, all interactive" in two feature tables, and
  `docs/docs/nodes.md` recommended it for "interactive panels" — all three now state the
  render-only reality and point at the hit-test alternative. `llms.txt`'s demo index no
  longer calls the picking sample an "interactive ViewNode overlay". (#2845)
<!-- category: Docs -->
