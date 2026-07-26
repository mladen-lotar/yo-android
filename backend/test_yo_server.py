import io
import json
import os
import tempfile
import unittest
from email.message import Message
from types import SimpleNamespace

from fcm_client import FCMDeliveryError, FCMNotConfiguredError
from yo_db import YoDatabase
from yo_server import (
    CREDENTIAL_ATTEMPTS,
    CREDENTIAL_WINDOW_SECONDS,
    MAX_PHOTO_BYTES,
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
        self.result = True
        self.error = None
        self.fail_tokens = set()

    def send_yo(self, fcm_token, sender):
        self.calls.append((fcm_token, sender))
        if self.error is not None:
            raise self.error
        if fcm_token in self.fail_tokens:
            raise FCMDeliveryError("delivery failed")
        return self.result


class YoServerTestCase(unittest.TestCase):
    """Shared harness: a real sqlite database plus a SimpleNamespace standing in for the server.

    The handler only ever touches server.database / fcm_client / password_iterations /
    credential_limiter / send_limiter, so a namespace is enough and avoids binding a socket.
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

    def test_the_limiter_keys_on_the_forwarded_client_ip(self):
        """Every socket is 127.0.0.1 behind the tunnel, so one abuser must not lock out all."""
        for _ in range(CREDENTIAL_ATTEMPTS):
            self.request(
                "POST",
                "/v1/login",
                {"username": "NOBODY", "password": "nope-nope-nope"},
                token=None,
                extra_headers={"CF-Connecting-IP": "198.51.100.7"},
            )

        blocked, _ = self.request(
            "POST",
            "/v1/login",
            {"username": "NOBODY", "password": "nope-nope-nope"},
            token=None,
            extra_headers={"CF-Connecting-IP": "198.51.100.7"},
        )
        other_caller, _ = self.request(
            "POST",
            "/v1/signup",
            {"username": "ALICE", "password": TEST_PASSWORD},
            token=None,
            extra_headers={"CF-Connecting-IP": "203.0.113.9"},
        )

        self.assertEqual(429, blocked)
        self.assertEqual(201, other_caller)


class TokenAuthTest(YoServerTestCase):
    def test_every_v1_route_rejects_a_missing_or_bad_token(self):
        routes = (
            ("GET", "/v1/friends", None),
            ("GET", "/v1/blocked", None),
            ("GET", "/v1/photo?message_id=message-1", None),
            ("POST", "/v1/register", {"fcm_token": "token"}),
            ("POST", "/v1/send", {"recipient": "BOB"}),
            ("POST", "/v1/friends", {"username": "BOB"}),
            ("POST", "/v1/block", {"username": "BOB"}),
            (
                "POST",
                "/v1/photo",
                {
                    "message_id": "message-1",
                    "mime_type": "image/jpeg",
                    "data": "base64-data",
                },
            ),
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


class PhotoTest(YoServerTestCase):
    def test_photo_upload_then_fetch_round_trips(self):
        alice_token = self.signup("ALICE")

        status, body = self.request(
            "POST",
            "/v1/photo",
            {
                "message_id": "message-1",
                "mime_type": "image/jpeg",
                "data": "base64-data",
            },
            token=alice_token,
        )

        self.assertEqual(200, status)
        self.assertEqual({"stored": True}, body)

        status, body = self.request(
            "GET",
            "/v1/photo?message_id=message-1",
            token=alice_token,
        )

        self.assertEqual(200, status)
        self.assertEqual(
            {"mime_type": "image/jpeg", "data": "base64-data"},
            body,
        )

    def test_only_the_owner_and_the_recipient_can_fetch_a_photo(self):
        alice_token = self.signup("ALICE")
        bob_token = self.signup("BOB")
        mallory_token = self.signup("MALLORY")
        self.request(
            "POST",
            "/v1/photo",
            {
                "message_id": "message-1",
                "mime_type": "image/jpeg",
                "data": "base64-data",
                "recipient": "BOB",
            },
            token=alice_token,
        )

        for name, token in (("owner", alice_token), ("recipient", bob_token)):
            with self.subTest(role=name):
                status, body = self.request(
                    "GET",
                    "/v1/photo?message_id=message-1",
                    token=token,
                )
                self.assertEqual(200, status)
                self.assertEqual("base64-data", body["data"])

        status, body = self.request(
            "GET",
            "/v1/photo?message_id=message-1",
            token=mallory_token,
        )
        # 404 rather than 403: a third party must not learn that the id exists at all.
        self.assertEqual(404, status)
        self.assertEqual({"error": "not_found"}, body)

    def test_a_different_owner_cannot_overwrite_an_existing_message_id(self):
        alice_token = self.signup("ALICE")
        mallory_token = self.signup("MALLORY")
        self.request(
            "POST",
            "/v1/photo",
            {
                "message_id": "message-1",
                "mime_type": "image/jpeg",
                "data": "alice-data",
            },
            token=alice_token,
        )

        status, body = self.request(
            "POST",
            "/v1/photo",
            {
                "message_id": "message-1",
                "mime_type": "image/jpeg",
                "data": "mallory-data",
            },
            token=mallory_token,
        )

        self.assertEqual(403, status)
        self.assertEqual({"error": "forbidden"}, body)
        self.assertEqual(
            "alice-data",
            self.server.database.get_photo("message-1")[1],
        )

    def test_the_owner_may_overwrite_their_own_message_id(self):
        alice_token = self.signup("ALICE")
        for data in ("first-data", "second-data"):
            status, _ = self.request(
                "POST",
                "/v1/photo",
                {
                    "message_id": "message-1",
                    "mime_type": "image/jpeg",
                    "data": data,
                },
                token=alice_token,
            )
            self.assertEqual(200, status)

        self.assertEqual(
            "second-data",
            self.server.database.get_photo("message-1")[1],
        )

    def test_photo_upload_rejects_oversized_payload(self):
        alice_token = self.signup("ALICE")

        status, body = self.request(
            "POST",
            "/v1/photo",
            {
                "message_id": "message-1",
                "mime_type": "image/jpeg",
                "data": "A" * (MAX_PHOTO_BYTES + 1),
            },
            token=alice_token,
        )

        self.assertEqual(400, status)
        self.assertEqual({"error": "photo_too_large"}, body)
        self.assertIsNone(self.server.database.get_photo("message-1"))

    def test_photo_upload_rejects_oversized_utf8_payload(self):
        # Mostly-ASCII data with a small multi-byte-UTF-8 suffix: the Python str
        # length (code-point count) stays just under MAX_PHOTO_BYTES -- so the old
        # len(data) check would have incorrectly accepted this -- while the actual
        # UTF-8-encoded byte length (each "é" costs 2 bytes) crosses over
        # MAX_PHOTO_BYTES, which the fixed len(data.encode("utf-8")) check must
        # reject. The JSON-escaped wire size for this payload stays comfortably
        # under MAX_BODY_BYTES so the request reaches _handle_photo_upload at all.
        alice_token = self.signup("ALICE")
        multi_byte_chars = 2_000
        ascii_chars = (MAX_PHOTO_BYTES - 1_000) - multi_byte_chars
        data = ("a" * ascii_chars) + ("é" * multi_byte_chars)

        status, body = self.request(
            "POST",
            "/v1/photo",
            {
                "message_id": "message-1",
                "mime_type": "image/jpeg",
                "data": data,
            },
            token=alice_token,
        )

        self.assertEqual(400, status)
        self.assertEqual({"error": "photo_too_large"}, body)
        self.assertIsNone(self.server.database.get_photo("message-1"))

    def test_photo_fetch_missing_message_id_returns_404(self):
        alice_token = self.signup("ALICE")

        for path in ("/v1/photo", "/v1/photo?message_id=missing"):
            with self.subTest(path=path):
                status, body = self.request("GET", path, token=alice_token)

                self.assertEqual(404, status)
                self.assertEqual({"error": "not_found"}, body)


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


class InstallPageTest(unittest.TestCase):
    """
    The install page is the landing target of shared invite links.

    Deliberately NOT a subclass of YoServerTestCase: inheriting would re-run every API test a
    second time under a new name. These routes never touch the database, so the harness here is
    a stub.
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


if __name__ == "__main__":
    unittest.main()
