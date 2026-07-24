import argparse
import secrets

from yo_db import YoDatabase
from yo_server import DEFAULT_DATABASE, _hash_client_key


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Provision a Yo broadcast API client"
    )
    parser.add_argument("--client-id", required=True)
    parser.add_argument("--database", default=str(DEFAULT_DATABASE))
    parser.add_argument("--subscribe", action="append", default=[])
    arguments = parser.parse_args()

    raw_key = secrets.token_urlsafe(32)
    database = YoDatabase(arguments.database)
    database.initialize()
    database.upsert_api_client(
        arguments.client_id,
        _hash_client_key(raw_key),
    )
    for username in arguments.subscribe:
        database.add_subscription(arguments.client_id, username)
    print(raw_key)


if __name__ == "__main__":
    main()
