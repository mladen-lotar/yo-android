# Yo Android

Yo Android is a native Android reimplementation of the original Yo app (2014): one tap sends the
word “Yo” to a chosen friend as a push notification, with the same small set of extensions the real
app grew — links, hashtags, one-shot location, groups, and photos — plus a public broadcast API for
third-party clients. Sends are delivered over FCM and mirrored to local Room history.

The app is deliberately small. Before adding anything, read **[docs/PRD.md](docs/PRD.md)** — it
records what the original product actually was, which features were intentionally left out and why,
and the known gaps in the current build (notably that real FCM push still needs credentials).

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

Use JDK 17 and an Android SDK with API 34 installed:

```sh
./gradlew assembleDebug
```

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

The app reads its backend location from a Gradle property, falling back to the gitignored
`local.properties`, and defaults to `http://10.0.2.2:8790` for emulator use:

```properties
yoBackendUrl=https://your-backend.example
yoInviteUrl=https://your-backend.example/install
```

There is deliberately **no** `yoBackendKey`. A single shared key baked into every APK used to grant
whoever extracted it full access to every account; credentials are now per user and issued at
sign-in.

Plain HTTP only works in debug builds (`usesCleartextTraffic` is set in the debug manifest); release
builds require HTTPS. See [backend/README.md](backend/README.md) to run the server.

## Google sign-in

Off unless configured, in both halves. Pass the OAuth **web** client id (type 3) of your Google
Cloud project — not the Android client id — the same way as any other build property:

```properties
yoGoogleClientId=<web-client-id>.apps.googleusercontent.com
```

Leave it unset and the app omits the Google band entirely. The backend needs the identical value as
`YO_GOOGLE_CLIENT_ID`, plus `pip install -r backend/requirements.txt`, or `/v1/google` answers 503.
A mismatch between the two fails closed: the token's audience will not match and it is rejected.

Unlike the old `yoBackendKey`, this is not a secret — an OAuth client id is public by design and
grants nothing without the Google account it names. Your app's signing SHA-1 must be registered
against an Android client in the same project. Full walkthrough: PRD §7.1.
