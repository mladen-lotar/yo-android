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
| Phone screenshots (min 2) | **Outstanding** - see section 7 |
| Data safety declaration | Answers prepared in section 6; must be typed into the Console |
| Content rating questionnaire | Outstanding - Console only |
| Backend on real hosting | **Outstanding** - see section 8 |
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

The release pipeline was built and checked end to end, using a throwaway `google-services.json`
(the real Cloud project does not exist yet - G21) which was deleted immediately afterwards:

```
app-release.aab   4.7 MB    signer certificate expires 2053-12-12
app-release.apk   2.3 MB    SHA-1 2202ede8e1b3789440a75223f46ee1202ddd61ba
package: hr.theshop.yo   versionCode 1   targetSdkVersion 36   compileSdkVersion 36
```

The APK's signing fingerprint matches the upload keystore exactly, and R8 ran without breaking the
build. What is *not* verified is that the minified app runs correctly - that needs an install on a
device, which needs the real Firebase project first. **Smoke-test the release build on a handset
before uploading**; R8 failures show up at runtime, not at build time.

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
| Location | **No** | No | - | A single fix is taken when the user turns on "attach location" and is written to local history only. `YoRemoteDeliveryPortImpl` sends nothing but the recipient. See G20 |
| App interactions / analytics | No | No | - | There is no analytics SDK |
| Crash logs | No | No | - | No crash reporter is integrated |

Also declare: data is encrypted in transit (HTTPS); users can request deletion (in-app and at
`/delete-account`); the app is not directed at children.

**Before submitting the form, resolve G20.** "ATTACH LOCATION" implies the recipient receives a
location, and they do not - nothing transmits it. Either wire it into the payload (then location
*is* collected and the answer above changes, and precise location additionally needs a prominent
in-app disclosure), or remove the feature and the two location permissions. Declaring "not
collected" is accurate today, but shipping a button that implies otherwise invites a reviewer to
disagree.

## 7. Store listing

Prepared assets are in `store/`, regenerated by `python3 tools/generate-store-assets.py`:

- `play-icon-512.png` - 512x512. Uses the press-kit lockup (purple square, white "Yo") rather
  than the launcher icon, which is deliberately a flat colour with no glyph. An empty tile in a
  store listing reads as a broken upload.
- `play-feature-graphic-1024x500.png` - the listing banner.

**Screenshots are still needed** - at least 2, PNG or JPEG, each side between 320px and 3840px.
They must show the actual app. Capture them from a signed-in device:

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
2. Create the Firebase/Cloud project properly (G16, G21): a project that owns both the FCM app and
   the OAuth clients for `hr.theshop.yo`, with **both** SHA-1 fingerprints from section 4
   registered - the release key as well as the debug key, or Google sign-in works in development
   and fails for every real user.
3. Regenerate `app/google-services.json` from that project for the new package name.
4. Move the backend (section 8) and repoint `yoBackendUrl`.
5. `./gradlew :app:testDebugUnitTest` and `python3 -m unittest discover` in `backend/`.
6. `./gradlew :app:bundleRelease`, upload the `.aab` and `mapping.txt`.
7. Fill in the data safety form (section 6) and the content rating questionnaire.
8. Capture screenshots (section 7).
