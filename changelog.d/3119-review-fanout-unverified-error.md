<!-- category: Fixed -->
`review-fanout` no longer recommends MERGE when an ERROR finding got no verdict. A
verifier agent that dies (reachable via an exhausted quota on its pinned model) used to
have its finding silently dropped, taking `confirmedErrors` to zero and clearing the
auto-merge gate on a change whose blocking findings were never checked. Such a finding is
now kept and marked unverified, which routes the run to `REVIEW_INCOMPLETE`.
