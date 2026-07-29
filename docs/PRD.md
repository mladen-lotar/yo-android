# Yo (Android) — Product Requirements Document

Status: consolidated 2026-07-25 from GitHub issues #1–#7 and #11, then reconciled against the
historical record of the original app. Google sign-in (FR10) was added 2026-07-26 on top of the
accounts work that closed gaps G3–G7. Revised 2026-07-28: the backend moved off the laptop
onto real hosting (§7), §7.1/§7.2 were reconciled against the live Google projects — several
identity values recorded here had been stale since the package rename — and the pre-release audit
promoted G20's tail to G23 and opened G23–G30, one of which removes a shipped feature (photos, G24).
Repo: `mladen-lotar/yo-android` · Package: `hr.theshop.yo` (renamed from `com.example.yo`
2026-07-27 for the Play release) · Baseline commit: `e401a0c`
Release process and Play requirements: **[RELEASE.md](RELEASE.md)**.

**Last revised 2026-07-28, second pass the same day.** An audit re-derived every gap entry from the
code and the live systems rather than from this document, and found the *facts* mostly right and
several *reasons* wrong — which is the worse failure of the two, because a wrong fact gets caught
the next time somebody looks and a wrong reason is copied forward as settled. G7, G8, G9, G12, G18,
G19 and G24 are corrected in place: each now says what it used to claim and why that was wrong,
which is this document's established habit (see G6). G30 is partly closed — the host is now shown
in the notification body, and a charset rule had to ship with it — and G31, G32 and G33 are new.
§4 also gained a public endpoint it had always omitted, and §7's "explicit file list" turned out not
to exist.

Revised again 2026-07-29: §7 is rewritten because the arrangement it described is gone - the hand-maintained copy of `backend/` in `lotar/claude` was replaced by a pinned git submodule, so the drift this document spent two revisions describing can no longer be expressed. The store description dropped its Google sign-in clause for as long as G27 stays open.

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

**P3 — DRY: one send pipeline.** `SendYoUseCase` is the single send path. Links, hashtags, location
and groups all *extend* it. Group send resolves to N calls through the same pipeline, never a
parallel implementation. Similarly `YoNotifier` is the only code that posts a notification — both
the local and the remote path terminate there. (Photos extended it too until 2026-07-28; see G24.)

**P4 — No runtime LLM in the app.** Local LLMs and a Fable-tier orchestrator were used to *build*
this app. Shipping an on-device or networked model at runtime would directly violate P2 and is
out of scope permanently.

**P5 — Deliberate smallness.** A missing feature is a decision. §5 records what was left out and
why, so absence is never mistaken for incompleteness.

---

## 3. Functional requirements (shipped)

FR1–FR8 are implemented and verified on a physical Galaxy S25 (SM_S931B) against the live backend.
FR9 is implemented and covered by tests, but has **not** yet been exercised on the phone — it landed
while the device was disconnected, and it is a breaking change for the installed build (§7).

**FR6 is the exception: it was withdrawn on 2026-07-28.** It is kept below with its number and its
original text, marked as removed, because a requirement that was deliberately dropped is a decision
worth being able to find — deleting it would leave the numbering with a hole and the reasoning
nowhere. See G24.

### FR1 — Send a Yo (historical: 2014-04-01 core)
Single "Yo" button sends to the selected friend. Persisted to Room as local history, rendered as
`"<sender> sent Yo to <recipient>"`. All sends route through `SendYoUseCase`. The sender is the
signed-in account (FR9); the client does not name it and the server would not believe it if it did.
*Source: issue #2.*

### FR2 — Push delivery (historical: core "text and audio notification")
Backend registers device tokens and fans a Yo out over FCM.
`YoFirebaseMessagingService` receives it; `YoNotifier` posts a high-importance notification titled
"Yo" over the body `"From <SENDER>"`, the bundled spoken-"Yo" clip as its sound, and vibration
pattern `[0, 150, 100, 150]`. The wording is not invented — it is read off Yo's own store
screenshots, as recorded in §4.1. Push-only — no polling (P2).

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

**They are delivered to the recipient as of 2026-07-28.** Until then both were written to local
Room history and never transmitted — the same "shows as attached, arrives as nothing" mismatch that
G20 was about for location. They now ride `sendYo` → `POST /v1/send` → the FCM data payload → the
recipient's notification: a hashtag renders inline in the body, and a link makes the notification
tappable. See G20's closing note.

Two properties of that path are deliberate:

- **The backend rejects rather than drops.** A `link` or `hashtag` that is not a string, or is over
  length, fails the request with a 400. A silently-dropped attachment is indistinguishable to the
  sender from a delivered one, which is the exact failure this change exists to end — so the send
  fails loudly instead.
- **The bound is in bytes, not characters.** `MAX_LINK_BYTES = 2048` and `MAX_HASHTAG_BYTES = 140`
  are checked against `len(value.encode("utf-8"))`. FCM's data payload caps at roughly 4096 **bytes**,
  so 2048 astral-plane characters would have passed a code-point check, been rejected by Google, and
  surfaced as a 502 that no amount of retrying could ever clear — a failure that looks like an
  outage and is actually a validation bug. The constants were renamed from `..._LENGTH` so the unit
  is impossible to misread at the call site.
- **Neither is stored server-side.** The backend validates and forwards; no table or column holds
  either. There is a test asserting that, because it is the claim the privacy policy and the data
  safety form both rest on, and a schema change could quietly falsify it.
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

### FR6 — Photo attach — **WITHDRAWN 2026-07-28** (historical: 2015-06 v2)
**This requirement no longer describes the app.** The feature was removed rather than completed;
the full reasoning, and what finishing it would have cost, is in G24. The app has no camera or
gallery UI, no `CAMERA` permission, and the backend has no photo routes. §5 records the omission
alongside the other deliberate ones.

Original text, kept so the decision is legible: *Capture via camera or pick from gallery,
JPEG-encoded with full 8-case EXIF orientation correction (rotate/flip/transpose) and a long-edge
cap of `UPLOAD_MAX_EDGE_PX = 1280`, uploaded to the backend and fetched back byte-identically.
Camera permission denial does not crash. No video and no multi-photo — neither shipped in the
original.*

What that text never said, and what turned out to be the whole problem, is that "fetched back
byte-identically" was only ever true of the **uploader**. No recipient could fetch anything: the app
had no fetch method and the push carried no message id to fetch with.
*Source: issue #7.*

### FR7 — Public broadcast API (historical: World Cup / FedEx / IFTTT integrations)
Authenticated `POST /v1/broadcast` lets a registered third-party client broadcast a Yo to its
subscribers, reusing FCM fan-out. Client credentials are `X-Yo-Client-Id` plus `X-Yo-Client-Key`,
verified against a stored hash. Backend-only; no Android changes.

**Two changes shipped on 2026-07-28.**

- **It shares the credential rate limiter.** It was the **only credential-checking route with no
  limiter at all** — signup, login and `/v1/google` all had one — so a client key could be guessed
  at line rate while the human-facing credentials were held to ten attempts per fifteen minutes. It
  now shares their bucket rather than getting its own, deliberately: an attacker must not be able to
  dodge the limit by moving between routes.
- **A `message` field is now refused with a 400** rather than accepted and silently discarded. It
  was validated as a string and then **never passed to `send_yo`**, so a caller was told their text
  had gone to every subscriber when no subscriber could ever have seen it. That is G20's defect
  class in a route of its own. A Yo carries no content — the fan-out sends the client id as the
  sender and nothing else — so refusing is the correct fix; *delivering* the text would be a product
  change, and a change to what a Yo is.

**It exists and has never been used.** Re-checked live against the production database on
2026-07-28: `api_clients` and `subscriptions` are both **0 rows**. No client has ever been
provisioned, so no broadcast has ever been sent, and the whole surface is unexercised outside its
unit tests. That is worth knowing before trusting it, and worth weighing against the alternative of
removing it.

**Three things become mandatory before any client is ever provisioned**, and none of them exists
today:

1. **A subscriber cap, or an asynchronous fan-out.** The fan-out is serial — one HTTPS call to FCM
   per subscriber, inside the request — so it crosses Cloudflare's 100-second origin timeout at
   roughly **400–650 subscribers**. Past that the caller gets a timeout, retries, and the retry
   restarts the list from the beginning: duplicate pushes to everyone the first attempt already
   reached, which is the worst possible failure for a broadcast product.
2. **A user-facing unsubscribe.** There is none. Subscriptions are written only by the admin CLI
   (`register_client.py --subscribe`), and a subscriber's only exit is deleting their account.
3. **An idempotency key**, without which (1) has no safe retry and a duplicated broadcast is
   indistinguishable from two real ones.

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
overridable via `yoInviteUrl`). The backend serves that page publicly — an invitee has no credential
by definition — styled from Yo's own values. It offers the APK only when `YO_APK_PATH` is set, and
says so plainly when it isn't rather than serving a broken download.
*Source: this task. Wording reuses Yo's own App Store copy.*

### FR9 — Accounts, credentials and friends (closes G3, G4, G5)
Until 2026-07-26 there was no identity at all: every install called itself `"me"`, every install
carried the same API key, and "friends" meant every device that had ever registered. Those were
gaps G3, G4 and G5, and they were a single problem wearing three hats — you cannot have a friend
list without accounts, and you cannot have per-user credentials without users.

**Sign-in.** First launch shows a two-band sign-in screen asking for a username and a password and
nothing else — no email, no phone verification — matching what Yo asked for. Usernames are
canonically uppercase, 2–32 characters of `A–Z`, `0–9`, `_`; Yo's own API documented the field as an
"UPPERCASE username". Passwords are 8–256 characters, stored as PBKDF2-HMAC-SHA256 at OWASP's
600,000-iteration floor, with the cost encoded alongside the hash so it can be raised later without
invalidating anyone. `hashlib` only: `google-auth` stays the single runtime dependency (§4).

**Credentials.** `POST /v1/signup` and `POST /v1/login` mint a bearer token, stored server-side as a
SHA-256 hash. Every other `/v1` route requires it and derives the caller's identity from it, so
`sender` and `username` request fields are simply gone — sending as somebody else, or pointing
another account's push notifications at your device, are no longer expressible requests rather than
merely forbidden ones. Login is per device: a second login does not revoke the first, and
`DELETE /v1/session` revokes only the token presented. A wrong password and an unknown username
return byte-identical 401s, so the endpoint cannot be used to enumerate who exists.

Signup and login must be public — an invitee has no credential by definition — so they, not the old
shared key, are now the abuse surface. Both are rate limited per caller IP (10 per 15 minutes) and
sends per account (60 per minute). The limiter cannot key on the socket, because the service is
published through Cloudflare and every socket reports the proxy: one global bucket for the entire
internet, turning a single abuser into a lockout for every user.

It therefore keys on `CF-Connecting-IP`, **but only when the request's peer is itself a Cloudflare
address** — the peer being the *rightmost* `X-Forwarded-For` entry, which the reverse proxy authors
from the TCP peer, checked against `YO_CLOUDFLARE_RANGES`. **That chain is read only when the socket
peer is private or loopback**, i.e. only when a proxy on this host authored it; a direct caller from
a public address has its header ignored entirely, and a non-IP entry falls back to the socket peer
rather than becoming a bucket of its own. See G33 for why that qualification is not decoration.
Anything else keys on the peer. **IPv6 keys on the /64, not the address** — see G32. Trusting
`CF-Connecting-IP` unconditionally, as this originally did, was a **measured bypass: 15 signups
against a limit of 10**, since any client may set that header directly. `X-Forwarded-For[0]` is not
a fix either — Cloudflare forwards a client-supplied `X-Forwarded-For` unmodified, so position [0]
is attacker-chosen, while it *rejects* a client-supplied `CF-Connecting-IP` at the edge and writes
that header itself. Empty ranges fail closed to the socket peer. See RELEASE.md §8.3 and §8.7 item
5: the `yo-cf-only` allowlist on the edge router and `YO_CLOUDFLARE_RANGES` in the application are
one trust boundary stated twice, and must change together.

**Friends.** `list_friends` returns the people you added, never the user table. Adding is
unilateral and needs no acceptance — that is how Yo worked, and why "Yo <USERNAME>" was something
people printed on posters. **Blocking**, not approval, is the control: it is one-directional, drops
the person from your list, and a blocked sender still receives an ordinary `{"delivered":true}` with
no push sent, because telling senders they are blocked turns a block into a notification for the
person who was blocked.

**That is correct here and does not generalise, which G9 originally got wrong.** Answering a lie is
right when *the sender is the adversary*, which is the whole premise of a block. It is wrong when
the sender is a friend whose recipient's registration failed — that person is not an attacker to be
starved of information, they are the one who most needs it, and the one best placed to tell the
recipient to open the app. Same response shape, opposite ethics; see G9.

The visible consequence is that a new account's home screen is **empty** until it adds somebody, so
the menu gained an **ADD FRIEND** band — the counterpart of Yo's own "FIND FRIENDS" row. Without it
this change would have shipped a blank app.

**Photos** were scoped by this work too — uploads recorded an owner and an optional recipient, only
those two could read one back, and only the owner could overwrite a `message_id`. That scoping is
gone with the routes it protected (G24, 2026-07-28); it is noted here only because the access-control
model above is otherwise described as covering every stored object, and photos were the one object
type that has since stopped existing.

**Blocking, removing a friend and unblocking are reachable from the UI as of 2026-07-28.** All three
were implemented, backed by these endpoints and covered by passing tests, and none of them was
called by any Composable — see G26. The control FR9 names as *the* control on unwanted Yos was, in
the shipped build, not a control the user could reach.
*Source: this task. Historical claims about uppercase usernames and unilateral adds follow Yo's own
API documentation and the well-documented spammability of the original.*

### FR10 — Sign in with Google (2026-07-26)
An addition to FR9, never a replacement: username-and-password sign-up and log-in are untouched and
remain the default path.

**What it is.** A third band, `CONTINUE WITH GOOGLE`, which opens the device's Google account picker
and signs in as whichever account is chosen. One Yo account is signed in at a time, exactly as
before; to use a different one, sign out and pick another. Choosing is the point — Credential
Manager is asked with `setFilterByAuthorizedAccounts(false)` and `setAutoSelectEnabled(false)`, so
every account on the device is offered every time rather than the app silently latching onto one.
Credential Manager, not the deprecated `GoogleSignInClient`.

**Why it is two steps the first time.** Google supplies a subject and an email; neither is a Yo
username, and the username is how friends address each other. So a Google account the backend has
never seen is answered `404 username_required`, the screen asks for a username, and the app posts
the *same* token back with the answer — Google is not consulted twice. Every later sign-in is one
tap and one round trip.

**Identity is keyed on Google's `sub`, not the email address.** An address is neither stable nor
permanently unique — a Workspace address can be reassigned to a different person — so keying on it
would eventually hand somebody another user's account. The email is not stored at all; the app has
no use for it, which is the same restraint applied to `PhoneContact` in FR8.

**An account created this way has no password.** It stores `"!"`, a value `hash_password` cannot
produce and `verify_password` rejects structurally rather than by a check — it does not split into
the four fields an encoded hash has. So `/v1/login` stays shut for these accounts by construction,
and remains indistinguishable from an unknown username.

**Verification is local.** `POST /v1/google` checks the token's signature, audience and expiry with
`google-auth` against Google's cached certificates, pinning `aud` to the deployment's own client id.
No call to the `tokeninfo` debugging endpoint, so a sign-in costs no extra round trip and does not
inherit that endpoint's rate limit. The transport is the existing urllib shim in `fcm_client`, which
is why `requests` still is not a dependency. The route is public — a new user has no credential by
definition — and shares the same 10-per-15-minute per-IP limiter as signup and login.

**Both halves are configuration-gated and dark by default.** With no OAuth client id the app omits
the band entirely rather than disabling it (a dead band in Yo's chromeless idiom is
indistinguishable from a live one) and the backend answers `503 google_not_configured`. A
deployment without `google-auth` installed answers `503 google_unavailable` instead of failing to
start. See §7 for the one console value needed to switch it on.
*Source: this task.*

## 4. Technical requirements

**Android.** Kotlin, Jetpack Compose, Hilt, Room, Coroutines. minSdk 24, targetSdk **36**,
compileSdk **36** — raised from 34 for the Play release, which is what forced mandatory
edge-to-edge and produced G22.
Gradle/AGP/Compose-BOM version pins reused from the sibling `anon-chat-android` project rather
than re-derived. Build tooling only — none of that project's crypto, Signal-protocol, or BLE-mesh
code is relevant here.

**Backend.** Python ≥ 3.10 (uses PEP-604 `X | Y` annotations — 3.9 fails to import),
`ThreadingHTTPServer` + SQLite, `google-auth` as the only runtime dependency — **pinned to 2.56.2**
since 2026-07-28, so a rebuild cannot silently change the library that verifies Google ID tokens —
used for configured FCM delivery and for verifying Google ID tokens (FR10). Endpoints: `/healthz`,
`/install`, **`/install/yo.apk`**, `/privacy`, `/delete-account`, `/v1/signup`, `/v1/login`,
`/v1/google`, `/v1/session` (DELETE), `/v1/register`, `/v1/friends` (GET/POST/DELETE), `/v1/block`
(POST/DELETE), `/v1/blocked`, `/v1/send`, `/v1/account` (DELETE), `/v1/broadcast`.

**`/install/yo.apk` was missing from this list until 2026-07-28**, and it is the one route whose
absence matters most: it is public, unauthenticated, dispatched **before** the `_authenticate()`
gate, and it serves `application/vnd.android.package-archive` — a **binary download**, handled by
`_handle_install_apk`. A section that exists to enumerate the public surface, and misses the only
public route that serves a binary, is exactly the sort of claim this document exists to make
trustworthy. It is served only when `YO_APK_PATH` is set, and says so plainly when it is not (FR8).

Three undocumented aliases while enumerating: `/install/`, `/privacy/` and `/delete-account/` are
each accepted alongside their slash-less forms.

`/v1/photo` (POST + GET) was removed on 2026-07-28 with the feature (G24). Both verbs now answer
`404 {"error":"not_found"}` to an authenticated caller. The tests that assert this deliberately send
a **valid token**, and one of them exists only to say why: authentication runs before path matching,
so an unauthenticated probe answers `401` for any unknown path and would pass identically against a
server still serving photos.

`/privacy` and `/delete-account` are public HTML pages, not API routes, and Play requires both to
stay reachable — it re-checks them after launch. `DELETE /v1/account` is the in-app half of the
same requirement.

`google-auth` is imported lazily, inside the verifier. A deployment that has not installed it
serves every other route normally and answers 503 on `/v1/google`, rather than refusing to start
over a dependency most installations never exercise.

Schema changes are additive — the only mechanism is `CREATE TABLE IF NOT EXISTS` plus a guarded
`ALTER TABLE ... ADD COLUMN`, so an existing database gains the new tables on next start and needs
no migration step.

**Auth.** Public: `/healthz`, the `/install`, `/privacy` and `/delete-account` pages, **the
`/install/yo.apk` download** (added to this partition 2026-07-28 — it was missing, and it is the
only public route that serves a binary), and `/v1/signup` + `/v1/login` + `/v1/google` (which mint
credentials and so cannot require one). Everything else requires a bearer token, resolved to an
account server-side. Broadcast clients keep their separate `X-Yo-Client-Id` / `X-Yo-Client-Key` pair
verified against a stored hash — that path is otherwise unchanged, though it now shares the
credential rate limiter (FR7). See FR9.

**Configuration.** `yoBackendUrl` and `yoInviteUrl` come from Gradle properties or the gitignored
`local.properties`, baked into `BuildConfig`. There are **no defaults at all** — a missing value
fails any task that produces an APK or a bundle, naming what is absent. An earlier revision of this
document said the backend URL "defaults to `http://10.0.2.2:8790` for emulator use"; that default
was removed precisely because it is the failure that hides itself, an APK that installs, launches,
looks healthy and silently reaches no server. See RELEASE.md §3 and the repository README, which
have said this correctly all along. There is deliberately **no** `yoBackendKey` and no
`YO_SERVER_KEY` — see FR9.
The Firebase Gradle plugin is applied only if `app/google-services.json` exists, so the app builds
and runs without Firebase configured.

**Cleartext policy.** `usesCleartextTraffic="true"` is set in the **debug** manifest only, so plain
HTTP works for local development while release builds require HTTPS. `allowBackup` is `false`, to
keep the session token out of cloud backups.

**Testing.** JVM unit tests via `./gradlew :app:testDebugUnitTest`; backend via
`python3 -m unittest discover` (unittest, not pytest). Both run on every push and pull request —
see §6 G7.

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
| Photo attachment | 2015-06 v2 | **Shipped, then withdrawn 2026-07-28 (G24).** It was upload-only — no recipient could ever fetch one — so it cost a `CAMERA` permission, unbounded unpruned storage, and a false claim in the live privacy policy, and bought nothing. Delivering it properly needs a receive-side persistence layer the app deliberately does not have. Reinstating it is a real project, not a revert. |
| Video, multi-photo | never shipped in Yo | Would exceed the original feature set. |
| E2E encryption, mesh relay | never in Yo | Available in a sibling repo; importing it would contradict P5. |
| Runtime LLM features | n/a | Violates P2 — see P4. |
| Monetization | Yo raised funding, never monetized | No revenue requirement. |
| Email / phone verification, password reset | Yo asked for a username and password only | Adding recovery means adding an email channel and an address to store; deliberately out of scope. A forgotten password means a new account. |
| Friend requests and acceptance | Yo added people unilaterally | Would contradict the original's whole social model. Blocking is the control instead — see FR9. |
| Several Yo accounts signed in at once | n/a | Considered and declined when FR10 was scoped. Google sign-in offers the device's account picker so you choose *which* account; one Yo account is signed in at a time, and switching means signing out. A switcher would also force the device's single FCM token to fan out across accounts. |

---

## 6. Known gaps and risks

These are the honest deltas between this document and the code. Gap numbers are stable — a closed
gap keeps its number rather than being deleted, so earlier references stay valid.

**G1 — No signature "Yo" audio. — RESOLVED 2026-07-25.** The original played a distinctive clip of a
voice saying "Yo"; `YoNotifier` used the device's generic tone. Now bundles its own synthesized
spoken-"Yo" clip as the channel sound — see FR2.

**G2 — Real FCM push is unconfigured. — RESOLVED 2026-07-26.** Until now no device obtained an FCM
token, so no `/v1/register` ever occurred and `/v1/send` answered
`{"delivered":false,"reason":"fcm_not_configured"}` — everything except the actual notification
worked. Both halves are now provisioned against project `yo-theshop` and a Yo has been delivered
to a physical device end to end; see §7.2 for the values and the walkthrough.

**G3 — The shared key ships inside the APK. — RESOLVED 2026-07-26.** `BuildConfig.YO_BACKEND_KEY`
was embedded in the binary, so extracting an installed APK granted full API access: registering or
overwriting any username's FCM token, sending as any `sender`, and fetching any stored photo. The
key is gone from the build entirely and credentials are per account, issued at sign-in and stored
hashed; rate limiting was added at the same time. See FR9, and G8/G9/G10 for what remains.

**G4 — No real identity. — RESOLVED 2026-07-26.** `YoIdentity.CURRENT_USERNAME` was the constant
`"me"`. There are now real accounts with passwords, and the sender is derived from the caller's
token rather than accepted from the request. See FR9.

**G5 — No friendship model. — RESOLVED 2026-07-26.** `list_friends` returned every registered
device. Friendships are now explicit and per account, with blocking. See FR9.

**G6 — Load-sensitive tests. — RESOLVED 2026-07-26.** The recorded diagnosis was wrong and is kept
here because the correction is the useful part. This entry previously blamed "Room/Robolectric
blocking work inside the test coroutine" that `runTest`'s virtual clock could not fast-forward. It
is not a virtual-clock problem at all: `runTest`'s 10 seconds is a real wall-clock budget, proved by
`runTest { Thread.sleep(12_000) }` failing identically to the off-dispatcher version — so moving
work onto the test scheduler could never have been sufficient on its own.

The true causes were dispatch starvation on Room's fixed four-thread `ArchTaskExecutor` pool (a
`SELECT` against an *empty* in-memory database failed to complete within 10s under load) and Room's
lazy open charging the one-time SQLite open and schema creation to whichever test ran first. Fixed
by giving Room the test dispatcher as its query and transaction executor, forcing the database open
in `@Before`, injecting a dispatcher into `BitmapPhotoEncoder` (a class since deleted with the photo
feature, G24), and moving fixture creation out of the timed block. Verified by reproduction, not assertion: 5/5 runs failed before the fix under 300
CPU spinners, 5/5 passed after at equal or heavier load, with no assertion changed.

**G7 — No CI. — RESOLVED 2026-07-26.** `.github/workflows/ci.yml` runs both suites on every push to
`main` and every pull request: JDK 17 + Gradle for `:app:testDebugUnitTest` and `:app:lintDebug`,
and Python 3.12 for the backend. Reports upload as an artifact on failure.

**Two things this entry said were wrong by 2026-07-28.** It said the Gradle job runs
`:app:assembleDebug`. It runs `:app:lintDebug` instead: producing an APK now requires real
deployment configuration — backend URL, invite URL, privacy URL, OAuth client id — which CI has no
business holding, and lint still merges the manifest, links resources and compiles everything, so
it covers what `assembleDebug` covered here and adds static analysis on top. And it said the
workflow "has never executed on a GitHub runner — the first push is its real test". It has: the
last ten runs are green, and the most recent run on `main` took 3m12s. Robolectric's runtime jar
download, the specific thing that sentence was worried about, has never been the failure it was
expected to be.

The gaps below were opened, or surfaced, by the work that closed G3–G7. They are recorded rather
than smoothed over: closing a security gap honestly means naming what it did *not* close.

**G8 — The session token is stored in the clear.** `SharedPreferencesSessionStore` writes it to
MODE_PRIVATE preferences. Other apps cannot read it, it sits in file-based-encrypted app storage,
and `allowBackup="false"` keeps it out of cloud backups — but a rooted device or a physical
extraction yields a working token. The verdict is still defer, which this entry already reached.
Its reasoning was wrong in two ways worth recording.

**The named remedy no longer exists.** This entry said "`EncryptedSharedPreferences` would close
this", which reads as a free option waiting to be taken. It is not available to take:
`androidx.security:security-crypto` 1.1.0 (released 2025-07-30) **deprecated every API in the
library**, as of 1.1.0-beta01, in favour of "existing platform APIs and direct use of Android
Keystore" — and it shipped **no drop-in replacement**. Adopting it today means adopting a
deprecated library, and doing the work properly means writing against the Keystore directly.

**And the cost was never just the dependency.** Three things it would buy, none of them recorded
before:

- Tink lands in the APK, for one string.
- A new crash-at-startup class. `SharedPreferencesSessionStore` is a `@Singleton` injected during
  Hilt's first injection, so a Keystore or keyset mismatch — the state a device-to-device migration
  produces — is not a failed read, it is an app that cannot start, recoverable only by uninstall.
  That happens **even with `allowBackup="false"`**, because migration transfers are not the backup
  path.
- All 5 tests in `SharedPreferencesSessionStoreTest` go, because Robolectric has no AndroidKeyStore.
  Trading a tested store for an untested one, to protect against an attacker who already has the
  device.

**The real mitigation for what G8 is actually about is G10, not encryption at rest.** The threat
here is a stolen token being valid forever. Encrypting it raises the cost of the theft; expiring it
bounds the damage of a theft that succeeded anyway. It also remains true, as this entry originally
noted, that none of this would have helped against the threat G3 was about, since a token only
exists after a real login.

**G9 — `/v1/send` is a device-registration oracle. — OPEN, and deliberately so as of 2026-07-28.**
Login was hardened so an unknown user and a wrong password are indistinguishable, but `send` still
answers 404 `recipient_unregistered` for an account with no registered device and 200 for one with
a device. An authenticated caller can therefore learn which accounts have a live install. Bounded —
account *existence* is already discoverable by design, since you must be able to add someone by
name, and `/v1/friends` and `/v1/block` say `no_such_user` outright.

**This entry used to prescribe the fix, and the prescription was wrong.** It read: "the
blocked-sender path shows the fix: return an indistinguishable success." Three reasons that is not
guidance to follow here.

- **It re-opens G25 for one of the exact cases G25 names.** Closing this at the response body means
  answering `delivered:true` for a Yo that was not delivered. G25 exists because the app used to
  claim delivery it had not confirmed, and "addressed to an account whose device never registered"
  is on its own list of the cases that looked like success and were not. The two fixes are
  individually reasonable and jointly incoherent: one of them has to lose, and it is not the one
  that keeps the user honestly informed.
- **It would not even work.** The deviceless path is two SQLite probes; the delivering path makes an
  HTTPS round trip to FCM. That is a 50–500ms timing side channel that survives any change to the
  response body, so the oracle would be closed on paper and open in practice — the worst of the
  three states.
- **It leaves a direct tell standing.** Folding only the deviceless case leaves the
  `502 fcm_delivery_failed` path, which is exactly what a stale token after an uninstall/reinstall
  produces, saying plainly that a device once existed.

**And the blocked-sender sentence needs amending where it stands, in FR9.** Lying to the sender is
correct there for a reason that does not generalise: for a block, *the sender is the adversary*, and
the whole point is to tell them nothing. For a friend whose registration failed, the sender is not
the adversary — they are the person who most needs to know, and the person best placed to tell the
recipient to open the app. Same response shape, opposite ethics.

Correcting the record on **G18** while it is in view: that change is **diagnostic only and has no
user-visible effect whatsoever**. `YoBackendApi.sendYo`
(`app/src/main/java/hr/theshop/yo/data/remote/YoBackendApi.kt:264`) returns
`response.isSuccessful && JSONObject(response.body).optBoolean("delivered", false)` — it reads the
`delivered` boolean and discards the rest of the body, `reason` included. So the string G18
carefully split is read by curl and by the server log and by nothing the user will ever see. It was
still worth doing; it is not worth citing as a change to how sending behaves.

**G10 — Tokens never expire.** The `tokens` table has a `created_at` that nothing reads. There is
no TTL, no "sign out everywhere", and no per-device labelling, so an exfiltrated token is valid
until that exact session calls `DELETE /v1/session`.

**G11 — Signup is open to the internet.** This is inherent to a public signup endpoint rather than
a defect, and it is a deliberate trade: the alternative was keeping a bootstrap secret in the APK,
which is precisely G3. Rate limiting is the only control, and it resets when the process restarts,
so it slows credential stuffing rather than preventing account creation at scale.

**G12 — CI has no style linter and no instrumentation tests.** This entry used to read "CI is unit
tests only … no ktlint/detekt, no `:app:lint`, and no instrumentation tests", and the middle clause
is false. `.github/workflows/ci.yml:51` runs `./gradlew :app:lintDebug --no-daemon`, added in
`b0f9b78` (PR #32), and it passes clean with **no baseline file and no `lint {}` block** suppressing
anything — so Android's own static analysis is enforced on every push and every pull request, and
has been for some time.

What is genuinely still missing is narrower than the old wording implied:

- **No ktlint and no detekt**, deliberately. Not one defect recorded in this document would have
  been caught by a style or complexity linter. G30's live defect below is the current example: a
  sender-authored hashtag interpolated into a notification body with no charset validation. That is
  a domain rule about what a string is allowed to say, not a code smell, and no formatter has an
  opinion about it.
- **No instrumentation tests**, which would need an emulator. This is the real gap, and it is the
  one the record keeps pointing at: G13, G22 and G23 each needed a real device or a real deployment
  to see, and none of the three is visible to lint or to a JVM suite by construction.

**G13 — Google sign-in works end to end. — RESOLVED 2026-07-26.** Proven on a physical S25 against
the production backend over the public internet: picker → `404 username_required` → username
claimed → `201` → `GET /v1/friends 200`, then log out and back in for `200` with no username asked.
See §7.1 for the run.

Getting there required diagnosing one thing the backend could never show. Credential Manager needs
**two** OAuth clients, not one: the Web client the app sends as `serverClientId` and the backend
pins `aud` to, *and* an **Android** client matching package + signing SHA-1. With only the web
client, `GetGoogleIdOption` fails in ~200ms with `cmsh: [28444]`, **shows no picker at all**, and
surfaces as `NoCredentialException` — which reads like "this device has no Google account" and is
not (the S25 had four). An empty `oauth_client` array in `google-services.json` is the cheap way to
spot this before ever touching a device.

Android OAuth clients have **no public API** — not gcloud, not the IAP API (which creates web
clients fine and is how ours was made), not the Firebase CLI, and `clientauthconfig.googleapis.com`
is not exposed. The only programmatic route is Firebase auto-creating the pair when an Android app
+ SHA-1 is registered in a project that already has Google enabled as a sign-in provider, and that
needs the project on billing (G15). See §7.1 for what was done instead and the cleanup it implies.

**G16 — RESOLVED 27 Jul 2026.** All OAuth clients now live in `yo-theshop`: the `google.com`
provider is enabled there, and both Android clients were auto-created from the release and debug
SHA-1s. `yoGoogleClientId` and `YO_GOOGLE_CLIENT_ID` point at `yo-theshop`'s web client, and
`blocksurge-theshop` retains only `hr.theshop.blocksurge`. Billing was never actually required —
that was the *Identity Platform* upgrade, not Firebase Auth. What did block it: Android OAuth
clients are globally unique on (package name, SHA-1), so blocksurge's had to be deleted **first**,
and nothing but a hand delete in Cloud Console releases them — not removing the SHA-1, not
`androidApps:remove`, not the IAP API. See `RELEASE.md` §9.3. Not yet re-verified on a handset.
The consent screen is still `orgInternalOnly`, which is a separate blocker for outside users.
Original text follows.

**G16 (historic) — the working OAuth clients live in the wrong project.** Because `yo-theshop` has no billing
and Firebase Auth would not initialize there, the Android app was registered in
`blocksurge-theshop` (which is billed and already had Google sign-in enabled), and Firebase
auto-created both clients there. Yo therefore borrows an unrelated project's OAuth identity. It
works and the consent sheet correctly reads "Sign in to Yo?", but the arrangement is wrong on the
merits: Yo's sign-in breaks if Block Surge's project is changed, and Yo's users appear under Block
Surge's consent screen. Correcting it means one Android OAuth client created by hand in
`yo-theshop` (package `com.example.yo`, SHA-1 in §7.1), then pointing `YO_GOOGLE_CLIENT_ID` and
`yoGoogleClientId` back at the `yo-theshop` web client. Nothing else changes; both values already
exist. The borrowed app entry can be removed with the Firebase Management API
(`androidApps:remove`), since this CLI version has no `apps:delete`.

**G15 — Firebase Auth cannot be initialized on this project without billing.** Not a defect in
Yo, but it explains the shape of the above: `identityPlatform:initializeAuth` answers
`BILLING_NOT_ENABLED`, and `defaultSupportedIdpConfigs` refuses to auto-create a client
(`client_id cannot be empty`). Everything here was therefore built without Firebase Auth, which is
the right architecture anyway — the backend verifies Google's token itself and owns its own
accounts, so a second account system would be redundant.

**G14 — A Google account has no second way in and cannot be unlinked.** The link is one Google
account to one Yo account, fixed at first sign-in. There is no route to attach a password to a
Google-created account, to attach a second Google account to an existing one, or to unlink. Losing
access to the Google account therefore means losing the Yo username, with no recovery path — the
same trade already accepted for forgotten passwords in §5, but worth naming separately because
here the credential is held by a third party.

**G17 — A device whose FCM registration fails was indistinguishable from one that worked. —
RESOLVED 2026-07-26.** `RegisterDeviceUseCase` wrapped every step in `runCatching` and returned
`false`: no retry, no log, nothing on screen. Not hypothetical — the S23's first attempt failed with
`SERVICE_NOT_AVAILABLE` while dozing (§7.2) and only succeeded on the next launch, looking perfectly
healthy throughout, the only symptom being that Yos sent to it vanished. Three things changed:

- **It retries.** Token retrieval now makes 3 attempts backing off 1s then 2s. Firebase logs "Won't
  retry the operation" and means it, and `onNewToken` fires on token *rotation* rather than on
  launch, so nothing else in the app would ever ask again.
- **It says which failure.** `DeviceRegistrationOutcome` replaces the `Boolean`, so `NotSignedIn`
  (nothing to register yet, not an error) is no longer conflated with `Failed`.
- **The user is told, and can act.** The home screen shows `NOT RECEIVING YOS - TAP TO RETRY` when
  registration failed. It names the consequence rather than the mechanism, and tapping asks again,
  which is the one thing that helps when the cause is transient.

A blank token now counts as a failure worth retrying too: registering `""` would have bound the
account to a token that can never receive anything, which reads as success everywhere downstream.

**G18 — `recipient_not_found` conflated "no such user" with "user has no device". — RESOLVED
2026-07-26.** `_handle_send` resolves the recipient account first, then looks up their FCM token; a
missing token returned `404 {"reason":"recipient_not_found"}`, so a real friend whose registration
had silently failed under G17 was reported to the sender as a nonexistent user — the same class of
mistake as the `NoCredentialException` copy fixed in G13, an error asserting a fact it had not
established. A registered-but-deviceless account now answers `recipient_unregistered`.

This discloses nothing new, which is why it does not worsen G9: `_handle_add_friend` and
`_handle_block` already answer `no_such_user` for names that do not exist, so any authenticated
caller could already enumerate accounts. The conflation bought no privacy, only a wrong message.

**Scope, corrected 2026-07-28: this is a diagnostic change and nothing more.** The Android client
never reads the string. See G9 for the detail and the line.

**G19 — the FCM token APIs the app is built on are deprecated. — STILL DEFERRED, for entirely
different reasons than this entry gave.** firebase-messaging 25.x marks
`FirebaseMessaging.getToken()`, `deleteToken()`, `send()` and
`FirebaseMessagingService.onNewToken()` deprecated in favour of `register()` / `unregister()` and
`onRegistered()` / `onUnregistered()`.

**The reason recorded here was false, and a wrong reason is self-perpetuating** — nobody re-derives
a decision that already has an argument attached, so it is worth saying plainly which argument was
wrong. This entry claimed that `register()` "returns no token and delivers it asynchronously to the
service instead, so registration stops being something the app can ask for and retry, and becomes
something it can only wait for", and that this "would rewrite the retry, the backoff and the
`NOT RECEIVING YOS` state added for G17". None of that holds. Checked by disassembling the cached
`firebase-messaging-25.1.1.aar` rather than by reading the release notes:

- `register()` returns a `Task<Void>` completed by a `TaskCompletionSource` whose exception table
  catches `IOException` — **structurally identical to `getToken()`**, which is the API the retry was
  built around. Failure is observable exactly as it is today.
- **Both funnel through the same `blockingRegister(boolean)`.** They are two façades over one
  code path, not two mechanisms.
- So the G17 retry and backoff transplant essentially unchanged. The value the old call returned is
  not what the retry loop keyed on; the failure was.

**The migration would in fact close a gap, not open one.** `onRegistered` fires on **every**
successful `register()`, including a cache hit, whereas `onNewToken` fires only on token rotation.
That launch-time silence is precisely what `RegisterDeviceUseCase` complains about in G17 — "nothing
else in the app would ever ask again" — so the deprecated API is the one with the hole.

**The real reasons to defer**, none of which this entry ever identified:

1. **It is a one-way manifest switch with no runtime fallback.** Both APIs check the
   `firebase_messaging_installation_id_enabled` meta-data flag, and **each throws
   `IllegalStateException` if it is set the other way**. There is no path that tries the new API and
   falls back to the old one, and that flag appears **nowhere in this repository today**. Migrating
   means committing the whole app to one API in one release.
2. **The stored identifier changes from an FCM token to the FID**, which rotates it for every
   existing install. Every device re-registers, and anything sent to the old identifier in the
   window is lost — for an app whose only surface is the notification, that is the failure mode that
   looks exactly like G17.
3. **The true V1 path wants Google Play services >= `261200000`** and silently falls back below
   that, so the behaviour on older handsets is not the behaviour under test on a current one.
4. **No removal version or date has been announced**, and Firebase's own documentation says both
   patterns are "fully co-supported".

The current path is the one proven end-to-end on a handset (§7.2), so it stays suppressed with a
comment for this release and is migrated deliberately afterwards — on the strength of (1) and (2),
which are real, and not on the strength of a retry rewrite that would never have been needed.

**G20 — "ATTACH LOCATION" attached nothing the recipient could see. — RESOLVED 2026-07-27,
verified on device.** `YoMessage` carried latitude and longitude and `MainViewModel.sendYo` filled
them from a real position fix, but `YoRemoteDeliveryPortImpl.deliver` sent only `recipient` (and,
at the time, the photo id — since removed, G24): the coordinates were written to local Room history
and never transmitted. The feature
was honest about the permission and dishonest about the product - the person receiving the Yo got
no location, while the sender was shown one attached.

The pair now travels `deliver` → `POST /v1/send` → FCM data payload → the recipient's notification,
whose tap target opens a map pinned on the sender. Three things about the fix are worth keeping:

- The notification previously had **no `contentIntent` at all**, so it was inert. Received Yos are
  never written to this device's Room history - `saveSent` is the only writer - which makes the
  notification the recipient's *only* surface for a shared location. Its body says
  `TAP TO OPEN MAP` for that reason; a body identical to a plain Yo gives no reason to tap, and the
  location is gone as soon as the shade is swiped.
- Coordinates are formatted with `Locale.ROOT` on both ends. The default locale on a Croatian
  handset renders 45.815 as `45,815`, and a comma is what separates latitude from longitude in a
  `geo:` URI - the pin would land somewhere else entirely, on the maintainers' own phones, while
  passing every en-US test.
- The `geo:` URI carries `?q=` rather than a bare `geo:lat,lng`, which only pans the map. `q=`
  is what drops the marker, and the parenthesised label is what names it.
- **The intent names Google Maps explicitly.** Left to the system, `ACTION_VIEW` on a `geo:` URI
  opened an "Open with" chooser on the test handset, where six applications claim the scheme
  (Maps, Waze, Uber, Bolt, myAudi, Zoom). `MapIntentFactory` sets the package when Maps is
  installed and degrades to the chooser, then to a browser, when it is not.

**The tail of this entry — `link` and `hashtag` — became G23 and is now closed.** It used to read
that both were written to Room and never sent, exactly as the location used to be, and that the
mismatch "should be closed **or the fields removed**". Both routes were taken, one per feature: link
and hashtag were closed by delivering them (G23), and the photo attachment was closed by removing it
(G24). The same sentence sanctioned both, and which one is right depends entirely on whether the
feature can be finished cheaply — it could for a 2 KB string riding an existing payload, and could
not for a binary needing a receive-side store the app does not have.

**G22 — targetSdk 36 forced edge-to-edge and the menu button fell under the navigation bar. —
FOUND AND FIXED ON DEVICE 2026-07-27.** Android 15 makes edge-to-edge mandatory for targetSdk 35+,
so raising the target from 34 changed where the app draws without changing a line of layout code.
`MainScreen` applied no window insets at all (`AuthScreen` already called `systemBarsPadding`), so
the list drew under the status bar and the 144px menu FAB ended at y=2304 on a 2340px screen with
the navigation bar covering everything below roughly y=2190. Measured on an S23: only a ~30px strip
of the button responded to touch. That button is the sole route to PRIVACY and DELETE ACCOUNT, both
of which Play requires to be reachable, so this was a release blocker that no unit test could see -
it needed a real handset. Fixed with vertical `systemBars` content padding on the list and a bottom
`windowInsetsPadding` on the button; the FAB now occupies y=2016-2160, clear of the bar, and a
centre tap opens the menu. Horizontal insets are deliberately not applied: the bands are full-bleed
and padding them would inset their colour from the screen edge.

**G21 — no Cloud project owns `hr.theshop.yo`. — RESOLVED 2026-07-27.** FCM: a Firebase Android app
for the renamed package in `yo-theshop`. Google sign-in: `yo-theshop` still cannot host it (no
billing → no Firebase Auth → Firebase never auto-creates the Android OAuth client), so the package
was also registered in **`blocksurge-theshop`**, which has Google sign-in enabled; adding the two
SHA-1s there made Firebase auto-create **two `client_type: 1` Android clients**, one per
fingerprint, alongside the existing web client `973904690282-a4dnbf8b…` the app and backend already
share. Proven on an S23 with the **release-signed** build: the Credential Manager picker opened -
no `cmsh:[28444]` - sign-in completed, and the home screen showed the account's friend band.
At the time this left G16 standing, with the OAuth clients in a borrowed project. **That is no
longer true**: G16 closed later the same day and every client now lives in `yo-theshop` (§7.1).
Original text follows.

**G21 (original) — no Cloud project owns `hr.theshop.yo`. — FCM HALF RESOLVED 2026-07-27.** A Firebase
Android app for `hr.theshop.yo` now exists in `yo-theshop`
(`1:747034506241:android:e5b34b298d59ea5e48bc00`) with **both** SHA-1 fingerprints registered —
the debug key `BC:E5:5B:00:…` and the new upload key `22:02:ED:E8:…` — and a real
`google-services.json` fetched from it. Push therefore works for the renamed package, and the
release build passes its configuration gate. Done entirely through the Firebase Management API
using the existing `firebase-adminsdk-fbsvc@yo-theshop` service-account key, so it needed no
interactive login.

**Google sign-in is still blocked.** The returned config carries only a `client_type: 3` (web)
OAuth client and no `client_type: 1` (Android) one, because Firebase only auto-creates the Android
client when Google sign-in is enabled on the project — and enabling it needs Firebase Auth, which
needs billing on `yo-theshop` (G15). So the app still points at the borrowed **blocksurge** web
client, which has no Android client for `hr.theshop.yo` either. Until one of the two is true —
billing on `yo-theshop`, or a new Android client registered for `hr.theshop.yo` + the release SHA-1
in whichever project holds the web client — `CONTINUE WITH GOOGLE` will fail with `cmsh:[28444]`
for the renamed package. There is no public API for creating an Android OAuth client; it is a
console action or a billing decision. Original text follows.

**G21 (original) — no Cloud project owns `hr.theshop.yo`.** The package rename invalidates both Google
integrations at once: `google-services.json` keys the FCM app on the package name, and an Android
OAuth client is registered as a (package, SHA-1) pair. Neither matches any more. The fix is the
same project that G16 already called for - one project owning both the FCM app and the OAuth
clients - now with `hr.theshop.yo` and with **both** signing fingerprints registered: the new
upload key (`22:02:ED:E8:…`) as well as the debug key (`BC:E5:5B:00:…`). Registering only the
debug key is the failure that works perfectly in development and breaks for every real user.
Blocked on `gcloud auth login` / `firebase login`; both CLI tokens are expired.

The gaps below were found by the pre-release audit on 2026-07-28 and by the adversarial review that
followed it. Five are resolved (G23, G24, G25, G26, G28). G27 cannot be closed from code at all and
G29 is deliberately deferred with reasons given. **G30 is now partly closed** — the first mitigation
it named shipped, along with a defect found while implementing it — and its trailing paragraph became
G31. G32 and G33 were opened and closed in the same pass and are recorded because the reasoning is
the useful part, not the diff.

**G23 — `link` and `hashtag` were local-only. — RESOLVED 2026-07-28.** Both were written to Room and
never transmitted: the sender saw an attachment, the recipient got a plain Yo. This is G20 exactly,
one attachment along — and it survived G20's fix because that fix added the *coordinates* to the
payload rather than asking what else the send screen offered that never left the device. Fixing one
instance of a defect class is not the same as fixing the class.

Both now travel `sendYo` → `POST /v1/send` → the FCM data payload → the recipient's notification. A
hashtag renders inline in the body; a link makes the notification tappable. FR3 has the validation
rules and the not-stored guarantee.

**The first fix did not actually work on any modern device, and that is the most useful part of this
entry.** `linkPendingIntent` gated on `resolveActivity`, and under `targetSdk 36` Android 11+
package visibility hides anything not on the automatic list — **browsers are not on it**. So
`resolveActivity` reported that nothing handles `https`, the link was never made tappable, and the
recipient got a plain Yo: G23 reproduced one layer below where G23 had just been fixed. The manifest
now declares a second `<queries>` intent for `https` + `BROWSABLE`, directly beneath the `geo:` one
added for G20 — the same root cause, the same remedy, twenty lines apart, missed anyway.

**No unit test could have caught it.** Robolectric does not enforce package-visibility filtering, so
the test suite resolves browsers happily and passes either way. This is the third defect in this
document that needed a real device or a real deployment to see (G13, G22, now G23), and the pattern
is worth naming: anything that depends on *what else is installed* or *what the platform hides* is
invisible to the JVM suite by construction.

Three smaller defects on the same path, all fixed:

- **A Yo with both a location and a link dropped the link from the notification entirely.** Location
  still wins the single `contentIntent` — a pin cannot be recovered later and a link usually can —
  but the body now reads `· LINK` so the recipient at least knows one arrived. Silently omitting it
  recreated, for the both-attached case, the exact mismatch this entry exists to remove.
- **`openableLink` now calls `normalizeScheme()`.** `IntentFilter` scheme matching is
  case-**sensitive** and expects lowercase, so `HTTPS://x` passed the http/https check and then
  resolved to nothing at all — validation and resolution disagreeing about the same string.
- **A bare domain is normalized.** `example.com` is what people actually type, and it carries no
  scheme, so it was transmitted and then discarded at the last step. `MainViewModel.normalizeLink`
  prepends `https://` **only when there is no scheme at all**, so a deliberate non-web scheme is
  still rejected later rather than quietly rewritten into a web one.

**The security rule on this path.** `YoNotifier.openableLink` opens only `http` and `https`, and
only with a non-blank host. The link is authored by whoever sent the Yo, so an unchecked
`ACTION_VIEW` would let any sender aim the recipient's tap at a `file://` path, a private
`content://` provider, or an `intent://` URI reaching a component never meant to be exported —
turning a notification tap into an attacker-chosen intent on someone else's device. Residual and
accepted: a sender-supplied `https://` link can still be captured by an installed app holding a
verified App Link for that host. That is inherent to `ACTION_VIEW`. What is *not* accepted is who
may send one at all — see G30.

**G24 — photo attachment was write-only. — RESOLVED 2026-07-28 BY REMOVAL.** This is the same shape
as G20: a feature honest about its permission and dishonest about its product. `POST /v1/photo`
stored the image correctly, and nothing could ever read it back — the app had **no fetch method at
all**, and the FCM payload carried no message id for a recipient to fetch *with*. So every photo
ever attached went into the database and stayed there, unread, unpruned and unreachable, while the
sender was shown an attachment they had apparently sent.

What it cost while it did nothing:

- a `CAMERA` permission and a camera `<uses-feature>`, both visible on the store listing;
- unbounded storage on a single non-WAL SQLite file that is also every account and friendship (§7),
  with no retention or per-owner quota — the rate limiter added on 2026-07-28 slowed the growth and
  did not bound it;
- a **false statement in the live privacy policy**, which said a photo was readable only by the
  sender and the person they sent it to. The second half of that sentence described an access path
  that did not exist.

**Why removal and not completion.** Delivering it properly is not a small fix. It needs, at minimum:
a receive-side persistence layer, which the app deliberately does not have — received Yos are never
written to this device (`saveSent` is the only writer) and the notification is the recipient's only
surface, the same constraint that shaped the G20 fix; a reordering so the upload completes *before*
the push rather than racing it; and the retention and per-owner quota work that was never scoped.
That is a feature project. Against it: the original Yo shipped photos in 2015, so historical
fidelity argues for keeping it — but P5 says a missing feature is a decision, and shipping a
permission plus a storage liability plus a false privacy claim in exchange for nothing is not a
defensible way to honour the history. G20's own text sanctioned this route explicitly: close the
mismatch *"or the fields removed"*.

Removed: the camera and gallery UI, `uploadPhoto`, `PhotoEncoder` and `BitmapPhotoEncoder`,
`decodeSampledBitmap`, `res/xml/file_paths.xml`, the `FileProvider` provider block, the `CAMERA`
permission and camera `<uses-feature>`, the `androidx.exifinterface` dependency, backend
`POST`/`GET /v1/photo`, `store_photo` and `get_photo`, creation of the `photos` table,
`PhotoRecord`, the `photo_limiter`, and `MAX_PHOTO_BYTES` / `PHOTO_ATTEMPTS` /
`PHOTO_WINDOW_SECONDS`.

**Deliberately kept: `YoEntity.photoUri`**, a nullable column that is now always null. Dropping it
changes the Room schema, and this database has **no migrations and no
`fallbackToDestructiveMigration`** — a version bump would throw on first open for every install that
already exists. Verified rather than assumed: `app/schemas/.../2.json` is byte-identical after a
full build, identityHash still `1536e7be5fe233a3141085fe9c550969`. A dead column is cheap; a crash
loop on upgrade is not.

**The schema bump is not the whole cost, and the omission changes the decision.** This paragraph
originally implied that once a migration mechanism exists, dropping the column is a line of SQL in
it. It is not, at this `minSdk`:

- **`minSdk 24` means the platform SQLite is 3.9**, and `ALTER TABLE ... DROP COLUMN` did not land
  until **3.35.0 (2021)**. On the oldest devices this app supports, the one-liner does not exist as
  a statement.
- So dropping it requires a **full table rebuild**: create the new table, `INSERT ... SELECT` the
  surviving columns across, drop the old, rename the new.
- **The failure modes differ in kind, and that is the part worth carrying.** A wrong `ADD COLUMN`
  crashes loudly at open — you find out on the first launch, from anybody. A wrong `INSERT ...
  SELECT` **succeeds** and silently corrupts user data: mismatched column order copies the wrong
  values into the right column names and nothing complains, ever.

**The deferral is genuinely free, which is the good news.** The rebuild SQL is version-independent:
it is byte-for-byte the same statement whether it runs folded into the next migration that has to be
written anyway, or on its own, later. Nothing is gained by doing it now and nothing is lost by not.

**The current Room version is 2**, not 1 — `app/src/main/java/hr/theshop/yo/data/local/YoDatabase.kt:8`
reads `version = 2`, and the exported schema is `app/schemas/.../2.json`. So the next bump is **2→3**,
not 1→2. Worth naming while looking: **there is no exported `1.json`**, because the 1→2 bump also
shipped with no migration. That is latent rather than harmless — but it is academic while the app is
unreleased and no install exists to be broken by it, and it becomes real the day one does.

**G25 — the send confirmation was unconditional. — RESOLVED 2026-07-28.** The band flashed `YO!` the
instant it was tapped, before the request had gone anywhere. A Yo that was rate-limited, addressed
to an account whose device never registered, dropped by a dead network, or handed to an unconfigured
FCM — which answers **HTTP 200 with `delivered:false`**, so even a 2xx check would have called it a
success — looked exactly like one that arrived.

`YoRepository.saveSent` returned `Unit`, and that was the bug in one word: there was nowhere to put
the truth. It now returns `YoSendOutcome { Delivered, NotDelivered, Unreachable }` — three cases
deliberately, not the server's `reason` string, because the app says the consequence and never the
mechanism.

Four things about the UI are deliberate:

- **A pending state.** The band shows `...` while in flight. A send is a round trip of up to 10s;
  without it, an honest flash shows nothing at all on a slow connection and the button reads as
  dead.
- **`YO!` only on confirmed delivery**, and `COULDN'T YO <NAME> - TAP TO RETRY` otherwise — in the
  same slot and the same idiom as the existing `NOT RECEIVING YOS - TAP TO RETRY` band, so there is
  one visual language for "this did not work, and here is the one action that helps".
- **The Room write still happens on failure**, deliberately. Link and hashtag exist only in that
  row, so discarding it would destroy what the user typed at exactly the moment they want to retry.
- **A group fan-out that did not reach everybody is not reported as delivered**, and cancellation is
  rethrown rather than reported as a failure — a cancelled coroutine is not a failed send.

**The retry this added could send to the wrong person, and that was worse than the bug it fixed.**
Two rapid taps put two sends in flight, and `lastSendAttempt` was a **single slot**: the later send
overwrote it, so a failure banner offered under Alice's label could re-issue Bob's send on tap. A
retry affordance that silently retargets is a worse outcome than the unconditional `YO!` this entry
started with — the user is now confidently told a specific thing happened to a specific person.
`runSend` now takes a monotonic generation token and only the newest send may publish an outcome,
and the failed attempt is stored **together with** its failure rather than in a parallel slot, so
the label and the action it retries cannot drift apart. `clearSendFailure()` was removed in the same
pass; nothing called it.

**G26 — BLOCK and REMOVE FRIEND were unreachable. — RESOLVED 2026-07-28.** Both were implemented,
backed by real endpoints, and covered by passing tests. No Composable called either. So the app
shipped with **no way for a user to stop somebody contacting them**, while FR9 and the privacy
policy both described blocking as *the* control on unwanted Yos — and Play expects exactly that
control in a user-to-user messaging app carrying user-generated content.

Worth naming the class of failure: every layer was green. The endpoint had tests, the client method
had tests, and nothing tested that anything called it. A feature can be fully implemented and
entirely absent.

Both are now on long-press of a friend band. And because a block with no undo is a one-way door,
**unblock was added at the same time**: `fetchBlocked()` and `unblock()` on the client API — the
server already had `GET /v1/blocked` and `DELETE /v1/block`, so this was client-side only — behind a
**BLOCKED** sheet in the menu that lists blocked accounts, tap to unblock.

`loadBlocked` was also rewritten to rethrow `CancellationException` instead of swallowing it in a
`runCatching` — the same anti-pattern `saveSent` was rewritten to avoid in G25, and it mattered most
here, on the one screen whose whole job is undoing a one-way door.

**G27 — the OAuth consent screen is `orgInternalOnly`. — STILL OPEN.** Only `the-shop.hr` accounts
can complete `CONTINUE WITH GOOGLE`, so every real Play user, **and every Play reviewer**, is locked
out of that sign-in path. Username and password are unaffected, which is why the App access
declaration must offer a password account (RELEASE.md §9, item 11).

This is **impossible by construction rather than a permissions gap**, which is worth stating plainly
so nobody spends another afternoon on it: `projects.brands` exposes no `patch` and no `update`
method in `v1` or in `v1beta1`, and the field is documented as **output only**. There is no call to
authorise and none to retry. Re-confirmed live on 2026-07-28 — `projects/747034506241/brands/747034506241`
still reads `orgInternalOnly: true`. Cloud Console is the only route.

**G28 — the 512x512 store icon was 24-bit and Play rejects that. — RESOLVED 2026-07-28.**
`tools/generate-store-assets.py` built it with `Image.new("RGB", ...)`; Play specifies the hi-res
icon as a 32-bit PNG and refuses a 24-bit one at upload. Changed to `"RGBA"`, regenerated, verified
`RGBA (512, 512)`.

The square is opaque either way, so **not one pixel changed appearance** — which is precisely why
this would have survived every visual review and failed at the upload dialog, after the bundle was
built and the listing was written.

The feature graphic on the next function **stays `RGB` deliberately**: Play specifies that one as
24-bit with no alpha, so making the two consistent would be the actual regression. There is now a
comment in the generator saying so, because the two lines sit twenty apart and read like an
oversight worth tidying.

**G29 — history still renders a failed send identically to a delivered one. — OPEN, deliberately
deferred.** G25 fixed the *transient* surface: the band shows `COULDN'T YO <NAME> - TAP TO RETRY`
and then the moment passes. The Room row it wrote is **permanent**, carries no delivery state, and
renders exactly like a Yo that arrived. So the surface that lasts is the one still lying, and it
outlives the one that tells the truth — which is arguably the worse half of the original defect.

It is deferred rather than overlooked, and the cost is the reason. A `delivered` column on
`YoEntity` bumps the Room schema, and this database has **no migrations and no
`fallbackToDestructiveMigration`** (see G24): closing this needs a hand-written `Migration`, a newly
exported schema, and a test that an existing database survives the upgrade. That is the exact work
G24 avoided by keeping a dead column, and doing it hurriedly before a first release trades a
cosmetic lie for a crash loop on upgrade. It should be the first thing done after the release, while
the schema is still trivial and almost nobody has data to migrate.

The row is written on failure on purpose, incidentally — see G25 — because `link` and `hashtag` live
only in that row and discarding it would destroy what the user typed at the moment they want to
retry. The fix is a column, not a deletion.

**G30 — anyone can send an attacker-controlled link to any username they can guess. — PARTLY CLOSED
2026-07-28; the send rule itself is still open.**
`POST /v1/send` requires **authentication but not friendship**, and signup is public (G11). So the
cost of pushing a link into a stranger's notification shade is one signup and one guessed username —
and usernames are guessable by design, since the whole social model is "Yo `<USERNAME>`" printed on
a poster (FR9). That has not changed.

`openableLink` confines the destination to `http`/`https` (G23), so this is not arbitrary-intent
territory. What remained was ordinary phishing with a trust advantage: the notification body said
`TAP TO OPEN LINK` and **never showed the host**, so the recipient tapped without seeing where they
were going, from a notification wearing a name they may recognise. **The body now names the host**,
which is the half that shipped.

Two cheap mitigations were named. **The first shipped on 2026-07-28. The second is declined for
now**, and the reasoning below is why.

**Shipped: the host is now in the notification body.**
`From ADA  ·  TAP TO OPEN example.com` instead of a bare `TAP TO OPEN LINK`. It converts a blind tap
into an informed one, which is most of the value and was correctly described as a string change.
Five things about it were not string changes.

**A live defect was found while implementing it, and it is the reason the two halves had to ship
together.** The hashtag was interpolated verbatim into the notification body with **no charset
validation anywhere** — not on the attach sheet, not in the ViewModel, and not in the backend's
`_optional_attachment`, which checked type and byte length and nothing else. So a sender could
attach the hashtag `x  ·  TAP TO OPEN paypal.com` and forge **a second, attacker-chosen tap
promise**, in someone else's notification shade, under a name they may recognise, using the app's
own separator and its own wording. Showing the real host while leaving that open would have shipped
a mitigation the attacker can simply rewrite: whichever host the app names honestly, the hashtag can
name a better one directly beside it. They go together or not at all. `displayHashtag` now strips
everything outside `[\p{L}\p{N}_-]` — `\p{L}` excludes format characters, so RTL overrides and
zero-width joiners go with the spaces.

**The exhaustive combination test that defends "at most one TAP TO OPEN" could never have caught
it**, and that is worth more than the fix. The test enumerated every coherent combination of link,
tappability and location — and **every single case passed `hashtag = null`**. It was exhaustive over
the axes somebody had thought of, which is the failure mode exhaustive tests have. It now crosses
**48 combinations**, including hostile hashtags, and asserts that no hashtag may name a destination
whatever the sender typed.

**The host is shown in its punycode form, deliberately.** `Uri.host` returns whatever the sender
wrote and does **no IDN conversion at all**, so a Cyrillic homograph of `example.com` comes back
looking exactly like `example.com` — and displaying it would have made the notification *more*
convincing than saying nothing. Converted, the same host reads `xn--exmple-4nf.com`, which is
visibly not the real thing. Showing the Unicode form would have been the bypass rather than the
mitigation.

**`IDN.toASCII` is not a sanitizer, and this was verified by execution rather than assumed.** On
JDK 17 it passes spaces, newlines and underscores straight through, and will happily emit them
*inside* an `xn--` label: `x  ·  TAP TO OPEN paypal.com` converts to
`xn--x    tap to open paypal-2cb.com` — a "host" containing the app's own separator and its own tap
wording, intact, after the conversion that was supposed to make it safe. Hence a strict `[a-z0-9.-]`
post-filter applied *after* conversion. That filter has a second benefit: Android's IDN is
ICU-backed and may not agree with the JDK's, so anything the two implementations disagree about
fails the filter and degrades to the host-less wording. The behaviour is device-independent by
construction rather than by hoping the two libraries match.

**Truncation is from the LEFT, keeping whole labels.** The attack is
`paypal.com.<sixty characters>.evil.com`. Right-truncation renders `paypal.com.aaaa…`, which names
the wrong party and looks authoritative doing it — strictly worse than showing no host at all.
Left-truncation keeps the labels that decide where the tap goes.

**Declined for now: "require friendship to send".**
The original entry called this "the stronger control and nearly free, since the friendship table
already exists". It is **two different features wearing one name, and only one of them is a
control**:

- **"Sender must have added recipient" is worth nothing.** Adding is unilateral and unmetered (FR9),
  so the attacker sends one extra `POST /v1/friends` first and is back exactly where they were. It
  costs one request and buys the defender nothing.
- **"Recipient must have added sender" is a real control** — and it is mutual consent under another
  name, which §5 explicitly rejects as contradicting the original's social model. It is the friend
  request, arrived at from the other direction.

It also **breaks the normal path**, which is the part that decides it. A user who added somebody
unilaterally — the ordinary case, the whole "Yo `<USERNAME>`" idiom — would tap their band, see the
delivered animation, and have nothing arrive. Forever, with no feedback possible, because feedback
is precisely the oracle this design forbids: telling the sender "they have not added you" is the
same disclosure a block is designed never to make. And it would silently invalidate the
reviewer-seeding instruction in RELEASE.md §9 item 11, which says to seed *a* friend — under this
rule a friendship seeded in one direction leaves the reviewer's Yo failing exactly as before.

Blocking remains the answer, with its known limit: it is reactive by construction and works only
after the first message has landed.

**G31 — the caller's IP is written to an access log by design. — RESOLVED 2026-07-28 by disclosure
and redaction.** This was the trailing paragraph of G30, and it was **over-read in one direction and
under-read in the other**.

The over-read: it said the log was "a record of who blocked whom". The **blocker's** username was
never in it, and could not have been — the actor is authenticated by a bearer *header*, and
`BaseHTTPRequestHandler` does not log headers. What the line actually recorded was who was
**blocked**, beside the **caller's IP**.

The under-read is the part that mattered, and it is not about usernames at all. The live privacy
policy made two claims the system broke regardless of who was named:

- **"briefly."** The rate limiter holds an IP for 60–900 seconds. The access log held it until
  30 MB of rotation, which at this volume is **months**.
- **"to rate-limit sign-ups and sending."** That is a stated purpose limitation. A debugging log is
  not that purpose, and a log that exists for a different reason than the one disclosed is the
  problem whether or not anything sensitive is in the line.

Fixed by overriding `log_message` to redact query-string **values** while keeping parameter names,
so the line stays useful for debugging and stops recording who was acted on. `log_message` is the
right override point rather than `log_request`: `log_error` funnels through it too, and that is the
path that echoes a malformed request line back out of `send_error`. The live privacy policy has
gained a sentence disclosing the access log, so the remaining fact — the caller's IP is written to a
rotating log and then discarded — is **disclosed rather than contradicted**, which is the state this
gap exists to reach.

**G32 — the credential rate limiter was free to bypass from any IPv6 client. — RESOLVED
2026-07-28.** The limiter keyed on the full client address. The smallest allocation a residential
IPv6 customer receives is a **/64 — 2^64 addresses** — so an IPv6 caller had an effectively
unlimited supply of fresh buckets and the limit was, for them, not a limit.

What that limiter is doing at once is why this matters more than a rate-limit bug usually does. It
is simultaneously **the signup-flood control, the login brute-force control, and the only cost
control on the 600,000-iteration PBKDF2 that `/v1/signup` runs** (FR9) — the last of which makes an
unmetered signup endpoint a CPU-exhaustion surface on a container capped at 0.5 CPU, not merely an
account-creation one.

`_limiter_key` now buckets IPv6 to its /64 and leaves IPv4 on the address, where one address is
roughly one host. Checked before shipping it, because the fix is only sound if the input is real:
**`YO_CLOUDFLARE_RANGES` in production already lists all seven of Cloudflare's IPv6 egress ranges**
alongside the fifteen IPv4 ones, so `CF-Connecting-IP` is genuinely trusted for IPv6 peers and the
key is the caller's own /64. Had those ranges been IPv4-only, every IPv6 user would have collapsed
into one bucket keyed on Cloudflare's own address — turning a bypass into a global lockout, which is
the failure FR9's limiter was designed around in the first place.

**G33 — the `X-Forwarded-For` trust boundary: an audit finding refuted, and a real one underneath
it. — RESOLVED 2026-07-28.** An audit claimed the rightmost `X-Forwarded-For` entry was trusted
unconditionally and that the limiter was therefore bypassable in production.

**That was measured against production on 2026-07-28 and refuted.** Twelve requests carrying twelve
different forged `X-Forwarded-For` values all landed in **one** bucket: ten `400`s and then `429`.
Traefik strips a client-supplied header and appends its own peer, so the forged chain never reached
the application as anything but Traefik's own address. (The probe used a deliberately invalid
password, because the limiter is consulted *before* validation — so it exercised the limiter fully
and created no accounts.)

**The real finding is underneath, and it is a documentation defect that became a code defect.**
Production's safety lived **entirely in the proxy**, while `_client_address`'s own docstring promised
something stronger: that if the `yo-cf-only` middleware were ever removed, "this falls back to the
peer rather than reopening the spoof". That was false. The `X-Forwarded-For` override ran
unconditionally, so removing the middleware — a Traefik label, one line, in a shared file — would
have reopened exactly the spoof the docstring promised it would not. A comment asserting a safety
property the code does not have is worse than no comment: it is the thing the next person reads
instead of the code.

The function now honours a forwarded chain **only when the socket peer is private or loopback**,
i.e. only when a reverse proxy on this host authored it. A direct caller reaches the origin from a
public address and has its header ignored, so the docstring's claim is true without Traefik. And a
non-IP forwarded entry now falls back to the socket peer rather than becoming a rate-limit key of
its own — otherwise a caller could mint unlimited buckets out of arbitrary strings, which is G32
again by a different route.

**G34 — a hashtag the server refuses failed the whole Yo, and the retry could never succeed. —
RESOLVED 2026-07-29.** G30's charset rule was added to the backend on 2026-07-28 and the matching
sanitiser was added to the *display* path in `YoNotifier`, but **not to the send path**. So the
client transmitted whatever was typed, and `world cup` — two words, on a keyboard with a space bar
— was rejected with a 400 that failed the entire Yo rather than the attachment. The band read
`COULDN'T YO <NAME> - TAP TO RETRY`, and G25's retry re-issued the identical rejected request
forever, so the one affordance offered was the one thing that could not work.

`MainViewModel.normalizeHashtag` now strips disallowed characters before sending, so `world cup`
travels as `worldcup`. The server rule is unchanged and unrelaxed — it is simply unreachable from
our own client, which is what defence in depth is supposed to mean. Validation the user cannot see,
on a field they cannot get right, is not a control.

Worth naming how it got in: the plan for G30 said *client sanitise and server reject*, and two of
the three pieces shipped. A partial implementation of a rule reads as complete right up until
somebody types the ordinary thing.

**G35 — the three failure rows can sit below the fold. — OPEN.** `COULDN'T LOAD FRIENDS`,
`NOT RECEIVING YOS` and `COULDN'T YO <NAME>` are all appended as items *after* every 89dp band in
the same `LazyColumn`. With roughly seven or more friends and groups they are off-screen on a normal
handset. The delivered flash is drawn on the band itself, so success is always visible and only
failure can scroll away — which quietly re-opens G25 for exactly the accounts that use the app most.
Pinning them above the bands, or floating them, is the fix; it is layout work that wants a device to
judge, and no unit test can see it (the same class as G22).

Recorded alongside it: **`COULDN'T LOAD FRIENDS` offers no retry**, unlike the push row and the send
row beside it, so it is a dead end whose only escape is adding a friend by name.

**G36 — a failed blocked-list fetch rendered as `NOBODY`. — RESOLVED 2026-07-29.** `loadBlocked`
kept the previous value on failure and the sheet drew `NOBODY` for an empty list, so a failed
request became an affirmative claim that you have blocked no one — on the one screen whose whole
purpose is the safety control FR9 names. `loadFriends` already distinguished a failed load from an
empty one, and that asymmetry is what identified this rather than any judgement about severity. The
sheet now reads `COULDN'T LOAD THIS LIST`, and the flag clears once the server answers.

---

## 7. Deployment state (as of 2026-07-28)

**The backend runs on a server, not on a laptop, since 2026-07-28.** It is a Docker container
`yo-backend` on the `the-shop` host, at `/root/claude/modules/yo`, where this repository is a git
**submodule** of **`lotar/claude`** — not `evh-claude` — fronted by that host's shared Traefik. The hostname
`https://yo.the-shop.io` did not change across the move, which is why every installed APK kept
working with no rebuild and no Play update.

| Piece | Value |
|---|---|
| Host | `the-shop`, `ssh root@46.225.53.158` |
| Repo / path | `lotar/claude`, `/root/claude/modules/yo` |
| Deploy | `docker compose -f compose.prod.yml up -d --build`, **run from that directory** |
| DNS | `yo.the-shop.io` → A `116.203.165.173` (Hetzner floating IP), proxied through Cloudflare |
| Database | `/root/claude/modules/yo/data/yo.db`, bind-mounted at `/data` |
| Backups | hourly to `/root/backups/yo` (7 days), plus a daily 07:30 pull to the laptop |

`46.225.53.158` is the DHCP-delivered primary address: correct for ssh, **wrong for DNS**. And the
deploy command's working directory is load-bearing — compose reads `.env` from the project
directory only, and the Traefik labels are `traefik.enable=${YO_TRAEFIK_ENABLE:-false}`, so the
same command run elsewhere produces a container that builds, starts, passes its healthcheck and is
simply not routed. **RELEASE.md §8 is the full record**: container hardening, the ownership trap
that cost real time, the cutover measurements, and the constraints any future host must satisfy.

Two properties of the deployment matter to anyone reading this document for product truth:

- **Exactly one replica, permanently.** The rate limiters are in-memory and the store is a non-WAL
  SQLite file, so a second replica halves every limit and invites `SQLITE_BUSY`. Horizontal scaling
  is not a configuration change here; it is a rewrite of FR9's limiter and the storage layer.
- **A merge to `main` is still not a deploy, but it is now impossible to be unsure what is deployed.**
  As of 2026-07-29 this repository is a **git submodule** of `lotar/claude`, at `modules/yo/src`,
  pinned to a commit. The image is built from that submodule. Deploying is: move the gitlink, land
  it, `git submodule update --init` on the host, rebuild.

  **What this replaced, and why it mattered.** Until then `backend/` was **copied by hand** into
  `lotar/claude`. Both this document and RELEASE.md §9 described that copy as running off "an
  explicit file list", and RELEASE.md told a future operator to "check the vendored file list
  whenever the backend gains a module". **There was no such list** — no script, no Makefile, no
  manifest, in either repository. The only machine-readable thing in the pipeline was that repo's
  `Dockerfile` doing `COPY backend/*.py ./`, **a glob**.

  That inverted the hazard the text warned about. A newly added module was *not* silently skipped —
  the glob picked up whatever reached the directory. The entire risk sat at the **manual copy step**,
  and the prescribed remedy pointed at nothing anybody could check. An instruction that cannot be
  followed is worse than none: it reads as a control and discharges the reader's attention without
  doing anything.

  The submodule removes the copy step altogether, so the drift stops being something to detect and
  becomes something that cannot be **expressed** — the deployed commit is the recorded commit, and
  `git -C modules/yo/src log -1` on the host answers "what is production built from" exactly.

  **It substitutes a quieter failure, which is worth naming rather than celebrating.** A plain
  `git pull` on the host moves the gitlink but leaves `src/` checked out at the *previous* commit,
  so a rebuild produces the previous release with a green healthcheck and passing checks.
  `git submodule update --init modules/yo/src` is mandatory, and hashing `/app/*.py` against this
  repository is what catches it. Verified that way at the 2026-07-29 cutover: all six modules inside
  the running container hash-match `main`.

**The `photos` table is still there on the production database, and is deliberately left alone.**
The code that created it is gone (G24) and a *fresh* database never gets one — there is a test
asserting that. The existing production file keeps the empty table because **nothing drops it**, and
that is the decision, not an oversight: production holds **0 photo rows**, so there is nothing to
clean up, and issuing a `DROP TABLE` would rewrite the file and change the content digest that the
deploy verification compares end to end (RELEASE.md §8.6). Trading a stable, checkable digest for
the removal of an empty table would be a bad exchange. If it is ever dropped, do it as its own
change with its own before/after digests, not folded into a code deploy.

### 7.0 How it used to run (historic, to 2026-07-28)

Kept because two entries below are dated against it. The backend ran on the operator's Mac as
launchd agent `com.yo.backend` (KeepAlive), bound to `127.0.0.1:8790`, database `backend/yo.db`,
log `~/.ai-fleet/logs/yo-backend.log`, published at `https://yo.the-shop.io` via a CNAME on the
`fleet-bridge` Cloudflare tunnel. A dedicated hostname was required rather than a path on
`alfred.the-shop.io`, because the fleet bridge on `:8787` answers `/healthz` and blanket-rejects
everything under `/v1/*`; Yo's native paths would have shadowed live infrastructure.

**FR9 was a breaking deployment** in that arrangement: the agent loaded `yo_server.py` straight out
of the working tree, so merging changed the running server as soon as it restarted, and the
previously installed APK — which sent `X-Yo-Key` and nothing else — got 401 on every route. Landing
required all three together: merge, `launchctl kickstart -k`, reinstall the APK.

The database carried over untouched (new tables are additive). Its two original rows were the
hand-seeded `Alice` / `Bob` demo pair with no accounts behind them, so they cannot be signed in as
and appear in nobody's friend list.

Verified end-to-end at the time on a physical S25 with USB port-forwarding removed: friends fetched
and a Yo sent over the public internet, `GET /v1/friends 200` and `POST /v1/send 200` server-side,
with history rendering the sent Yo.

### 7.1 Switching on Google sign-in (FR10)

FR10 is live. Nothing here is a secret — an OAuth client id is public by design.

**As provisioned, re-read from the live projects on 2026-07-28.** Everything is now in **one**
project, `yo-theshop` (`747034506241`). Push and Google sign-in share it; earlier revisions of this
document said sign-in "borrows" a different project, and that has not been true since G16 closed on
2026-07-27.

| Piece | Value |
|---|---|
| Project | `yo-theshop`, project number `747034506241` |
| **Live** web (server) client id | `747034506241-1ibqvftch4s7htnmfkspteiqs5h2jv9d.apps.googleusercontent.com` |
| Android OAuth client, **release** SHA-1 | `747034506241-0102hfni4imbn33rv8sqmmtp6sbropdp.apps.googleusercontent.com`, auto-created |
| Android OAuth client, **debug** SHA-1 | `747034506241-9gdal4ibn24m94kisa0dsrfk9taniuck.apps.googleusercontent.com`, auto-created |
| Firebase Android app (live) | `1:747034506241:android:e5b34b298d59ea5e48bc00`, package `hr.theshop.yo` |
| Registered SHA-1, release | `22:02:ED:E8:E1:B3:78:94:40:A7:52:23:F4:6E:E1:20:2D:DD:61:BA` (upload keystore) |
| Registered SHA-1, debug | `BC:E5:5B:00:AA:7E:68:4D:72:EF:B7:2F:53:AF:B3:97:20:F7:F8:88` |
| Backend runtime | the production image, `google-auth` pinned to 2.56.2 |
| Backend env | `YO_GOOGLE_CLIENT_ID` in `/root/claude/modules/yo/.env`, mode `0600` |
| App config | `yoGoogleClientId` in the gitignored `local.properties` |

**Both** fingerprints are registered, not just the debug one. Registering only debug is the failure
that works perfectly in development and breaks for every real user, and it is the reason the
release build could be driven on a handset at all (RELEASE.md §4).

Values that appear in older revisions and are **dead** — do not copy them forward:

| Dead value | Status |
|---|---|
| `973904690282-a4dnbf8b3gv1o9v0v6ts2phe9em4kg41…` (web, `blocksurge-theshop`) | no longer used by app or backend |
| `973904690282-c1l1eqe5veh16ialmru3apmdm74i0n7j…` (Android, `blocksurge-theshop`) | deleted by hand — see G16 |
| `747034506241-c56bjfe0hihuarel9rucuo7b4oogvbkg…` (web, `yo-theshop`) | the IAP-created client; **never became the live one** |
| `1:747034506241:android:2643dabcb1f28ae548bc00`, package `com.example.yo` | soft-**deleted** (`state: DELETED`) |

That `-c56bjfe0…` web client was created through the **IAP API**
(`iap.googleapis.com/v1/projects/{n}/brands` then `.../identityAwareProxyClients`) because Google
exposes no public API for ordinary OAuth clients. It verified real tokens correctly but was never
usable for sign-in on its own, since no Android client can be created alongside it without the
console — which is the whole of G13. When Firebase Auth was finally enabled it auto-created the
`-1ibqvftch…` web client, and that is the live one.

Still open on this project: the IAP brand `projects/747034506241/brands/747034506241` is
`orgInternalOnly: true`, re-confirmed live on 2026-07-28, so only `the-shop.hr` accounts can
complete sign-in. This is **console-only by construction, not a permissions gap** — `projects.brands`
exposes no `patch` or `update` method in `v1` or `v1beta1`, and the field is output-only. There is
no call to retry.

**Two launchd gotchas, historic but worth keeping**, from when the backend ran on the laptop (§7.0).
`launchctl kickstart -k` restarts the job but re-uses the *loaded* configuration, so plist edits are
silently ignored; use `launchctl bootout gui/$UID/com.yo.backend` followed by
`launchctl bootstrap gui/$UID ~/Library/LaunchAgents/com.yo.backend.plist`. And the interpreter had
to be the venv's, since Homebrew's Python is externally managed and will not accept `pip install`.
Neither applies to production any more — the container installs its dependencies at build time —
but both still apply to a scratch backend on a Mac.

App and backend must carry the identical client id. Out of step fails closed — a mismatched
audience is rejected as an invalid token, never accepted. That is not a claim from reading the
code: a real Google-signed token minted for a different audience was posted to production and came
back `401 invalid_google_token`.

**Verified against production** on 2026-07-26 with genuinely Google-signed ID tokens (minted by
impersonating service account `yo-e2e@yo-theshop.iam.gserviceaccount.com` with `--audiences` set to
the web client id, since a human account cannot mint a token for an arbitrary audience):

| Check | Result |
|---|---|
| First sign-in, no username | `404 username_required` |
| Same token + username | `201`, account `GTEST` created and linked |
| Issued bearer token | `GET /v1/friends` → `200 {"friends":[]}` |
| Returning sign-in, no username | `200 GTEST` |
| Junk username once linked | `200 GTEST` — ignored, owner not locked out |
| `POST /v1/login` as that account | never succeeds (401/400 for every password tried) |
| Token for another audience | `401 invalid_google_token` |

`GTEST` and its identity and tokens were deleted from the production database afterwards.

**Verified on the handset** on 2026-07-26, once the Android OAuth client existed (G13). Against a
scratch backend first, then repeated against production over the public internet:

| Step | Result |
|---|---|
| Tap `GOOGLE SIGN-IN` | Credential Manager sheet, "Sign in to Yo?" — the app's own name, not the borrowed project's |
| Choose account, Sign in | `POST /v1/google` → `404 username_required` |
| Type a username, IME Done | `201` — account created and linked, then `GET /v1/friends 200` |
| Signed-in home screen | Renders, friend list empty as FR9 requires for a new account |
| Menu | Reads `LOG OUT MLADEN` — the claimed username, end to end |
| Log out, tap `GOOGLE SIGN-IN` again | `POST /v1/google` → **`200`, no username asked**, then `GET /v1/friends 200` |
| Dismiss the sheet instead of signing in | Screen shows nothing at all, as designed |

Two tokens existed for the one account afterwards, one per sign-in — per-device tokens behaving as
FR9 specifies. The account `MLADEN` created during the production run is a real account and was
left in place; delete it if it is not wanted.

Both of those runs predate the package rename **and** the move of every OAuth client into
`yo-theshop`, so they exercised client ids that no longer exist. They are kept as the record of how
the flow behaves, not as evidence about the current configuration: **Google sign-in has not been
re-verified on a handset since the consolidation** (RELEASE.md §9, item 5).

---

### 7.2 Push delivery — provisioned 2026-07-26

Push runs on `yo-theshop` (project number `747034506241`) — **the same project Google sign-in uses**,
since G16 closed. Earlier revisions said sign-in borrowed a different project and that push and
sign-in "do not interact"; the second half was always true for a different reason and still is:
Credential Manager reads `BuildConfig.YO_GOOGLE_CLIENT_ID`, never `google-services.json`. What must
match is the **sender** — an FCM token minted for project A cannot be targeted by a server
authenticated as project B — so the app's `google-services.json` and the backend's service-account
key both have to come from `yo-theshop`. They now do, and so does the OAuth client, so there is
only one project to keep straight.

| Piece | Value |
|---|---|
| App id | `1:747034506241:android:e5b34b298d59ea5e48bc00`, package `hr.theshop.yo` |
| App id (superseded) | `1:747034506241:android:2643dabcb1f28ae548bc00`, package `com.example.yo` — soft-deleted |
| `app/google-services.json` | `firebase apps:sdkconfig ANDROID <app id> -P yo-theshop --out app/google-services.json` |
| `YO_FIREBASE_PROJECT_ID` | `yo-theshop` |
| Service-account key | for `firebase-adminsdk-fbsvc@yo-theshop.iam.gserviceaccount.com`. In production it is a compose **secret** at `/run/secrets/yo_firebase_sa`, never in the image or the repo |

`google-services.json` is gitignored and `app/build.gradle.kts` only applies the google-services
plugin when the file is present, so a fresh clone still builds — with the Firebase half dark. No
billing is involved; FCM is free and `fcm.googleapis.com` was already enabled.

**Verified on a physical S23 against production**, app backgrounded behind a locked screen — a real
push, not a foreground one:

| Step | Result |
|---|---|
| App launch, signed in | `POST /v1/register 200`, real FCM token stored for `MLADEN` |
| `POST /v1/send` | `{"delivered":true}` |
| Device, ~1s later | Notification posted: title `Yo`, text `From MLADEN` |
| Channel | `yo_push_v2`, importance 4, sound `android.resource://<package>/…`, vibration `0/150/100/150`, accent `0xff9b59b6` |

The sound URI carries the package name, so it read `android.resource://com.example.yo/…` on the run
above and reads **`android.resource://hr.theshop.yo/…`** on every build since the 2026-07-27 rename.
Re-verified on the release build on a handset that same day, `pkg=hr.theshop.yo` (RELEASE.md §4).

One bug was found and fixed in the course of this. `fcm_client.send_yo` sent a data-only message
with no priority, and FCM defaults those to *normal*, which Doze may hold until its next
maintenance window — for an app whose entire product is immediacy, the wrong default. The payload
now sets `android.priority = high`. It would not have shown up in this test: a screen-on, plugged-in
handset is never in Doze, so the defect would have shipped behind a green run.

A second observation, since fixed: the first token attempt failed with
`FirebaseMessaging: … java.io.IOException: SERVICE_NOT_AVAILABLE` while the handset was dozing, and
succeeded on the next launch with the screen awake. At the time `RegisterDeviceUseCase` swallowed
that and returned `false` with nothing surfaced, so an install whose registration never succeeded
looked identical to one that worked until somebody tried to Yo it. See G17, now resolved.

**Re-verified 2026-07-26 after the G17/G18 fixes**, same handset and production backend:

| Check | Result |
|---|---|
| `POST /v1/send` to `MLADEN` (registered) | `200 {"delivered":true}`, notification on the device |
| `POST /v1/send` to `LOTAR` (real account, no device) | `404 {"reason":"recipient_unregistered"}` |
| `POST /v1/send` to `NOBODY` (no such account) | `404 {"reason":"recipient_not_found"}` |

The `NOT RECEIVING YOS` row itself has **not** been seen on a screen: the S23 is pattern-locked, so
screencaps come back black, and provoking it would mean breaking registration on purpose. Its logic
is covered by unit tests on `MainViewModel` (fails → warns, succeeds → silent, signed out → silent,
retry clears it); the row's rendering is not.

**`FCMDeliveryError` is now logged (2026-07-28).** The message was previously discarded: a delivery
failure produced `{"delivered":false}` to the caller and left no trace on the server at all, so
there was nothing to read afterwards to find out *why* Firebase refused — an expired key, a wrong
project and a revoked token were indistinguishable in retrospect. This is the same class of defect
as G17, a failure that looks identical to success from every surface anyone would think to check.
The reason string now reaches the container log (`docker logs yo-backend`).

---

## 8. Sources

- Wikipedia, "Yo (app)" — dates, funding, user numbers, feature timeline, the 2014-06-20 hack, API
  integrations, 2016 shutdown, 2018 Patreon.
- `mladen-lotar/yo-android` issues #1–#7, #11 — product brief, per-feature scope, boundaries, and
  dependencies. Issue #1 carries the originating operator ticket.
- The code at `a7843b8` plus the FR9 work on top of it — every "shipped" and "gap" claim above was
  read from or executed against the code, not inferred from the issues. The G6 entry in particular
  records a reproduction, not a hypothesis.
