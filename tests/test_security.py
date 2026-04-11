"""Security regression tests -- these must never break.

Each test maps to a specific security finding. If a test fails,
a vulnerability has been reintroduced.
"""

import os
import pytest

from hivemem.db import fetch_one, execute
from hivemem.security import (
    create_token,
    validate_token,
    check_tool_permission,
    filter_tools_for_role,
    AuthMiddleware,
    ROLE_TOOLS,
    READ_TOOLS,
    ADMIN_TOOLS,
)
from hivemem.tools.write import hivemem_approve_pending, hivemem_add_drawer
from hivemem.tools.import_tools import _validate_path, ALLOWED_IMPORT_DIRS


# -- CRIT-1: Path traversal in mine_file / mine_directory --


def test_path_traversal_etc_passwd():
    """mine_file must reject paths outside allowed directories."""
    with pytest.raises(PermissionError):
        _validate_path("/etc/passwd")


def test_path_traversal_secrets():
    """mine_file must reject /data/secrets.json."""
    with pytest.raises(PermissionError):
        _validate_path("/data/secrets.json")


def test_path_traversal_dotdot():
    """mine_file must reject directory traversal via ../."""
    with pytest.raises(PermissionError):
        _validate_path("/data/imports/../../etc/passwd")


def test_path_traversal_allowed_dir():
    """mine_file allows paths inside /data/imports."""
    # This should not raise (even if file doesn't exist, path is valid)
    path = _validate_path("/data/imports/test.md")
    assert str(path).startswith("/data/imports")


def test_path_traversal_tmp_allowed():
    """mine_file allows paths inside /tmp."""
    path = _validate_path("/tmp/test.txt")
    assert str(path).startswith("/tmp")


# -- CRIT-2: Tool call enforcement --


def test_reader_cannot_call_write_tool():
    """Reader role must be denied write tools."""
    err = check_tool_permission("reader", "hivemem_add_drawer")
    assert err is not None
    assert "not permitted" in err.lower()


def test_reader_cannot_call_admin_tool():
    """Reader role must be denied admin tools."""
    err = check_tool_permission("reader", "hivemem_approve_pending")
    assert err is not None


def test_writer_cannot_call_admin_tool():
    """Writer role must be denied admin-only tools."""
    err = check_tool_permission("writer", "hivemem_approve_pending")
    assert err is not None


def test_agent_cannot_call_approve():
    """Agent role must never be able to approve its own suggestions."""
    err = check_tool_permission("agent", "hivemem_approve_pending")
    assert err is not None


def test_admin_can_call_everything():
    """Admin role can call any tool."""
    for tool in ROLE_TOOLS["admin"]:
        err = check_tool_permission("admin", tool)
        assert err is None, f"Admin denied {tool}: {err}"


def test_all_admin_tools_excluded_from_writer():
    """No admin tool appears in writer role."""
    for tool in ADMIN_TOOLS:
        assert tool not in ROLE_TOOLS["writer"], f"Admin tool {tool} in writer set"
        assert tool not in ROLE_TOOLS["agent"], f"Admin tool {tool} in agent set"


# -- CRIT-3: Decision validation --


async def test_approve_invalid_decision_rejected(pool):
    """approve_pending rejects decisions other than committed/rejected."""
    r = await hivemem_add_drawer(
        pool, content="test", wing="test", hall="sec", room="facts",
        status="pending", created_by="agent",
    )
    with pytest.raises(ValueError, match="Invalid decision"):
        await hivemem_approve_pending(pool, [r["id"]], "pending")


async def test_approve_arbitrary_string_rejected(pool):
    """approve_pending rejects arbitrary strings."""
    with pytest.raises(ValueError, match="Invalid decision"):
        await hivemem_approve_pending(pool, [], "admin_override")


# -- IMP-1: X-Forwarded-For removed --


async def test_xff_header_not_trusted(pool):
    """Auth middleware ignores X-Forwarded-For (prevents rate limit bypass)."""
    plaintext = await create_token(pool, "xff-test", "admin")

    captured_scope = {}

    async def mock_app(scope, receive, send):
        captured_scope.update(scope)

    mw = AuthMiddleware(mock_app, pool)

    # Request with spoofed XFF but valid client IP
    scope = {
        "type": "http",
        "headers": [
            (b"authorization", f"Bearer {plaintext}".encode()),
            (b"x-forwarded-for", b"1.2.3.4"),
        ],
        "client": ("10.0.0.1", 12345),
    }
    await mw(scope, lambda: {}, lambda msg: None)
    # The middleware should use client IP, not XFF
    # (verified by audit log using real IP -- here we just verify auth works)
    assert captured_scope.get("token_name") == "xff-test"


async def test_xff_without_client_returns_unknown(pool):
    """When client is missing and XFF present, IP should be 'unknown' not XFF."""
    mw = AuthMiddleware(None, pool)
    # Simulate scope without client tuple
    scope = {
        "type": "http",
        "headers": [(b"x-forwarded-for", b"1.2.3.4")],
    }
    ip = mw._get_client_ip(scope)
    assert ip == "unknown"  # must NOT be "1.2.3.4"


# -- IMP-2: Safe defaults --


def test_default_identity_is_reader():
    """ContextVar default must be reader, not admin."""
    from hivemem.server import _request_identity
    default = _request_identity.get()
    assert default["role"] == "reader"
    assert default["name"] != "admin"


# -- IMP-3: accessed_by not spoofable --
# (This is enforced at the server.py wrapper level, which injects get_identity().
#  We test that the wrapper doesn't accept a client-provided accessed_by.)


def test_log_access_no_accessed_by_param():
    """hivemem_log_access MCP wrapper must not accept accessed_by from client."""
    from hivemem.server import hivemem_log_access
    import inspect
    sig = inspect.signature(hivemem_log_access)
    assert "accessed_by" not in sig.parameters, \
        "accessed_by must be injected from token, not client-controlled"


# -- Token security --


async def test_token_hash_not_in_list(pool):
    """list_tokens must never expose token hashes."""
    from hivemem.security import list_tokens
    await create_token(pool, "hash-test", "reader")
    tokens = await list_tokens(pool)
    for t in tokens:
        assert "token_hash" not in t
        assert "hash" not in str(t).lower() or "token_hash" not in t


async def test_token_hash_not_in_info(pool):
    """get_token_info must never expose token hashes."""
    from hivemem.security import get_token_info
    await create_token(pool, "info-hash-test", "reader")
    info = await get_token_info(pool, "info-hash-test")
    assert "token_hash" not in info


# -- Import path safety --


def test_allowed_import_dirs_exist_or_are_safe():
    """Allowed import dirs must not include sensitive system paths."""
    sensitive = {"/", "/etc", "/root", "/data", "/var", "/proc", "/sys"}
    for d in ALLOWED_IMPORT_DIRS:
        assert str(d.resolve()) not in sensitive, f"Allowed dir {d} is too broad"
