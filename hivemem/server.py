"""HiveMem MCP Server — Streamable HTTP on port 8421."""

from __future__ import annotations

import asyncio
import os
from contextvars import ContextVar

from mcp.server.fastmcp import FastMCP

_request_identity: ContextVar[dict] = ContextVar("request_identity", default={"name": "unauthenticated", "role": "reader"})


def get_identity() -> dict:
    """Get the current request's token identity."""
    return _request_identity.get()

from hivemem.db import get_pool
from hivemem.recompute_embeddings import check_and_recompute
from hivemem.security import get_db_url
from hivemem.tools.admin import hivemem_health as _health
from hivemem.tools.read import (
    hivemem_diary_read as _diary_read,
    hivemem_drawer_history as _drawer_history,
    hivemem_fact_history as _fact_history,
    hivemem_get_drawer as _get_drawer,
    hivemem_list_agents as _list_agents,
    hivemem_list_halls as _list_halls,
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
    hivemem_get_blueprint as _get_blueprint,
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
    hivemem_update_blueprint as _update_blueprint,
    hivemem_add_tunnel as _add_tunnel,
    hivemem_remove_tunnel as _remove_tunnel,
)

DB_URL = os.environ.get("HIVEMEM_DB_URL", None) or get_db_url()

MCP_PORT = int(os.environ.get("HIVEMEM_PORT", "8421"))

HIVEMEM_PROTOCOL = """\
You have access to HiveMem — a persistent knowledge system with 36 tools.

HIERARCHY (spatial metaphor — a building you walk through):
- Wing = major life area (e.g. "projects", "knowledge", "cooking")
- Hall = broad category within a wing (e.g. "software", "italian-cuisine")
- Room = specific topic within a hall (e.g. "hivemem", "pasta-recipes")
- Drawer = single knowledge item with 4 layers: L0 content, L1 summary, L2 key_points, L3 insight
- Tunnel = link between two drawers (related_to, builds_on, contradicts, refines)
- Fact = atomic knowledge triple (subject → predicate → object) with temporal validity
- Blueprint = narrative overview of a wing

RULES:
1. EVERY session starts with hivemem_wake_up. No exceptions.
2. When the user asks about past decisions, people, or projects: hivemem_search FIRST, never guess.
3. Before EVERY hivemem_add_drawer: call hivemem_check_duplicate. No duplicates.
4. Before EVERY hivemem_kg_add: call hivemem_check_contradiction. If conflict: invalidate old, then add new.
5. Every fact needs a date (valid_from). Knowledge without timestamps is useless.
6. Use existing wings and halls. Call hivemem_list_wings/halls before creating new ones.
7. Keep facts atomic: one triple per relationship. Never combine multiple facts.
8. When a fact changes: invalidate old FIRST, then add new. Never overwrite.
9. Store the FULL content in drawers, never abbreviated. Fill all layers: content (L0), summary (L1), key_points (L2), insight (L3).
10. Never mention HiveMem tools or internal mechanics to the user unless asked. Just be knowledgeable.

ARCHIVING:
- Archive IMMEDIATELY after completing a significant action (bug fix, feature, design decision, deployment). Do not wait for session end.
- Archive when the user says 'archive' or 'save session'.
- At session end, archive anything not yet stored.
Steps:
1. Summarize → classify wing/hall → check_duplicate → add_drawer with all L0-L3 layers
2. Extract facts: check_contradiction → invalidate old if needed → kg_add with valid_from
2b. Link related drawers: hivemem_search with key_points → for top 2-3 results: hivemem_add_tunnel(from=new_id, to=found_id, relation, note). Relations: related_to, builds_on, contradicts, refines. Do not over-link.
3. Update Blueprint if wing structure changed (get_blueprint → update_blueprint)
4. Confirm: wing/hall, drawer count, facts added, contradictions resolved

BULK FILE ARCHIVING (trigger: user asks to archive files or directories):
1. Read files yourself — understand the content before storing
2. For many files: dispatch parallel subagents, each handling a subset
3. Each file → read content → classify wing/hall → write summary/key_points/insight → check_duplicate → add_drawer with full L0-L3
4. Extract cross-file facts and relationships into the knowledge graph
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
_pool_cache_lock = None  # created lazily to avoid event loop issues


async def get_db_pool():
    """Get the shared database pool (lazy init, concurrency-safe)."""
    global _pool_cache, _pool_cache_lock
    if _pool_cache is not None:
        return _pool_cache
    if _pool_cache_lock is None:
        _pool_cache_lock = asyncio.Lock()
    async with _pool_cache_lock:
        if _pool_cache is None:
            _pool_cache = await get_pool(DB_URL)
    return _pool_cache


# ── Read Tools ──────────────────────────────────────────────────────────


@mcp.tool()
async def hivemem_status() -> dict:
    """Counts of drawers, facts, tunnels, wings list, and last activity."""
    pool = await get_db_pool()
    return await _status(pool)


@mcp.tool()
async def hivemem_search(
    query: str,
    limit: int = 10,
    wing: str | None = None,
    hall: str | None = None,
    room: str | None = None,
    weight_semantic: float = 0.35,
    weight_keyword: float = 0.15,
    weight_recency: float = 0.20,
    weight_importance: float = 0.15,
    weight_popularity: float = 0.15,
) -> list[dict]:
    """5-signal ranked search. RULE: If the user asks about past decisions, people, or projects — ALWAYS search first, never guess. Returns results ranked by semantic similarity, keyword match, recency, importance, and popularity. Adjust weights to change ranking."""
    pool = await get_db_pool()
    return await _search(pool, query, limit=limit, wing=wing, hall=hall, room=room,
                         weight_semantic=weight_semantic, weight_keyword=weight_keyword,
                         weight_recency=weight_recency, weight_importance=weight_importance,
                         weight_popularity=weight_popularity)


@mcp.tool()
async def hivemem_search_kg(
    subject: str | None = None,
    predicate: str | None = None,
    object_: str | None = None,
    limit: int = 100,
) -> list[dict]:
    """ILIKE search on the knowledge graph facts table."""
    pool = await get_db_pool()
    return await _search_kg(pool, subject=subject, predicate=predicate, object_=object_, limit=limit)


@mcp.tool()
async def hivemem_get_drawer(drawer_id: str) -> dict | None:
    """Get a single drawer by UUID."""
    pool = await get_db_pool()
    return await _get_drawer(pool, drawer_id)


@mcp.tool()
async def hivemem_list_wings() -> list[dict]:
    """List all wings with hall and drawer counts."""
    pool = await get_db_pool()
    return await _list_wings(pool)


@mcp.tool()
async def hivemem_list_halls(wing: str) -> list[dict]:
    """List halls within a wing."""
    pool = await get_db_pool()
    return await _list_halls(pool, wing)


@mcp.tool()
async def hivemem_traverse(drawer_id: str, max_depth: int = 2, relation_filter: str | None = None) -> list[dict]:
    """Bidirectional graph traversal on drawer-to-drawer tunnels. Returns linked drawers with relation type and depth."""
    pool = await get_db_pool()
    return await _traverse(pool, drawer_id, max_depth=max_depth, relation_filter=relation_filter)


@mcp.tool()
async def hivemem_quick_facts(entity: str) -> list[dict]:
    """Get all active facts about an entity (as subject or object)."""
    pool = await get_db_pool()
    return await _quick_facts(pool, entity)


@mcp.tool()
async def hivemem_time_machine(subject: str, as_of: str | None = None, limit: int = 100) -> list[dict]:
    """Facts valid at a point in time using valid_from/valid_until."""
    from datetime import datetime, timezone

    pool = await get_db_pool()
    dt = None
    if as_of:
        dt = datetime.fromisoformat(as_of)
        if dt.tzinfo is None:
            dt = dt.replace(tzinfo=timezone.utc)
    return await _time_machine(pool, subject, as_of=dt, limit=limit)


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
    hall: str | None = None,
    room: str | None = None,
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
        pool, content, wing=wing, hall=hall, room=room, source=source, tags=tags,
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


# ── Blueprints ─────────────────────────────────────────────────────────


@mcp.tool()
async def hivemem_get_blueprint(wing: str | None = None) -> list[dict]:
    """Get active Blueprints for a wing (or all wings)."""
    pool = await get_db_pool()
    return await _get_blueprint(pool, wing=wing)


@mcp.tool()
async def hivemem_update_blueprint(
    wing: str,
    title: str,
    narrative: str,
    hall_order: list[str] | None = None,
    key_drawers: list[str] | None = None,
) -> dict:
    """Create or update a Blueprint for a wing (append-only versioning)."""
    pool = await get_db_pool()
    identity = get_identity()
    created_by = identity["name"]
    return await _update_blueprint(pool, wing, title, narrative, hall_order=hall_order,
                                   key_drawers=key_drawers, created_by=created_by)



# ── Tunnel Tools ───────────────────────────────────────────────────────


@mcp.tool()
async def hivemem_add_tunnel(
    from_drawer: str,
    to_drawer: str,
    relation: str,
    note: str | None = None,
    status: str = "committed",
) -> dict:
    """Link two drawers. Relations: related_to, builds_on, contradicts, refines. RULE: After archiving a drawer, search for related drawers and link the top 2-3."""
    pool = await get_db_pool()
    identity = get_identity()
    created_by = identity["name"]
    if identity["role"] == "agent":
        status = "pending"
    return await _add_tunnel(pool, from_drawer, to_drawer, relation, note=note, status=status, created_by=created_by)


@mcp.tool()
async def hivemem_remove_tunnel(tunnel_id: str) -> dict:
    """Soft-delete a tunnel (sets valid_until to now). The tunnel remains in history for time-machine queries."""
    pool = await get_db_pool()
    return await _remove_tunnel(pool, tunnel_id)


# ── Admin Tools ─────────────────────────────────────────────────────────


@mcp.tool()
async def hivemem_health() -> dict:
    """Check DB connection, extension versions, drawer/fact counts, db size, disk free."""
    pool = await get_db_pool()
    return await _health(pool)


@mcp.tool()
async def hivemem_log_access(drawer_id: str | None = None, fact_id: str | None = None) -> dict:
    """Log an access event for popularity tracking."""
    pool = await get_db_pool()
    identity = get_identity()
    return await _log_access(pool, drawer_id=drawer_id, fact_id=fact_id, accessed_by=identity["name"])


@mcp.tool()
async def hivemem_refresh_popularity() -> dict:
    """Refresh the drawer popularity materialized view."""
    pool = await get_db_pool()
    return await _refresh_popularity(pool)


class _ToolGateMiddleware:
    """ASGI middleware: filter tools/list by role, enforce tools/call permissions."""

    def __init__(self, app):
        self.app = app

    async def __call__(self, scope, receive, send):
        if scope["type"] != "http":
            await self.app(scope, receive, send)
            return

        role = scope.get("token_role", "reader")

        # Intercept request body to enforce tools/call permissions
        body_parts = []

        async def buffering_receive():
            message = await receive()
            if message.get("type") == "http.request":
                body_parts.append(message.get("body", b""))
            return message

        # Buffer the request to inspect it
        first_message = await receive()
        if first_message.get("type") == "http.request":
            body_parts.append(first_message.get("body", b""))

        request_body = b"".join(body_parts)

        # Check tools/call permission
        try:
            import json as _json
            data = _json.loads(request_body)
            if isinstance(data, dict) and data.get("method") == "tools/call":
                tool_name = data.get("params", {}).get("name", "")
                from hivemem.security import check_tool_permission
                err = check_tool_permission(role, tool_name)
                if err:
                    await self._send_jsonrpc_error(
                        send, data.get("id"), -32600, err
                    )
                    return
        except (ValueError, KeyError, AttributeError):
            pass

        # Replay the buffered message for the inner app
        replayed = False

        async def replay_receive():
            nonlocal replayed
            if not replayed:
                replayed = True
                return first_message
            return await receive()

        # Intercept response to filter tools/list (skip SSE streams)
        response_parts = []
        is_sse = False

        async def capture_send(message):
            nonlocal is_sse
            if message["type"] == "http.response.start":
                for k, v in message.get("headers", []):
                    if k == b"content-type" and b"text/event-stream" in v:
                        is_sse = True
                        break
                if is_sse:
                    await send(message)
                    return
                response_parts.append(message)
            elif is_sse:
                await send(message)
                return
            elif message["type"] == "http.response.body":
                body = message.get("body", b"")
                if body:
                    try:
                        resp_data = _json.loads(body)
                        if isinstance(resp_data, dict) and "result" in resp_data:
                            result = resp_data["result"]
                            if isinstance(result, dict) and "tools" in result:
                                from hivemem.security import filter_tools_for_role
                                result["tools"] = filter_tools_for_role(role, result["tools"])
                                body = _json.dumps(resp_data).encode()
                                message = dict(message, body=body)
                    except (ValueError, KeyError):
                        pass
                if response_parts:
                    start_msg = response_parts[0]
                    headers = [
                        (k, v) for k, v in start_msg.get("headers", [])
                        if k != b"content-length"
                    ]
                    headers.append((b"content-length", str(len(message.get("body", b""))).encode()))
                    start_msg = dict(start_msg, headers=headers)
                    await send(start_msg)
                    response_parts.clear()
                await send(message)
            else:
                await send(message)

        await self.app(scope, replay_receive, capture_send)

        if response_parts:
            for part in response_parts:
                await send(part)

    @staticmethod
    async def _send_jsonrpc_error(send, req_id, code: int, message: str):
        import json as _json
        body = _json.dumps({
            "jsonrpc": "2.0",
            "id": req_id,
            "error": {"code": code, "message": message},
        }).encode()
        await send({
            "type": "http.response.start",
            "status": 403,
            "headers": [
                (b"content-type", b"application/json"),
                (b"content-length", str(len(body)).encode()),
            ],
        })
        await send({"type": "http.response.body", "body": body})


class _IdentityMiddleware:
    """ASGI middleware: copy token identity from scope into contextvar."""

    def __init__(self, app):
        self.app = app

    async def __call__(self, scope, receive, send):
        if scope["type"] == "http":
            name = scope.get("token_name", "anonymous")
            role = scope.get("token_role", "reader")
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
    auth_mw = AuthMiddleware(_AcceptMiddleware(_IdentityMiddleware(_ToolGateMiddleware(mcp_app))))

    class _LifespanWrapper:
        """Wraps the ASGI app to init the DB pool on startup within uvicorn's event loop."""

        def __init__(self, app):
            self.app = app

        async def __call__(self, scope, receive, send):
            if scope["type"] == "lifespan":
                async def wrapped_receive():
                    msg = await receive()
                    if msg["type"] == "lifespan.startup":
                        pool = await get_db_pool()
                        await check_and_recompute(pool)
                        auth_mw.pool = pool
                    return msg
                await self.app(scope, wrapped_receive, send)
            else:
                await self.app(scope, receive, send)

    uvicorn.run(_LifespanWrapper(auth_mw), host="0.0.0.0", port=MCP_PORT)
