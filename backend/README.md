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

`GET /healthz` and the `/install` pages are unauthenticated — an invitee has no credential by
definition. `POST /v1/signup`, `POST /v1/login` and `POST /v1/google` are public because they *mint*
credentials; all three are rate limited per caller IP. Everything else needs
`Authorization: Bearer <token>` (`X-Yo-Token: <token>` is accepted too).

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

## Photo attachment

Upload a base64-encoded photo for a message:

```sh
curl -X POST http://127.0.0.1:8790/v1/photo \
  -H "Authorization: Bearer $YO_TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"message_id":"id","mime_type":"image/jpeg","data":"base64","recipient":"friend"}'
```

`message_id` is chosen by the caller, so uploads record an owner: only the uploader may overwrite
an id, and only the uploader and the named `recipient` may read it back. Everyone else gets 404.

Photo upload request bodies are capped at 2 MB, and the base64 `data` payload
is capped at approximately 1.4 MB.
The approximately 1.4 MB cap is measured in actual UTF-8-encoded bytes of the
`data` field, not Python string length.

Fetch the stored photo by message ID:

```sh
curl 'http://127.0.0.1:8790/v1/photo?message_id=id' \
  -H "Authorization: Bearer $YO_TOKEN"
```

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
  -d '{"message":"Package update"}'
```

## Tests

The suite uses a temporary SQLite file and a fake FCM client, so it performs no
network calls and does not require Firebase credentials:

```sh
python3 -m unittest discover -v
```
