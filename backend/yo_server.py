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
MAX_BODY_BYTES = 2_000_000
MAX_PHOTO_BYTES = 1_400_000


def _hash_client_key(raw_key: str) -> str:
    return hashlib.sha256(raw_key.encode("utf-8")).hexdigest()


def _configured_apk_path() -> Optional[Path]:
    """The APK to hand out at /install/yo.apk, or None if none is configured."""
    configured = os.environ.get("YO_APK_PATH", "").strip()
    if not configured:
        return None
    path = Path(configured)
    return path if path.is_file() else None


# Styled from Yo's own values: Amethyst #9B59B6, Montserrat Bold, white centred type, the mixed-case
# "Yo" wordmark and the tagline "It's that simple." See docs/PRD.md section 4.1.
INSTALL_PAGE_TEMPLATE = """<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="theme-color" content="#9b59b6">
<title>Yo - It's that simple.</title>
<link rel="stylesheet" href="https://fonts.googleapis.com/css?family=Montserrat:700,400">
<style>
  html, body { height: 100%; margin: 0; }
  body {
    background: #9B59B6;
    font-family: 'Montserrat', arial, sans-serif;
    font-weight: 700;
    color: white;
    text-align: center;
    text-rendering: optimizeLegibility;
    display: flex; flex-direction: column;
    align-items: center; justify-content: center;
    padding: 24px;
    box-sizing: border-box;
  }
  h1 { font-size: 22vw; line-height: 1; margin: 0; letter-spacing: -0.04em; }
  h2 { font-size: 5vw; margin: 12px 0 36px; font-weight: 700; }
  .btn {
    display: inline-block; background: #1ABC9C; color: white;
    text-decoration: none; padding: 18px 34px; border: 0; border-radius: 5px;
    font-size: 18px; font-family: inherit;
  }
  .small { font-size: 13px; font-weight: 400; opacity: 0.85; max-width: 22em; }
  @media (min-width: 700px) { h1 { font-size: 150px; } h2 { font-size: 32px; } }
</style>
</head>
<body>
  <h1>Yo</h1>
  <h2>It's that simple.</h2>
  {{ACTION}}
</body>
</html>
"""


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
        # The install page is the target of shared invite links, so it has to be reachable by
        # someone who does not have the app and therefore has no shared key. It is static and
        # exposes no data.
        if parsed.path in ("/install", "/install/"):
            self._handle_install_page()
            return
        if parsed.path == "/install/yo.apk":
            self._handle_install_apk()
            return
        if not self._authorize():
            return
        if parsed.path == "/v1/friends":
            self._handle_friends(parsed.query)
            return
        if parsed.path == "/v1/photo":
            self._handle_photo_fetch(parsed.query)
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
            if parsed.path == "/v1/photo":
                self._handle_photo_upload(body)
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

    def _handle_photo_upload(self, body: Dict[str, Any]) -> None:
        message_id = self._required_string(body, "message_id")
        mime_type = self._required_string(body, "mime_type")
        data = self._required_string(body, "data")
        if len(data.encode("utf-8")) > MAX_PHOTO_BYTES:
            self._write_json(
                HTTPStatus.BAD_REQUEST,
                {"error": "photo_too_large"},
            )
            return
        self.server.database.store_photo(message_id, mime_type, data)
        self._write_json(HTTPStatus.OK, {"stored": True})

    def _handle_photo_fetch(self, query: str) -> None:
        parameters = parse_qs(query, keep_blank_values=True)
        values = parameters.get("message_id")
        message_id = values[0].strip() if values else ""
        photo = self.server.database.get_photo(message_id)
        if photo is None:
            self._write_json(HTTPStatus.NOT_FOUND, {"error": "not_found"})
            return
        mime_type, data = photo
        self._write_json(
            HTTPStatus.OK,
            {"mime_type": mime_type, "data": data},
        )

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

    def _handle_install_page(self) -> None:
        apk = _configured_apk_path()
        if apk is not None:
            action = (
                '<a class="btn" href="/install/yo.apk">Download Yo</a>'
                '<p class="small">Android only. You may need to allow installs '
                "from your browser.</p>"
            )
        else:
            action = '<p class="small">The download is not published yet.</p>'
        body = INSTALL_PAGE_TEMPLATE.replace("{{ACTION}}", action).encode("utf-8")
        self.send_response(HTTPStatus.OK.value)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _handle_install_apk(self) -> None:
        apk = _configured_apk_path()
        if apk is None:
            self._write_json(HTTPStatus.NOT_FOUND, {"error": "not_found"})
            return
        data = apk.read_bytes()
        self.send_response(HTTPStatus.OK.value)
        self.send_header("Content-Type", "application/vnd.android.package-archive")
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Content-Disposition", 'attachment; filename="yo.apk"')
        self.end_headers()
        self.wfile.write(data)

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
