<!-- category: Fixed -->
<!-- breaking: false -->
`codex-delegate.sh --new-worktree` now provisions a safe `local.properties` in the
worktree it creates, so a delegated agent can configure an Android build without anyone
copying the developer's own file across. Only `sdk.dir` is carried over; every other key
keeps its name and loses its value, which is what Gradle needs to configure and what a
secret must never be given.
