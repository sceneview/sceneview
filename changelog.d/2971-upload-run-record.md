<!-- category: Fixed -->
- `pr-review.yml` now uploads the fan-out's run record as an artifact (7 days) when a review
  produced no `review-verdict.json`. #2971's description announced this upload; its diff did
  not contain it, and the claim reached the merge commit — implementing it is the honest way
  to settle that. The argument it was merged on holds: the record is the only place the
  refused tool names and the full turn sequence live, the diagnostic step can print only a
  bounded excerpt, and the file dies with the runner. It is uploaded *only* on a failed
  review — a healthy one has nothing to explain, and the record carries the whole reviewer
  conversation.
