import argparse
import hashlib
import hmac
import json
import os
import threading
import time
from collections import defaultdict, deque
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any, Deque, Dict, Optional
from urllib.parse import parse_qs, urlsplit

import yo_auth
import yo_google
from fcm_client import FCMClient, FCMDeliveryError, FCMNotConfiguredError
from yo_db import YoDatabase


DEFAULT_PORT = 8790
DEFAULT_DATABASE = Path(__file__).with_name("yo.db")
MAX_BODY_BYTES = 2_000_000
MAX_PHOTO_BYTES = 1_400_000

# Signup and login are necessarily public - an invitee has no credentials by definition - so
# they are the abuse surface that replaces the old shared key. Sends are limited per account.
CREDENTIAL_ATTEMPTS = 10
CREDENTIAL_WINDOW_SECONDS = 900
SEND_ATTEMPTS = 60
SEND_WINDOW_SECONDS = 60

# How many allowed requests between sweeps of the rate limiter's idle keys.
_SWEEP_EVERY = 256


def _hash_client_key(raw_key: str) -> str:
    return hashlib.sha256(raw_key.encode("utf-8")).hexdigest()


class RateLimiter:
    """A fixed-capacity sliding window per key, held in memory.

    In-memory is the honest scope here: this is a single-process ThreadingHTTPServer, so there
    is no second instance to share state with. It resets on restart, which is acceptable for
    slowing credential stuffing and unacceptable as a billing control - it is only the former.
    """

    def __init__(self, limit: int, window_seconds: float, clock=time.monotonic):
        self._limit = limit
        self._window = window_seconds
        self._clock = clock
        self._hits: Dict[str, Deque[float]] = defaultdict(deque)
        self._sweep_counter = 0
        self._lock = threading.Lock()

    def allow(self, key: str) -> bool:
        now = self._clock()
        with self._lock:
            hits = self._hits[key]
            while hits and now - hits[0] >= self._window:
                hits.popleft()
            if len(hits) >= self._limit:
                return False
            hits.append(now)
            self._evict_idle(now)
            return True

    def _evict_idle(self, now: float) -> None:
        """Drop keys whose window has fully expired.

        Without this the dict is a slow leak: it is keyed on caller IP and nothing ever removes
        an entry, so it grows for the lifetime of the process - and since the key comes from a
        request header, an attacker could grow it as fast as they can send requests.
        """
        self._sweep_counter += 1
        if self._sweep_counter < _SWEEP_EVERY:
            return
        self._sweep_counter = 0
        stale = [
            key
            for key, hits in self._hits.items()
            if not hits or now - hits[-1] >= self._window
        ]
        for key in stale:
            del self._hits[key]


def _configured_apk_path() -> Optional[Path]:
    """The APK to hand out at /install/yo.apk, or None if none is configured."""
    configured = os.environ.get("YO_APK_PATH", "").strip()
    if not configured:
        return None
    path = Path(configured)
    return path if path.is_file() else None


# Styled from Yo's own values: Amethyst #9B59B6, Montserrat Bold, white centred type, the mixed-case
# "Yo" wordmark and the tagline "It's that simple." See docs/PRD.md section 4.1.
INSTALL_PAGE_TEMPLATE = """<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="theme-color" content="#9b59b6">
<title>Yo - It's that simple.</title>
<link rel="stylesheet" href="https://fonts.googleapis.com/css?family=Montserrat:700,400">
<style>
  html, body { height: 100%; margin: 0; }
  body {
    background: #9B59B6;
    font-family: 'Montserrat', arial, sans-serif;
    font-weight: 700;
    color: white;
    text-align: center;
    text-rendering: optimizeLegibility;
    display: flex; flex-direction: column;
    align-items: center; justify-content: center;
    padding: 24px;
    box-sizing: border-box;
  }
  h1 { font-size: 22vw; line-height: 1; margin: 0; letter-spacing: -0.04em; }
  h2 { font-size: 5vw; margin: 12px 0 36px; font-weight: 700; }
  .btn {
    display: inline-block; background: #1ABC9C; color: white;
    text-decoration: none; padding: 18px 34px; border: 0; border-radius: 5px;
    font-size: 18px; font-family: inherit;
  }
  .small { font-size: 13px; font-weight: 400; opacity: 0.85; max-width: 22em; }
  @media (min-width: 700px) { h1 { font-size: 150px; } h2 { font-size: 32px; } }
</style>
</head>
<body>
  <h1>Yo</h1>
  <h2>It's that simple.</h2>
  {{ACTION}}
</body>
</html>
"""


class BadRequestError(ValueError):
    pass


class YoHTTPServer(ThreadingHTTPServer):
    daemon_threads = True
    allow_reuse_address = True

    def __init__(
        self,
        server_address: tuple[str, int],
        database: YoDatabase,
        fcm_client: Any,
        password_iterations: int = yo_auth.DEFAULT_ITERATIONS,
        google_verifier: Optional[Any] = None,
    ):
        super().__init__(server_address, YoRequestHandler)
        self.database = database
        self.fcm_client = fcm_client
        self.password_iterations = password_iterations
        # None when no YO_GOOGLE_CLIENT_ID is configured, which keeps /v1/google answering 503
        # rather than pretending to verify tokens against an audience nobody set.
        self.google_verifier = google_verifier
        self.credential_limiter = RateLimiter(
            CREDENTIAL_ATTEMPTS,
            CREDENTIAL_WINDOW_SECONDS,
        )
        self.send_limiter = RateLimiter(SEND_ATTEMPTS, SEND_WINDOW_SECONDS)


class YoRequestHandler(BaseHTTPRequestHandler):
    server: YoHTTPServer

    def do_GET(self) -> None:
        parsed = urlsplit(self.path)
        if parsed.path == "/healthz":
            self._write_json(HTTPStatus.OK, {"ok": True})
            return
        # The install page is the target of shared invite links, so it has to be reachable by
        # someone who does not have the app and therefore has no shared key. It is static and
        # exposes no data.
        if parsed.path in ("/install", "/install/"):
            self._handle_install_page()
            return
        if parsed.path == "/install/yo.apk":
            self._handle_install_apk()
            return
        username = self._authenticate()
        if username is None:
            return
        if parsed.path == "/v1/friends":
            self._handle_friends(username)
            return
        if parsed.path == "/v1/blocked":
            self._handle_list_blocked(username)
            return
        if parsed.path == "/v1/photo":
            self._handle_photo_fetch(username, parsed.query)
            return
        self._write_json(HTTPStatus.NOT_FOUND, {"error": "not_found"})

    def do_DELETE(self) -> None:
        parsed = urlsplit(self.path)
        username = self._authenticate()
        if username is None:
            return
        try:
            if parsed.path == "/v1/session":
                self._handle_logout()
                return
            if parsed.path == "/v1/friends":
                self._handle_remove_friend(username, parsed.query)
                return
            if parsed.path == "/v1/block":
                self._handle_unblock(username, parsed.query)
                return
            if parsed.path == "/v1/account":
                self._handle_delete_account(username)
                return
            self._write_json(HTTPStatus.NOT_FOUND, {"error": "not_found"})
        except BadRequestError as error:
            self._write_json(
                HTTPStatus.BAD_REQUEST,
                {"error": "bad_request", "reason": str(error)},
            )

    def do_POST(self) -> None:
        parsed = urlsplit(self.path)
        # Signup and login mint credentials, so they cannot themselves require one.
        if parsed.path in ("/v1/signup", "/v1/login"):
            self._handle_credentials(parsed.path)
            return
        if parsed.path == "/v1/google":
            self._handle_google()
            return
        client_id: Optional[str] = None
        username: Optional[str] = None
        if parsed.path == "/v1/broadcast":
            client_id = self._authorize_client()
            if client_id is None:
                return
        else:
            username = self._authenticate()
            if username is None:
                return
        try:
            body = self._read_json_body()
            if client_id is not None:
                self._handle_broadcast(client_id, body)
                return
            if parsed.path == "/v1/friends":
                self._handle_add_friend(username, body)
                return
            if parsed.path == "/v1/block":
                self._handle_block(username, body)
                return
            if parsed.path == "/v1/register":
                self._handle_register(username, body)
                return
            if parsed.path == "/v1/send":
                self._handle_send(username, body)
                return
            if parsed.path == "/v1/photo":
                self._handle_photo_upload(username, body)
                return
            self._write_json(HTTPStatus.NOT_FOUND, {"error": "not_found"})
        except BadRequestError as error:
            self._write_json(
                HTTPStatus.BAD_REQUEST,
                {"error": "bad_request", "reason": str(error)},
            )

    def _authenticate(self) -> Optional[str]:
        """Resolve the caller from their bearer token, or answer 401 and return None.

        This is what closes gap G3: the caller's identity comes from a per-device token the
        server issued, so an attacker who extracts an APK gets the credentials of nobody.
        """
        token = self._bearer_token()
        if token:
            username = self.server.database.username_for_token(
                yo_auth.hash_token(token)
            )
            if username is not None:
                return username
        self._write_json(HTTPStatus.UNAUTHORIZED, {"error": "unauthorized"})
        return None

    def _bearer_token(self) -> str:
        header = self.headers.get("Authorization", "")
        if header.lower().startswith("bearer "):
            return header[7:].strip()
        return self.headers.get("X-Yo-Token", "").strip()

    def _client_address(self) -> str:
        """The caller's real IP, which is NOT the socket address.

        The server binds 127.0.0.1 and is published through a Cloudflare tunnel, so every
        socket reports 127.0.0.1. Keying a rate limiter on that would make one global bucket
        for the whole internet, turning any single abuser into a lockout for every user.
        Trusting these headers is only safe *because* the socket is loopback-only: a spoofed
        CF-Connecting-IP requires local access, which is already game over.
        """
        forwarded = self.headers.get("CF-Connecting-IP", "").strip()
        if forwarded:
            return forwarded
        chain = self.headers.get("X-Forwarded-For", "").strip()
        if chain:
            return chain.split(",")[0].strip()
        return self.client_address[0] if self.client_address else "unknown"

    def _handle_credentials(self, path: str) -> None:
        """POST /v1/signup and POST /v1/login - the only routes that mint a token."""
        if not self.server.credential_limiter.allow(self._client_address()):
            self._write_json(
                HTTPStatus.TOO_MANY_REQUESTS,
                {"error": "rate_limited"},
            )
            return
        try:
            body = self._read_json_body()
            username = yo_auth.validate_username(
                self._required_string(body, "username")
            )
            password = yo_auth.validate_password(body.get("password"))
        except BadRequestError as error:
            self._write_json(
                HTTPStatus.BAD_REQUEST,
                {"error": "bad_request", "reason": str(error)},
            )
            return
        except yo_auth.CredentialError as error:
            self._write_json(
                HTTPStatus.BAD_REQUEST,
                {"error": "bad_request", "reason": str(error)},
            )
            return

        if path == "/v1/signup":
            created = self.server.database.create_account(
                username,
                yo_auth.hash_password(password, self.server.password_iterations),
            )
            if not created:
                self._write_json(
                    HTTPStatus.CONFLICT,
                    {"error": "username_taken"},
                )
                return
            status = HTTPStatus.CREATED
        else:
            stored = self.server.database.get_password_hash(username)
            if not yo_auth.verify_password(password, stored):
                # Deliberately identical for an unknown user and a wrong password, so the
                # response cannot be used to enumerate which usernames exist.
                self._write_json(
                    HTTPStatus.UNAUTHORIZED,
                    {"error": "invalid_credentials"},
                )
                return
            status = HTTPStatus.OK

        self._issue_token(status, username)

    def _handle_google(self) -> None:
        """POST /v1/google - sign in with a Google ID token.

        Two steps by necessity rather than by choice. Google hands over a subject and an email;
        neither is a Yo username, and the username is how friends address each other. So a Google
        account nobody has seen before is answered with `username_required`, the app asks for one,
        and it posts the same token again. Every later sign-in is a single round trip.
        """
        verifier = self.server.google_verifier
        if verifier is None:
            self._write_json(
                HTTPStatus.SERVICE_UNAVAILABLE,
                {"error": "google_not_configured"},
            )
            return
        if not self.server.credential_limiter.allow(self._client_address()):
            self._write_json(HTTPStatus.TOO_MANY_REQUESTS, {"error": "rate_limited"})
            return
        try:
            body = self._read_json_body()
            raw_token = self._required_string(body, "id_token")
        except BadRequestError as error:
            self._write_json(
                HTTPStatus.BAD_REQUEST,
                {"error": "bad_request", "reason": str(error)},
            )
            return

        try:
            subject = verifier.subject_for(raw_token)
        except yo_google.GoogleAuthError:
            # The library's reason is not echoed back: it is derived from attacker-controlled
            # input and tells an honest caller nothing they can act on.
            self._write_json(
                HTTPStatus.UNAUTHORIZED,
                {"error": "invalid_google_token"},
            )
            return
        except yo_google.GoogleUnavailableError:
            self._write_json(
                HTTPStatus.SERVICE_UNAVAILABLE,
                {"error": "google_unavailable"},
            )
            return

        username = self.server.database.username_for_identity(
            yo_google.PROVIDER,
            subject,
        )
        if username is not None:
            self._issue_token(HTTPStatus.OK, username)
            return

        requested = body.get("username")
        if not isinstance(requested, str) or not requested.strip():
            self._write_json(HTTPStatus.NOT_FOUND, {"error": "username_required"})
            return
        try:
            username = yo_auth.validate_username(requested)
        except yo_auth.CredentialError as error:
            self._write_json(
                HTTPStatus.BAD_REQUEST,
                {"error": "bad_request", "reason": str(error)},
            )
            return

        created = self.server.database.create_linked_account(
            username,
            yo_google.PROVIDER,
            subject,
            yo_auth.UNUSABLE_PASSWORD_HASH,
        )
        if not created:
            # Either the name belongs to somebody else, or this Google account was linked by a
            # request that arrived while this one was verifying. Only the second can sign in.
            linked = self.server.database.username_for_identity(
                yo_google.PROVIDER,
                subject,
            )
            if linked is None:
                self._write_json(HTTPStatus.CONFLICT, {"error": "username_taken"})
                return
            self._issue_token(HTTPStatus.OK, linked)
            return
        self._issue_token(HTTPStatus.CREATED, username)

    def _issue_token(self, status: HTTPStatus, username: str) -> None:
        token = yo_auth.new_token()
        self.server.database.store_token(yo_auth.hash_token(token), username)
        self._write_json(status, {"token": token, "username": username})

    def _handle_logout(self) -> None:
        self.server.database.delete_token(yo_auth.hash_token(self._bearer_token()))
        self._write_json(HTTPStatus.OK, {"ended": True})

    def _handle_delete_account(self, username: str) -> None:
        """Erase the caller's account. Required by Google Play for any app with sign-up.

        The bearer token is the only authorisation asked for. A password would be a second
        factor for exactly one class of account and none at all for the other: accounts created
        through Google sign-in have no password, so requiring one would make them undeletable -
        the opposite of what the policy is for. The destructive confirmation lives in the app.

        Deleting is idempotent from the caller's side: a second attempt with a now-invalid token
        gets 401, which is indistinguishable from success and gives an interrupted client
        nothing dangerous to retry into.
        """
        deleted = self.server.database.delete_account(username)
        if not deleted:
            self._write_json(HTTPStatus.NOT_FOUND, {"error": "no_such_user"})
            return
        self._write_json(HTTPStatus.OK, {"deleted": True, "username": username})

    def _authorize_client(self) -> Optional[str]:
        client_id = self.headers.get("X-Yo-Client-Id", "")
        supplied_key = self.headers.get("X-Yo-Client-Key", "")
        stored_hash = self.server.database.get_api_key_hash(client_id)
        if (
            client_id
            and supplied_key
            and stored_hash is not None
            and hmac.compare_digest(
                _hash_client_key(supplied_key),
                stored_hash,
            )
        ):
            return client_id
        self._write_json(
            HTTPStatus.UNAUTHORIZED,
            {"error": "unauthorized"},
        )
        return None

    def _handle_register(self, username: str, body: Dict[str, Any]) -> None:
        """The username comes from the token, never the body - you may only claim your own."""
        fcm_token = self._required_string(body, "fcm_token")
        self.server.database.upsert_device(username, fcm_token)
        self._write_json(
            HTTPStatus.OK,
            {"registered": True, "username": username},
        )

    def _handle_friends(self, username: str) -> None:
        friends = self.server.database.list_friends(username)
        self._write_json(HTTPStatus.OK, {"friends": friends})

    def _handle_add_friend(self, username: str, body: Dict[str, Any]) -> None:
        friend = self._target_username(body)
        if friend == username:
            raise BadRequestError("you cannot add yourself")
        if not self.server.database.account_exists(friend):
            self._write_json(
                HTTPStatus.NOT_FOUND,
                {"error": "no_such_user"},
            )
            return
        self.server.database.add_friend(username, friend)
        self._write_json(HTTPStatus.OK, {"added": friend})

    def _handle_remove_friend(self, username: str, query: str) -> None:
        friend = self._query_username(query)
        self.server.database.remove_friend(username, friend)
        self._write_json(HTTPStatus.OK, {"removed": friend})

    def _handle_block(self, username: str, body: Dict[str, Any]) -> None:
        blocked = self._target_username(body)
        if blocked == username:
            raise BadRequestError("you cannot block yourself")
        # Refuse unknown names so an authenticated caller cannot stuff the blocks table with
        # arbitrary junk; there is no cap on its size.
        if not self.server.database.account_exists(blocked):
            self._write_json(HTTPStatus.NOT_FOUND, {"error": "no_such_user"})
            return
        self.server.database.block(username, blocked)
        # Blocking also drops them from your own list, so the block is visible immediately.
        self.server.database.remove_friend(username, blocked)
        self._write_json(HTTPStatus.OK, {"blocked": blocked})

    def _handle_unblock(self, username: str, query: str) -> None:
        blocked = self._query_username(query)
        self.server.database.unblock(username, blocked)
        self._write_json(HTTPStatus.OK, {"unblocked": blocked})

    def _handle_list_blocked(self, username: str) -> None:
        self._write_json(
            HTTPStatus.OK,
            {"blocked": self.server.database.list_blocked(username)},
        )

    def _handle_send(self, sender: str, body: Dict[str, Any]) -> None:
        recipient = self._target_username(body, key="recipient")
        if not self.server.send_limiter.allow(sender):
            self._write_json(
                HTTPStatus.TOO_MANY_REQUESTS,
                {"delivered": False, "reason": "rate_limited"},
            )
            return
        if self.server.database.is_blocked(recipient, sender):
            # Indistinguishable from a delivered Yo on purpose: telling the sender they were
            # blocked turns the block into a notification for the person who was blocked.
            self._write_json(HTTPStatus.OK, {"delivered": True})
            return
        fcm_token = self.server.database.get_fcm_token(recipient)
        if fcm_token is None:
            # A real account with no device is not a missing account. Reporting both as
            # "recipient_not_found" told senders their friend did not exist whenever that friend's
            # registration had quietly failed. This discloses nothing new: /v1/friends and /v1/block
            # already answer "no_such_user" for names that do not exist.
            reason = (
                "recipient_unregistered"
                if self.server.database.account_exists(recipient)
                else "recipient_not_found"
            )
            self._write_json(
                HTTPStatus.NOT_FOUND,
                {
                    "delivered": False,
                    "reason": reason,
                },
            )
            return
        try:
            delivered = bool(self.server.fcm_client.send_yo(fcm_token, sender))
        except FCMNotConfiguredError:
            self._write_json(
                HTTPStatus.OK,
                {
                    "delivered": False,
                    "reason": "fcm_not_configured",
                },
            )
            return
        except FCMDeliveryError:
            self._write_json(
                HTTPStatus.BAD_GATEWAY,
                {
                    "delivered": False,
                    "reason": "fcm_delivery_failed",
                },
            )
            return
        self._write_json(HTTPStatus.OK, {"delivered": delivered})

    def _handle_photo_upload(self, username: str, body: Dict[str, Any]) -> None:
        message_id = self._required_string(body, "message_id")
        mime_type = self._required_string(body, "mime_type")
        data = self._required_string(body, "data")
        recipient = (
            self._target_username(body, key="recipient")
            if body.get("recipient")
            else None
        )
        if len(data.encode("utf-8")) > MAX_PHOTO_BYTES:
            self._write_json(
                HTTPStatus.BAD_REQUEST,
                {"error": "photo_too_large"},
            )
            return
        existing = self.server.database.get_photo(message_id)
        if existing is not None and existing[2] not in (None, username):
            # message_id is chosen by the client, so without this a caller could overwrite
            # somebody else's photo simply by reusing their id.
            self._write_json(HTTPStatus.FORBIDDEN, {"error": "forbidden"})
            return
        self.server.database.store_photo(
            message_id,
            mime_type,
            data,
            owner=username,
            recipient=recipient,
        )
        self._write_json(HTTPStatus.OK, {"stored": True})

    def _handle_photo_fetch(self, username: str, query: str) -> None:
        parameters = parse_qs(query, keep_blank_values=True)
        values = parameters.get("message_id")
        message_id = values[0].strip() if values else ""
        photo = self.server.database.get_photo(message_id)
        if photo is None:
            self._write_json(HTTPStatus.NOT_FOUND, {"error": "not_found"})
            return
        mime_type, data, owner, recipient = photo
        # Only the two ends of the conversation. A row with no owner predates ownership and is
        # denied outright rather than left world-readable - there were none in any live database
        # when this shipped, so nothing legitimate is lost.
        if username not in (owner, recipient):
            self._write_json(HTTPStatus.NOT_FOUND, {"error": "not_found"})
            return
        self._write_json(
            HTTPStatus.OK,
            {"mime_type": mime_type, "data": data},
        )

    def _handle_broadcast(
        self,
        client_id: str,
        body: Dict[str, Any],
    ) -> None:
        message = body.get("message")
        if message is not None and not isinstance(message, str):
            raise BadRequestError("message must be a string")
        tokens = self.server.database.list_subscriber_tokens(client_id)
        delivered_count = 0
        failed_count = 0
        for token in tokens:
            try:
                if self.server.fcm_client.send_yo(token, client_id):
                    delivered_count += 1
                else:
                    failed_count += 1
            except FCMNotConfiguredError:
                self._write_json(
                    HTTPStatus.OK,
                    {
                        "delivered": 0,
                        "failed": 0,
                        "subscriber_count": len(tokens),
                        "reason": "fcm_not_configured",
                    },
                )
                return
            except FCMDeliveryError:
                failed_count += 1
        self._write_json(
            HTTPStatus.OK,
            {
                "delivered": delivered_count,
                "failed": failed_count,
                "subscriber_count": len(tokens),
            },
        )

    def _read_json_body(self) -> Dict[str, Any]:
        raw_length = self.headers.get("Content-Length")
        if raw_length is None:
            raise BadRequestError("Content-Length is required")
        try:
            content_length = int(raw_length)
        except ValueError as error:
            raise BadRequestError("Content-Length must be an integer") from error
        if content_length <= 0:
            raise BadRequestError("request body is required")
        if content_length > MAX_BODY_BYTES:
            raise BadRequestError("request body is too large")
        raw_body = self.rfile.read(content_length)
        try:
            body = json.loads(raw_body.decode("utf-8"))
        except (UnicodeDecodeError, json.JSONDecodeError) as error:
            raise BadRequestError("request body must be valid JSON") from error
        if not isinstance(body, dict):
            raise BadRequestError("request body must be a JSON object")
        return body

    @staticmethod
    def _required_string(body: Dict[str, Any], key: str) -> str:
        value = body.get(key)
        if not isinstance(value, str) or not value.strip():
            raise BadRequestError(f"{key} is required")
        return value.strip()

    @classmethod
    def _target_username(cls, body: Dict[str, Any], key: str = "username") -> str:
        """Another user named in a request body, normalised to the canonical uppercase form."""
        try:
            return yo_auth.validate_username(cls._required_string(body, key))
        except yo_auth.CredentialError as error:
            raise BadRequestError(str(error)) from error

    @staticmethod
    def _query_username(query: str, key: str = "username") -> str:
        parameters = parse_qs(query, keep_blank_values=True)
        values = parameters.get(key)
        if not values or not values[0].strip():
            raise BadRequestError(f"{key} is required")
        try:
            return yo_auth.validate_username(values[0])
        except yo_auth.CredentialError as error:
            raise BadRequestError(str(error)) from error

    def _handle_install_page(self) -> None:
        apk = _configured_apk_path()
        if apk is not None:
            action = (
                '<a class="btn" href="/install/yo.apk">Download Yo</a>'
                '<p class="small">Android only. You may need to allow installs '
                "from your browser.</p>"
            )
        else:
            action = '<p class="small">The download is not published yet.</p>'
        body = INSTALL_PAGE_TEMPLATE.replace("{{ACTION}}", action).encode("utf-8")
        self.send_response(HTTPStatus.OK.value)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _handle_install_apk(self) -> None:
        apk = _configured_apk_path()
        if apk is None:
            self._write_json(HTTPStatus.NOT_FOUND, {"error": "not_found"})
            return
        data = apk.read_bytes()
        self.send_response(HTTPStatus.OK.value)
        self.send_header("Content-Type", "application/vnd.android.package-archive")
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Content-Disposition", 'attachment; filename="yo.apk"')
        self.end_headers()
        self.wfile.write(data)

    def _write_json(self, status: HTTPStatus, payload: Dict[str, Any]) -> None:
        body = json.dumps(payload, separators=(",", ":")).encode("utf-8")
        self.send_response(status.value)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)


def create_server(
    host: str,
    port: int,
    database_path: os.PathLike | str,
    fcm_client: Optional[Any] = None,
    password_iterations: int = yo_auth.DEFAULT_ITERATIONS,
    google_verifier: Optional[Any] = None,
) -> YoHTTPServer:
    database = YoDatabase(database_path)
    database.initialize()
    if google_verifier is None:
        client_id = yo_google.configured_client_id()
        if client_id is not None:
            google_verifier = yo_google.GoogleIdTokenVerifier(client_id)
    return YoHTTPServer(
        (host, port),
        database=database,
        fcm_client=fcm_client or FCMClient(),
        password_iterations=password_iterations,
        google_verifier=google_verifier,
    )


def main() -> None:
    parser = argparse.ArgumentParser(description="Yo push delivery backend")
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=DEFAULT_PORT)
    parser.add_argument("--database", default=str(DEFAULT_DATABASE))
    arguments = parser.parse_args()

    # YO_SERVER_KEY is deliberately gone. A single shared key that every install carried was
    # gap G3; callers now authenticate with a per-account token issued by /v1/signup.
    server = create_server(
        host=arguments.host,
        port=arguments.port,
        database_path=arguments.database,
    )
    try:
        print(
            f"Yo backend listening on http://{arguments.host}:{arguments.port}",
            flush=True,
        )
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
