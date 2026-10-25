"""Anonymous device sign-in, plus phone verification before a user may post listings.

Tokens and one-time codes are stored only as SHA-256 hashes.
"""

import hashlib
import logging
import re
import secrets
from typing import Protocol

import psycopg

log = logging.getLogger(__name__)

OTP_TTL_MINUTES = 10
OTP_MAX_ATTEMPTS = 5
# Mauritian mobile numbers: +230 followed by 8 digits starting with 5.
MU_MOBILE = re.compile(r"^\+2305\d{7}$")


def _sha(value: str) -> str:
    return hashlib.sha256(value.encode()).hexdigest()


def issue_device_token(conn: psycopg.Connection) -> tuple[str, str]:
    token = secrets.token_urlsafe(32)
    with conn.transaction():
        user_id = conn.execute("INSERT INTO app_user DEFAULT VALUES RETURNING id").fetchone()["id"]
        conn.execute("INSERT INTO auth_token (token_sha256, user_id) VALUES (%s, %s)", (_sha(token), user_id))
    return token, str(user_id)


def user_for_token(conn: psycopg.Connection, token: str) -> dict | None:
    return conn.execute(
        """UPDATE auth_token t SET last_used_at = now() FROM app_user u
           WHERE t.token_sha256 = %s AND u.id = t.user_id RETURNING u.*""",
        (_sha(token),),
    ).fetchone()


class OtpSender(Protocol):
    def send(self, phone_e164: str, code: str) -> None: ...


class LogOtpSender:
    """SMS costs money with every provider, so codes are only logged. Users verify
    with Google sign-in instead; plug a provider in here if SMS is ever wanted."""

    def send(self, phone_e164: str, code: str) -> None:
        log.warning("OTP for %s: %s", phone_e164, code)


def start_phone_verification(conn: psycopg.Connection, user_id, phone: str, sender: OtpSender) -> None:
    phone = re.sub(r"[\s-]", "", phone)
    if not MU_MOBILE.match(phone):
        raise ValueError("invalid_phone")
    code = f"{secrets.randbelow(1_000_000):06d}"
    with conn.transaction():
        conn.execute(
            """INSERT INTO phone_otp (user_id, phone_e164, code_sha256, expires_at)
               VALUES (%s, %s, %s, now() + make_interval(mins => %s))
               ON CONFLICT (user_id) DO UPDATE SET phone_e164 = EXCLUDED.phone_e164,
                 code_sha256 = EXCLUDED.code_sha256, attempts = 0, expires_at = EXCLUDED.expires_at""",
            (user_id, phone, _sha(code), OTP_TTL_MINUTES),
        )
    sender.send(phone, code)


def verify_phone(conn: psycopg.Connection, user_id, code: str) -> bool:
    with conn.transaction():
        row = conn.execute(
            "SELECT * FROM phone_otp WHERE user_id = %s AND expires_at > now() FOR UPDATE", (user_id,)
        ).fetchone()
        if row is None or row["attempts"] >= OTP_MAX_ATTEMPTS:
            return False
        if not secrets.compare_digest(row["code_sha256"], _sha(code)):
            conn.execute("UPDATE phone_otp SET attempts = attempts + 1 WHERE user_id = %s", (user_id,))
            return False
        conn.execute("DELETE FROM phone_otp WHERE user_id = %s", (user_id,))
        owner = conn.execute(
            "SELECT id FROM app_user WHERE phone_e164 = %s AND id <> %s", (row["phone_e164"], user_id)
        ).fetchone()
        if owner:
            # The number already has an account (e.g. a new phone): move this device's
            # token and saved items onto it. The anonymous user row stays, as its
            # leads and reports still reference it.
            _move_device_to(conn, user_id, owner["id"])
        else:
            conn.execute(
                """UPDATE app_user SET phone_e164 = %s, phone_verified_at = now(),
                     verified_at = coalesce(verified_at, now()) WHERE id = %s""",
                (row["phone_e164"], user_id),
            )
    return True


def _move_device_to(conn: psycopg.Connection, from_user, to_user) -> None:
    """Attach this device's token and saved items to an existing account."""
    conn.execute("UPDATE auth_token SET user_id = %s WHERE user_id = %s", (to_user, from_user))
    conn.execute("UPDATE saved_search SET user_id = %s WHERE user_id = %s", (to_user, from_user))
    conn.execute(
        """INSERT INTO saved_property (user_id, property_id)
           SELECT %s, property_id FROM saved_property WHERE user_id = %s ON CONFLICT DO NOTHING""",
        (to_user, from_user),
    )


class GoogleVerifier(Protocol):
    def __call__(self, token: str) -> dict: ...


def google_verifier(client_ids: tuple[str, ...]) -> GoogleVerifier:
    """Checks a Google ID token's signature, expiry and audience against Google's
    public keys. Free: no SMS, no paid API."""
    from google.auth.transport.requests import Request
    from google.oauth2 import id_token

    def verify(token: str) -> dict:
        claims = id_token.verify_oauth2_token(token, Request(), audience=None)
        if claims.get("aud") not in client_ids:
            raise ValueError("wrong_audience")
        if not claims.get("email_verified"):
            raise ValueError("email_not_verified")
        return claims

    return verify


def verify_google(conn: psycopg.Connection, user_id, token: str, verifier: GoogleVerifier) -> None:
    """Marks the user verified through Google sign-in (an alternative to SMS codes)."""
    claims = verifier(token)
    with conn.transaction():
        owner = conn.execute("SELECT id FROM app_user WHERE google_sub = %s AND id <> %s",
                             (claims["sub"], user_id)).fetchone()
        if owner:
            _move_device_to(conn, user_id, owner["id"])
        else:
            conn.execute(
                """UPDATE app_user SET google_sub = %s, email = %s, verified_at = coalesce(verified_at, now())
                   WHERE id = %s""",
                (claims["sub"], claims.get("email"), user_id),
            )
