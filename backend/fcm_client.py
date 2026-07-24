import json
import os
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from typing import Any, Dict, Mapping, Optional


FCM_SCOPE = "https://www.googleapis.com/auth/firebase.messaging"
FCM_ENDPOINT = "https://fcm.googleapis.com/v1/projects/{project_id}/messages:send"


class FCMError(RuntimeError):
    pass


class FCMNotConfiguredError(FCMError):
    pass


class FCMDeliveryError(FCMError):
    pass


class _TransportResponse:
    def __init__(
        self,
        status: int,
        data: bytes,
        headers: Mapping[str, str],
    ):
        self.status = status
        self.data = data
        self.headers = headers


class _UrllibAuthRequest:
    def __call__(
        self,
        url: str,
        method: str = "GET",
        body: Optional[bytes] = None,
        headers: Optional[Mapping[str, str]] = None,
        timeout: Optional[float] = 120,
        **_: Any,
    ) -> _TransportResponse:
        request = urllib.request.Request(
            url=url,
            data=body,
            headers=dict(headers or {}),
            method=method,
        )
        try:
            with urllib.request.urlopen(request, timeout=timeout) as response:
                return _TransportResponse(
                    status=response.status,
                    data=response.read(),
                    headers=response.headers,
                )
        except urllib.error.HTTPError as error:
            return _TransportResponse(
                status=error.code,
                data=error.read(),
                headers=error.headers,
            )
        except urllib.error.URLError as error:
            from google.auth.exceptions import TransportError

            raise TransportError(str(error)) from error


def _new_auth_request() -> Any:
    try:
        from google.auth.transport.requests import Request
    except ImportError:
        return _UrllibAuthRequest()
    return Request()


class FCMClient:
    def __init__(
        self,
        service_account_path: Optional[str] = None,
        project_id: Optional[str] = None,
        timeout_seconds: float = 15,
    ):
        self._service_account_path = service_account_path
        self._project_id = project_id
        self._timeout_seconds = timeout_seconds
        self._credentials = None
        self._credential_source: Optional[str] = None
        self._lock = threading.Lock()

    def send_yo(self, fcm_token: str, sender: str) -> bool:
        service_account_path, project_id = self._configuration()
        access_token = self._access_token(service_account_path)
        endpoint = FCM_ENDPOINT.format(
            project_id=urllib.parse.quote(project_id, safe="")
        )
        payload: Dict[str, object] = {
            "message": {
                "token": fcm_token,
                "data": {
                    "type": "yo",
                    "sender": sender,
                    "timestamp": str(int(time.time() * 1000)),
                },
            }
        }
        body = json.dumps(payload, separators=(",", ":")).encode("utf-8")
        request = urllib.request.Request(
            endpoint,
            data=body,
            headers={
                "Authorization": f"Bearer {access_token}",
                "Content-Type": "application/json; charset=utf-8",
            },
            method="POST",
        )
        try:
            with urllib.request.urlopen(
                request,
                timeout=self._timeout_seconds,
            ) as response:
                response.read()
                return 200 <= response.status < 300
        except urllib.error.HTTPError as error:
            detail = error.read(512).decode("utf-8", errors="replace")
            raise FCMDeliveryError(
                f"FCM returned HTTP {error.code}: {detail}"
            ) from error
        except urllib.error.URLError as error:
            raise FCMDeliveryError(f"FCM request failed: {error}") from error

    def _configuration(self) -> tuple[str, str]:
        service_account_path = (
            self._service_account_path or os.environ.get("YO_FIREBASE_SA_KEY", "")
        ).strip()
        project_id = (
            self._project_id or os.environ.get("YO_FIREBASE_PROJECT_ID", "")
        ).strip()
        if not service_account_path or not project_id:
            raise FCMNotConfiguredError(
                "YO_FIREBASE_SA_KEY and YO_FIREBASE_PROJECT_ID must both be set"
            )
        return service_account_path, project_id

    def _access_token(self, service_account_path: str) -> str:
        with self._lock:
            try:
                from google.oauth2 import service_account
            except ImportError as error:
                raise FCMDeliveryError(
                    "google-auth is required for configured FCM delivery"
                ) from error

            if (
                self._credentials is None
                or self._credential_source != service_account_path
            ):
                try:
                    self._credentials = (
                        service_account.Credentials.from_service_account_file(
                            service_account_path,
                            scopes=[FCM_SCOPE],
                        )
                    )
                except (OSError, ValueError) as error:
                    raise FCMDeliveryError(
                        f"Unable to load Firebase service account: {error}"
                    ) from error
                self._credential_source = service_account_path

            if not self._credentials.valid:
                try:
                    self._credentials.refresh(_new_auth_request())
                except Exception as error:
                    raise FCMDeliveryError(
                        f"Unable to refresh Firebase access token: {error}"
                    ) from error

            if not self._credentials.token:
                raise FCMDeliveryError(
                    "Firebase credential refresh returned no access token"
                )
            return self._credentials.token
