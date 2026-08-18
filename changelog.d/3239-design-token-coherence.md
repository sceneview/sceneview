<!-- category: Fixed -->
<!-- breaking: false -->
`DESIGN.md` no longer contradicts itself on `radius-lg`, which it gave as 24px in
its token table and 28px in two prose sentences. The published token bundle had
taken 24px and the Android demo app's shape theme had taken 28, so the two
disagreed with each other through a source that agreed with neither. A new gate,
`check-design-token-coherence.py`, refuses any inline token value in `DESIGN.md`
that contradicts that token's table row.
