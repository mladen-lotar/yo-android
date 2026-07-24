import os
import sqlite3
import time
from typing import List, Optional, Union


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

    def _connect(self) -> sqlite3.Connection:
        return sqlite3.connect(self.path, timeout=5)
