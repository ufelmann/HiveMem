"""Integration tests for token management."""

from hivemem.db import fetch_one


async def test_api_tokens_table_exists(pool):
    """api_tokens table was created by schema."""
    row = await fetch_one(
        pool,
        "SELECT count(*) AS cnt FROM information_schema.tables "
        "WHERE table_name = 'api_tokens'",
    )
    assert row["cnt"] == 1
