# Yo backend

This service keeps the registered username-to-device-token directory and sends
data-only Firebase Cloud Messaging notifications. It uses Python's
`ThreadingHTTPServer` and SQLite; `google-auth` is the only package needed for
configured FCM delivery.

## Setup

Create a virtual environment and install the one runtime dependency:

```sh
cd backend
python3 -m venv .venv
. .venv/bin/activate
python3 -m pip install -r requirements.txt
```

Set the shared API key before starting the server:

```sh
export YO_SERVER_KEY='replace-with-a-long-random-value'
python3 yo_server.py --port 8790
```

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

Keep the service-account file outside the repository. If either Firebase
variable is missing, `/v1/send` remains available and returns:

```json
{"delivered":false,"reason":"fcm_not_configured"}
```

Messages use an FCM data-only payload so
`YoFirebaseMessagingService.onMessageReceived` remains the only notification
receive path. Android can delay data-only delivery while the app is backgrounded
or the device is in Doze mode. This is an accepted tradeoff for fully controlled
notification text, sound, and vibration.

## API

`GET /healthz` is unauthenticated. The app-facing `/v1/register`,
`/v1/friends`, and `/v1/send` routes require `X-Yo-Key: $YO_SERVER_KEY`.

Register or rotate a device token:

```sh
curl -X POST http://127.0.0.1:8790/v1/register \
  -H "X-Yo-Key: $YO_SERVER_KEY" \
  -H 'Content-Type: application/json' \
  -d '{"username":"me","fcm_token":"token"}'
```

List every registered username except the requester:

```sh
curl 'http://127.0.0.1:8790/v1/friends?username=me' \
  -H "X-Yo-Key: $YO_SERVER_KEY"
```

Send a Yo:

```sh
curl -X POST http://127.0.0.1:8790/v1/send \
  -H "X-Yo-Key: $YO_SERVER_KEY" \
  -H 'Content-Type: application/json' \
  -d '{"sender":"me","recipient":"friend"}'
```

## Broadcast API (third-party clients)

Provision a client and optionally pre-seed subscriptions for registered
usernames:

```sh
python3 register_client.py --client-id fedex --subscribe alice --subscribe bob
```

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
