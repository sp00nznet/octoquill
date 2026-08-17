# Octoquill

An Android GitHub client you can actually **edit files in**. Sign in, browse your repos,
open a file, change it, write a commit message, push — all from the phone.

## Why this one edits files

Most Android GitHub clients stop at browsing because "commit and push from a phone"
sounds like it needs a full git implementation (JGit, a local clone, a working tree,
credentials for the transport). It doesn't.

GitHub's **Contents API** does the whole thing server-side in one request:

```
PUT /repos/{owner}/{repo}/contents/{path}
{ "message": "...", "content": "<base64>", "sha": "<blob being replaced>", "branch": "main" }
```

That single call writes the blob, builds the tree, creates the commit, and moves the
branch ref. There is no clone, no push, no merge — the commit lands on the remote
directly. `Gh.commit()` in `Api.kt` is the entire "push from phone" feature.

Trade-off: one file per commit, and no offline editing. Both are fine for the thing this
app is for — fixing a typo, tweaking a config, jotting a note — and neither is worth
dragging JGit onto a phone for.

## Auth

Two ways in, both without a backend server:

1. **Sign in with GitHub** (OAuth device flow) — the app shows a code, you enter it at
   `github.com/login/device`. No redirect URI, no client secret. Needs a one-time OAuth
   App on your account; see setup below.
2. **Personal access token** — paste a `repo`-scoped token. Works with zero setup.

The token is stored in app-private `SharedPreferences` with `allowBackup=false`.

### Enabling device-flow sign-in

1. https://github.com/settings/developers → **New OAuth App**
2. Name: `Octoquill`, Homepage URL: anything (`https://github.com/sp00nznet/octoquill`),
   Authorization callback URL: anything (device flow never uses it)
3. On the created app, tick **Enable Device Flow** and save
4. Copy the Client ID into `gradle.properties`:
   ```
   octoquill.clientId=Ov23li...
   ```

Leave it blank and the app just shows the token field.

## Build

```
./gradlew :app:assembleDebug
# app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Requires JDK 17+ and an Android SDK with API 36. `local.properties` points at the SDK.

## Checking the API contract

The one piece worth verifying outside the app is that the Contents API request shape
really produces a commit. `tools/check-contents-api.sh` does exactly that against a
scratch repo using the same JSON body the app sends:

```
./tools/check-contents-api.sh <owner>/<repo>
```

## Layout

| File | What's in it |
|---|---|
| `Api.kt` | GitHub REST calls + OAuth device flow. All the network code. |
| `MainActivity.kt` | `Vm` — the whole app state, a screen back-stack, and the actions. |
| `Screens.kt` | Compose UI: login, repo list, file browser, editor, dialogs. |

Three files. No DI framework, no navigation library, no repository layer, no git library.

## Not built yet

- Multi-file commits (needs the Git Data API: blobs → tree → commit → ref)
- Diffs, PRs, issues, history
- Syntax highlighting
- Offline / drafts
- File rename and delete (the Contents API supports delete; nothing calls it yet)
