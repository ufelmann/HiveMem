"""Tests for read tools."""

from datetime import datetime, timezone, timedelta

import pytest

from hivemem.db import close_pool, execute, fetch_one, get_pool
from hivemem.embeddings import encode
from hivemem.tools.read import (
    hivemem_get_drawer,
    hivemem_list_rooms,
    hivemem_list_wings,
    hivemem_search,
    hivemem_search_kg,
    hivemem_status,
    hivemem_time_machine,
    hivemem_traverse,
    hivemem_wake_up,
)


@pytest.fixture
async def pool(db_url):
    """Get a connection pool for the test DB."""
    p = await get_pool(db_url)
    yield p
    await close_pool(db_url)


@pytest.fixture
async def seeded_pool(pool):
    """Seed data for read tool tests."""
    # Clean up from previous runs
    await execute(pool, "DELETE FROM edges")
    await execute(pool, "DELETE FROM facts")
    await execute(pool, "DELETE FROM drawers")
    await execute(pool, "DELETE FROM identity")

    # Seed a drawer with a real embedding
    vector = encode("Server deployment automation and backup scripts")
    await execute(
        pool,
        """
        INSERT INTO drawers (id, content, embedding, wing, room, hall, source, tags)
        VALUES (
            'aaaaaaaa-0000-0000-0000-000000000001',
            'Server deployment automation and backup scripts',
            %s::vector, 'tech', 'infra', 'facts', 'test', ARRAY['deployment', 'backup']
        )
        """,
        (str(vector),),
    )

    # Seed a second drawer in a different wing
    vector2 = encode("Family vacation plans for summer")
    await execute(
        pool,
        """
        INSERT INTO drawers (id, content, embedding, wing, room, hall, source, tags)
        VALUES (
            'aaaaaaaa-0000-0000-0000-000000000002',
            'Family vacation plans for summer',
            %s::vector, 'personal', 'family', 'facts', 'test', ARRAY['family', 'vacation']
        )
        """,
        (str(vector2),),
    )

    # Seed facts with temporal validity
    now = datetime.now(timezone.utc)
    past = now - timedelta(days=365)
    await execute(
        pool,
        """
        INSERT INTO facts (id, subject, predicate, object, confidence, valid_from, valid_until)
        VALUES (
            'bbbbbbbb-0000-0000-0000-000000000001',
            'Alice', 'works_at', 'Acme', 1.0, %s, NULL
        )
        """,
        (past,),
    )
    await execute(
        pool,
        """
        INSERT INTO facts (id, subject, predicate, object, confidence, valid_from, valid_until)
        VALUES (
            'bbbbbbbb-0000-0000-0000-000000000002',
            'Alice', 'lives_in', 'Old City', 0.9, %s, %s
        )
        """,
        (past, past + timedelta(days=180)),
    )
    await execute(
        pool,
        """
        INSERT INTO facts (id, subject, predicate, object, confidence, valid_from, valid_until)
        VALUES (
            'bbbbbbbb-0000-0000-0000-000000000003',
            'Alice', 'lives_in', 'New City', 1.0, %s, NULL
        )
        """,
        (past + timedelta(days=180),),
    )

    # Seed two more drawers so we have 4 total for edge seeding
    vector3 = encode("Project planning and task management")
    await execute(
        pool,
        """
        INSERT INTO drawers (id, content, embedding, wing, room, hall, source, tags)
        VALUES (
            'aaaaaaaa-0000-0000-0000-000000000003',
            'Project planning and task management',
            %s::vector, 'work', 'projects', 'facts', 'test', ARRAY['planning', 'tasks']
        )
        """,
        (str(vector3),),
    )
    vector4 = encode("Health and fitness tracking notes")
    await execute(
        pool,
        """
        INSERT INTO drawers (id, content, embedding, wing, room, hall, source, tags)
        VALUES (
            'aaaaaaaa-0000-0000-0000-000000000004',
            'Health and fitness tracking notes',
            %s::vector, 'personal', 'health', 'facts', 'test', ARRAY['health', 'fitness']
        )
        """,
        (str(vector4),),
    )

    # Seed edges for graph traversal (drawer-to-drawer)
    from hivemem.tools.write import hivemem_add_edge
    from hivemem.db import fetch_all as _fa
    all_drawers = await _fa(pool, "SELECT id FROM drawers ORDER BY created_at LIMIT 4")
    if len(all_drawers) >= 2:
        await hivemem_add_edge(pool, str(all_drawers[0]["id"]), str(all_drawers[1]["id"]), "related_to", created_by="test")
    if len(all_drawers) >= 3:
        await hivemem_add_edge(pool, str(all_drawers[1]["id"]), str(all_drawers[2]["id"]), "builds_on", created_by="test")
    if len(all_drawers) >= 4:
        await hivemem_add_edge(pool, str(all_drawers[2]["id"]), str(all_drawers[3]["id"]), "builds_on", created_by="test")

    # Seed identity
    await execute(
        pool,
        """
        INSERT INTO identity (key, content, token_count) VALUES
        ('l0_identity', 'I am a personal knowledge system.', 8),
        ('l1_critical', 'Critical context and values.', 6)
        """,
    )

    return pool


async def test_status(seeded_pool):
    result = await hivemem_status(seeded_pool)
    assert result["drawers"] == 4
    assert result["facts"] >= 2  # active_facts filters by status+valid_until
    assert result["edges"] == 3
    assert "tech" in result["wings"]
    assert "personal" in result["wings"]
    assert result["last_activity"] is not None


async def test_search(seeded_pool):
    results = await hivemem_search(seeded_pool, "deployment backup", limit=5)
    assert len(results) > 0
    # The deployment drawer should be the most similar
    assert "deployment" in results[0]["content"].lower() or "backup" in results[0]["content"].lower()
    assert results[0]["score_total"] > 0


async def test_search_with_wing_filter(seeded_pool):
    results = await hivemem_search(seeded_pool, "deployment", limit=5, wing="personal")
    # Should only return drawers from 'personal' wing
    for r in results:
        assert r["wing"] == "personal"


async def test_search_kg(seeded_pool):
    results = await hivemem_search_kg(seeded_pool, subject="Alice", predicate="works_at")
    assert len(results) >= 1
    assert results[0]["subject"] == "Alice"
    assert results[0]["object"] == "Acme"


async def test_search_kg_partial(seeded_pool):
    results = await hivemem_search_kg(seeded_pool, subject="Ali")
    assert len(results) >= 1


async def test_get_drawer(seeded_pool):
    result = await hivemem_get_drawer(seeded_pool, "aaaaaaaa-0000-0000-0000-000000000001")
    assert result is not None
    assert result["wing"] == "tech"
    assert "deployment" in result["tags"]


async def test_get_drawer_not_found(seeded_pool):
    result = await hivemem_get_drawer(seeded_pool, "00000000-0000-0000-0000-000000000099")
    assert result is None


async def test_list_wings(seeded_pool):
    wings = await hivemem_list_wings(seeded_pool)
    assert len(wings) >= 2
    wing_names = [w["wing"] for w in wings]
    assert "tech" in wing_names
    assert "personal" in wing_names


async def test_list_rooms(seeded_pool):
    rooms = await hivemem_list_rooms(seeded_pool, "tech")
    assert len(rooms) >= 1
    assert rooms[0]["room"] == "infra"


async def test_traverse(seeded_pool):
    from hivemem.db import fetch_all as _fa
    all_drawers = await _fa(seeded_pool, "SELECT id FROM drawers ORDER BY created_at LIMIT 4")
    first_id = str(all_drawers[0]["id"])
    results = await hivemem_traverse(seeded_pool, first_id, max_depth=2)
    assert len(results) >= 2
    neighbors = set()
    for r in results:
        neighbors.add(r["from_drawer"])
        neighbors.add(r["to_drawer"])
    assert first_id in neighbors


async def test_traverse_depth_limit(seeded_pool):
    from hivemem.db import fetch_all as _fa
    all_drawers = await _fa(seeded_pool, "SELECT id FROM drawers ORDER BY created_at LIMIT 4")
    first_id = str(all_drawers[0]["id"])
    fourth_id = str(all_drawers[3]["id"])
    results = await hivemem_traverse(seeded_pool, first_id, max_depth=1)
    targets = [r["to_drawer"] for r in results] + [r["from_drawer"] for r in results]
    assert fourth_id not in targets


async def test_time_machine_current(seeded_pool):
    results = await hivemem_time_machine(seeded_pool, "Alice")
    # Should return only currently valid facts (valid_until IS NULL)
    subjects = [r["object"] for r in results]
    assert "Acme" in subjects
    assert "New City" in subjects
    assert "Old City" not in subjects


async def test_time_machine_past(seeded_pool):
    past_date = datetime.now(timezone.utc) - timedelta(days=300)
    results = await hivemem_time_machine(seeded_pool, "Alice", as_of=past_date)
    objects = [r["object"] for r in results]
    assert "Old City" in objects
    assert "Acme" in objects


async def test_wake_up(seeded_pool):
    result = await hivemem_wake_up(seeded_pool)
    assert "l0_identity" in result
    assert "l1_critical" in result
    assert result["l0_identity"]["content"] == "I am a personal knowledge system."
    assert result["l0_identity"]["token_count"] == 8
