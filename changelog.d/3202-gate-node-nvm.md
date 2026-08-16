<!-- category: Fixed -->
The pre-push gate now finds a node installed by nvm, instead of announcing
`node not found` and skipping its two JS legs on a host where node works. The
gate ran from a non-interactive shell, which never loads `nvm.sh`, so `which
node` returned nothing while node was installed — measured four times in a day.
Resolution order is `$NODE_CMD` → PATH → Homebrew → nvm's default alias → its
newest install, version-sorted so `v9` cannot outrank `v22`.
