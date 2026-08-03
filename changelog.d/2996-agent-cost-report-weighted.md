<!-- category: Fixed -->
- `agent-cost-report.sh` now sees subagent transcripts. They live at
  `<slug>/<sessionId>/subagents/agent-*.jsonl`, not `<slug>/*.jsonl`, so the
  report globbed past them and printed no subagent line at all — measured
  2026-08-03, 643 subagent transcripts on disk, 22% of all requests, invisible.
- `agent-cost-report.sh` reports a **weighted** cost (cache read x0.1, cache
  write x1.25-2, output x5) instead of headlining raw output tokens. The old
  headline called output "the quota-binding number"; measured over 7 days across
  all projects, output is 11.7% of the bill and cache reads are 60.8%. The
  report now also prints the average context re-read per request — the quantity
  the cost actually scales with.
