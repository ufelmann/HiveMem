"""Cross-feature integration tests."""

from hivemem.tools.write import (
    hivemem_add_drawer,
    hivemem_kg_add,
    hivemem_kg_invalidate,
    hivemem_revise_drawer,
    hivemem_revise_fact,
    hivemem_check_contradiction,
    hivemem_check_duplicate,
    hivemem_approve_pending,
    hivemem_register_agent,
    hivemem_diary_write,
    hivemem_add_reference,
    hivemem_link_reference,
    hivemem_update_map,
)
from hivemem.tools.read import (
    hivemem_search,
    hivemem_get_drawer,
    hivemem_pending_approvals,
    hivemem_quick_facts,
    hivemem_log_access,
    hivemem_refresh_popularity,
    hivemem_get_map,
    hivemem_reading_list,
    hivemem_time_machine,
)
from hivemem.tools.import_tools import hivemem_mine_file
from hivemem.db import execute

import os
import tempfile


async def test_revise_drawer_preserves_progressive_summarization(pool):
    """BUG FIX: Revising a drawer must carry forward key_points, insight, actionability."""
    original = await hivemem_add_drawer(
        pool,
        content="Original content about auth migration",
        wing="eng", room="auth", hall="facts",
        summary="Auth migration v1",
        key_points=["Migrate from Camunda", "Use Temporal", "Q3 deadline"],
        insight="This unblocks the Go rewrite",
        actionability="actionable",
        importance=1,
    )
    revised = await hivemem_revise_drawer(
        pool, original["id"], "Updated content about auth migration complete",
        new_summary="Auth migration done",
    )
    drawer = await hivemem_get_drawer(pool, revised["new_id"])
    assert drawer["key_points"] == ["Migrate from Camunda", "Use Temporal", "Q3 deadline"]
    assert drawer["insight"] == "This unblocks the Go rewrite"
    assert drawer["actionability"] == "actionable"
    assert drawer["importance"] == 1
    assert drawer["summary"] == "Auth migration done"
    await execute(pool, "DELETE FROM drawers")


async def test_revise_fact_preserves_source_id(pool):
    """Revising a fact must carry forward the source_id link to the originating drawer."""
    drawer = await hivemem_add_drawer(
        pool, content="Source drawer for fact",
        wing="eng", room="test", hall="facts",
    )
    fact = await hivemem_kg_add(
        pool, "HiveMem", "uses", "PostgreSQL",
        source_id=drawer["id"],
    )
    revised = await hivemem_revise_fact(pool, fact["id"], "PostgreSQL 17")

    from hivemem.db import fetch_one
    row = await fetch_one(pool, "SELECT source_id FROM facts WHERE id = %s", (revised["new_id"],))
    assert str(row["source_id"]) == drawer["id"]
    await execute(pool, "DELETE FROM facts")
    await execute(pool, "DELETE FROM drawers")


async def test_pending_drawer_excluded_from_ranked_search(pool):
    """Agent-written pending drawer must not appear in search until approved."""
    await hivemem_register_agent(pool, "classifier", "Test classifier")
    pending = await hivemem_add_drawer(
        pool, content="Pending drawer about vector database optimization",
        wing="eng", room="db", hall="discoveries",
        status="pending", created_by="classifier",
        summary="Vector DB optimization",
    )

    # Search should NOT find pending drawer
    results = await hivemem_search(pool, "vector database optimization")
    found_ids = [r["id"] for r in results]
    assert pending["id"] not in found_ids

    # Approve
    await hivemem_approve_pending(pool, [pending["id"]], "committed")

    # Now search SHOULD find it
    results = await hivemem_search(pool, "vector database optimization")
    found_ids = [r["id"] for r in results]
    assert pending["id"] in found_ids
    await execute(pool, "DELETE FROM drawers")
    await execute(pool, "DELETE FROM agents")


async def test_map_key_drawers_dangling_after_revise(pool):
    """Known limitation: map key_drawers holds old UUID after drawer is revised."""
    drawer = await hivemem_add_drawer(
        pool, content="Important drawer",
        wing="eng", room="arch", hall="facts",
        summary="Key architecture decision",
    )
    await hivemem_update_map(
        pool, wing="eng", title="Engineering Overview",
        narrative="Architecture decisions",
        key_drawers=[drawer["id"]],
    )
    # Revise the drawer — old UUID gets valid_until
    revised = await hivemem_revise_drawer(pool, drawer["id"], "Updated important drawer")

    # Map still references the OLD UUID (known limitation)
    maps = await hivemem_get_map(pool, wing="eng")
    assert drawer["id"] in maps[0]["key_drawers"]
    assert revised["new_id"] not in maps[0]["key_drawers"]
    await execute(pool, "DELETE FROM maps")
    await execute(pool, "DELETE FROM drawers")


async def test_popularity_without_refresh_returns_zero(pool):
    """Popularity score must be 0 when materialized view is not refreshed after access logging."""
    drawer = await hivemem_add_drawer(
        pool, content="Content about Docker container orchestration",
        wing="eng", room="infra", hall="facts",
        summary="Docker orchestration",
    )
    # Log access but do NOT refresh materialized view
    for _ in range(5):
        await hivemem_log_access(pool, drawer_id=drawer["id"])

    results = await hivemem_search(pool, "Docker container orchestration")
    assert len(results) >= 1
    assert results[0]["score_popularity"] == 0.0  # not refreshed
    await execute(pool, "DELETE FROM access_log")
    await execute(pool, "DELETE FROM drawers")


async def test_full_agent_pipeline(pool):
    """Full agent workflow: register → diary → pending drawer → approve → searchable."""
    await hivemem_register_agent(pool, "curator", "Curate and organize knowledge")
    await hivemem_diary_write(pool, "curator", "Found duplicate content in engineering wing")

    drawer = await hivemem_add_drawer(
        pool, content="Curated summary of authentication patterns",
        wing="eng", room="auth", hall="facts",
        summary="Auth patterns curated",
        status="pending", created_by="curator",
    )

    # Verify pending
    pending = await hivemem_pending_approvals(pool)
    curated = [p for p in pending if p["id"] == drawer["id"]]
    assert len(curated) == 1
    assert curated[0]["created_by"] == "curator"

    # Approve and verify searchable
    await hivemem_approve_pending(pool, [drawer["id"]], "committed")
    results = await hivemem_search(pool, "authentication patterns")
    found_ids = [r["id"] for r in results]
    assert drawer["id"] in found_ids
    await execute(pool, "DELETE FROM agent_diary")
    await execute(pool, "DELETE FROM drawers")
    await execute(pool, "DELETE FROM agents")


async def test_contradiction_then_revise_resolves(pool):
    """Full contradiction resolution: detect → revise old fact → re-check returns empty."""
    await hivemem_kg_add(pool, "BOGIS", "uses", "Camunda7")

    # Detect contradiction
    contradictions = await hivemem_check_contradiction(pool, "BOGIS", "uses", "Temporal")
    assert len(contradictions) == 1
    old_fact_id = contradictions[0]["fact_id"]

    # Revise old fact (closes it, creates new with updated object)
    await hivemem_revise_fact(pool, old_fact_id, "Temporal")

    # Re-check: no contradiction anymore
    contradictions = await hivemem_check_contradiction(pool, "BOGIS", "uses", "Temporal")
    assert len(contradictions) == 0

    # quick_facts should show only Temporal
    facts = await hivemem_quick_facts(pool, "BOGIS")
    objects = [f["object"] for f in facts]
    assert "Temporal" in objects
    assert "Camunda7" not in objects
    await execute(pool, "DELETE FROM facts")


async def test_mine_file_then_search(pool):
    """Imported file content must be findable via ranked search."""
    with tempfile.NamedTemporaryFile(mode="w", suffix=".txt", delete=False) as f:
        f.write("HiveMem uses PostgreSQL pgvector for semantic vector search embeddings")
        tmp_path = f.name

    try:
        result = await hivemem_mine_file(pool, tmp_path, wing="eng", room="search", hall="discoveries")
        assert result["drawer_id"]

        results = await hivemem_search(pool, "PostgreSQL pgvector semantic search")
        assert len(results) >= 1
        assert any("pgvector" in r["content"].lower() for r in results)
    finally:
        os.unlink(tmp_path)
    await execute(pool, "DELETE FROM drawers")
