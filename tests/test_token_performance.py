"""Performance tests for HiveMem token management.

Measures actual timings and asserts performance bounds for:
- Auth cache hit latency
- Cold (DB) token validation
- Bulk token creation
- tools/list role filtering
- HTTP request throughput
- Indexed lookup with many active tokens

Run with: pytest tests/test_token_performance.py -v -s
"""

import asyncio
import json
import time

import httpx
import pytest

from hivemem.security import (
    ALL_TOOLS,
    AuthMiddleware,
    create_token,
    filter_tools_for_role,
    validate_token,
)
from hivemem.server import (
    _AcceptMiddleware,
    _IdentityMiddleware,
    _ToolGateMiddleware,
    mcp,
)

import sys
import os
sys.path.insert(0, os.path.dirname(__file__))
from test_http_integration import (
    MCP_HEADERS,
    _LifespanASGIWrapper,
    _init_request,
    _jsonrpc,
    _parse_mcp_response,
)


# ── Fixtures ──────────────────────────────────────────────────────────────


@pytest.fixture
async def mcp_app(pool, db_url):
    """Build full ASGI middleware stack with test pool."""
    import hivemem.server as srv

    original_pool = srv._pool_cache
    srv._pool_cache = pool

    starlette_app = mcp.streamable_http_app()
    full_app = AuthMiddleware(
        _AcceptMiddleware(
            _IdentityMiddleware(
                _ToolGateMiddleware(starlette_app)
            )
        ),
        pool=pool,
    )

    wrapper = _LifespanASGIWrapper(starlette_app, full_app)
    await wrapper.start()
    yield wrapper
    await wrapper.stop()
    mcp._session_manager = None
    srv._pool_cache = original_pool


@pytest.fixture
async def client(mcp_app):
    """httpx AsyncClient wired to the ASGI app."""
    transport = httpx.ASGITransport(app=mcp_app)
    async with httpx.AsyncClient(transport=transport, base_url="http://test") as c:
        yield c


# ── 1. Auth Cache Performance ─────────────────────────────────────────────


async def test_cached_validation_performance(pool):
    """Cached token validation should be sub-0.1ms on average."""
    plaintext = await create_token(pool, "perf-cache", "admin")

    # Warmup: first call populates cache
    middleware = AuthMiddleware(None, pool=pool)
    await middleware._validate_cached(plaintext)

    # Measure cached lookups
    iterations = 1000
    start = time.perf_counter()
    for _ in range(iterations):
        result = await middleware._validate_cached(plaintext)
        assert result is not None
    elapsed = time.perf_counter() - start

    avg_ms = (elapsed / iterations) * 1000
    print(f"\nCached validation: {avg_ms:.4f}ms avg ({iterations} iterations)")
    assert avg_ms < 0.1, f"Cached validation too slow: {avg_ms:.4f}ms"


async def test_cache_hit_rate(pool):
    """Cache hit rate should be >99% after warmup."""
    plaintext = await create_token(pool, "perf-hitrate", "admin")

    middleware = AuthMiddleware(None, pool=pool)

    # Warmup
    await middleware._validate_cached(plaintext)

    # Count cache states before and after
    from hivemem.security import _hash_token

    token_hash = _hash_token(plaintext)
    iterations = 1000
    hits = 0
    for _ in range(iterations):
        cached = middleware._cache.get(token_hash)
        if cached is not None:
            _, cached_at = cached
            if time.time() - cached_at < middleware.CACHE_TTL:
                hits += 1
        await middleware._validate_cached(plaintext)

    hit_rate = hits / iterations * 100
    print(f"\nCache hit rate: {hit_rate:.1f}% ({hits}/{iterations})")
    assert hit_rate > 99, f"Cache hit rate too low: {hit_rate:.1f}%"


# ── 2. Token Validation Without Cache (cold DB lookups) ───────────────────


async def test_cold_validation_performance(pool):
    """Cold (DB) token validation should average <5ms."""
    # Create 100 unique tokens
    tokens = []
    for i in range(100):
        t = await create_token(pool, f"perf-cold-{i}", "reader")
        tokens.append(t)

    # Validate each once (no cache benefit)
    start = time.perf_counter()
    for t in tokens:
        result = await validate_token(pool, t)
        assert result is not None
    elapsed = time.perf_counter() - start

    avg_ms = (elapsed / len(tokens)) * 1000
    print(f"\nCold DB validation: {avg_ms:.4f}ms avg ({len(tokens)} tokens)")
    assert avg_ms < 5, f"Cold validation too slow: {avg_ms:.4f}ms"


# ── 3. Bulk Token Creation ────────────────────────────────────────────────


async def test_bulk_token_creation(pool):
    """Creating 100 tokens should complete in <2 seconds."""
    start = time.perf_counter()
    for i in range(100):
        await create_token(pool, f"perf-bulk-{i}", "writer")
    elapsed = time.perf_counter() - start

    print(f"\nBulk creation: {elapsed:.3f}s for 100 tokens ({elapsed/100*1000:.2f}ms each)")
    assert elapsed < 2.0, f"Bulk creation too slow: {elapsed:.3f}s"


# ── 4. tools/list Filtering Performance ───────────────────────────────────


def test_tool_filtering_performance():
    """Role-based tool filtering should be <0.01ms per call."""
    # Build a mock tool list with 100 entries
    mock_tools = [{"name": f"hivemem_tool_{i}"} for i in range(100)]
    # Add real tool names so some actually match
    for name in ALL_TOOLS:
        mock_tools.append({"name": name})

    roles = ["admin", "writer", "reader", "agent"]
    iterations = 10000

    start = time.perf_counter()
    for _ in range(iterations):
        for role in roles:
            filter_tools_for_role(role, mock_tools)
    elapsed = time.perf_counter() - start

    total_calls = iterations * len(roles)
    avg_ms = (elapsed / total_calls) * 1000
    print(f"\nTool filtering: {avg_ms:.6f}ms avg ({total_calls} calls)")
    assert avg_ms < 0.01, f"Tool filtering too slow: {avg_ms:.6f}ms"


# ── 5. HTTP Request Throughput ────────────────────────────────────────────


async def test_http_throughput(client, pool):
    """Full HTTP stack should handle >20 requests/second."""
    token = await create_token(pool, "perf-http", "admin")
    headers = {**MCP_HEADERS, "Authorization": f"Bearer {token}"}

    # Initialize session
    init_resp = await client.post("/mcp", json=_init_request(), headers=headers)
    session_id = init_resp.headers.get("mcp-session-id")
    if session_id:
        headers["Mcp-Session-Id"] = session_id

    # Send initialized notification
    notif = {"jsonrpc": "2.0", "method": "notifications/initialized"}
    await client.post("/mcp", json=notif, headers=headers)

    # Measure tools/list requests
    iterations = 100
    start = time.perf_counter()
    for i in range(iterations):
        resp = await client.post(
            "/mcp",
            json=_jsonrpc("tools/list", req_id=i + 10),
            headers=headers,
        )
        data = _parse_mcp_response(resp)
        assert "result" in data or "error" not in data, f"Request {i} failed: {data}"
    elapsed = time.perf_counter() - start

    rps = iterations / elapsed
    print(f"\nHTTP throughput: {rps:.1f} req/s ({iterations} requests in {elapsed:.2f}s)")
    assert rps > 20, f"Throughput too low: {rps:.1f} req/s"


# ── 6. Many Active Tokens (indexed lookup) ────────────────────────────────


async def test_lookup_with_many_tokens(pool):
    """Indexed lookup should stay <5ms even with 500 tokens in the table."""
    # Insert 500 tokens
    for i in range(500):
        await create_token(pool, f"perf-many-{i}", "reader")

    # Create the target token
    target = await create_token(pool, "perf-many-target", "admin")

    # Warmup (prime the connection)
    await validate_token(pool, target)

    # Measure repeated lookups of one token among 501
    iterations = 50
    start = time.perf_counter()
    for _ in range(iterations):
        result = await validate_token(pool, target)
        assert result is not None
        assert result["role"] == "admin"
    elapsed = time.perf_counter() - start

    avg_ms = (elapsed / iterations) * 1000
    print(f"\nLookup with 501 tokens: {avg_ms:.4f}ms avg ({iterations} iterations)")
    assert avg_ms < 5, f"Indexed lookup too slow: {avg_ms:.4f}ms"
