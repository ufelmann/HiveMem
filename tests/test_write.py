"""Tests for write tools."""

import pytest

from hivemem.db import close_pool, execute, fetch_one, get_pool
from hivemem.tools.write import (
    hivemem_add_drawer,
    hivemem_kg_add,
    hivemem_kg_invalidate,
    hivemem_update_identity,
)


@pytest.fixture
async def pool(db_url):
    """Get a connection pool for the test DB."""
    p = await get_pool(db_url)
    yield p
    await close_pool(db_url)


@pytest.fixture
async def clean_pool(pool):
    """Clean tables before write tests."""
    await execute(pool, "DELETE FROM tunnels")
    await execute(pool, "DELETE FROM facts")
    await execute(pool, "DELETE FROM drawers")
    await execute(pool, "DELETE FROM identity")
    return pool


async def test_add_drawer(clean_pool):
    result = await hivemem_add_drawer(
        clean_pool,
        content="GraphQL API migration notes",
        wing="tech",
        hall="api",
        tags=["graphql", "migration"],
    )
    assert "id" in result
    assert result["wing"] == "tech"
    assert result["hall"] == "api"

    # Verify it was actually stored with an embedding
    row = await fetch_one(
        clean_pool,
        "SELECT content, embedding, tags FROM drawers WHERE id = %s",
        (result["id"],),
    )
    assert row["content"] == "GraphQL API migration notes"
    assert row["embedding"] is not None
    assert "graphql" in row["tags"]


async def test_add_drawer_minimal(clean_pool):
    result = await hivemem_add_drawer(clean_pool, content="Just some text")
    assert "id" in result
    assert result["wing"] is None


async def test_kg_add(clean_pool):
    result = await hivemem_kg_add(
        clean_pool,
        subject="Python",
        predicate="is_a",
        object_="programming language",
        confidence=1.0,
    )
    assert "id" in result
    assert result["subject"] == "Python"
    assert result["predicate"] == "is_a"
    assert result["object"] == "programming language"


async def test_kg_add_with_source(clean_pool):
    drawer = await hivemem_add_drawer(clean_pool, content="Source doc")
    result = await hivemem_kg_add(
        clean_pool,
        subject="Fact",
        predicate="from",
        object_="source doc",
        source_id=drawer["id"],
    )
    assert "id" in result

    row = await fetch_one(
        clean_pool,
        "SELECT source_id FROM facts WHERE id = %s",
        (result["id"],),
    )
    assert str(row["source_id"]) == drawer["id"]


async def test_kg_invalidate(clean_pool):
    fact = await hivemem_kg_add(
        clean_pool,
        subject="Alice",
        predicate="lives_in",
        object_="Old City",
    )
    result = await hivemem_kg_invalidate(clean_pool, fact["id"])
    assert result["invalidated"] is True

    row = await fetch_one(
        clean_pool,
        "SELECT valid_until FROM facts WHERE id = %s",
        (fact["id"],),
    )
    assert row["valid_until"] is not None


async def test_update_identity_insert(clean_pool):
    result = await hivemem_update_identity(
        clean_pool, key="l0_identity", content="I am HiveMem."
    )
    assert result["key"] == "l0_identity"
    assert result["token_count"] == len("I am HiveMem.") // 4

    row = await fetch_one(
        clean_pool,
        "SELECT content, token_count FROM identity WHERE key = %s",
        ("l0_identity",),
    )
    assert row["content"] == "I am HiveMem."


async def test_update_identity_upsert(clean_pool):
    await hivemem_update_identity(clean_pool, key="l0_identity", content="Version 1")
    result = await hivemem_update_identity(
        clean_pool, key="l0_identity", content="Version 2 updated"
    )
    assert result["token_count"] == len("Version 2 updated") // 4

    row = await fetch_one(
        clean_pool,
        "SELECT content FROM identity WHERE key = %s",
        ("l0_identity",),
    )
    assert row["content"] == "Version 2 updated"


async def test_add_tunnel(pool):
    """add_tunnel creates a drawer-to-drawer link."""
    d1 = await hivemem_add_drawer(pool, "Drawer A about Python", wing="eng", hall="code")
    d2 = await hivemem_add_drawer(pool, "Drawer B about Python testing", wing="eng", hall="code")
    from hivemem.tools.write import hivemem_add_tunnel
    result = await hivemem_add_tunnel(pool, d1["id"], d2["id"], "builds_on", note="B extends A", created_by="test")
    assert result["from_drawer"] == d1["id"]
    assert result["to_drawer"] == d2["id"]
    assert result["relation"] == "builds_on"
    assert result["note"] == "B extends A"
    assert result["status"] == "committed"


async def test_add_tunnel_agent_forces_pending(pool):
    """add_tunnel with status override for agent role."""
    d1 = await hivemem_add_drawer(pool, "Drawer C", wing="eng", hall="code")
    d2 = await hivemem_add_drawer(pool, "Drawer D", wing="eng", hall="code")
    from hivemem.tools.write import hivemem_add_tunnel
    result = await hivemem_add_tunnel(pool, d1["id"], d2["id"], "related_to", status="pending", created_by="agent-bot")
    assert result["status"] == "pending"


async def test_add_tunnel_invalid_relation(pool):
    """add_tunnel rejects invalid relation type."""
    d1 = await hivemem_add_drawer(pool, "Drawer E", wing="eng", hall="code")
    d2 = await hivemem_add_drawer(pool, "Drawer F", wing="eng", hall="code")
    from hivemem.tools.write import hivemem_add_tunnel
    with pytest.raises(Exception):
        await hivemem_add_tunnel(pool, d1["id"], d2["id"], "invalid_relation", created_by="test")


async def test_remove_tunnel(pool):
    """remove_tunnel soft-deletes by setting valid_until."""
    d1 = await hivemem_add_drawer(pool, "Drawer G", wing="eng", hall="code")
    d2 = await hivemem_add_drawer(pool, "Drawer H", wing="eng", hall="code")
    from hivemem.tools.write import hivemem_add_tunnel, hivemem_remove_tunnel
    tunnel = await hivemem_add_tunnel(pool, d1["id"], d2["id"], "related_to", created_by="test")
    result = await hivemem_remove_tunnel(pool, tunnel["id"])
    assert result["removed"] is True
    # Verify tunnel is gone from active_tunnels
    from hivemem.db import fetch_all
    active = await fetch_all(pool, "SELECT * FROM active_tunnels WHERE id = %s", (tunnel["id"],))
    assert len(active) == 0


async def test_remove_tunnel_idempotent(pool):
    """remove_tunnel on already-removed tunnel succeeds."""
    d1 = await hivemem_add_drawer(pool, "Drawer I", wing="eng", hall="code")
    d2 = await hivemem_add_drawer(pool, "Drawer J", wing="eng", hall="code")
    from hivemem.tools.write import hivemem_add_tunnel, hivemem_remove_tunnel
    tunnel = await hivemem_add_tunnel(pool, d1["id"], d2["id"], "refines", created_by="test")
    await hivemem_remove_tunnel(pool, tunnel["id"])
    result = await hivemem_remove_tunnel(pool, tunnel["id"])
    assert result["removed"] is True


async def test_approve_pending_tunnels(pool):
    """approve_pending handles tunnel IDs alongside drawers/facts."""
    d1 = await hivemem_add_drawer(pool, "Drawer K", wing="eng", hall="code")
    d2 = await hivemem_add_drawer(pool, "Drawer L", wing="eng", hall="code")
    from hivemem.tools.write import hivemem_add_tunnel, hivemem_approve_pending
    tunnel = await hivemem_add_tunnel(pool, d1["id"], d2["id"], "related_to", status="pending", created_by="agent")
    assert tunnel["status"] == "pending"
    result = await hivemem_approve_pending(pool, [tunnel["id"]], "committed")
    assert result["count"] == 1
    # Verify tunnel is now in active_tunnels
    from hivemem.db import fetch_all
    active = await fetch_all(pool, "SELECT * FROM active_tunnels WHERE id = %s", (tunnel["id"],))
    assert len(active) == 1
