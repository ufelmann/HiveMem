"""HiveMem Security — Bearer token auth, rate limiting, audit logging."""

from __future__ import annotations

import hashlib
import hmac
import json
import logging
import secrets
import time
from datetime import datetime, timedelta, timezone
from logging.handlers import RotatingFileHandler
from pathlib import Path

DATA_DIR = Path("/data")
SECRETS_FILE = DATA_DIR / "secrets.json"
AUDIT_LOG_FILE = DATA_DIR / "audit.log"

# ── Secrets Management ─────────────────────────────────────────────────


def load_secrets() -> dict:
    """Load secrets from /data/secrets.json."""
    if SECRETS_FILE.exists():
        return json.loads(SECRETS_FILE.read_text())
    return {}


def save_secrets(data: dict):
    """Save secrets with restrictive permissions."""
    SECRETS_FILE.write_text(json.dumps(data, indent=2))
    SECRETS_FILE.chmod(0o600)


def ensure_secrets() -> dict:
    """Generate API token and DB password on first start. Returns secrets dict."""
    data = load_secrets()
    changed = False

    if "api_token" not in data:
        data["api_token"] = secrets.token_urlsafe(32)
        changed = True
        print(f"\n{'=' * 60}")
        print(f"  HiveMem API token: {data['api_token']}")
        print(f"  Save this for your MCP client config.")
        print(f"  Retrieve later: docker exec hivemem hivemem-token")
        print(f"{'=' * 60}\n")

    if "db_password" not in data:
        data["db_password"] = secrets.token_urlsafe(24)
        changed = True

    if changed:
        save_secrets(data)

    return data


def get_api_token() -> str:
    """Get the current API token."""
    return load_secrets()["api_token"]


def regenerate_api_token() -> str:
    """Generate a new API token. Returns the new token."""
    data = load_secrets()
    data["api_token"] = secrets.token_urlsafe(32)
    save_secrets(data)
    return data["api_token"]


def get_db_url() -> str:
    """Build DB URL with password from secrets."""
    data = load_secrets()
    password = data.get("db_password", "")
    return f"postgresql://hivemem:{password}@/hivemem?host=/var/run/postgresql"


# ── Token Management (DB-backed) ─────────────────────────────────────


def _hash_token(plaintext: str) -> str:
    """SHA-256 hash of a token."""
    return hashlib.sha256(plaintext.encode()).hexdigest()


VALID_ROLES = {"admin", "writer", "reader", "agent"}


async def create_token(
    pool, name: str, role: str, expires_in_days: int | None = None
) -> str:
    """Create a new API token. Returns plaintext (shown once, never stored)."""
    if role not in VALID_ROLES:
        raise ValueError(f"Invalid role '{role}'. Must be one of: {', '.join(sorted(VALID_ROLES))}")

    from hivemem.db import fetch_one

    existing = await fetch_one(pool, "SELECT 1 FROM api_tokens WHERE name = %s", (name,))
    if existing:
        raise ValueError(f"Token '{name}' already exists")

    plaintext = secrets.token_urlsafe(32)
    token_hash = _hash_token(plaintext)

    expires_at = None
    if expires_in_days is not None:
        expires_at = datetime.now(timezone.utc) + timedelta(days=expires_in_days)

    from hivemem.db import execute

    await execute(
        pool,
        "INSERT INTO api_tokens (token_hash, name, role, expires_at) VALUES (%s, %s, %s, %s)",
        (token_hash, name, role, expires_at),
    )
    return plaintext


async def validate_token(pool, plaintext: str) -> dict | None:
    """Validate a plaintext token. Returns {name, role} or None."""
    token_hash = _hash_token(plaintext)

    from hivemem.db import fetch_one

    row = await fetch_one(
        pool,
        "SELECT name, role FROM api_tokens "
        "WHERE token_hash = %s AND revoked_at IS NULL "
        "AND (expires_at IS NULL OR expires_at > now())",
        (token_hash,),
    )
    if row is None:
        return None
    return {"name": row["name"], "role": row["role"]}


async def list_tokens(pool) -> list[dict]:
    """List all tokens (no hashes, no plaintext)."""
    from hivemem.db import fetch_all

    rows = await fetch_all(
        pool,
        "SELECT name, role, created_at, expires_at, revoked_at FROM api_tokens ORDER BY created_at",
    )
    return [
        {
            "name": r["name"],
            "role": r["role"],
            "created_at": r["created_at"].isoformat(),
            "expires_at": r["expires_at"].isoformat() if r["expires_at"] else None,
            "revoked_at": r["revoked_at"].isoformat() if r["revoked_at"] else None,
            "status": "revoked" if r["revoked_at"] else (
                "expired" if r["expires_at"] and r["expires_at"] < datetime.now(timezone.utc) else "active"
            ),
        }
        for r in rows
    ]


async def revoke_token(pool, name: str) -> None:
    """Revoke a token by name."""
    from hivemem.db import fetch_one, execute

    existing = await fetch_one(pool, "SELECT 1 FROM api_tokens WHERE name = %s", (name,))
    if not existing:
        raise ValueError(f"Token '{name}' not found")

    await execute(
        pool,
        "UPDATE api_tokens SET revoked_at = now() WHERE name = %s AND revoked_at IS NULL",
        (name,),
    )


async def get_token_info(pool, name: str) -> dict | None:
    """Get token metadata by name (no hash)."""
    from hivemem.db import fetch_one

    row = await fetch_one(
        pool,
        "SELECT name, role, created_at, expires_at, revoked_at FROM api_tokens WHERE name = %s",
        (name,),
    )
    if row is None:
        return None
    return {
        "name": row["name"],
        "role": row["role"],
        "created_at": row["created_at"],
        "expires_at": row["expires_at"],
        "revoked_at": row["revoked_at"],
    }


# ── Rate Limiting ──────────────────────────────────────────────────────

MAX_FAILED_ATTEMPTS = 5
BAN_SECONDS = 900  # 15 minutes

_failed_attempts: dict[str, tuple[int, float]] = {}


def check_rate_limit(ip: str) -> int | None:
    """Check if IP is banned. Returns seconds remaining if banned, None if OK."""
    if ip not in _failed_attempts:
        return None
    count, last_fail = _failed_attempts[ip]
    if count >= MAX_FAILED_ATTEMPTS:
        elapsed = time.time() - last_fail
        if elapsed < BAN_SECONDS:
            return int(BAN_SECONDS - elapsed)
        del _failed_attempts[ip]
    return None


def record_failed_auth(ip: str):
    """Record a failed auth attempt for rate limiting."""
    count, _ = _failed_attempts.get(ip, (0, 0.0))
    _failed_attempts[ip] = (count + 1, time.time())


def clear_failed_auth(ip: str):
    """Clear failed attempts after successful auth."""
    _failed_attempts.pop(ip, None)


# ── Audit Logging ──────────────────────────────────────────────────────

_audit_logger: logging.Logger | None = None


def get_audit_logger() -> logging.Logger:
    """Get or create the audit logger with rotating file handler."""
    global _audit_logger
    if _audit_logger is None:
        _audit_logger = logging.getLogger("hivemem.audit")
        _audit_logger.setLevel(logging.INFO)
        handler = RotatingFileHandler(
            str(AUDIT_LOG_FILE), maxBytes=10 * 1024 * 1024, backupCount=3
        )
        handler.setFormatter(
            logging.Formatter("%(asctime)s | %(message)s", datefmt="%Y-%m-%d %H:%M:%S")
        )
        _audit_logger.addHandler(handler)
    return _audit_logger


def audit(ip: str, status: str, detail: str = ""):
    """Write an audit log entry."""
    get_audit_logger().info(f"{ip} | {status} | {detail[:200]}")


# ── ASGI Auth Middleware ───────────────────────────────────────────────


class AuthMiddleware:
    """ASGI middleware: Bearer token auth + rate limiting + audit."""

    def __init__(self, app):
        self.app = app

    async def __call__(self, scope, receive, send):
        if scope["type"] != "http":
            await self.app(scope, receive, send)
            return

        ip = self._get_client_ip(scope)

        # Rate limit check
        ban_remaining = check_rate_limit(ip)
        if ban_remaining is not None:
            audit(ip, "AUTH_BANNED", f"remaining={ban_remaining}s")
            await self._send_error(send, 429, f"Too many failed attempts. Retry in {ban_remaining}s.",
                                   headers=[(b"retry-after", str(ban_remaining).encode())])
            return

        # Extract Bearer token
        token = self._extract_bearer_token(scope)
        if token is None:
            record_failed_auth(ip)
            audit(ip, "AUTH_FAIL", "missing token")
            await self._send_error(send, 401, "Bearer token required.")
            return

        # Timing-safe token comparison (re-read on every call so regenerate works live)
        if not hmac.compare_digest(token, get_api_token()):
            record_failed_auth(ip)
            audit(ip, "AUTH_FAIL", "invalid token")
            await self._send_error(send, 401, "Invalid token.")
            return

        # Auth OK
        clear_failed_auth(ip)
        audit(ip, "AUTH_OK")

        await self.app(scope, receive, send)

    @staticmethod
    def _get_client_ip(scope) -> str:
        client = scope.get("client")
        if client:
            return client[0]
        # Check X-Forwarded-For for proxied requests
        for key, value in scope.get("headers", []):
            if key == b"x-forwarded-for":
                return value.decode().split(",")[0].strip()
        return "unknown"

    @staticmethod
    def _extract_bearer_token(scope) -> str | None:
        for key, value in scope.get("headers", []):
            if key == b"authorization":
                auth = value.decode()
                if auth.lower().startswith("bearer "):
                    return auth[7:]
        return None

    @staticmethod
    async def _send_error(send, status: int, message: str, headers: list | None = None):
        import json as _json
        body = _json.dumps({"error": message}).encode()
        response_headers = [
            (b"content-type", b"application/json"),
            (b"content-length", str(len(body)).encode()),
        ]
        if headers:
            response_headers.extend(headers)
        await send({"type": "http.response.start", "status": status, "headers": response_headers})
        await send({"type": "http.response.body", "body": body})
