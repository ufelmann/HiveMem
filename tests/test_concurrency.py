"""Concurrency tests -- verify thread-safety under parallel async writes."""

import asyncio

from hivemem.db import fetch_one, fetch_all, execute
from hivemem.security import create_token, validate_token, revoke_token, AuthMiddleware
from hivemem.tools.write import (
    hivemem_add_drawer,
    hivemem_kg_add,
    hivemem_approve_pending,
    hivemem_revise_drawer,
    hivemem_update_map,
)
from hivemem.tools.read import hivemem_pending_approvals


async def test_concurrent_drawer_writes(pool):
    """10 concurrent add_drawer calls don't corrupt data."""
    async def write(i):
        return await hivemem_add_drawer(
            pool, content=f"Concurrent drawer {i}",
            wing="test", room="concurrency", hall="facts",
            status="committed", created_by="tester",
        )

    results = await asyncio.gather(*[write(i) for i in range(10)])
    ids = [r["id"] for r in results]
    assert len(set(ids)) == 10  # all unique UUIDs

    row = await fetch_one(
        pool,
        "SELECT count(*) AS cnt FROM drawers WHERE wing = 'test' AND room = 'concurrency'",
    )
    assert row["cnt"] == 10


async def test_concurrent_fact_writes(pool):
    """10 concurrent kg_add calls don't lose data."""
    async def write(i):
        return await hivemem_kg_add(
            pool, f"Entity{i}", "has_property", f"value{i}",
            status="committed", created_by="tester",
        )

    results = await asyncio.gather(*[write(i) for i in range(10)])
    ids = [r["id"] for r in results]
    assert len(set(ids)) == 10


async def test_concurrent_revise_same_drawer(pool):
    """Two concurrent revisions of the same drawer -- one wins, one fails."""
    original = await hivemem_add_drawer(
        pool, content="Original content",
        wing="test", room="revise", hall="facts",
        status="committed", created_by="tester",
        summary="Original",
    )

    errors = []

    async def revise(text):
        try:
            return await hivemem_revise_drawer(
                pool, original["id"], f"Revised: {text}",
                new_summary=text, created_by="tester",
            )
        except Exception as e:
            errors.append(str(e))
            return None

    results = await asyncio.gather(revise("version-a"), revise("version-b"))
    successes = [r for r in results if r is not None]

    # At least one must succeed; the other may fail (already revised)
    assert len(successes) >= 1
    # No more than one active version
    rows = await fetch_all(
        pool,
        "SELECT id FROM drawers WHERE parent_id = %s AND valid_until IS NULL",
        (original["id"],),
    )
    assert len(rows) == 1  # exactly one child


async def test_concurrent_token_creation_different_names(pool):
    """50 tokens created concurrently with unique names all succeed."""
    async def create(i):
        return await create_token(pool, f"concurrent-{i}", "reader")

    results = await asyncio.gather(*[create(i) for i in range(50)])
    assert len(results) == 50
    assert all(isinstance(t, str) for t in results)


async def test_concurrent_token_creation_same_name(pool):
    """Two concurrent creates with same name -- one wins, one gets ValueError."""
    errors = []

    async def create():
        try:
            return await create_token(pool, "race-name", "reader")
        except ValueError:
            errors.append(True)
            return None

    results = await asyncio.gather(create(), create())
    successes = [r for r in results if r is not None]
    assert len(successes) == 1
    assert len(errors) == 1


async def test_concurrent_validate_same_token(pool):
    """100 concurrent validations of the same token all return correct identity."""
    plaintext = await create_token(pool, "validate-race", "admin")

    results = await asyncio.gather(*[validate_token(pool, plaintext) for _ in range(100)])
    assert all(r is not None for r in results)
    assert all(r["name"] == "validate-race" for r in results)
    assert all(r["role"] == "admin" for r in results)


async def test_concurrent_cache_eviction(pool):
    """Cache eviction under concurrent load doesn't raise KeyError."""
    mw = AuthMiddleware(None, pool)
    mw.CACHE_MAX_SIZE = 5  # tiny cache to force evictions

    tokens = []
    for i in range(20):
        t = await create_token(pool, f"evict-{i}", "reader")
        tokens.append(t)

    # Validate all 20 concurrently through the cache (cache only holds 5)
    async def validate(t):
        return await mw._validate_cached(t)

    results = await asyncio.gather(*[validate(t) for t in tokens])
    # All should validate successfully despite cache churn
    assert all(r is not None for r in results)
    assert len(mw._cache) <= mw.CACHE_MAX_SIZE


async def test_concurrent_approve_same_ids(pool):
    """Two concurrent approvals of overlapping IDs don't double-commit."""
    ids = []
    for i in range(5):
        r = await hivemem_add_drawer(
            pool, content=f"Pending {i}", wing="test", room="approve", hall="facts",
            status="pending", created_by="agent",
        )
        ids.append(r["id"])

    # Both try to approve the same 5 IDs
    r1, r2 = await asyncio.gather(
        hivemem_approve_pending(pool, ids, "committed"),
        hivemem_approve_pending(pool, ids, "committed"),
    )
    # Total approved should be 5, split between the two calls
    assert r1["count"] + r2["count"] == 5

    # All should be committed exactly once
    for item_id in ids:
        row = await fetch_one(pool, "SELECT status FROM drawers WHERE id = %s", (item_id,))
        assert row["status"] == "committed"


async def test_concurrent_update_map_same_wing(pool):
    """Two concurrent map updates for same wing -- advisory lock serializes them."""
    async def update(version):
        return await hivemem_update_map(
            pool, "race-wing", f"Map v{version}", f"Narrative {version}",
            created_by="tester",
        )

    r1, r2 = await asyncio.gather(update(1), update(2))

    # Both should succeed (advisory lock serializes, doesn't block)
    assert r1["wing"] == "race-wing"
    assert r2["wing"] == "race-wing"

    # Exactly one active map for this wing
    rows = await fetch_all(
        pool,
        "SELECT id FROM maps WHERE wing = 'race-wing' AND valid_until IS NULL",
    )
    assert len(rows) == 1
    await execute(pool, "DELETE FROM maps WHERE wing = 'race-wing'")


async def test_concurrent_revoke_same_token(pool):
    """Two concurrent revokes of same token -- one succeeds, one gets error."""
    await create_token(pool, "revoke-race", "writer")

    errors = []

    async def revoke():
        try:
            await revoke_token(pool, "revoke-race")
            return True
        except ValueError:
            errors.append(True)
            return False

    results = await asyncio.gather(revoke(), revoke())
    successes = [r for r in results if r]
    assert len(successes) == 1
    assert len(errors) == 1


async def test_pool_init_concurrent(db_url):
    """Concurrent get_pool calls for same URL return the same pool."""
    from hivemem.db import get_pool, _pools, _pool_lock

    # Clear cached pool for this URL to test init race
    old_pool = _pools.pop(db_url, None)

    pools = await asyncio.gather(*[get_pool(db_url) for _ in range(10)])

    # All should be the same pool object
    assert all(p is pools[0] for p in pools)

    # Restore for other tests
    if old_pool:
        _pools[db_url] = old_pool
