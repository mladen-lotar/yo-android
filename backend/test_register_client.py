import hashlib
import io
import os
import tempfile
import unittest
from contextlib import redirect_stdout
from unittest.mock import patch

from register_client import main
from yo_db import YoDatabase


class RegisterClientTest(unittest.TestCase):
    def setUp(self):
        database_file = tempfile.NamedTemporaryFile(delete=False)
        database_file.close()
        self.database_path = database_file.name

    def tearDown(self):
        os.unlink(self.database_path)

    def test_register_writes_hashed_key_and_prints_raw_key_once(self):
        with patch(
            "register_client.secrets.token_urlsafe",
            return_value="raw-client-key",
        ):
            output = self.run_register_client("--client-id", "fedex")

        printed_keys = output.splitlines()
        self.assertEqual(["raw-client-key"], printed_keys)
        stored_hash = YoDatabase(self.database_path).get_api_key_hash("fedex")
        self.assertNotEqual(printed_keys[0], stored_hash)
        self.assertEqual(
            hashlib.sha256(printed_keys[0].encode("utf-8")).hexdigest(),
            stored_hash,
        )

    def test_subscribe_flag_seeds_subscriptions(self):
        database = YoDatabase(self.database_path)
        database.initialize()
        # Accounts - and therefore devices, which /v1/register keys off the token's username -
        # are canonically uppercase, so that is what the join has to match.
        database.upsert_device("ALICE", "alice-token")
        database.upsert_device("BOB", "bob-token")

        self.run_register_client(
            "--client-id",
            "fedex",
            "--subscribe",
            "ALICE",
            "--subscribe",
            "BOB",
        )

        self.assertEqual(
            ["alice-token", "bob-token"],
            database.list_subscriber_tokens("fedex"),
        )

    def test_subscribe_normalises_a_username_typed_in_lower_case(self):
        """Operators type `--subscribe alice`; without normalisation the join finds nothing
        and the broadcast silently reports zero subscribers."""
        database = YoDatabase(self.database_path)
        database.initialize()
        database.upsert_device("ALICE", "alice-token")

        self.run_register_client(
            "--client-id",
            "fedex",
            "--subscribe",
            "  alice ",
        )

        self.assertEqual(
            ["alice-token"],
            database.list_subscriber_tokens("fedex"),
        )

    def run_register_client(self, *arguments):
        output = io.StringIO()
        command = [
            "register_client.py",
            "--database",
            self.database_path,
            *arguments,
        ]
        with patch("sys.argv", command):
            with redirect_stdout(output):
                main()
        return output.getvalue()


if __name__ == "__main__":
    unittest.main()
