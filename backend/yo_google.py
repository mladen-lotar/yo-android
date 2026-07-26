"""Verification of Google ID tokens, for "continue with Google" sign-in.

Only the subject claim is read. Google's `sub` is the stable per-application identifier for an
account; an email address is neither stable (people rename them) nor permanently unique (a
Workspace address can be reassigned to a different person), so keying an identity on the address
would eventually hand somebody another person's account. Nothing else from the token is read or
stored - the app has no use for the address, so it never keeps one.
"""

import os
from typing import Any, Optional, Tuple

from fcm_client import new_auth_request


PROVIDER = "google"

CLIENT_ID_ENVIRONMENT_VARIABLE = "YO_GOOGLE_CLIENT_ID"

# Google mints the `iss` claim with and without the scheme depending on the vintage of the
# client; both are legitimate and both are documented.
ISSUERS = ("accounts.google.com", "https://accounts.google.com")


class GoogleAuthError(Exception):
    """The token was malformed, expired, signed by somebody else, or minted for another app."""


class GoogleUnavailableError(Exception):
    """The token could not be checked at all: no google-auth installed, or Google unreachable.

    Kept separate from GoogleAuthError because the two mean opposite things to the caller. A
    rejected token is the caller's problem and must not be retried; an unreachable verifier is
    ours and should be.
    """


def configured_client_id() -> Optional[str]:
    """The OAuth web client id this deployment accepts tokens for, or None if unset.

    Unset is a supported state, not a broken one: the feature stays dark, the route answers 503
    and the app hides its Google band, so no build ever shows a button that cannot work.
    """
    value = os.environ.get(CLIENT_ID_ENVIRONMENT_VARIABLE, "").strip()
    return value or None


class GoogleIdTokenVerifier:
    """Checks a Google ID token locally and returns its subject.

    Local verification rather than a call to Google's tokeninfo endpoint: the signature, audience
    and expiry are all checked against cached certificates, so a sign-in costs no round trip and
    does not inherit Google's rate limit on that debugging endpoint.
    """

    def __init__(self, client_id: str):
        self._client_id = client_id

    def subject_for(self, raw_token: str) -> str:
        google_id_token, transport_error = _load_google_auth()
        try:
            claims = google_id_token.verify_oauth2_token(
                raw_token,
                new_auth_request(),
                self._client_id,
            )
        except ValueError as error:
            # google-auth reports every "this token is not acceptable" case as ValueError:
            # bad signature, wrong audience, expired, or simply not a JWT.
            raise GoogleAuthError(str(error)) from error
        except transport_error as error:
            raise GoogleUnavailableError(str(error)) from error

        # verify_oauth2_token already pins the audience and the expiry. The issuer check is
        # repeated here so that this holds even if the library's own check is ever relaxed.
        if claims.get("iss") not in ISSUERS:
            raise GoogleAuthError("unexpected issuer")
        subject = claims.get("sub")
        if not isinstance(subject, str) or not subject:
            raise GoogleAuthError("token carries no subject")
        return subject


def _load_google_auth() -> Tuple[Any, type]:
    """Return google-auth's id_token module and its transport error class.

    Imported here rather than at module scope so that a deployment without google-auth installed
    still serves every other route, and answers 503 on this one, instead of failing to start.
    """
    try:
        from google.auth.exceptions import TransportError
        from google.oauth2 import id_token
    except ImportError as error:
        raise GoogleUnavailableError(
            "google-auth is not installed; see backend/requirements.txt"
        ) from error
    return id_token, TransportError
