import io
import json
import os
import tempfile
import unittest
from email.message import Message
from types import SimpleNamespace

from fcm_client import FCMNotConfiguredError
from yo_db import YoDatabase
from yo_server import YoRequestHandler


class RecordingFCMClient:
    def __init__(self):
        self.calls = []
        self.result = True
        self.error = None

    def send_yo(self, fcm_token, sender):
        self.calls.append((fcm_token, sender))
        if self.error is not None:
            raise self.error
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

    def request(self, method, path, body=None, key=server_key):
        data = b""
        headers = Message()
        if body is not None:
            data = json.dumps(body).encode("utf-8")
            headers["Content-Type"] = "application/json"
            headers["Content-Length"] = str(len(data))
        if key is not None:
            headers["X-Yo-Key"] = key

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
