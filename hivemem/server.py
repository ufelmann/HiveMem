"""HiveMem MCP Server — Streamable HTTP on port 8421."""

from __future__ import annotations

import os

from mcp.server.fastmcp import FastMCP

from hivemem.db import get_pool
from hivemem.security import get_db_url
from hivemem.tools.admin import hivemem_health as _health
from hivemem.tools.import_tools import (
    hivemem_mine_directory as _mine_directory,
    hivemem_mine_file as _mine_file,
)
from hivemem.tools.read import (
    hivemem_get_drawer as _get_drawer,
    hivemem_list_rooms as _list_rooms,
    hivemem_list_wings as _list_wings,
    hivemem_search as _search,
    hivemem_search_kg as _search_kg,
    hivemem_status as _status,
    hivemem_time_machine as _time_machine,
    hivemem_traverse as _traverse,
    hivemem_wake_up as _wake_up,
)
from hivemem.tools.write import (
    hivemem_add_drawer as _add_drawer,
    hivemem_kg_add as _kg_add,
    hivemem_kg_invalidate as _kg_invalidate,
    hivemem_update_identity as _update_identity,
)

DB_URL = os.environ.get("HIVEMEM_DB_URL", None) or get_db_url()

MCP_PORT = int(os.environ.get("HIVEMEM_PORT", "8421"))

mcp = FastMCP(
    "HiveMem",
    instructions="Personal knowledge system with semantic search and temporal knowledge graph",
    host="0.0.0.0",
    port=MCP_PORT,
    json_response=True,
    stateless_http=True,
)

_pool_cache = None


async def get_db_pool():
    """Get the shared database pool (lazy init)."""
    global _pool_cache
    if _pool_cache is None:
        _pool_cache = await get_pool(DB_URL)
    return _pool_cache


# ── Read Tools ──────────────────────────────────────────────────────────


@mcp.tool()
async def hivemem_status() -> dict:
    """Counts of drawers, facts, edges, wings list, and last activity."""
    pool = await get_db_pool()
    return await _status(pool)


@mcp.tool()
async def hivemem_search(query: str, limit: int = 10, wing: str | None = None) -> list[dict]:
    """Semantic search using vector cosine distance."""
    pool = await get_db_pool()
    return await _search(pool, query, limit=limit, wing=wing)


@mcp.tool()
async def hivemem_search_kg(
    subject: str | None = None,
    predicate: str | None = None,
    object_: str | None = None,
) -> list[dict]:
    """ILIKE search on the knowledge graph facts table."""
    pool = await get_db_pool()
    return await _search_kg(pool, subject=subject, predicate=predicate, object_=object_)


@mcp.tool()
async def hivemem_get_drawer(drawer_id: str) -> dict | None:
    """Get a single drawer by UUID."""
    pool = await get_db_pool()
    return await _get_drawer(pool, drawer_id)


@mcp.tool()
async def hivemem_list_wings() -> list[dict]:
    """List all wings with room and drawer counts."""
    pool = await get_db_pool()
    return await _list_wings(pool)


@mcp.tool()
async def hivemem_list_rooms(wing: str) -> list[dict]:
    """List rooms within a wing."""
    pool = await get_db_pool()
    return await _list_rooms(pool, wing)


@mcp.tool()
async def hivemem_traverse(entity: str, max_depth: int = 2) -> list[dict]:
    """Recursive graph traversal on the edges table."""
    pool = await get_db_pool()
    return await _traverse(pool, entity, max_depth=max_depth)


@mcp.tool()
async def hivemem_time_machine(subject: str, as_of: str | None = None) -> list[dict]:
    """Facts valid at a point in time using valid_from/valid_until."""
    from datetime import datetime, timezone

    pool = await get_db_pool()
    dt = None
    if as_of:
        dt = datetime.fromisoformat(as_of)
        if dt.tzinfo is None:
            dt = dt.replace(tzinfo=timezone.utc)
    return await _time_machine(pool, subject, as_of=dt)


@mcp.tool()
async def hivemem_wake_up() -> dict:
    """Load l0_identity and l1_critical from identity table."""
    pool = await get_db_pool()
    return await _wake_up(pool)


# ── Write Tools ─────────────────────────────────────────────────────────


@mcp.tool()
async def hivemem_add_drawer(
    content: str,
    wing: str | None = None,
    room: str | None = None,
    hall: str | None = None,
    source: str | None = None,
    tags: list[str] | None = None,
    valid_from: str | None = None,
) -> dict:
    """Encode content and store as a drawer with optional metadata."""
    from datetime import datetime, timezone

    pool = await get_db_pool()
    dt = None
    if valid_from:
        dt = datetime.fromisoformat(valid_from)
        if dt.tzinfo is None:
            dt = dt.replace(tzinfo=timezone.utc)
    return await _add_drawer(
        pool, content, wing=wing, room=room, hall=hall, source=source, tags=tags, valid_from=dt
    )


@mcp.tool()
async def hivemem_kg_add(
    subject: str,
    predicate: str,
    object_: str,
    confidence: float = 1.0,
    source_id: str | None = None,
    valid_from: str | None = None,
) -> dict:
    """Add a fact to the knowledge graph."""
    from datetime import datetime, timezone

    pool = await get_db_pool()
    dt = None
    if valid_from:
        dt = datetime.fromisoformat(valid_from)
        if dt.tzinfo is None:
            dt = dt.replace(tzinfo=timezone.utc)
    return await _kg_add(
        pool, subject, predicate, object_, confidence=confidence, source_id=source_id, valid_from=dt
    )


@mcp.tool()
async def hivemem_kg_invalidate(fact_id: str) -> dict:
    """Invalidate a fact by setting valid_until to now."""
    pool = await get_db_pool()
    return await _kg_invalidate(pool, fact_id)


@mcp.tool()
async def hivemem_update_identity(key: str, content: str) -> dict:
    """UPSERT into identity with rough token count."""
    pool = await get_db_pool()
    return await _update_identity(pool, key, content)


# ── Import Tools ────────────────────────────────────────────────────────


@mcp.tool()
async def hivemem_mine_file(
    file_path: str,
    wing: str | None = None,
    room: str | None = None,
    hall: str | None = None,
) -> dict:
    """Read a file and store its content as a drawer."""
    pool = await get_db_pool()
    return await _mine_file(pool, file_path, wing=wing, room=room, hall=hall)


@mcp.tool()
async def hivemem_mine_directory(
    dir_path: str,
    wing: str | None = None,
    hall: str | None = None,
    extensions: list[str] | None = None,
) -> dict:
    """Glob for files in a directory and store each as a drawer."""
    pool = await get_db_pool()
    return await _mine_directory(pool, dir_path, wing=wing, hall=hall, extensions=extensions)


# ── Admin Tools ─────────────────────────────────────────────────────────


@mcp.tool()
async def hivemem_health() -> dict:
    """Check DB connection, extension versions, drawer/fact counts, db size, disk free."""
    pool = await get_db_pool()
    return await _health(pool)


class _AcceptMiddleware:
    """ASGI middleware: ensure Accept header includes both MCP-required types."""

    def __init__(self, app):
        self.app = app

    async def __call__(self, scope, receive, send):
        if scope["type"] == "http":
            headers = [(k, v) for k, v in scope["headers"] if k != b"accept"]
            headers.append((b"accept", b"application/json, text/event-stream"))
            scope = dict(scope, headers=headers)
        await self.app(scope, receive, send)


if __name__ == "__main__":
    import uvicorn

    from hivemem.embeddings import get_model
    from hivemem.security import AuthMiddleware

    print("Loading BGE-M3 embedding model...")
    get_model()
    print("Model ready.")

    app = AuthMiddleware(_AcceptMiddleware(mcp.streamable_http_app()))
    uvicorn.run(app, host="0.0.0.0", port=MCP_PORT)
