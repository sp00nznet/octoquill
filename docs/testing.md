# Testing

Three layers, in order of how much they would hurt to get wrong.

## 1. The splice (unit tests)

```bash
./gradlew :app:testDebugUnitTest
```

`Sections.kt` is pure Kotlin with no Compose imports specifically so this can run on the JVM.
Eight tests in `SectionsTest.kt` cover the one operation that could silently destroy work:

- headings found fence-aware (a `#` inside a ``` block is not a heading)
- sections **tile** the document — concatenating every slice reproduces the original exactly
- splicing a slice back unchanged is a no-op, for every section in the document
- editing one section leaves line count, heading count and both neighbours untouched

## 2. The API contract (live)

```bash
./tools/check-contents-api.sh <owner>/<scratch-repo>
```

Creates a file, updates it, reads it back, deletes it, and asserts the branch ref moved —
proving one `PUT` really is a commit and a push.

It also pins the two conflict statuses the recovery UI keys on:

| Case | Expected |
|---|---|
| Stale blob sha | `409` |
| No sha on a file that exists | `422` |

If GitHub ever changed those, the app would degrade to a raw error toast and the writer would
lose the Overwrite option, so the script fails loudly rather than warning.

Idempotent — it clears its own leftovers first. **Use a scratch repo**: it commits to the
default branch.

## 3. The offline path (manual, on a device)

The part no unit test can prove. Run against a throwaway repo:

```bash
adb shell cmd connectivity airplane-mode enable      # lose signal
adb shell cmd connectivity airplane-mode disable     # get it back
```

The sequence that must pass:

1. **Online:** open the repo, tap *Save repo for offline*. Expect "N files available offline".
2. **Airplane mode on.** Force-stop and relaunch the app.
3. It should land **in your home repo's file list**, not on a sign-in screen or an empty
   list, with a "No signal" strip.
4. Open a file that you never opened individually — the pre-sync should have it.
5. Edit it. The commit dialog should say the commit will push itself later, and the button
   should read **Commit**, not *Commit & push*.
6. Commit two different files. The strip should read "2 commit(s) saved".
7. Check `adb shell run-as net.sp00nz.octoquill ls files/outbox` — two entries.
8. **Airplane mode off. Touch nothing.** Both commits should appear on GitHub within seconds,
   in the order they were written, and the outbox should empty itself.

Also worth re-checking after changes to the editor:

- Type, background the app, force-stop it, reopen the file — the *Unsaved writing found*
  dialog should offer back what you typed.
- Open a file on the phone, commit to the same file from a laptop, then commit on the phone —
  you should get a conflicted entry on the Pending screen, not a lost edit.

## Driving the UI from a script

The screenshots and the offline runs were done with `uiautomator` dumps over adb. Two traps
worth writing down, both of which produced confusing wrong results:

- A failed `uiautomator dump` leaves the **previous** XML on disk, so the next read returns
  stale UI. Delete the file before each dump.
- `xml.etree` `Element.__bool__` is `False` when the element has no children, so
  `find(a) or find(b)` silently discards a valid hit. Compare with `is not None`.
