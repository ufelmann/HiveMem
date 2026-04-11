"""E2E tests for agent fleet + approval workflow."""

from hivemem.tools.write import (
    hivemem_add_drawer,
    hivemem_kg_add,
    hivemem_approve_pending,
    hivemem_register_agent,
    hivemem_diary_write,
)
from hivemem.tools.read import (
    hivemem_pending_approvals,
    hivemem_list_agents,
    hivemem_diary_read,
    hivemem_search_kg,
)
from hivemem.db import execute


async def test_register_agent(pool):
    """Register an agent."""
    result = await hivemem_register_agent(pool, "classifier", "Classify incoming drawers")
    assert result["name"] == "classifier"
    assert result["focus"] == "Classify incoming drawers"
    # Cleanup
    await execute(pool, "DELETE FROM agents WHERE name = 'classifier'")


async def test_list_agents(pool):
    """List registered agents."""
    await hivemem_register_agent(pool, "classifier", "Classify drawers")
    await hivemem_register_agent(pool, "curator", "Curate knowledge")
    agents = await hivemem_list_agents(pool)
    names = [a["name"] for a in agents]
    assert "classifier" in names
    assert "curator" in names
    await execute(pool, "DELETE FROM agents")


async def test_agent_writes_pending_drawer(pool):
    """Agent writes drawer with status=pending, appears in pending_approvals."""
    await hivemem_register_agent(pool, "classifier", "Classify drawers")
    result = await hivemem_add_drawer(
        pool, content="Agent suggestion",
        wing="eng", hall="test", room="facts",
        status="pending", created_by="classifier",
    )
    pending = await hivemem_pending_approvals(pool)
    assert any(p["id"] == result["id"] for p in pending)
    await execute(pool, "DELETE FROM drawers")
    await execute(pool, "DELETE FROM agents")


async def test_approve_drawer_becomes_active(pool):
    """Approve pending drawer -> appears in search."""
    await hivemem_register_agent(pool, "classifier", "Classify drawers")
    result = await hivemem_add_drawer(
        pool, content="Pending agent drawer about testing",
        wing="eng", hall="test", room="facts",
        status="pending", created_by="classifier",
        summary="Agent test drawer",
    )
    await hivemem_approve_pending(pool, [result["id"]], "committed")
    # Now should be findable (via direct query since search needs embedding match)
    from hivemem.db import fetch_one
    row = await fetch_one(pool, "SELECT count(*) AS cnt FROM active_drawers WHERE id = %s", (result["id"],))
    assert row["cnt"] == 1
    await execute(pool, "DELETE FROM drawers")
    await execute(pool, "DELETE FROM agents")


async def test_reject_drawer_excluded(pool):
    """Rejected drawer excluded from active_drawers."""
    result = await hivemem_add_drawer(
        pool, content="Bad suggestion",
        wing="eng", hall="test", room="facts",
        status="pending", created_by="classifier",
    )
    await hivemem_approve_pending(pool, [result["id"]], "rejected")
    from hivemem.db import fetch_one
    row = await fetch_one(pool, "SELECT count(*) AS cnt FROM active_drawers WHERE id = %s", (result["id"],))
    assert row["cnt"] == 0
    # But still exists in DB
    row = await fetch_one(pool, "SELECT status FROM drawers WHERE id = %s", (result["id"],))
    assert row["status"] == "rejected"
    await execute(pool, "DELETE FROM drawers")


async def test_agent_pending_fact(pool):
    """Agent writes fact with status=pending, approve makes it active."""
    result = await hivemem_kg_add(
        pool, "HiveMem", "uses", "pgvector",
        status="pending", created_by="classifier",
    )
    # Not in active facts
    results = await hivemem_search_kg(pool, subject="HiveMem")
    assert len(results) == 0

    await hivemem_approve_pending(pool, [result["id"]], "committed")
    results = await hivemem_search_kg(pool, subject="HiveMem")
    assert len(results) == 1
    await execute(pool, "DELETE FROM facts")


async def test_diary_write_and_read(pool):
    """Write and read agent diary entries."""
    await hivemem_register_agent(pool, "curator", "Curate knowledge")
    await hivemem_diary_write(pool, "curator", "Found 3 duplicate drawers in engineering wing")
    await hivemem_diary_write(pool, "curator", "Merged duplicates, kept most recent")

    entries = await hivemem_diary_read(pool, "curator", last_n=10)
    assert len(entries) == 2
    assert entries[0]["entry"] == "Merged duplicates, kept most recent"  # newest first
    await execute(pool, "DELETE FROM agent_diary")
    await execute(pool, "DELETE FROM agents")
