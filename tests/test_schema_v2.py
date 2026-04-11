"""E2E tests for schema v2 append-only operations."""

from hivemem.db import get_pool, fetch_one, fetch_all, execute
from hivemem.tools.write import (
    hivemem_add_drawer,
    hivemem_kg_add,
    hivemem_kg_invalidate,
    hivemem_revise_drawer,
    hivemem_revise_fact,
    hivemem_check_contradiction,
    hivemem_approve_pending,
)
from hivemem.tools.read import (
    hivemem_status,
    hivemem_drawer_history,
    hivemem_fact_history,
    hivemem_pending_approvals,
    hivemem_search_kg,
    hivemem_list_wings,
)

import pytest


# pool fixture is defined in conftest.py


async def test_add_drawer_with_new_columns(pool):
    """Insert drawer with importance, summary, hall, status, created_by."""
    result = await hivemem_add_drawer(
        pool, content="Test content",
        wing="engineering", hall="auth", room="facts",
        importance=2, summary="Test summary",
        status="committed", created_by="user",
    )
    assert result["wing"] == "engineering"
    assert result["room"] == "facts"
    assert result["status"] == "committed"


async def test_room_check_constraint(pool):
    """Invalid room value should fail."""
    with pytest.raises(Exception):
        await hivemem_add_drawer(
            pool, content="Bad hall",
            wing="test", hall="test", room="invalid_room",
        )


async def test_drawer_appears_in_active_view(pool):
    """Committed drawer appears in active_drawers."""
    await hivemem_add_drawer(pool, content="Active", wing="test", hall="test", room="facts")
    row = await fetch_one(pool, "SELECT count(*) AS cnt FROM active_drawers WHERE wing = 'test'")
    assert row["cnt"] == 1


async def test_pending_drawer_not_in_active_view(pool):
    """Pending drawer does NOT appear in active_drawers."""
    await hivemem_add_drawer(
        pool, content="Pending", wing="test", hall="test", room="facts",
        status="pending", created_by="classifier",
    )
    row = await fetch_one(pool, "SELECT count(*) AS cnt FROM active_drawers WHERE wing = 'test'")
    assert row["cnt"] == 0


async def test_pending_approvals_view(pool):
    """Pending items appear in pending_approvals."""
    await hivemem_add_drawer(
        pool, content="Needs approval", wing="test", hall="test", room="facts",
        summary="Test pending", status="pending", created_by="classifier",
    )
    pending = await hivemem_pending_approvals(pool)
    assert len(pending) >= 1
    assert pending[0]["type"] == "drawer"
    assert pending[0]["created_by"] == "classifier"


async def test_approve_pending(pool):
    """Approve pending drawer -> appears in active_drawers."""
    result = await hivemem_add_drawer(
        pool, content="To approve", wing="test", hall="test", room="facts",
        status="pending", created_by="classifier",
    )
    # Not in active yet
    row = await fetch_one(pool, "SELECT count(*) AS cnt FROM active_drawers WHERE id = %s", (result["id"],))
    assert row["cnt"] == 0

    # Approve
    await hivemem_approve_pending(pool, [result["id"]], "committed")

    # Now in active
    row = await fetch_one(pool, "SELECT count(*) AS cnt FROM active_drawers WHERE id = %s", (result["id"],))
    assert row["cnt"] == 1


async def test_revise_drawer(pool):
    """Revise drawer: old gets valid_until, new has parent_id."""
    original = await hivemem_add_drawer(
        pool, content="Version 1", wing="test", hall="test", room="facts",
        summary="V1", importance=2,
    )
    revised = await hivemem_revise_drawer(pool, original["id"], "Version 2", new_summary="V2")

    # Old drawer has valid_until
    old = await fetch_one(pool, "SELECT valid_until FROM drawers WHERE id = %s", (original["id"],))
    assert old["valid_until"] is not None

    # New drawer has parent_id
    new = await fetch_one(pool, "SELECT parent_id, summary, importance FROM drawers WHERE id = %s", (revised["new_id"],))
    assert str(new["parent_id"]) == original["id"]
    assert new["summary"] == "V2"
    assert new["importance"] == 2  # inherited

    # active_drawers only shows new
    rows = await fetch_all(pool, "SELECT id FROM active_drawers WHERE wing = 'test'")
    ids = [str(r["id"]) for r in rows]
    assert revised["new_id"] in ids
    assert original["id"] not in ids


async def test_drawer_history(pool):
    """drawer_history returns version chain."""
    v1 = await hivemem_add_drawer(pool, content="V1", wing="test", hall="test", room="facts", summary="V1")
    v2 = await hivemem_revise_drawer(pool, v1["id"], "V2", new_summary="V2")

    history = await hivemem_drawer_history(pool, v2["new_id"])
    assert len(history) == 2
    assert history[0]["summary"] == "V1"  # oldest first
    assert history[1]["summary"] == "V2"


async def test_revise_fact(pool):
    """Revise fact: old gets valid_until, new has parent_id and new object."""
    original = await hivemem_kg_add(pool, "BOGIS", "uses", "Camunda7")
    revised = await hivemem_revise_fact(pool, original["id"], "Temporal")

    # Old fact has valid_until
    old = await fetch_one(pool, "SELECT valid_until FROM facts WHERE id = %s", (original["id"],))
    assert old["valid_until"] is not None

    # New fact has parent_id and new object
    new = await fetch_one(pool, "SELECT parent_id, object FROM facts WHERE id = %s", (revised["new_id"],))
    assert str(new["parent_id"]) == original["id"]
    assert new["object"] == "Temporal"


async def test_fact_history(pool):
    """fact_history returns version chain."""
    v1 = await hivemem_kg_add(pool, "BOGIS", "uses", "Camunda7")
    v2 = await hivemem_revise_fact(pool, v1["id"], "Temporal")

    history = await hivemem_fact_history(pool, v2["new_id"])
    assert len(history) == 2
    assert history[0]["object"] == "Camunda7"
    assert history[1]["object"] == "Temporal"


async def test_check_contradiction(pool):
    """check_contradiction finds conflicting active facts."""
    await hivemem_kg_add(pool, "BOGIS", "uses", "Camunda7")
    contradictions = await hivemem_check_contradiction(pool, "BOGIS", "uses", "Temporal")
    assert len(contradictions) == 1
    assert contradictions[0]["existing_object"] == "Camunda7"


async def test_check_contradiction_none(pool):
    """No contradiction when no conflicting fact exists."""
    contradictions = await hivemem_check_contradiction(pool, "NewEntity", "uses", "Something")
    assert len(contradictions) == 0


async def test_invalidate_fact(pool):
    """Invalidated fact disappears from active_facts."""
    fact = await hivemem_kg_add(pool, "Alice", "works_at", "Acme")
    # Visible in active
    results = await hivemem_search_kg(pool, subject="Alice")
    assert len(results) == 1

    await hivemem_kg_invalidate(pool, fact["id"])

    # Gone from active
    results = await hivemem_search_kg(pool, subject="Alice")
    assert len(results) == 0


async def test_wing_stats(pool):
    """wing_stats returns correct counts from active drawers."""
    await hivemem_add_drawer(pool, content="D1", wing="eng", hall="auth", room="facts")
    await hivemem_add_drawer(pool, content="D2", wing="eng", hall="auth", room="events")
    await hivemem_add_drawer(pool, content="D3", wing="eng", hall="infra", room="facts")

    wings = await hivemem_list_wings(pool)
    eng = next(w for w in wings if w["wing"] == "eng")
    assert eng["drawer_count"] == 3
    assert eng["hall_count"] == 2


async def test_status_includes_pending_count(pool):
    """Status shows pending count."""
    await hivemem_add_drawer(pool, content="Pending", wing="test", hall="test", room="facts", status="pending")
    status = await hivemem_status(pool)
    assert status["pending"] >= 1
