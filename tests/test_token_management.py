"""Integration tests for token management."""

import pytest
from hivemem.db import fetch_one
from hivemem.security import (
    create_token,
    list_tokens,
    revoke_token,
    get_token_info,
    validate_token,
)


async def test_api_tokens_table_exists(pool):
    """api_tokens table was created by schema."""
    row = await fetch_one(
        pool,
        "SELECT count(*) AS cnt FROM information_schema.tables "
        "WHERE table_name = 'api_tokens'",
    )
    assert row["cnt"] == 1


async def test_create_token(pool):
    """Create a token, get back plaintext, validate it."""
    plaintext = await create_token(pool, "test-admin", "admin")
    assert isinstance(plaintext, str)
    assert len(plaintext) > 20

    info = await get_token_info(pool, "test-admin")
    assert info["name"] == "test-admin"
    assert info["role"] == "admin"
    assert info["revoked_at"] is None


async def test_create_duplicate_name_fails(pool):
    """Duplicate token name raises ValueError."""
    await create_token(pool, "dup-test", "reader")
    with pytest.raises(ValueError, match="already exists"):
        await create_token(pool, "dup-test", "reader")


async def test_create_invalid_role_fails(pool):
    """Invalid role raises ValueError."""
    with pytest.raises(ValueError, match="Invalid role"):
        await create_token(pool, "bad-role", "superuser")


async def test_validate_token_returns_identity(pool):
    """Valid token returns name and role."""
    plaintext = await create_token(pool, "val-test", "writer")
    identity = await validate_token(pool, plaintext)
    assert identity["name"] == "val-test"
    assert identity["role"] == "writer"


async def test_validate_invalid_token_returns_none(pool):
    """Unknown token returns None."""
    identity = await validate_token(pool, "bogus-token-value")
    assert identity is None


async def test_list_tokens(pool):
    """List shows all tokens with metadata, no hashes."""
    await create_token(pool, "list-a", "admin")
    await create_token(pool, "list-b", "reader")
    tokens = await list_tokens(pool)
    names = [t["name"] for t in tokens]
    assert "list-a" in names
    assert "list-b" in names
    # No hash or plaintext in output
    for t in tokens:
        assert "token_hash" not in t
        assert "plaintext" not in t


async def test_revoke_token(pool):
    """Revoked token fails validation."""
    plaintext = await create_token(pool, "rev-test", "writer")
    await revoke_token(pool, "rev-test")

    identity = await validate_token(pool, plaintext)
    assert identity is None

    info = await get_token_info(pool, "rev-test")
    assert info["revoked_at"] is not None


async def test_revoke_nonexistent_raises(pool):
    """Revoking unknown name raises ValueError."""
    with pytest.raises(ValueError, match="not found"):
        await revoke_token(pool, "ghost")


async def test_expired_token_fails_validation(pool):
    """Token with expires_at in the past fails validation."""
    from hivemem.db import execute

    plaintext = await create_token(pool, "exp-test", "reader")
    # Manually set expires_at to the past
    await execute(
        pool,
        "UPDATE api_tokens SET expires_at = now() - interval '1 hour' WHERE name = 'exp-test'",
    )
    identity = await validate_token(pool, plaintext)
    assert identity is None


async def test_create_token_with_expiry(pool):
    """Token with future expiry validates successfully."""
    plaintext = await create_token(pool, "expiry-test", "admin", expires_in_days=90)
    identity = await validate_token(pool, plaintext)
    assert identity is not None

    info = await get_token_info(pool, "expiry-test")
    assert info["expires_at"] is not None


from hivemem.security import ROLE_TOOLS, ALL_TOOLS


def test_admin_sees_all_tools():
    """Admin role has access to every registered tool."""
    assert ROLE_TOOLS["admin"] == ALL_TOOLS


def test_reader_sees_only_read_tools():
    """Reader cannot see any write or admin tool."""
    reader_tools = ROLE_TOOLS["reader"]
    assert "hivemem_search" in reader_tools
    assert "hivemem_wake_up" in reader_tools
    assert "hivemem_add_drawer" not in reader_tools
    assert "hivemem_approve_pending" not in reader_tools
    assert "hivemem_health" not in reader_tools


def test_writer_cannot_approve():
    """Writer has write tools but not approve_pending."""
    writer_tools = ROLE_TOOLS["writer"]
    assert "hivemem_add_drawer" in writer_tools
    assert "hivemem_kg_add" in writer_tools
    assert "hivemem_approve_pending" not in writer_tools
    assert "hivemem_health" not in writer_tools


def test_agent_matches_writer():
    """Agent sees the same tools as writer."""
    assert ROLE_TOOLS["agent"] == ROLE_TOOLS["writer"]


def test_no_unknown_tools_in_roles():
    """Every tool in a role set must exist in ALL_TOOLS."""
    for role, tools in ROLE_TOOLS.items():
        unknown = tools - ALL_TOOLS
        assert not unknown, f"Role '{role}' has unknown tools: {unknown}"


from hivemem.security import AuthMiddleware, create_token, validate_token, revoke_token


async def test_middleware_valid_token(pool):
    """Valid token passes auth and injects identity into scope."""
    plaintext = await create_token(pool, "mw-admin", "admin")

    captured_scope = {}

    async def mock_app(scope, receive, send):
        captured_scope.update(scope)

    mw = AuthMiddleware(mock_app, pool)

    scope = {
        "type": "http",
        "headers": [
            (b"authorization", f"Bearer {plaintext}".encode()),
        ],
        "client": ("127.0.0.1", 12345),
    }

    sent = []

    async def mock_receive():
        return {}

    async def mock_send(message):
        sent.append(message)

    await mw(scope, mock_receive, mock_send)
    assert captured_scope.get("token_name") == "mw-admin"
    assert captured_scope.get("token_role") == "admin"
    assert not sent  # no error response


async def test_middleware_invalid_token(pool):
    """Invalid token gets 401."""
    async def mock_app(scope, receive, send):
        raise AssertionError("Should not reach app")

    mw = AuthMiddleware(mock_app, pool)

    scope = {
        "type": "http",
        "headers": [(b"authorization", b"Bearer bad-token")],
        "client": ("127.0.0.1", 12345),
    }

    sent = []

    async def mock_send(message):
        sent.append(message)

    await mw(scope, lambda: {}, mock_send)
    assert sent[0]["status"] == 401


async def test_middleware_missing_token(pool):
    """Missing token gets 401."""
    async def mock_app(scope, receive, send):
        raise AssertionError("Should not reach app")

    mw = AuthMiddleware(mock_app, pool)

    scope = {
        "type": "http",
        "headers": [],
        "client": ("127.0.0.1", 12345),
    }

    sent = []

    async def mock_send(message):
        sent.append(message)

    await mw(scope, lambda: {}, mock_send)
    assert sent[0]["status"] == 401


async def test_middleware_revoked_token(pool):
    """Revoked token gets 401."""
    plaintext = await create_token(pool, "mw-revoked", "writer")
    await revoke_token(pool, "mw-revoked")

    async def mock_app(scope, receive, send):
        raise AssertionError("Should not reach app")

    mw = AuthMiddleware(mock_app, pool)
    # Clear cache so revocation is immediate in tests
    mw._cache.clear()

    scope = {
        "type": "http",
        "headers": [(b"authorization", f"Bearer {plaintext}".encode())],
        "client": ("127.0.0.1", 12345),
    }

    sent = []

    async def mock_send(message):
        sent.append(message)

    await mw(scope, lambda: {}, mock_send)
    assert sent[0]["status"] == 401


from hivemem.security import ROLE_TOOLS, READ_TOOLS, WRITE_TOOLS, ADMIN_TOOLS


def test_reader_tool_set_count():
    """Reader sees exactly READ_TOOLS."""
    assert len(ROLE_TOOLS["reader"]) == len(READ_TOOLS)


def test_writer_tool_set_count():
    """Writer sees READ + WRITE but not ADMIN."""
    assert len(ROLE_TOOLS["writer"]) == len(READ_TOOLS | WRITE_TOOLS)


def test_admin_tool_set_count():
    """Admin sees everything."""
    assert len(ROLE_TOOLS["admin"]) == len(READ_TOOLS | WRITE_TOOLS | ADMIN_TOOLS)


async def test_tool_call_denied_for_reader(pool):
    """Reader calling a write tool gets error via check_tool_permission."""
    from hivemem.security import check_tool_permission

    err = check_tool_permission("reader", "hivemem_add_drawer")
    assert err is not None
    assert "not permitted" in err.lower()


async def test_tool_call_allowed_for_admin(pool):
    """Admin calling approve_pending is allowed."""
    from hivemem.security import check_tool_permission

    err = check_tool_permission("admin", "hivemem_approve_pending")
    assert err is None


async def test_tool_call_denied_writer_approve(pool):
    """Writer calling approve_pending gets error."""
    from hivemem.security import check_tool_permission

    err = check_tool_permission("writer", "hivemem_approve_pending")
    assert err is not None


async def test_tool_call_denied_agent_approve(pool):
    """Agent calling approve_pending gets error."""
    from hivemem.security import check_tool_permission

    err = check_tool_permission("agent", "hivemem_approve_pending")
    assert err is not None


def test_filter_tools_for_reader():
    """filter_tools_for_role removes non-read tools."""
    from hivemem.security import filter_tools_for_role

    all_tool_defs = [
        {"name": "hivemem_search", "description": "search"},
        {"name": "hivemem_add_drawer", "description": "add"},
        {"name": "hivemem_approve_pending", "description": "approve"},
        {"name": "hivemem_health", "description": "health"},
    ]
    filtered = filter_tools_for_role("reader", all_tool_defs)
    names = [t["name"] for t in filtered]
    assert "hivemem_search" in names
    assert "hivemem_add_drawer" not in names
    assert "hivemem_approve_pending" not in names
    assert "hivemem_health" not in names


def test_filter_tools_for_admin():
    """Admin keeps all tools."""
    from hivemem.security import filter_tools_for_role

    all_tool_defs = [
        {"name": "hivemem_search", "description": "search"},
        {"name": "hivemem_add_drawer", "description": "add"},
        {"name": "hivemem_approve_pending", "description": "approve"},
    ]
    filtered = filter_tools_for_role("admin", all_tool_defs)
    assert len(filtered) == 3


def test_filter_tools_for_writer():
    """Writer sees read+write but not admin tools."""
    from hivemem.security import filter_tools_for_role

    all_tool_defs = [
        {"name": "hivemem_search", "description": "search"},
        {"name": "hivemem_add_drawer", "description": "add"},
        {"name": "hivemem_approve_pending", "description": "approve"},
        {"name": "hivemem_health", "description": "health"},
    ]
    filtered = filter_tools_for_role("writer", all_tool_defs)
    names = [t["name"] for t in filtered]
    assert "hivemem_search" in names
    assert "hivemem_add_drawer" in names
    assert "hivemem_approve_pending" not in names
    assert "hivemem_health" not in names
