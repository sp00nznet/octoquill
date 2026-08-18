# Design

Everything here follows from one goal: **write prose into a git repo from a phone, in a
place with no signal, and never lose a word.**

## No git

Most Android GitHub clients stop at browsing, because "commit and push from a phone" sounds
like it needs JGit, a local clone, a working tree and transport credentials. It doesn't.

```
PUT /repos/{owner}/{repo}/contents/{path}
{ "message": ..., "content": <base64>, "sha": <blob being replaced>, "branch": ... }
```

GitHub does the blob, the tree, the commit and the ref move server-side. `Gh.commit()` in
`Api.kt` is the whole feature. `DELETE` on the same endpoint removes a file, also as one
commit. Rename is create-then-delete, so it lands as two commits — one commit would mean
hand-building a tree through the Git Data API for something that happens rarely.

**Cost:** one file per commit. For prose that is exactly right — you work on one piece and
commit it. Multi-file commits are on the roadmap and need a different API.

## The outbox

Every commit is written to `files/outbox/` first and only then sent.

```
commit()  ->  outbox.put(Pending)  ->  drain()
```

`drain()` walks the queue oldest-first and `PUT`s each entry. It stops at the first network
failure, because if there is no signal for one commit there is none for the next. A
`ConnectivityManager.NetworkCallback` calls it again when a network appears, so a commit
written in a dead spot lands by itself.

Entries are keyed by `repo + branch + path`, so committing the same file twice replaces the
queued version instead of stacking intermediate states. For writing that is what you want:
the last thing you wrote is the thing that should land.

Consequences worth knowing:

- **Nothing is ever "lost to a failed commit."** A failure is just a commit that has not
  gone yet, visible on the Pending screen with a word count.
- **Opening a file prefers the queued version** over what GitHub last served, so you see
  your own work rather than a stale copy.
- **A conflicted entry stays put.** See below.

## Three stores on disk

All three are the same tiny keyed-blob class in `Store.kt`, differing only in what they hold.

| | Holds | Cleared when |
|---|---|---|
| `drafts/` | The editor buffer, whole-file, debounced 600ms and flushed on `onStop()` | The commit is queued |
| `cache/` | The last JSON GitHub served for a listing or a file, plus the repo list | Never (no expiry, no cap) |
| `outbox/` | Queued commits with their base sha and message | The commit lands |

Drafts hold the **whole file** even when you were editing one section, so there is exactly
one draft per file however you reached it. They deliberately survive sign-out — signing out
must never eat writing that has not landed.

## Offline

Three separate things have to work with no network, and each is handled differently:

1. **Getting in.** The repo list is cached whole, so a cold start with no signal still lands
   you in your home repo rather than an empty screen or a sign-in form. Only a `401` signs
   you out; a dropped request keeps the session.
2. **Reading.** Listings and file bodies come from the cache when the network fails. A
   response *with* an HTTP status is a real answer and is never masked by the cache — a 404
   stays a 404.
3. **Writing.** The outbox, above.

Reading only covers files you have actually fetched, which is no use if the plan is to go
somewhere remote and write. **Save repo for offline** walks the whole tree and caches every
text file under the API's 1MB inline limit, skipping media.

## Long documents

A phone text field cannot hold a book. Open a markdown file with 12+ headings, or over 64KB,
and Octoquill shows the outline instead. Pick a heading and you edit only that slice.

`Sections.kt` is pure Kotlin with no Compose imports, so it is unit-tested properly:

- headings are found fence-aware, so a `#` comment inside a ``` block is not a heading
- sections **tile** the document — no gaps, no overlaps, concatenating them reproduces it
- `splice(full, section, newText)` preserves everything outside the slice byte-for-byte

That last property is the one that matters. A bug there would silently mangle a manuscript,
which is why it is a pure function with tests rather than arithmetic inlined in a ViewModel.

## Editing in two places

The `sha` parameter is a compare-and-swap: GitHub refuses a commit built on a blob that has
moved rather than clobbering it.

| Situation | Status | What happens |
|---|---|---|
| File changed on GitHub since you opened it | `409` | Entry marked conflicted, stays queued |
| New-file path onto a file that already exists | `422` | Same |

A conflicted entry is never retried automatically. It waits on the Pending screen with
**Overwrite** (re-fetch the current sha and commit on top — the other version stays in
history) or **Discard**. Your text is on disk the whole time, so the decision is never urgent.

Both status codes are asserted in `tools/check-contents-api.sh`, because the recovery UI
keys on them exactly.

## Deliberate omissions

Marked in the source with `ponytail:` comments.

- **No R8.** Turn it on with ktor/kotlinx keep rules when APK size matters.
- **No cache expiry or size cap.** A writing repo is a few hundred KB of text.
- **Token in plain app-private prefs**, `allowBackup=false`. Android's file-based encryption
  already covers it at rest.
- **Repo list stops at 1000.** Ten pages of 100.
- **Drafts write the whole file each time.** Fine for prose; revisit for megabyte files.
- **A small markdown subset** for preview. A real CommonMark parser is a dependency and a lot
  of surface for a read-only pane.
