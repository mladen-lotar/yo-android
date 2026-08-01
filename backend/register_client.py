import argparse
import secrets
import sys

from yo_db import YoDatabase
from yo_server import CLIENT_ID_PATTERN, DEFAULT_DATABASE, _hash_client_key


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Provision a Yo broadcast API client"
    )
    parser.add_argument("--client-id", required=True)
    parser.add_argument("--database", default=str(DEFAULT_DATABASE))
    parser.add_argument("--subscribe", action="append", default=[])
    arguments = parser.parse_args()

    # Stored exactly as typed, only trimmed: the caller has to send this id back in the
    # X-Yo-Client-Id header, so normalising it here would silently break every request they make.
    # A client id is broadcast to every subscriber AS THE SENDER of a Yo, landing in the same
    # position a username lands in - so it is validated for the same reason, and refusing it here
    # means a forging id can never enter the database in the first place. The server re-checks at
    # use, so a row that arrives some other way cannot reach a notification either.
    client_id = arguments.client_id.strip()
    if not CLIENT_ID_PATTERN.fullmatch(client_id):
        parser.error(
            "client id must be 2-32 characters of A-Z, a-z, 0-9, underscore or hyphen; it is "
            "shown to every subscriber as the sender of the Yo"
        )

    raw_key = secrets.token_urlsafe(32)
    database = YoDatabase(arguments.database)
    database.initialize()
    database.upsert_api_client(
        client_id,
        _hash_client_key(raw_key),
    )
    for username in arguments.subscribe:
        database.add_subscription(client_id, username)
    # stdout stays EXACTLY the key and nothing else - it is meant to be piped or captured, and a
    # confirmation line here would silently become part of whatever a caller stores as the secret.
    print(f"registered client {client_id!r}", file=sys.stderr)
    print(raw_key)


if __name__ == "__main__":
    main()
