# Yo (Android) — Product Requirements Document

Status: consolidated 2026-07-25 from GitHub issues #1–#7 and #11, then reconciled against the
historical record of the original app.
Repo: `mladen-lotar/yo-android` · Package: `com.example.yo` · Baseline commit: `b8d6d07`

This document is the single source of truth for *what this app is meant to be*. It exists because
the specification previously lived only in closed GitHub issues, so a reader of the checkout saw a
ten-line README and could not tell which absent features were deliberate.

Two rules govern every entry below:

1. **Historical fidelity.** This is a reimplementation of a real product. Features are justified by
   what the original Yo actually shipped, not by what would be nice to add. Dates and numbers come
   from the sources in §8.
2. **Honest status.** Every requirement is marked with what is actually true of the code today, not
   what was intended. Gaps are listed in §6, not hidden.

---

## 1. The original product (reference)

Yo was a minimalist notification app: one tap sent the word "Yo" to a chosen contact as a push
notification with sound. Its entire value was the absence of typing, composition, and attention
cost.

| Date | Event |
|---|---|
| 2014-04-01 | Launched on iOS and Android by Or Arbel, written in eight hours at the request of Moshe Hogeg (CEO, Mobli), who wanted a single-button app to notify his assistant or wife. Initially rejected by Apple "for being too simple". |
| 2014-04 → 05 | Released first to Mobli employees; 20,000 users within the first month. |
| 2014-06 | Traffic exploded after a Product Hunt posting. $1 million investment raised. |
| 2014-06-20 | Isaiah Turner hacked the app, able to retrieve any user's phone number and spoof Yos to any user. Arbel subsequently hired him. |
| 2014-07 | Windows Phone version. Additional $1.5 million raised at a $5–10 million valuation (~$2.5M total). Peaked at #4 overall on the iOS App Store and #1 in social networking. |
| 2014-08 | Added web links, hashtags, and user profiles. |
| 2014-10 | Added location sharing. |
| 2015-06 | Version 2.0: photo sharing, location "within one swipe and a tap", and contact groups so a user could Yo several people with one tap. |
| — | Public API let developers and brands broadcast Yos to subscribers. Used for World Cup goal alerts and a FedEx delivery-notification service; integrated with IFTTT. |
| 2016 | Company shut down; Arbel described the app as running "on autopilot". |
| 2018 | Arbel opened a Patreon to fund maintenance. |
| by 2019 | Over 3 million downloads and over 100 million Yos sent. |

The feature order matters: Yo grew by *small deliberate additions to one ping*, never by redesign.
This app's task breakdown (T2 → T7) intentionally follows that same order.

---

## 2. Goal and product principles

Reproduce the one-tap presence ping on modern Android, with the historical extensions, and nothing
else.

**P1 — No typing.** The primary action is a single button. Sending a Yo requires selecting a
recipient and one tap. Optional attachments must never become required steps.

**P2 — Battery-frugal (hard requirement, from the originating ticket).** Delivery is push-only.
No background polling, no periodic sync, no always-on foreground "listening" service, and no
continuous location listener. Location is a one-shot fetch at send time.

**P3 — DRY: one send pipeline.** `SendYoUseCase` is the single send path. Links, hashtags,
location, groups, and photos all *extend* it. Group send resolves to N calls through the same
pipeline, never a parallel implementation. Similarly `YoNotifier` is the only code that posts a
notification — both the local and the remote path terminate there.

**P4 — No runtime LLM in the app.** Local LLMs and a Fable-tier orchestrator were used to *build*
this app. Shipping an on-device or networked model at runtime would directly violate P2 and is
out of scope permanently.

**P5 — Deliberate smallness.** A missing feature is a decision. §5 records what was left out and
why, so absence is never mistaken for incompleteness.

---

## 3. Functional requirements (shipped)

All of the following are implemented and verified on a physical Galaxy S25 (SM_S931B) against the
live backend.

### FR1 — Send a Yo (historical: 2014-04-01 core)
Single "Yo" button sends to the selected friend. Persisted to Room as local history, rendered as
`"<sender> sent Yo to <recipient>"`. All sends route through `SendYoUseCase`.
*Source: issue #2.*

### FR2 — Push delivery (historical: core "text and audio notification")
Backend registers device tokens and fans a Yo out over FCM.
`YoFirebaseMessagingService` receives it; `YoNotifier` posts a high-importance notification titled
"Yo" with body `"<sender> says Yo!"`, the bundled spoken-"Yo" clip as its sound, and vibration
pattern `[0, 150, 100, 150]`. Push-only — no polling (P2).

The sound is `res/raw/yo.mp3`: a ~0.47s mono clip of a synthesized voice saying "Yo!", normalized to
−14 LUFS with the tail tapered. It is **our own** synthesis, not the original app's asset, and
`tools/generate-yo-sound.sh` regenerates it byte-for-byte so the binary is reproducible rather than
opaque. Because a notification channel's sound is immutable after creation, the channel id is
`yo_push_v2`; bumping it is what makes the new sound take effect on installs that already had the
old default-tone channel. On API 24–25 there are no channels, so the sound rides on the
notification itself — both paths are covered by `YoNotifierTest`.

*Source: issue #3. See gap G2 (FCM credentials).*

### FR3 — Links and hashtags (historical: 2014-08)
Optional link and hashtag fields on the send screen, carried as nullable fields on `YoMessage`
through the same pipeline. A user-supplied leading `#` is not double-prefixed when rendered.
*Source: issues #4, #11.*

### FR4 — Location attach (historical: 2014-10)
Optional "Attach my location" checkbox performs a **one-shot** `FusedLocationProviderClient`
fetch at send time only. Runtime permission is requested at use; denial does not crash or block a
plain Yo. Coroutine cancellation is rethrown, never swallowed.
*Source: issues #4, #11.*

### FR5 — Groups (historical: 2015-06 v2)
A Room-backed Group of member usernames. One tap Yos every member, implemented as recipient
resolution followed by N sends through the existing pipeline (P3).
*Source: issue #5.*

### FR6 — Photo attach (historical: 2015-06 v2)
Capture via camera or pick from gallery, JPEG-encoded with full 8-case EXIF orientation correction
(rotate/flip/transpose) and a long-edge cap of `UPLOAD_MAX_EDGE_PX = 1280`, uploaded to the backend
and fetched back byte-identically. Camera permission denial does not crash. No video and no
multi-photo — neither shipped in the original.
*Source: issue #7.*

### FR7 — Public broadcast API (historical: World Cup / FedEx / IFTTT integrations)
Authenticated `POST /v1/broadcast` lets a registered third-party client broadcast a Yo to its
subscribers, reusing FCM fan-out. Client credentials are `X-Yo-Client-Id` plus `X-Yo-Client-Key`,
verified against a stored hash. Backend-only; no Android changes.
*Source: issue #6.*

---

### FR8 — Invite contacts (historical: the INVITE row; "Find Friends")
`READ_CONTACTS` is requested on first launch alongside `POST_NOTIFICATIONS`, so the user answers
once rather than being interrupted later. The menu's **INVITE** band lists device contacts as colour
bands; tapping one opens the **system share sheet** (`ACTION_SEND`, `text/plain`) with an invite
message and an install link, so Viber, Messenger, WhatsApp, Signal, Telegram, SMS and email all
appear without Yo integrating with any of them.

This is not an invented screen: Yo's own menu render carried **INVITE** and **FIND FRIENDS** rows,
and the Windows Phone build had a dedicated uppercase INVITE band in the contact list itself.

A **search field** pins to the top of the sheet, because a real address book is hundreds of entries
and scrolling 89dp bands through it is unusable. Matching is deliberately forgiving: case-insensitive,
**diacritic-insensitive** (typing `marjanovic` finds `Marjanović`, `zeljko` finds `Željko` — without
this the filter is useless on a Croatian address book), matching any word rather than only the start
of a name, and requiring all terms of a multi-word query in any order. Digits are preserved, so a
contact stored as a bare number stays findable by typing part of it. A query that matches nothing
says so explicitly rather than looking like an empty address book.

The greeting is only used when the contact's first token actually reads like a name — at least two
characters, letters only. Live devices produced `Hey AS;Pizza/1,` for a business and `Hey 031,` for a
nameless contact stored as its phone number; those now open with plain "get Yo" instead.

Privacy is deliberate. `PhoneContact` carries **only an id and a display name** — no phone number,
no email. The recipient is chosen inside the messaging app after the chooser opens, so Yo never
reads, stores or transmits anyone's contact details, and nothing about the address book reaches the
backend. Declining the permission degrades honestly: the list is empty and a **SHARE LINK** band
still shares the invite without any contact.

The link points at `BuildConfig.YO_INVITE_URL` (default `https://yo.the-shop.io/install`,
overridable via `yoInviteUrl`). The backend serves that page publicly — an invitee has no shared key
by definition — styled from Yo's own values. It offers the APK only when `YO_APK_PATH` is set, and
says so plainly when it isn't rather than serving a broken download.
*Source: this task. Wording reuses Yo's own App Store copy.*

## 4. Technical requirements

**Android.** Kotlin, Jetpack Compose, Hilt, Room, Coroutines. minSdk 24, targetSdk 34, compileSdk
34. Gradle/AGP/Compose-BOM version pins reused from the sibling `anon-chat-android` project rather
than re-derived. Build tooling only — none of that project's crypto, Signal-protocol, or BLE-mesh
code is relevant here.

**Backend.** Python ≥ 3.10 (uses PEP-604 `X | Y` annotations — 3.9 fails to import),
`ThreadingHTTPServer` + SQLite, `google-auth` as the only runtime dependency and only for
configured FCM delivery. Endpoints: `/healthz`, `/v1/register`, `/v1/friends`, `/v1/send`,
`/v1/photo` (POST + GET), `/v1/broadcast`.

**Auth.** Every endpoint except `/healthz` requires the shared `X-Yo-Key`, compared with
`hmac.compare_digest`. Broadcast clients use per-client hashed keys. See gap G3.

**Configuration.** `yoBackendUrl` / `yoBackendKey` come from Gradle properties or the gitignored
`local.properties`, baked into `BuildConfig`. Defaults to `http://10.0.2.2:8790` for emulator use.
The Firebase Gradle plugin is applied only if `app/google-services.json` exists, so the app builds
and runs without Firebase configured.

**Cleartext policy.** `usesCleartextTraffic="true"` is set in the **debug** manifest only, so plain
HTTP works for local development while release builds require HTTPS.

**Testing.** 43 JVM unit tests (`testDebugUnitTest`), plus backend pytest for the server and the
broadcast client. See gap G6 on flakiness and G7 on absent CI.

---

### 4.1 Visual design system

Every value below is quoted from a primary source, not eyeballed from a screenshot. The sources, in
descending order of authority:

1. **Yo's own "Yo Branding Guidelines"**, embedded as an HTML block in their archived developer docs
   (`docs.justyo.co/docs/ui-design-guidelines`). It names and hex-specifies all ten colours and
   states the typeface, the text colour and the row height outright.
2. **Yo's press kit** (`yoapp.s3.amazonaws.com/yo/yomediakit.zip`, Last-Modified 2014-10-10) — the
   vector logo whose embedded font descriptor reads `Montserrat-Bold.ttf`, `FontWeight 700`.
3. **Archived store listings** — the real Google Play, App Store and Windows Phone screenshots, plus
   an unretouched real-device Android screenshot, measured with PIL.
4. **Archived `justyo.co` inline CSS**, which dogfooded the same system on the web.

**Palette** — ten colours, a curated subset of Flat UI Colors (2013) with the names abbreviated.
Roles are Yo's, not ours:

| Yo's name | Hex | Role |
|---|---|---|
| TURQUOISE | `#1ABC9C` | row 1 of the cycle |
| EMERALD | `#2ECC71` | row 2 |
| PETER | `#3498DB` | row 3 |
| ASPHALT | `#34495E` | row 4 |
| GREEN | `#16A085` | row 5 |
| SUNFLOWER | `#F1C40F` | row 6 |
| BELIZE | `#2980B9` | row 7 |
| WISTERIA | `#8E44AD` | row 8 |
| ALIZARIN | `#E74C3C` | **menu button only** — the one entry Yo annotated with a usage |
| AMETHYST | `#9B59B6` | "the main purple": background, app icon, sheets — never a row |

Row colour is a function of **position, not identity**. Yo's own menu screen — whose rows are not
contacts at all — uses the identical sequence, and the same five names appear in completely
different colours on iOS versus Android. So `colorForIndex(i) = Rows[i % 8]`, with friends, groups
and the trailing `+` row sharing one continuous cycle. Hashing a username to pick a colour would be
wrong.

**Typography** — Montserrat Bold (700), white `#FFFFFF`, uppercase, centred on both axes. Bundled as
a static 700 instance under the SIL OFL. Row labels are **42sp**, derived rather than guessed: the
measured cap height on Yo's press render is 29.66pt and Montserrat's capHeight is 0.700em, so
29.66 / 0.700 = 42.4sp — which puts the cap height at almost exactly one third of the row, matching
the original's proportions. A `letterSpacing` of `-0.03em` is a **correction, not a style choice**:
today's Montserrat is a 2017 redraw averaging ~3.7% wider than the 2014 cut (`Y` is +8.6%), and Yo
sized its type so the longest label cleared the screen by ~6.5pt, so without the correction long
labels overflow where they originally did not.

**Layout** — bands are a fixed **89dp** ("Row Height: 89px", verbatim; measured at 89/87/87/86px on
the real screenshot), full-bleed, butted directly together with single-pixel transitions: no
dividers, no gutters, no insets, no rounding, no shadows. Rows do not stretch; leftover space below
the last band stays Amethyst, which is why the original screen reads deliberately "unfinished".
There is **no chrome at all** — no app bar, title, search, tabs or section headers; the list starts
at the first pixel of the content area. The single floating control is a 48dp Alizarin circle inset
12dp from the bottom-right corner, carrying a white overflow glyph.

**Casing** — usernames are ALL CAPS (Yo's API documented the field as "UPPERCASE username"), but the
app's own name is mixed-case "Yo". The guidelines are emphatic: «Please note to casing of the name:
"Yo". Not YO, yo, Yo!, YO!.» Hence uppercase bands but a mixed-case "Yo" send button.

**App icon** — a flat, solid `#9B59B6` square with no wordmark, no glyph, no gradient. This is
measured, not inferred: the archived 300×300 Play icon contains exactly one unique colour across all
90,000 pixels. The purple square bearing a white "Yo" is the *press-kit logo*; mistaking it for the
app icon is the most common error in recreations.

**Notification** — title "Yo" over body "From LEO", read directly off the iOS lockscreen and Android
shade in Yo's own store screenshots.

#### Conflicts and deliberate deviations

Recorded rather than smoothed over:

- **Typeface conflict.** Yo's guidelines state "Font: Montserrat Bold" and their logo file embeds it,
  but pixel measurement of the shipped 2014 rows suggests those rendered in the *platform* font
  (Roboto/Helvetica Bold), with Montserrat only on the website. We follow Yo's stated spec, since it
  is first-party and is provably the wordmark's face. Reasonable people could ship Roboto Bold here.
- **Status bar.** The real v1 sat under an untinted black KitKat bar. We tint it Amethyst, because
  that was a platform-era limitation rather than a design decision — Yo's website was purple to the
  edges — and an untinted bar reads as a bug on a modern edge-to-edge phone.
- **Long labels.** Yo used one fixed size with no shrink-to-fit, tuned for short usernames. Group
  names are user-supplied and can be far longer, so labels over 9 characters step down one size and
  ellipsize rather than overflow.
- **Sheets.** Attachments, history and group creation use modern bottom sheets. The original reached
  its equivalents through swipes and a menu screen; the sheets are styled as colour bands to stay in
  the idiom, but they are a modern construct.
- **Cycle offset.** The 2014 build started the cycle at Turquoise and the 2016 build at Emerald. We
  start at Turquoise, matching v1.

## 5. Non-goals (deliberate omissions)

| Omitted | Historical counterpart | Why |
|---|---|---|
| User profiles | 2014-08 | Out of scope from the T1 breakdown; nothing in the product depends on it. |
| IFTTT integration | original API era | Explicitly deferred — no fleet infrastructure for it. The `/v1/broadcast` endpoint is the generic substitute. |
| iOS / Windows Phone | original platforms | Android-only reimplementation by request. |
| Video, multi-photo | never shipped in Yo | Would exceed the original feature set. |
| E2E encryption, mesh relay | never in Yo | Available in a sibling repo; importing it would contradict P5. |
| Runtime LLM features | n/a | Violates P2 — see P4. |
| Monetization | Yo raised funding, never monetized | No revenue requirement. |

---

## 6. Known gaps and risks

These are the honest deltas between this document and the code. Gap numbers are stable — a closed
gap keeps its number rather than being deleted, so earlier references stay valid.

**G1 — No signature "Yo" audio. — RESOLVED 2026-07-25.** The original played a distinctive clip of a
voice saying "Yo"; `YoNotifier` used the device's generic tone. Now bundles its own synthesized
spoken-"Yo" clip as the channel sound — see FR2.

**G2 — Real FCM push is unconfigured.** Without `google-services.json` and a backend
service-account key, no device obtains an FCM token, so no `/v1/register` occurs and `/v1/send`
returns `{"delivered":false,"reason":"fcm_not_configured"}`. Sending, history, friends, groups, and
photos all work; the actual push notification does not arrive. Blocked on interactive
`firebase login` + `gcloud auth login`.

**G3 — The shared key ships inside the APK.** `BuildConfig.YO_BACKEND_KEY` is embedded in the
binary, so anyone who extracts an installed APK obtains full API access: registering or overwriting
any username's FCM token, sending as any `sender`, and fetching stored photos. There is no rate
limiting. Acceptable for a controlled prototype; **not** acceptable for public multi-user use. A
real fix requires per-user credentials issued server-side.

**G4 — No real identity.** `YoIdentity.CURRENT_USERNAME` is the hardcoded constant `"me"`. There is
no sign-up, no authentication of the sender, and `/v1/send` accepts an arbitrary `sender` string.

**G5 — No friendship model.** `list_friends(requester)` returns *every registered device except
yourself*. There is no friend request, acceptance, or blocking. Any registered user is visible to
every other user.

**G6 — Load-sensitive tests.** The suite is green (43 tests, 0 failures) on an idle machine, but
`GroupRepositoryImplTest` (×2) and `BitmapPhotoEncoderTest` (×1) fail under heavy load with
`UncompletedCoroutinesError: ... not completing` after `runTest`'s 10-second default, caused by
Room/Robolectric blocking work inside the test coroutine. Re-run in isolation before treating a
failure as a regression.

**G7 — No CI.** There is no `.github/workflows`. Tests have only ever run on developer and agent
machines, which is why G6 went unnoticed.

---

## 7. Deployment state (as of 2026-07-25)

The backend runs on the operator's Mac as launchd agent `com.yo.backend` (KeepAlive), bound to
`127.0.0.1:8790`, database `backend/yo.db`, log `~/.ai-fleet/logs/yo-backend.log`. It must be
launched with Homebrew's `python3` (see §4).

It is publicly reachable at **`https://yo.the-shop.io`** via a CNAME on the existing `fleet-bridge`
Cloudflare tunnel. A dedicated hostname is required rather than a path on `alfred.the-shop.io`,
because the fleet bridge on `:8787` answers `/healthz` and blanket-rejects everything under
`/v1/*`; Yo's native paths would shadow live infrastructure. Cloudflared cannot rewrite paths, so a
`/yo/` prefix would require a code change.

Verified end-to-end on a physical S25 with USB port-forwarding removed: friends fetched and a Yo
sent over the public internet, `GET /v1/friends 200` and `POST /v1/send 200` server-side, with
history rendering the sent Yo. Gap G2 still applies to the push itself.

---

## 8. Sources

- Wikipedia, "Yo (app)" — dates, funding, user numbers, feature timeline, the 2014-06-20 hack, API
  integrations, 2016 shutdown, 2018 Patreon.
- `mladen-lotar/yo-android` issues #1–#7, #11 — product brief, per-feature scope, boundaries, and
  dependencies. Issue #1 carries the originating operator ticket.
- The code at `b8d6d07` — every "shipped" and "gap" claim above was read from or executed against
  it, not inferred from the issues.
