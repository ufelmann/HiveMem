"""HiveMem MCP Server — Streamable HTTP on port 8421."""

from __future__ import annotations

import os
from contextvars import ContextVar

from mcp.server.fastmcp import FastMCP

_request_identity: ContextVar[dict] = ContextVar("request_identity", default={"name": "anonymous", "role": "admin"})


def get_identity() -> dict:
    """Get the current request's token identity."""
    return _request_identity.get()

from hivemem.db import get_pool
from hivemem.security import get_db_url
from hivemem.tools.admin import hivemem_health as _health
from hivemem.tools.import_tools import (
    hivemem_mine_directory as _mine_directory,
    hivemem_mine_file as _mine_file,
)
from hivemem.tools.read import (
    hivemem_diary_read as _diary_read,
    hivemem_drawer_history as _drawer_history,
    hivemem_fact_history as _fact_history,
    hivemem_get_drawer as _get_drawer,
    hivemem_list_agents as _list_agents,
    hivemem_list_rooms as _list_rooms,
    hivemem_list_wings as _list_wings,
    hivemem_pending_approvals as _pending_approvals,
    hivemem_log_access as _log_access,
    hivemem_quick_facts as _quick_facts,
    hivemem_refresh_popularity as _refresh_popularity,
    hivemem_reading_list as _reading_list,
    hivemem_search as _search,
    hivemem_search_kg as _search_kg,
    hivemem_status as _status,
    hivemem_time_machine as _time_machine,
    hivemem_traverse as _traverse,
    hivemem_get_map as _get_map,
    hivemem_wake_up as _wake_up,
)
from hivemem.tools.write import (
    hivemem_add_drawer as _add_drawer,
    hivemem_add_reference as _add_reference,
    hivemem_approve_pending as _approve_pending,
    hivemem_check_contradiction as _check_contradiction,
    hivemem_check_duplicate as _check_duplicate,
    hivemem_diary_write as _diary_write,
    hivemem_kg_add as _kg_add,
    hivemem_kg_invalidate as _kg_invalidate,
    hivemem_link_reference as _link_reference,
    hivemem_register_agent as _register_agent,
    hivemem_revise_drawer as _revise_drawer,
    hivemem_revise_fact as _revise_fact,
    hivemem_update_identity as _update_identity,
    hivemem_update_map as _update_map,
)

DB_URL = os.environ.get("HIVEMEM_DB_URL", None) or get_db_url()

MCP_PORT = int(os.environ.get("HIVEMEM_PORT", "8421"))

HIVEMEM_PROTOCOL = """\
You have access to HiveMem — a persistent knowledge system with 36 tools.

RULES:
1. EVERY session starts with hivemem_wake_up. No exceptions.
2. When the user asks about past decisions, people, or projects: hivemem_search FIRST, never guess.
3. Before EVERY hivemem_add_drawer: call hivemem_check_duplicate. No duplicates.
4. Before EVERY hivemem_kg_add: call hivemem_check_contradiction. If conflict: invalidate old, then add new.
5. Every fact needs a date (valid_from). Knowledge without timestamps is useless.
6. Use existing wings and rooms. Call hivemem_list_wings/rooms before creating new ones.
7. Keep facts atomic: one triple per relationship. Never combine multiple facts.
8. When a fact changes: invalidate old FIRST, then add new. Never overwrite.
9. Store the FULL content in drawers, never abbreviated. Fill all layers: content (L0), summary (L1), key_points (L2), insight (L3).
10. Never mention HiveMem tools or internal mechanics to the user unless asked. Just be knowledgeable.

ARCHIVING (trigger: user says 'archive', 'save session', or session ending):
1. Summarize session → classify wing/room/hall → check_duplicate → add_drawer with all L0-L3 layers
2. Extract facts: check_contradiction → invalidate old if needed → kg_add with valid_from
3. Update Map of Content if wing structure changed (get_map → update_map)
4. Confirm: wing/room/hall, drawer count, facts added, contradictions resolved
"""

mcp = FastMCP(
    "HiveMem",
    instructions=HIVEMEM_PROTOCOL,
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
async def hivemem_search(
    query: str,
    limit: int = 10,
    wing: str | None = None,
    room: str | None = None,
    hall: str | None = None,
    weight_semantic: float = 0.35,
    weight_keyword: float = 0.15,
    weight_recency: float = 0.20,
    weight_importance: float = 0.15,
    weight_popularity: float = 0.15,
) -> list[dict]:
    """5-signal ranked search. RULE: If the user asks about past decisions, people, or projects — ALWAYS search first, never guess. Returns results ranked by semantic similarity, keyword match, recency, importance, and popularity. Adjust weights to change ranking."""
    pool = await get_db_pool()
    return await _search(pool, query, limit=limit, wing=wing, room=room, hall=hall,
                         weight_semantic=weight_semantic, weight_keyword=weight_keyword,
                         weight_recency=weight_recency, weight_importance=weight_importance,
                         weight_popularity=weight_popularity)


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
async def hivemem_traverse(entity: str, max_depth: int = 2, relation_filter: str | None = None) -> list[dict]:
    """Recursive graph traversal on edges. Optional relation_filter to only follow specific edge types."""
    pool = await get_db_pool()
    return await _traverse(pool, entity, max_depth=max_depth, relation_filter=relation_filter)


@mcp.tool()
async def hivemem_quick_facts(entity: str) -> list[dict]:
    """Get all active facts about an entity (as subject or object)."""
    pool = await get_db_pool()
    return await _quick_facts(pool, entity)


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
    """Load identity context at session start. MANDATORY: call this BEFORE responding to the user's first message. Returns L0 identity (who the user is) and L1 critical facts."""
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
    importance: int | None = None,
    summary: str | None = None,
    key_points: list[str] | None = None,
    insight: str | None = None,
    actionability: str | None = None,
    status: str = "committed",
    valid_from: str | None = None,
) -> dict:
    """Store knowledge with progressive summarization. RULE: Always call hivemem_check_duplicate BEFORE adding. Include all layers: content (L0), summary (L1), key_points (L2), insight (L3). One drawer per topic."""
    from datetime import datetime, timezone

    pool = await get_db_pool()
    identity = get_identity()
    created_by = identity["name"]
    if identity["role"] == "agent":
        status = "pending"
    dt = None
    if valid_from:
        dt = datetime.fromisoformat(valid_from)
        if dt.tzinfo is None:
            dt = dt.replace(tzinfo=timezone.utc)
    return await _add_drawer(
        pool, content, wing=wing, room=room, hall=hall, source=source, tags=tags,
        importance=importance, summary=summary, key_points=key_points, insight=insight,
        actionability=actionability, status=status, created_by=created_by, valid_from=dt,
    )


@mcp.tool()
async def hivemem_check_duplicate(content: str, threshold: float = 0.95) -> list[dict]:
    """MANDATORY: Call BEFORE every hivemem_add_drawer. Returns drawers with similarity > threshold. If match found, skip or update existing instead."""
    pool = await get_db_pool()
    return await _check_duplicate(pool, content, threshold=threshold)


@mcp.tool()
async def hivemem_kg_add(
    subject: str,
    predicate: str,
    object_: str,
    confidence: float = 1.0,
    source_id: str | None = None,
    status: str = "committed",
    valid_from: str | None = None,
) -> dict:
    """Add a fact triple to the knowledge graph. RULE: Always call hivemem_check_contradiction FIRST. Keep facts atomic — one triple per relationship. Always include valid_from date."""
    from datetime import datetime, timezone

    pool = await get_db_pool()
    identity = get_identity()
    created_by = identity["name"]
    if identity["role"] == "agent":
        status = "pending"
    dt = None
    if valid_from:
        dt = datetime.fromisoformat(valid_from)
        if dt.tzinfo is None:
            dt = dt.replace(tzinfo=timezone.utc)
    return await _kg_add(
        pool, subject, predicate, object_, confidence=confidence, source_id=source_id,
        status=status, created_by=created_by, valid_from=dt,
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


@mcp.tool()
async def hivemem_add_reference(
    title: str,
    url: str | None = None,
    author: str | None = None,
    ref_type: str | None = None,
    status: str = "read",
    notes: str | None = None,
    tags: list[str] | None = None,
    importance: int | None = None,
) -> dict:
    """Add a source reference (article, paper, book, video, etc.)."""
    pool = await get_db_pool()
    return await _add_reference(pool, title, url=url, author=author, ref_type=ref_type,
                                status=status, notes=notes, tags=tags, importance=importance)


@mcp.tool()
async def hivemem_link_reference(drawer_id: str, reference_id: str, relation: str = "source") -> dict:
    """Link a reference to a drawer (source, inspired_by, contradicts, extends)."""
    pool = await get_db_pool()
    return await _link_reference(pool, drawer_id, reference_id, relation=relation)


@mcp.tool()
async def hivemem_reading_list(ref_type: str | None = None, limit: int = 20) -> list[dict]:
    """Show unread and in-progress references."""
    pool = await get_db_pool()
    return await _reading_list(pool, ref_type=ref_type, limit=limit)


@mcp.tool()
async def hivemem_revise_drawer(
    old_id: str,
    new_content: str,
    new_summary: str | None = None,
) -> dict:
    """Revise a drawer: close old version and insert new with parent_id link."""
    pool = await get_db_pool()
    identity = get_identity()
    created_by = identity["name"]
    return await _revise_drawer(pool, old_id, new_content, new_summary=new_summary, created_by=created_by)


@mcp.tool()
async def hivemem_revise_fact(
    old_id: str,
    new_object: str,
) -> dict:
    """Revise a fact: close old version and insert new with parent_id link."""
    pool = await get_db_pool()
    identity = get_identity()
    created_by = identity["name"]
    return await _revise_fact(pool, old_id, new_object, created_by=created_by)


@mcp.tool()
async def hivemem_check_contradiction(
    subject: str,
    predicate: str,
    new_object: str,
) -> list[dict]:
    """MANDATORY: Call BEFORE every hivemem_kg_add. Returns active facts with same subject+predicate but different object. If found, invalidate old fact first."""
    pool = await get_db_pool()
    return await _check_contradiction(pool, subject, predicate, new_object)


@mcp.tool()
async def hivemem_drawer_history(drawer_id: str) -> list[dict]:
    """Get all versions of a drawer via the parent_id chain."""
    pool = await get_db_pool()
    return await _drawer_history(pool, drawer_id)


@mcp.tool()
async def hivemem_fact_history(fact_id: str) -> list[dict]:
    """Get all versions of a fact via the parent_id chain."""
    pool = await get_db_pool()
    return await _fact_history(pool, fact_id)


@mcp.tool()
async def hivemem_pending_approvals() -> list[dict]:
    """List all pending agent suggestions awaiting approval."""
    pool = await get_db_pool()
    return await _pending_approvals(pool)


@mcp.tool()
async def hivemem_approve_pending(ids: list[str], decision: str) -> dict:
    """Approve or reject pending drawers/facts by ID list."""
    pool = await get_db_pool()
    return await _approve_pending(pool, ids, decision)


# ── Agent Fleet Tools ───────────────────────────────────────────────────


@mcp.tool()
async def hivemem_register_agent(name: str, focus: str, schedule: str | None = None) -> dict:
    """Register or update an agent in the fleet."""
    pool = await get_db_pool()
    return await _register_agent(pool, name, focus, schedule=schedule)


@mcp.tool()
async def hivemem_diary_write(agent: str, entry: str) -> dict:
    """Write an entry to an agent's diary."""
    pool = await get_db_pool()
    return await _diary_write(pool, agent, entry)


@mcp.tool()
async def hivemem_list_agents() -> list[dict]:
    """List all registered agents."""
    pool = await get_db_pool()
    return await _list_agents(pool)


@mcp.tool()
async def hivemem_diary_read(agent: str, last_n: int = 10) -> list[dict]:
    """Read recent diary entries for an agent."""
    pool = await get_db_pool()
    return await _diary_read(pool, agent, last_n=last_n)


# ── Maps of Content ─────────────────────────────────────────────────────


@mcp.tool()
async def hivemem_get_map(wing: str | None = None) -> list[dict]:
    """Get active Maps of Content for a wing (or all wings)."""
    pool = await get_db_pool()
    return await _get_map(pool, wing=wing)


@mcp.tool()
async def hivemem_update_map(
    wing: str,
    title: str,
    narrative: str,
    room_order: list[str] | None = None,
    key_drawers: list[str] | None = None,
) -> dict:
    """Create or update a Map of Content for a wing (append-only versioning)."""
    pool = await get_db_pool()
    identity = get_identity()
    created_by = identity["name"]
    return await _update_map(pool, wing, title, narrative, room_order=room_order,
                             key_drawers=key_drawers, created_by=created_by)


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


@mcp.tool()
async def hivemem_log_access(drawer_id: str | None = None, fact_id: str | None = None, accessed_by: str = "user") -> dict:
    """Log an access event for popularity tracking."""
    pool = await get_db_pool()
    return await _log_access(pool, drawer_id=drawer_id, fact_id=fact_id, accessed_by=accessed_by)


@mcp.tool()
async def hivemem_refresh_popularity() -> dict:
    """Refresh the drawer popularity materialized view."""
    pool = await get_db_pool()
    return await _refresh_popularity(pool)


class _IdentityMiddleware:
    """ASGI middleware: copy token identity from scope into contextvar."""

    def __init__(self, app):
        self.app = app

    async def __call__(self, scope, receive, send):
        if scope["type"] == "http":
            name = scope.get("token_name", "anonymous")
            role = scope.get("token_role", "admin")
            token = _request_identity.set({"name": name, "role": role})
            try:
                await self.app(scope, receive, send)
            finally:
                _request_identity.reset(token)
        else:
            await self.app(scope, receive, send)


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
    import asyncio
    import uvicorn

    from hivemem.embeddings import get_model
    from hivemem.security import AuthMiddleware

    print("Loading BGE-M3 embedding model...")
    get_model()
    print("Model ready.")

    mcp_app = mcp.streamable_http_app()
    auth_mw = AuthMiddleware(_AcceptMiddleware(_IdentityMiddleware(mcp_app)))

    async def startup():
        pool = await get_db_pool()
        auth_mw.pool = pool

    asyncio.get_event_loop().run_until_complete(startup())
    uvicorn.run(auth_mw, host="0.0.0.0", port=MCP_PORT)
