# Releasing Yo to Google Play

Everything needed to take `hr.theshop.yo` from this repository to a Play listing, and the
decisions behind the parts that are not obvious. Written 27 July 2026; revised 28 July 2026, when
the backend moved off the laptop (section 8), the photo feature was removed (section 6), and the
store icon was found to be in a format Play rejects (section 7).

**Revised again 28 July 2026, second pass.** An audit measured the claims here against the release
APK, the store assets and the live systems instead of re-reading them, and five were wrong. The
permission count in section 4 was guessed rather than measured, and its table missed two
permissions. The screenshots were 32-bit RGBA against a spec that is no-alpha, and are now RGB -
though one of the two is stale for a different reason (section 7). The full-description placeholder
in section 7 described copy that already exists in `store/listing.md`, which this document never
referenced. Section 6's argument for declaring precise location contradicts the code. And section 9
item 7 told a future operator to check a vendored file list that does not exist anywhere. All five
are corrected below rather than quietly rewritten.

**Revised 29 July 2026.** The backend stopped being a hand-maintained copy: this repository is now
a git **submodule** of `lotar/claude` at `modules/yo/src`, pinned to a commit, and the image builds
from it. Section 9 item 7 carries the new deploy sequence and the new failure mode, which is quieter
than the one it replaced - a plain `git pull` on the host moves the gitlink but leaves the submodule
at the previous commit, so a rebuild silently ships the previous release. The store description also
stopped advertising Google sign-in, which fails for every account outside `the-shop.hr` while G27 is
open - including the reviewer's, whom this document already tells not to use it.

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
| 512x512 icon, 1024x500 feature graphic | Done - `store/`, regenerate with `tools/generate-store-assets.py`. Icon is now **32-bit RGBA**; the 24-bit version Play rejects was shipped until 28 Jul 2026, see section 7 |
| Phone screenshots (min 2) | Done (29 Jul 2026) - **recaptured on a Galaxy S23 from a release build of HEAD**, 1080x2340 RGB. The menu shot now shows all 8 rows including BLOCKED; the home shot shows three friend bands rather than one |
| Release artifact matches HEAD | **Outstanding** - the `.aab` must be **rebuilt from HEAD before upload**; the one previously on disk predates the CAMERA removal, see section 4 |
| Data safety declaration | Answers prepared in section 6; must be typed into the Console |
| Content rating questionnaire | **Answers done, entry outstanding** - fully derived in `store/listing.md`; the questionnaire itself is Console-only |
| Photo attachment | **Removed** (28 Jul 2026) - it was upload-only and could never be read back; see PRD G24. No `CAMERA` permission at all now |
| Send confirmation is honest | Done (28 Jul 2026) - `YO!` only on confirmed delivery, `COULDN'T YO <NAME> - TAP TO RETRY` otherwise; see PRD G25 |
| Link / hashtag actually delivered | Done (28 Jul 2026) - they now reach the recipient's notification instead of only local history. Needed a manifest `<queries>` entry to work at all on Android 11+; see PRD G23 |
| User controls: block / remove friend / unblock | Done (28 Jul 2026) - long-press a friend; BLOCKED sheet in the menu unblocks. Play expects these in user-to-user messaging; see PRD G26 |
| Deletion contact address readable without JS | Done (28 Jul 2026) - Cloudflare Scrape Shield was rewriting it, see section 8.9 |
| Backend on real hosting | Done (28 Jul 2026) - Docker container behind the shared Traefik, see section 8 |
| Database backups | Done (28 Jul 2026) - hourly on the host, daily off-host, see section 8 |
| FCM for `hr.theshop.yo` | Done - app + both SHA-1s in `yo-theshop` |
| Google sign-in for `hr.theshop.yo` | Done - all clients in `yo-theshop` (G16 closed); not yet re-verified on device |
| OAuth consent screen off `orgInternalOnly` | **Outstanding** - Console only, blocks every non-`the-shop.hr` account |
| App access - credentials for the reviewer | Done (29 Jul 2026) - `YODEMO1` / `YomyoU4NTT1pe8ik`, three friends visible, and `YODEMO2` now has a registered device so a reviewer's Yo actually delivers. Credentials and instructions in `store/listing.md`. **Four accounts to delete after launch** |
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

**Rebuild the bundle from HEAD before uploading it, every time.** The `.aab` previously sitting in
`app/build/outputs/` was built **2h14m before the commit that removed `CAMERA`**, and still declared
`android.permission.CAMERA` and `<uses-feature android:name="android.hardware.camera">`. Uploading
it would have contradicted **both** the data safety form in section 6 (which now answers the whole
*Photos and videos* section "no") and the store listing in section 7 - a permission on the listing
page for a feature the app does not have, submitted alongside a declaration saying it has no such
feature.

The general form is worth keeping, because nothing in this repository catches it: **build time is
not commit time.** A stale artifact is indistinguishable from a fresh one by inspection, `bundleRelease`
succeeds identically either way, and no check anywhere asserts that the artifact was built from
HEAD. Verify with `aapt dump permissions` against the bundle you are actually about to upload, not
against one you built earlier.

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

- **Run a laptop backend on the venv interpreter.** `~/.local/share/yo-backend-venv/bin/python`, not
  the system `python3` - google-auth lives only in the venv, and without it every send fails with
  `fcm_delivery_failed` from `FCMDeliveryError: google-auth is required`. A scratch server started
  with plain `python3` looks broken in a way that has nothing to do with the code under test.
  Production no longer has this failure mode at all: since the move (section 8) it runs in an image
  that installs `google-auth` at build time, pinned to 2.56.2.
- **Driving the sign-in form over adb needs TAB.** Tapping the second field does not move focus in
  these Compose text fields, so `input text` appends to the first one; `input keyevent 61` follows
  the declared `ImeAction.Next` and lands in the password field.

**The feature needed section 8 done before it worked in production, and section 8 is now done**
(28 Jul 2026). Until then the shipped release build pointed at `https://yo.the-shop.io` running the
old server, which ignored the extra `latitude`/`longitude` fields rather than rejecting them, so
sends kept working - they simply arrived without a location, exactly as before. The deployed
container carries the new code, so the pair now travels end to end in production. **That specific
path has not been re-driven on a handset since the move** - the cutover was verified over HTTP
(section 8), not with a phone in hand, so treat "location arrives in production" as deployed rather
than as re-verified on device.

### Permissions the merged manifest adds

`AndroidManifest.xml` now declares **six** permissions - `ACCESS_COARSE_LOCATION`,
`ACCESS_FINE_LOCATION`, `INTERNET`, `POST_NOTIFICATIONS`, `READ_CONTACTS`, `VIBRATE`. It declared
seven until 28 July 2026; `CAMERA` and its `<uses-feature>` went with the photo removal (section 6,
and PRD G24). Libraries merge in **five** more:

| Added | From |
|---|---|
| `USE_BIOMETRIC`, `USE_FINGERPRINT` | `androidx.credentials` |
| `ACCESS_NETWORK_STATE` | Firebase / Play Services |
| `WAKE_LOCK` | firebase-messaging |
| `com.google.android.c2dm.permission.RECEIVE` | firebase-messaging |

The biometric ones are harmless but they appear on the store listing, so they will be visible to
users even though the app never asks for a fingerprint. Nothing to fix - just do not be surprised.

**Both the count and the table above were wrong until 28 July 2026, and the count was wrong by
guessing.** This section used to say the merged total "was measured at ten when the manifest
declared seven", that it had "**not** been re-measured since `CAMERA` was removed", and that "the
arithmetic says nine". Subtracting one from a stale measurement is not a measurement.

Measured with `aapt dump permissions` on the release APK: **11 real permissions** - the 6 declared
in `app/src/main/AndroidManifest.xml` plus the 5 merged from libraries above - or **12** if you
count the self-defined signature permission
`hr.theshop.yo.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`, which the app declares for its own use and
which no user ever grants. The table listed only three merged permissions and missed `WAKE_LOCK` and
`com.google.android.c2dm.permission.RECEIVE`, both from firebase-messaging - which is to say it
missed the two that push delivery depends on. Re-run `aapt dump permissions` after any dependency
change; the merged manifest is not derivable from the one in the repository.

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
| Contacts | **No** | No | - | Read on device for the invite list. Only display name plus a local id, never a phone number or email, and never transmitted |
| Precise location | Yes | **Yes** | App functionality | Optional. A single fix, only when the user turns on "attach location" for that Yo, relayed to the chosen recipient so they can open it on a map. Never continuous, never in the background |
| Other in-app messages (link, hashtag) | Yes | **Yes** | App functionality | Optional, user-typed. Relayed to the chosen recipient in the push and **not stored on the server** - the backend validates and forwards, no table or column holds either |
| App interactions / analytics | No | No | - | There is no analytics SDK |
| Crash logs | No | No | - | No crash reporter is integrated |

Also declare: data is encrypted in transit (HTTPS); users can request deletion (in-app and at
`/delete-account`); the app is not directed at children.

**Two rows changed on 28 July 2026, and the form is simpler for it.**

- **Photos is gone entirely**, because the feature is gone - see PRD G24. The row used to claim the
  photo was "scoped to sender and recipient", and that claim was never true in the way a reader
  would take it: the upload worked, but no recipient could ever fetch one, so what the row actually
  described was storage nobody could read. With the feature removed there is nothing to declare, the
  app no longer requests `CAMERA`, and the whole *Photos and videos* section of the form is
  answered "no". The live privacy policy carried the same false claim in prose and was corrected in
  the same change; a backend test now asserts neither page mentions photos at all.
- **Link and hashtag are a new row**, under *Messages → Other in-app messages*. Until 28 July 2026
  they were written to local history and never transmitted, so "not collected" was honest. They now
  travel to the recipient, so they are **collected and shared** on exactly the same footing as
  location: optional, user-initiated, per message, and relayed to the chosen recipient only. The
  difference worth stating on the form is that the server does not retain them - it validates
  length and type, rejects anything malformed with a 400, and forwards. There is a test asserting no
  table or column holds either, which is what keeps this answer true as the schema changes.

**The location row is unchanged and stays "collected AND shared".** It is the one row on this form
that is easy to get wrong. It changed when G20 was fixed (2026-07-27): until then "attach location"
took a fix, wrote it to local history and transmitted nothing, so the honest answer was "not
collected". The coordinates genuinely travel to the recipient now, so location must be declared
**collected and shared**, and the sharing must be described as user-initiated and optional. Nothing
in the 28 July change touched that - do not let the photo removal tempt anyone into re-answering it.

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

Precise location is declared because the manifest requests `ACCESS_FINE_LOCATION`. **That answer is
right; the argument this section used to give for it is not, and the two should not be left sitting
next to each other.**

The old wording justified the declaration by saying that dropping to coarse "would be a pin that can
be a city block out - which for 'come and find me' is the whole feature". But
`app/src/main/java/hr/theshop/yo/data/location/FusedOneShotLocationProvider.kt:31` already requests
`Priority.PRIORITY_BALANCED_POWER_ACCURACY`, **not** `PRIORITY_HIGH_ACCURACY`. The app therefore
already accepts, at runtime, roughly the accuracy the argument calls unacceptable - the permission
allows a precise fix, and the code does not ask for one.

**This does not change the data-safety answer.** The manifest declares `ACCESS_FINE_LOCATION`, so
"precise" is the correct and required declaration whatever the runtime priority is; declaring
"approximate" while holding the fine permission would be the wrong answer, not a truer one. But one
of the two is wrong on the merits - either the provider should ask for high accuracy because the
feature needs it, or the manifest should drop to coarse because it does not - and it is worth naming
that rather than leaving a justification in this document that the code contradicts. Deciding it is
a product question (P2 also argues for the cheaper fix), not a release blocker.

## 7. Store listing

Prepared assets are in `store/`, regenerated by `python3 tools/generate-store-assets.py`:

- `play-icon-512.png` - 512x512, **RGBA (32-bit)**. Uses the press-kit lockup (purple square, white
  "Yo") rather than the launcher icon, which is deliberately a flat colour with no glyph. An empty
  tile in a store listing reads as a broken upload.
- `play-feature-graphic-1024x500.png` - the listing banner, **RGB (24-bit)**.

**The icon was 24-bit until 28 July 2026 and Play would have rejected it at upload.** The generator
built it with `Image.new("RGB", ...)`; Play specifies the hi-res icon as a 32-bit PNG and its
uploader refuses a 24-bit one outright. Changed to `"RGBA"`, regenerated, and verified as
`RGBA (512, 512)`. The square is opaque either way, so this changed the file's format and not one
visible pixel - which is exactly why it would have survived every eyeball check and failed at the
one place it mattered.

**The feature graphic deliberately stays `RGB`.** Play specifies *that* one as 24-bit with no alpha,
so "fixing" it to match the icon would be the actual regression. There is now a comment in
`tools/generate-store-assets.py` saying so, because the two lines sit twenty apart and look like an
inconsistency worth tidying.

Two screenshots are in `store/screenshots/` (1080x2340, captured from the release build on a
signed-in S23): the friends list and the menu. Play wants at least 2; more is better, and the
invite sheet and a delivered notification are the obvious next two.

**Both screenshots are now RGB, and that is the fix rather than the defect.** Play's screenshot spec
is **no alpha**, and both files were 32-bit RGBA with a fully opaque alpha channel - the exact
inverse of the icon bug above, and invisible to an eyeball check in exactly the same way. The two
specs genuinely disagree with each other: hi-res icon 32-bit **with** alpha, feature graphic and
screenshots 24-bit **without**. Check the mode, never assume consistency across the three.

**`sc-menu.png` is stale and must be recaptured - this one needs a handset.** It shows **7** menu
bands; the app renders **8**. The missing one is **BLOCKED**, added with G26 on 28 July 2026
(`MainScreen.kt`), which is the band that makes the moderation answer in section 9 item 10 true. A
screenshot showing 7 rows is a store listing quietly asserting the app has no unblock surface, in
the same submission where the content rating questionnaire answers that blocking is the moderation
control. Recapture it before upload; nothing about this is fixable from the repository.

Capture with:

```sh
adb -s <serial> shell screencap -p /sdcard/yo-1.png
adb -s <serial> pull /sdcard/yo-1.png store/
```

Worth capturing: the friends list with a few bands, the menu, the invite sheet, and a delivered
Yo notification. Note that a pattern-locked Samsung returns an all-black screencap while the
Bouncer is up - unlock first, or the files look like the app rendered nothing.

**The listing copy lives in `store/listing.md`, and this section used to carry a "FULL DESCRIPTION -
PLACEHOLDER, TO BE WRITTEN BY HAND" block instead.** That block was obsolete: the copy exists.
`store/listing.md` holds the app name, the short description, the full description, the App access
instructions, the category and contact block, and the content rating answers - and until
28 July 2026 this document **never referenced that file at all**, so a reader following RELEASE.md
would have concluded the listing was unwritten and written it a second time.

The full description is **2,803 characters against Play's 4,000 limit** and satisfies every
constraint the placeholder listed: it says what a Yo is, that friends are added explicitly by
username, that contacts never leave the phone, and it carries the independence statement from
section 5. It does **not** mention photo attachment, which was the placeholder's one hard
prohibition - that feature was removed on 28 July 2026 (PRD G24), and a store listing describing a
feature the app does not have is its own rejection reason.

`store/listing.md` is the source of truth for listing copy. Edit it there, not here; this document
records release *process*, and duplicating the copy into it is how the two drift.

For quick reference, the two short fields:

- **Title:** `Yo - The Shop` (30 chars max)
- **Short description:** `One tap. One word. Yo.` (80 chars max)

## 8. Backend deployment - done 28 July 2026

The backend no longer runs on a laptop. It was a launchd agent behind a Cloudflare tunnel until
28 July 2026, which was never a scale problem - the load is trivial - but a consequence problem:
once strangers install from a store listing, a laptop that sleeps, reboots or loses its tunnel
takes every user's Yo down with it, and the privacy policy and deletion URLs Play requires go down
with it too. Play can and does re-check those URLs after launch.

### 8.1 Where it runs now

```
host        the-shop, ssh root@46.225.53.158
repo        lotar/claude  (NOT evh-claude)
path        /root/claude/modules/yo
container   yo-backend, compose project `yo`, one service
front door  yo.the-shop.io -> Cloudflare (proxied) -> shared Traefik v3 -> yo-backend
data        /root/claude/modules/yo/data/yo.db, bind-mounted at /data
```

Deploy is one command, and **the working directory is load-bearing**:

```sh
cd /root/claude/modules/yo
docker compose -f compose.prod.yml up -d --build
```

Compose auto-loads `.env` from the *project directory* only. The Traefik labels read
`traefik.enable=${YO_TRAEFIK_ENABLE:-false}`, so the same command run from anywhere else builds and
starts a container that passes its healthcheck, logs nothing wrong, and is **not routed** - the
hostname keeps serving whatever was there before. There is no error to notice. Deploy from the
project directory or not at all.

`compose.yml` is deliberately absent, so a bare `docker compose up` fails rather than guessing at a
configuration.

### 8.2 The hostname, and the address that is not it

`yo.the-shop.io` is an **A record to `116.203.165.173`, proxied through Cloudflare**. That is the
Hetzner *floating* IP. `46.225.53.158` is the DHCP-delivered primary address - correct for ssh and
wrong for DNS, and the two are easy to confuse because ssh is how you reach the box.

The tell was a neighbour: `odvjetnik-vrbosic.hr` is the one non-proxied hostname on this origin, so
it is the only one whose public DNS answer shows what the box is actually reachable on, and it
already resolved to `.173`. Checking a proxied hostname would only have shown Cloudflare's anycast
addresses and proved nothing.

`yoBackendUrl` did not change and never needs to: the hostname stayed `yo.the-shop.io`, which is
the entire reason every installed APK kept working across the move with no rebuild and no Play
update.

### 8.3 Edge and routing

Traefik v3, label-driven, shared with every other service on this origin. Yo's router carries a
`yo-cf-only` **ipAllowList** middleware scoped to Cloudflare's egress ranges, so the origin only
answers Yo requests that came through Cloudflare:

| Check | Result |
|---|---|
| `yo.the-shop.io` through Cloudflare | 200 |
| Direct to the origin IP with `Host: yo.the-shop.io` | **403** |
| Direct to the origin IP for a neighbour (`webward`) | 307 - unchanged |

Per-router scoping is why this is a middleware and not a host firewall. A firewall rule restricting
the box to Cloudflare would also have blocked `odvjetnik-vrbosic.hr`, which is deliberately not
proxied and must keep answering direct traffic.

This is the same trust boundary as `YO_CLOUDFLARE_RANGES` in item 5 of §8.7, stated twice - once in
Traefik, once in the application. **They must change together.**

### 8.4 The container

No published host ports at all; it joins the external `traefik-public` network and is reachable
only through the proxy.

| Setting | Value |
|---|---|
| User | `10001:10001`, non-root |
| Filesystem | `read_only: true`, `tmpfs` on `/tmp` |
| Privileges | `cap_drop: ALL`, `no-new-privileges` |
| Limits | memory 256M, cpu 0.5 |
| Logging | json-file, 10m x 3 |
| Restart | `unless-stopped` |
| Stop | `STOPSIGNAL SIGINT` |

One bind mount: `./data:/data`. **The directory is mounted, not the file** - deliberately. The
store is `journal_mode=delete`, so SQLite writes `yo.db-journal` *beside* the database on every
transaction; bind-mounting the single file would leave the journal nowhere to go.

The Firebase service-account key is a compose **secret**, mounted at `/run/secrets/yo_firebase_sa`.
It is not in the image and not in the repository.

Environment comes from `/root/claude/modules/yo/.env`, mode `0600`: `YO_FIREBASE_PROJECT_ID`,
`YO_GOOGLE_CLIENT_ID`, `YO_CLOUDFLARE_RANGES`, `YO_TRAEFIK_ENABLE`. All four are set.

### 8.5 The migration trap, which cost real time

`scp` lands the database as `root:root` `0600`. Uid 10001 inside the container cannot read it, and
**`chmod` on the file can never fix it**, because `journal_mode=delete` needs to create
`yo.db-journal` in the *directory*. The fix is ownership on the directory:

```sh
chown -R 10001:10001 /root/claude/modules/yo/data/
```

Worth pairing with the `/healthz` and unwritable-database notes in §8.7: a database the process can
read but not write starts cleanly and reports healthy.

### 8.6 The cutover, as it actually ran

Window **12:08:37Z -> 12:11:42Z, 3 minutes 5 seconds**.

| Check | Result |
|---|---|
| Content digest, live -> snapshot -> transferred -> served -> backup | `0582f09b70e68cb2c08191413ff52c0487f11ecc339a5c62520a3838f43cec17` at all five points - identical |
| Traefik routers / services / middlewares | 48/16/28 -> 50/17/30, 0 errors, container never restarted |
| Services checked for backend count | all 13 still single-backend |
| All 8 neighbour hostnames | at their baseline status codes |

The digest is a *content* digest, not a file checksum of the copy - which is the point. It is what
distinguishes "the same bytes arrived" from "a file of the same size arrived", and it is the same
comparison the rollback rule in the module's `CLAUDE.md` depends on.

**Backups now exist**, where before there was one manual `.bak`:

| Where | What |
|---|---|
| Host | `/etc/cron.d/yo-db-backup`, hourly, into `/root/backups/yo`, 7-day retention |
| Laptop | daily pull at 07:30 into `~/backups/yo` |

Both were verified running. `/root/backups/yo` is **deliberately outside `/root/claude`**: the repo
carries an `auto-commit-push.sh` whose `git add -A` would otherwise sweep every user's account data
into a git history.

The snapshot is taken with **CPython's `sqlite3` `Connection.backup()`, not the `sqlite3` CLI's
`.backup`**. The CLI has no default busy timeout, so against a live writer it fails roughly 46% of
the time - and its failure leaves a *stale* destination file behind, one that still passes
`PRAGMA integrity_check` and still has plausible row counts. A backup that is silently a day old
and passes every check you would think to run is worse than one that is absent.

Landed as `yo-android` `5eb748e` (PR #34) and `lotar/claude` `f0beee8cc` (PR #190).

### 8.7 Constraints that outlive the host

These were written before the move and are still exactly true. They are the reason the deployment
looks the way it does, and they are what any future host must also satisfy.

1. **Single-replica permanently.** The service is a Python `ThreadingHTTPServer` across five runtime
   modules (`yo_server`, `yo_db`, `yo_auth`, `yo_google`, `fcm_client`) with one direct
   dependency (`google-auth`, six packages transitively, all prebuilt wheels - no compiler).
   It is **single-replica permanently**: the rate limiters are in-memory and the store is a
   non-WAL SQLite file, so a second replica halves every limit and invites `SQLITE_BUSY`.
2. **The two settings with no usable default.** Two settings decide whether a containerised run
   works at all and neither has a usable default: `--host 0.0.0.0` (the default `127.0.0.1` is
   unreachable from outside the container) and `--database` on the mounted volume (the default
   lands in the image layer and is discarded on every restart).
   Fail-fast is **not** total: an existing database the process cannot write does not fail at
   startup - `initialize()` is `CREATE TABLE IF NOT EXISTS`, a no-op needing no write lock - so
   it starts, reports healthy, serves reads, and fails every write. `/healthz` never opens the
   database. Prove writes explicitly after any deploy.
3. **TLS on a stable hostname.** The release build refuses a non-`https://` backend URL at build
   time, so the hostname is not something a deploy may quietly change.
4. **The service-account key** never enters the repository or the image. On the laptop it was
   `~/.config/yo/firebase-sa.json`, `chmod 600`; in production it is the compose secret in §8.4.
5. **Rate limiting keys on `CF-Connecting-IP`, but only from a Cloudflare peer.** The header is
   believed only when the request's peer - the **rightmost** `X-Forwarded-For` entry, which a
   proxy authors from the TCP peer - falls inside `YO_CLOUDFLARE_RANGES`. Otherwise the peer
   itself is the key.
   Do not "simplify" this to `X-Forwarded-For[0]`: Cloudflare *forwards* a client-supplied
   `X-Forwarded-For` unmodified, so position [0] is attacker-chosen, while it *rejects* a
   client-supplied `CF-Connecting-IP` at the edge and writes the header itself. Trusting
   `CF-Connecting-IP` unconditionally was a measured bypass - 15 signups against a limit of 10.
   The `yo-cf-only` ipAllowList middleware on the Traefik router is what makes the CF branch
   sound; leaving `YO_CLOUDFLARE_RANGES` empty disables the branch and fails closed.
   After the fix the same probe returns `201` ten times and then `429`.
   **Two corrections from 28 July 2026** (PRD G32, G33). The forwarded chain is now read **only when
   the socket peer is private or loopback** - only a proxy on this host may author it. Production was
   never spoofable, which was *measured*: twelve forged `X-Forwarded-For` values landed in one bucket,
   ten `400`s then `429`, because Traefik strips a client-supplied header and appends its own peer.
   But that safety lived **entirely in the proxy**, while the function's own docstring promised it
   fell back to the peer without one. It does now. And **IPv6 is bucketed to its /64**: a residential
   customer's smallest allocation is a /64, so keying on the full address handed every IPv6 client an
   unlimited supply of buckets against the limiter that is also the login brute-force control and the
   only cost control on a 600,000-iteration PBKDF2. `YO_CLOUDFLARE_RANGES` in production already
   lists all seven Cloudflare IPv6 ranges alongside the fifteen IPv4 ones, so real users do not
   collapse into a shared bucket.
6. **Prove a write after every deploy**, from the public internet rather than from the host, and
   never from `/healthz` alone: `GET /healthz`, `GET /privacy`, `GET /delete-account`, then a real
   signup, register and send from a phone. A read-only database passes every check that only reads.

### 8.8 Hardening that shipped with the move

Four changes landed in the same commit as the deployment, and all four are load-bearing rather
than cosmetic:

- **The rate-limit bypass is closed** - item 5 above, measured at 15 signups against a limit of 10
  before the fix and `201` x10 then `429` after.
- **`/v1/photo` was limited per account**, 30 uploads per hour, because it was the one
  authenticated write with no limit at all. *Superseded on 28 July 2026*: the route no longer
  exists. The limiter and its constants went with it (PRD G24), so there is nothing left to tune -
  the remaining limiters are signup/login per IP and sends per account.
- **`FCMDeliveryError` is logged rather than discarded.** A delivery failure used to leave no
  trace anywhere, which is the same class of mistake as G17: a failure that looks identical to
  success from every surface anyone would check.
- **`google-auth` is pinned to 2.56.2**, so a rebuild cannot silently change the library that
  verifies Google ID tokens.

And one packaging defect found while writing the image: **`.dockerignore` did not list `secrets/`**,
so the Firebase service-account key was being copied into the build context. Fixed. Nothing is
pushed to a registry here, so the exposure was local to the build - but a private key in a build
context is one `COPY .` away from a layer, and layers are forever.

### 8.9 The edge was breaking the only no-app deletion route

Found 28 July 2026, and it is the kind of defect that only exists in production - the pages are
correct in the repository and correct when served from the container.

Cloudflare's **Scrape Shield / email obfuscation** rewrites any `mailto:` it finds in an HTML
response. On `/privacy` and `/delete-account` it replaced the contact address with a JavaScript-only
`/cdn-cgi/l/email-protection` link. To a browser this is invisible and works. To anything without
JavaScript - an automated policy re-check, a text-mode reviewer, `curl` - the anchor is dead and the
visible text is the literal string `[email protected]`.

That address is the **only** route to account deletion for somebody who cannot open the app, which
is precisely the person `/delete-account` exists for and precisely what Play re-checks after launch.

Fixed in the page source with Cloudflare's own documented per-page opt-out, which needs no dashboard
access and no API token:

```html
<!--email_off--><a href="mailto:...">...</a><!--/email_off-->
```

A backend test asserts the fence is present around the address on both pages. Two things follow
from this that are worth carrying forward:

- **Verify Play-facing pages the way Play does**, over the public hostname and without JavaScript.
  Fetching from inside the container, or in a browser, would have shown a working link in both
  cases.
- **The edge can rewrite the body.** Nothing else on these pages is JavaScript-dependent, so this
  was the only exposure - but the general lesson is that "the server returns the right bytes" and
  "the client receives the right bytes" are different claims when a proxy sits between them.

## 9. Before the first upload

1. Confirm the Play developer account type. A personal account created after Nov 2023 must run
   **closed testing with at least 12 testers opted in for 14 continuous days** before it can apply
   for production. This dominates the schedule; check it first.
2. **Push: done** (27 Jul 2026). `yo-theshop` now has a Firebase Android app for `hr.theshop.yo`
   (`1:747034506241:android:e5b34b298d59ea5e48bc00`) with both SHA-1s from section 4 registered,
   and `app/google-services.json` is fetched from it. It stays gitignored.
3. **Google sign-in: moved home, G16 CLOSED** (27 Jul 2026). Everything now lives in
   `yo-theshop`: the `google.com` provider is enabled, the web client is
   `747034506241-1ibqvftch4s7htnmfkspteiqs5h2jv9d`, and both Android clients
   (`...-0102hfni...` and `...-9gdal4ib...`) were auto-created from the two SHA-1s in section 4.
   `local.properties` `yoGoogleClientId` and the backend's `YO_GOOGLE_CLIENT_ID` both point at
   the new web client. `blocksurge-theshop` is no longer involved; only `hr.theshop.blocksurge`
   remains there, untouched. **Not yet re-verified on a handset** - no device was attached when
   the cutover finished.

   Four things made this harder than "repeat the registration", and all four are worth knowing:

   - **Billing was never the real gate.** `identityPlatform:initializeAuth` returns
     `BILLING_NOT_ENABLED`, but that is the *Identity Platform upgrade*. Classic Firebase Auth is
     free on Spark. What no API can do is create the Auth config singleton: `POST
     defaultSupportedIdpConfigs` and `PATCH .../config` both answer `CONFIGURATION_NOT_FOUND`
     until it exists. One console click (Authentication -> Get started) creates it for free.
   - **Android OAuth clients are globally unique on (package name, SHA-1).** While
     `blocksurge-theshop` held `hr.theshop.yo` + a fingerprint, `yo-theshop` could not mint its
     own: `409 ALREADY_EXISTS - Oauth client already exists in a different project`. So deleting
     from the borrowed project is a **precondition**, not the cleanup step it looks like.
   - **Nothing releases those clients except deleting them by hand.** Removing the SHA-1 from the
     Firebase app does not delete the client. Neither does `androidApps/...:remove` - the app went
     to `state: DELETED` and the 409 was unchanged. The IAP API returns `NOT_FOUND` because they
     are not IAP-brand clients, and no other Google API deletes an OAuth client. Cloud Console ->
     APIs & Services -> Credentials is the only route.
   - **Error ordering is not a dependency graph.** The create endpoint validates the request body
     before it looks up the parent, so an empty body yields `client_id cannot be empty` and looks
     like proof that the call would otherwise succeed. It is not; a complete body reveals the real
     `CONFIGURATION_NOT_FOUND` underneath.

   Still open, and unrelated to G16: the OAuth consent screen is `orgInternalOnly: true`, so only
   `the-shop.hr` accounts can complete sign-in. Console-only to change, and it blocks every real
   Play user until it does.

4. **Google sign-in: the original blocked state** (G21, historic). The project returns only a web OAuth client; Firebase
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
5. Re-verify `GOOGLE SIGN-IN` on a handset against the `yo-theshop` clients (section 9.3).
6. Flip the OAuth consent screen off `orgInternalOnly`, or no account outside `the-shop.hr` can
   sign in. **Still open**, and re-confirmed against the live IAP brands API on 28 Jul 2026:
   `projects/747034506241/brands/747034506241` still reads `orgInternalOnly: true`.
   Console only, and console-only *by construction* rather than by a missing permission -
   `projects.brands` exposes no `patch` or `update` method in either `v1` or `v1beta1`, and the
   field is documented as output-only. There is nothing to grant and no call to retry; do not
   spend time hunting for an API for this one.
7. **Move the backend: done 28 Jul 2026** (section 8). `yoBackendUrl` did **not** change - the
   hostname stayed `yo.the-shop.io`, which is the entire reason every installed APK kept working
   across the move with no rebuild and no Play update.
   An earlier note here said `/privacy` and `/delete-account` returned 401 in production; that
   was true only until PR #32 merged. Both serve 200 today.
   **The lesson attached to this item has now inverted, and the new form is the dangerous one.**
   It used to read: the launchd job runs `yo_server.py` straight out of the main checkout with no
   deploy step, so anything branch-only is simply absent from production. That is no longer how
   production is fed. This repository is a submodule of `lotar/claude` and is built into an image,
   so production is a snapshot taken at deploy time. The failure mode is therefore the opposite one:
   `yo-android` `main` can be *ahead* of the pinned commit, and the container keeps serving the older
   code perfectly happily. A merge to `main` is not a deploy. Redeploying is a separate, deliberate
   step - but unlike the hand-copied arrangement this replaced, the deployed commit is now recorded
   rather than assumed.

   **There is no copy any more, as of 29 July 2026.** This repository is a **git submodule** of
   `lotar/claude` at `modules/yo/src`, pinned to a commit, and the image is built from it.

   Deploying is now:

   ```sh
   # in lotar/claude
   cd modules/yo/src && git fetch && git checkout <sha> && cd -
   git add modules/yo/src && git commit    # the gitlink IS the deploy record
   # on the host
   cd /root/claude && git pull && git submodule update --init modules/yo/src
   cd /root/claude/modules/yo && docker compose -f compose.prod.yml up -d --build
   ```

   **What this replaced.** The instruction that used to end this item was impossible to follow. It
   said `backend/` was vendored "by an explicit file list", that a newly added file was therefore
   silently not vendored, and that "the vendored file list has to be checked whenever the backend
   gains a module". **There was no file list** - no script, no Makefile, no manifest, in either
   repository, only a prose line asserting one existed. The only machine-readable step was a
   `COPY backend/*.py ./` **glob**, which inverted both the hazard and the remedy: a new module was
   never skipped, and the whole risk sat in the manual copy, which no list would have guarded.
   Checking a list that does not exist is worse than checking nothing, because it reads like a
   control and stops the reader looking further.

   **The new failure mode, which is quieter than the one it replaced.** A plain `git pull` on the
   host moves the gitlink but leaves `src/` checked out at the *previous* commit. The rebuild then
   produces the previous release: image builds, container starts, healthcheck passes, and every
   check that does not hash the running code says the deploy worked. **`git submodule update
   --init` is mandatory**, and `git -C modules/yo/src log --oneline -1` on the host is the one-line
   check that catches it.

   **What to actually verify:** hash `/app/*.py` inside the running container against this
   repository. Done that way at the 29 July 2026 cutover - all six modules matched, the build
   context fell to 1.17 kB, and the database content digest was unchanged across the switch.
8. `./gradlew :app:testDebugUnitTest` and `python3 -m unittest discover` in `backend/`.
9. `./gradlew :app:bundleRelease`, upload the `.aab` and `mapping.txt`.
10. Fill in the data safety form (section 6) and the content rating questionnaire.

    The rating questionnaire asks about **user-generated content and whether users can interact**.
    Both are yes: a Yo carries a user-typed hashtag and a user-supplied link, and any signed-in
    account can send to any username it can name. The moderation answer is **blocking**, which is
    now actually reachable from the UI (PRD G26) — it was not until 28 July 2026, and answering
    "users can block" while shipping a build where they could not would have been false.

    Answer it knowing PRD **G30** is partly closed as of 28 July 2026. `POST /v1/send` still
    requires authentication but **not** friendship, so a stranger can still push a link into any
    guessable username's notification shade. What changed is that **the notification now names the
    destination host**, in punycode, so a homograph domain reads as `xn--…` rather than as the
    thing it is imitating - and a sender-supplied hashtag can no longer forge a second tap promise
    beside it, which it could until that day. It remains bounded to `http`/`https`, and blocking
    remains reactive by construction, working only after the first message lands. The stronger
    mitigation - requiring friendship to send - is **declined**, with reasoning in G30: one form of
    it is free for the attacker to defeat and the other is mutual consent under another name, which
    section 5 of the PRD rejects. That is the honest answer if a reviewer probes UGC safety.
11. **App access: provide reviewer credentials.** Play's *App access* declaration asks whether any
    part of the app is behind a login. Here all of it is - first launch is the sign-in screen, and
    an unauthenticated install can reach nothing but `/healthz`. So "all functionality is available
    without special access" is not an answer that can be given honestly, and answering it that way
    gets the review closed as unable-to-test.

    **The credentials cannot be a Google account.** The consent screen is still `orgInternalOnly`
    (item 6), so a reviewer's Google account cannot complete `CONTINUE WITH GOOGLE` at all - it is
    the one sign-in path guaranteed to fail for them. The answer is therefore a **username and
    password demo account**, which is exactly the path FR9 keeps as the default and which works for
    anybody.

    **The declaration text and the instructions box are already drafted** in `store/listing.md`,
    under *App access (for the Play reviewer)*, including the explicit warning not to try
    `CONTINUE WITH GOOGLE`. What does **not** exist is the account itself. That half is outstanding
    and cannot be done from this repository.

    **Pre-seed it with a friend, or the reviewer sees an empty screen.** A new account's home screen
    is deliberately blank until it adds somebody (FR9), so a reviewer who signs in and finds nothing
    has been shown a working app that looks broken. Seed the demo account with at least one
    friendship - ideally a second demo account with a registered device, so a Yo can actually be
    sent and the notification observed - and say so in the instructions box. `store/listing.md`
    records the two conditions that must both hold: the friendship must be seeded in **both**
    directions, because `list_friends` selects on `owner` only, and the friend must have registered a
    device at least once or the reviewer's Yo visibly fails with `recipient_unregistered`.

    Whatever is seeded, note it here when it is created, and remember it is a real account in the
    production database: it needs deleting after launch, the same way `GTEST` was (PRD §7.1).
