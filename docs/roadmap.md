# Roadmap

## Shipped

- Commit and push from the phone (Contents API, no git)
- Offline writing: commit queue that drains itself when signal returns
- Offline reading: whole-repo pre-sync, cached listings and files, cold start with no network
- Draft persistence across process death
- Section editing for long documents, with a tested byte-exact splice
- Conflict detection and resolution when the same file is edited in two places
- Markdown preview, live word count
- New / rename / delete, branch switching, per-file history
- Media hiding
- Home screen of added repos, each with its own token; no sign-out, and a refused token
  only flags the repo - its queue waits
- Instant start: the home screen needs no network, and a repo paints from disk first
- Known dead spots answer from disk instead of waiting out another timeout
- Conflict diffs: GitHub's version against yours before Keep mine / Keep theirs
- Search across the repo, over what is saved on the phone, so it works offline

## Next

**Windows desktop build.** The long-term goal. Most of the app is already portable — the
plan is Compose Multiplatform, sharing everything except a handful of Android calls.

| Portable as-is | Android-bound, needs `expect`/`actual` |
|---|---|
| `Api.kt` (ktor + kotlinx.serialization) | `android.util.Base64` -> `java.util.Base64` |
| `Sections.kt`, `Markdown.kt` | `SharedPreferences` -> a properties file |
| `Vm` state and logic | `ConnectivityManager` -> a reachability poll |
| `Screens.kt` / `Editor.kt` (Compose) | `AndroidViewModel` -> a plain class + `CoroutineScope` |

Roughly four seams. The outbox and cache are already plain files under one directory, which
is the part that would normally hurt. A desktop build also removes the reason for section
editing — a desktop text field handles a whole manuscript — so the outline becomes
navigation rather than a workaround.

Worth deciding early: whether desktop and phone should share one on-disk format so the same
queue can be inspected from either.

**Multi-file commits.** Needs the Git Data API: create blobs, build a tree, create a commit,
move the ref. Four calls instead of one, and real value only when a single change spans
files. The outbox would need to hold a set of paths rather than one.

## Later

- **Syntax highlighting** for code files. Markdown preview covers the writing case, so this
  is only for the times you edit a config file from your phone.
- **Diffs, pull requests, issues.** Standard GitHub client territory, and none of it is why
  this app exists.
- **Attachments.** Committing a photo from the phone is a `PUT` with base64 like any other
  file, capped at the API's 1MB inline limit. Larger needs the Git Data API.

## Not planned

- **A full git implementation.** The whole design is a bet against it, and everything above
  stays cheap because of that bet.
- **Merge resolution.** If two versions of a piece of prose genuinely need merging, that is a
  laptop job.
