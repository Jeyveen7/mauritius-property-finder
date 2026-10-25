"""Push notifications through Firebase Cloud Messaging (free, no usage charges).

Needs a Firebase project's service-account JSON (MPF_FCM_SERVICE_ACCOUNT). Messages
are data-only; the app builds the notification text itself, in the user's language.
"""

import json
import logging
from pathlib import Path

import httpx
from google.auth.transport.requests import Request
from google.oauth2 import service_account

log = logging.getLogger(__name__)
SCOPE = "https://www.googleapis.com/auth/firebase.messaging"


class TokenGone(Exception):
    """The device uninstalled the app or the token expired; stop sending to it."""


class FcmSender:
    def __init__(self, service_account_file: str):
        info = json.loads(Path(service_account_file).read_text())
        self.project_id = info["project_id"]
        self.credentials = service_account.Credentials.from_service_account_info(info, scopes=[SCOPE])
        self.http = httpx.Client(timeout=20)

    def _bearer(self) -> str:
        if not self.credentials.valid:
            self.credentials.refresh(Request())
        return self.credentials.token

    def send(self, push_token: str, kind: str, property_id: str) -> None:
        resp = self.http.post(
            f"https://fcm.googleapis.com/v1/projects/{self.project_id}/messages:send",
            headers={"Authorization": f"Bearer {self._bearer()}"},
            json={"message": {"token": push_token, "data": {"kind": kind, "property_id": property_id},
                              "android": {"priority": "normal"}}},
        )
        if resp.status_code == 404 or "UNREGISTERED" in resp.text:
            raise TokenGone(push_token)
        resp.raise_for_status()


class LogPushSender:
    """Used when no Firebase project is configured."""

    def send(self, push_token: str, kind: str, property_id: str) -> None:
        log.info("push %s %s -> %s…", kind, property_id, push_token[:8])
