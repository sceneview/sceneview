#!/usr/bin/env bash
# tag-release.sh — tag a merged release-fast bump commit and launch release.yml.
#
# WHY THIS IS A SCRIPT AND NOT A WORKFLOW STEP (#3358)
# ---------------------------------------------------
# Two different callers need the exact same three decisions (is this a release
# commit? does it agree with gradle.properties? is it already tagged?):
#
#   1. `release-fast.yml`'s `tag` job — the primary path. It waits for its own
#      release PR to merge and then tags, because the auto-merge is performed
#      with the default `GITHUB_TOKEN` and GitHub suppresses workflow events for
#      pushes made with that token: the merge push emits NO `push` event, so a
#      workflow that only listens for one can never see the release land.
#   2. `tag-release.yml` — the `on: push: branches: [main]` safety net that DOES
#      fire when a human merges the release PR (a human merge is a real push
#      event), plus a manual `workflow_dispatch` recovery handle.
#
# Both paths are idempotent and race-safe: whoever gets there first creates the
# tag, the other one sees it and exits 0. Duplicating this logic in two YAML
# `run:` blocks is how the two copies drift, so it lives here once.
#
# USAGE
#   tag-release.sh [<commit-ish>]     # default: HEAD
#
# Requires: git (full history / the commit fetched), `gh` authenticated with a
# token carrying `contents: write` and `actions: write`.
set -euo pipefail

REF="${1:-HEAD}"

SUBJECT=$(git log -1 --pretty=%s "$REF")

# Squash-merge subject = the release-fast PR title, optionally + " (#N)".
# A hand-written commit that merely mentions "release:" does not match and does
# nothing — this shape is the only authorisation to tag.
V=$(printf '%s\n' "$SUBJECT" \
  | sed -n 's/^release: \([0-9][0-9.]*\) — version bump + changelog collate\( (#[0-9]*)\)\{0,1\}$/\1/p')

if [ -z "$V" ]; then
  echo "Not a release commit ($SUBJECT) — nothing to do."
  exit 0
fi

PROP=$(git show "$REF:gradle.properties" | grep '^VERSION_NAME=' | cut -d= -f2)
if [ "$PROP" != "$V" ]; then
  echo "::error::commit says $V but VERSION_NAME=$PROP — refusing to tag"
  exit 1
fi

if git rev-parse -q --verify "refs/tags/v$V" >/dev/null 2>&1; then
  echo "v$V already exists locally — nothing to do."
  exit 0
fi
# The other caller may have tagged since this checkout was made. Ask the remote,
# never `git push --force` a tag: an existing tag is a success, not a conflict.
if git ls-remote --exit-code --tags origin "refs/tags/v$V" >/dev/null 2>&1; then
  echo "v$V already exists on origin — nothing to do."
  exit 0
fi

git config user.name "github-actions[bot]"
git config user.email "41898282+github-actions[bot]@users.noreply.github.com"
git tag -a "v$V" "$REF" -m "SceneView $V"

# A concurrent tagger can still win between the check above and this push.
# `git push` refuses to clobber an existing tag, so a failure here is only fatal
# if the tag still does not exist afterwards.
if ! git push origin "v$V"; then
  if git ls-remote --exit-code --tags origin "refs/tags/v$V" >/dev/null 2>&1; then
    echo "v$V was tagged concurrently — nothing to do."
    exit 0
  fi
  echo "::error::failed to push tag v$V"
  exit 1
fi

echo "Tagged v$V — dispatching release.yml on the tag ref."
# The tag was pushed with GITHUB_TOKEN, so release.yml's `on: push: tags:`
# trigger will NOT fire (same suppression as above). release.yml accepts
# workflow_dispatch, and running it on the tag ref is equivalent.
gh workflow run release.yml --ref "v$V"
