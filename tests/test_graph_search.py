"""E2E tests for graph search and traversal."""

from hivemem.tools.write import hivemem_add_drawer, hivemem_add_edge, hivemem_kg_add
from hivemem.tools.read import hivemem_quick_facts, hivemem_traverse
from hivemem.db import execute


async def test_quick_facts_as_subject(pool):
    """quick_facts finds facts where entity is subject."""
    await hivemem_kg_add(pool, "HiveMem", "uses", "PostgreSQL")
    await hivemem_kg_add(pool, "HiveMem", "uses", "pgvector")
    facts = await hivemem_quick_facts(pool, "HiveMem")
    assert len(facts) == 2
    preds = [f["object"] for f in facts]
    assert "PostgreSQL" in preds
    assert "pgvector" in preds
    await execute(pool, "DELETE FROM facts")


async def test_quick_facts_as_object(pool):
    """quick_facts finds facts where entity is object."""
    await hivemem_kg_add(pool, "Viktor", "created", "HiveMem")
    facts = await hivemem_quick_facts(pool, "HiveMem")
    assert len(facts) == 1
    assert facts[0]["subject"] == "Viktor"
    await execute(pool, "DELETE FROM facts")


async def test_quick_facts_empty(pool):
    """quick_facts returns empty for unknown entity."""
    facts = await hivemem_quick_facts(pool, "NonExistent")
    assert len(facts) == 0


async def test_traverse_with_relation_filter(pool):
    """Traverse with relation_filter only follows matching edges."""
    d_a = await hivemem_add_drawer(pool, "Node A", wing="test", room="graph")
    d_b = await hivemem_add_drawer(pool, "Node B", wing="test", room="graph")
    d_c = await hivemem_add_drawer(pool, "Node C", wing="test", room="graph")
    d_d = await hivemem_add_drawer(pool, "Node D", wing="test", room="graph")
    await hivemem_add_edge(pool, d_a["id"], d_b["id"], "builds_on", created_by="test")
    await hivemem_add_edge(pool, d_a["id"], d_c["id"], "related_to", created_by="test")
    await hivemem_add_edge(pool, d_b["id"], d_d["id"], "builds_on", created_by="test")

    results = await hivemem_traverse(pool, d_a["id"], max_depth=3, relation_filter="builds_on")
    targets = [r["to_drawer"] for r in results]
    assert d_b["id"] in targets
    assert d_d["id"] in targets
    assert d_c["id"] not in targets


async def test_traverse_without_filter(pool):
    """Traverse without filter follows all edges."""
    d_a = await hivemem_add_drawer(pool, "Node A2", wing="test", room="graph")
    d_b = await hivemem_add_drawer(pool, "Node B2", wing="test", room="graph")
    d_c = await hivemem_add_drawer(pool, "Node C2", wing="test", room="graph")
    await hivemem_add_edge(pool, d_a["id"], d_b["id"], "builds_on", created_by="test")
    await hivemem_add_edge(pool, d_a["id"], d_c["id"], "related_to", created_by="test")

    results = await hivemem_traverse(pool, d_a["id"], max_depth=2)
    targets = [r["to_drawer"] for r in results]
    assert d_b["id"] in targets
    assert d_c["id"] in targets


async def test_traverse_depth_limit(pool):
    """Traverse respects depth limit."""
    d_a = await hivemem_add_drawer(pool, "Node A3", wing="test", room="graph")
    d_b = await hivemem_add_drawer(pool, "Node B3", wing="test", room="graph")
    d_c = await hivemem_add_drawer(pool, "Node C3", wing="test", room="graph")
    d_d = await hivemem_add_drawer(pool, "Node D3", wing="test", room="graph")
    await hivemem_add_edge(pool, d_a["id"], d_b["id"], "builds_on", created_by="test")
    await hivemem_add_edge(pool, d_b["id"], d_c["id"], "builds_on", created_by="test")
    await hivemem_add_edge(pool, d_c["id"], d_d["id"], "builds_on", created_by="test")

    results = await hivemem_traverse(pool, d_a["id"], max_depth=2)
    targets = [r["to_drawer"] for r in results]
    assert d_b["id"] in targets
    assert d_c["id"] in targets
    assert d_d["id"] not in targets  # depth 3, limit is 2


async def test_traverse_bidirectional(pool):
    """Traverse finds backlinks (edges pointing TO the starting drawer)."""
    d_a = await hivemem_add_drawer(pool, "Node A4", wing="test", room="graph")
    d_b = await hivemem_add_drawer(pool, "Node B4", wing="test", room="graph")
    # B builds_on A — traversing from A should find B as a backlink
    await hivemem_add_edge(pool, d_b["id"], d_a["id"], "builds_on", created_by="test")

    results = await hivemem_traverse(pool, d_a["id"], max_depth=1)
    assert len(results) >= 1
    # Should find the edge connecting to B
    found_drawers = set()
    for r in results:
        found_drawers.add(r["from_drawer"])
        found_drawers.add(r["to_drawer"])
    assert d_b["id"] in found_drawers


async def test_traverse_ignores_removed_edges(pool):
    """Traverse does not follow soft-deleted edges."""
    d_a = await hivemem_add_drawer(pool, "Node A5", wing="test", room="graph")
    d_b = await hivemem_add_drawer(pool, "Node B5", wing="test", room="graph")
    from hivemem.tools.write import hivemem_remove_edge
    edge = await hivemem_add_edge(pool, d_a["id"], d_b["id"], "related_to", created_by="test")
    await hivemem_remove_edge(pool, edge["id"])

    results = await hivemem_traverse(pool, d_a["id"], max_depth=1)
    assert len(results) == 0


async def test_traverse_ignores_pending_edges(pool):
    """Traverse does not follow pending edges."""
    d_a = await hivemem_add_drawer(pool, "Node A6", wing="test", room="graph")
    d_b = await hivemem_add_drawer(pool, "Node B6", wing="test", room="graph")
    await hivemem_add_edge(pool, d_a["id"], d_b["id"], "related_to", status="pending", created_by="agent")

    results = await hivemem_traverse(pool, d_a["id"], max_depth=1)
    assert len(results) == 0
