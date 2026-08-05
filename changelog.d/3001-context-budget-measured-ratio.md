<!-- category: Fixed -->

- `context-budget.sh` reported the standing session context at ~4 chars/token, a
  plain-English default that understated it by ~35% for markdown full of tables,
  paths and emoji. The ratio is now ~2.7, derived from two natural experiments in
  the local transcripts, and the report gained the two items it never counted:
  the user-level `CLAUDE.md` and the one-line skill/command/workflow descriptions
  that ship in every preamble whether or not a body is ever opened. `STATE.md` and
  `workflows/README.md` moved to a separate "read at bootstrap" block — they are
  not in the preamble, and counting them as standing cost is what kept sending
  each pass back to cut the same file ([#3001](https://github.com/sceneview/sceneview/issues/3001)).
