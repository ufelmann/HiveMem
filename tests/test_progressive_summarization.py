"""E2E tests for progressive summarization (L0-L3)."""

from hivemem.tools.write import hivemem_add_drawer, hivemem_check_duplicate
from hivemem.tools.read import hivemem_get_drawer

import pytest


async def test_add_drawer_with_all_layers(pool):
    """Insert drawer with L0-L3: content, summary, key_points, insight."""
    result = await hivemem_add_drawer(
        pool,
        content="We decided to migrate BOGIS from Camunda 7 to Temporal. The main drivers are better developer experience and native Go support.",
        summary="BOGIS migrating from Camunda 7 to Temporal",
        key_points=["Camunda 7 → Temporal migration", "Better DX", "Native Go support"],
        insight="This unblocks the Go rewrite of the orchestration layer",
        actionability="actionable",
        wing="engineering", room="bogis", hall="facts",
        importance=1, source="claude-code",
    )
    assert result["id"]

    drawer = await hivemem_get_drawer(pool, result["id"])
    assert drawer["summary"] == "BOGIS migrating from Camunda 7 to Temporal"
    assert drawer["key_points"] == ["Camunda 7 → Temporal migration", "Better DX", "Native Go support"]
    assert drawer["insight"] == "This unblocks the Go rewrite of the orchestration layer"
    assert drawer["actionability"] == "actionable"


async def test_actionability_check_constraint(pool):
    """Invalid actionability value should fail."""
    with pytest.raises(Exception):
        await hivemem_add_drawer(
            pool, content="Bad actionability",
            wing="test", room="test", hall="facts",
            actionability="invalid_value",
        )


async def test_check_duplicate_finds_similar(pool):
    """check_duplicate finds existing drawer with >0.95 similarity."""
    await hivemem_add_drawer(
        pool, content="PostgreSQL vector search with pgvector extension",
        wing="eng", room="db", hall="facts", summary="pgvector search",
    )
    # Same content should be a duplicate
    dupes = await hivemem_check_duplicate(
        pool, "PostgreSQL vector search with pgvector extension", threshold=0.9
    )
    assert len(dupes) >= 1
    assert dupes[0]["similarity"] > 0.9


async def test_check_duplicate_no_match(pool):
    """check_duplicate returns empty for completely different content."""
    await hivemem_add_drawer(
        pool, content="PostgreSQL vector search",
        wing="eng", room="db", hall="facts",
    )
    dupes = await hivemem_check_duplicate(
        pool, "Cooking Italian pasta recipes for dinner tonight"
    )
    assert len(dupes) == 0


async def test_layers_optional(pool):
    """All progressive summarization fields are optional."""
    result = await hivemem_add_drawer(
        pool, content="Minimal drawer without layers",
        wing="test", room="test", hall="facts",
    )
    drawer = await hivemem_get_drawer(pool, result["id"])
    assert drawer["key_points"] == []
    assert drawer["insight"] is None
    assert drawer["actionability"] is None
