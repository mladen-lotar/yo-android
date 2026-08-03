"""Tests for tools/scan-secrets.py.

The scanner's filename has a hyphen, so it cannot be `import`-ed normally; it is loaded here via
importlib from its file path. Run with:

    python3 -m unittest tools.test_scan_secrets -v

This file's own content is excluded from the scanner's file walk (see SELF_EXCLUDE_PATHS in
scan-secrets.py) precisely so the deliberately secret-shaped strings used as positive controls
below don't trip the scanner once this file is tracked.
"""

import importlib.util
import io
import subprocess
import sys
import tempfile
import unittest
from contextlib import redirect_stderr, redirect_stdout
from pathlib import Path

_TOOLS_DIR = Path(__file__).resolve().parent
_REPO_ROOT = _TOOLS_DIR.parent
_SCANNER_PATH = _TOOLS_DIR / "scan-secrets.py"

_spec = importlib.util.spec_from_file_location("scan_secrets", _SCANNER_PATH)
scanner = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(scanner)


def caught(line: str) -> bool:
    """True if LINE produces at least one finding."""
    return bool(scanner.line_findings(line))


class AllowlistIsGone(unittest.TestCase):
    """The redesign's central claim: there is no line-level allowlist left to reopen the hole."""

    def test_module_has_no_allowed_regex(self):
        self.assertFalse(
            hasattr(scanner, "ALLOWED"),
            "a module-level ALLOWED regex means someone can widen it back into the exact bug "
            "that hid the real leak - the fix deletes it, it does not narrow it",
        )


class ReassuringSentenceCannotSuppressAFinding(unittest.TestCase):
    """Pins the actual incident. Before the fix, appending any of these sentences to a caught
    line made it SKIPPED. After the fix, the judgement is about the captured value, not the
    prose around it, so none of them can change the verdict.
    """

    REASSURING_PHRASES = [
        "<from the operator's password manager - NOT stored in this repository>",
        "(not stored in this repository)",
        "not recorded here",
        "must be at least 8 characters",
        "this is a placeholder, replace before use",
        "redacted for security",
        "see the password manager for the real value",
        "DO NOT COMMIT THE REAL VALUE",
        "rotated after the incident, this one is dead",
        "for documentation purposes only",
        "never stored in this repository",
        "ask an operator for the current value",
    ]

    def test_bare_secret_line_is_caught(self):
        self.assertTrue(caught("Password: Xk9mQzL2Pw"))

    def test_appending_any_reassuring_phrase_does_not_change_the_verdict(self):
        secret_line = "Password: Xk9mQzL2Pw"
        self.assertTrue(caught(secret_line), "precondition: the bare line must be caught")
        for phrase in self.REASSURING_PHRASES:
            with self.subTest(phrase=phrase):
                self.assertTrue(
                    caught(f"{secret_line}  {phrase}"),
                    f"appending {phrase!r} suppressed a finding it has no business suppressing",
                )

    def test_the_exact_incident_shape_two_lines_from_the_measured_writeup(self):
        # From the WHY section of scan-secrets.py: these two variants of the real leaked line
        # were SKIPPED by the old allowlist-based scanner. Both must be caught now.
        self.assertTrue(
            caught(
                "Password: Xk9mQzL2Pw  <from the operator's password manager - "
                "NOT stored in this repository>"
            )
        )
        self.assertTrue(
            caught("Password: Xk9mQzL2Pw (not stored in this repository)")
        )


class CaseInsensitivityIsScopedToTheKeywordOnly(unittest.TestCase):
    """The old regex applied (?i) to the whole pattern, which made its documented mixed-case
    value requirement inert. There is no case requirement on the value anymore - prove the
    keyword is found regardless of its casing, and that an all-lowercase (or all-uppercase)
    secret-shaped value is caught exactly the same as a mixed-case one.
    """

    def test_keyword_matches_regardless_of_case(self):
        for keyword in ("password", "Password", "PASSWORD", "PassWord", "PWD", "pwd", "Passwd"):
            with self.subTest(keyword=keyword):
                self.assertTrue(caught(f"{keyword}: gener8edvalue123"))

    def test_value_case_does_not_gate_the_verdict(self):
        self.assertTrue(caught("password: gener8edvalue123"))
        self.assertTrue(caught("password: GENER8EDVALUE123"))
        self.assertTrue(caught("password: Gener8EdValue123"))


class KeywordVisibleInsideLargerIdentifiers(unittest.TestCase):
    """`\\b` never matches after `_`, so `\\bpassword\\b` cannot match inside
    YO_KEYSTORE_PASSWORD or DB_PASSWORD. Both are real identifier shapes in this repo
    (app/build.gradle.kts, docs/RELEASE.md).
    """

    def test_screaming_snake_identifier_with_password_in_the_middle(self):
        self.assertTrue(caught("YO_KEYSTORE_PASSWORD=Tr0ub4dor2026xyz"))

    def test_camel_case_identifier_with_password_at_the_end(self):
        self.assertTrue(caught("yoKeystorePassword=Tr0ub4dor2026xyz"))

    def test_db_password_prefix_form(self):
        self.assertTrue(caught("DB_PASSWORD=Sup3rSecretValue99"))

    def test_shell_deref_pwd_is_guarded(self):
        # $PWD / ${PWD} is a shell working-directory reference, not a credential.
        self.assertFalse(caught("cd $PWD/backend && ls Xk9mQzL2Pw123"))
        self.assertFalse(caught("echo ${PWD}"))


class MarkdownTablePipeSeparator(unittest.TestCase):
    """Requiring `[:=]` misses a markdown table row - and the file that leaked
    (store/listing.md) is markdown.
    """

    def test_pipe_separator_is_accepted(self):
        self.assertTrue(caught("| password | Str0ngGener8edPass99 | demo account |"))

    def test_capture_is_bounded_to_the_adjacent_cell(self):
        # The real docs/RELEASE.md row this is modeled on: the secret-shaped-looking token
        # (PBKDF2-HMAC-SHA256, which has letters and digits and is 10+ chars) sits two cells
        # away from the "Password" label, separated by unrelated cells. It must not be captured
        # as if it were this row's value.
        line = "| Password | Yes | No | Authentication | Stored as PBKDF2-HMAC-SHA256, never in clear |"
        self.assertFalse(
            caught(line),
            "a later, unrelated table cell was captured as the value of an earlier keyword cell",
        )


class ValueCharacterClassIncludesDotAndSlash(unittest.TestCase):
    """The old value character class, [A-Za-z0-9!@#$%^&*_\\-+=], excluded `.` and `/` - so a
    STRONGER generated password (using those characters) was LESS likely to be caught.
    """

    def test_value_with_a_dot_is_caught(self):
        self.assertTrue(caught("password: Genr8ed.P4ss"))

    def test_value_with_a_slash_is_caught(self):
        self.assertTrue(caught("password: Pa55/Word2026"))

    def test_value_with_both_is_caught(self):
        self.assertTrue(caught("password = Str0ng.Pass/word99"))


class ExtensionlessTrackedFilesAreScanned(unittest.TestCase):
    """path.suffix skips extensionless files by construction, so a tracked .env or Dockerfile
    (or, in this very repository, `gradlew`) was invisible regardless of content. Proves the
    replacement (binary skip-set + NUL sniff) actually reads such a file rather than skipping it
    on sight.
    """

    def test_gradlew_is_a_real_tracked_extensionless_file(self):
        gradlew = _REPO_ROOT / "gradlew"
        self.assertTrue(gradlew.exists(), "sanity check: this repo really does track gradlew")
        self.assertEqual("", gradlew.suffix, "sanity check: gradlew really has no suffix")

    def test_an_extensionless_file_with_a_real_secret_is_scanned_not_skipped(self):
        with tempfile.TemporaryDirectory() as tmp:
            repo = Path(tmp)
            subprocess.run(["git", "init", "-q"], cwd=repo, check=True)
            subprocess.run(
                ["git", "config", "user.email", "test@example.invalid"], cwd=repo, check=True
            )
            subprocess.run(["git", "config", "user.name", "Test"], cwd=repo, check=True)
            (repo / "Dockerfile").write_text("ENV DB_PASSWORD=Sup3rSecretValue99\n")
            subprocess.run(["git", "add", "Dockerfile"], cwd=repo, check=True)
            subprocess.run(["git", "commit", "-q", "-m", "init"], cwd=repo, check=True)

            result = subprocess.run(
                [sys.executable, str(_SCANNER_PATH)],
                cwd=repo,
                capture_output=True,
                text=True,
            )
            self.assertEqual(1, result.returncode, result.stdout + result.stderr)
            self.assertIn("Dockerfile", result.stdout + result.stderr)

    def test_a_true_binary_file_with_no_suffix_is_not_decoded(self):
        with tempfile.TemporaryDirectory() as tmp:
            repo = Path(tmp)
            subprocess.run(["git", "init", "-q"], cwd=repo, check=True)
            subprocess.run(
                ["git", "config", "user.email", "test@example.invalid"], cwd=repo, check=True
            )
            subprocess.run(["git", "config", "user.name", "Test"], cwd=repo, check=True)
            (repo / "some-binary").write_bytes(b"\x00\x01\x02binarygarbage\xffPASSWORD=xx")
            subprocess.run(["git", "add", "some-binary"], cwd=repo, check=True)
            subprocess.run(["git", "commit", "-q", "-m", "init"], cwd=repo, check=True)

            result = subprocess.run(
                [sys.executable, str(_SCANNER_PATH)],
                cwd=repo,
                capture_output=True,
                text=True,
            )
            self.assertEqual(0, result.returncode, result.stdout + result.stderr)


class NegativeControlsFromTheRealRepo(unittest.TestCase):
    """Every line here is copied verbatim from a tracked file, not invented. None of them should
    ever produce a finding.
    """

    def test_the_current_fixed_listing_md_line_is_still_clean(self):
        line = "Password: <from the operator's password manager - NOT stored in this repository>"
        self.assertFalse(caught(line))

    def test_password_equals_password_is_a_variable_reference_not_a_secret(self):
        self.assertFalse(caught("password=password"))

    def test_test_password_constant_assignment(self):
        self.assertFalse(caught('TEST_PASSWORD = "correct-horse-battery"'))

    def test_test_password_used_as_a_kwarg_value(self):
        self.assertFalse(caught('signup(self, username, password=TEST_PASSWORD)'))

    def test_auth_fixture_some_attempted_password(self):
        self.assertFalse(
            caught(
                '            result = yo_auth.verify_password(\n'
                '                "some-attempted-password",'
            )
        )

    def test_auth_fixture_the_real_password_hash_password_call(self):
        self.assertFalse(
            caught('real_hash = yo_auth.hash_password("the-real-password", CHEAP_ITERATIONS)')
        )

    def test_auth_fixture_correct_horse_battery(self):
        self.assertFalse(
            caught('        encoded = yo_auth.hash_password("correct-horse", CHEAP_ITERATIONS)')
        )

    def test_docs_release_md_pbkdf2_row_is_clean(self):
        line = (
            "| Password | Yes | No | Authentication | Stored as PBKDF2-HMAC-SHA256, "
            "never in clear |"
        )
        self.assertFalse(caught(line))

    def test_min_password_length_constant(self):
        self.assertFalse(caught("MIN_PASSWORD_LENGTH = 8"))

    def test_keystore_password_gradle_optional_setting(self):
        self.assertFalse(
            caught(
                'val keystorePassword = optionalSetting("yoKeystorePassword", '
                '"YO_KEYSTORE_PASSWORD")'
            )
        )

    def test_release_md_keystore_password_table_row(self):
        self.assertFalse(
            caught("| `yoKeystorePassword` | `YO_KEYSTORE_PASSWORD` | Keystore password |")
        )

    def test_backend_json_dict_password_kwarg(self):
        self.assertFalse(
            caught('            {"username": "ALICE", "password": "correct-horse-1"},')
        )

    def test_compose_password_visibility_toggle(self):
        self.assertFalse(caught("                    isPassword = true,"))
        self.assertFalse(caught("                    onValueChange = { password = it },"))


class ExitCodeReflectsTheVerdict(unittest.TestCase):
    """An empty pass must not read as green for the wrong reason, and a real hit must not read as
    clean. Runs the actual script as a subprocess against two throwaway git repos so this checks
    the real `sys.exit(main())` path, not a reimplementation of it.
    """

    def _run_in_repo(self, files: dict) -> subprocess.CompletedProcess:
        with tempfile.TemporaryDirectory() as tmp:
            repo = Path(tmp)
            subprocess.run(["git", "init", "-q"], cwd=repo, check=True)
            subprocess.run(
                ["git", "config", "user.email", "test@example.invalid"], cwd=repo, check=True
            )
            subprocess.run(["git", "config", "user.name", "Test"], cwd=repo, check=True)
            for name, content in files.items():
                path = repo / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(content)
            subprocess.run(["git", "add", "."], cwd=repo, check=True)
            subprocess.run(["git", "commit", "-q", "-m", "init"], cwd=repo, check=True)
            return subprocess.run(
                [sys.executable, str(_SCANNER_PATH)],
                cwd=repo,
                capture_output=True,
                text=True,
            )

    def test_clean_repo_exits_zero(self):
        result = self._run_in_repo({"README.md": "Sign up with a username and a password.\n"})
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn("clean", result.stdout)

    def test_dirty_repo_exits_one(self):
        result = self._run_in_repo(
            {"store/listing.md": "Password: Xk9mQzL2Pw\n"}
        )
        self.assertEqual(1, result.returncode, result.stdout + result.stderr)
        self.assertIn("store/listing.md", result.stdout + result.stderr)


class AntiNoiseRegressionGuard(unittest.TestCase):
    """The anti-goal: a scanner that cries wolf gets an allowlist, then gets skipped, then gets
    deleted. This runs the rewrite over the REAL tracked files in this repository (not a fixture)
    and asserts zero findings and exit 0 - if a future change makes this scanner noisy on real
    content, this test is what catches it.
    """

    def test_real_repo_scan_is_clean_and_exits_zero(self):
        stdout = io.StringIO()
        stderr = io.StringIO()
        old_argv = sys.argv
        try:
            sys.argv = ["scan-secrets.py"]
            with redirect_stdout(stdout), redirect_stderr(stderr):
                exit_code = scanner.main()
        finally:
            sys.argv = old_argv

        self.assertEqual(
            0,
            exit_code,
            "scan-secrets.py found something in the real tracked files:\n" + stderr.getvalue(),
        )
        self.assertIn("clean", stdout.getvalue())


if __name__ == "__main__":
    unittest.main()
