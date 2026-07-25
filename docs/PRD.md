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
"Yo" with body `"<sender> says Yo!"`, a notification sound, and vibration pattern
`[0, 150, 100, 150]`. Push-only — no polling (P2).
*Source: issue #3. See gap G1 (audio) and G2 (FCM credentials).*

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

These are the honest deltas between this document and the code as of `b8d6d07`.

**G1 — No signature "Yo" audio.** The original played a distinctive audio clip of a voice saying
"Yo"; `YoNotifier` uses `RingtoneManager.getDefaultUri(TYPE_NOTIFICATION)`, the device's generic
tone. FR2 is therefore *functionally* but not *characterfully* aligned. Closing this needs a bundled
audio asset set as the channel sound.

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
