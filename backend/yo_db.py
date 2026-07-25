import os
import sqlite3
import time
from typing import List, Optional, Tuple, Union


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
                CREATE TABLE IF NOT EXISTS photos(
                    message_id TEXT PRIMARY KEY,
                    mime_type TEXT NOT NULL,
                    data_base64 TEXT NOT NULL,
                    created_at INTEGER
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

    def list_friends(self, requester: str) -> List[str]:
        with self._connect() as connection:
            rows = connection.execute(
                """
                SELECT username
                FROM devices
                WHERE username <> ?
                ORDER BY username
                """,
                (requester,),
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
                    created_at
                )
                VALUES (?, ?, ?, ?)
                ON CONFLICT(message_id) DO UPDATE SET
                    mime_type = excluded.mime_type,
                    data_base64 = excluded.data_base64,
                    created_at = excluded.created_at
                """,
                (message_id, mime_type, data_base64, timestamp),
            )

    def get_photo(self, message_id: str) -> Optional[Tuple[str, str]]:
        with self._connect() as connection:
            row = connection.execute(
                """
                SELECT mime_type, data_base64
                FROM photos
                WHERE message_id = ?
                """,
                (message_id,),
            ).fetchone()
        return None if row is None else (row[0], row[1])

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
                (client_id, username, timestamp),
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

    def _connect(self) -> sqlite3.Connection:
        return sqlite3.connect(self.path, timeout=5)
