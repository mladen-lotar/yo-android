import json
import unittest
import urllib.error
from unittest import mock

import fcm_client


class FakeResponse:
    def __init__(self, status=200):
        self.status = status

    def read(self, *_):
        return b"{}"

    def __enter__(self):
        return self

    def __exit__(self, *_):
        return False


class RecordingUrlopen:
    """Captures the outbound request instead of talking to FCM."""

    def __init__(self, status=200):
        self.status = status
        self.requests = []

    def __call__(self, request, timeout=None):
        self.requests.append(request)
        return FakeResponse(self.status)

    @property
    def payload(self):
        return json.loads(self.requests[-1].data.decode("utf-8"))


def configured_client():
    return fcm_client.FCMClient(
        service_account_path="/nonexistent/service-account.json",
        project_id="yo-theshop",
    )


class ConfigurationTest(unittest.TestCase):
    def test_missing_configuration_is_not_a_delivery_failure(self):
        client = fcm_client.FCMClient()
        with mock.patch.dict("os.environ", {}, clear=True):
            with self.assertRaises(fcm_client.FCMNotConfiguredError):
                client.send_yo("token", "ALICE")

    def test_project_id_alone_is_not_enough(self):
        client = fcm_client.FCMClient()
        with mock.patch.dict(
            "os.environ",
            {"YO_FIREBASE_PROJECT_ID": "yo-theshop"},
            clear=True,
        ):
            with self.assertRaises(fcm_client.FCMNotConfiguredError):
                client.send_yo("token", "ALICE")

    def test_environment_supplies_configuration(self):
        client = fcm_client.FCMClient()
        transport = RecordingUrlopen()
        with mock.patch.dict(
            "os.environ",
            {
                "YO_FIREBASE_SA_KEY": "/nonexistent/service-account.json",
                "YO_FIREBASE_PROJECT_ID": "yo-theshop",
            },
            clear=True,
        ):
            with mock.patch.object(
                fcm_client.FCMClient, "_access_token", return_value="access-token"
            ):
                with mock.patch("urllib.request.urlopen", transport):
                    self.assertTrue(client.send_yo("device-token", "ALICE"))
        self.assertIn("yo-theshop", transport.requests[-1].full_url)


class SendYoTest(unittest.TestCase):
    def setUp(self):
        self.transport = RecordingUrlopen()
        access_token = mock.patch.object(
            fcm_client.FCMClient, "_access_token", return_value="access-token"
        )
        access_token.start()
        self.addCleanup(access_token.stop)
        urlopen = mock.patch("urllib.request.urlopen", self.transport)
        urlopen.start()
        self.addCleanup(urlopen.stop)

    def test_yo_is_sent_at_high_priority(self):
        """A normal-priority data message may wait for Doze's next maintenance window."""
        configured_client().send_yo("device-token", "ALICE")
        self.assertEqual(
            "high",
            self.transport.payload["message"]["android"]["priority"],
        )

    def test_payload_addresses_the_device_and_names_the_sender(self):
        configured_client().send_yo("device-token", "ALICE")
        message = self.transport.payload["message"]
        self.assertEqual("device-token", message["token"])
        self.assertEqual("yo", message["data"]["type"])
        self.assertEqual("ALICE", message["data"]["sender"])

    def test_timestamp_is_a_millisecond_string(self):
        configured_client().send_yo("device-token", "ALICE")
        timestamp = self.transport.payload["message"]["data"]["timestamp"]
        self.assertIsInstance(timestamp, str)
        self.assertGreater(int(timestamp), 1_600_000_000_000)

    def test_request_targets_the_projects_v1_endpoint(self):
        configured_client().send_yo("device-token", "ALICE")
        request = self.transport.requests[-1]
        self.assertEqual(
            "https://fcm.googleapis.com/v1/projects/yo-theshop/messages:send",
            request.full_url,
        )
        self.assertEqual("POST", request.get_method())

    def test_access_token_is_presented_as_a_bearer(self):
        configured_client().send_yo("device-token", "ALICE")
        headers = self.transport.requests[-1].headers
        self.assertEqual("Bearer access-token", headers["Authorization"])


class DeliveryFailureTest(unittest.TestCase):
    def setUp(self):
        access_token = mock.patch.object(
            fcm_client.FCMClient, "_access_token", return_value="access-token"
        )
        access_token.start()
        self.addCleanup(access_token.stop)

    def test_http_error_becomes_a_delivery_error(self):
        error = urllib.error.HTTPError(
            url="https://fcm.googleapis.com",
            code=404,
            msg="Not Found",
            hdrs=None,
            fp=None,
        )
        error.read = lambda *_: b'{"error":{"status":"NOT_FOUND"}}'
        with mock.patch("urllib.request.urlopen", side_effect=error):
            with self.assertRaises(fcm_client.FCMDeliveryError) as raised:
                configured_client().send_yo("stale-token", "ALICE")
        self.assertIn("404", str(raised.exception))

    def test_transport_error_becomes_a_delivery_error(self):
        with mock.patch(
            "urllib.request.urlopen",
            side_effect=urllib.error.URLError("connection refused"),
        ):
            with self.assertRaises(fcm_client.FCMDeliveryError):
                configured_client().send_yo("device-token", "ALICE")

    def test_non_2xx_without_an_exception_is_reported_undelivered(self):
        with mock.patch("urllib.request.urlopen", RecordingUrlopen(status=304)):
            self.assertFalse(configured_client().send_yo("device-token", "ALICE"))


if __name__ == "__main__":
    unittest.main()
