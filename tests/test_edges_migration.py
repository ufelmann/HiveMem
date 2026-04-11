"""Test that the edges schema is correct."""

from hivemem.db import fetch_one, fetch_all, execute
import pytest


async def test_edges_has_new_columns(pool):
    """New edges schema has from_drawer column."""
    row = await fetch_one(
        pool,
        "SELECT column_name FROM information_schema.columns WHERE table_name = 'edges' AND column_name = 'from_drawer'",
    )
    assert row is not None, "New edges schema should have from_drawer column"


async def test_edges_fk_constraint(pool):
    """Edges require valid drawer UUIDs (FK constraint)."""
    with pytest.raises(Exception):
        await execute(
            pool,
            "INSERT INTO edges (from_drawer, to_drawer, relation, created_by) VALUES (%s, %s, 'related_to', 'test')",
            ("00000000-0000-0000-0000-000000000001", "00000000-0000-0000-0000-000000000002"),
        )


async def test_edges_relation_check_constraint(pool):
    """Only allowed relation types pass the CHECK constraint."""
    from hivemem.tools.write import hivemem_add_drawer
    d1 = await hivemem_add_drawer(pool, "Migration test A", wing="test")
    d2 = await hivemem_add_drawer(pool, "Migration test B", wing="test")
    with pytest.raises(Exception):
        await execute(
            pool,
            "INSERT INTO edges (from_drawer, to_drawer, relation, created_by) VALUES (%s, %s, 'invalid', 'test')",
            (d1["id"], d2["id"]),
        )


async def test_edges_status_check_constraint(pool):
    """Only allowed status values pass the CHECK constraint."""
    from hivemem.tools.write import hivemem_add_drawer
    d1 = await hivemem_add_drawer(pool, "Migration test C", wing="test")
    d2 = await hivemem_add_drawer(pool, "Migration test D", wing="test")
    with pytest.raises(Exception):
        await execute(
            pool,
            "INSERT INTO edges (from_drawer, to_drawer, relation, status, created_by) VALUES (%s, %s, 'related_to', 'invalid', 'test')",
            (d1["id"], d2["id"]),
        )


async def test_active_edges_view_filters(pool):
    """active_edges only shows committed edges without valid_until."""
    from hivemem.tools.write import hivemem_add_drawer, hivemem_add_edge, hivemem_remove_edge
    d1 = await hivemem_add_drawer(pool, "View test A", wing="test")
    d2 = await hivemem_add_drawer(pool, "View test B", wing="test")
    d3 = await hivemem_add_drawer(pool, "View test C", wing="test")

    # Committed edge — should appear
    e1 = await hivemem_add_edge(pool, d1["id"], d2["id"], "related_to", created_by="test")
    # Pending edge — should NOT appear
    await hivemem_add_edge(pool, d1["id"], d3["id"], "builds_on", status="pending", created_by="agent")
    # Removed edge — should NOT appear
    e3 = await hivemem_add_edge(pool, d2["id"], d3["id"], "refines", created_by="test")
    await hivemem_remove_edge(pool, e3["id"])

    active = await fetch_all(pool, "SELECT id FROM active_edges")
    active_ids = [str(r["id"]) for r in active]
    assert e1["id"] in active_ids
    assert e3["id"] not in active_ids


async def test_partial_indexes_exist(pool):
    """Partial indexes on edges table exist."""
    rows = await fetch_all(
        pool,
        """SELECT indexname FROM pg_indexes
           WHERE tablename = 'edges'
           ORDER BY indexname""",
    )
    index_names = [r["indexname"] for r in rows]
    assert "idx_edges_from" in index_names
    assert "idx_edges_to" in index_names
    assert "idx_edges_relation" in index_names
    assert "idx_edges_temporal" in index_names
    assert "idx_edges_status" in index_names


async def test_existing_drawers_untouched(pool):
    """Migration doesn't affect existing drawers."""
    from hivemem.tools.write import hivemem_add_drawer
    d = await hivemem_add_drawer(pool, "Untouched drawer", wing="test")
    row = await fetch_one(pool, "SELECT id FROM drawers WHERE id = %s", (d["id"],))
    assert row is not None
