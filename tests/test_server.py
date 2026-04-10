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


def test_all_16_tools_registered():
    """Verify all 16 tools are registered in the MCP server."""
    from hivemem.server import mcp

    tools = mcp._tool_manager._tools
    expected_tools = [
        "hivemem_status",
        "hivemem_search",
        "hivemem_search_kg",
        "hivemem_get_drawer",
        "hivemem_list_wings",
        "hivemem_list_rooms",
        "hivemem_traverse",
        "hivemem_time_machine",
        "hivemem_wake_up",
        "hivemem_add_drawer",
        "hivemem_kg_add",
        "hivemem_kg_invalidate",
        "hivemem_update_identity",
        "hivemem_mine_file",
        "hivemem_mine_directory",
        "hivemem_health",
    ]
    registered = set(tools.keys())
    for name in expected_tools:
        assert name in registered, f"Tool '{name}' not registered"
    assert len(registered) >= 16, f"Expected at least 16 tools, got {len(registered)}: {registered}"


async def test_health(pool):
    result = await hivemem_health(pool)
    assert result["db_connected"] is True
    assert "vector" in result["extensions"]
    assert isinstance(result["drawers"], int)
    assert isinstance(result["facts"], int)
    assert isinstance(result["db_size"], str)
    assert result["disk_free_gb"] > 0
