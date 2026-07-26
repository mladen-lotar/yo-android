import os
import sqlite3
import time
from contextlib import contextmanager
from typing import Iterator, List, Optional, Tuple, Union


PathValue = Union[str, os.PathLike]

# (mime_type, data_base64, owner, recipient)
PhotoRecord = Tuple[str, str, Optional[str], Optional[str]]


class YoDatabase:
    def __init__(self, path: PathValue):
        self.path = os.fspath(path)

    def initialize(self) -> None:
        with self._connect() as connection:
            connection.execute(
                """
                CREATE TABLE IF NOT EXISTS devices(
                    username TEXT PRIMARY KEY,
                    fcm_token TEXT NOT NULL,
                    updated_at INTEGER
                )
                """
            )
            connection.execute(
                """
                CREATE TABLE IF NOT EXISTS api_clients(
                    client_id TEXT PRIMARY KEY,
                    api_key_hash TEXT NOT NULL,
                    created_at INTEGER
                )
                """
            )
            connection.execute(
                """
                CREATE TABLE IF NOT EXISTS subscriptions(
                    client_id TEXT NOT NULL,
                    username TEXT NOT NULL,
                    created_at INTEGER,
                    PRIMARY KEY (client_id, username)
                )
                """
            )
            connection.execute(
                """
                CREATE TABLE IF NOT EXISTS photos(
                    message_id TEXT PRIMARY KEY,
                    mime_type TEXT NOT NULL,
                    data_base64 TEXT NOT NULL,
                    created_at INTEGER
                )
                """
            )
            # Photos predate per-user credentials, so the ownership columns are added rather
            # than declared. CREATE TABLE IF NOT EXISTS never alters an existing table.
            self._add_column_if_missing(connection, "photos", "owner", "TEXT")
            self._add_column_if_missing(connection, "photos", "recipient", "TEXT")
            connection.execute(
                """
                CREATE TABLE IF NOT EXISTS accounts(
                    username TEXT PRIMARY KEY,
                    password_hash TEXT NOT NULL,
                    created_at INTEGER
                )
                """
            )
            connection.execute(
                """
                CREATE TABLE IF NOT EXISTS tokens(
                    token_hash TEXT PRIMARY KEY,
                    username TEXT NOT NULL,
                    created_at INTEGER
                )
                """
            )
            connection.execute(
                "CREATE INDEX IF NOT EXISTS idx_tokens_username ON tokens(username)"
            )
            connection.execute(
                """
                CREATE TABLE IF NOT EXISTS identities(
                    provider TEXT NOT NULL,
                    subject TEXT NOT NULL,
                    username TEXT NOT NULL,
                    created_at INTEGER,
                    PRIMARY KEY (provider, subject)
                )
                """
            )
            connection.execute(
                "CREATE INDEX IF NOT EXISTS idx_identities_username"
                " ON identities(username)"
            )
            connection.execute(
                """
                CREATE TABLE IF NOT EXISTS friendships(
                    owner TEXT NOT NULL,
                    friend TEXT NOT NULL,
                    created_at INTEGER,
                    PRIMARY KEY (owner, friend)
                )
                """
            )
            connection.execute(
                """
                CREATE TABLE IF NOT EXISTS blocks(
                    owner TEXT NOT NULL,
                    blocked TEXT NOT NULL,
                    created_at INTEGER,
                    PRIMARY KEY (owner, blocked)
                )
                """
            )

    def upsert_device(
        self,
        username: str,
        fcm_token: str,
        updated_at: Optional[int] = None,
    ) -> None:
        timestamp = int(time.time()) if updated_at is None else updated_at
        with self._connect() as connection:
            connection.execute(
                """
                INSERT INTO devices(username, fcm_token, updated_at)
                VALUES (?, ?, ?)
                ON CONFLICT(username) DO UPDATE SET
                    fcm_token = excluded.fcm_token,
                    updated_at = excluded.updated_at
                """,
                (username, fcm_token, timestamp),
            )

    def create_account(
        self,
        username: str,
        password_hash: str,
        created_at: Optional[int] = None,
    ) -> bool:
        """Insert a new account. Returns False if the username is already taken."""
        timestamp = int(time.time()) if created_at is None else created_at
        with self._connect() as connection:
            cursor = connection.execute(
                """
                INSERT OR IGNORE INTO accounts(username, password_hash, created_at)
                VALUES (?, ?, ?)
                """,
                (username, password_hash, timestamp),
            )
        return cursor.rowcount == 1

    def get_password_hash(self, username: str) -> Optional[str]:
        with self._connect() as connection:
            row = connection.execute(
                "SELECT password_hash FROM accounts WHERE username = ?",
                (username,),
            ).fetchone()
        return None if row is None else row[0]

    def account_exists(self, username: str) -> bool:
        with self._connect() as connection:
            row = connection.execute(
                "SELECT 1 FROM accounts WHERE username = ?",
                (username,),
            ).fetchone()
        return row is not None

    def username_for_identity(self, provider: str, subject: str) -> Optional[str]:
        """The account an external identity signs in to, or None if it has never signed in."""
        with self._connect() as connection:
            row = connection.execute(
                "SELECT username FROM identities WHERE provider = ? AND subject = ?",
                (provider, subject),
            ).fetchone()
        return None if row is None else row[0]

    def create_linked_account(
        self,
        username: str,
        provider: str,
        subject: str,
        password_hash: str,
        created_at: Optional[int] = None,
    ) -> bool:
        """Create an account and link it to an external identity, both or neither.

        One transaction on purpose. Two separate writes could leave an account that exists and
        is linked to nothing, and since the username is then taken its owner could never claim
        it - the failure would be permanent and invisible.

        Returns False when the username is already taken or the identity is already linked.
        """
        timestamp = int(time.time()) if created_at is None else created_at
        with self._connect() as connection:
            cursor = connection.execute(
                """
                INSERT OR IGNORE INTO accounts(username, password_hash, created_at)
                VALUES (?, ?, ?)
                """,
                (username, password_hash, timestamp),
            )
            if cursor.rowcount != 1:
                return False
            linked = connection.execute(
                """
                INSERT OR IGNORE INTO identities(provider, subject, username, created_at)
                VALUES (?, ?, ?, ?)
                """,
                (provider, subject, username, timestamp),
            )
            if linked.rowcount != 1:
                # The identity was claimed between the lookup and here. Undo the account so the
                # username stays free; `with connection` would otherwise commit it.
                connection.execute(
                    "DELETE FROM accounts WHERE username = ?",
                    (username,),
                )
                return False
        return True

    def store_token(
        self,
        token_hash: str,
        username: str,
        created_at: Optional[int] = None,
    ) -> None:
        timestamp = int(time.time()) if created_at is None else created_at
        with self._connect() as connection:
            connection.execute(
                """
                INSERT OR REPLACE INTO tokens(token_hash, username, created_at)
                VALUES (?, ?, ?)
                """,
                (token_hash, username, timestamp),
            )

    def username_for_token(self, token_hash: str) -> Optional[str]:
        with self._connect() as connection:
            row = connection.execute(
                "SELECT username FROM tokens WHERE token_hash = ?",
                (token_hash,),
            ).fetchone()
        return None if row is None else row[0]

    def delete_token(self, token_hash: str) -> None:
        with self._connect() as connection:
            connection.execute(
                "DELETE FROM tokens WHERE token_hash = ?",
                (token_hash,),
            )

    def add_friend(
        self,
        owner: str,
        friend: str,
        created_at: Optional[int] = None,
    ) -> None:
        timestamp = int(time.time()) if created_at is None else created_at
        with self._connect() as connection:
            connection.execute(
                """
                INSERT OR IGNORE INTO friendships(owner, friend, created_at)
                VALUES (?, ?, ?)
                """,
                (owner, friend, timestamp),
            )

    def remove_friend(self, owner: str, friend: str) -> None:
        with self._connect() as connection:
            connection.execute(
                "DELETE FROM friendships WHERE owner = ? AND friend = ?",
                (owner, friend),
            )

    def list_friends(self, requester: str) -> List[str]:
        """The friends this user has added - never the whole user table (gap G5)."""
        with self._connect() as connection:
            rows = connection.execute(
                """
                SELECT friend
                FROM friendships
                WHERE owner = ?
                ORDER BY friend
                """,
                (requester,),
            ).fetchall()
        return [row[0] for row in rows]

    def block(
        self,
        owner: str,
        blocked: str,
        created_at: Optional[int] = None,
    ) -> None:
        timestamp = int(time.time()) if created_at is None else created_at
        with self._connect() as connection:
            connection.execute(
                """
                INSERT OR IGNORE INTO blocks(owner, blocked, created_at)
                VALUES (?, ?, ?)
                """,
                (owner, blocked, timestamp),
            )

    def unblock(self, owner: str, blocked: str) -> None:
        with self._connect() as connection:
            connection.execute(
                "DELETE FROM blocks WHERE owner = ? AND blocked = ?",
                (owner, blocked),
            )

    def is_blocked(self, owner: str, blocked: str) -> bool:
        """True when `owner` has blocked `blocked`, so `blocked` may not Yo them."""
        with self._connect() as connection:
            row = connection.execute(
                "SELECT 1 FROM blocks WHERE owner = ? AND blocked = ?",
                (owner, blocked),
            ).fetchone()
        return row is not None

    def list_blocked(self, owner: str) -> List[str]:
        with self._connect() as connection:
            rows = connection.execute(
                "SELECT blocked FROM blocks WHERE owner = ? ORDER BY blocked",
                (owner,),
            ).fetchall()
        return [row[0] for row in rows]

    def get_fcm_token(self, username: str) -> Optional[str]:
        with self._connect() as connection:
            row = connection.execute(
                "SELECT fcm_token FROM devices WHERE username = ?",
                (username,),
            ).fetchone()
        return None if row is None else row[0]

    def store_photo(
        self,
        message_id: str,
        mime_type: str,
        data_base64: str,
        owner: Optional[str] = None,
        recipient: Optional[str] = None,
        created_at: Optional[int] = None,
    ) -> None:
        timestamp = int(time.time()) if created_at is None else created_at
        with self._connect() as connection:
            connection.execute(
                """
                INSERT INTO photos(
                    message_id,
                    mime_type,
                    data_base64,
                    owner,
                    recipient,
                    created_at
                )
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT(message_id) DO UPDATE SET
                    mime_type = excluded.mime_type,
                    data_base64 = excluded.data_base64,
                    owner = excluded.owner,
                    recipient = excluded.recipient,
                    created_at = excluded.created_at
                """,
                (message_id, mime_type, data_base64, owner, recipient, timestamp),
            )

    def get_photo(self, message_id: str) -> Optional[PhotoRecord]:
        """Returns (mime_type, data_base64, owner, recipient); the last two may be None
        for photos stored before ownership was recorded."""
        with self._connect() as connection:
            row = connection.execute(
                """
                SELECT mime_type, data_base64, owner, recipient
                FROM photos
                WHERE message_id = ?
                """,
                (message_id,),
            ).fetchone()
        return None if row is None else (row[0], row[1], row[2], row[3])

    @staticmethod
    def _add_column_if_missing(
        connection: sqlite3.Connection,
        table: str,
        column: str,
        declaration: str,
    ) -> None:
        existing = {
            row[1] for row in connection.execute(f"PRAGMA table_info({table})").fetchall()
        }
        if column not in existing:
            connection.execute(f"ALTER TABLE {table} ADD COLUMN {column} {declaration}")

    def upsert_api_client(
        self,
        client_id: str,
        api_key_hash: str,
        created_at: Optional[int] = None,
    ) -> None:
        timestamp = int(time.time()) if created_at is None else created_at
        with self._connect() as connection:
            connection.execute(
                """
                INSERT INTO api_clients(client_id, api_key_hash, created_at)
                VALUES (?, ?, ?)
                ON CONFLICT(client_id) DO UPDATE SET
                    api_key_hash = excluded.api_key_hash
                """,
                (client_id, api_key_hash, timestamp),
            )

    def get_api_key_hash(self, client_id: str) -> Optional[str]:
        with self._connect() as connection:
            row = connection.execute(
                "SELECT api_key_hash FROM api_clients WHERE client_id = ?",
                (client_id,),
            ).fetchone()
        return None if row is None else row[0]

    def add_subscription(
        self,
        client_id: str,
        username: str,
        created_at: Optional[int] = None,
    ) -> None:
        timestamp = int(time.time()) if created_at is None else created_at
        # Subscriptions join devices on username. Accounts are canonically uppercase, so a
        # subscription stored as typed ("alice") would join nothing and the broadcast would
        # silently report zero subscribers.
        with self._connect() as connection:
            connection.execute(
                """
                INSERT OR IGNORE INTO subscriptions(
                    client_id,
                    username,
                    created_at
                )
                VALUES (?, ?, ?)
                """,
                (client_id, username.strip().upper(), timestamp),
            )

    def list_subscriber_tokens(self, client_id: str) -> List[str]:
        with self._connect() as connection:
            rows = connection.execute(
                """
                SELECT devices.fcm_token
                FROM subscriptions
                JOIN devices
                    ON devices.username = subscriptions.username
                WHERE subscriptions.client_id = ?
                ORDER BY devices.username
                """,
                (client_id,),
            ).fetchall()
        return [row[0] for row in rows]

    @contextmanager
    def _connect(self) -> Iterator[sqlite3.Connection]:
        """Open a connection, commit or roll back, and always close it.

        `with sqlite3.connect(...)` only manages the transaction - it never closes the
        connection, so the bare form leaked one handle per query until the garbage collector
        happened to run. This server is threaded and long-lived, so it closes explicitly.
        """
        connection = sqlite3.connect(self.path, timeout=5)
        try:
            with connection:
                yield connection
        finally:
            connection.close()
