"""Password hashing, bearer tokens and username rules for per-user credentials.

Standard library only. The backend's sole runtime dependency is google-auth, and that exists
purely for FCM delivery (docs/PRD.md section 4), so no bcrypt/argon2/passlib here.
"""

import hashlib
import hmac
import re
import secrets
from typing import Optional


# OWASP's 2023 floor for PBKDF2-HMAC-SHA256. hashlib delegates to OpenSSL's C implementation,
# so this is a few hundred milliseconds per call rather than the seconds a pure-Python loop
# would cost. Tests pass a smaller value explicitly; the default is what production uses.
DEFAULT_ITERATIONS = 600_000

ALGORITHM = "pbkdf2_sha256"
_SALT_BYTES = 16
_TOKEN_BYTES = 32

MIN_PASSWORD_LENGTH = 8
MAX_PASSWORD_LENGTH = 256

# Yo's own API documented the field as an "UPPERCASE username", and the app rendered every
# username in caps, so uppercase is the canonical form rather than a display choice.
USERNAME_PATTERN = re.compile(r"^[A-Z0-9_]{2,32}$")


class CredentialError(ValueError):
    """A username or password that fails the rules below."""


def normalize_username(raw: str) -> str:
    return raw.strip().upper()


def validate_username(raw: str) -> str:
    """Return the canonical form of `raw`, or raise CredentialError."""
    username = normalize_username(raw)
    if not USERNAME_PATTERN.match(username):
        raise CredentialError(
            "username must be 2-32 characters of A-Z, 0-9 or underscore"
        )
    return username


def validate_password(password: str) -> str:
    if not isinstance(password, str) or len(password) < MIN_PASSWORD_LENGTH:
        raise CredentialError(
            f"password must be at least {MIN_PASSWORD_LENGTH} characters"
        )
    if len(password) > MAX_PASSWORD_LENGTH:
        raise CredentialError(
            f"password must be at most {MAX_PASSWORD_LENGTH} characters"
        )
    return password


def hash_password(password: str, iterations: int = DEFAULT_ITERATIONS) -> str:
    """Encode as `pbkdf2_sha256$<iterations>$<salt_hex>$<digest_hex>`.

    The iteration count travels with the hash so it can be raised later without invalidating
    credentials issued under the old cost.
    """
    salt = secrets.token_bytes(_SALT_BYTES)
    digest = _derive(password, salt, iterations)
    return f"{ALGORITHM}${iterations}${salt.hex()}${digest.hex()}"


def verify_password(password: str, encoded: Optional[str]) -> bool:
    if not encoded:
        return False
    try:
        algorithm, iterations_text, salt_hex, digest_hex = encoded.split("$")
        if algorithm != ALGORITHM:
            return False
        iterations = int(iterations_text)
        salt = bytes.fromhex(salt_hex)
        expected = bytes.fromhex(digest_hex)
    except (ValueError, AttributeError):
        return False
    if iterations < 1:
        return False
    return hmac.compare_digest(_derive(password, salt, iterations), expected)


def new_token() -> str:
    """A bearer token handed to one device. Returned to the client exactly once."""
    return secrets.token_urlsafe(_TOKEN_BYTES)


def hash_token(token: str) -> str:
    """Tokens are stored hashed, so a database read does not yield usable credentials.

    A plain SHA-256 is right here where it would be wrong for a password: a token is 256 bits
    of `secrets` output, so there is no low-entropy guess space for an attacker to grind.
    """
    return hashlib.sha256(token.encode("utf-8")).hexdigest()


def _derive(password: str, salt: bytes, iterations: int) -> bytes:
    return hashlib.pbkdf2_hmac("sha256", password.encode("utf-8"), salt, iterations)
