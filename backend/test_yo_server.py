import io
import json
import os
import tempfile
import unittest
from email.message import Message
from types import SimpleNamespace

from fcm_client import FCMDeliveryError, FCMNotConfiguredError
from yo_db import YoDatabase
from yo_server import MAX_PHOTO_BYTES, YoRequestHandler, _hash_client_key


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


class YoServerTest(unittest.TestCase):
    server_key = "test-server-key"

    def setUp(self):
        database_file = tempfile.NamedTemporaryFile(delete=False)
        database_file.close()
        self.database_path = database_file.name
        self.fcm_client = RecordingFCMClient()
        database = YoDatabase(self.database_path)
        database.initialize()
        self.server = SimpleNamespace(
            database=database,
            shared_key=self.server_key,
            fcm_client=self.fcm_client,
        )

    def tearDown(self):
        os.unlink(self.database_path)

    def test_register_upserts_token(self):
        first_status, first_body = self.request(
            "POST",
            "/v1/register",
            {"username": "alice", "fcm_token": "first-token"},
        )
        second_status, second_body = self.request(
            "POST",
            "/v1/register",
            {"username": "alice", "fcm_token": "second-token"},
        )

        self.assertEqual(200, first_status)
        self.assertEqual(200, second_status)
        self.assertTrue(first_body["registered"])
        self.assertTrue(second_body["registered"])
        database = YoDatabase(self.database_path)
        self.assertEqual("second-token", database.get_fcm_token("alice"))

    def test_friends_excludes_requester(self):
        for username in ("alice", "bob", "charlie"):
            self.request(
                "POST",
                "/v1/register",
                {"username": username, "fcm_token": f"{username}-token"},
            )

        status, body = self.request(
            "GET",
            "/v1/friends?username=bob",
        )

        self.assertEqual(200, status)
        self.assertEqual(["alice", "charlie"], body["friends"])

    def test_friends_accepts_requester_alias(self):
        for username in ("alice", "bob"):
            self.request(
                "POST",
                "/v1/register",
                {"username": username, "fcm_token": f"{username}-token"},
            )

        status, body = self.request(
            "GET",
            "/v1/friends?requester=alice",
        )

        self.assertEqual(200, status)
        self.assertEqual(["bob"], body["friends"])

    def test_send_calls_fcm_client_for_registered_recipient(self):
        self.request(
            "POST",
            "/v1/register",
            {"username": "bob", "fcm_token": "bob-token"},
        )

        status, body = self.request(
            "POST",
            "/v1/send",
            {"sender": "alice", "recipient": "bob"},
        )

        self.assertEqual(200, status)
        self.assertEqual({"delivered": True}, body)
        self.assertEqual([("bob-token", "alice")], self.fcm_client.calls)

    def test_send_with_unconfigured_fcm_returns_false(self):
        self.request(
            "POST",
            "/v1/register",
            {"username": "bob", "fcm_token": "bob-token"},
        )
        self.fcm_client.error = FCMNotConfiguredError("not configured")

        status, body = self.request(
            "POST",
            "/v1/send",
            {"sender": "alice", "recipient": "bob"},
        )

        self.assertEqual(200, status)
        self.assertEqual(
            {"delivered": False, "reason": "fcm_not_configured"},
            body,
        )

    def test_send_with_missing_recipient_returns_404(self):
        status, body = self.request(
            "POST",
            "/v1/send",
            {"sender": "alice", "recipient": "missing"},
        )

        self.assertEqual(404, status)
        self.assertEqual(
            {"delivered": False, "reason": "recipient_not_found"},
            body,
        )
        self.assertEqual([], self.fcm_client.calls)

    def test_photo_upload_then_fetch_round_trips(self):
        status, body = self.request(
            "POST",
            "/v1/photo",
            {
                "message_id": "message-1",
                "mime_type": "image/jpeg",
                "data": "base64-data",
            },
        )

        self.assertEqual(200, status)
        self.assertEqual({"stored": True}, body)

        status, body = self.request(
            "GET",
            "/v1/photo?message_id=message-1",
        )

        self.assertEqual(200, status)
        self.assertEqual(
            {"mime_type": "image/jpeg", "data": "base64-data"},
            body,
        )

    def test_photo_upload_rejects_oversized_payload(self):
        status, body = self.request(
            "POST",
            "/v1/photo",
            {
                "message_id": "message-1",
                "mime_type": "image/jpeg",
                "data": "A" * (MAX_PHOTO_BYTES + 1),
            },
        )

        self.assertEqual(400, status)
        self.assertEqual({"error": "photo_too_large"}, body)
        self.assertIsNone(self.server.database.get_photo("message-1"))

    def test_photo_fetch_missing_message_id_returns_404(self):
        for path in ("/v1/photo", "/v1/photo?message_id=missing"):
            with self.subTest(path=path):
                status, body = self.request("GET", path)

                self.assertEqual(404, status)
                self.assertEqual({"error": "not_found"}, body)

    def test_photo_upload_requires_authorization(self):
        for key in (None, "wrong-key"):
            with self.subTest(key=key):
                status, body = self.request(
                    "POST",
                    "/v1/photo",
                    {
                        "message_id": "message-1",
                        "mime_type": "image/jpeg",
                        "data": "base64-data",
                    },
                    key=key,
                )

                self.assertEqual(401, status)
                self.assertEqual({"error": "unauthorized"}, body)
        self.assertIsNone(self.server.database.get_photo("message-1"))

    def test_broadcast_delivers_to_all_subscribers(self):
        client_key = "fedex-client-key"
        self.server.database.upsert_device("alice", "alice-token")
        self.server.database.upsert_device("bob", "bob-token")
        self.server.database.upsert_api_client(
            "fedex",
            _hash_client_key(client_key),
        )
        self.server.database.add_subscription("fedex", "alice")
        self.server.database.add_subscription("fedex", "bob")

        status, body = self.request(
            "POST",
            "/v1/broadcast",
            {"message": "Package update"},
            key=None,
            extra_headers={
                "X-Yo-Client-Id": "fedex",
                "X-Yo-Client-Key": client_key,
            },
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

    def test_broadcast_rejects_wrong_client_key(self):
        client_key = "fedex-client-key"
        self.server.database.upsert_api_client(
            "fedex",
            _hash_client_key(client_key),
        )
        credentials = (
            (None, None),
            (self.server_key, None),
            (
                None,
                {
                    "X-Yo-Client-Id": "fedex",
                    "X-Yo-Client-Key": "wrong-key",
                },
            ),
        )

        for key, extra_headers in credentials:
            with self.subTest(key=key, extra_headers=extra_headers):
                status, body = self.request(
                    "POST",
                    "/v1/broadcast",
                    {},
                    key=key,
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
            key=None,
            extra_headers={
                "X-Yo-Client-Id": "unknown",
                "X-Yo-Client-Key": "unknown-key",
            },
        )

        self.assertEqual(401, status)
        self.assertEqual({"error": "unauthorized"}, body)
        self.assertEqual([], self.fcm_client.calls)

    def test_broadcast_with_no_subscribers_returns_zero_counts(self):
        client_key = "fedex-client-key"
        self.server.database.upsert_api_client(
            "fedex",
            _hash_client_key(client_key),
        )

        status, body = self.request(
            "POST",
            "/v1/broadcast",
            {},
            key=None,
            extra_headers={
                "X-Yo-Client-Id": "fedex",
                "X-Yo-Client-Key": client_key,
            },
        )

        self.assertEqual(200, status)
        self.assertEqual(
            {"delivered": 0, "failed": 0, "subscriber_count": 0},
            body,
        )
        self.assertEqual([], self.fcm_client.calls)

    def test_broadcast_continues_past_single_delivery_failure(self):
        client_key = "fedex-client-key"
        self.server.database.upsert_device("alice", "alice-token")
        self.server.database.upsert_device("bob", "bob-token")
        self.server.database.upsert_api_client(
            "fedex",
            _hash_client_key(client_key),
        )
        self.server.database.add_subscription("fedex", "alice")
        self.server.database.add_subscription("fedex", "bob")
        self.fcm_client.fail_tokens.add("alice-token")

        status, body = self.request(
            "POST",
            "/v1/broadcast",
            {},
            key=None,
            extra_headers={
                "X-Yo-Client-Id": "fedex",
                "X-Yo-Client-Key": client_key,
            },
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
        client_key = "fedex-client-key"
        self.server.database.upsert_device("alice", "alice-token")
        self.server.database.upsert_device("bob", "bob-token")
        self.server.database.upsert_api_client(
            "fedex",
            _hash_client_key(client_key),
        )
        self.server.database.add_subscription("fedex", "alice")
        self.server.database.add_subscription("fedex", "bob")
        self.fcm_client.error = FCMNotConfiguredError("not configured")

        status, body = self.request(
            "POST",
            "/v1/broadcast",
            {},
            key=None,
            extra_headers={
                "X-Yo-Client-Id": "fedex",
                "X-Yo-Client-Key": client_key,
            },
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

    def test_missing_or_wrong_key_is_unauthorized_for_every_v1_route(self):
        routes = (
            (
                "POST",
                "/v1/register",
                {"username": "alice", "fcm_token": "token"},
            ),
            ("GET", "/v1/friends?username=alice", None),
            (
                "POST",
                "/v1/send",
                {"sender": "alice", "recipient": "bob"},
            ),
        )
        for method, path, body in routes:
            for key in (None, "wrong-key"):
                with self.subTest(method=method, path=path, key=key):
                    status, response = self.request(
                        method,
                        path,
                        body,
                        key=key,
                    )
                    self.assertEqual(401, status)
                    self.assertEqual({"error": "unauthorized"}, response)

    def test_healthz_does_not_require_authentication(self):
        status, body = self.request("GET", "/healthz", key=None)

        self.assertEqual(200, status)
        self.assertEqual({"ok": True}, body)

    def request(
        self,
        method,
        path,
        body=None,
        key=server_key,
        extra_headers=None,
    ):
        data = b""
        headers = Message()
        if body is not None:
            data = json.dumps(body).encode("utf-8")
            headers["Content-Type"] = "application/json"
            headers["Content-Length"] = str(len(data))
        if key is not None:
            headers["X-Yo-Key"] = key
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
        else:
            raise AssertionError(f"unsupported test method: {method}")

        response_head, response_body = handler.wfile.getvalue().split(
            b"\r\n\r\n",
            1,
        )
        status_line = response_head.splitlines()[0].decode("ascii")
        status = int(status_line.split(" ", 2)[1])
        return status, json.loads(response_body)


if __name__ == "__main__":
    unittest.main()
