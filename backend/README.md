# Yo backend

This service keeps the registered username-to-device-token directory and sends
data-only Firebase Cloud Messaging notifications. It uses Python's
`ThreadingHTTPServer` and SQLite; `google-auth` is the only package it needs, for
configured FCM delivery and for verifying Google ID tokens. Both features are off
until configured, and `google-auth` is imported lazily, so the server runs without
it installed.

## Setup

Create a virtual environment and install the one runtime dependency:

```sh
cd backend
python3 -m venv .venv
. .venv/bin/activate
python3 -m pip install -r requirements.txt
```

Start the server:

```sh
python3 yo_server.py --port 8790
```

There is no `YO_SERVER_KEY` any more. A single shared key that every install carried was the
security gap G3: extracting one APK gave the holder full access as any user. Callers now
authenticate with a per-account bearer token issued by `/v1/signup`.

Optional environment:

| Variable | Effect when unset |
|---|---|
| `YO_GOOGLE_CLIENT_ID` | `/v1/google` answers `503 google_not_configured`; everything else is unaffected. |
| `YO_APK_PATH` | `/install` says the download is not published yet. |
| `YO_CLOUDFLARE_RANGES` | Fails **closed**: `CF-Connecting-IP` is never trusted and every per-IP limiter keys on the socket peer. Safe, but behind a proxy that makes one global bucket for the whole internet, so set it whenever the service is published through Cloudflare. |

`YO_CLOUDFLARE_RANGES` is a comma-separated list of CIDRs. `CF-Connecting-IP` is honoured **only**
when the request's own peer falls inside one of them, and the peer is read from the **rightmost**
`X-Forwarded-For` entry — the one a reverse proxy authors from the TCP peer. Do not "simplify" this
to `X-Forwarded-For[0]`: Cloudflare forwards a client-supplied `X-Forwarded-For` unmodified, so
position [0] is attacker-chosen, while it *rejects* a client-supplied `CF-Connecting-IP` at the edge
and writes that header itself. Trusting `CF-Connecting-IP` unconditionally was a measured bypass —
15 signups against a limit of 10.

**The forwarded chain is read only from a private or loopback socket peer** — only a reverse proxy
on this host may author it. A direct caller arrives from a public address and has its header
ignored, and a non-IP entry falls back to the socket peer rather than becoming a rate-limit bucket of
its own. This was tightened on 2026-07-28: production was never spoofable, which was measured —
twelve forged `X-Forwarded-For` values landed in one bucket, ten `400`s then `429`, because Traefik
strips a client-supplied header and appends its own peer — but that safety lived entirely in the
proxy, while `_client_address`'s docstring claimed the function fell back to the peer on its own. It
does now. See `docs/PRD.md` G33.

**IPv6 buckets on the /64, IPv4 on the address.** The smallest allocation a residential IPv6
customer receives is a /64, so keying on the full address gave any IPv6 client an unlimited supply
of fresh buckets against the limiter that is simultaneously the signup-flood control, the login
brute-force control, and the only cost control on the 600,000-iteration PBKDF2 in `/v1/signup`. If
you set `YO_CLOUDFLARE_RANGES`, list the IPv6 ranges too: with IPv4-only ranges, `CF-Connecting-IP`
is not trusted for IPv6 peers and every IPv6 user collapses into one bucket keyed on Cloudflare's own
address. See `docs/PRD.md` G32.

The value has a twin at the edge: an allowlist on the proxy is what guarantees the peer is really
Cloudflare. They are one trust boundary written down twice, so **change them together**; widening
one alone either breaks routing or reopens the bypass.

## The access log

`log_message` is overridden to strip query-string **values** while keeping parameter names, so the
log records `DELETE /v1/block?username=` and not who was blocked. It is overridden there rather than
in `log_request` because `log_error` funnels through it too, and that is the path that echoes a
malformed request line back out of `send_error`.

The caller's IP is still written, by design, and the privacy policy discloses it. What it did not
disclose before 2026-07-28 was the *duration* or the *purpose*: the policy said IPs are kept
"briefly" and "to rate-limit sign-ups and sending", while the rate limiter holds one for 60–900
seconds and the access log held it until 30 MB of rotation — months, at this volume — for debugging,
which is not the stated purpose. See `docs/PRD.md` G31.

The SQLite registry defaults to `backend/yo.db`. Use
`--database /path/to/yo.db` to select another file. The server binds to
`127.0.0.1` by default; pass `--host 0.0.0.0` only when it must accept traffic
from another machine.

## Firebase

Live delivery also requires:

```sh
export YO_FIREBASE_SA_KEY='/absolute/path/to/service-account.json'
export YO_FIREBASE_PROJECT_ID='your-firebase-project-id'
```

Keep the service-account file outside the repository. The project must be the
same one the app's `google-services.json` names — a token minted for one project
cannot be targeted by a server authenticated as another. If either Firebase
variable is missing, `/v1/send` remains available and returns:

```json
{"delivered":false,"reason":"fcm_not_configured"}
```

Messages use an FCM data-only payload so
`YoFirebaseMessagingService.onMessageReceived` remains the only notification
receive path, which is what buys fully controlled notification text, sound, and
vibration. Data-only messages default to *normal* priority, which Doze may hold
until its next maintenance window, so the payload sets
`android.priority = high` — a Yo that arrives ten minutes late is not a Yo.

## API

`GET /healthz`, the `/install`, `/privacy` and `/delete-account` pages and the `/install/yo.apk`
download are unauthenticated — an invitee has no credential by definition. `POST /v1/signup`,
`POST /v1/login` and `POST /v1/google` are public because they *mint* credentials; all three are
rate limited per caller IP, and `POST /v1/broadcast` shares that same limiter. Everything else needs
`Authorization: Bearer <token>` (`X-Yo-Token: <token>` is accepted too).

`/install/yo.apk` is the only public route that serves a binary
(`application/vnd.android.package-archive`), and it is dispatched before the authentication gate. It
is served only when `YO_APK_PATH` is set. The trailing-slash forms `/install/`, `/privacy/` and
`/delete-account/` are accepted as aliases.

Create an account. Usernames are canonically uppercase, 2–32 of `A-Z`, `0-9`, `_`; passwords are
8–256 characters, stored as PBKDF2-HMAC-SHA256:

```sh
curl -X POST http://127.0.0.1:8790/v1/signup \
  -H 'Content-Type: application/json' \
  -d '{"username":"me","password":"correct horse battery"}'
# {"token":"...","username":"ME"}
export YO_TOKEN='the-token-printed-above'
```

`POST /v1/login` takes the same body and returns a fresh token without revoking existing ones, so
each device holds its own. `DELETE /v1/session` revokes just the token presented.

### Sign in with Google

Requires `YO_GOOGLE_CLIENT_ID` (the OAuth **web** client id, which the token's `aud` is pinned to)
and `google-auth` installed. Without either, the route answers `503` — `google_not_configured` or
`google_unavailable` respectively — and every other route is unaffected.

```sh
curl -X POST http://127.0.0.1:8790/v1/google \
  -H 'Content-Type: application/json' \
  -d '{"id_token":"<google id token>"}'
# 200 {"token":"...","username":"ME"}      already linked
# 404 {"error":"username_required"}        first time for this Google account
```

On `username_required`, post the **same** token again with a username; the account is created and
linked in one transaction and the reply is `201`:

```sh
curl -X POST http://127.0.0.1:8790/v1/google \
  -H 'Content-Type: application/json' \
  -d '{"id_token":"<the same token>","username":"me"}'
```

The link is keyed on Google's `sub`, never the email address — addresses are reassignable, and none
is stored. `409 username_taken` if the name belongs to someone else; once linked, a username in the
body is ignored, so a stale one cannot lock its owner out. Accounts created this way have no
password and cannot be logged into through `/v1/login` at all.

Register or rotate this device's FCM token. The account comes from the bearer token, so a caller
can only ever claim their own:

```sh
curl -X POST http://127.0.0.1:8790/v1/register \
  -H "Authorization: Bearer $YO_TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"fcm_token":"token"}'
```

Friends are explicit — the list holds only the people you added, never every registered device:

```sh
curl http://127.0.0.1:8790/v1/friends -H "Authorization: Bearer $YO_TOKEN"

curl -X POST http://127.0.0.1:8790/v1/friends \
  -H "Authorization: Bearer $YO_TOKEN" -H 'Content-Type: application/json' \
  -d '{"username":"friend"}'                       # 404 if no such account

curl -X DELETE 'http://127.0.0.1:8790/v1/friends?username=friend' \
  -H "Authorization: Bearer $YO_TOKEN"
```

Blocking is one-directional and also drops the person from your friends. A blocked sender receives
an ordinary `{"delivered":true}` and no push is sent, so a block never announces itself:

```sh
curl -X POST http://127.0.0.1:8790/v1/block \
  -H "Authorization: Bearer $YO_TOKEN" -H 'Content-Type: application/json' \
  -d '{"username":"nuisance"}'

curl -X DELETE 'http://127.0.0.1:8790/v1/block?username=nuisance' \
  -H "Authorization: Bearer $YO_TOKEN"
curl http://127.0.0.1:8790/v1/blocked -H "Authorization: Bearer $YO_TOKEN"
```

Send a Yo. There is no `sender` field: the server reads it from the token, so sending as somebody
else is not expressible. Sends are rate limited per account.

```sh
curl -X POST http://127.0.0.1:8790/v1/send \
  -H "Authorization: Bearer $YO_TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"recipient":"friend"}'
```

When a Yo cannot be delivered, `reason` says which of two different things went wrong:

| Reason | Meaning |
|---|---|
| `recipient_not_found` | No account by that name |
| `recipient_unregistered` | The account exists but has no device registered for push |

They were one string until 2026-07-26, which meant a real friend whose registration had failed was
reported to the sender as a nonexistent user.

**The distinction is diagnostic only.** The Android client reads the `delivered` boolean and
discards the rest of the body, `reason` included (`YoBackendApi.kt:264`), so this string reaches
`curl` and the server log and nothing a user sees. Worth having; not worth citing as a change to
how sending behaves. See `docs/PRD.md` G18.

A Yo may also carry an optional `link` and `hashtag`. Both are forwarded to the recipient in the
push and **neither is stored**:

```sh
curl -X POST http://127.0.0.1:8790/v1/send \
  -H "Authorization: Bearer $YO_TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"recipient":"friend","link":"https://example.com","hashtag":"lunch"}'
```

Both are validated, not sanitised: a non-string, or one over length, fails the whole request with a
400. Silently dropping an attachment would be indistinguishable to the sender from delivering it,
which is the failure this validation exists to prevent.

| Bound | Value | Measured in |
|---|---|---|
| `MAX_LINK_BYTES` | 2048 | **bytes** of UTF-8, not characters |
| `MAX_HASHTAG_BYTES` | 140 | **bytes** of UTF-8, not characters |

The unit is the point, and the constants were renamed from `..._LENGTH` to make it unmissable.
FCM's data payload caps at roughly 4096 **bytes**, so 2048 astral-plane characters would pass a
code-point check, be rejected by Google, and come back as a 502 no retry could ever clear.

There is a test asserting no table or column retains either value — the privacy policy and the Play
data-safety declaration both rest on that.

## Photo attachment — removed 2026-07-28

`POST /v1/photo` and `GET /v1/photo` no longer exist, and neither does the `photos` table. Both
verbs answer `404 {"error":"not_found"}` to an authenticated caller.

The upload worked; nothing could ever read it back. The Android app had no fetch method and the push
carried no message id to fetch with, so every stored photo was unreachable, unpruned, and described
by a privacy policy claiming it was readable by the recipient. Delivering it properly needs a
receive-side persistence layer the app deliberately does not have, so the feature was withdrawn
rather than finished. Full reasoning: `docs/PRD.md` G24.

If you are probing for these routes, **send a valid token**. Authentication runs before path
matching, so an unauthenticated request answers `401` for any unknown path and cannot tell a removed
route from a live one.

The `photos` table is still present on the existing production database — empty, and deliberately
not dropped; see `docs/PRD.md` §7.

## Broadcast API (third-party clients)

Provision a client and optionally pre-seed subscriptions for registered
usernames:

```sh
python3 register_client.py --client-id fedex --subscribe alice --subscribe bob
```

Subscription usernames are normalised to uppercase to match how accounts are stored, so
`--subscribe alice` and `--subscribe ALICE` are the same person.

The command prints the raw client key once. Store it securely and send it with
the client ID in the `X-Yo-Client-Key` and `X-Yo-Client-Id` headers:

```sh
export YO_CLIENT_KEY='the-key-printed-by-register-client'
curl -X POST http://127.0.0.1:8790/v1/broadcast \
  -H 'X-Yo-Client-Id: fedex' \
  -H "X-Yo-Client-Key: $YO_CLIENT_KEY" \
  -H 'Content-Type: application/json' \
  -d '{}'
```

**A broadcast carries no message, and sending one is now a 400.** This example used to read
`-d '{"message":"Package update"}'`, which documented a field that did not work: `message` was
validated as a string and then never passed to `send_yo`, so the caller was told their text had gone
to every subscriber when no subscriber could ever see it. A Yo is the whole content - the fan-out
sends the client id as the sender and nothing else - so the field is **refused** rather than
silently dropped. Delivering it would be a product change, not a fix.

The route also **shares the credential rate limiter** with `/v1/signup`, `/v1/login` and
`/v1/google`. It was the one credential-checking route with no limiter at all, so a client key could
be guessed at line rate while a password could not. It shares their bucket rather than getting its
own, so the limit cannot be dodged by moving between routes.

The fan-out is **serial and synchronous** - one HTTPS call to FCM per subscriber, inside the
request. That is fine at zero subscribers and is not fine at scale: see `docs/PRD.md` FR7 for the
three things (a cap or async fan-out, a user-facing unsubscribe, an idempotency key) that have to
exist before any client is provisioned.

## Tests

The suite uses a temporary SQLite file and a fake FCM client, so it performs no
network calls and does not require Firebase credentials:

```sh
python3 -m unittest discover -v
```
