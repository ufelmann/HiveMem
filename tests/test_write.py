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
    await execute(pool, "DELETE FROM edges")
    await execute(pool, "DELETE FROM facts")
    await execute(pool, "DELETE FROM drawers")
    await execute(pool, "DELETE FROM identity")
    return pool


async def test_add_drawer(clean_pool):
    result = await hivemem_add_drawer(
        clean_pool,
        content="GraphQL API migration notes",
        wing="tech",
        room="api",
        tags=["graphql", "migration"],
    )
    assert "id" in result
    assert result["wing"] == "tech"
    assert result["room"] == "api"

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
