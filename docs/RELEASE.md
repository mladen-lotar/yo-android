# Releasing Yo to Google Play

Everything needed to take `hr.theshop.yo` from this repository to a Play listing, and the
decisions behind the parts that are not obvious. Written 27 July 2026.

## 1. What Play requires, and where it stands

| Requirement | State |
|---|---|
| Package name not `com.example.*` | Done - `hr.theshop.yo` |
| targetSdk 36 (new apps, from 31 Aug 2026) | Done - `compileSdk`/`targetSdk` 36 |
| Signed App Bundle | Done - upload keystore generated, `bundleRelease` wired |
| Privacy policy at a public URL | Done - served at `/privacy` |
| In-app privacy policy link | Done - menu, PRIVACY band |
| In-app account deletion | Done - menu, DELETE ACCOUNT band |
| Web account-deletion URL | Done - served at `/delete-account` |
| 512x512 icon, 1024x500 feature graphic | Done - `store/`, regenerate with `tools/generate-store-assets.py` |
| Phone screenshots (min 2) | Done - `store/screenshots/`, captured from the release build |
| Data safety declaration | Answers prepared in section 6; must be typed into the Console |
| Content rating questionnaire | Outstanding - Console only |
| Backend on real hosting | **Outstanding** - see section 8 |
| FCM for `hr.theshop.yo` | Done - app + both SHA-1s in `yo-theshop` |
| Google sign-in for `hr.theshop.yo` | Done - Android OAuth clients in `blocksurge-theshop`, proven on device |
| Closed testing, 12 testers x 14 days | **Unknown** - see section 9 |

## 2. Toolchain

- JDK **17** builds the app.
- JDK **21** runs the unit tests. Robolectric refuses to create a sandbox for Android SDK 36 on
  anything lower (`Android SDK 36 requires Java 21`). Gradle resolves it as a toolchain, so the
  path stays out of the repo; on this machine Homebrew's `openjdk@21` is keg-only and invisible to
  `/usr/libexec/java_home`, so it is listed in `~/.gradle/gradle.properties` under
  `org.gradle.java.installations.paths`.
- Android SDK platform **36** and build-tools **36.0.0**.

AGP is held at the last 8.x (8.13.2) rather than 9.x. Dagger 2.59 dropped AGP 8 support, and the
newest AndroidX (core 1.19, lifecycle 2.11, hilt-navigation-compose 1.4) requires AGP 9.1 *and*
compileSdk 37. AGP 8.13 compiles against API 36, which is all Play asks for. Moving to AGP 9 is a
deliberate follow-up, not a prerequisite for shipping.

## 3. Configuration

There are no defaults. A missing value fails the build rather than being substituted, because a
plausible default is exactly what lets a misconfigured release reach a user looking healthy - an
APK pointing at `10.0.2.2` installs and launches perfectly and simply never reaches a server.

| Property (`local.properties`) | Environment variable | Meaning |
|---|---|---|
| `yoBackendUrl` | `YO_BACKEND_URL` | API base. Must be `https://` for a release build |
| `yoInviteUrl` | `YO_INVITE_URL` | Where invite links point |
| `yoGoogleClientId` | `YO_GOOGLE_CLIENT_ID` | OAuth **web** client id (public by design) |
| `yoPrivacyUrl` | `YO_PRIVACY_URL` | Privacy policy link shown in the menu |
| `yoKeystoreFile` | `YO_KEYSTORE_FILE` | Upload keystore path |
| `yoKeystorePassword` | `YO_KEYSTORE_PASSWORD` | Keystore password |
| `yoKeyAlias` | `YO_KEY_ALIAS` | `yo-upload` |
| `yoKeyPassword` | `YO_KEY_PASSWORD` | Key password |

`verifyYoConfiguration` gates any task that produces an artifact; `verifyYoReleaseConfiguration`
additionally requires signing material, an `https://` backend and `app/google-services.json`.
Unit tests deliberately run without any of it, so CI works in a bare checkout.

## 4. Signing

The upload keystore was generated on 27 July 2026:

```
~/.config/yo/upload-keystore.jks     PKCS12, RSA 4096, valid to 2053-12-12
alias                                yo-upload
SHA-1    22:02:ED:E8:E1:B3:78:94:40:A7:52:23:F4:6E:E1:20:2D:DD:61:BA
SHA-256  1C:7D:99:D8:E2:27:E2:7A:A6:BB:A1:2E:51:06:29:9F:BC:1E:42:DA:B5:FC:B6:28:33:D2:F2:84:DE:3C:F9:D7
```

The debug key, for comparison, is
`BC:E5:5B:00:AA:7E:68:4D:72:EF:B7:2F:53:AF:B3:97:20:F7:F8:88`.

> **Back this file up somewhere that is not this laptop.** With Play App Signing an upload key can
> be reset by support if lost, but that is a support ticket and a delay. The password lives in
> `local.properties`, which is gitignored and `chmod 600`; it exists nowhere else.

Enrol in **Play App Signing** at first upload (the default). Google then holds the app signing key
and this keystore is only the upload key.

Build the bundle:

```sh
./gradlew :app:bundleRelease
# app/build/outputs/bundle/release/app-release.aab
```

Upload `app/build/outputs/mapping/release/mapping.txt` with it, or every crash report arrives
obfuscated. R8 is on (`isMinifyEnabled`/`isShrinkResources`); the app has no reflective JSON
mapping - every request is built field-by-field with `org.json` - so the usual "R8 broke my API
models" failure mode does not apply here.

### Verified on 27 July 2026

The release pipeline was built and checked end to end, and then rebuilt against the real
`google-services.json` once the Firebase app existed (section 9, step 2):

```
app-release.aab   4.7 MB    signer certificate expires 2053-12-12
app-release.apk   2.3 MB    SHA-1 2202ede8e1b3789440a75223f46ee1202ddd61ba
package: hr.theshop.yo   versionCode 1   targetSdkVersion 36   compileSdkVersion 36
```

The APK's signing fingerprint matches the upload keystore exactly.

### Verified on a handset (Galaxy S23, release build, 27 July 2026)

The R8'd, release-signed APK was installed and driven on a real device against production:

| Step | Result |
|---|---|
| Launch | No crash; sign-in screen renders correctly under R8 |
| `CONTINUE WITH GOOGLE` | Credential Manager picker opened - no `cmsh:[28444]`, so the release-SHA Android OAuth client works |
| Sign in | Completed; home screen shows the account's friend band |
| Device registration | `devices` row written with a fresh FCM token for `hr.theshop.yo`; no "NOT RECEIVING YOS" warning |
| Push | `send_yo` returned `True`; notification posted, `pkg=hr.theshop.yo`, `channel=yo_push_v2`, importance 4, colour `0xff9b59b6` |
| Menu | All seven bands present, including PRIVACY and DELETE ACCOUNT |

This found **G22**, a release blocker no unit test could have caught - see section 4a.

### 4a. The bug the handset found

Raising targetSdk to 36 opted the app into **mandatory edge-to-edge** (Android 15 applies it to
targetSdk 35+). `MainScreen` applied no window insets, so the menu button - a 144px circle ending
at y=2304 on a 2340px screen - sat almost entirely under the navigation bar, which swallowed the
touches. Only a ~30px strip responded. Since that button is the only route to PRIVACY and DELETE
ACCOUNT, two Play requirements were effectively unreachable.

Fixed by adding vertical `systemBars` content padding to the list and a bottom
`windowInsetsPadding` to the button. The FAB now occupies y=2016-2160 and a centre tap works.
Horizontal insets are deliberately omitted so the bands stay full-bleed.

**If you raise targetSdk again, re-check the layout on a device with 3-button navigation.** This
class of bug is invisible to unit tests, to lint, and to a build that succeeds.

### 4b. Location sharing, verified on the same handset (27 July 2026)

Closing G20 was validated on the S23 against a scratch backend on `127.0.0.1:8799`, reached with
`adb reverse tcp:8799 tcp:8799` and a debug build. Production was deliberately not touched: it runs
the old code from a separate process and its own database. **Push was real FCM throughout** - only
the API server was local, so nothing about the delivery path was simulated.

| Step | Result |
|---|---|
| Inbound Yo with coordinates | Notification body `From ADA  ·  TAP TO OPEN MAP`, and a `contentIntent` where there had never been one before |
| Tapping it | Google Maps opened directly on a pin labelled `ADA` at the sent coordinates |
| `ATTACH LOCATION` in the app | Toggle read `LOCATION ON`; `POST /v1/send` carried the fix |
| The resulting push | `From MLADEN  ·  TAP TO OPEN MAP`, pin labelled `MLADEN` on the handset's own GPS position, with the blue "you are here" dot sitting on it |
| History row | Tapping a row with coordinates opens the same pin |

**This found a second device-only bug.** The first implementation used a bare `ACTION_VIEW` on the
`geo:` URI and let the system resolve it. On this handset six applications claim that scheme -
Google Maps, Waze, Uber, Bolt, myAudi and Zoom - so tapping a shared location produced an
**"Open with" chooser**, not a map. For a message whose whole content is "here is where I am", a
disambiguation dialog is a failure. `MapIntentFactory` now asks for Google Maps by name when it is
installed, falls back to an unpackaged `geo:` intent when it is not, and to a browser URL when the
device has no map application at all. A `<package>` entry in `<queries>` makes Maps visible to
`resolveActivity` under API 30+ package visibility.

Two other things worth keeping:

- **Run the backend on the venv interpreter.** `~/.local/share/yo-backend-venv/bin/python`, not the
  system `python3` - google-auth lives only in the venv, and without it every send fails with
  `fcm_delivery_failed` from `FCMDeliveryError: google-auth is required`. The prod service already
  runs there; a scratch server started with plain `python3` looks broken in a way that has nothing
  to do with the code under test.
- **Driving the sign-in form over adb needs TAB.** Tapping the second field does not move focus in
  these Compose text fields, so `input text` appends to the first one; `input keyevent 61` follows
  the declared `ImeAction.Next` and lands in the password field.

**The feature needs section 8 done before it works in production.** The shipped release build points
at `https://yo.the-shop.io`, which still runs the old server. That server ignores the extra
`latitude`/`longitude` fields rather than rejecting them, so sends keep working - they simply arrive
without a location, exactly as before. Deploying the new backend is what switches it on.

### Permissions the merged manifest adds

`AndroidManifest.xml` declares seven permissions; the built APK carries ten. Libraries merge in:

| Added | From |
|---|---|
| `USE_BIOMETRIC`, `USE_FINGERPRINT` | `androidx.credentials` |
| `ACCESS_NETWORK_STATE` | Firebase / Play Services |

They are harmless but they appear on the store listing, so the biometric ones will be visible to
users even though the app never asks for a fingerprint. Nothing to fix - just do not be surprised.

## 5. The name

The original Yo (Or Arbel, 2014) shut down around 2016. Checked on 27 July 2026:

- **US trademark application 86368420, "YO", Life Before Us, Inc.** - filed 15 Aug 2014 in
  classes 009 and 038 (electronic transmission of messages; instant messaging), which are exactly
  this app's classes. Status: **DEAD**, abandoned 14 Mar 2019 for failure to respond to an Office
  action, status date 11 Apr 2019. It never reached registration.
- **US application 90357777, "YO", Yohana LLC** - classes 035/044/045 (wellness and personal
  assistant services). Also **DEAD**, abandoned 11 Aug 2025. Unrelated field.
- The original app's Play listings (`co.justyo.yoapp`, `com.yo.yo`, `co.justyo.yo`) all return
  404 - it is delisted.
- A Play search for "Yo" surfaces no exact-name messaging app.

So there is no live US registration blocking the name in the relevant classes, and no competing
listing to be confused with.

**The residual risk is not trademark, it is Play's impersonation policy**, which is about consumer
confusion rather than registry status. This app deliberately reproduces the original's name, its
palette, its 89dp bands and its wordmark. A reviewer who recognises it, or a rights holder
asserting unregistered rights, could still object.

Cheap insurance, recommended:

- List it as **"Yo - The Shop"** or **"Yo by The Shop"** rather than bare "Yo". This also dodges
  the plain "another app already has this name" rejection, which is common and unrelated to
  trademarks.
- State in the full description that it is an independent app, not affiliated with or a
  continuation of the 2014 Yo.

**Not checked:** EUIPO / Croatian national marks. The EUIPO database has no usable public API and
`uspto.report` and Justia both refuse automated fetches. Since the publisher is Croatian, an
EUIPO `eSearch plus` lookup for "YO" in classes 9 and 38 is worth ten minutes before launch.

## 6. Data safety declaration

Answers derived from what the code actually does, not from the permission list. Two of these are
narrower than a reviewer would guess, and both are load-bearing:

| Data type | Collected? | Shared? | Purpose | Notes |
|---|---|---|---|---|
| Name / username | Yes | No | App functionality | Chosen by the user; how friends address them |
| Email address | **No** | No | - | Google sign-in stores only the opaque `sub` |
| Password | Yes | No | Authentication | Stored as PBKDF2-HMAC-SHA256, never in clear |
| User IDs | Yes | No | App functionality | FCM registration token; Google subject id |
| Photos | Yes | No | App functionality | Only those attached to a Yo; scoped to sender and recipient |
| Contacts | **No** | No | - | Read on device for the invite list. Only display name plus a local id, never a phone number or email, and never transmitted |
| Precise location | Yes | **Yes** | App functionality | Optional. A single fix, only when the user turns on "attach location" for that Yo, relayed to the chosen recipient so they can open it on a map. Never continuous, never in the background |
| App interactions / analytics | No | No | - | There is no analytics SDK |
| Crash logs | No | No | - | No crash reporter is integrated |

Also declare: data is encrypted in transit (HTTPS); users can request deletion (in-app and at
`/delete-account`); the app is not directed at children.

**The location row changed when G20 was fixed (2026-07-27) and it is the one row on this form that
is easy to get wrong.** Until then "attach location" took a fix, wrote it to local history and
transmitted nothing, so the honest answer was "not collected". The coordinates now genuinely travel
to the recipient, so location must be declared **collected and shared**, and the sharing must be
described as user-initiated and optional.

Two things a reviewer will look for, and where they stand:

- **Data sharing.** "Shared" here means transferred to another user, not sold or handed to a third
  party for their own purposes. That is what the form's *App functionality* purpose covers; do not
  tick advertising or analytics.
- **Prominent disclosure.** Play requires one where the collection would not be obvious from
  context. Here the user turns on a control labelled "ATTACH LOCATION" for a single message and
  then answers the system permission prompt, so the collection is in-context and foreground-only -
  no background access, no continuous tracking, and the manifest deliberately does not declare
  `ACCESS_BACKGROUND_LOCATION`. If a reviewer disagrees, the cheapest answer is a one-line
  explanation on the attach sheet rather than a code change.

Precise location is declared because the manifest requests `ACCESS_FINE_LOCATION`. Dropping to
coarse only would let this be declared as approximate, at the cost of a pin that can be a city
block out - which for "come and find me" is the whole feature.

## 7. Store listing

Prepared assets are in `store/`, regenerated by `python3 tools/generate-store-assets.py`:

- `play-icon-512.png` - 512x512. Uses the press-kit lockup (purple square, white "Yo") rather
  than the launcher icon, which is deliberately a flat colour with no glyph. An empty tile in a
  store listing reads as a broken upload.
- `play-feature-graphic-1024x500.png` - the listing banner.

Two screenshots are in `store/screenshots/` (1080x2340, captured from the release build on a
signed-in S23): the friends list and the menu. Play wants at least 2; more is better, and the
invite sheet and a delivered notification are the obvious next two. Capture with:

```sh
adb -s <serial> shell screencap -p /sdcard/yo-1.png
adb -s <serial> pull /sdcard/yo-1.png store/
```

Worth capturing: the friends list with a few bands, the menu, the invite sheet, and a delivered
Yo notification. Note that a pattern-locked Samsung returns an all-black screencap while the
Bouncer is up - unlock first, or the files look like the app rendered nothing.

Suggested copy:

- **Title:** `Yo - The Shop` (30 chars max)
- **Short description:** `One tap. One word. Yo.` (80 chars max)
- **Full description:** cover what a Yo is, that friends are added explicitly by username, that
  contacts never leave the phone, and the independence statement from section 5.

## 8. Backend deployment (pre-release)

Today the backend runs as a launchd agent on a laptop, behind a Cloudflare tunnel:

```
~/Library/LaunchAgents/com.yo.backend.plist   KeepAlive, /opt/homebrew/bin/python3
yo.the-shop.io -> fleet-bridge tunnel -> 127.0.0.1:8790
backend/yo.db (SQLite), log ~/.ai-fleet/logs/yo-backend.log
```

**This must move to a server before the app is public.** It is not a matter of scale - the load
is trivial - but of consequence. Once strangers install from a store listing, a laptop that
sleeps, reboots or loses its tunnel takes every user's Yo down with it, and the privacy policy
and deletion URLs Play requires go down with it too. Play can and does re-check those URLs after
launch.

What the move needs:

1. **A host.** Any small VM. The service is a single-file Python `ThreadingHTTPServer` with one
   dependency (`google-auth`); no container is required, though one is fine.
2. **A process supervisor.** systemd unit replacing the launchd agent, `Restart=always`, running
   as a non-root user. Environment carries `YO_GOOGLE_CLIENT_ID`, `YO_FIREBASE_PROJECT_ID`,
   `YO_FIREBASE_SA_KEY` and `YO_APK_PATH` if the APK is served.
3. **TLS on a stable hostname.** Keep `yo.the-shop.io`, repointed from the tunnel to the host,
   with a real certificate. The release build refuses a non-`https://` backend URL at build time.
4. **The service-account key**, currently `~/.config/yo/firebase-sa.json`, `chmod 600`, copied
   outside the repository and never into it.
5. **Rate limiting still keys on `CF-Connecting-IP`.** If Cloudflare is dropped, that header
   disappears and every caller collapses into one bucket - one abuser would lock out everybody.
   Whatever proxy replaces it must set a trusted client-IP header and the code must read it.
6. **Database migration.** Stop the agent, copy `backend/yo.db`, start the service. Accounts,
   friendships and FCM tokens all live in that one file.
7. **Backups.** There are none today beyond one manual `.bak`. A nightly copy off the host is the
   minimum, since losing the file means losing every account and every friendship.
8. **Verify after the move**, over the public internet rather than from the host:
   `GET /healthz`, `GET /privacy`, `GET /delete-account`, then a real signup, register and send
   from a phone.

## 9. Before the first upload

1. Confirm the Play developer account type. A personal account created after Nov 2023 must run
   **closed testing with at least 12 testers opted in for 14 continuous days** before it can apply
   for production. This dominates the schedule; check it first.
2. **Push: done** (27 Jul 2026). `yo-theshop` now has a Firebase Android app for `hr.theshop.yo`
   (`1:747034506241:android:e5b34b298d59ea5e48bc00`) with both SHA-1s from section 4 registered,
   and `app/google-services.json` is fetched from it. It stays gitignored.
3. **Google sign-in: done** (27 Jul 2026). `yo-theshop` cannot host it - no billing means no
   Firebase Auth, and Firebase only auto-creates the Android OAuth client when Google sign-in is
   enabled. So `hr.theshop.yo` was also registered in **`blocksurge-theshop`**, which has it
   enabled; adding both SHA-1s there auto-created two Android clients, one per fingerprint,
   alongside the web client the app and backend already share. Proven on device with the
   release-signed build.

   This leaves **G16** standing: the OAuth clients live in a borrowed project. To close it, put
   `yo-theshop` on billing and repeat the same registration there. Historic detail follows.

4. **Google sign-in: the original blocked state** (G21). The project returns only a web OAuth client; Firebase
   auto-creates the Android one only when Google sign-in is enabled, which needs Firebase Auth,
   which needs billing on `yo-theshop`. Pick one:
   - put `yo-theshop` on billing, enable Google sign-in, let Firebase create both clients, then
     point `yoGoogleClientId` and the backend's `YO_GOOGLE_CLIENT_ID` at its web client; or
   - keep borrowing `blocksurge-theshop` and add an Android client there for `hr.theshop.yo` with
     **both** fingerprints.

   Either way the release SHA-1 must be registered, not just the debug one - that is the failure
   that works perfectly in development and breaks for every real user. There is no public API for
   creating an Android OAuth client, so this is a console action.

   Until it is done, `CONTINUE WITH GOOGLE` fails with `cmsh:[28444]` on the renamed package.
   Username and password sign-in is unaffected.
4. Move the backend (section 8) and repoint `yoBackendUrl`.
5. `./gradlew :app:testDebugUnitTest` and `python3 -m unittest discover` in `backend/`.
6. `./gradlew :app:bundleRelease`, upload the `.aab` and `mapping.txt`.
7. Fill in the data safety form (section 6) and the content rating questionnaire.
8. Capture screenshots (section 7).
