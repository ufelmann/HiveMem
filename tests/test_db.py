"""Tests for database connection and helpers."""

import pytest

from hivemem.db import close_pool, execute, fetch_all, fetch_one, get_pool


async def test_pool_connects(db_url):
    pool = await get_pool(db_url)
    async with pool.connection() as conn:
        row = await conn.execute("SELECT 1 AS ok")
        result = await row.fetchone()
    assert result["ok"] == 1
    await close_pool(db_url)


async def test_execute_insert_and_fetch(db_url):
    pool = await get_pool(db_url)
    await execute(
        pool,
        """
        INSERT INTO identity (key, content, token_count)
        VALUES (%s, %s, %s)
        ON CONFLICT (key) DO UPDATE SET content = EXCLUDED.content
        """,
        ("test_key", "test content", 10),
    )
    row = await fetch_one(
        pool, "SELECT content FROM identity WHERE key = %s", ("test_key",)
    )
    assert row["content"] == "test content"

    rows = await fetch_all(
        pool, "SELECT * FROM identity WHERE key = %s", ("test_key",)
    )
    assert len(rows) >= 1
    await close_pool(db_url)
