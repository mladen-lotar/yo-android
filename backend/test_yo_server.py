import io
import ipaddress
import json
import os
import sqlite3
import tempfile
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
    ):
        data = b""
        headers = Message()
        if body is not None:
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
        hashtag = "#" * MAX_HASHTAG_BYTES

        status, _ = self.send(link=link, hashtag=hashtag)

        self.assertEqual(200, status)
        self.assertEqual(
            [("bob-token", "ALICE", link, hashtag)],
            self.fcm_client.attachment_calls,
        )

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
            {"message": "Package update"},
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

    def test_blocks_are_cleared_in_both_directions(self):
        alice = self.signup("ALICE")
        bob = self.signup("BOB")
        self.request("POST", "/v1/block", {"username": "BOB"}, token=alice)

        self.request("DELETE", "/v1/account", token=bob)

        self.assertFalse(self.server.database.is_blocked("ALICE", "BOB"))
        self.assertEqual([], self.server.database.list_blocked("ALICE"))

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


if __name__ == "__main__":
    unittest.main()
