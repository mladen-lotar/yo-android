import os
import sqlite3
import time
from contextlib import contextmanager
from typing import Iterator, List, Optional, Union


PathValue = Union[str, os.PathLike]


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

    def delete_account(self, username: str) -> bool:
        """Erase an account and everything keyed to it. Returns False if it did not exist.

        Google Play requires an app that creates accounts to be able to delete them, and a
        deletion that leaves rows behind is not a deletion. Every table that names a user is
        cleared here, in one transaction, so a partial failure cannot leave an account that is
        half-gone - unreachable but still holding its username.

        `friendships` and `blocks` are cleared in BOTH directions. Clearing only the rows this
        user owns would leave them in other people's friend lists: a name that can be tapped,
        that answers `recipient_not_found`, and that cannot be removed by its owner because the
        account behind it no longer exists.
        """
        with self._connect() as connection:
            existed = connection.execute(
                "SELECT 1 FROM accounts WHERE username = ?",
                (username,),
            ).fetchone()
            if existed is None:
                return False
            connection.execute("DELETE FROM accounts WHERE username = ?", (username,))
            connection.execute("DELETE FROM tokens WHERE username = ?", (username,))
            connection.execute("DELETE FROM identities WHERE username = ?", (username,))
            connection.execute("DELETE FROM devices WHERE username = ?", (username,))
            connection.execute("DELETE FROM subscriptions WHERE username = ?", (username,))
            connection.execute(
                "DELETE FROM friendships WHERE owner = ? OR friend = ?",
                (username, username),
            )
            connection.execute(
                "DELETE FROM blocks WHERE owner = ? OR blocked = ?",
                (username, username),
            )
        return True

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
    ) -> bool:
        """Issue a token, but only to an account that still exists. False if it does not.

        The existence check is INSIDE the insert, and that is the entire point. Sign-in reads the
        password hash, spends ~300 ms deriving PBKDF2, and only then writes the token - so a
        DELETE /v1/account arriving in that window ran its own `DELETE FROM tokens` before this
        row existed, and the row landed afterwards, naming an account that was gone. Measured:
        with the delete fired 0, 5, 15 and 30 ms after the login, a live token survived 10 times
        out of 10.

        Two things made that worse than an orphan row. Nothing prunes it, since tokens have no
        expiry, and `username_for_token` resolves purely on the tokens table - it never asks
        whether the account is still there - so the holder kept a working session on a deleted
        account. And a deleted username is immediately free to claim again, so the stale token
        becomes a live session belonging to whoever registers that name next.

        It needs no attacker: deleting an account on one phone while another is signing in is
        enough. SQLite serialises write transactions, so after this change the two possible
        orders both end correctly - the insert finds no account and does nothing, or it lands
        first and the delete reaps it.
        """
        timestamp = int(time.time()) if created_at is None else created_at
        with self._connect() as connection:
            cursor = connection.execute(
                """
                INSERT OR REPLACE INTO tokens(token_hash, username, created_at)
                SELECT ?, ?, ?
                WHERE EXISTS (SELECT 1 FROM accounts WHERE username = ?)
                """,
                (token_hash, username, timestamp, username),
            )
        return cursor.rowcount == 1

    def delete_expired_tokens(self, older_than: int) -> int:
        """Drop every token issued before `older_than`. Returns how many went.

        Swept rather than only filtered on read, so the table does not grow without bound - it
        never had any pruning at all, and nothing else deletes a token except an explicit logout
        or account deletion.
        """
        with self._connect() as connection:
            cursor = connection.execute(
                "DELETE FROM tokens WHERE created_at IS NULL OR created_at < ?",
                (older_than,),
            )
        return cursor.rowcount

    def username_for_token(
        self,
        token_hash: str,
        issued_after: Optional[int] = None,
    ) -> Optional[str]:
        """Resolve a bearer token, but only to an account that still exists.

        The JOIN is belt to store_token's braces, and it is what makes the property hold for rows
        that are already in the database rather than only for rows written from now on. A token
        row has no referential tie to `accounts` - there is no foreign key and nothing prunes it -
        so any token that outlived its account, however it got there, authenticated happily. That
        matters because a deleted username is immediately free to claim again: the stale session
        does not merely linger, it silently transfers to whoever registers that name next.

        `issued_after` is the expiry, and it is the answer to "a stolen session can never be
        revoked" (G10, issue #37). A token used to be valid until that exact session called
        `DELETE /v1/session`, so an exfiltrated one was good forever and its holder had no reason
        ever to log out. Encrypting the token at rest - the remedy G8 originally named - raises
        the cost of a theft; expiring it bounds the damage of a theft that already succeeded,
        which is the half that helps.

        A NULL `created_at` counts as EXPIRED rather than as ancient-but-valid. The column has
        always been written, so this can only fire on a row that arrived some other way, and
        "unknown age" is not a reason to trust something forever.
        """
        with self._connect() as connection:
            row = connection.execute(
                """
                SELECT tokens.username
                FROM tokens
                JOIN accounts ON accounts.username = tokens.username
                WHERE tokens.token_hash = ?
                  AND (
                        ? IS NULL
                     OR (tokens.created_at IS NOT NULL AND tokens.created_at >= ?)
                  )
                """,
                (token_hash, issued_after, issued_after),
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

    def delete_device(self, username: str) -> None:
        """Forget where this account's Yos are delivered.

        Called on logout, because signing out has to mean the handset stops receiving the
        account's pushes. It did not: `DELETE /v1/session` revoked the presented token and left
        the `devices` row untouched, so every Yo sent to that account kept arriving on the phone
        of whoever had signed out - with the sender's name, and any attached location, link or
        hashtag. On a phone that was sold, handed on, or shared, that is one person's messages
        being delivered to another, and there was no API that stopped it short of deleting the
        account outright: /v1/register refuses a blank token, so the signed-out user could not
        even overwrite their own row with junk.

        `subscriptions` joins `devices` on username too, so a stale row also kept any broadcast
        the departed account was subscribed to ringing on the new holder's handset.
        """
        with self._connect() as connection:
            connection.execute(
                "DELETE FROM devices WHERE username = ?",
                (username,),
            )

    def get_fcm_token(self, username: str) -> Optional[str]:
        with self._connect() as connection:
            row = connection.execute(
                "SELECT fcm_token FROM devices WHERE username = ?",
                (username,),
            ).fetchone()
        return None if row is None else row[0]

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
