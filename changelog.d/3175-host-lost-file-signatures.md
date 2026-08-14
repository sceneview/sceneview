<!-- category: Fixed -->
Gradle gates now name the host when a cached file goes missing, instead of blaming the
code. Three failure shapes — a hollow wrapper distribution, an artefact evicted from
`caches/modules-2`, and a worktree-local configuration cache pointing at an evicted
transform — are one event: the file is gone but the metadata indexing it survived, so
Gradle reports a dependency, API or plugin problem. Each is now classified as a host
setup failure with its own remedy, and the remedies are kept distinct because one of the
three is worktree-local and must never send anyone into the shared `~/.gradle`.
