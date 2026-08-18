#!/usr/bin/env bash
# The one runnable check: prove that a single PUT to the Contents API really is a
# commit + push, and that the two conflict statuses the app's recovery dialog keys on
# (409 stale sha, 422 missing sha) are what GitHub actually returns.
# Creates a scratch file, updates it, then deletes it. Needs `gh auth login`.
#
#   ./tools/check-contents-api.sh <owner>/<repo>
set -euo pipefail

REPO="${1:?usage: check-contents-api.sh <owner>/<repo>}"
FILE="octoquill-selftest.txt"
BRANCH="$(gh api "repos/$REPO" --jq .default_branch)"
b64() { printf '%s' "$1" | base64 | tr -d '\n'; }
head_sha() { gh api "repos/$REPO/commits/$BRANCH" --jq .sha; }
# these calls are meant to fail, so shield them from set -e / pipefail
status() { { gh api "$@" -i 2>/dev/null || true; } | head -1 | grep -oE '[0-9]{3}' | head -1 || true; }

echo "repo=$REPO branch=$BRANCH"

# an earlier aborted run may have left the scratch file behind; start from nothing
old=$(gh api "repos/$REPO/contents/$FILE?ref=$BRANCH" --jq .sha 2>/dev/null || true)
if [ -n "$old" ]; then
  echo "-- removing leftover $FILE"
  gh api -X DELETE "repos/$REPO/contents/$FILE"     -f message="Octoquill self-test: clear leftover" -f sha="$old" -f branch="$BRANCH" >/dev/null
fi

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

# Vm.push() only shows the "Changed on GitHub" recovery dialog for these two codes.
# If GitHub ever changes them, the app degrades to a raw error toast and the writer
# loses the overwrite option — so assert them exactly.
echo "-- stale sha must be 409 (someone committed since we opened the file)"
code=$(status -X PUT "repos/$REPO/contents/$FILE" -f message=x \
  -f content="$(b64 'nope')" -f sha=0000000000000000000000000000000000000000 -f branch="$BRANCH")
[ "$code" = "409" ] || { echo "FAIL: stale sha gave $code, expected 409"; exit 1; }

echo "-- missing sha on an existing file must be 422 (new-file path onto a live file)"
code=$(status -X PUT "repos/$REPO/contents/$FILE" -f message=x \
  -f content="$(b64 'nope')" -f branch="$BRANCH")
[ "$code" = "422" ] || { echo "FAIL: missing sha gave $code, expected 422"; exit 1; }

echo "-- cleanup"
gh api -X DELETE "repos/$REPO/contents/$FILE" \
  -f message="Octoquill self-test: cleanup" -f sha="$sha" -f branch="$BRANCH" >/dev/null

after="$(head_sha)"
[ "$before" != "$after" ] || { echo "FAIL: branch ref never moved"; exit 1; }
echo "OK: $before -> $after, commits pushed by API calls alone, conflict codes 409/422 confirmed"
