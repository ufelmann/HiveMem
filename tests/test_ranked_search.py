"""E2E tests for ranked search with 5 signals."""

from hivemem.db import get_pool, execute, fetch_one
from hivemem.tools.write import hivemem_add_drawer
from hivemem.tools.read import (
    hivemem_search,
    hivemem_log_access,
    hivemem_refresh_popularity,
)

import pytest


# pool fixture is defined in conftest.py


async def test_ranked_search_returns_scores(pool):
    """Ranked search returns all 5 individual scores + total."""
    await hivemem_add_drawer(
        pool, content="PostgreSQL vector search with pgvector",
        wing="eng", hall="db", room="facts", importance=2, summary="pgvector search",
    )
    results = await hivemem_search(pool, "vector search")
    assert len(results) >= 1
    r = results[0]
    assert "score_semantic" in r
    assert "score_keyword" in r
    assert "score_recency" in r
    assert "score_importance" in r
    assert "score_popularity" in r
    assert "score_total" in r
    assert r["score_total"] > 0


async def test_importance_weight_affects_ranking(pool):
    """Higher importance weight should push important drawers up."""
    await hivemem_add_drawer(
        pool, content="Critical auth decision for production",
        wing="eng", hall="auth", room="facts", importance=1, summary="Critical auth",
    )
    await hivemem_add_drawer(
        pool, content="Minor auth logging tweak",
        wing="eng", hall="auth", room="facts", importance=5, summary="Minor auth logging",
    )
    # High importance weight
    results = await hivemem_search(
        pool, "auth decision",
        weight_semantic=0.1, weight_importance=0.7,
        weight_keyword=0.1, weight_recency=0.05, weight_popularity=0.05,
    )
    assert len(results) >= 2
    assert results[0]["importance"] == 1  # critical first


async def test_keyword_search_works(pool):
    """Keyword match should find drawers even with low semantic similarity."""
    await hivemem_add_drawer(
        pool, content="The BGE-M3 model uses 1024 dimensional embeddings for multilingual search",
        wing="eng", hall="ml", room="facts", summary="BGE-M3 embedding dimensions",
    )
    results = await hivemem_search(
        pool, "BGE-M3 1024",
        weight_keyword=0.8, weight_semantic=0.1,
        weight_recency=0.05, weight_importance=0.025, weight_popularity=0.025,
    )
    assert len(results) >= 1
    assert results[0]["score_keyword"] > 0


async def test_popularity_signal(pool):
    """Access logging + popularity refresh affects ranking."""
    d1 = await hivemem_add_drawer(
        pool, content="Popular knowledge about Docker",
        wing="eng", hall="infra", room="facts", summary="Docker knowledge",
    )
    await hivemem_add_drawer(
        pool, content="Unpopular knowledge about Docker",
        wing="eng", hall="infra", room="facts", summary="Docker unpopular",
    )

    # Log 5 accesses for d1
    for _ in range(5):
        await hivemem_log_access(pool, drawer_id=d1["id"], accessed_by="search")

    result = await hivemem_refresh_popularity(pool)
    assert result["refreshed"] is True

    # Search with high popularity weight
    results = await hivemem_search(
        pool, "Docker knowledge",
        weight_popularity=0.7, weight_semantic=0.2,
        weight_keyword=0.05, weight_recency=0.025, weight_importance=0.025,
    )
    assert len(results) >= 2
    assert results[0]["score_popularity"] > results[1]["score_popularity"]


async def test_wing_filter(pool):
    """Wing filter narrows results."""
    await hivemem_add_drawer(pool, content="Engineering topic", wing="eng", hall="test", room="facts")
    await hivemem_add_drawer(pool, content="Personal topic", wing="personal", hall="test", room="facts")

    results = await hivemem_search(pool, "topic", wing="eng")
    assert all(r["wing"] == "eng" for r in results)


async def test_hall_filter(pool):
    """Hall filter narrows results."""
    await hivemem_add_drawer(pool, content="A discovery about search", wing="eng", hall="test", room="discoveries")
    await hivemem_add_drawer(pool, content="A fact about search", wing="eng", hall="test", room="facts")

    results = await hivemem_search(pool, "search", hall="discoveries")
    assert all(r["room"] == "discoveries" for r in results)
