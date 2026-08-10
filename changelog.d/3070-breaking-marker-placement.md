<!-- category: Fixed -->
<!-- breaking: false -->
- The release guard now reads a `<!-- breaking -->` marker wherever it appears on
  a line, including trailing a bullet. Anchored to a whole line, a marker written
  next to its bullet was silently discarded and the fragment shipped unflagged.
