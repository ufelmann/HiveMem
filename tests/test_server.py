"""Tests for MCP server tool registration and admin tool."""

import pytest

from hivemem.db import close_pool, execute, get_pool
from hivemem.tools.admin import hivemem_health


@pytest.fixture
async def pool(db_url):
    """Get a connection pool for the test DB."""
    p = await get_pool(db_url)
    yield p
    await close_pool(db_url)


def test_all_tools_registered():
    """Verify all tools are registered in the MCP server."""
    from hivemem.server import mcp

    tools = mcp._tool_manager._tools
    key_tools = [
        "hivemem_status",
        "hivemem_search",
        "hivemem_health",
        "hivemem_add_drawer",
        "hivemem_wake_up",
    ]
    registered = set(tools.keys())
    for name in key_tools:
        assert name in registered, f"Tool '{name}' not registered"
    assert len(registered) >= 36, f"Expected at least 36 tools, got {len(registered)}: {registered}"


async def test_health(pool):
    result = await hivemem_health(pool)
    assert result["db_connected"] is True
    assert "vector" in result["extensions"]
    assert isinstance(result["drawers"], int)
    assert isinstance(result["facts"], int)
    assert isinstance(result["db_size"], str)
    assert result["disk_free_gb"] > 0
