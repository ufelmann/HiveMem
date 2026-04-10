"""E2E tests for references & reading list."""

from hivemem.tools.write import (
    hivemem_add_drawer,
    hivemem_add_reference,
    hivemem_link_reference,
)
from hivemem.tools.read import hivemem_reading_list
from hivemem.db import execute

import pytest


async def test_add_reference(pool):
    """Add a reference with all fields."""
    result = await hivemem_add_reference(
        pool, title="GraphRAG Survey 2024",
        url="https://arxiv.org/abs/2024.xxxxx",
        author="Zhang et al.",
        ref_type="paper",
        status="read",
        importance=2,
        tags=["graph", "rag"],
    )
    assert result["title"] == "GraphRAG Survey 2024"
    assert result["status"] == "read"
    await execute(pool, "DELETE FROM references_")


async def test_reading_list_shows_unread(pool):
    """Unread references appear in reading list."""
    await hivemem_add_reference(pool, title="Unread Paper", status="unread", ref_type="paper", importance=1)
    await hivemem_add_reference(pool, title="Read Paper", status="read", ref_type="paper")

    reading = await hivemem_reading_list(pool)
    assert len(reading) == 1
    assert reading[0]["title"] == "Unread Paper"
    await execute(pool, "DELETE FROM references_")


async def test_reading_list_shows_reading(pool):
    """In-progress references appear in reading list."""
    await hivemem_add_reference(pool, title="Currently Reading", status="reading", ref_type="book")
    reading = await hivemem_reading_list(pool)
    assert len(reading) == 1
    assert reading[0]["status"] == "reading"
    await execute(pool, "DELETE FROM references_")


async def test_link_reference_to_drawer(pool):
    """Link a reference to a drawer."""
    drawer = await hivemem_add_drawer(
        pool, content="Based on GraphRAG paper",
        wing="eng", room="search", hall="discoveries",
    )
    ref = await hivemem_add_reference(pool, title="GraphRAG Paper", ref_type="paper")
    link = await hivemem_link_reference(pool, drawer["id"], ref["id"], relation="source")
    assert link["relation"] == "source"

    # Reading list should show linked_drawers count
    await hivemem_add_reference(pool, title="Unread ref", status="unread", ref_type="paper")
    reading = await hivemem_reading_list(pool)
    # The linked ref is status=read, so only "Unread ref" shows
    assert len(reading) == 1
    await execute(pool, "DELETE FROM drawer_references")
    await execute(pool, "DELETE FROM references_")
    await execute(pool, "DELETE FROM drawers")


async def test_reading_list_filter_by_type(pool):
    """Filter reading list by ref_type."""
    await hivemem_add_reference(pool, title="Unread Article", status="unread", ref_type="article")
    await hivemem_add_reference(pool, title="Unread Book", status="unread", ref_type="book")

    articles = await hivemem_reading_list(pool, ref_type="article")
    assert len(articles) == 1
    assert articles[0]["ref_type"] == "article"
    await execute(pool, "DELETE FROM references_")


async def test_ref_type_check_constraint(pool):
    """Invalid ref_type should fail."""
    with pytest.raises(Exception):
        await hivemem_add_reference(pool, title="Bad type", ref_type="invalid_type")
