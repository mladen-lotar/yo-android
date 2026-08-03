import inspect
import io
import ipaddress
import json
import os
import sqlite3
import tempfile
import time
import unicodedata
import unittest
from email.message import Message
from types import SimpleNamespace
from unittest import mock

import yo_auth
import yo_google
import yo_server
from fcm_client import FCMDeliveryError, FCMNotConfiguredError
from yo_db import YoDatabase
from yo_server import (
    CREDENTIAL_ATTEMPTS,
    CREDENTIAL_WINDOW_SECONDS,
    MAX_HASHTAG_BYTES,
    MAX_LINK_BYTES,
    LOOKUP_ATTEMPTS,
    LOOKUP_WINDOW_SECONDS,
    SEND_ATTEMPTS,
    SEND_WINDOW_SECONDS,
    RateLimiter,
    YoRequestHandler,
    _hash_client_key,
)

# Production uses yo_auth.DEFAULT_ITERATIONS (600k), which costs a few hundred milliseconds per
# hash. The suite signs up dozens of accounts, so it passes a deliberately cheap cost instead.
TEST_ITERATIONS = 1_000
TEST_PASSWORD = "correct-horse-battery"


class RecordingFCMClient:
    def __init__(self):
        self.calls = []
        # Coordinates are recorded separately so the existing (token, sender) assertions - about
        # twenty of them, none concerned with location - keep saying what they were written to say.
        self.location_calls = []
        # Same reasoning again for the link and hashtag attachments: a third list rather than a
        # wider `calls` tuple, so every assertion keeps testing exactly what it names.
        self.attachment_calls = []
        self.result = True
        self.error = None
        self.fail_tokens = set()

    def send_yo(
        self,
        fcm_token,
        sender,
        latitude=None,
        longitude=None,
        link=None,
        hashtag=None,
    ):
        self.calls.append((fcm_token, sender))
        self.location_calls.append((fcm_token, sender, latitude, longitude))
        self.attachment_calls.append((fcm_token, sender, link, hashtag))
        if self.error is not None:
            raise self.error
        if fcm_token in self.fail_tokens:
            raise FCMDeliveryError("delivery failed")
        return self.result


class StubGoogleVerifier:
    """Stands in for GoogleIdTokenVerifier: the network and the signing certificates are
    yo_google's problem, tested in test_yo_google.py. What matters here is what the route does
    with a subject."""

    def __init__(self, subject="google-subject-alice"):
        self.subject = subject
        self.error = None
        self.calls = []

    def subject_for(self, raw_token):
        self.calls.append(raw_token)
        if self.error is not None:
            raise self.error
        return self.subject


class YoServerTestCase(unittest.TestCase):
    """Shared harness: a real sqlite database plus a SimpleNamespace standing in for the server.

    The handler only ever touches server.database / fcm_client / password_iterations /
    credential_limiter / send_limiter, so a namespace is enough and avoids binding a socket.
    Every limiter the handler reads must be listed here, or the tests that exercise that route
    fail with AttributeError rather than a useful assertion.
    """

    def setUp(self):
        database_file = tempfile.NamedTemporaryFile(delete=False)
        database_file.close()
        self.database_path = database_file.name
        self.fcm_client = RecordingFCMClient()
        database = YoDatabase(self.database_path)
        database.initialize()
        self.server = SimpleNamespace(
            database=database,
            fcm_client=self.fcm_client,
            password_iterations=TEST_ITERATIONS,
            credential_limiter=RateLimiter(
                CREDENTIAL_ATTEMPTS,
                CREDENTIAL_WINDOW_SECONDS,
            ),
            send_limiter=RateLimiter(SEND_ATTEMPTS, SEND_WINDOW_SECONDS),
            lookup_limiter=RateLimiter(LOOKUP_ATTEMPTS, LOOKUP_WINDOW_SECONDS),
            # Zero, so the equal-cost sleep on the blocked path does not add a tenth
            # of a second to every block test. The PROPERTY is asserted separately.
            delivery_cost=yo_server.DeliveryCostEstimator(initial_seconds=0.0),
            # Unconfigured by default, matching a deployment with no YO_GOOGLE_CLIENT_ID.
            # GoogleSignInTest installs a stub.
            google_verifier=None,
        )

    def tearDown(self):
        os.unlink(self.database_path)

    def signup(self, username, password=TEST_PASSWORD):
        """Create an account and return its bearer token."""
        status, body = self.request(
            "POST",
            "/v1/signup",
            {"username": username, "password": password},
            token=None,
        )
        self.assertEqual(201, status, body)
        return body["token"]

    def signup_with_device(self, username, fcm_token=None):
        """Create an account and register a device for it; returns the bearer token."""
        token = self.signup(username)
        status, _ = self.request(
            "POST",
            "/v1/register",
            {"fcm_token": fcm_token or f"{username.lower()}-token"},
            token=token,
        )
        self.assertEqual(200, status)
        return token

    def request(
        self,
        method,
        path,
        body=None,
        token=None,
        extra_headers=None,
        raw_body=None,
    ):
        data = b""
        headers = Message()
        # raw_body sends bytes the JSON encoder could not have produced, which is the only way to
        # exercise what the server does with a body it cannot parse.
        if raw_body is not None:
            data = raw_body
            headers["Content-Type"] = "application/json"
            headers["Content-Length"] = str(len(data))
        elif body is not None:
            data = json.dumps(body).encode("utf-8")
            headers["Content-Type"] = "application/json"
            headers["Content-Length"] = str(len(data))
        if token is not None:
            headers["Authorization"] = f"Bearer {token}"
        if extra_headers is not None:
            for name, value in extra_headers.items():
                headers[name] = value

        handler = YoRequestHandler.__new__(YoRequestHandler)
        handler.command = method
        handler.path = path
        handler.request_version = "HTTP/1.1"
        handler.requestline = f"{method} {path} HTTP/1.1"
        handler.headers = headers
        handler.rfile = io.BytesIO(data)
        handler.wfile = io.BytesIO()
        handler.server = self.server
        handler.client_address = ("127.0.0.1", 0)
        handler.log_message = lambda *_: None

        if method == "GET":
            handler.do_GET()
        elif method == "POST":
            handler.do_POST()
        elif method == "DELETE":
            handler.do_DELETE()
        else:
            raise AssertionError(f"unsupported test method: {method}")

        response_head, response_body = handler.wfile.getvalue().split(
            b"\r\n\r\n",
            1,
        )
        status_line = response_head.splitlines()[0].decode("ascii")
        status = int(status_line.split(" ", 2)[1])
        return status, json.loads(response_body)


class CredentialTest(YoServerTestCase):
    """POST /v1/signup and /v1/login - the routes that replace the old shared key."""

    def test_signup_creates_the_account_and_returns_a_token(self):
        status, body = self.request(
            "POST",
            "/v1/signup",
            {"username": "alice", "password": TEST_PASSWORD},
            token=None,
        )

        self.assertEqual(201, status)
        self.assertEqual("ALICE", body["username"])
        self.assertTrue(body["token"])
        self.assertTrue(self.server.database.account_exists("ALICE"))

    def test_signup_stores_a_hash_and_never_the_password(self):
        self.signup("ALICE")

        stored = self.server.database.get_password_hash("ALICE")
        self.assertTrue(stored.startswith("pbkdf2_sha256$"))
        self.assertNotIn(TEST_PASSWORD, stored)

    def test_signup_normalises_the_username_to_uppercase(self):
        status, body = self.request(
            "POST",
            "/v1/signup",
            {"username": "  mixedCase  ", "password": TEST_PASSWORD},
            token=None,
        )

        self.assertEqual(201, status)
        self.assertEqual("MIXEDCASE", body["username"])

    def test_duplicate_username_is_rejected_with_409(self):
        self.signup("ALICE")

        status, body = self.request(
            "POST",
            "/v1/signup",
            {"username": "alice", "password": "a-different-password"},
            token=None,
        )

        self.assertEqual(409, status)
        self.assertEqual({"error": "username_taken"}, body)

    def test_signup_rejects_an_invalid_username(self):
        for username in ("a", "has space", "no-dashes", "A" * 33, ""):
            with self.subTest(username=username):
                status, body = self.request(
                    "POST",
                    "/v1/signup",
                    {"username": username, "password": TEST_PASSWORD},
                    token=None,
                )

                self.assertEqual(400, status)
                self.assertEqual("bad_request", body["error"])

    def test_signup_rejects_a_short_or_missing_password(self):
        for password in ("short", "", None, 12345678):
            with self.subTest(password=password):
                status, body = self.request(
                    "POST",
                    "/v1/signup",
                    {"username": "ALICE", "password": password},
                    token=None,
                )

                self.assertEqual(400, status)
                self.assertEqual("bad_request", body["error"])
        self.assertFalse(self.server.database.account_exists("ALICE"))

    def test_login_with_the_right_password_returns_a_working_token(self):
        self.signup("ALICE")

        status, body = self.request(
            "POST",
            "/v1/login",
            {"username": "alice", "password": TEST_PASSWORD},
            token=None,
        )

        self.assertEqual(200, status)
        self.assertEqual("ALICE", body["username"])
        friends_status, _ = self.request(
            "GET",
            "/v1/friends",
            token=body["token"],
        )
        self.assertEqual(200, friends_status)

    def test_login_issues_a_second_token_without_revoking_the_first(self):
        first_token = self.signup("ALICE")

        _, body = self.request(
            "POST",
            "/v1/login",
            {"username": "ALICE", "password": TEST_PASSWORD},
            token=None,
        )

        self.assertNotEqual(first_token, body["token"])
        for token in (first_token, body["token"]):
            with self.subTest(token=token):
                status, _ = self.request("GET", "/v1/friends", token=token)
                self.assertEqual(200, status)

    def test_login_with_the_wrong_password_is_401(self):
        self.signup("ALICE")

        status, body = self.request(
            "POST",
            "/v1/login",
            {"username": "ALICE", "password": "not-the-password"},
            token=None,
        )

        self.assertEqual(401, status)
        self.assertEqual({"error": "invalid_credentials"}, body)

    def test_unknown_user_is_indistinguishable_from_a_wrong_password(self):
        """Otherwise /v1/login becomes a username oracle."""
        self.signup("ALICE")

        wrong_password = self.request(
            "POST",
            "/v1/login",
            {"username": "ALICE", "password": "not-the-password"},
            token=None,
        )
        unknown_user = self.request(
            "POST",
            "/v1/login",
            {"username": "NOBODY", "password": "not-the-password"},
            token=None,
        )

        self.assertEqual((401, {"error": "invalid_credentials"}), wrong_password)
        self.assertEqual(wrong_password, unknown_user)

    def test_credential_routes_are_rate_limited_per_caller(self):
        for attempt in range(CREDENTIAL_ATTEMPTS):
            status, _ = self.request(
                "POST",
                "/v1/login",
                {"username": "NOBODY", "password": "not-the-password"},
                token=None,
            )
            self.assertEqual(401, status, f"attempt {attempt} should still be allowed")

        status, body = self.request(
            "POST",
            "/v1/login",
            {"username": "NOBODY", "password": "not-the-password"},
            token=None,
        )
        self.assertEqual(429, status)
        self.assertEqual({"error": "rate_limited"}, body)

        # Signup shares the same bucket: an attacker cannot dodge the limit by switching route.
        status, _ = self.request(
            "POST",
            "/v1/signup",
            {"username": "ALICE", "password": TEST_PASSWORD},
            token=None,
        )
        self.assertEqual(429, status)

    def test_cf_connecting_ip_is_ignored_when_no_cloudflare_ranges_are_configured(self):
        """A forged CF-Connecting-IP must NOT hand the caller a fresh rate-limit bucket.

        Regression test for a demonstrated bypass: the header used to be trusted first and
        unconditionally, so 15 signups sailed past a limit of 10 by varying it. With no
        YO_CLOUDFLARE_RANGES configured nothing is trusted, every caller collapses onto the
        socket peer, and the limiter holds.
        """
        for _ in range(CREDENTIAL_ATTEMPTS):
            self.request(
                "POST",
                "/v1/login",
                {"username": "NOBODY", "password": "nope-nope-nope"},
                token=None,
                extra_headers={"CF-Connecting-IP": "198.51.100.7"},
            )

        forged, _ = self.request(
            "POST",
            "/v1/signup",
            {"username": "ALICE", "password": TEST_PASSWORD},
            token=None,
            extra_headers={"CF-Connecting-IP": "203.0.113.9"},
        )

        self.assertEqual(429, forged)

    def test_cf_connecting_ip_is_honoured_only_from_a_cloudflare_peer(self):
        """Configured ranges restore per-user keying, but only for traffic that came via CF.

        The peer is the RIGHTMOST X-Forwarded-For entry, which the proxy authors from the TCP
        peer on every request.
        """
        cf_peer = "173.245.48.9"  # inside Cloudflare's 173.245.48.0/20
        with mock.patch.object(
            yo_server,
            "CLOUDFLARE_RANGES",
            (ipaddress.ip_network("173.245.48.0/20"),),
        ):
            for _ in range(CREDENTIAL_ATTEMPTS):
                self.request(
                    "POST",
                    "/v1/login",
                    {"username": "NOBODY", "password": "nope-nope-nope"},
                    token=None,
                    extra_headers={
                        "X-Forwarded-For": cf_peer,
                        "CF-Connecting-IP": "198.51.100.7",
                    },
                )

            blocked, _ = self.request(
                "POST",
                "/v1/login",
                {"username": "NOBODY", "password": "nope-nope-nope"},
                token=None,
                extra_headers={
                    "X-Forwarded-For": cf_peer,
                    "CF-Connecting-IP": "198.51.100.7",
                },
            )
            other_caller, _ = self.request(
                "POST",
                "/v1/signup",
                {"username": "ALICE", "password": TEST_PASSWORD},
                token=None,
                extra_headers={
                    "X-Forwarded-For": cf_peer,
                    "CF-Connecting-IP": "203.0.113.9",
                },
            )

        self.assertEqual(429, blocked)
        self.assertEqual(201, other_caller)

    def test_the_rightmost_forwarded_entry_wins_over_a_client_supplied_chain(self):
        """A client-supplied XFF prefix must not be mistaken for the peer.

        Cloudflare forwards a client-supplied X-Forwarded-For unmodified and appends, so
        position [0] is attacker-chosen. Varying only the left entry must not mint a bucket.
        """
        with mock.patch.object(
            yo_server,
            "CLOUDFLARE_RANGES",
            (ipaddress.ip_network("173.245.48.0/20"),),
        ):
            for _ in range(CREDENTIAL_ATTEMPTS):
                self.request(
                    "POST",
                    "/v1/login",
                    {"username": "NOBODY", "password": "nope-nope-nope"},
                    token=None,
                    extra_headers={
                        "X-Forwarded-For": "203.0.113.9, 173.245.48.9",
                        "CF-Connecting-IP": "198.51.100.7",
                    },
                )
            blocked, _ = self.request(
                "POST",
                "/v1/signup",
                {"username": "ALICE", "password": TEST_PASSWORD},
                token=None,
                extra_headers={
                    "X-Forwarded-For": "8.8.8.8, 173.245.48.9",
                    "CF-Connecting-IP": "198.51.100.7",
                },
            )

        self.assertEqual(429, blocked)


class GoogleSignInTest(YoServerTestCase):
    """POST /v1/google - "continue with Google"."""

    def setUp(self):
        super().setUp()
        self.verifier = StubGoogleVerifier()
        self.server.google_verifier = self.verifier

    def google(self, username=None, id_token="an.id.token", extra_headers=None):
        body = {"id_token": id_token}
        if username is not None:
            body["username"] = username
        return self.request(
            "POST",
            "/v1/google",
            body,
            token=None,
            extra_headers=extra_headers,
        )

    def test_an_unknown_google_account_is_asked_for_a_username(self):
        status, body = self.google()
        self.assertEqual(404, status)
        self.assertEqual("username_required", body["error"])
        # Nothing was created on the way to asking.
        self.assertFalse(self.server.database.account_exists("ALICE"))

    def test_signing_in_with_a_username_creates_the_account(self):
        status, body = self.google(username="ALICE")
        self.assertEqual(201, status, body)
        self.assertEqual("ALICE", body["username"])
        self.assertTrue(self.server.database.account_exists("ALICE"))

    def test_the_issued_token_authenticates_the_new_account(self):
        _, body = self.google(username="ALICE")
        status, friends = self.request(
            "GET",
            "/v1/friends",
            token=body["token"],
        )
        self.assertEqual(200, status)
        self.assertEqual([], friends["friends"])

    def test_a_returning_google_account_needs_no_username(self):
        self.google(username="ALICE")
        status, body = self.google()
        self.assertEqual(200, status, body)
        self.assertEqual("ALICE", body["username"])

    def test_a_returning_account_is_matched_on_subject_not_on_the_token_string(self):
        """Google mints a fresh ID token on every sign-in; only `sub` is stable."""
        self.google(username="ALICE", id_token="first.id.token")
        status, body = self.google(id_token="a.completely.different.token")
        self.assertEqual(200, status, body)
        self.assertEqual("ALICE", body["username"])

    def test_a_supplied_username_is_ignored_once_the_account_exists(self):
        """Otherwise a second sign-in could silently rename or fork the account."""
        self.google(username="ALICE")
        status, body = self.google(username="MALLORY")
        self.assertEqual(200, status)
        self.assertEqual("ALICE", body["username"])
        self.assertFalse(self.server.database.account_exists("MALLORY"))

    def test_a_junk_username_cannot_break_a_sign_in_that_needs_no_username(self):
        """Once the Google account is linked the body's username is not read at all, so an app
        that sends a stale or malformed one still signs its user in rather than 400-ing them out
        of their own account."""
        self.google(username="ALICE")
        status, body = self.google(username="!! not a username !!")
        self.assertEqual(200, status, body)
        self.assertEqual("ALICE", body["username"])

    def test_a_second_google_account_cannot_take_an_existing_username(self):
        self.signup("ALICE")
        self.verifier.subject = "google-subject-mallory"
        status, body = self.google(username="ALICE")
        self.assertEqual(409, status)
        self.assertEqual("username_taken", body["error"])

    def test_a_failed_claim_leaves_the_google_account_unlinked(self):
        """The username was rejected, so nothing may be recorded - not even a dangling link."""
        self.signup("ALICE")
        self.verifier.subject = "google-subject-mallory"
        self.google(username="ALICE")
        self.assertIsNone(
            self.server.database.username_for_identity(
                "google",
                "google-subject-mallory",
            )
        )

    def test_one_google_account_cannot_hold_two_usernames(self):
        """Exercised directly because only a concurrent request can reach this branch through
        the route: it re-checks the identity table immediately before writing."""
        database = self.server.database
        self.assertTrue(
            database.create_linked_account(
                "ALICE",
                "google",
                "subject-1",
                yo_auth.UNUSABLE_PASSWORD_HASH,
            )
        )
        self.assertFalse(
            database.create_linked_account(
                "ALICE2",
                "google",
                "subject-1",
                yo_auth.UNUSABLE_PASSWORD_HASH,
            )
        )
        # The second username must not survive as an account nobody can ever sign in to.
        self.assertFalse(database.account_exists("ALICE2"))
        self.assertEqual("ALICE", database.username_for_identity("google", "subject-1"))

    def test_the_username_is_normalised_to_uppercase(self):
        status, body = self.google(username="  alice ")
        self.assertEqual(201, status)
        self.assertEqual("ALICE", body["username"])

    def test_an_invalid_username_is_rejected(self):
        for username in ("a", "has spaces", "!!", "x" * 33):
            with self.subTest(username=username):
                status, body = self.google(username=username)
                self.assertEqual(400, status)
                self.assertEqual("bad_request", body["error"])

    def test_a_missing_id_token_is_a_bad_request(self):
        status, body = self.request("POST", "/v1/google", {"username": "ALICE"})
        self.assertEqual(400, status)
        self.assertEqual("bad_request", body["error"])

    def test_a_rejected_token_is_401_and_says_nothing_more(self):
        self.verifier.error = yo_google.GoogleAuthError("Token expired: 1699999999")
        status, body = self.google(username="ALICE")
        self.assertEqual(401, status)
        self.assertEqual({"error": "invalid_google_token"}, body)

    def test_an_unverifiable_token_is_503_not_401(self):
        """Google being unreachable is our outage; answering 401 would tell the user to
        re-authenticate over a problem no amount of re-authenticating can fix."""
        self.verifier.error = yo_google.GoogleUnavailableError("certs unreachable")
        status, body = self.google(username="ALICE")
        self.assertEqual(503, status)
        self.assertEqual("google_unavailable", body["error"])

    def test_the_route_is_503_when_no_client_id_is_configured(self):
        self.server.google_verifier = None
        status, body = self.google(username="ALICE")
        self.assertEqual(503, status)
        self.assertEqual("google_not_configured", body["error"])

    def test_an_unconfigured_route_never_reaches_the_database(self):
        self.server.google_verifier = None
        self.google(username="ALICE")
        self.assertFalse(self.server.database.account_exists("ALICE"))

    def test_attempts_are_rate_limited_per_caller(self):
        headers = {"CF-Connecting-IP": "203.0.113.9"}
        self.verifier.error = yo_google.GoogleAuthError("nope")
        for _ in range(CREDENTIAL_ATTEMPTS):
            status, _ = self.google(username="ALICE", extra_headers=headers)
            self.assertEqual(401, status)
        status, body = self.google(username="ALICE", extra_headers=headers)
        self.assertEqual(429, status)
        self.assertEqual("rate_limited", body["error"])

    def test_a_google_account_cannot_be_logged_into_with_a_password(self):
        """The stored hash is a sentinel that no password can produce, so /v1/login stays shut
        for accounts that have no password - including for the sentinel's own text."""
        self.google(username="ALICE")
        for password in (
            TEST_PASSWORD,
            yo_auth.UNUSABLE_PASSWORD_HASH,
            "!" * 8,
            "",
        ):
            with self.subTest(password=password):
                status, _ = self.request(
                    "POST",
                    "/v1/login",
                    {"username": "ALICE", "password": password},
                )
                self.assertIn(status, (400, 401))

    def test_a_google_account_stores_no_usable_password_hash(self):
        self.google(username="ALICE")
        self.assertEqual(
            yo_auth.UNUSABLE_PASSWORD_HASH,
            self.server.database.get_password_hash("ALICE"),
        )

    def test_a_google_account_is_a_normal_account_everywhere_else(self):
        """It must be addressable as a friend, or signing in with Google would be a dead end."""
        google_token = self.google(username="ALICE")[1]["token"]
        bob_token = self.signup("BOB")
        status, _ = self.request(
            "POST",
            "/v1/friends",
            {"username": "ALICE"},
            token=bob_token,
        )
        self.assertEqual(200, status)
        status, body = self.request(
            "POST",
            "/v1/friends",
            {"username": "BOB"},
            token=google_token,
        )
        self.assertEqual(200, status, body)


class TokenAuthTest(YoServerTestCase):
    def test_every_v1_route_rejects_a_missing_or_bad_token(self):
        routes = (
            ("GET", "/v1/friends", None),
            ("GET", "/v1/blocked", None),
            ("POST", "/v1/register", {"fcm_token": "token"}),
            ("POST", "/v1/send", {"recipient": "BOB"}),
            ("POST", "/v1/friends", {"username": "BOB"}),
            ("POST", "/v1/block", {"username": "BOB"}),
            ("DELETE", "/v1/friends?username=BOB", None),
            ("DELETE", "/v1/block?username=BOB", None),
            ("DELETE", "/v1/session", None),
        )
        for method, path, body in routes:
            for token in (None, "not-a-real-token", ""):
                with self.subTest(method=method, path=path, token=token):
                    status, response = self.request(method, path, body, token=token)

                    self.assertEqual(401, status)
                    self.assertEqual({"error": "unauthorized"}, response)
        self.assertEqual([], self.fcm_client.calls)

    def test_the_x_yo_token_header_is_accepted_as_well_as_bearer(self):
        token = self.signup("ALICE")

        status, body = self.request(
            "GET",
            "/v1/friends",
            token=None,
            extra_headers={"X-Yo-Token": token},
        )

        self.assertEqual(200, status)
        self.assertEqual({"friends": []}, body)

    def test_logout_invalidates_the_token(self):
        token = self.signup("ALICE")

        status, body = self.request("DELETE", "/v1/session", token=token)

        self.assertEqual(200, status)
        self.assertEqual({"ended": True}, body)

        status, body = self.request("GET", "/v1/friends", token=token)
        self.assertEqual(401, status)
        self.assertEqual({"error": "unauthorized"}, body)

    def test_logout_leaves_other_sessions_alone(self):
        first_token = self.signup("ALICE")
        _, login = self.request(
            "POST",
            "/v1/login",
            {"username": "ALICE", "password": TEST_PASSWORD},
            token=None,
        )

        self.request("DELETE", "/v1/session", token=first_token)

        self.assertEqual(401, self.request("GET", "/v1/friends", token=first_token)[0])
        self.assertEqual(
            200,
            self.request("GET", "/v1/friends", token=login["token"])[0],
        )

    def test_healthz_does_not_require_authentication(self):
        status, body = self.request("GET", "/healthz", token=None)

        self.assertEqual(200, status)
        self.assertEqual({"ok": True}, body)


class RegisterTest(YoServerTestCase):
    def test_register_upserts_token(self):
        token = self.signup("ALICE")

        first_status, first_body = self.request(
            "POST",
            "/v1/register",
            {"fcm_token": "first-token"},
            token=token,
        )
        second_status, second_body = self.request(
            "POST",
            "/v1/register",
            {"fcm_token": "second-token"},
            token=token,
        )

        self.assertEqual(200, first_status)
        self.assertEqual(200, second_status)
        self.assertTrue(first_body["registered"])
        self.assertTrue(second_body["registered"])
        database = YoDatabase(self.database_path)
        self.assertEqual("second-token", database.get_fcm_token("ALICE"))

    def test_register_binds_to_the_token_and_ignores_a_username_in_the_body(self):
        """Otherwise anyone could point another account's push notifications at their device."""
        alice_token = self.signup("ALICE")
        self.signup("BOB")

        status, body = self.request(
            "POST",
            "/v1/register",
            {"username": "BOB", "fcm_token": "attacker-token"},
            token=alice_token,
        )

        self.assertEqual(200, status)
        self.assertEqual("ALICE", body["username"])
        self.assertEqual("attacker-token", self.server.database.get_fcm_token("ALICE"))
        self.assertIsNone(self.server.database.get_fcm_token("BOB"))

    def test_register_requires_an_fcm_token(self):
        token = self.signup("ALICE")

        status, body = self.request("POST", "/v1/register", {}, token=token)

        self.assertEqual(400, status)
        self.assertEqual("bad_request", body["error"])


class FriendsAndBlocksTest(YoServerTestCase):
    def test_friends_lists_only_the_people_this_user_added(self):
        """Gap G5: /v1/friends used to hand back every registered device on the server."""
        alice_token = self.signup_with_device("ALICE")
        self.signup_with_device("BOB")
        self.signup_with_device("CHARLIE")

        self.request("POST", "/v1/friends", {"username": "BOB"}, token=alice_token)

        status, body = self.request("GET", "/v1/friends", token=alice_token)

        self.assertEqual(200, status)
        self.assertEqual({"friends": ["BOB"]}, body)

    def test_friend_lists_are_per_account_and_not_reciprocal(self):
        alice_token = self.signup("ALICE")
        bob_token = self.signup("BOB")

        self.request("POST", "/v1/friends", {"username": "bob"}, token=alice_token)

        self.assertEqual(
            {"friends": ["BOB"]},
            self.request("GET", "/v1/friends", token=alice_token)[1],
        )
        self.assertEqual(
            {"friends": []},
            self.request("GET", "/v1/friends", token=bob_token)[1],
        )

    def test_adding_a_nonexistent_user_is_404(self):
        alice_token = self.signup("ALICE")

        status, body = self.request(
            "POST",
            "/v1/friends",
            {"username": "NOBODY"},
            token=alice_token,
        )

        self.assertEqual(404, status)
        self.assertEqual({"error": "no_such_user"}, body)
        self.assertEqual([], self.server.database.list_friends("ALICE"))

    def test_adding_yourself_is_400(self):
        alice_token = self.signup("ALICE")

        status, body = self.request(
            "POST",
            "/v1/friends",
            {"username": "alice"},
            token=alice_token,
        )

        self.assertEqual(400, status)
        self.assertEqual("bad_request", body["error"])

    def test_removing_a_friend_drops_them_from_the_list(self):
        alice_token = self.signup("ALICE")
        self.signup("BOB")
        self.request("POST", "/v1/friends", {"username": "BOB"}, token=alice_token)

        status, body = self.request(
            "DELETE",
            "/v1/friends?username=bob",
            token=alice_token,
        )

        self.assertEqual(200, status)
        self.assertEqual({"removed": "BOB"}, body)
        self.assertEqual(
            {"friends": []},
            self.request("GET", "/v1/friends", token=alice_token)[1],
        )

    def test_blocking_also_removes_them_from_your_friends(self):
        alice_token = self.signup("ALICE")
        self.signup("BOB")
        self.request("POST", "/v1/friends", {"username": "BOB"}, token=alice_token)

        status, body = self.request(
            "POST",
            "/v1/block",
            {"username": "bob"},
            token=alice_token,
        )

        self.assertEqual(200, status)
        self.assertEqual({"blocked": "BOB"}, body)
        self.assertEqual(
            {"friends": []},
            self.request("GET", "/v1/friends", token=alice_token)[1],
        )
        self.assertEqual(
            {"blocked": ["BOB"]},
            self.request("GET", "/v1/blocked", token=alice_token)[1],
        )

    def test_unblocking_clears_the_block(self):
        alice_token = self.signup("ALICE")
        self.signup("BOB")
        self.request("POST", "/v1/block", {"username": "BOB"}, token=alice_token)

        status, body = self.request(
            "DELETE",
            "/v1/block?username=BOB",
            token=alice_token,
        )

        self.assertEqual(200, status)
        self.assertEqual({"unblocked": "BOB"}, body)
        self.assertEqual(
            {"blocked": []},
            self.request("GET", "/v1/blocked", token=alice_token)[1],
        )

    def test_blocking_yourself_is_400(self):
        alice_token = self.signup("ALICE")

        status, body = self.request(
            "POST",
            "/v1/block",
            {"username": "ALICE"},
            token=alice_token,
        )

        self.assertEqual(400, status)
        self.assertEqual("bad_request", body["error"])

    def test_blocked_lists_are_private_to_each_account(self):
        alice_token = self.signup("ALICE")
        bob_token = self.signup("BOB")
        self.request("POST", "/v1/block", {"username": "BOB"}, token=alice_token)

        self.assertEqual(
            {"blocked": []},
            self.request("GET", "/v1/blocked", token=bob_token)[1],
        )


class SendLocationTest(YoServerTestCase):
    """Gap G20: the app captured a location, stored it locally and never sent it, so the sender
    was told they had shared a position the recipient could not possibly receive."""

    def send_location(self, latitude, longitude, recipient="BOB"):
        alice_token = self.signup("ALICE")
        self.signup_with_device(recipient, "bob-token")
        return self.request(
            "POST",
            "/v1/send",
            {"recipient": recipient, "latitude": latitude, "longitude": longitude},
            token=alice_token,
        )

    def test_attached_location_reaches_the_push(self):
        status, body = self.send_location(45.815, 15.982)

        self.assertEqual(200, status)
        self.assertEqual({"delivered": True}, body)
        self.assertEqual(
            [("bob-token", "ALICE", 45.815, 15.982)],
            self.fcm_client.location_calls,
        )

    def test_a_send_without_a_location_carries_none(self):
        alice_token = self.signup("ALICE")
        self.signup_with_device("BOB", "bob-token")

        self.request("POST", "/v1/send", {"recipient": "BOB"}, token=alice_token)

        self.assertEqual(
            [("bob-token", "ALICE", None, None)],
            self.fcm_client.location_calls,
        )

    def test_negative_coordinates_survive(self):
        self.send_location(-33.8688, -151.2093)

        self.assertEqual(
            [("bob-token", "ALICE", -33.8688, -151.2093)],
            self.fcm_client.location_calls,
        )

    def test_integer_coordinates_are_accepted_as_numbers(self):
        self.send_location(45, 16)

        self.assertEqual(
            [("bob-token", "ALICE", 45.0, 16.0)],
            self.fcm_client.location_calls,
        )

    def test_a_lone_coordinate_is_rejected(self):
        alice_token = self.signup("ALICE")
        self.signup_with_device("BOB", "bob-token")

        status, body = self.request(
            "POST",
            "/v1/send",
            {"recipient": "BOB", "latitude": 45.815},
            token=alice_token,
        )

        self.assertEqual(400, status)
        self.assertEqual("bad_request", body["error"])
        # Rejected outright rather than sent without the location: silently dropping half a
        # position tells the sender their location went out when it did not.
        self.assertEqual([], self.fcm_client.calls)

    def test_out_of_range_coordinates_are_rejected(self):
        alice_token = self.signup("ALICE")
        self.signup_with_device("BOB", "bob-token")

        for latitude, longitude in ((90.1, 0), (-90.1, 0), (0, 180.1), (0, -180.1)):
            with self.subTest(latitude=latitude, longitude=longitude):
                status, _ = self.request(
                    "POST",
                    "/v1/send",
                    {
                        "recipient": "BOB",
                        "latitude": latitude,
                        "longitude": longitude,
                    },
                    token=alice_token,
                )
                self.assertEqual(400, status)

        self.assertEqual([], self.fcm_client.calls)

    def test_the_poles_and_the_antimeridian_are_accepted(self):
        self.send_location(90, 180)

        self.assertEqual(
            [("bob-token", "ALICE", 90.0, 180.0)],
            self.fcm_client.location_calls,
        )

    def test_non_numeric_coordinates_are_rejected(self):
        alice_token = self.signup("ALICE")
        self.signup_with_device("BOB", "bob-token")

        # True is in this list because bool subclasses int in Python: without an explicit check
        # it would sail through as the coordinate 1.0. None means "no latitude", which pairs with
        # a longitude that IS present - rejected by the both-or-neither rule rather than by type.
        for latitude in ("45.815", None, [45.815], {"lat": 1}, True):
            with self.subTest(latitude=latitude):
                status, _ = self.request(
                    "POST",
                    "/v1/send",
                    {"recipient": "BOB", "latitude": latitude, "longitude": 15.982},
                    token=alice_token,
                )
                self.assertEqual(400, status)

        self.assertEqual([], self.fcm_client.calls)

    def test_a_blocked_recipient_still_sees_nothing(self):
        alice_token = self.signup("ALICE")
        bob_token = self.signup_with_device("BOB", "bob-token")
        self.request("POST", "/v1/block", {"username": "ALICE"}, token=bob_token)

        status, body = self.request(
            "POST",
            "/v1/send",
            {"recipient": "BOB", "latitude": 45.815, "longitude": 15.982},
            token=alice_token,
        )

        # Same indistinguishable-from-delivered answer as a plain Yo, and no push carrying a
        # position to somebody who blocked the sender.
        self.assertEqual(200, status)
        self.assertEqual({"delivered": True}, body)
        self.assertEqual([], self.fcm_client.calls)


class SendAttachmentTest(YoServerTestCase):
    """Gap G23: the app let you attach a link or a hashtag, wrote it to its own local history and
    then sent a bare Yo, so the sender was shown an attachment the recipient could never receive.

    The same defect class as G20 one field along. The push is the recipient's only surface for
    these - nothing about a received Yo is stored on their device - so an attachment that misses
    the payload is an attachment nobody will ever see.
    """

    def setUp(self):
        super().setUp()
        self.alice_token = self.signup("ALICE")
        self.signup_with_device("BOB", "bob-token")

    def send(self, **attachments):
        body = {"recipient": "BOB"}
        body.update(attachments)
        return self.request("POST", "/v1/send", body, token=self.alice_token)

    def test_an_attached_link_reaches_the_push(self):
        status, body = self.send(link="https://example.com/a")

        self.assertEqual(200, status)
        self.assertEqual({"delivered": True}, body)
        self.assertEqual(
            [("bob-token", "ALICE", "https://example.com/a", None)],
            self.fcm_client.attachment_calls,
        )

    def test_an_attached_hashtag_reaches_the_push(self):
        status, body = self.send(hashtag="#yo")

        self.assertEqual(200, status)
        self.assertEqual({"delivered": True}, body)
        self.assertEqual(
            [("bob-token", "ALICE", None, "#yo")],
            self.fcm_client.attachment_calls,
        )

    def test_a_link_and_a_hashtag_travel_together(self):
        self.send(link="https://example.com/a", hashtag="#yo")

        self.assertEqual(
            [("bob-token", "ALICE", "https://example.com/a", "#yo")],
            self.fcm_client.attachment_calls,
        )

    def test_a_send_with_neither_carries_neither(self):
        """A plain Yo must stay plain: a field that defaults into the payload would put an
        attachment on the recipient's screen that the sender never made."""
        self.send()

        self.assertEqual(
            [("bob-token", "ALICE", None, None)],
            self.fcm_client.attachment_calls,
        )

    def test_a_blank_attachment_is_absent_rather_than_an_empty_string(self):
        """Otherwise the recipient's app sees the key, believes something was attached, and
        offers to open nothing."""
        for blank in ("", "   ", "\t\n "):
            with self.subTest(blank=blank):
                self.fcm_client.attachment_calls.clear()

                status, _ = self.send(link=blank, hashtag=blank)

                self.assertEqual(200, status)
                self.assertEqual(
                    [("bob-token", "ALICE", None, None)],
                    self.fcm_client.attachment_calls,
                )

    def test_surrounding_whitespace_is_trimmed_off(self):
        self.send(link="  https://example.com/a  ", hashtag=" #yo ")

        self.assertEqual(
            [("bob-token", "ALICE", "https://example.com/a", "#yo")],
            self.fcm_client.attachment_calls,
        )

    def test_a_non_string_attachment_is_rejected_not_silently_dropped(self):
        """The whole point of G23: an attachment that vanishes on the way is indistinguishable
        to the sender from one that arrived, so a malformed one has to come back as an error."""
        # True is in this list because bool subclasses int, and a bare `if value` test would let
        # it through; a dict and a list are what a confused client sends instead of a string.
        for key in ("link", "hashtag"):
            for value in (7, True, 1.5, ["https://example.com/a"], {"url": "x"}):
                with self.subTest(key=key, value=value):
                    status, body = self.send(**{key: value})

                    self.assertEqual(400, status)
                    self.assertEqual("bad_request", body["error"])

        self.assertEqual([], self.fcm_client.calls)

    def test_an_over_long_attachment_is_rejected(self):
        cases = (
            ("link", "h" * (MAX_LINK_BYTES + 1)),
            ("hashtag", "#" * (MAX_HASHTAG_BYTES + 1)),
        )
        for key, value in cases:
            with self.subTest(key=key):
                status, body = self.send(**{key: value})

                self.assertEqual(400, status)
                self.assertEqual("bad_request", body["error"])

        self.assertEqual([], self.fcm_client.calls)

    def test_the_limit_counts_bytes_and_not_characters(self):
        """FCM's data payload is capped in BYTES. A code-point bound would let this through, and
        Google would then reject the push - surfacing to the sender as a 502 that no retry can
        clear, for a link the app itself declared acceptable."""
        # Well under MAX_LINK_BYTES as characters, well over it as UTF-8.
        link = "\U0001f600" * (MAX_LINK_BYTES // 3)
        self.assertLess(len(link), MAX_LINK_BYTES)
        self.assertGreater(len(link.encode("utf-8")), MAX_LINK_BYTES)

        status, body = self.send(link=link)

        self.assertEqual(400, status)
        self.assertEqual("bad_request", body["error"])
        self.assertEqual([], self.fcm_client.calls)

    def test_a_multibyte_attachment_within_the_byte_limit_is_accepted(self):
        """The byte bound must not become a blanket ban on non-ASCII: a hashtag in a language
        that needs multiple bytes per character is ordinary use, not an attack."""
        hashtag = "šđčćž" * 4

        status, _ = self.send(hashtag=hashtag)

        self.assertEqual(200, status)
        self.assertEqual(
            [("bob-token", "ALICE", None, hashtag)],
            self.fcm_client.attachment_calls,
        )

    def test_an_attachment_exactly_at_the_limit_is_accepted(self):
        """The bound is inclusive, so the rejection above is the length above the limit rather
        than an off-by-one that also refuses a legal link."""
        link = "h" * MAX_LINK_BYTES
        # A hashtag of the limit's length, not a hashtag of that many '#'. The leading hashes are
        # stripped before the charset rule runs, so "#" * 140 is an empty tag rather than a long
        # one and would be refused for a reason that has nothing to do with the bound under test.
        hashtag = "h" * MAX_HASHTAG_BYTES

        status, _ = self.send(link=link, hashtag=hashtag)

        self.assertEqual(200, status)
        self.assertEqual(
            [("bob-token", "ALICE", link, hashtag)],
            self.fcm_client.attachment_calls,
        )

    def test_a_homoglyph_hashtag_cannot_forge_the_notification_body(self):
        """RED-FIRST: five of \\w's own letters render as nothing in every mainstream renderer
        (four Hangul fillers plus the Greek ypogegrammeni). A charset rule keyed on Unicode
        category alone still calls them letters, so a hashtag spelled with these instead of
        ASCII spaces reproduces the exact forgery the ASCII-space rule was written to stop -
        without ever containing a literal space or the app's own "·" separator. U+1427
        (a real letter, Canadian syllabics) is deliberately included and must NOT be stripped:
        it is the missing whitespace that makes a forgery legible, not the dot."""
        filler = "ㅤ"  # HANGUL FILLER - Unicode category Lo, renders as nothing
        dot = "ᐧ"  # CANADIAN SYLLABICS FINAL MIDDLE DOT - a real letter, stays allowed
        hostile = (
            "x"
            + filler * 2
            + dot
            + filler * 2
            + "TAP"
            + filler
            + "TO"
            + filler
            + "OPEN"
            + filler
            + "paypal"
            + dot
            + "com"
        )

        status, body = self.send(hashtag=hostile)

        self.assertEqual(200, status)
        delivered_hashtag = self.fcm_client.attachment_calls[-1][3]
        for blank_codepoint in "ͺᅟᅠㅤﾠ":
            self.assertNotIn(
                blank_codepoint,
                delivered_hashtag,
                f"delivered hashtag {delivered_hashtag!r} still carries a blank-rendering "
                "code point, which forges the app's own separator",
            )
        # The dot survives - it is a real letter in a real script - but with the fillers gone
        # there is no whitespace left to make it read as a separator between words.
        self.assertIn(dot, delivered_hashtag)

    def test_a_hashtag_cannot_forge_the_notification_body(self):
        """The recipient's notification body is built by interpolating the hashtag between the
        app's own separators, and the app writes "TAP TO OPEN <host>" there for a real link. A
        hashtag carrying that wording used to put a second, attacker-chosen tap promise in
        somebody else's shade under a sender name they recognise, by evading a charset rule
        expressed as `\\A[\\w-]+\\Z` - every one of these cases relies on a literal ASCII space
        or the "·" separator, both of which that rule already refused. That made the rule look
        sufficient without proving it, because a hashtag spelled with a blank-rendering LETTER
        instead of a space passed the exact same rule (see
        test_a_homoglyph_hashtag_cannot_forge_the_notification_body).

        The real property, asserted here instead of seven examples that were all true by
        construction: a hashtag with at least one legal character is now delivered - sanitised,
        not 400ed, because rejecting the whole attachment over one bad character was G25's
        retry loop - and whatever reaches the fcm client contains no space, no "·", and none of
        the five letters that render as either."""
        forgeries_with_a_survivor = (
            "x  ·  TAP TO OPEN paypal.com",
            "x\nFrom BANK",
            "x TAP TO OPEN",
            "· evil.com",
            "a b",
        )

        for hashtag in forgeries_with_a_survivor:
            with self.subTest(hashtag=hashtag):
                self.fcm_client.attachment_calls.clear()

                status, body = self.send(hashtag=hashtag)

                self.assertEqual(200, status)
                self.assertEqual({"delivered": True}, body)
                delivered_hashtag = self.fcm_client.attachment_calls[-1][3]
                self.assertNotIn(" ", delivered_hashtag)
                self.assertNotIn("·", delivered_hashtag)
                for blank_codepoint in "ͺᅟᅠㅤﾠ":
                    self.assertNotIn(blank_codepoint, delivered_hashtag)

        # Sanitising can still land on nothing, and an attachment that vanishes is exactly the
        # case _optional_attachment's own docstring exists to catch - so these still 400.
        for hashtag in ("#", "###"):
            with self.subTest(hashtag=hashtag):
                self.fcm_client.attachment_calls.clear()

                status, body = self.send(hashtag=hashtag)

                self.assertEqual(400, status)
                self.assertEqual("bad_request", body["error"])
                self.assertEqual([], self.fcm_client.attachment_calls)

    def test_a_real_hashtag_still_passes(self):
        """The charset rule is Unicode-aware on purpose: a Croatian or Chinese tag is a hashtag,
        and refusing it to stop an ASCII forgery would be the wrong trade."""
        for hashtag in ("worldcup", "#worldcup", "world_cup", "world-cup", "世界", "šđč"):
            with self.subTest(hashtag=hashtag):
                self.fcm_client.attachment_calls.clear()

                status, _ = self.send(hashtag=hashtag)

                self.assertEqual(200, status)
                self.assertEqual(1, len(self.fcm_client.attachment_calls))

    def test_a_devanagari_hashtag_keeps_its_vowel_signs(self):
        """RED-FIRST: [\\w-] does not cover Unicode categories Mn (non-spacing mark) or Mc
        (spacing combining mark), so HASHTAG_PATTERN stripped a vowel sign or virama exactly as
        unconditionally as it strips a literal space. Those marks are not decoration - a script
        assigns them the meaning that distinguishes one word from another, and they belong to
        the consonant in front of them. "नमस्ते" (Devanagari for "namaste") is न+म+स+् (virama,
        Mn)+त+े (vowel sign E, Mn); stripping the two Mn marks reduces it to "नमसत", a bare
        consonant skeleton that is a different, nonsensical string, not a narrowed version of
        the same word. A rule that widens correctly must deliver the hashtag unchanged."""
        hashtag = "नमस्ते"

        status, body = self.send(hashtag=hashtag)

        self.assertEqual(200, status)
        self.assertEqual({"delivered": True}, body)
        self.assertEqual(
            [("bob-token", "ALICE", None, hashtag)],
            self.fcm_client.attachment_calls,
        )

    def test_a_hashtag_and_the_client_agree_on_the_same_survivors(self):
        """The shared table: every one of these must come out EXACTLY as written here, and
        `HashtagRuleTest`'s `hostile input sanitises to the exact expected survivor` case in the
        Kotlin suite asserts the identical pairs, so client and server cannot silently drift
        onto two different rules again the way `sanitised, not rejected` almost did. Vocalised
        Arabic ("مَرْحَبًا", marhaban) keeps its harakat, and a space between two Devanagari words
        is still removed exactly as before. "#नमस्ते" keeps its leading hash for the same
        pre-existing reason "#yo" does above - see _optional_attachment's docstring: the hash is
        only dropped when sanitising the rest of the string actually changed something."""
        cases = (
            ("नमस्ते", "नमस्ते"),
            ("#नमस्ते", "#नमस्ते"),
            ("مَرْحَبًا", "مَرْحَبًا"),
            ("नमस्ते दुनिया", "नमस्तेदुनिया"),
            ("world cup", "worldcup"),
            ("world_cup", "world_cup"),
        )
        for hashtag, expected in cases:
            with self.subTest(hashtag=hashtag):
                self.fcm_client.attachment_calls.clear()

                status, body = self.send(hashtag=hashtag)

                self.assertEqual(200, status)
                self.assertEqual({"delivered": True}, body)
                self.assertEqual(
                    [("bob-token", "ALICE", None, expected)],
                    self.fcm_client.attachment_calls,
                )

    def test_a_hashtag_of_only_combining_marks_is_rejected(self):
        """A combining mark has no meaning without the base character it modifies - a lone vowel
        sign or accent renders as garbage attached to whatever precedes the hashtag in the
        notification body, not as a word of its own. Widening the charset to admit Mn/Mc must
        not let a hashtag consisting ENTIRELY of such marks through: it has to be treated the
        same as any other attachment that sanitises to nothing (see
        test_a_hashtag_cannot_forge_the_notification_body's "#"/"###" cases)."""
        pure_mark_hashtags = (
            "́",  # COMBINING ACUTE ACCENT alone, category Mn
            "्",  # DEVANAGARI SIGN VIRAMA alone, category Mn
            "्́",  # more than one mark, still no base character
            "#́",  # the leading hash is stripped first, still nothing but a mark left
        )
        for hashtag in pure_mark_hashtags:
            with self.subTest(hashtag=repr(hashtag)):
                self.fcm_client.attachment_calls.clear()

                status, body = self.send(hashtag=hashtag)

                self.assertEqual(400, status)
                self.assertEqual("bad_request", body["error"])
                self.assertEqual([], self.fcm_client.attachment_calls)

    def test_mn_and_mc_can_never_be_space_or_middle_dot(self):
        """Widening HASHTAG_PATTERN to admit Mn/Mc must not reopen the forgery the five
        blank-rendering codepoints and the base [\\w-] rule exist to stop. Unicode's General
        Category is a strict partition - a code point is never in two categories at once - so
        proving the literal space and the app's own '·' separator are NOT category Mn or Mc is a
        structural guarantee, not a sample: nothing admitted by HASHTAG_MARK_CATEGORIES can BE
        either character. None of the five codepoints excluded by exact value is Mn/Mc either
        (they are Lm/Lo), so this widening cannot let any of those back in."""
        self.assertNotIn(unicodedata.category(" "), yo_server.HASHTAG_MARK_CATEGORIES)
        self.assertNotIn(unicodedata.category("·"), yo_server.HASHTAG_MARK_CATEGORIES)
        for codepoint in "ͺᅟᅠㅤﾠ":
            self.assertNotIn(unicodedata.category(codepoint), yo_server.HASHTAG_MARK_CATEGORIES)

    def test_a_zero_width_mark_cannot_recreate_visible_word_separation(self):
        """A combining mark draws on the character before it rather than occupying a column of
        its own, so - unlike the five excluded letters above, which render as blank WIDTH -
        admitting one cannot reproduce the visible word-separating gap that made "TAP TO OPEN"
        legible as three words. U+034F (COMBINING GRAPHEME JOINER, category Mn) is now admitted
        rather than stripped, but it carries no width: the base letters it sits between still
        read as run together, not as separate words, so nothing about this hostile string
        becomes any more legible than it was before this widening."""
        cgj = "͏"
        hostile = "TAP" + cgj + "TO" + cgj + "OPEN"

        status, body = self.send(hashtag=hostile)

        self.assertEqual(200, status)
        delivered_hashtag = self.fcm_client.attachment_calls[-1][3]
        self.assertNotIn(" ", delivered_hashtag)
        self.assertNotIn("·", delivered_hashtag)

    def test_an_astral_letter_this_process_barely_knows_about_still_sends(self):
        """G30 was a category-shaped charset rule; this is the OTHER way one diverges. A modern
        handset's ICU and this process's own Unicode table disagree on which astral code points
        are even assigned yet - U+2EBF0 (CJK Extension I, real characters in real Chinese names)
        is a real letter to a handset that resolves against a newer table than this process
        does. Rejecting it 400s a hashtag the sending phone considered perfectly legal, and the
        client's retry re-issues that identical doomed request forever (G25). Sanitising rather
        than rejecting means this process's own table only ever narrows what survives - it can
        never fail the whole Yo over a character it simply hasn't caught up to yet."""
        astral_letter = "\U0002ebf0"
        hashtag = "family" + astral_letter

        status, body = self.send(hashtag=hashtag)

        self.assertEqual(200, status)
        self.assertEqual({"delivered": True}, body)
        self.assertEqual(
            [("bob-token", "ALICE", None, hashtag)],
            self.fcm_client.attachment_calls,
        )

    def test_each_blank_rendering_letter_is_stripped_rather_than_rejected(self):
        """U+037A, U+115F, U+1160, U+3164 and U+FFA0 are exactly the code points HASHTAG_PATTERN
        now treats as disallowed even though Python's own \\w calls every one of them a letter -
        each renders as nothing (or as blank whitespace) in every mainstream renderer, which is
        what let G30's forgery hide inside a rule that only checked Unicode category. Checked
        individually, so a future change to the excluded set is caught at the one code point
        that regressed rather than only by a combined string."""
        blank_codepoints = ("ͺ", "ᅟ", "ᅠ", "ㅤ", "ﾠ")
        for codepoint in blank_codepoints:
            with self.subTest(codepoint=hex(ord(codepoint))):
                self.fcm_client.attachment_calls.clear()
                hashtag = "a" + codepoint + "b"

                status, body = self.send(hashtag=hashtag)

                self.assertEqual(200, status)
                self.assertEqual({"delivered": True}, body)
                self.assertEqual(
                    [("bob-token", "ALICE", None, "ab")],
                    self.fcm_client.attachment_calls,
                )

    def test_the_200_response_never_echoes_the_sanitised_hashtag(self):
        """The blocked-sender path returns `{"delivered": True}` with nothing else, on purpose -
        indistinguishable from a real delivery is the whole point of that path. Echoing the
        sanitised hashtag back here, on the path that actually sends, would reopen exactly that
        gap: a sender could tell the two paths apart by whether the response body carried a
        hashtag at all."""
        status, body = self.send(hashtag="world cup")

        self.assertEqual(200, status)
        self.assertEqual({"delivered": True}, body)

    def test_an_attachment_is_never_written_to_the_database(self):
        """The privacy policy claims a link and a hashtag pass through without being stored, so
        the schema is asserted rather than trusted. If a future change starts persisting either,
        the live policy becomes false the moment it deploys."""
        marker = "https://example.invalid/never-stored-3f9a2c"
        hashtag_marker = "#never-stored-3f9a2c"

        status, _ = self.send(link=marker, hashtag=hashtag_marker)
        self.assertEqual(200, status)

        connection = sqlite3.connect(self.database_path)
        self.addCleanup(connection.close)
        tables = [
            row[0]
            for row in connection.execute(
                "SELECT name FROM sqlite_master WHERE type = 'table'"
            )
        ]
        self.assertNotEqual([], tables, "the schema was empty, so this proved nothing")
        for table in tables:
            with self.subTest(table=table):
                columns = [
                    row[1] for row in connection.execute(f'PRAGMA table_info("{table}")')
                ]
                self.assertEqual(
                    [],
                    [name for name in columns if name in ("link", "hashtag")],
                )
                stored = " ".join(
                    str(value)
                    for row in connection.execute(f'SELECT * FROM "{table}"')
                    for value in row
                )
                self.assertNotIn(marker, stored)
                self.assertNotIn(hashtag_marker, stored)


class RemovedPhotoRoutesTest(YoServerTestCase):
    """The photo upload was write-only: the app had no way to fetch one and the push carried no
    message id, so no recipient could ever retrieve what was stored for them. It is gone, along
    with its table, and this is what keeps it gone.

    Every request here carries a valid token on purpose. Authentication runs before path
    matching, so an unauthenticated probe answers 401 for any unknown path and would pass just
    as happily whether or not the routes still existed.
    """

    def test_uploading_a_photo_is_no_longer_a_route(self):
        token = self.signup("ALICE")

        status, body = self.request(
            "POST",
            "/v1/photo",
            {"message_id": "message-1", "mime_type": "image/jpeg", "data": "d"},
            token=token,
        )

        self.assertEqual(404, status)
        self.assertEqual({"error": "not_found"}, body)

    def test_fetching_a_photo_is_no_longer_a_route(self):
        token = self.signup("ALICE")

        status, body = self.request(
            "GET",
            "/v1/photo?message_id=message-1",
            token=token,
        )

        self.assertEqual(404, status)
        self.assertEqual({"error": "not_found"}, body)

    def test_an_unauthenticated_probe_could_not_have_told_them_apart(self):
        """States the trap the two tests above are written around, so nobody later 'simplifies'
        them into a pair that would pass against a server still serving photos."""
        for method, path, body in (
            ("GET", "/v1/photo?message_id=message-1", None),
            ("POST", "/v1/photo", {"message_id": "message-1"}),
        ):
            with self.subTest(method=method):
                status, response = self.request(method, path, body, token=None)

                self.assertEqual(401, status)
                self.assertEqual({"error": "unauthorized"}, response)

    def test_the_photos_table_is_no_longer_created(self):
        """It was never pruned, so leaving the schema behind leaves unbounded storage of data
        the privacy policy no longer describes."""
        connection = sqlite3.connect(self.database_path)
        self.addCleanup(connection.close)

        tables = {
            row[0]
            for row in connection.execute(
                "SELECT name FROM sqlite_master WHERE type = 'table'"
            )
        }

        self.assertNotIn("photos", tables)


class SendTest(YoServerTestCase):
    def test_send_calls_fcm_client_for_registered_recipient(self):
        alice_token = self.signup("ALICE")
        self.signup_with_device("BOB", "bob-token")

        status, body = self.request(
            "POST",
            "/v1/send",
            {"recipient": "bob"},
            token=alice_token,
        )

        self.assertEqual(200, status)
        self.assertEqual({"delivered": True}, body)
        self.assertEqual([("bob-token", "ALICE")], self.fcm_client.calls)

    def test_the_sender_comes_from_the_token_and_cannot_be_spoofed(self):
        alice_token = self.signup("ALICE")
        self.signup("CHARLIE")
        self.signup_with_device("BOB", "bob-token")

        status, _ = self.request(
            "POST",
            "/v1/send",
            {"recipient": "BOB", "sender": "CHARLIE"},
            token=alice_token,
        )

        self.assertEqual(200, status)
        self.assertEqual([("bob-token", "ALICE")], self.fcm_client.calls)

    def test_send_with_unconfigured_fcm_returns_false(self):
        alice_token = self.signup("ALICE")
        self.signup_with_device("BOB", "bob-token")
        self.fcm_client.error = FCMNotConfiguredError("not configured")

        status, body = self.request(
            "POST",
            "/v1/send",
            {"recipient": "BOB"},
            token=alice_token,
        )

        self.assertEqual(200, status)
        self.assertEqual(
            {"delivered": False, "reason": "fcm_not_configured"},
            body,
        )

    def test_send_with_a_delivery_failure_returns_502(self):
        alice_token = self.signup("ALICE")
        self.signup_with_device("BOB", "bob-token")
        self.fcm_client.fail_tokens.add("bob-token")

        status, body = self.request(
            "POST",
            "/v1/send",
            {"recipient": "BOB"},
            token=alice_token,
        )

        self.assertEqual(502, status)
        self.assertEqual(
            {"delivered": False, "reason": "fcm_delivery_failed"},
            body,
        )

    def test_send_with_missing_recipient_returns_404(self):
        alice_token = self.signup("ALICE")

        status, body = self.request(
            "POST",
            "/v1/send",
            {"recipient": "MISSING"},
            token=alice_token,
        )

        self.assertEqual(404, status)
        self.assertEqual(
            {"delivered": False, "reason": "recipient_not_found"},
            body,
        )
        self.assertEqual([], self.fcm_client.calls)

    def test_a_real_account_with_no_device_is_not_reported_as_missing(self):
        """Otherwise a friend whose registration failed is announced as a nonexistent user."""
        alice_token = self.signup("ALICE")
        self.signup("BOB")  # signs up, never registers a device

        status, body = self.request(
            "POST",
            "/v1/send",
            {"recipient": "BOB"},
            token=alice_token,
        )

        self.assertEqual(404, status)
        self.assertEqual(
            {"delivered": False, "reason": "recipient_unregistered"},
            body,
        )
        self.assertEqual([], self.fcm_client.calls)

    def test_the_two_unreachable_reasons_are_distinguishable(self):
        alice_token = self.signup("ALICE")
        self.signup("BOB")

        _, unregistered = self.request(
            "POST", "/v1/send", {"recipient": "BOB"}, token=alice_token
        )
        _, absent = self.request(
            "POST", "/v1/send", {"recipient": "NOBODY"}, token=alice_token
        )

        self.assertNotEqual(unregistered["reason"], absent["reason"])

    def test_a_registering_recipient_becomes_reachable(self):
        alice_token = self.signup("ALICE")
        bob_token = self.signup("BOB")

        _, before = self.request(
            "POST", "/v1/send", {"recipient": "BOB"}, token=alice_token
        )
        self.request("POST", "/v1/register", {"fcm_token": "bob-token"}, token=bob_token)
        status, after = self.request(
            "POST", "/v1/send", {"recipient": "BOB"}, token=alice_token
        )

        self.assertEqual("recipient_unregistered", before["reason"])
        self.assertEqual(200, status)
        self.assertEqual({"delivered": True}, after)

    def test_a_blocked_sender_is_silently_dropped(self):
        """The response has to look delivered, or the block becomes a notification."""
        alice_token = self.signup("ALICE")
        bob_token = self.signup_with_device("BOB", "bob-token")
        self.request("POST", "/v1/block", {"username": "ALICE"}, token=bob_token)

        status, body = self.request(
            "POST",
            "/v1/send",
            {"recipient": "BOB"},
            token=alice_token,
        )

        self.assertEqual(200, status)
        self.assertEqual({"delivered": True}, body)
        self.assertEqual([], self.fcm_client.calls)

    def test_a_block_is_one_directional(self):
        alice_token = self.signup_with_device("ALICE", "alice-token")
        bob_token = self.signup_with_device("BOB", "bob-token")
        self.request("POST", "/v1/block", {"username": "BOB"}, token=alice_token)

        status, _ = self.request(
            "POST",
            "/v1/send",
            {"recipient": "ALICE"},
            token=bob_token,
        )

        # ALICE blocked BOB, so BOB may not reach ALICE.
        self.assertEqual(200, status)
        self.assertEqual([], self.fcm_client.calls)

        self.request("POST", "/v1/send", {"recipient": "BOB"}, token=alice_token)
        self.assertEqual([("bob-token", "ALICE")], self.fcm_client.calls)

    def test_send_is_rate_limited_per_sender(self):
        self.server.send_limiter = RateLimiter(1, SEND_WINDOW_SECONDS)
        alice_token = self.signup("ALICE")
        bob_token = self.signup_with_device("BOB", "bob-token")
        self.signup_with_device("CHARLIE", "charlie-token")

        first, _ = self.request(
            "POST",
            "/v1/send",
            {"recipient": "BOB"},
            token=alice_token,
        )
        second, body = self.request(
            "POST",
            "/v1/send",
            {"recipient": "CHARLIE"},
            token=alice_token,
        )
        other_sender, _ = self.request(
            "POST",
            "/v1/send",
            {"recipient": "CHARLIE"},
            token=bob_token,
        )

        self.assertEqual(200, first)
        self.assertEqual(429, second)
        self.assertEqual({"delivered": False, "reason": "rate_limited"}, body)
        self.assertEqual(200, other_sender, "the limit is per sender, not global")

    def test_send_requires_a_recipient(self):
        alice_token = self.signup("ALICE")

        status, body = self.request("POST", "/v1/send", {}, token=alice_token)

        self.assertEqual(400, status)
        self.assertEqual("bad_request", body["error"])


class BlockedVsUnblockedStatusParityTest(YoServerTestCase):
    """G9: the block check used to run BEFORE the device lookup, so a recipient with no
    registered device answered 200 {"delivered": true} for a blocked sender and 404
    recipient_unregistered for an unblocked one - the status code itself was the oracle, no
    timing needed. Asserted as a property: the same send, once blocked and once not, against
    otherwise identical fixtures, must answer with the identical (status, body)."""

    def _run(self, recipient_state, blocked):
        """Build one fresh sender/recipient pair in the given recipient_state, block the sender
        if asked, send, and report (status, body, how many NEW fcm calls that send made)."""
        suffix = f"{recipient_state}_{'blocked' if blocked else 'open'}".upper()
        sender_name = f"SENDER_{suffix}"
        recipient_name = f"RECIPIENT_{suffix}"
        sender_token = self.signup(sender_name)

        if recipient_state == "no_account":
            pass
        elif recipient_state == "no_device":
            self.signup(recipient_name)
        elif recipient_state == "with_device":
            self.signup_with_device(recipient_name)
        else:
            raise AssertionError(f"unknown recipient_state: {recipient_state}")

        if blocked:
            # The HTTP route (`_handle_block`) requires `account_exists`, which the no_account
            # case cannot satisfy - this reaches straight into the primitive it is built on to
            # hold recipient_state and blocked-ness independent, exactly like every other case.
            self.server.database.block(recipient_name, sender_name)

        calls_before = len(self.fcm_client.calls)
        status, body = self.request(
            "POST", "/v1/send", {"recipient": recipient_name}, token=sender_token
        )
        return status, body, len(self.fcm_client.calls) - calls_before

    def _assert_parity(self, recipient_state):
        open_status, open_body, open_calls = self._run(recipient_state, blocked=False)
        blocked_status, blocked_body, blocked_calls = self._run(recipient_state, blocked=True)
        self.assertEqual((open_status, open_body), (blocked_status, blocked_body))
        return open_status, open_body, open_calls, blocked_calls

    def test_parity_when_the_recipient_has_no_account(self):
        self._assert_parity("no_account")

    def test_parity_when_the_recipient_has_no_device(self):
        self._assert_parity("no_device")

    def test_parity_when_the_recipient_has_a_device(self):
        status, body, open_calls, blocked_calls = self._assert_parity("with_device")
        self.assertEqual(200, status)
        self.assertEqual({"delivered": True}, body)
        self.assertEqual(1, open_calls, "the unblocked run must reach FCM")
        self.assertEqual(0, blocked_calls, "the blocked run must never reach FCM")

    def test_a_blocked_send_still_consumes_the_senders_rate_limit(self):
        """Otherwise the quota counter becomes the oracle the status code no longer is."""
        self.server.send_limiter = RateLimiter(1, SEND_WINDOW_SECONDS)
        sender_token = self.signup("SENDER")
        self.signup_with_device("BLOCKER")
        self.server.database.block("BLOCKER", "SENDER")
        self.signup("OTHER")

        first_status, _ = self.request(
            "POST", "/v1/send", {"recipient": "BLOCKER"}, token=sender_token
        )
        second_status, second_body = self.request(
            "POST", "/v1/send", {"recipient": "OTHER"}, token=sender_token
        )

        self.assertEqual(200, first_status)
        self.assertEqual(429, second_status)
        self.assertEqual({"delivered": False, "reason": "rate_limited"}, second_body)


class BroadcastTest(YoServerTestCase):
    """POST /v1/broadcast still uses client-id/client-key, not per-user tokens."""

    client_key = "fedex-client-key"

    def client_headers(self, key=None):
        return {
            "X-Yo-Client-Id": "fedex",
            "X-Yo-Client-Key": self.client_key if key is None else key,
        }

    def register_client(self):
        self.server.database.upsert_api_client(
            "fedex",
            _hash_client_key(self.client_key),
        )

    def subscribe_two_devices(self):
        self.server.database.upsert_device("ALICE", "alice-token")
        self.server.database.upsert_device("BOB", "bob-token")
        self.server.database.add_subscription("fedex", "alice")
        self.server.database.add_subscription("fedex", "bob")

    def test_broadcast_delivers_to_all_subscribers(self):
        self.register_client()
        self.subscribe_two_devices()

        status, body = self.request(
            "POST",
            "/v1/broadcast",
            {},
            token=None,
            extra_headers=self.client_headers(),
        )

        self.assertEqual(200, status)
        self.assertEqual(
            {"delivered": 2, "failed": 0, "subscriber_count": 2},
            body,
        )
        self.assertEqual(
            [
                ("alice-token", "fedex"),
                ("bob-token", "fedex"),
            ],
            self.fcm_client.calls,
        )

    def test_broadcast_credentials_are_rate_limited(self):
        """This was the one credential-checking route with no limiter at all, so a client key
        could be guessed at line rate while signup and login were held to ten attempts. It
        shares their bucket on purpose: an attacker must not be able to dodge the limit by
        moving between routes, which is the property CredentialTest already asserts for the
        signup/login pair."""
        self.register_client()

        for _ in range(CREDENTIAL_ATTEMPTS):
            self.request(
                "POST",
                "/v1/broadcast",
                {},
                token=None,
                extra_headers={"X-Yo-Client-Id": "fedex", "X-Yo-Client-Key": "wrong"},
            )

        status, body = self.request(
            "POST",
            "/v1/broadcast",
            {},
            token=None,
            extra_headers={"X-Yo-Client-Id": "fedex", "X-Yo-Client-Key": "wrong"},
        )

        self.assertEqual(429, status)
        self.assertEqual({"error": "rate_limited"}, body)
        self.assertEqual([], self.fcm_client.calls)

    def test_a_broadcast_refuses_a_message_rather_than_dropping_it(self):
        """This test used to send {"message": "Package update"} and assert the fan-out below,
        which recorded only (token, sender) - so it passed while the text reached nobody. The
        field was validated and then never handed to send_yo. A caller told their message went
        out to every subscriber, when no subscriber could ever see it, is G20's defect wearing
        a different route; a Yo carries no content, so the field is refused instead."""
        self.register_client()
        self.subscribe_two_devices()

        status, body = self.request(
            "POST",
            "/v1/broadcast",
            {"message": "Package update"},
            token=None,
            extra_headers=self.client_headers(),
        )

        self.assertEqual(400, status)
        self.assertEqual("bad_request", body["error"])
        self.assertEqual([], self.fcm_client.calls)

    def test_broadcast_rejects_wrong_or_missing_client_credentials(self):
        self.register_client()
        user_token = self.signup("ALICE")
        credentials = (
            (None, None),
            (user_token, None),
            (None, self.client_headers(key="wrong-key")),
            (None, {"X-Yo-Client-Id": "fedex"}),
        )

        for token, extra_headers in credentials:
            with self.subTest(token=token, extra_headers=extra_headers):
                status, body = self.request(
                    "POST",
                    "/v1/broadcast",
                    {},
                    token=token,
                    extra_headers=extra_headers,
                )

                self.assertEqual(401, status)
                self.assertEqual({"error": "unauthorized"}, body)
        self.assertEqual([], self.fcm_client.calls)

    def test_broadcast_rejects_unknown_client_id(self):
        status, body = self.request(
            "POST",
            "/v1/broadcast",
            {},
            token=None,
            extra_headers={
                "X-Yo-Client-Id": "unknown",
                "X-Yo-Client-Key": "unknown-key",
            },
        )

        self.assertEqual(401, status)
        self.assertEqual({"error": "unauthorized"}, body)
        self.assertEqual([], self.fcm_client.calls)

    def test_broadcast_with_no_subscribers_returns_zero_counts(self):
        self.register_client()

        status, body = self.request(
            "POST",
            "/v1/broadcast",
            {},
            token=None,
            extra_headers=self.client_headers(),
        )

        self.assertEqual(200, status)
        self.assertEqual(
            {"delivered": 0, "failed": 0, "subscriber_count": 0},
            body,
        )
        self.assertEqual([], self.fcm_client.calls)

    def test_broadcast_continues_past_single_delivery_failure(self):
        self.register_client()
        self.subscribe_two_devices()
        self.fcm_client.fail_tokens.add("alice-token")

        status, body = self.request(
            "POST",
            "/v1/broadcast",
            {},
            token=None,
            extra_headers=self.client_headers(),
        )

        self.assertEqual(200, status)
        self.assertEqual(
            {"delivered": 1, "failed": 1, "subscriber_count": 2},
            body,
        )
        self.assertEqual(
            [
                ("alice-token", "fedex"),
                ("bob-token", "fedex"),
            ],
            self.fcm_client.calls,
        )

    def test_broadcast_with_unconfigured_fcm_short_circuits(self):
        self.register_client()
        self.subscribe_two_devices()
        self.fcm_client.error = FCMNotConfiguredError("not configured")

        status, body = self.request(
            "POST",
            "/v1/broadcast",
            {},
            token=None,
            extra_headers=self.client_headers(),
        )

        self.assertEqual(200, status)
        self.assertEqual(
            {
                "delivered": 0,
                "failed": 0,
                "subscriber_count": 2,
                "reason": "fcm_not_configured",
            },
            body,
        )
        self.assertEqual(
            [("alice-token", "fedex")],
            self.fcm_client.calls,
        )


class StaticPageTestCase(unittest.TestCase):
    """Harness for the unauthenticated HTML routes: install, privacy, delete-account.

    Deliberately NOT built on YoServerTestCase: inheriting would re-run every API test a second
    time under a new name. These routes never touch the database, so the stub below is enough.
    """

    def setUp(self):
        self.server = SimpleNamespace(
            database=None,
            fcm_client=None,
            password_iterations=TEST_ITERATIONS,
            credential_limiter=None,
            send_limiter=None,
        )

    def raw_request(self, method, path):
        """Returns the head and undecoded body, since these responses are HTML and binary."""
        handler = YoRequestHandler.__new__(YoRequestHandler)
        handler.command = method
        handler.path = path
        handler.request_version = "HTTP/1.1"
        handler.requestline = f"{method} {path} HTTP/1.1"
        handler.headers = Message()
        handler.rfile = io.BytesIO(b"")
        handler.wfile = io.BytesIO()
        handler.server = self.server
        handler.client_address = ("127.0.0.1", 0)
        handler.log_message = lambda *_: None
        handler.do_GET()

        response_head, response_body = handler.wfile.getvalue().split(b"\r\n\r\n", 1)
        status = int(response_head.splitlines()[0].decode("ascii").split(" ", 2)[1])
        return status, response_head.decode("latin-1"), response_body


class InstallPageTest(StaticPageTestCase):
    """The install page is the landing target of shared invite links."""

    def test_install_page_is_public_because_invitees_have_no_credential(self):
        status, head, body = self.raw_request("GET", "/install")

        self.assertEqual(200, status)
        self.assertIn("text/html", head)
        self.assertIn(b"<h1>Yo</h1>", body)
        self.assertIn(b"It's that simple.", body)
        self.assertIn(b"#9B59B6", body)

    def test_install_page_never_ships_a_bootstrap_credential(self):
        """The whole point of G3 was killing the baked-in key; do not reintroduce one here."""
        _, head, body = self.raw_request("GET", "/install")

        for secret_marker in (b"YO_SERVER_KEY", b"X-Yo-Key", b"X-Yo-Token", b"Bearer "):
            with self.subTest(marker=secret_marker):
                self.assertNotIn(secret_marker, body)
                self.assertNotIn(secret_marker.decode("latin-1"), head)

    def test_trailing_slash_serves_the_same_page(self):
        status, _, body = self.raw_request("GET", "/install/")

        self.assertEqual(200, status)
        self.assertIn(b"<h1>Yo</h1>", body)

    def test_without_a_configured_apk_the_page_says_so_and_the_download_404s(self):
        os.environ.pop("YO_APK_PATH", None)

        _, _, page = self.raw_request("GET", "/install")
        self.assertIn(b"not published yet", page)
        self.assertNotIn(b"/install/yo.apk", page)

        status, _, _ = self.raw_request("GET", "/install/yo.apk")
        self.assertEqual(404, status)

    def test_with_a_configured_apk_the_page_links_it_and_the_download_serves_bytes(self):
        apk = tempfile.NamedTemporaryFile(suffix=".apk", delete=False)
        apk.write(b"PK\x03\x04 pretend-apk")
        apk.close()
        self.addCleanup(os.unlink, apk.name)
        os.environ["YO_APK_PATH"] = apk.name
        self.addCleanup(os.environ.pop, "YO_APK_PATH", None)

        _, _, page = self.raw_request("GET", "/install")
        self.assertIn(b"/install/yo.apk", page)

        status, head, body = self.raw_request("GET", "/install/yo.apk")
        self.assertEqual(200, status)
        self.assertIn("application/vnd.android.package-archive", head)
        self.assertEqual(b"PK\x03\x04 pretend-apk", body)

    def test_a_configured_but_missing_apk_falls_back_to_404_rather_than_crashing(self):
        os.environ["YO_APK_PATH"] = "/nonexistent/path/to/yo.apk"
        self.addCleanup(os.environ.pop, "YO_APK_PATH", None)

        status, _, _ = self.raw_request("GET", "/install/yo.apk")
        self.assertEqual(404, status)

    def test_adding_the_public_route_did_not_open_up_the_api(self):
        status, _, body = self.raw_request("GET", "/v1/friends")

        self.assertEqual(401, status)
        self.assertIn(b"unauthorized", body)


class PolicyPageTest(StaticPageTestCase):
    """The two URLs Google Play checks before it will list the app."""

    def test_the_privacy_policy_is_public(self):
        """Play fetches it without an account, so a 401 here fails review."""
        status, head, body = self.raw_request("GET", "/privacy")

        self.assertEqual(200, status)
        self.assertIn("text/html", head)
        self.assertIn(b"Yo privacy policy", body)

    def test_the_deletion_page_is_public(self):
        status, head, body = self.raw_request("GET", "/delete-account")

        self.assertEqual(200, status)
        self.assertIn("text/html", head)
        self.assertIn(b"Delete your Yo account", body)

    def test_the_privacy_policy_states_that_contacts_stay_on_the_device(self):
        """This is the app's strongest privacy claim and the one a reviewer will check against
        the READ_CONTACTS permission."""
        _, _, body = self.raw_request("GET", "/privacy")

        self.assertIn(b"never a phone number", body)

    def test_the_privacy_policy_points_at_the_deletion_route(self):
        _, _, body = self.raw_request("GET", "/privacy")

        self.assertIn(b"/delete-account", body)

    def test_the_privacy_policy_says_a_location_is_carried_and_never_written_down(self):
        """The load-bearing claim behind the Play data-safety declaration, and until now the one
        sentence on the page with no test under it. It states that an attached position is a
        single fix that travels inside the notification and is never stored; if that wording ever
        drifts away from the code, the declaration filed with Google becomes untrue."""
        _, _, body = self.raw_request("GET", "/privacy")

        self.assertIn(b"What passes through without being stored", body)
        self.assertIn(b"not written to our database", body)
        self.assertIn(b"There is no continuous tracking", body)
        self.assertIn(b"one fix, when you ask for it, for one message", body)

    def test_neither_page_mentions_photos_any_more(self):
        """The upload route and its table are gone. A policy still describing them is a false
        policy - which is the exact state the removal was meant to end, not to recreate."""
        for path in ("/privacy", "/delete-account"):
            with self.subTest(path=path):
                _, _, body = self.raw_request("GET", path)

                self.assertNotIn(b"photo", body.lower())

    def test_both_pages_fence_the_contact_address_against_cloudflare(self):
        """Cloudflare's email obfuscation rewrites a bare mailto into a /cdn-cgi/l/
        email-protection link that resolves only with JavaScript. That address is the only
        deletion route for somebody who cannot open the app, so it has to survive a reader that
        runs no scripts - which the email_off fence is what guarantees."""
        fenced = (
            b'<!--email_off--><a href="mailto:mladen@the-shop.io">'
            b"mladen@the-shop.io</a><!--/email_off-->"
        )
        for path in ("/privacy", "/delete-account"):
            with self.subTest(path=path):
                _, _, body = self.raw_request("GET", path)

                self.assertIn(fenced, body)
                self.assertNotIn(b"/cdn-cgi/l/email-protection", body)

    def test_both_pages_are_reachable_with_a_trailing_slash(self):
        for path in ("/privacy/", "/delete-account/"):
            status, _, _ = self.raw_request("GET", path)
            self.assertEqual(200, status, path)

    def test_the_pages_carry_no_credential(self):
        for path in ("/privacy", "/delete-account"):
            _, _, body = self.raw_request("GET", path)
            for secret_marker in (b"X-Yo-Token", b"Bearer ", b"YO_SERVER_KEY"):
                self.assertNotIn(secret_marker, body, path)

    def test_a_web_side_deletion_cannot_wipe_local_history_by_itself(self):
        """The delete-account page's whole audience is people who cannot open the app, so a
        promise that deletion erases their local history is untrue for every one of its
        visitors: no client-side wipe ever fires for them. The copy must say what actually
        happens - in-app deletion and logout erase it immediately, a deletion done for you is
        erased next time the app runs and finds the account gone, and uninstalling removes it
        only because there is no cloud copy - not repeat the old blanket claim."""
        _, _, privacy_raw = self.raw_request("GET", "/privacy")
        _, _, delete_raw = self.raw_request("GET", "/delete-account")
        privacy = " ".join(privacy_raw.decode("utf-8").split())
        delete_page = " ".join(delete_raw.decode("utf-8").split())

        self.assertIn("logging out, erases it immediately", privacy)
        self.assertIn("finds the account gone", privacy)
        self.assertNotIn("held on your own phone", delete_page)
        self.assertIn("cannot reach inside a phone we do not control", delete_page)

    def test_the_privacy_policy_names_cloudflare_and_hetzner_as_processors(self):
        """All traffic is proxied through Cloudflare, which terminates TLS and has demonstrably
        rewritten served HTML, and the database is hosted on Hetzner - so 'nothing is shared
        with anyone else' was untrue about the two companies that see or hold it. Both must be
        named, without a hyperlink (an existing test forbids one), as processors bound to our
        instructions rather than free agents."""
        _, _, raw = self.raw_request("GET", "/privacy")
        body = " ".join(raw.decode("utf-8").split())

        self.assertIn("Cloudflare", body)
        self.assertIn("Hetzner", body)
        self.assertIn("only on our instructions", body)

    def test_the_access_log_claim_matches_its_real_size_based_rotation(self):
        """Rotation is size-based - 10 MB times 3 files, verified in the compose config - with
        no time bound, so the policy must not invent a retention period for it. It must instead
        say the log is append-only, that a deletion cannot reach back into it, and that it
        rotates on size with the oldest file discarded first."""
        _, _, body = self.raw_request("GET", "/privacy")
        text = body.decode("utf-8")

        start = text.index("access log")
        end = text.index("</li>", start)
        log_bullet = text[start:end]

        self.assertIn("never edited", log_bullet)
        self.assertIn("rotates on size", log_bullet)
        self.assertIn("oldest", log_bullet)
        self.assertNotIn("30 days", log_bullet)
        self.assertIn("does not reach back into", text)

    def test_the_privacy_policy_lists_the_session_token(self):
        """The 'what we store' list omitted the session-token table entirely. Expiry is
        enforced on read and swept at sign-in, not on a timer, so the copy must say the key
        'stops working' after 90 days rather than implying a scheduled purge."""
        _, _, raw = self.raw_request("GET", "/privacy")
        body = " ".join(raw.decode("utf-8").split())

        self.assertIn("session key", body)
        self.assertIn("stops working 90 days after it was issued", body)
        self.assertIn("logging out removes that device", body)


class DeleteAccountTest(YoServerTestCase):
    """DELETE /v1/account - the in-app deletion Google Play requires."""

    def test_deleting_an_account_reports_what_it_deleted(self):
        token = self.signup("ALICE")

        status, body = self.request("DELETE", "/v1/account", token=token)

        self.assertEqual(200, status, body)
        self.assertEqual({"deleted": True, "username": "ALICE"}, body)
        self.assertFalse(self.server.database.account_exists("ALICE"))

    def test_deletion_requires_a_credential(self):
        self.signup("ALICE")

        status, body = self.request("DELETE", "/v1/account", token=None)

        self.assertEqual(401, status)
        self.assertTrue(self.server.database.account_exists("ALICE"))

    def test_the_token_stops_working_afterwards(self):
        token = self.signup("ALICE")
        self.request("DELETE", "/v1/account", token=token)

        status, _ = self.request("GET", "/v1/friends", token=token)

        self.assertEqual(401, status)

    def test_every_device_of_the_account_is_forgotten(self):
        """Sessions on other devices must die too, or the account is only half-deleted."""
        first = self.signup("ALICE")
        status, body = self.request(
            "POST",
            "/v1/login",
            {"username": "ALICE", "password": TEST_PASSWORD},
            token=None,
        )
        self.assertEqual(200, status, body)
        second = body["token"]

        self.request("DELETE", "/v1/account", token=first)

        self.assertEqual(401, self.request("GET", "/v1/friends", token=second)[0])

    def test_the_username_can_be_claimed_again(self):
        token = self.signup("ALICE")
        self.request("DELETE", "/v1/account", token=token)

        status, body = self.request(
            "POST",
            "/v1/signup",
            {"username": "ALICE", "password": TEST_PASSWORD},
            token=None,
        )

        self.assertEqual(201, status, body)

    def test_a_deleted_user_disappears_from_other_peoples_friend_lists(self):
        """The half-deletion that matters most: a name left in someone else's list is tappable,
        answers recipient_not_found, and cannot be removed by anyone."""
        alice = self.signup("ALICE")
        bob = self.signup("BOB")
        self.request("POST", "/v1/friends", {"username": "BOB"}, token=alice)

        self.request("DELETE", "/v1/account", token=bob)

        status, body = self.request("GET", "/v1/friends", token=alice)
        self.assertEqual(200, status)
        self.assertEqual([], body["friends"])

    def test_a_block_placed_on_the_deleted_user_survives_as_a_tombstone(self):
        """The row is the blocker's safety control, not the deleted user's data - it costs
        nothing to keep while the account behind `BOB` does not exist. `list_blocked` is asserted
        alongside `is_blocked` because it runs a different query and `is_blocked` alone would
        pass even for a row the list can no longer see."""
        alice = self.signup("ALICE")
        bob = self.signup("BOB")
        self.request("POST", "/v1/block", {"username": "BOB"}, token=alice)

        self.request("DELETE", "/v1/account", token=bob)

        self.assertTrue(self.server.database.is_blocked("ALICE", "BOB"))
        self.assertEqual(["BOB"], self.server.database.list_blocked("ALICE"))

    def test_a_deleted_users_own_blocks_are_cleared(self):
        """The direction that IS this user's data - blocks they placed on others - still goes."""
        alice = self.signup("ALICE")
        bob = self.signup("BOB")
        self.request("POST", "/v1/block", {"username": "ALICE"}, token=bob)

        self.request("DELETE", "/v1/account", token=bob)

        self.assertFalse(self.server.database.is_blocked("BOB", "ALICE"))
        self.assertEqual([], self.server.database.list_blocked("BOB"))

    def test_a_reclaimed_username_stays_blocked_by_the_tombstone(self):
        """End to end: ALICE blocks BOB, BOB deletes, BOB re-signs-up with the identical name and
        registers a device, BOB sends to ALICE - nothing may arrive. Without the tombstone this is
        a block-evasion path: delete, re-register the same name, reach the blocker again."""
        alice = self.signup_with_device("ALICE")
        bob = self.signup("BOB")
        self.request("POST", "/v1/block", {"username": "BOB"}, token=alice)

        self.request("DELETE", "/v1/account", token=bob)
        new_bob = self.signup_with_device("BOB")

        status, body = self.request(
            "POST", "/v1/send", {"recipient": "ALICE"}, token=new_bob
        )

        self.assertEqual(200, status)
        self.assertEqual({"delivered": True}, body)
        self.assertEqual([], self.fcm_client.calls)

    def test_a_new_holder_does_not_inherit_the_old_holders_own_blocks(self):
        """Inverse of the reclaim test above: the departed user's OWN blocks (rows where they were
        the owner) do not carry over to whoever signs up as the same name next."""
        old_bob = self.signup("BOB")
        charlie = self.signup_with_device("CHARLIE")
        self.request("POST", "/v1/block", {"username": "CHARLIE"}, token=old_bob)

        self.request("DELETE", "/v1/account", token=old_bob)
        new_bob = self.signup_with_device("BOB")

        status, body = self.request(
            "POST", "/v1/send", {"recipient": "BOB"}, token=charlie
        )

        self.assertEqual(200, status)
        self.assertEqual({"delivered": True}, body)
        self.assertEqual([("bob-token", "CHARLIE")], self.fcm_client.calls)

    def test_the_registered_device_is_removed(self):
        alice = self.signup_with_device("ALICE")
        bob = self.signup_with_device("BOB")
        self.request("DELETE", "/v1/account", token=bob)

        status, body = self.request("POST", "/v1/send", {"recipient": "BOB"}, token=alice)

        # Not "unregistered": there is no account behind the name any more.
        self.assertEqual(404, status)
        self.assertEqual("recipient_not_found", body["reason"])
        self.assertEqual([], self.fcm_client.calls)

    def test_a_deleted_google_account_is_unlinked_not_orphaned(self):
        """Otherwise the identity still points at a username that no longer exists, and the
        person can never sign in again with the same Google account."""
        self.server.google_verifier = StubGoogleVerifier()
        status, body = self.request(
            "POST",
            "/v1/google",
            {"id_token": "an.id.token", "username": "ALICE"},
            token=None,
        )
        self.assertEqual(201, status, body)

        self.request("DELETE", "/v1/account", token=body["token"])

        status, body = self.request(
            "POST",
            "/v1/google",
            {"id_token": "an.id.token"},
            token=None,
        )
        self.assertEqual(404, status)
        self.assertEqual("username_required", body["error"])


class LimiterKeyTest(unittest.TestCase):
    """Which bucket a caller lands in.

    Built by hand rather than through YoServerTestCase.request, because the socket peer has to
    vary and the harness hardcodes 127.0.0.1.
    """

    CF_RANGES = (
        ipaddress.ip_network("173.245.48.0/20"),
        ipaddress.ip_network("2400:cb00::/32"),
    )

    def key(self, socket_peer="127.0.0.1", forwarded=None, connecting=None, ranges=None):
        headers = Message()
        if forwarded is not None:
            headers["X-Forwarded-For"] = forwarded
        if connecting is not None:
            headers["CF-Connecting-IP"] = connecting
        handler = YoRequestHandler.__new__(YoRequestHandler)
        handler.headers = headers
        handler.client_address = (socket_peer, 0)
        with mock.patch.object(
            yo_server,
            "CLOUDFLARE_RANGES",
            self.CF_RANGES if ranges is None else ranges,
        ):
            return handler._limiter_key()

    def test_one_ipv6_customer_cannot_mint_buckets_out_of_their_own_prefix(self):
        """The smallest allocation a residential IPv6 customer gets is a /64 - eighteen
        quintillion addresses. Keying on the full address hands them an unlimited supply of
        fresh buckets against the limiter that guards a 600,000-iteration PBKDF2."""
        first = self.key(forwarded="2400:cb00::1", connecting="2001:db8:1:1::5")
        second = self.key(forwarded="2400:cb00::1", connecting="2001:db8:1:1::99ff")

        self.assertEqual(first, second)
        self.assertEqual("2001:db8:1:1::", first)

    def test_a_different_prefix_is_a_different_customer(self):
        """Bucketing to /64 must not collapse unrelated subscribers into one bucket, which
        would turn one abuser into a lockout for everybody else - the failure the whole
        CF-Connecting-IP arrangement exists to avoid."""
        first = self.key(forwarded="2400:cb00::1", connecting="2001:db8:1:1::5")
        second = self.key(forwarded="2400:cb00::1", connecting="2001:db8:2:2::5")

        self.assertNotEqual(first, second)

    def test_ipv4_keys_on_the_address_itself(self):
        key = self.key(forwarded="173.245.48.9", connecting="198.51.100.7")

        self.assertEqual("198.51.100.7", key)

    def test_a_direct_caller_cannot_author_its_own_peer(self):
        """Production was never spoofable here - Traefik strips a client-supplied
        X-Forwarded-For and appends its own peer, measured 2026-07-28 as twelve forged values
        landing in one bucket. But that safety lived in the proxy while the docstring claimed
        this function fell back to the peer by itself. A caller reaching us directly, from a
        public address, is not a proxy and its chain is ignored.

        The peer here is a genuinely routable address, and it has to be: Python's ipaddress
        reports is_private == True for the documentation ranges 192.0.2.0/24, 198.51.100.0/24
        and 203.0.113.0/24, because the predicate means "not globally routable" rather than
        "RFC1918". Reaching for a TEST-NET address - the obvious thing to write, and what every
        other address in this file is - makes this test assert the opposite of what it reads as.
        """
        key = self.key(socket_peer="8.8.8.8", forwarded="198.51.100.9")

        self.assertEqual("8.8.8.8", key)

    def test_a_non_ip_forwarded_entry_is_not_a_peer(self):
        """An arbitrary string is a perfectly good dict key, so honouring one would let a
        caller grow the limiter's table as fast as they can send requests - the leak the
        _evict_idle docstring names."""
        key = self.key(forwarded="not-an-ip")

        self.assertEqual("127.0.0.1", key)


class AccessLogRedactionTest(unittest.TestCase):
    """The access log, which the main harness stubs out at YoServerTestCase.request.

    Written against a hand-built handler for exactly that reason: with log_message replaced by
    a no-op everywhere else, nothing in the suite would notice this regressing.
    """

    def log_line(self, requestline, code=200):
        handler = YoRequestHandler.__new__(YoRequestHandler)
        handler.client_address = ("203.0.113.9", 51000)
        handler.requestline = requestline
        handler.request_version = "HTTP/1.1"
        stream = io.StringIO()
        with mock.patch("sys.stderr", stream):
            handler.log_request(code)
        return stream.getvalue()

    def test_a_block_does_not_record_who_was_blocked(self):
        """`DELETE /v1/block?username=BOB` beside the caller's IP is a record of who blocked
        whom, sitting next to a privacy policy that promises the address is kept briefly and
        only to rate-limit."""
        line = self.log_line("DELETE /v1/block?username=BOB HTTP/1.1")

        self.assertNotIn("BOB", line)
        self.assertIn("/v1/block?username=[redacted]", line)

    def test_removing_a_friend_does_not_record_the_friend(self):
        line = self.log_line("DELETE /v1/friends?username=CAROL HTTP/1.1")

        self.assertNotIn("CAROL", line)
        self.assertIn("/v1/friends?username=[redacted]", line)

    def test_the_line_is_still_worth_having(self):
        """Redaction that removed the route or the status would trade a privacy problem for a
        diagnostic one - this is the only request-level logging the backend has."""
        line = self.log_line("DELETE /v1/block?username=BOB HTTP/1.1", code=200)

        self.assertIn("203.0.113.9", line)
        self.assertIn("DELETE", line)
        self.assertIn("200", line)

    def test_a_request_without_a_query_string_is_untouched(self):
        line = self.log_line("POST /v1/send HTTP/1.1")

        self.assertIn("POST /v1/send HTTP/1.1", line)
        self.assertNotIn("redacted", line)


class ServedPageHeaderTest(StaticPageTestCase):
    """Headers on the three public HTML routes.

    Two of them are what Google Play re-checks after launch, and `/delete-account` is the only
    route to erasure for somebody who cannot open the app - so it is reached, by definition, by
    people with no other way through. Framing that is worth denying outright.
    """

    PAGES = ("/install", "/privacy", "/delete-account")

    def test_every_served_page_carries_the_headers(self):
        expected = (
            ("Content-Security-Policy", "frame-ancestors 'none'"),
            ("X-Frame-Options", "DENY"),
            ("X-Content-Type-Options", "nosniff"),
            ("Referrer-Policy", "no-referrer"),
            ("Strict-Transport-Security", "max-age="),
        )
        for path in self.PAGES:
            _, head, _ = self.raw_request("GET", path)
            for header, value in expected:
                with self.subTest(path=path, header=header):
                    self.assertIn(header, head)
                    self.assertIn(value, head)

    def test_hsts_is_not_preloaded(self):
        """`preload` is a browser-list submission that is slow and awkward to undo. Nothing here
        needs it, and adding it by reflex is how a domain acquires a commitment nobody chose."""
        for path in self.PAGES:
            _, head, _ = self.raw_request("GET", path)
            with self.subTest(path=path):
                self.assertNotIn("preload", head)

    def test_no_served_page_requests_anything_from_a_third_party(self):
        """The property, not the instance.

        `/install` pulled Montserrat from fonts.googleapis.com, so every invitee's browser
        announced its IP to Google before agreeing to anything - on the one page whose visitors
        by definition have not accepted a policy - while the privacy policy served beside it
        promises Google receives data in exactly two roles and that nothing is shared with anyone
        else. Asserting "no absolute URL to any host" rather than "not that one URL" is what makes
        this catch the next CDN, analytics snippet or webfont somebody reaches for.
        """
        for path in self.PAGES:
            _, _, body = self.raw_request("GET", path)
            text = body.decode("utf-8")
            for marker in ("http://", "https://", "//fonts.", "url("):
                with self.subTest(path=path, marker=marker):
                    self.assertNotIn(marker, text)

    def test_the_deletion_address_is_still_reachable_without_javascript(self):
        """It is the only deletion route for someone who cannot open the app, and Cloudflare's
        Scrape Shield rewrites a bare mailto into a JS-only link. The fences must stay."""
        _, _, body = self.raw_request("GET", "/delete-account")
        text = body.decode("utf-8")

        self.assertIn("mailto:", text)
        self.assertIn("<!--email_off-->", text)


class RegisterTokenBoundTest(YoServerTestCase):
    """An FCM token is ~150-200 characters and nothing bounded it."""

    def _token_for(self, username="ALICE"):
        status, body = self.request(
            "POST",
            "/v1/signup",
            {"username": username, "password": "correct-horse-1"},
        )
        self.assertEqual(201, status)
        return body["token"]

    def test_an_ordinary_token_is_accepted(self):
        session = self._token_for()
        status, _ = self.request(
            "POST", "/v1/register", {"fcm_token": "f" * 200}, token=session
        )

        self.assertEqual(200, status)

    def test_an_oversized_token_is_refused_and_nothing_is_stored(self):
        session = self._token_for()
        status, _ = self.request(
            "POST",
            "/v1/register",
            {"fcm_token": "f" * (yo_server.MAX_FCM_TOKEN_BYTES + 1)},
            token=session,
        )

        self.assertEqual(400, status)
        self.assertIsNone(self.server.database.get_fcm_token("ALICE"))

    def test_the_bound_is_bytes_not_characters(self):
        """Same reasoning as the link and hashtag caps: a multibyte string passes a len() check
        and is then several times larger on the wire and in the database."""
        session = self._token_for()
        multibyte = "é" * yo_server.MAX_FCM_TOKEN_BYTES  # 2 bytes each
        status, _ = self.request(
            "POST", "/v1/register", {"fcm_token": multibyte}, token=session
        )

        self.assertEqual(400, status)


class LookupRateLimitTest(YoServerTestCase):
    """Adding a friend and blocking answer `no_such_user`, which makes them existence probes.

    The disclosure is deliberate and stays (FR9). The unmetered RATE was not: one signup bought a
    sweep of the whole username space at line rate.
    """

    def _session(self, username):
        status, body = self.request(
            "POST",
            "/v1/signup",
            {"username": username, "password": "correct-horse-1"},
        )
        self.assertEqual(201, status)
        return body["token"]

    def test_probing_for_accounts_is_eventually_refused(self):
        session = self._session("ALICE")
        statuses = [
            self.request(
                "POST", "/v1/friends", {"username": f"GHOST{index:03d}"}, token=session
            )[0]
            for index in range(LOOKUP_ATTEMPTS + 5)
        ]

        self.assertEqual(404, statuses[0], "a probe must still work at all")
        self.assertIn(429, statuses)
        self.assertEqual(429, statuses[-1])

    def test_the_limit_is_per_account_not_shared(self):
        """Keyed on the authenticated caller on purpose. An IP bucket would let one abuser behind
        a carrier NAT lock out every other user sharing that address."""
        alice = self._session("ALICE")
        bob = self._session("BOB")
        for index in range(LOOKUP_ATTEMPTS + 2):
            self.request(
                "POST", "/v1/friends", {"username": f"GHOST{index:03d}"}, token=alice
            )

        status, _ = self.request(
            "POST", "/v1/friends", {"username": "GHOSTX"}, token=bob
        )

        self.assertEqual(404, status, "Bob must be unaffected by Alice's probing")

    def test_every_authenticated_write_without_its_own_budget_is_covered(self):
        """A path list is only as good as the thing that notices when a route is added to one
        list and not the other."""
        self.assertEqual(
            {"/v1/friends", "/v1/block", "/v1/register"},
            set(yo_server.LOOKUP_LIMITED_PATHS),
        )


class TokenOutlivingItsAccountTest(YoServerTestCase):
    """A token must never name an account that is not there.

    Sign-in reads the account, spends ~300 ms on PBKDF2, and only then writes the token. A
    DELETE /v1/account inside that window completed first and reaped the tokens that existed at
    that moment; the new one landed afterwards. Measured before the fix: with the delete fired 0,
    5, 15 or 30 ms after the login, a live token survived 40 times out of 40.

    The consequence is not a stray row. Nothing prunes tokens, and a deleted username is free to
    claim again, so the stale session silently becomes a session on whoever registers that name
    next.
    """

    def _account(self, username="ALICE"):
        status, body = self.request(
            "POST", "/v1/signup", {"username": username, "password": "correct-horse-1"}
        )
        self.assertEqual(201, status)
        return body["token"]

    def test_a_token_cannot_be_issued_for_an_account_that_is_gone(self):
        """The race, without the race: the state the loser of it would have written."""
        self.assertFalse(
            self.server.database.store_token("some-token-hash", "NOBODY"),
            "storing a token for an account that does not exist must not succeed",
        )
        self.assertIsNone(self.server.database.username_for_token("some-token-hash"))

    def test_a_token_written_before_a_delete_stops_authenticating_after_it(self):
        """Belt to the other's braces: the JOIN makes this true for rows already in the table,
        however they got there, not only for rows written from now on."""
        token = self._account()
        self.assertEqual(200, self.request("GET", "/v1/friends", token=token)[0])

        self.server.database.delete_account("ALICE")

        self.assertEqual(401, self.request("GET", "/v1/friends", token=token)[0])

    def test_a_stale_token_does_not_transfer_to_whoever_claims_the_name_next(self):
        """The whole reason this is not merely an orphan row."""
        attacker = self._account("GHOST")
        # Simulate the losing login: a token row for an account that is about to vanish.
        self.server.database.delete_account("GHOST")
        self.server.database.store_token(yo_auth.hash_token("stale-token"), "GHOST")

        status, body = self.request(
            "POST", "/v1/signup", {"username": "GHOST", "password": "different-pass-9"}
        )
        self.assertEqual(201, status, "the freed username is claimable, by design")

        self.assertEqual(
            401,
            self.request("GET", "/v1/friends", token="stale-token")[0],
            "the old token must not authenticate as the new owner",
        )
        self.assertEqual(401, self.request("GET", "/v1/friends", token=attacker)[0])

    def test_an_ordinary_sign_in_is_unaffected(self):
        token = self._account()
        status, body = self.request("GET", "/v1/friends", token=token)

        self.assertEqual(200, status)
        self.assertEqual([], body["friends"])


class LogoutForgetsTheDeviceTest(YoServerTestCase):
    """Signing out has to mean the handset stops receiving the account's Yos."""

    def _account(self, username):
        _, body = self.request(
            "POST", "/v1/signup", {"username": username, "password": "correct-horse-1"}
        )
        return body["token"]

    def test_logging_out_removes_the_registration(self):
        token = self._account("CAROL")
        self.request("POST", "/v1/register", {"fcm_token": "CAROL-PHONE"}, token=token)
        self.assertEqual("CAROL-PHONE", self.server.database.get_fcm_token("CAROL"))

        self.assertEqual(200, self.request("DELETE", "/v1/session", token=token)[0])

        self.assertIsNone(
            self.server.database.get_fcm_token("CAROL"),
            "a signed-out phone must stop receiving the account's Yos",
        )

    def test_a_yo_to_a_signed_out_account_is_not_delivered_to_the_old_handset(self):
        """The observable consequence, which is the part that matters: the sender's name, and any
        location, link or hashtag they attached, were arriving on a phone its owner had signed
        out of - and on a phone that was sold or handed on, that is one person's messages being
        delivered to another."""
        carol = self._account("CAROL")
        self.request("POST", "/v1/register", {"fcm_token": "CAROL-PHONE"}, token=carol)
        self.request("DELETE", "/v1/session", token=carol)
        bob = self._account("BOB")

        status, body = self.request(
            "POST", "/v1/send", {"recipient": "CAROL"}, token=bob
        )

        self.assertEqual(404, status)
        self.assertEqual("recipient_unregistered", body["reason"])
        self.assertEqual([], self.fcm_client.calls)


class BlockedSendCostTest(YoServerTestCase):
    """A block answers 200 delivered:true and sends nothing. That has to include the clock.

    Measured before the fix: blocked p50 0.79 ms, delivered p50 116.69 ms, ranges disjoint by
    110 ms. The one control whose threat model explicitly names the sender as the adversary
    announced itself to that adversary in the timing, while the body was byte-identical.

    Padding only the blocked branch to a mean was still two leaks of its own: a delivered send
    was never padded at all, so a min-of-N classifier still separated a point mass (blocked) from
    the distribution it was drawn from (delivered) even with matching means; and the mean
    cold-starts at a laptop number every restart, so blocked was briefly SLOWER than a real
    delivery until it decayed. The fix gives both branches one shared deadline, so they are
    asserted as "both branches sleep to the identical instant" via the injected clock and
    sleeper, rather than by measuring wall-clock time (flaky on a loaded machine) or by asserting
    only that a sleep function was called (which passes at a zero deadline with both leaks
    still present).
    """

    def _account(self, username):
        _, body = self.request(
            "POST", "/v1/signup", {"username": username, "password": "correct-horse-1"}
        )
        return body["token"]

    def test_a_blocked_send_and_a_delivered_send_target_the_same_deadline(self):
        alice = self._account("ALICE")
        bob = self._account("BOB")
        self.request("POST", "/v1/register", {"fcm_token": "BOB-PHONE"}, token=bob)
        carol = self._account("CAROL")
        self.request("POST", "/v1/register", {"fcm_token": "CAROL-PHONE"}, token=carol)
        self.request("POST", "/v1/block", {"username": "ALICE"}, token=carol)

        sleeps = []
        # `started` inside _handle_send is pinned so both requests measure elapsed time from the
        # identical instant; the estimator's own clock is faked separately so the padding
        # arithmetic is asserted exactly, with no wall-clock measurement anywhere in this test.
        self.server.delivery_cost = yo_server.DeliveryCostEstimator(
            clock=lambda: 1_000.05,
            sleeper=lambda seconds: sleeps.append(seconds),
        )

        with mock.patch("yo_server.time.monotonic", return_value=1_000.0):
            status_delivered, body_delivered = self.request(
                "POST", "/v1/send", {"recipient": "BOB"}, token=alice
            )
            status_blocked, body_blocked = self.request(
                "POST", "/v1/send", {"recipient": "CAROL"}, token=alice
            )

        self.assertEqual(200, status_delivered)
        self.assertEqual({"delivered": True}, body_delivered)
        self.assertEqual(200, status_blocked)
        self.assertEqual({"delivered": True}, body_blocked)
        self.assertEqual(
            2, len(sleeps), "both the delivered and the blocked send must pad to the deadline"
        )
        self.assertEqual(
            sleeps[0],
            sleeps[1],
            "a delivered and a blocked send must sleep to the exact same absolute instant",
        )
        self.assertEqual(
            [("BOB-PHONE", "ALICE")],
            self.fcm_client.calls,
            "nothing may actually be sent to the blocked recipient",
        )

    def test_a_real_delivery_teaches_the_estimator_what_one_costs(self):
        alice = self._account("ALICE")
        bob = self._account("BOB")
        self.request("POST", "/v1/register", {"fcm_token": "BOB-PHONE"}, token=bob)

        with mock.patch.object(self.server.delivery_cost, "observe") as observed:
            self.request("POST", "/v1/send", {"recipient": "BOB"}, token=alice)

        observed.assert_called_once()
        self.assertGreaterEqual(observed.call_args[0][0], 0.0)

    def test_the_observed_duration_excludes_the_pad_before_it_can_ratchet(self):
        """CRITICAL FOOTGUN: if observe() ever saw the padded (post-sleep) duration, the deadline
        would feed on its own output and ratchet upward without bound - every Yo slower forever.
        Order must be: measure elapsed, observe(elapsed), sleep to deadline, write the response.
        Asserted as call ORDER through a fake standing in for the whole estimator, not by
        inspecting timings."""
        alice = self._account("ALICE")
        bob = self._account("BOB")
        self.request("POST", "/v1/register", {"fcm_token": "BOB-PHONE"}, token=bob)

        order = []

        class _OrderRecordingCost:
            def observe(self, seconds):
                order.append(("observe", seconds))

            def sleep_until_deadline(self, started):
                order.append(("sleep", started))

        self.server.delivery_cost = _OrderRecordingCost()

        status, body = self.request("POST", "/v1/send", {"recipient": "BOB"}, token=alice)

        self.assertEqual(200, status)
        self.assertEqual({"delivered": True}, body)
        self.assertEqual(2, len(order))
        self.assertEqual("observe", order[0][0])
        self.assertEqual("sleep", order[1][0])

    def test_the_estimator_pads_both_paths_to_the_same_deadline(self):
        """Self-calibrating rather than a constant, because the right delay depends on how far
        the server is from Google - a laptop pays ~117 ms, the production host perhaps 60-75.

        Constructed with NO argument, exactly as production constructs it - not with
        initial_seconds=0.0, which steps around the only value production ever uses and would
        pass even with a zero deadline. RED-FIRST: fails against unmodified source, because
        DeliveryCostEstimator takes no injectable clock/sleeper and the delivered branch never
        sleeps at all.
        """
        sleeps = []
        estimator = yo_server.DeliveryCostEstimator(
            clock=lambda: 42.05,
            sleeper=lambda seconds: sleeps.append(seconds),
        )
        started = 42.0

        # Delivered path: observe the real cost first, then pad to the shared deadline.
        estimator.observe(0.03)
        estimator.sleep_until_deadline(started)

        # Blocked path: no observation, same deadline computation, same `started`.
        estimator.sleep_until_deadline(started)

        self.assertEqual(2, len(sleeps))
        self.assertEqual(
            sleeps[0], sleeps[1], "blocked and delivered must target the identical instant"
        )

    def test_an_empty_window_falls_back_to_the_floor_not_to_zero(self):
        sleeps = []
        estimator = yo_server.DeliveryCostEstimator(
            clock=lambda: 100.0,
            sleeper=lambda seconds: sleeps.append(seconds),
        )

        estimator.sleep_until_deadline(started=100.0)

        self.assertEqual(1, len(sleeps))
        self.assertAlmostEqual(
            yo_server.DeliveryCostEstimator._INITIAL_SECONDS, sleeps[0], places=9
        )

    def test_one_enormous_sample_is_capped_by_the_ceiling(self):
        sleeps = []
        estimator = yo_server.DeliveryCostEstimator(
            clock=lambda: 100.0,
            sleeper=lambda seconds: sleeps.append(seconds),
        )
        estimator.observe(45.0)

        estimator.sleep_until_deadline(started=100.0)

        self.assertEqual([yo_server.DeliveryCostEstimator._CEILING_SECONDS], sleeps)

    def test_the_deadline_sits_above_a_high_percentile_and_strictly_above_the_mean(self):
        """A mean-padded implementation fails this: 90 fast deliveries and 10 slow ones give a
        mean of 0.075s but a 95th percentile of 0.3s, and the deadline must track the tail, not
        the average, or the tail below it leaks exactly as before."""
        sleeps = []
        estimator = yo_server.DeliveryCostEstimator(
            clock=lambda: 100.0,
            sleeper=lambda seconds: sleeps.append(seconds),
        )
        durations = [0.05] * 90 + [0.3] * 10
        for seconds in durations:
            estimator.observe(seconds)
        mean = sum(durations) / len(durations)

        estimator.sleep_until_deadline(started=100.0)

        self.assertEqual(1, len(sleeps))
        pad = sleeps[0]
        self.assertGreaterEqual(
            round(pad, 9), 0.3, "the deadline must sit at or above the 95th percentile"
        )
        self.assertGreater(pad, mean, "a mean-padded implementation would fail here")


class TokenExpiryTest(YoServerTestCase):
    """A stolen session has to have a deadline (G10, issue #37).

    A token was valid until that exact session called DELETE /v1/session - which a thief has no
    reason to do - so an exfiltrated one was good forever. The `created_at` column has been
    written since the table existed and nothing ever read it.
    """

    def _session(self, username="ALICE"):
        status, body = self.request(
            "POST", "/v1/signup", {"username": username, "password": "correct-horse-1"}
        )
        self.assertEqual(201, status)
        return body["token"]

    def _age_all_tokens(self, seconds):
        with sqlite3.connect(self.database_path) as connection:
            connection.execute(
                "UPDATE tokens SET created_at = ?", (int(time.time()) - seconds,)
            )

    def test_a_fresh_token_works(self):
        token = self._session()
        self.assertEqual(200, self.request("GET", "/v1/friends", token=token)[0])

    def test_a_token_past_its_ttl_is_refused(self):
        token = self._session()
        self._age_all_tokens(yo_server.TOKEN_TTL_SECONDS + 60)

        self.assertEqual(401, self.request("GET", "/v1/friends", token=token)[0])

    def test_a_token_just_inside_the_ttl_still_works(self):
        """The boundary in the direction that matters: expiry must not log people out early."""
        token = self._session()
        self._age_all_tokens(yo_server.TOKEN_TTL_SECONDS - 3600)

        self.assertEqual(200, self.request("GET", "/v1/friends", token=token)[0])

    def test_a_token_of_unknown_age_is_treated_as_expired(self):
        """NULL created_at means "arrived some other way". Unknown age is not a reason to trust
        something indefinitely - and the column has always been written, so this cannot fire on a
        row the application itself produced."""
        token = self._session()
        with sqlite3.connect(self.database_path) as connection:
            connection.execute("UPDATE tokens SET created_at = NULL")

        self.assertEqual(401, self.request("GET", "/v1/friends", token=token)[0])

    def test_expired_tokens_are_swept_rather_than_only_filtered(self):
        """Otherwise the table grows forever: nothing else deletes a token except an explicit
        logout or account deletion, and an expired row is dead weight either way."""
        self._session()
        self._age_all_tokens(yo_server.TOKEN_TTL_SECONDS + 60)
        with sqlite3.connect(self.database_path) as connection:
            before = connection.execute("SELECT COUNT(*) FROM tokens").fetchone()[0]
        self.assertEqual(1, before)

        # Signing in is the moment a new row appears, so it is the moment to sweep.
        self.request("POST", "/v1/login", {"username": "ALICE", "password": "correct-horse-1"})

        with sqlite3.connect(self.database_path) as connection:
            rows = connection.execute("SELECT COUNT(*) FROM tokens").fetchone()[0]
        self.assertEqual(1, rows, "the expired row went, the fresh one stayed")

    def test_the_ttl_is_long_enough_to_not_be_a_login_prompt(self):
        """A guard on the constant, not on the mechanism. There is no refresh flow, so the whole
        cost of expiry is a re-login; setting this to hours would turn a one-tap app into a
        password screen, and the value should not drift there without somebody noticing."""
        self.assertGreaterEqual(yo_server.TOKEN_TTL_SECONDS, 30 * 24 * 60 * 60)


class HeadRequestTest(StaticPageTestCase):
    """HEAD answered 501, which made a healthy service look down.

    `curl -I` is the reflex reach for "is it up", and uptime monitors default to HEAD precisely
    because it is cheap - so the service that most needs watching would have reported itself
    broken from its first check.
    """

    PAGES = ("/healthz", "/install", "/privacy", "/delete-account")

    def raw_head(self, path):
        handler = YoRequestHandler.__new__(YoRequestHandler)
        handler.command = "HEAD"
        handler.path = path
        handler.request_version = "HTTP/1.1"
        handler.requestline = f"HEAD {path} HTTP/1.1"
        handler.headers = Message()
        handler.rfile = io.BytesIO(b"")
        handler.wfile = io.BytesIO()
        handler.server = self.server
        handler.client_address = ("127.0.0.1", 0)
        handler.log_message = lambda *_: None
        handler.do_HEAD()
        head, body = handler.wfile.getvalue().split(b"\r\n\r\n", 1)
        status = int(head.splitlines()[0].decode("ascii").split(" ", 2)[1])
        return status, head.decode("latin-1"), body

    def test_head_matches_get_and_sends_no_body(self):
        for path in self.PAGES:
            with self.subTest(path=path):
                head_status, head_headers, head_body = self.raw_head(path)
                get_status, _, get_body = self.raw_request("GET", path)

                self.assertEqual(get_status, head_status)
                self.assertEqual(b"", head_body, "HEAD must not send a body")
                self.assertIn(
                    f"Content-Length: {len(get_body)}",
                    head_headers,
                    "Content-Length must still describe the body a GET would return",
                )

    def test_head_carries_the_same_security_headers(self):
        """A monitor that follows headers must see the same posture as a browser."""
        _, headers, _ = self.raw_head("/privacy")
        for header in (
            "Content-Security-Policy",
            "X-Frame-Options",
            "X-Content-Type-Options",
            "Referrer-Policy",
            "Strict-Transport-Security",
        ):
            with self.subTest(header=header):
                self.assertIn(header, headers)

    def test_head_does_not_read_the_apk_off_disk(self):
        """/install/yo.apk reads the whole file into memory. Doing that for a body that is then
        discarded turns the cheapest possible probe into the most expensive request on the
        server, on a route that is public and unauthenticated."""
        self.assertIn("_suppress_body", inspect.getsource(YoRequestHandler._handle_install_apk))


class MalformedNumberTest(YoServerTestCase):
    """Numbers a caller can send that Python does not treat like other languages do.

    Both cases below escaped every handler and dropped the connection with no response at all -
    the caller saw a reset and could not tell a server fault from a network one. The float forms
    of the same inputs were always handled correctly, which is the tell: whoever wrote this
    thought about infinity and NaN, and did not think about Python integers being unbounded.
    """

    def _session(self):
        status, body = self.request(
            "POST", "/v1/signup", {"username": "ALICE", "password": "correct-horse-1"}
        )
        self.assertEqual(201, status)
        return body["token"]

    def test_an_integer_too_large_for_a_float_is_refused_not_crashed(self):
        """math.isfinite raises OverflowError, not False, for an int that will not fit a float."""
        session = self._session()
        status, body = self.request(
            "POST",
            "/v1/send",
            {"recipient": "BOB", "latitude": int("9" * 400), "longitude": 1},
            token=session,
        )

        self.assertEqual(400, status)
        self.assertEqual("bad_request", body["error"])

    def test_the_float_forms_still_behave(self):
        session = self._session()
        for value in (float("inf"), float("-inf"), float("nan")):
            with self.subTest(value=value):
                status, _ = self.request(
                    "POST",
                    "/v1/send",
                    {"recipient": "BOB", "latitude": value, "longitude": 1},
                    token=session,
                )
                self.assertEqual(400, status)

    def test_a_valid_coordinate_is_unaffected(self):
        session = self._session()
        status, body = self.request(
            "POST",
            "/v1/send",
            {"recipient": "BOB", "latitude": 45.815, "longitude": 15.982},
            token=session,
        )

        # BOB does not exist; the point is that the coordinates were accepted and we got that far.
        self.assertEqual(404, status)
        self.assertEqual("recipient_not_found", body["reason"])


class RawBodyTest(YoServerTestCase):
    """_read_json_body's contract is that a bad body is a 400. It had a hole."""

    def test_a_json_integer_over_the_digit_limit_is_a_bad_request(self):
        """CPython refuses to convert an integer literal of more than 4300 digits and raises a
        plain ValueError - not a JSONDecodeError - so naming the subclasses individually looked
        exhaustive and missed it. Catching the base class is what makes the contract true."""
        status, body = self.request(
            "POST", "/v1/signup", raw_body=b'{"username": ' + b"9" * 5000 + b"}"
        )

        self.assertEqual(400, status)
        self.assertEqual("bad_request", body["error"])

    def test_ordinary_malformed_json_is_still_a_bad_request(self):
        status, body = self.request("POST", "/v1/signup", raw_body=b"{{{")

        self.assertEqual(400, status)
        self.assertEqual("bad_request", body["error"])


class UnhandledErrorTest(YoServerTestCase):
    """Anything a route did not anticipate must be a 500, not a dropped connection.

    The specific escapes found on 2026-08-01 are fixed above. This asserts the backstop, because
    the ones worth guarding against are the ones nobody has enumerated - the store is a non-WAL
    SQLite file behind a threaded server, so `database is locked` is reachable under ordinary
    production concurrency and is not a bug in any route.
    """

    def test_an_unexpected_failure_answers_500_rather_than_dropping_the_connection(self):
        status, body = self.request(
            "POST", "/v1/signup", {"username": "ALICE", "password": "correct-horse-1"}
        )
        self.assertEqual(201, status)
        token = body["token"]

        def exploding(*_args, **_kwargs):
            raise sqlite3.OperationalError("database is locked")

        with mock.patch.object(self.server.database, "list_friends", exploding):
            status, body = self.request("GET", "/v1/friends", token=token)

        self.assertEqual(500, status)
        self.assertEqual("internal_error", body["error"])

    def test_the_reason_is_never_disclosed_to_the_caller(self):
        """Same rule /v1/google already applies to Google's rejection messages: a message derived
        from a caller-controlled request tells an honest client nothing they can act on."""
        status, body = self.request(
            "POST", "/v1/signup", {"username": "ALICE", "password": "correct-horse-1"}
        )
        token = body["token"]

        def exploding(*_args, **_kwargs):
            raise RuntimeError("SECRET-INTERNAL-DETAIL /root/claude/modules/yo/data/yo.db")

        with mock.patch.object(self.server.database, "list_friends", exploding):
            _, body = self.request("GET", "/v1/friends", token=token)

        self.assertNotIn("SECRET-INTERNAL-DETAIL", json.dumps(body))
        self.assertNotIn("yo.db", json.dumps(body))


class BroadcastClientIdTest(YoServerTestCase):
    """A client id is delivered to every subscriber AS THE SENDER of the Yo."""

    def _register(self, client_id, key="secret-key"):
        self.server.database.upsert_api_client(client_id, _hash_client_key(key))
        return {"X-Yo-Client-Id": client_id, "X-Yo-Client-Key": key}

    def test_an_ordinary_client_id_still_works(self):
        status, _ = self.request(
            "POST", "/v1/broadcast", {}, extra_headers=self._register("fedex")
        )

        self.assertEqual(200, status)

    def test_a_client_id_that_could_forge_a_tap_promise_is_refused_at_use(self):
        """Not only at registration. A row written by hand, restored from a backup, or created by
        an older build of register_client.py would otherwise still reach every subscriber's shade.
        The app renders `From <SENDER>` with no charset filter of its own, because until now that
        field could only ever have come from validate_username.
        """
        forging = "WORLDCUP  ·  TAP TO OPEN evil.com"
        status, body = self.request(
            "POST", "/v1/broadcast", {}, extra_headers=self._register(forging)
        )

        self.assertEqual(401, status)
        self.assertEqual("unauthorized", body["error"])

    def test_the_charset_rule_rejects_everything_that_can_imitate_the_app(self):
        for hostile in (
            "a b",  # a space is all it takes to start a second clause
            "yo·evil",  # the app's own separator
            "yo.evil.com",  # reads as a host
            "yo\nevil",  # a newline
            "‮yo",  # an RTL override
            "y",  # too short to be meaningful
            "y" * 33,  # longer than a username may be
        ):
            with self.subTest(hostile):
                self.assertIsNone(yo_server.CLIENT_ID_PATTERN.fullmatch(hostile))

    def test_the_historical_client_names_this_route_exists_for_are_allowed(self):
        for legitimate in ("fedex", "worldcup", "WorldCup", "yo-bot", "yo_bot", "ifttt2"):
            with self.subTest(legitimate):
                self.assertIsNotNone(yo_server.CLIENT_ID_PATTERN.fullmatch(legitimate))


if __name__ == "__main__":
    unittest.main()
