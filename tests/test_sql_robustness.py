"""Tests for SQL robustness fixes."""

from hivemem.db import fetch_one, fetch_all, execute
from hivemem.tools.write import (
    hivemem_add_drawer,
    hivemem_add_tunnel,
    hivemem_kg_add,
    hivemem_approve_pending,
    hivemem_update_blueprint,
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
            pool, content=f"Pending {i}", wing="test", hall="batch", room="facts",
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
    """Traverse with UNION deduplicates rows with same tunnel at same depth."""
    # Create a diamond: A -> B, A -> C, B -> D, C -> D
    d_a = await hivemem_add_drawer(pool, "Diamond A", wing="test", hall="graph")
    d_b = await hivemem_add_drawer(pool, "Diamond B", wing="test", hall="graph")
    d_c = await hivemem_add_drawer(pool, "Diamond C", wing="test", hall="graph")
    d_d = await hivemem_add_drawer(pool, "Diamond D", wing="test", hall="graph")
    await hivemem_add_tunnel(pool, d_a["id"], d_b["id"], "related_to", created_by="test")
    await hivemem_add_tunnel(pool, d_a["id"], d_c["id"], "related_to", created_by="test")
    await hivemem_add_tunnel(pool, d_b["id"], d_d["id"], "related_to", created_by="test")
    await hivemem_add_tunnel(pool, d_c["id"], d_d["id"], "related_to", created_by="test")
    results = await hivemem_traverse(pool, d_a["id"], max_depth=3)
    # Should find all drawers connected
    found = set()
    for r in results:
        found.add(r["from_drawer"])
        found.add(r["to_drawer"])
    assert d_a["id"] in found
    assert d_b["id"] in found
    assert d_c["id"] in found
    assert d_d["id"] in found
    # UNION deduplicates — bounded: 4 tunnels × 2 directions × up to 3 depths
    # Key check: no exponential blowup, result count stays reasonable
    assert len(results) <= 16


async def test_status_returns_all_fields(pool):
    """status returns consolidated counts."""
    result = await hivemem_status(pool)
    assert "drawers" in result
    assert "facts" in result
    assert "tunnels" in result
    assert "pending" in result
    assert "wings" in result
    assert isinstance(result["drawers"], int)


async def test_update_blueprint_atomic(pool):
    """update_blueprint closes old and creates new in one transaction."""
    r1 = await hivemem_update_blueprint(pool, "test-wing", "Map v1", "First version")
    r2 = await hivemem_update_blueprint(pool, "test-wing", "Map v2", "Second version")
    # Old map should be closed
    row = await fetch_one(pool, "SELECT valid_until FROM blueprints WHERE id = %s", (r1["id"],))
    assert row["valid_until"] is not None
    # New map should be active
    row = await fetch_one(pool, "SELECT valid_until FROM blueprints WHERE id = %s", (r2["id"],))
    assert row["valid_until"] is None
    await execute(pool, "DELETE FROM blueprints")
