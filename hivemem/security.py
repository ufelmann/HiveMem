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

# ── Role-based tool permissions ───────────────────────────────────────

READ_TOOLS = {
    "hivemem_status",
    "hivemem_search",
    "hivemem_search_kg",
    "hivemem_get_drawer",
    "hivemem_list_wings",
    "hivemem_list_rooms",
    "hivemem_traverse",
    "hivemem_quick_facts",
    "hivemem_time_machine",
    "hivemem_wake_up",
    "hivemem_drawer_history",
    "hivemem_fact_history",
    "hivemem_pending_approvals",
    "hivemem_get_map",
    "hivemem_reading_list",
    "hivemem_list_agents",
    "hivemem_diary_read",
}

WRITE_TOOLS = {
    "hivemem_add_drawer",
    "hivemem_check_duplicate",
    "hivemem_kg_add",
    "hivemem_kg_invalidate",
    "hivemem_update_identity",
    "hivemem_add_reference",
    "hivemem_link_reference",
    "hivemem_revise_drawer",
    "hivemem_revise_fact",
    "hivemem_check_contradiction",
    "hivemem_register_agent",
    "hivemem_diary_write",
    "hivemem_update_map",
}

ADMIN_TOOLS = {
    "hivemem_approve_pending",
    "hivemem_health",
    "hivemem_log_access",
    "hivemem_refresh_popularity",
}

ALL_TOOLS = READ_TOOLS | WRITE_TOOLS | ADMIN_TOOLS

ROLE_TOOLS: dict[str, set[str]] = {
    "admin": ALL_TOOLS,
    "writer": READ_TOOLS | WRITE_TOOLS,
    "reader": READ_TOOLS,
    "agent": READ_TOOLS | WRITE_TOOLS,
}


def check_tool_permission(role: str, tool_name: str) -> str | None:
    """Check if a role can call a tool. Returns error message or None."""
    allowed = ROLE_TOOLS.get(role)
    if allowed is None:
        return f"Unknown role '{role}'"
    if tool_name not in allowed:
        return f"Tool '{tool_name}' not permitted for role '{role}'"
    return None


def filter_tools_for_role(role: str, tools: list[dict]) -> list[dict]:
    """Filter a tools/list response to only include tools allowed for the role."""
    allowed = ROLE_TOOLS.get(role, set())
    return [t for t in tools if t["name"] in allowed]


async def create_token(
    pool, name: str, role: str, expires_in_days: int | None = None
) -> str:
    """Create a new API token. Returns plaintext (shown once, never stored)."""
    if role not in VALID_ROLES:
        raise ValueError(f"Invalid role '{role}'. Must be one of: {', '.join(sorted(VALID_ROLES))}")

    plaintext = secrets.token_urlsafe(32)
    token_hash = _hash_token(plaintext)

    expires_at = None
    if expires_in_days is not None:
        expires_at = datetime.now(timezone.utc) + timedelta(days=expires_in_days)

    from psycopg.errors import UniqueViolation

    try:
        async with pool.connection() as conn:
            await conn.execute(
                "INSERT INTO api_tokens (token_hash, name, role, expires_at) VALUES (%s, %s, %s, %s)",
                (token_hash, name, role, expires_at),
            )
            await conn.commit()
    except UniqueViolation:
        raise ValueError(f"Token '{name}' already exists")
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


async def list_tokens(pool, include_revoked: bool = True, limit: int = 500) -> list[dict]:
    """List all tokens (no hashes, no plaintext)."""
    from hivemem.db import fetch_all

    if include_revoked:
        rows = await fetch_all(
            pool,
            "SELECT name, role, created_at, expires_at, revoked_at FROM api_tokens ORDER BY created_at LIMIT %s",
            (limit,),
        )
    else:
        rows = await fetch_all(
            pool,
            "SELECT name, role, created_at, expires_at, revoked_at FROM api_tokens "
            "WHERE revoked_at IS NULL ORDER BY created_at LIMIT %s",
            (limit,),
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
    """Revoke a token by name. Atomic — no race between check and update."""
    async with pool.connection() as conn:
        cur = await conn.execute(
            "UPDATE api_tokens SET revoked_at = now() WHERE name = %s AND revoked_at IS NULL RETURNING id",
            (name,),
        )
        row = await cur.fetchone()
        await conn.commit()
    if row is None:
        raise ValueError(f"Token '{name}' not found or already revoked")


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
        _failed_attempts.pop(ip, None)
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
        if AUDIT_LOG_FILE.parent.exists():
            handler: logging.Handler = RotatingFileHandler(
                str(AUDIT_LOG_FILE), maxBytes=10 * 1024 * 1024, backupCount=3
            )
        else:
            handler = logging.StreamHandler()
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
    """ASGI middleware: Bearer token auth via DB + rate limiting + audit.

    Cache: valid tokens are cached for CACHE_TTL seconds (default 60s).
    Revoked tokens remain usable until their cache entry expires.
    Cache is capped at CACHE_MAX_SIZE entries (LRU eviction).
    """

    CACHE_TTL = 60  # seconds
    CACHE_MAX_SIZE = 1000

    def __init__(self, app, pool=None):
        self.app = app
        self.pool = pool
        self._cache: dict[str, tuple[dict, float]] = {}  # hash -> (identity, timestamp)

    async def __call__(self, scope, receive, send):
        if scope["type"] != "http":
            await self.app(scope, receive, send)
            return

        ip = self._get_client_ip(scope)

        # Rate limit check
        ban_remaining = check_rate_limit(ip)
        if ban_remaining is not None:
            audit(ip, "AUTH_BANNED", f"remaining={ban_remaining}s")
            await self._send_error(
                send, 429, f"Too many failed attempts. Retry in {ban_remaining}s.",
                headers=[(b"retry-after", str(ban_remaining).encode())],
            )
            return

        # Extract Bearer token
        token = self._extract_bearer_token(scope)
        if token is None:
            record_failed_auth(ip)
            audit(ip, "AUTH_FAIL", "missing token")
            await self._send_error(send, 401, "Bearer token required.")
            return

        # Validate token (cached)
        identity = await self._validate_cached(token)
        if identity is None:
            record_failed_auth(ip)
            audit(ip, "AUTH_FAIL", "invalid token")
            await self._send_error(send, 401, "Invalid token.")
            return

        # Auth OK — inject identity into scope
        clear_failed_auth(ip)
        scope = dict(scope, token_name=identity["name"], token_role=identity["role"])
        audit(ip, "AUTH_OK", f"token={identity['name']} role={identity['role']}")

        await self.app(scope, receive, send)

    async def _validate_cached(self, plaintext: str) -> dict | None:
        """Validate token with in-memory cache (TTL-based)."""
        token_hash = _hash_token(plaintext)
        now = time.time()

        cached = self._cache.get(token_hash)
        if cached is not None:
            identity, cached_at = cached
            if now - cached_at < self.CACHE_TTL:
                return identity

        if self.pool is None:
            return None

        identity = await validate_token(self.pool, plaintext)
        if identity is not None:
            # LRU eviction: remove oldest entries if cache is full
            if len(self._cache) >= self.CACHE_MAX_SIZE:
                oldest_key = min(self._cache, key=lambda k: self._cache[k][1])
                self._cache.pop(oldest_key, None)
            self._cache[token_hash] = (identity, now)
        else:
            self._cache.pop(token_hash, None)
        return identity

    @staticmethod
    def _get_client_ip(scope) -> str:
        client = scope.get("client")
        return client[0] if client else "unknown"

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
