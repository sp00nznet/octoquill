# Setup

## Build requirements

- JDK 17 or newer
- Android SDK with API 36 platform and build-tools
- `local.properties` pointing at the SDK (`sdk.dir=...`), not committed

```bash
./gradlew :app:assembleDebug          # app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:testDebugUnitTest      # the section-splice tests
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The debug APK is debug-signed, so Android will warn about installing from an unknown source.

## Signing in

Two ways in, neither of which needs a server of your own.

### Personal access token (zero setup)

Paste a token with the `repo` scope. Works the moment the app builds.

<p align="center"><img src="screenshots/01-sign-in.png" width="230"><br><sub><i>With no OAuth app configured, this is the whole sign-in screen.</i></sub></p>

Prefer a **fine-grained** token scoped to the one repo you actually write in, rather than a
classic `repo` token. A classic token on a lost phone reads every private repo you own.

The app stores it in app-private `SharedPreferences` with `allowBackup=false`.

### Sign in with GitHub (OAuth device flow)

Nicer on a phone: the app shows a code, you type it at `github.com/login/device`. No
redirect URI, no client secret, no backend. It needs a one-time OAuth App on your account:

1. <https://github.com/settings/developers> -> **New OAuth App**
2. Name it. Homepage and callback URL can be anything — device flow never uses the callback.
3. On the created app, tick **Enable Device Flow** and save.
4. Put the client ID in `gradle.properties`:

   ```
   octoquill.clientId=Ov23li...
   ```

Leave that blank and the app shows only the token field.

## First run

1. Sign in.
2. **Star** the repo you write in. That makes it home, and the app opens straight into it on
   every later launch — including with no signal.

<p align="center"><img src="screenshots/02-repos.png" width="230"><br><sub><i>The star sets your home repo.</i></sub></p>
3. Open the repo and tap **Save repo for offline** to pull every text file down.

<p align="center"><img src="screenshots/03-browse.png" width="230"><br><sub><i>Images and binaries are hidden by default; one tap shows them.</i></sub></p>

Do step 3 before you go anywhere without coverage. Without it you can only read the files
you happened to open.

## Where things live on the device

Useful when something looks wrong:

```bash
adb shell run-as net.sp00nz.octoquill ls files/drafts    # uncommitted editor buffers
adb shell run-as net.sp00nz.octoquill ls files/outbox    # commits waiting for signal
adb shell run-as net.sp00nz.octoquill ls files/cache     # last-synced listings and files
```

Filenames are readable on purpose. Nothing here is deleted by signing out.
