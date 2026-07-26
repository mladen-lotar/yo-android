import unittest
from unittest import mock

import yo_google


class FakeIdTokenModule:
    """Stands in for google.oauth2.id_token so the checks around it can be tested offline."""

    def __init__(self, claims=None, error=None):
        self.claims = claims
        self.error = error
        self.calls = []

    def verify_oauth2_token(self, raw_token, request, audience):
        self.calls.append((raw_token, audience))
        if self.error is not None:
            raise self.error
        return self.claims


class FakeTransportError(Exception):
    pass


class ConfiguredClientIdTest(unittest.TestCase):
    def test_returns_none_when_unset(self):
        with mock.patch.dict("os.environ", {}, clear=True):
            self.assertIsNone(yo_google.configured_client_id())

    def test_returns_none_when_blank_or_whitespace(self):
        for value in ("", "   "):
            with self.subTest(value=repr(value)):
                with mock.patch.dict(
                    "os.environ",
                    {yo_google.CLIENT_ID_ENVIRONMENT_VARIABLE: value},
                    clear=True,
                ):
                    self.assertIsNone(yo_google.configured_client_id())

    def test_returns_the_trimmed_client_id(self):
        with mock.patch.dict(
            "os.environ",
            {yo_google.CLIENT_ID_ENVIRONMENT_VARIABLE: "  123.apps.example  "},
            clear=True,
        ):
            self.assertEqual("123.apps.example", yo_google.configured_client_id())


class VerifierTest(unittest.TestCase):
    CLIENT_ID = "123.apps.googleusercontent.com"

    def verify(self, claims=None, error=None):
        fake = FakeIdTokenModule(claims=claims, error=error)
        verifier = yo_google.GoogleIdTokenVerifier(self.CLIENT_ID)
        with mock.patch.object(
            yo_google,
            "_load_google_auth",
            return_value=(fake, FakeTransportError),
        ), mock.patch.object(yo_google, "new_auth_request", return_value=object()):
            return verifier.subject_for("an.id.token"), fake

    def test_returns_the_subject_claim(self):
        subject, fake = self.verify(
            claims={"iss": "https://accounts.google.com", "sub": "108"},
        )
        self.assertEqual("108", subject)

    def test_pins_the_audience_to_the_configured_client_id(self):
        _, fake = self.verify(
            claims={"iss": "accounts.google.com", "sub": "108"},
        )
        self.assertEqual([("an.id.token", self.CLIENT_ID)], fake.calls)

    def test_accepts_both_documented_issuer_spellings(self):
        for issuer in yo_google.ISSUERS:
            with self.subTest(issuer=issuer):
                subject, _ = self.verify(claims={"iss": issuer, "sub": "108"})
                self.assertEqual("108", subject)

    def test_rejects_a_token_from_another_issuer(self):
        with self.assertRaises(yo_google.GoogleAuthError):
            self.verify(claims={"iss": "https://evil.example", "sub": "108"})

    def test_rejects_a_token_with_no_subject(self):
        for claims in (
            {"iss": "accounts.google.com"},
            {"iss": "accounts.google.com", "sub": ""},
            {"iss": "accounts.google.com", "sub": 108},
        ):
            with self.subTest(claims=claims):
                with self.assertRaises(yo_google.GoogleAuthError):
                    self.verify(claims=claims)

    def test_a_rejected_token_is_an_auth_error_not_an_outage(self):
        """google-auth reports bad signature, wrong audience and expiry all as ValueError."""
        with self.assertRaises(yo_google.GoogleAuthError):
            self.verify(error=ValueError("Token expired"))

    def test_a_transport_failure_is_an_outage_not_a_rejection(self):
        with self.assertRaises(yo_google.GoogleUnavailableError):
            self.verify(error=FakeTransportError("certs unreachable"))


class LoadGoogleAuthTest(unittest.TestCase):
    def test_missing_library_is_reported_as_unavailable(self):
        """A deployment without google-auth must degrade to 503, never fail to import."""
        real_import = __import__

        def fail_for_google(name, *arguments, **keywords):
            if name.startswith("google"):
                raise ImportError(f"No module named {name!r}")
            return real_import(name, *arguments, **keywords)

        with mock.patch("builtins.__import__", side_effect=fail_for_google):
            with self.assertRaises(yo_google.GoogleUnavailableError):
                yo_google._load_google_auth()


if __name__ == "__main__":
    unittest.main()
