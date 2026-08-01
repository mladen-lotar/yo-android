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

Scans TRACKED files only, so gitignored `local.properties`, `app/google-services.json` and
`backend/secrets/` are out of scope by construction - they are meant to hold this material.

Deliberately narrow. A scanner with a high false-positive rate gets an allowlist, then gets
skipped, then gets deleted; one that only fires on things that are nearly always real keeps its
authority. Each pattern below is here because it matches a shape that has either already appeared
in this repository or would be unambiguous if it did.
"""

import re
import subprocess
import sys
from pathlib import Path

# (name, pattern, why it matters)
PATTERNS = [
    (
        "password assignment",
        re.compile(
            r"(?i)\b(password|passwd|pwd)\b\s*[:=]\s*[\"'`]?"
            # The VALUE must look generated, not like an identifier. `password=password` and
            # `password=TEST_PASSWORD` are variable references and matched the earlier form of
            # this pattern, which is how a scanner earns four false positives on its first run
            # and gets switched off on its second. Requiring both cases AND a digit is the shape
            # of something a generator produced - which is what a real credential here is.
            r"(?=[A-Za-z0-9!@#$%^&*_\-+=]*[a-z])"
            r"(?=[A-Za-z0-9!@#$%^&*_\-+=]*[A-Z])"
            r"(?=[A-Za-z0-9!@#$%^&*_\-+=]*[0-9])"
            r"([A-Za-z0-9!@#$%^&*_\-+=]{10,})",
        ),
        "a literal password. The demo account's real one lived in store/listing.md.",
    ),
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
        "keystore password property",
        re.compile(r"(?i)\byo(Keystore|Key)Password\b\s*=\s*\S+"),
        "the upload keystore password. It belongs in local.properties, which is gitignored.",
    ),
    (
        "bearer token literal",
        re.compile(r"(?i)\bBearer\s+[A-Za-z0-9_\-]{24,}"),
        "a bearer token.",
    ),
]

# Values that look like a hit but are the documented ABSENCE of a secret, or a test fixture whose
# whole purpose is to be fake. Kept short on purpose: every entry is a hole.
ALLOWED = re.compile(
    r"(?i)"
    r"<from the operator|not recorded here|not stored in this repository"
    r"|password must be at least|password must be at most"
    r"|correct-horse|correcthorse|wrongpassword|some-attempted-password|the-real-password"
    r"|hunter2|password123|not-it|raw-client-key|secret-key"
    r"|<your|example|placeholder|redacted|xxxx"
)

TEXT_SUFFIXES = {
    ".md", ".py", ".kt", ".kts", ".java", ".xml", ".json", ".yml", ".yaml",
    ".properties", ".txt", ".sh", ".toml", ".cfg", ".gradle", ".pro", ".in",
}


def tracked_files() -> list[Path]:
    out = subprocess.run(
        ["git", "ls-files", "-z"],
        capture_output=True,
        check=True,
        text=True,
    ).stdout
    return [Path(name) for name in out.split("\0") if name]


def main() -> int:
    findings = []
    for path in tracked_files():
        if path.suffix.lower() not in TEXT_SUFFIXES:
            continue
        # This scanner quotes the patterns it looks for, so it would always match itself.
        if path.name == "scan-secrets.py":
            continue
        try:
            content = path.read_text(encoding="utf-8")
        except (OSError, UnicodeDecodeError):
            continue
        for number, line in enumerate(content.splitlines(), start=1):
            if ALLOWED.search(line):
                continue
            for name, pattern, why in PATTERNS:
                if pattern.search(line):
                    findings.append((path, number, name, why, line.strip()[:120]))

    if not findings:
        print(f"scan-secrets: clean ({len(tracked_files())} tracked files)")
        return 0

    print("scan-secrets: possible credentials in TRACKED files\n", file=sys.stderr)
    for path, number, name, why, excerpt in findings:
        print(f"  {path}:{number}  [{name}]", file=sys.stderr)
        print(f"      {why}", file=sys.stderr)
        print(f"      {excerpt}", file=sys.stderr)
        print(file=sys.stderr)
    print(
        "If this is a false positive, make the line unambiguous rather than widening ALLOWED - "
        "an allowlist that grows is how a scanner stops working. See docs/RELEASE.md section 4c.",
        file=sys.stderr,
    )
    return 1


if __name__ == "__main__":
    sys.exit(main())
