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


from hivemem.tools.write import hivemem_add_drawer, hivemem_kg_add
from hivemem.db import fetch_one as db_fetch_one


async def test_agent_write_forces_pending(pool):
    """Agent-role writes get status=pending regardless of input."""
    result = await hivemem_add_drawer(
        pool,
        content="Agent suggestion about testing",
        wing="eng", room="test", hall="facts",
        status="pending",
        created_by="test-agent",
    )
    row = await db_fetch_one(pool, "SELECT status, created_by FROM drawers WHERE id = %s", (result["id"],))
    assert row["status"] == "pending"
    assert row["created_by"] == "test-agent"


async def test_writer_write_is_committed(pool):
    """Writer-role writes get status=committed."""
    result = await hivemem_add_drawer(
        pool,
        content="Writer content about testing",
        wing="eng", room="test", hall="facts",
        status="committed",
        created_by="test-writer",
    )
    row = await db_fetch_one(pool, "SELECT status, created_by FROM drawers WHERE id = %s", (result["id"],))
    assert row["status"] == "committed"
    assert row["created_by"] == "test-writer"


async def test_agent_fact_forces_pending(pool):
    """Agent-role fact writes get status=pending."""
    result = await hivemem_kg_add(
        pool,
        subject="HiveMem",
        predicate="tested_by",
        object_="pytest",
        status="pending",
        created_by="test-agent",
    )
    row = await db_fetch_one(pool, "SELECT status, created_by FROM facts WHERE id = %s", (result["id"],))
    assert row["status"] == "pending"
    assert row["created_by"] == "test-agent"


# ── End-to-End Integration Tests ──────────────────────────────────────

from hivemem.tools.write import hivemem_approve_pending
from hivemem.tools.read import hivemem_pending_approvals


async def test_e2e_agent_writes_admin_approves(pool):
    """Full flow: agent writes pending, admin approves, drawer becomes active."""
    result = await hivemem_add_drawer(
        pool,
        content="Agent discovery about memory patterns",
        wing="eng", room="patterns", hall="discoveries",
        status="pending",
        created_by="archivarius",
        summary="Memory pattern observation",
    )
    drawer_id = result["id"]
    assert result["status"] == "pending"

    # Verify it's in pending approvals
    pending = await hivemem_pending_approvals(pool)
    assert any(p["id"] == drawer_id for p in pending)

    # Admin approves
    approve_result = await hivemem_approve_pending(pool, [drawer_id], "committed")
    assert approve_result["count"] == 1

    # Verify it's now active
    row = await fetch_one(pool, "SELECT status FROM drawers WHERE id = %s", (drawer_id,))
    assert row["status"] == "committed"


async def test_e2e_multiple_tokens_different_roles(pool):
    """Create tokens with different roles, validate each has correct permissions."""
    from hivemem.security import check_tool_permission

    admin_token = await create_token(pool, "e2e-admin", "admin")
    writer_token = await create_token(pool, "e2e-writer", "writer")
    reader_token = await create_token(pool, "e2e-reader", "reader")
    agent_token = await create_token(pool, "e2e-agent", "agent")

    admin_id = await validate_token(pool, admin_token)
    writer_id = await validate_token(pool, writer_token)
    reader_id = await validate_token(pool, reader_token)
    agent_id = await validate_token(pool, agent_token)

    assert admin_id["role"] == "admin"
    assert writer_id["role"] == "writer"
    assert reader_id["role"] == "reader"
    assert agent_id["role"] == "agent"

    # Admin can do everything
    assert check_tool_permission("admin", "hivemem_approve_pending") is None
    assert check_tool_permission("admin", "hivemem_health") is None

    # Writer can write but not approve
    assert check_tool_permission("writer", "hivemem_add_drawer") is None
    assert check_tool_permission("writer", "hivemem_approve_pending") is not None

    # Reader can only read
    assert check_tool_permission("reader", "hivemem_search") is None
    assert check_tool_permission("reader", "hivemem_add_drawer") is not None

    # Agent same as writer
    assert check_tool_permission("agent", "hivemem_add_drawer") is None
    assert check_tool_permission("agent", "hivemem_approve_pending") is not None


async def test_e2e_token_lifecycle(pool):
    """Create → validate → revoke → validate fails → list shows revoked."""
    plaintext = await create_token(pool, "lifecycle", "writer")

    # Active
    identity = await validate_token(pool, plaintext)
    assert identity is not None

    # Revoke
    await revoke_token(pool, "lifecycle")

    # Revoked — fails
    identity = await validate_token(pool, plaintext)
    assert identity is None

    # List shows revoked
    tokens = await list_tokens(pool)
    lifecycle_token = [t for t in tokens if t["name"] == "lifecycle"][0]
    assert lifecycle_token["status"] == "revoked"


async def test_e2e_many_tokens(pool):
    """Can create and manage many tokens."""
    names = [f"bulk-{i}" for i in range(10)]
    for name in names:
        await create_token(pool, name, "reader")

    tokens = await list_tokens(pool)
    bulk_tokens = [t for t in tokens if t["name"].startswith("bulk-")]
    assert len(bulk_tokens) == 10

    # Revoke half
    for name in names[:5]:
        await revoke_token(pool, name)

    tokens = await list_tokens(pool)
    active_bulk = [t for t in tokens if t["name"].startswith("bulk-") and t["status"] == "active"]
    revoked_bulk = [t for t in tokens if t["name"].startswith("bulk-") and t["status"] == "revoked"]
    assert len(active_bulk) == 5
    assert len(revoked_bulk) == 5


async def test_e2e_rate_limiting_still_works(pool):
    """Rate limiting works with DB-backed auth."""
    from hivemem.security import (
        check_rate_limit,
        record_failed_auth,
        clear_failed_auth,
        MAX_FAILED_ATTEMPTS,
    )

    ip = "10.0.0.99"
    clear_failed_auth(ip)

    # Record max failures
    for _ in range(MAX_FAILED_ATTEMPTS):
        record_failed_auth(ip)

    # Should be banned
    remaining = check_rate_limit(ip)
    assert remaining is not None
    assert remaining > 0

    # Cleanup
    clear_failed_auth(ip)


# ── SQL Robustness Tests ──────────────────────────────────────────────


async def test_double_revoke_raises(pool):
    """Revoking an already-revoked token raises ValueError."""
    plaintext = await create_token(pool, "double-rev", "writer")
    await revoke_token(pool, "double-rev")
    with pytest.raises(ValueError, match="already revoked"):
        await revoke_token(pool, "double-rev")


async def test_create_duplicate_is_atomic(pool):
    """Concurrent-safe: duplicate name gives ValueError, not raw DB exception."""
    await create_token(pool, "atomic-dup", "reader")
    with pytest.raises(ValueError, match="already exists"):
        await create_token(pool, "atomic-dup", "admin")


async def test_list_tokens_with_limit(pool):
    """list_tokens respects limit parameter."""
    for i in range(5):
        await create_token(pool, f"limit-{i}", "reader")
    tokens = await list_tokens(pool, limit=3)
    assert len(tokens) == 3


async def test_list_tokens_exclude_revoked(pool):
    """list_tokens can filter out revoked tokens."""
    await create_token(pool, "active-tok", "reader")
    plaintext = await create_token(pool, "revoked-tok", "reader")
    await revoke_token(pool, "revoked-tok")

    all_tokens = await list_tokens(pool, include_revoked=True)
    active_only = await list_tokens(pool, include_revoked=False)

    all_names = [t["name"] for t in all_tokens]
    active_names = [t["name"] for t in active_only]

    assert "revoked-tok" in all_names
    assert "revoked-tok" not in active_names
    assert "active-tok" in active_names


async def test_cache_evicts_at_max_size(pool):
    """Cache evicts oldest entry when CACHE_MAX_SIZE is reached."""
    from hivemem.security import AuthMiddleware

    async def noop_app(scope, receive, send):
        pass

    mw = AuthMiddleware(noop_app, pool)
    mw.CACHE_MAX_SIZE = 3  # small cap for testing

    # Create and validate 4 tokens — 4th should evict 1st
    tokens = []
    for i in range(4):
        t = await create_token(pool, f"cache-evict-{i}", "reader")
        tokens.append(t)

    # Validate all 4 through middleware cache
    for t in tokens:
        scope = {
            "type": "http",
            "headers": [(b"authorization", f"Bearer {t}".encode())],
            "client": ("127.0.0.1", 12345),
        }
        await mw(scope, lambda: {}, lambda msg: None)

    # Cache should be capped at 3
    assert len(mw._cache) == 3
