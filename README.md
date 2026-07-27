# Yo Android

Yo Android is a native Android reimplementation of the original Yo app (2014): one tap sends the
word “Yo” to a chosen friend as a push notification, with the same small set of extensions the real
app grew — links, hashtags, one-shot location, groups, and photos — plus a public broadcast API for
third-party clients. Sends are delivered over FCM and mirrored to local Room history.

The app is deliberately small. Before adding anything, read **[docs/PRD.md](docs/PRD.md)** — it
records what the original product actually was, which features were intentionally left out and why,
and the known gaps in the current build. For shipping it, read
**[docs/RELEASE.md](docs/RELEASE.md)**.

Package name `hr.theshop.yo`, `minSdk` 24, `targetSdk` 36.

## Accounts

Sign up with a username and a password on first launch — the same two fields the original asked for,
and nothing else. Usernames are uppercase, 2–32 characters of `A–Z`, `0–9` or `_`.

Your friend list starts empty. Add people by username from **ADD FRIEND** in the menu; adding is
unilateral, as it was in Yo, and **BLOCK** rather than approval is the control on unwanted Yos. A
blocked sender still sees an ordinary "delivered", so blocking never notifies the person blocked.

The backend issues a bearer token per device at sign-in. No shared API key is compiled into the
app, so extracting the APK yields no credentials.

**Sign in with Google** is offered alongside, never instead. Tapping `CONTINUE WITH GOOGLE` opens
the device's account picker — every Google account on the phone, every time, so you choose which
one rather than the app latching onto the first. One Yo account is signed in at a time; to use
another, sign out. The first time a Google account is used it is asked to pick a Yo username, since
Google supplies an email and friends are addressed by username; after that it is one tap.

Working end to end on a physical device as of 2026-07-26 (PRD §7.1). Note that Google Sign-In needs
**two** OAuth clients in one project — a Web client *and* an Android client for your package and
signing SHA-1. With only the Web client the account picker never appears at all; see gap G13.

The band only appears when the build carries an OAuth client id. The operator's build has one — see
"Google sign-in" below, and PRD §7.1 for the provisioned values.

## Build

Needs the Android SDK with **API 36** installed, plus two JDKs:

- **JDK 17** builds the app.
- **JDK 21** runs the unit tests. Robolectric will not create a sandbox for Android SDK 36 on
  anything lower (`Android SDK 36 requires Java 21`). Gradle resolves it as a toolchain, so no
  machine path is committed; if auto-detection misses your JDK - Homebrew's `openjdk@21` is
  keg-only and invisible to `/usr/libexec/java_home` - list it in `~/.gradle/gradle.properties`:

  ```properties
  org.gradle.java.installations.paths=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
  ```

```sh
./gradlew assembleDebug
```

## Configuring a build

There are **no defaults**. A missing value fails any task that produces an APK or a bundle,
naming what is absent. That is deliberate: the backend URL used to fall back to the emulator
loopback, so a misconfigured build installed, launched, looked healthy and silently reached no
server at all.

Put these in the gitignored `local.properties`, or pass them as `-P` properties, or set the
matching environment variable:

```properties
yoBackendUrl=https://your-backend.example
yoInviteUrl=https://your-backend.example/install
yoPrivacyUrl=https://your-backend.example/privacy
yoGoogleClientId=<web-client-id>.apps.googleusercontent.com
```

Unit tests deliberately need none of it, so a bare clone can still run the suite - which is what
CI does. Release builds additionally require signing material, an `https://` backend URL and
`app/google-services.json`; see **[docs/RELEASE.md](docs/RELEASE.md)**.

## Test

```sh
./gradlew :app:testDebugUnitTest              # Android unit tests
cd backend && python3 -m unittest discover    # backend, Python >= 3.10
```

Both suites run on every push and pull request — see
[.github/workflows/ci.yml](.github/workflows/ci.yml).

## Run

With an emulator or Android device connected:

```sh
./gradlew installDebug
```

## Backend configuration

See "Configuring a build" above for the full set. There is deliberately **no** `yoBackendKey`. A single shared key baked into every APK used to grant
whoever extracted it full access to every account; credentials are now per user and issued at
sign-in.

Plain HTTP only works in debug builds (`usesCleartextTraffic` is set in the debug manifest); release
builds require HTTPS. See [backend/README.md](backend/README.md) to run the server.

## Push

Yos are delivered over FCM, and both halves are off until configured — the app builds and runs
without them, it just never receives anything.

The app needs `app/google-services.json` for its Firebase project. It is gitignored, and
`app/build.gradle.kts` applies the google-services plugin only when the file is present, so a clone
without it still builds:

```sh
firebase apps:sdkconfig ANDROID <android-app-id> -P <project> --out app/google-services.json
```

The backend needs a service-account key for the **same** project, since an FCM token minted for one
project cannot be targeted by a server authenticated as another:

```sh
gcloud iam service-accounts keys create <path>.json \
  --iam-account=firebase-adminsdk-<id>@<project>.iam.gserviceaccount.com --project=<project>
export YO_FIREBASE_SA_KEY=<path>.json
export YO_FIREBASE_PROJECT_ID=<project>
```

Keep the key outside the repository. With either half missing, `/v1/send` answers
`{"delivered":false,"reason":"fcm_not_configured"}` rather than failing — everything else keeps
working. Working end to end on a physical device as of 2026-07-26; see PRD §7.2.

## Google sign-in

Off unless configured, in both halves. Pass the OAuth **web** client id (type 3) of your Google
Cloud project — not the Android client id — the same way as any other build property:

It is a required build value (see "Configuring a build"); a build cannot ship without one. The backend needs the identical value as
`YO_GOOGLE_CLIENT_ID`, plus `pip install -r backend/requirements.txt`, or `/v1/google` answers 503.
A mismatch between the two fails closed: the token's audience will not match and it is rejected.

Unlike the old `yoBackendKey`, this is not a secret — an OAuth client id is public by design and
grants nothing without the Google account it names. Your app's signing SHA-1 must be registered
against an Android client in the same project. Full walkthrough: PRD §7.1.

## Account deletion

Menu, then **DELETE ACCOUNT**. It erases the account, its sessions on every device, friends and
blocks in both directions, the FCM token, uploaded photos, and the local Yo history and groups.
Photos other people sent are left alone - they belong to the sender. The backend route is
`DELETE /v1/account`; a web request page for people who cannot open the app is served at
`/delete-account`, and the privacy policy at `/privacy`.
