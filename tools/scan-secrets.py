#!/usr/bin/env python3
"""Refuse to let a credential into this repository again.

This exists because one already got in. `store/listing.md` carried the live password of a real
production account, in a **public** repository, from 2026-07-29 until it was found by an audit on
2026-08-01 - and it survived a review that produced a written report quoting the password back.
See docs/RELEASE.md section 4c.

The lesson is not "be more careful". The rule was already written down and already correct: PRD
section 7.1 records the demo password as "generated and reported to the user, deliberately NOT
committed". A rule that depends on somebody remembering it at the moment they are busy doing
something else is not a control. This is the mechanical version.

Scans TRACKED files by default (`git ls-files`), so gitignored `local.properties`,
`app/google-services.json` and `backend/secrets/` are out of scope by construction - they are
meant to hold this material. Pass --staged to scan the git INDEX instead (what a pre-commit hook
needs): see tools/install-hooks.sh.

## Why there is no allowlist

An earlier version of this scanner had one. After the leak above, `store/listing.md` was fixed to
read `Password: <from the operator's password manager - NOT stored in this repository>`, and that
reassuring sentence - plus a few others like it - was added to a module-level ALLOWED regex that
the old scanner checked against the WHOLE LINE before any credential pattern ever ran:
`if ALLOWED.search(line): continue`. That dropped the entire line, so a real password followed by
the sentence asserting it is safe was invisible for exactly the same reason the sentence itself
was reassuring to a human reviewer. Measured on that old code, one line at a time:

    Password: <a real generated password>                                    CAUGHT
    Password: <a real generated password>  <from the operator ...>           SKIPPED
    Password: <a real generated password> (not stored in this repository)    SKIPPED

Every pattern below was also run over every tracked file in this repository with that allowlist
bypassed entirely: zero lines matched anything. The allowlist was not suppressing false positives
- it was suppressing the one true positive it was written for. So there is no line-level
allowlist here at all. The judgement of "is this a placeholder" happens in Python, per CAPTURED
VALUE, in is_placeholder() below - never by pattern-matching the surrounding prose. Rewriting or
deleting a reassuring sentence cannot reopen this hole, because the sentence is never consulted.

A scanner with a high false-positive rate gets an allowlist, then gets skipped, then gets deleted;
one that only fires on things that are nearly always real keeps its authority. Each pattern below
is here because it matches a shape that has either already appeared in this repository or would be
unambiguous if it did.
"""

import argparse
import re
import subprocess
import sys
from pathlib import Path

# Structural patterns: these are anchored to a fixed, unambiguous prefix (a PEM header, a JSON
# field name, a key-id format, a scheme name) so the character class of what follows them barely
# matters. They stay simple regexes. The password/passwd/pwd family below is different - its
# "value" can legitimately contain almost any character, which is exactly what a pure regex got
# wrong three different ways (see PATTERNS docstring notes below), so that one is hand-rolled.
PATTERNS = [
    (
        "private key block",
        re.compile(r"-----BEGIN (?:RSA |EC |OPENSSH |PGP )?PRIVATE KEY-----"),
        "a private key. The Firebase service-account key must never leave secrets/.",
    ),
    (
        "google service account json",
        re.compile(r"\"type\"\s*:\s*\"service_account\""),
        "a Google service-account key body.",
    ),
    (
        "google api key",
        re.compile(r"\bAIza[0-9A-Za-z_\-]{35}\b"),
        "a Google API key.",
    ),
    (
        "bearer token literal",
        re.compile(r"(?i)\bBearer\s+[A-Za-z0-9_\-]{24,}"),
        "a bearer token.",
    ),
]

# --- password / passwd / pwd family -----------------------------------------------------------
#
# This used to be one regex: `(?i)\b(password|passwd|pwd)\b\s*[:=]\s*[...]{10,}`. Three of its
# holes:
#
#   1. `(?i)` scopes over the WHOLE pattern, including the value. The comment above the old regex
#      claimed the value "must have both upper and lower case", but with a blanket case-insensitive
#      flag that requirement was inert - `(?=[A-Z])` is satisfied by a lowercase letter under
#      `(?i)`, same as `(?=[a-z])` is satisfied by an uppercase one. The stated rule was never the
#      enforced one. Case-insensitivity is scoped to the KEYWORD only below; the value rule is
#      plain Python (is_secret_shaped) so its docstring can say what it actually checks.
#   2. `\b` is a word-boundary between a \w character and a non-\w one. `_` counts as \w, so there
#      is no boundary between `_` and `P`, and `\bpassword\b` never matches inside
#      `YO_KEYSTORE_PASSWORD` or `DB_PASSWORD` at all. Real identifiers in this repo look exactly
#      like that (yoKeystorePassword / YO_KEYSTORE_PASSWORD in app/build.gradle.kts and
#      docs/RELEASE.md). find_password_findings() below matches the keyword as a substring of a
#      larger identifier instead of requiring it to stand alone.
#   3. The value character class, `[A-Za-z0-9!@#$%^&*_\-+=]`, excludes `.` and `/` - so a
#      *stronger* generated password (more punctuation variety) is *less* likely to be caught than
#      a weak one. Below, the whole whitespace-delimited token is captured regardless of which
#      characters it contains, and only surrounding quotes / trailing sentence punctuation are
#      stripped.
#
# On top of the character-class problem, requiring `[:=]` right after the keyword misses a
# markdown table row - and the file that leaked (`store/listing.md`) is markdown. `|` is accepted
# as a separator too, with the captured region bounded to the adjacent cell so a later table cell
# ("Stored as PBKDF2-HMAC-SHA256, never in clear" two cells over from a "Password" row label) can
# never be captured as if it were the value.

KEYWORD_SUBSTRINGS = ("password", "passwd", "pwd")

# An identifier token: letters, digits, underscore. This is what lets the keyword match anywhere
# inside a larger name (YO_KEYSTORE_PASSWORD, yoKeystorePassword, DB_PASSWORD) instead of only as
# a whole word.
_IDENTIFIER_RE = re.compile(r"[A-Za-z0-9_]+")

# A separator that plausibly introduces a value: `:` or `=` as before, plus `|` for a markdown
# table cell boundary.
_SEPARATOR_RE = re.compile(r"\s*([:=|])")

# A value is a "variable reference", not a literal, if it is SCREAMING_SNAKE_CASE - the shape of
# an environment variable or constant name (TEST_PASSWORD, YO_KEYSTORE_PASSWORD, DB_PASSWORD),
# never of something a password generator produces. Requires at least one underscore so a random
# all-caps generated secret (which happens to contain no lowercase letters) is not swept in here
# by accident - is_secret_shaped's digit requirement mostly guards that case anyway, but this is
# belt and suspenders.
_SCREAMING_SNAKE_RE = re.compile(r"^[A-Z][A-Z0-9]*(?:_[A-Z0-9]+)+$")

# Exact-match words that look secret-shaped (10+ chars, a letter and a digit) but are known,
# deliberately-fake fixtures or documented non-values - not something a generator produced. Kept
# short and exact-match on purpose: a substring allowlist is exactly the mechanism that hid the
# real leak (see module docstring), so nothing here is checked against a substring of the line,
# only against the whole captured value.
KNOWN_SAFE_VALUES = frozenset(
    {
        "password",
        "passwd",
        "changeme",
        "placeholder",
        "redacted",
        "example",
        "xxxx",
        "password123",
        "hunter2",
        "correct-horse",
        "correcthorse",
        "correct-horse-battery",
        "wrongpassword",
        "some-attempted-password",
        "the-real-password",
        "not-it",
        "raw-client-key",
        "secret-key",
    }
)

# Characters a value is allowed to *start* with and still be recognised as a placeholder rather
# than a secret: template/interpolation openers and bracket-wrapped descriptions
# (`<from the operator...>`, `{{ secret }}`, `$ENV_VAR`, `(not stored in this repository)`,
# `[REDACTED]`).
_PLACEHOLDER_PREFIXES = ("<", "{", "$", "(", "[")


def is_placeholder(value: str) -> bool:
    """True if VALUE is structurally a placeholder, independent of any words around it on the
    line. This is the judgement that used to live in a line-level ALLOWED regex (see module
    docstring for why that was the bug); it now runs on the captured value alone, so a reassuring
    sentence appended to a real secret cannot change the verdict.
    """
    if not value:
        return True
    if value[0] in _PLACEHOLDER_PREFIXES:
        return True
    if value.startswith("***"):
        return True
    if value.lower() in KNOWN_SAFE_VALUES:
        return True
    if _SCREAMING_SNAKE_RE.match(value):
        return True
    return False


def is_secret_shaped(value: str) -> bool:
    """True if VALUE is 10 or more characters and contains at least one letter and at least one
    digit - the shape of something a generator produced, not something typed or referenced. This
    says nothing about case: an earlier version of this scanner claimed to require both upper and
    lower case, but that requirement was applied under a blanket case-insensitive flag and so was
    never actually enforced. This function checks only what it actually checks.
    """
    if len(value) < 10:
        return False
    has_letter = any(character.isalpha() for character in value)
    has_digit = any(character.isdigit() for character in value)
    return has_letter and has_digit


def is_secret_value(value: str) -> bool:
    return not is_placeholder(value) and is_secret_shaped(value)


def _clean_value_token(raw: str) -> str:
    """Strip only surrounding quotes and trailing sentence punctuation. Leading bracket/template
    characters (<, {, $, (, [) are deliberately NOT stripped - they are the placeholder signal
    is_placeholder() looks for, so removing them would erase the thing that makes a placeholder
    recognisable as one.
    """
    token = raw.strip("\"'`")
    token = token.rstrip(".,;:!?")
    return token


def _keyword_is_shell_deref(line: str, start: int) -> bool:
    """Guard against `$PWD` / `${PWD}` - a shell working-directory reference, not a password."""
    if start > 0 and line[start - 1] == "$":
        return True
    if start > 1 and line[start - 1] == "{" and line[start - 2] == "$":
        return True
    return False


def find_password_findings(line: str):
    """Yield (keyword_token, secret_value) for every keyword-bearing identifier on LINE that is
    followed by an assignment-like separator (`:`, `=`, or a markdown table `|`) and at least one
    non-placeholder, secret-shaped value token.

    Every whitespace-delimited token after the separator is classified, not just the first, so a
    real secret sitting after a placeholder on the same line
    (`Password: <placeholder> Xk9mQzL2Pw`) is still caught. When the separator is a table pipe, or
    a pipe appears later in the captured region, the region is bounded to the adjacent cell so a
    later, unrelated table cell is never treated as this keyword's value.
    """
    findings = []
    for match in _IDENTIFIER_RE.finditer(line):
        token = match.group(0)
        lowered = token.lower()
        if not any(keyword in lowered for keyword in KEYWORD_SUBSTRINGS):
            continue
        if _keyword_is_shell_deref(line, match.start()):
            continue

        rest = line[match.end() :]
        separator_match = _SEPARATOR_RE.match(rest)
        if not separator_match:
            continue

        after_separator = rest[separator_match.end() :]
        pipe_index = after_separator.find("|")
        value_region = after_separator if pipe_index == -1 else after_separator[:pipe_index]

        for raw_value in value_region.split():
            value = _clean_value_token(raw_value)
            if is_secret_value(value):
                findings.append((token, value))
    return findings


def line_findings(line: str):
    """All findings on a single line, as (name, why, excerpt) - shared by the tracked-file scan,
    the --staged scan, and the unit tests.
    """
    results = []
    for keyword_token, value in find_password_findings(line):
        results.append(
            (
                "password assignment",
                f"a literal secret assigned to `{keyword_token}`.",
            )
        )
    for name, pattern, why in PATTERNS:
        if pattern.search(line):
            results.append((name, why))
    return results


# --- file discovery ----------------------------------------------------------------------------
#
# The old version skipped any file whose suffix wasn't in a fixed allowlist of "text" extensions -
# which means a tracked, extensionless file (a `Dockerfile`, an `.env` with no leading dot stripped
# by some tooling, or this repository's own `gradlew`) was invisible by construction, forever,
# regardless of content. Replaced with a small skip-set for formats that are unambiguously binary
# by extension, plus a NUL-byte sniff of the actual bytes for everything else - which also catches
# a binary file that happens to carry an extension not in the skip set.

BINARY_SKIP_SUFFIXES = {
    ".png",
    ".jpg",
    ".jpeg",
    ".gif",
    ".ico",
    ".webp",
    ".bmp",
    ".ttf",
    ".otf",
    ".woff",
    ".woff2",
    ".mp3",
    ".mp4",
    ".wav",
    ".ogg",
    ".jar",
    ".zip",
    ".gz",
    ".tar",
    ".7z",
    ".class",
    ".so",
    ".dylib",
    ".dll",
    ".exe",
    ".pdf",
    ".keystore",
    ".jks",
}

_SNIFF_BYTES = 8192

# This scanner's own source quotes its patterns and known-safe values, so it would always match
# itself; the test file embeds positive-control secret-shaped strings on purpose. Both are
# excluded by path, the same way the previous version excluded itself.
SELF_EXCLUDE_PATHS = {"tools/scan-secrets.py", "tools/test_scan_secrets.py"}


def _is_binary(data: bytes) -> bool:
    return b"\0" in data[:_SNIFF_BYTES]


def _read_scannable_text(read_bytes) -> "str | None":
    """READ_BYTES is a zero-arg callable returning the file's raw bytes. Returns decoded text, or
    None if the content should not be scanned (binary, unreadable).
    """
    try:
        raw = read_bytes()
    except (OSError, subprocess.CalledProcessError):
        return None
    if raw is None:
        return None
    if _is_binary(raw):
        return None
    try:
        return raw.decode("utf-8")
    except UnicodeDecodeError:
        return None


def tracked_files() -> list[Path]:
    out = subprocess.run(
        ["git", "ls-files", "-z"],
        capture_output=True,
        check=True,
        text=True,
    ).stdout
    return [Path(name) for name in out.split("\0") if name]


def staged_files() -> list[Path]:
    """Files staged for commit (added/copied/modified/renamed - not deletions)."""
    out = subprocess.run(
        ["git", "diff", "--cached", "--name-only", "--diff-filter=ACMR", "-z"],
        capture_output=True,
        check=True,
        text=True,
    ).stdout
    return [Path(name) for name in out.split("\0") if name]


def read_staged_bytes(path: Path) -> bytes:
    """The INDEX version of PATH - what will actually be committed, not the working-tree copy,
    which may differ from what was `git add`-ed.
    """
    result = subprocess.run(
        ["git", "show", f":{path.as_posix()}"],
        capture_output=True,
        check=True,
    )
    return result.stdout


def _should_skip_by_suffix(path: Path) -> bool:
    return path.suffix.lower() in BINARY_SKIP_SUFFIXES


def scan_text(path: Path, text: str) -> list:
    findings = []
    for number, line in enumerate(text.splitlines(), start=1):
        for name, why in line_findings(line):
            findings.append((path, number, name, why, line.strip()[:160]))
    return findings


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--staged",
        action="store_true",
        help="Scan the git index (staged content) instead of the tracked working tree. "
        "Used by the pre-commit hook installed by tools/install-hooks.sh.",
    )
    args = parser.parse_args()

    files = staged_files() if args.staged else tracked_files()
    findings = []
    for path in files:
        if path.as_posix() in SELF_EXCLUDE_PATHS:
            continue
        if _should_skip_by_suffix(path):
            continue
        if args.staged:
            text = _read_scannable_text(lambda p=path: read_staged_bytes(p))
        else:
            text = _read_scannable_text(lambda p=path: p.read_bytes())
        if text is None:
            continue
        findings.extend(scan_text(path, text))

    scope = "staged" if args.staged else "tracked"
    if not findings:
        print(f"scan-secrets: clean ({len(files)} {scope} files)")
        return 0

    print(f"scan-secrets: possible credentials in {scope.upper()} files\n", file=sys.stderr)
    for path, number, name, why, excerpt in findings:
        print(f"  {path}:{number}  [{name}]", file=sys.stderr)
        print(f"      {why}", file=sys.stderr)
        print(f"      {excerpt}", file=sys.stderr)
        print(file=sys.stderr)
    print(
        "If this is a false positive, make the VALUE unambiguous (e.g. wrap it as "
        "<a placeholder>) rather than adding words to a line-level allowlist - that is exactly "
        "the mechanism that hid the real leak. See docs/RELEASE.md section 4c and the module "
        "docstring in this file.",
        file=sys.stderr,
    )
    return 1


if __name__ == "__main__":
    sys.exit(main())
