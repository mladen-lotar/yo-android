import argparse
import hashlib
import hmac
import json
import os
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any, Dict, Optional
from urllib.parse import parse_qs, urlsplit

from fcm_client import FCMClient, FCMDeliveryError, FCMNotConfiguredError
from yo_db import YoDatabase


DEFAULT_PORT = 8790
DEFAULT_DATABASE = Path(__file__).with_name("yo.db")
MAX_BODY_BYTES = 1_000_000


def _hash_client_key(raw_key: str) -> str:
    return hashlib.sha256(raw_key.encode("utf-8")).hexdigest()


class BadRequestError(ValueError):
    pass


class YoHTTPServer(ThreadingHTTPServer):
    daemon_threads = True
    allow_reuse_address = True

    def __init__(
        self,
        server_address: tuple[str, int],
        database: YoDatabase,
        shared_key: str,
        fcm_client: Any,
    ):
        super().__init__(server_address, YoRequestHandler)
        self.database = database
        self.shared_key = shared_key
        self.fcm_client = fcm_client


class YoRequestHandler(BaseHTTPRequestHandler):
    server: YoHTTPServer

    def do_GET(self) -> None:
        parsed = urlsplit(self.path)
        if parsed.path == "/healthz":
            self._write_json(HTTPStatus.OK, {"ok": True})
            return
        if not self._authorize():
            return
        if parsed.path == "/v1/friends":
            self._handle_friends(parsed.query)
            return
        self._write_json(HTTPStatus.NOT_FOUND, {"error": "not_found"})

    def do_POST(self) -> None:
        parsed = urlsplit(self.path)
        client_id: Optional[str] = None
        if parsed.path == "/v1/broadcast":
            client_id = self._authorize_client()
            if client_id is None:
                return
        elif not self._authorize():
            return
        try:
            body = self._read_json_body()
            if client_id is not None:
                self._handle_broadcast(client_id, body)
                return
            if parsed.path == "/v1/register":
                self._handle_register(body)
                return
            if parsed.path == "/v1/send":
                self._handle_send(body)
                return
            self._write_json(HTTPStatus.NOT_FOUND, {"error": "not_found"})
        except BadRequestError as error:
            self._write_json(
                HTTPStatus.BAD_REQUEST,
                {"error": "bad_request", "reason": str(error)},
            )

    def _authorize(self) -> bool:
        supplied_key = self.headers.get("X-Yo-Key", "")
        if hmac.compare_digest(supplied_key, self.server.shared_key):
            return True
        self._write_json(
            HTTPStatus.UNAUTHORIZED,
            {"error": "unauthorized"},
        )
        return False

    def _authorize_client(self) -> Optional[str]:
        client_id = self.headers.get("X-Yo-Client-Id", "")
        supplied_key = self.headers.get("X-Yo-Client-Key", "")
        stored_hash = self.server.database.get_api_key_hash(client_id)
        if (
            client_id
            and supplied_key
            and stored_hash is not None
            and hmac.compare_digest(
                _hash_client_key(supplied_key),
                stored_hash,
            )
        ):
            return client_id
        self._write_json(
            HTTPStatus.UNAUTHORIZED,
            {"error": "unauthorized"},
        )
        return None

    def _handle_register(self, body: Dict[str, Any]) -> None:
        username = self._required_string(body, "username")
        fcm_token = self._required_string(body, "fcm_token")
        self.server.database.upsert_device(username, fcm_token)
        self._write_json(
            HTTPStatus.OK,
            {"registered": True, "username": username},
        )

    def _handle_friends(self, query: str) -> None:
        parameters = parse_qs(query, keep_blank_values=True)
        values = parameters.get("username") or parameters.get("requester")
        if not values or not values[0].strip():
            self._write_json(
                HTTPStatus.BAD_REQUEST,
                {
                    "error": "bad_request",
                    "reason": "username is required",
                },
            )
            return
        username = values[0].strip()
        friends = self.server.database.list_friends(username)
        self._write_json(HTTPStatus.OK, {"friends": friends})

    def _handle_send(self, body: Dict[str, Any]) -> None:
        sender = self._required_string(body, "sender")
        recipient = self._required_string(body, "recipient")
        fcm_token = self.server.database.get_fcm_token(recipient)
        if fcm_token is None:
            self._write_json(
                HTTPStatus.NOT_FOUND,
                {
                    "delivered": False,
                    "reason": "recipient_not_found",
                },
            )
            return
        try:
            delivered = bool(self.server.fcm_client.send_yo(fcm_token, sender))
        except FCMNotConfiguredError:
            self._write_json(
                HTTPStatus.OK,
                {
                    "delivered": False,
                    "reason": "fcm_not_configured",
                },
            )
            return
        except FCMDeliveryError:
            self._write_json(
                HTTPStatus.BAD_GATEWAY,
                {
                    "delivered": False,
                    "reason": "fcm_delivery_failed",
                },
            )
            return
        self._write_json(HTTPStatus.OK, {"delivered": delivered})

    def _handle_broadcast(
        self,
        client_id: str,
        body: Dict[str, Any],
    ) -> None:
        message = body.get("message")
        if message is not None and not isinstance(message, str):
            raise BadRequestError("message must be a string")
        tokens = self.server.database.list_subscriber_tokens(client_id)
        delivered_count = 0
        failed_count = 0
        for token in tokens:
            try:
                if self.server.fcm_client.send_yo(token, client_id):
                    delivered_count += 1
                else:
                    failed_count += 1
            except FCMNotConfiguredError:
                self._write_json(
                    HTTPStatus.OK,
                    {
                        "delivered": 0,
                        "failed": 0,
                        "subscriber_count": len(tokens),
                        "reason": "fcm_not_configured",
                    },
                )
                return
            except FCMDeliveryError:
                failed_count += 1
        self._write_json(
            HTTPStatus.OK,
            {
                "delivered": delivered_count,
                "failed": failed_count,
                "subscriber_count": len(tokens),
            },
        )

    def _read_json_body(self) -> Dict[str, Any]:
        raw_length = self.headers.get("Content-Length")
        if raw_length is None:
            raise BadRequestError("Content-Length is required")
        try:
            content_length = int(raw_length)
        except ValueError as error:
            raise BadRequestError("Content-Length must be an integer") from error
        if content_length <= 0:
            raise BadRequestError("request body is required")
        if content_length > MAX_BODY_BYTES:
            raise BadRequestError("request body is too large")
        raw_body = self.rfile.read(content_length)
        try:
            body = json.loads(raw_body.decode("utf-8"))
        except (UnicodeDecodeError, json.JSONDecodeError) as error:
            raise BadRequestError("request body must be valid JSON") from error
        if not isinstance(body, dict):
            raise BadRequestError("request body must be a JSON object")
        return body

    @staticmethod
    def _required_string(body: Dict[str, Any], key: str) -> str:
        value = body.get(key)
        if not isinstance(value, str) or not value.strip():
            raise BadRequestError(f"{key} is required")
        return value.strip()

    def _write_json(self, status: HTTPStatus, payload: Dict[str, Any]) -> None:
        body = json.dumps(payload, separators=(",", ":")).encode("utf-8")
        self.send_response(status.value)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)


def create_server(
    host: str,
    port: int,
    database_path: os.PathLike | str,
    shared_key: str,
    fcm_client: Optional[Any] = None,
) -> YoHTTPServer:
    if not shared_key:
        raise ValueError("shared_key must not be empty")
    database = YoDatabase(database_path)
    database.initialize()
    return YoHTTPServer(
        (host, port),
        database=database,
        shared_key=shared_key,
        fcm_client=fcm_client or FCMClient(),
    )


def main() -> None:
    parser = argparse.ArgumentParser(description="Yo push delivery backend")
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=DEFAULT_PORT)
    parser.add_argument("--database", default=str(DEFAULT_DATABASE))
    arguments = parser.parse_args()

    server_key = os.environ.get("YO_SERVER_KEY", "").strip()
    if not server_key:
        parser.error("YO_SERVER_KEY must be set")

    server = create_server(
        host=arguments.host,
        port=arguments.port,
        database_path=arguments.database,
        shared_key=server_key,
    )
    try:
        print(
            f"Yo backend listening on http://{arguments.host}:{arguments.port}",
            flush=True,
        )
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
