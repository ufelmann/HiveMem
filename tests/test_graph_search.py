"""E2E tests for graph search and traversal."""

from hivemem.tools.write import hivemem_add_drawer, hivemem_kg_add
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
    await execute(pool, "INSERT INTO edges (from_entity, to_entity, relation) VALUES ('A', 'B', 'tunnel')")
    await execute(pool, "INSERT INTO edges (from_entity, to_entity, relation) VALUES ('A', 'C', 'hall_link')")
    await execute(pool, "INSERT INTO edges (from_entity, to_entity, relation) VALUES ('B', 'D', 'tunnel')")

    # Only tunnels
    results = await hivemem_traverse(pool, "A", max_depth=3, relation_filter="tunnel")
    entities = [r["to_entity"] for r in results]
    assert "B" in entities
    assert "D" in entities
    assert "C" not in entities
    await execute(pool, "DELETE FROM edges")


async def test_traverse_without_filter(pool):
    """Traverse without filter follows all edges."""
    await execute(pool, "INSERT INTO edges (from_entity, to_entity, relation) VALUES ('A', 'B', 'tunnel')")
    await execute(pool, "INSERT INTO edges (from_entity, to_entity, relation) VALUES ('A', 'C', 'hall_link')")

    results = await hivemem_traverse(pool, "A", max_depth=2)
    entities = [r["to_entity"] for r in results]
    assert "B" in entities
    assert "C" in entities
    await execute(pool, "DELETE FROM edges")


async def test_traverse_depth_limit(pool):
    """Traverse respects depth limit."""
    await execute(pool, "INSERT INTO edges (from_entity, to_entity, relation) VALUES ('A', 'B', 'tunnel')")
    await execute(pool, "INSERT INTO edges (from_entity, to_entity, relation) VALUES ('B', 'C', 'tunnel')")
    await execute(pool, "INSERT INTO edges (from_entity, to_entity, relation) VALUES ('C', 'D', 'tunnel')")

    results = await hivemem_traverse(pool, "A", max_depth=2)
    entities = [r["to_entity"] for r in results]
    assert "B" in entities
    assert "C" in entities
    assert "D" not in entities  # depth 3, limit is 2
    await execute(pool, "DELETE FROM edges")
