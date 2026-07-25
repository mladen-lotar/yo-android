# Yo Android

Yo Android is a native Android reimplementation of the original Yo app (2014): one tap sends the
word “Yo” to a chosen friend as a push notification, with the same small set of extensions the real
app grew — links, hashtags, one-shot location, groups, and photos — plus a public broadcast API for
third-party clients. Sends are delivered over FCM and mirrored to local Room history.

The app is deliberately small. Before adding anything, read **[docs/PRD.md](docs/PRD.md)** — it
records what the original product actually was, which features were intentionally left out and why,
and the known gaps in the current build (notably that real FCM push needs credentials, and that the
backend shared key ships inside the APK).

## Build

Use JDK 17 and an Android SDK with API 34 installed:

```sh
./gradlew assembleDebug
```

## Run

With an emulator or Android device connected:

```sh
./gradlew installDebug
```

## Backend configuration

The app reads its backend location and shared key from Gradle properties, falling back to the
gitignored `local.properties`, and defaults to `http://10.0.2.2:8790` for emulator use:

```properties
yoBackendUrl=https://your-backend.example
yoBackendKey=<shared key, must match the server's YO_SERVER_KEY>
```

Plain HTTP only works in debug builds (`usesCleartextTraffic` is set in the debug manifest); release
builds require HTTPS. See [backend/README.md](backend/README.md) to run the server.
