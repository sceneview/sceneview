<!-- category: Fixed -->
- `pr-review.yml`'s missing-verdict diagnostic now also prints the orchestrator's closing
  message. Zero denials and no verdict file is a *different* failure from refused tools, and
  the denial count cannot explain it: measured on run 30800617868, the fan-out reported 0
  denials, ran 11 turns in 60s — far too few for four reviewers — and wrote nothing. Bounded
  to 1500 chars of the agent's own one-paragraph summary, which the prompt already requires.
