#!/usr/bin/env bash
# The one runnable check: prove that a single PUT to the Contents API really is a
# commit + push, using the same body shape Gh.commit() sends from the app.
# Creates a scratch file, updates it, then deletes it. Needs `gh auth login`.
#
#   ./tools/check-contents-api.sh <owner>/<repo>
set -euo pipefail

REPO="${1:?usage: check-contents-api.sh <owner>/<repo>}"
FILE="octoquill-selftest.txt"
BRANCH="$(gh api "repos/$REPO" --jq .default_branch)"
b64() { printf '%s' "$1" | base64 | tr -d '\n'; }
head_sha() { gh api "repos/$REPO/commits/$BRANCH" --jq .sha; }

echo "repo=$REPO branch=$BRANCH"
before="$(head_sha)"

echo "-- create"
sha=$(gh api -X PUT "repos/$REPO/contents/$FILE" \
  -f message="Octoquill self-test: create" \
  -f content="$(b64 'hello from the contents api')" \
  -f branch="$BRANCH" --jq .content.sha)
[ -n "$sha" ] || { echo "FAIL: create returned no blob sha"; exit 1; }

echo "-- update (requires the previous blob sha, like the editor does)"
sha=$(gh api -X PUT "repos/$REPO/contents/$FILE" \
  -f message="Octoquill self-test: update" \
  -f content="$(b64 'edited on a phone')" \
  -f sha="$sha" -f branch="$BRANCH" --jq .content.sha)

echo "-- read back"
got=$(gh api "repos/$REPO/contents/$FILE?ref=$BRANCH" --jq .content | base64 -d)
[ "$got" = "edited on a phone" ] || { echo "FAIL: read back '$got'"; exit 1; }

echo "-- stale sha must be rejected (409, not a silent overwrite)"
if gh api -X PUT "repos/$REPO/contents/$FILE" \
     -f message="should fail" -f content="$(b64 'nope')" \
     -f sha="0000000000000000000000000000000000000000" -f branch="$BRANCH" >/dev/null 2>&1; then
  echo "FAIL: stale sha was accepted"; exit 1
fi

echo "-- cleanup"
gh api -X DELETE "repos/$REPO/contents/$FILE" \
  -f message="Octoquill self-test: cleanup" -f sha="$sha" -f branch="$BRANCH" >/dev/null

after="$(head_sha)"
[ "$before" != "$after" ] || { echo "FAIL: branch ref never moved"; exit 1; }
echo "OK: $before -> $after, 3 commits pushed by 3 API calls, no git involved"
