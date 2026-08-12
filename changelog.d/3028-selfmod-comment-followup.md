<!-- category: Tests -->
The "NOT REVIEWED" comment a self-modifying PR gets (#3028) no longer writes its
scratch file into the checkout, where `Assert the reviewers left the tree clean`
would blame it on a reviewer, and its marker-comment lookup no longer depends on
`gh api --paginate --jq` returning a single id. `test-selfmod-guard.sh` covers
the update-in-place branch that had no test at all.
