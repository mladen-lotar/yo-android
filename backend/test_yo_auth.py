import unittest

import yo_auth


# The real cost is DEFAULT_ITERATIONS; these tests only care about the encoding and the
# comparison, so they pay the cheap cost and assert the default separately.
CHEAP_ITERATIONS = 1_000


class PasswordHashingTest(unittest.TestCase):
    def test_hash_then_verify_round_trips(self):
        encoded = yo_auth.hash_password("correct-horse", CHEAP_ITERATIONS)

        self.assertTrue(yo_auth.verify_password("correct-horse", encoded))

    def test_the_wrong_password_does_not_verify(self):
        encoded = yo_auth.hash_password("correct-horse", CHEAP_ITERATIONS)

        self.assertFalse(yo_auth.verify_password("correct-hors", encoded))
        self.assertFalse(yo_auth.verify_password("", encoded))

    def test_the_encoding_carries_the_algorithm_salt_and_cost(self):
        encoded = yo_auth.hash_password("correct-horse", CHEAP_ITERATIONS)

        algorithm, iterations, salt_hex, digest_hex = encoded.split("$")
        self.assertEqual(yo_auth.ALGORITHM, algorithm)
        self.assertEqual(str(CHEAP_ITERATIONS), iterations)
        self.assertEqual(32, len(salt_hex))
        self.assertEqual(64, len(digest_hex))

    def test_the_same_password_hashes_differently_every_time(self):
        """A per-hash salt is what stops one rainbow table covering the whole accounts table."""
        first = yo_auth.hash_password("correct-horse", CHEAP_ITERATIONS)
        second = yo_auth.hash_password("correct-horse", CHEAP_ITERATIONS)

        self.assertNotEqual(first, second)
        self.assertTrue(yo_auth.verify_password("correct-horse", first))
        self.assertTrue(yo_auth.verify_password("correct-horse", second))

    def test_a_stored_hash_from_an_older_cost_still_verifies(self):
        """The cost travels with the hash so it can be raised without locking anyone out."""
        encoded = yo_auth.hash_password("correct-horse", 500)

        self.assertTrue(yo_auth.verify_password("correct-horse", encoded))

    def test_a_corrupt_encoded_hash_returns_false_rather_than_raising(self):
        """verify_password is called on whatever the database holds; it must never 500."""
        corrupt = (
            None,
            "",
            "not-a-hash",
            "pbkdf2_sha256$1000",
            "pbkdf2_sha256$1000$notyhex$deadbeef",
            "pbkdf2_sha256$notanumber$aabb$ccdd",
            "pbkdf2_sha256$0$aabb$ccdd",
            "pbkdf2_sha256$-1$aabb$ccdd",
            "bcrypt$1000$aabb$ccdd",
            "pbkdf2_sha256$1000$aabb$ccdd$extra",
            12345,
        )
        for encoded in corrupt:
            with self.subTest(encoded=encoded):
                self.assertFalse(yo_auth.verify_password("correct-horse", encoded))

    def test_the_default_cost_is_not_quietly_weakened(self):
        self.assertGreaterEqual(yo_auth.DEFAULT_ITERATIONS, 600_000)


class UsernameRulesTest(unittest.TestCase):
    def test_normalisation_uppercases_and_trims(self):
        self.assertEqual("ALICE", yo_auth.normalize_username("  alice "))
        self.assertEqual("ALICE", yo_auth.normalize_username("Alice"))
        self.assertEqual("ALICE_1", yo_auth.normalize_username("alice_1"))

    def test_validate_returns_the_canonical_form(self):
        for raw, expected in (
            ("alice", "ALICE"),
            (" Bob ", "BOB"),
            ("user_42", "USER_42"),
            ("ab", "AB"),
            ("A" * 32, "A" * 32),
        ):
            with self.subTest(raw=raw):
                self.assertEqual(expected, yo_auth.validate_username(raw))

    def test_validate_rejects_out_of_spec_usernames(self):
        for raw in ("", " ", "a", "A", "A" * 33, "has space", "no-dash", "email@x", "é"):
            with self.subTest(raw=raw):
                with self.assertRaises(yo_auth.CredentialError):
                    yo_auth.validate_username(raw)


class PasswordRulesTest(unittest.TestCase):
    def test_validate_accepts_passwords_inside_the_bounds(self):
        for password in ("a" * yo_auth.MIN_PASSWORD_LENGTH, "a" * yo_auth.MAX_PASSWORD_LENGTH):
            with self.subTest(length=len(password)):
                self.assertEqual(password, yo_auth.validate_password(password))

    def test_validate_rejects_short_long_and_non_string_passwords(self):
        for password in (
            "",
            "a" * (yo_auth.MIN_PASSWORD_LENGTH - 1),
            "a" * (yo_auth.MAX_PASSWORD_LENGTH + 1),
            None,
            12345678,
            ["a" * 8],
        ):
            with self.subTest(password=password):
                with self.assertRaises(yo_auth.CredentialError):
                    yo_auth.validate_password(password)


class TokenTest(unittest.TestCase):
    def test_new_token_is_unguessable_and_unique(self):
        tokens = {yo_auth.new_token() for _ in range(50)}

        self.assertEqual(50, len(tokens))
        for token in tokens:
            self.assertGreaterEqual(len(token), 32)

    def test_hash_token_is_stable_and_never_the_token_itself(self):
        token = yo_auth.new_token()

        digest = yo_auth.hash_token(token)
        self.assertEqual(digest, yo_auth.hash_token(token))
        self.assertNotEqual(token, digest)
        self.assertEqual(64, len(digest))
        self.assertNotEqual(digest, yo_auth.hash_token(yo_auth.new_token()))


if __name__ == "__main__":
    unittest.main()
