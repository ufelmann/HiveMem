"""Tests for SQL robustness fixes."""

from hivemem.db import fetch_one, fetch_all, execute
from hivemem.tools.write import (
    hivemem_add_drawer,
    hivemem_kg_add,
    hivemem_approve_pending,
    hivemem_update_map,
)
from hivemem.tools.read import (
    hivemem_search_kg,
    hivemem_time_machine,
    hivemem_traverse,
    hivemem_status,
)


async def test_approve_pending_batch(pool):
    """approve_pending handles batch of IDs in 2 queries, not 2*N."""
    ids = []
    for i in range(5):
        r = await hivemem_add_drawer(
            pool, content=f"Pending {i}", wing="test", room="batch", hall="facts",
            status="pending", created_by="agent",
        )
        ids.append(r["id"])
    result = await hivemem_approve_pending(pool, ids, "committed")
    assert result["count"] == 5
    # Verify all committed
    for item_id in ids:
        row = await fetch_one(pool, "SELECT status FROM drawers WHERE id = %s", (item_id,))
        assert row["status"] == "committed"


async def test_search_kg_respects_limit(pool):
    """search_kg with limit returns at most that many results."""
    for i in range(10):
        await hivemem_kg_add(pool, f"Entity{i}", "has", "value", status="committed", created_by="test")
    results = await hivemem_search_kg(pool, subject="Entity", limit=3)
    assert len(results) <= 3


async def test_time_machine_respects_limit(pool):
    """time_machine with limit returns at most that many results."""
    for i in range(10):
        await hivemem_kg_add(pool, "TimeMachine", f"fact{i}", "val", status="committed", created_by="test")
    results = await hivemem_time_machine(pool, "TimeMachine", limit=3)
    assert len(results) <= 3


async def test_traverse_no_exponential_blowup(pool):
    """Traverse with UNION deduplicates rows with same edge at same depth."""
    # Create a diamond: A -> B, A -> C, B -> D, C -> D
    await execute(pool, "INSERT INTO edges (from_entity, to_entity, relation) VALUES ('A', 'B', 'links')")
    await execute(pool, "INSERT INTO edges (from_entity, to_entity, relation) VALUES ('A', 'C', 'links')")
    await execute(pool, "INSERT INTO edges (from_entity, to_entity, relation) VALUES ('B', 'D', 'links')")
    await execute(pool, "INSERT INTO edges (from_entity, to_entity, relation) VALUES ('C', 'D', 'links')")
    results = await hivemem_traverse(pool, "A", max_depth=3)
    # Should find all entities
    entities = set()
    for r in results:
        entities.add(r["from_entity"])
        entities.add(r["to_entity"])
    assert entities == {"A", "B", "C", "D"}
    # UNION deduplicates: depth 1 has A->B, A->C; depth 2 has B->D, C->D
    # With UNION ALL we'd get duplicates if same edge appears via different paths at same depth
    assert len(results) == 4
    await execute(pool, "DELETE FROM edges")


async def test_status_returns_all_fields(pool):
    """status returns consolidated counts."""
    result = await hivemem_status(pool)
    assert "drawers" in result
    assert "facts" in result
    assert "edges" in result
    assert "pending" in result
    assert "wings" in result
    assert isinstance(result["drawers"], int)


async def test_update_map_atomic(pool):
    """update_map closes old and creates new in one transaction."""
    r1 = await hivemem_update_map(pool, "test-wing", "Map v1", "First version")
    r2 = await hivemem_update_map(pool, "test-wing", "Map v2", "Second version")
    # Old map should be closed
    row = await fetch_one(pool, "SELECT valid_until FROM maps WHERE id = %s", (r1["id"],))
    assert row["valid_until"] is not None
    # New map should be active
    row = await fetch_one(pool, "SELECT valid_until FROM maps WHERE id = %s", (r2["id"],))
    assert row["valid_until"] is None
    await execute(pool, "DELETE FROM maps")
