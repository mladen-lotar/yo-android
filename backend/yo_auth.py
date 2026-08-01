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

# A fixed salt used only to spend the same CPU on a login that cannot possibly succeed. It is
# not a secret and never protects anything: its whole job is to make the "no such account" and
# "account has no password" paths cost what a real verification costs. See verify_password.
_EQUAL_COST_SALT = b"yo.equal.cost.16"

MIN_PASSWORD_LENGTH = 8
MAX_PASSWORD_LENGTH = 256

# What is stored for an account that has no password at all, because it signs in through Google.
# The column stays NOT NULL and "no password" never degrades into "any password works":
# verify_password rejects this structurally rather than by a check, since it does not split into
# the four fields an encoded hash has. No caller can opt out of that.
UNUSABLE_PASSWORD_HASH = "!"

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


def verify_password(
    password: str,
    encoded: Optional[str],
    *,
    equal_cost_iterations: int = DEFAULT_ITERATIONS,
) -> bool:
    """Check `password` against a stored hash, spending the same CPU whatever the answer.

    The equal-cost path is the security property, not a detail. Every early return this used to
    take was free, while a real account cost a 600,000-iteration PBKDF2 - measured at 294 ms in
    the production container. So `/v1/login` answered a byte-identical 401 in ~1 ms for a
    username that does not exist and in ~294 ms for one that does, which is a username oracle
    hundreds of times louder than network jitter and readable in a single request per guess.

    Identical response bytes were mistaken for indistinguishability. They are not the same claim:
    the bytes were always identical and the endpoint was always an oracle.

    Two distinct absent-hash cases funnel through here and both must cost the same as a real one:
    an unknown account (`None`), and an account created through Google sign-in, which stores
    UNUSABLE_PASSWORD_HASH and would otherwise be distinguishable from a password account by
    timing alone - a second oracle sitting behind the first.

    `equal_cost_iterations` must be the cost this deployment issues new hashes at, so the decoy
    and the real thing agree. Tests pass their own small value for both.
    """
    parsed = _decode(encoded)
    if parsed is None:
        # Deliberately discarded. The point is the elapsed time, not the digest.
        _derive(password, _EQUAL_COST_SALT, max(1, equal_cost_iterations))
        return False
    salt, expected, iterations = parsed
    return hmac.compare_digest(_derive(password, salt, iterations), expected)


def _decode(encoded: Optional[str]) -> Optional[tuple[bytes, bytes, int]]:
    """Split an encoded hash into (salt, digest, iterations), or None if it is not one.

    UNUSABLE_PASSWORD_HASH ("!") lands here and returns None structurally rather than by a
    check, because it does not split into four fields - so "no password" can never degrade into
    "any password works", and no caller can opt out of that.
    """
    if not encoded:
        return None
    try:
        algorithm, iterations_text, salt_hex, digest_hex = encoded.split("$")
    except (ValueError, AttributeError):
        return None
    if algorithm != ALGORITHM:
        return None
    try:
        iterations = int(iterations_text)
        salt = bytes.fromhex(salt_hex)
        expected = bytes.fromhex(digest_hex)
    except ValueError:
        return None
    if iterations < 1:
        return None
    return salt, expected, iterations


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
