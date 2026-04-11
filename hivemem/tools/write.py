"""Write tools for modifying HiveMem."""

from __future__ import annotations

import asyncio
from datetime import datetime

from psycopg_pool import AsyncConnectionPool

from hivemem.db import execute, fetch_one, fetch_all
from hivemem.embeddings import encode


async def hivemem_add_drawer(
    pool: AsyncConnectionPool,
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
    created_by: str | None = None,
    valid_from: datetime | None = None,
) -> dict:
    """Encode content, insert into drawers, return {id, wing, hall, room, status}."""
    vector = await asyncio.to_thread(encode, content)
    vector_str = str(vector)
    tags_val = tags or []
    key_points_val = key_points or []

    row = await fetch_one(
        pool,
        """
        INSERT INTO drawers (content, embedding, wing, hall, room, source, tags,
                             importance, summary, key_points, insight, actionability,
                             status, created_by, valid_from)
        VALUES (%s, %s::vector, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, COALESCE(%s, now()))
        RETURNING id, wing, hall, room, status
        """,
        (content, vector_str, wing, hall, room, source, tags_val,
         importance, summary, key_points_val, insight, actionability,
         status, created_by, valid_from),
    )
    return {
        "id": str(row["id"]),
        "wing": row["wing"],
        "hall": row["hall"],
        "room": row["room"],
        "status": row["status"],
    }


async def hivemem_check_duplicate(
    pool: AsyncConnectionPool,
    content: str,
    threshold: float = 0.95,
) -> list[dict]:
    """Check if similar content already exists."""
    vector = await asyncio.to_thread(encode, content)
    vector_str = str(vector)
    rows = await fetch_all(
        pool,
        "SELECT * FROM check_duplicate_drawer(%s::vector, %s::real)",
        (vector_str, threshold),
    )
    return [
        {
            "id": str(row["id"]),
            "similarity": round(float(row["similarity"]), 4),
            "summary": row["summary"],
        }
        for row in rows
    ]


async def hivemem_kg_add(
    pool: AsyncConnectionPool,
    subject: str,
    predicate: str,
    object_: str,
    confidence: float = 1.0,
    source_id: str | None = None,
    status: str = "committed",
    created_by: str | None = None,
    valid_from: datetime | None = None,
) -> dict:
    """Insert into facts, return {id, subject, predicate, object, status}."""
    row = await fetch_one(
        pool,
        """
        INSERT INTO facts (subject, predicate, object, confidence, source_id,
                           status, created_by, valid_from)
        VALUES (%s, %s, %s, %s, %s, %s, %s, COALESCE(%s, now()))
        RETURNING id, subject, predicate, object, status
        """,
        (subject, predicate, object_, confidence, source_id,
         status, created_by, valid_from),
    )
    return {
        "id": str(row["id"]),
        "subject": row["subject"],
        "predicate": row["predicate"],
        "object": row["object"],
        "status": row["status"],
    }


async def hivemem_kg_invalidate(
    pool: AsyncConnectionPool,
    fact_id: str,
) -> dict:
    """Mark a fact as no longer valid by setting valid_until=now()."""
    await execute(
        pool,
        "UPDATE facts SET valid_until = now() WHERE id = %s",
        (fact_id,),
    )
    return {"invalidated": True}


async def hivemem_revise_drawer(
    pool: AsyncConnectionPool,
    old_id: str,
    new_content: str,
    new_summary: str | None = None,
    created_by: str = "user",
) -> dict:
    """Revise a drawer: close old version, insert new with parent_id."""
    row = await fetch_one(
        pool,
        "SELECT revise_drawer(%s, %s, %s, %s) AS new_id",
        (old_id, new_content, new_summary, created_by),
    )
    return {"old_id": old_id, "new_id": str(row["new_id"])}


async def hivemem_revise_fact(
    pool: AsyncConnectionPool,
    old_id: str,
    new_object: str,
    created_by: str = "user",
) -> dict:
    """Revise a fact: close old version, insert new with parent_id."""
    row = await fetch_one(
        pool,
        "SELECT revise_fact(%s, %s, now(), %s) AS new_id",
        (old_id, new_object, created_by),
    )
    return {"old_id": old_id, "new_id": str(row["new_id"])}


async def hivemem_check_contradiction(
    pool: AsyncConnectionPool,
    subject: str,
    predicate: str,
    new_object: str,
) -> list[dict]:
    """Check if a new fact contradicts existing active facts."""
    rows = await fetch_all(
        pool,
        "SELECT * FROM check_contradiction(%s, %s, %s)",
        (subject, predicate, new_object),
    )
    return [
        {
            "fact_id": str(row["fact_id"]),
            "existing_object": row["existing_object"],
            "valid_from": str(row["valid_from"]),
        }
        for row in rows
    ]


VALID_DECISIONS = {"committed", "rejected"}


async def hivemem_approve_pending(
    pool: AsyncConnectionPool,
    ids: list[str],
    decision: str,
) -> dict:
    """Approve or reject pending items (drawers and facts)."""
    if decision not in VALID_DECISIONS:
        raise ValueError(f"Invalid decision '{decision}'. Must be 'committed' or 'rejected'.")
    async with pool.connection() as conn:
        cur = await conn.execute(
            "WITH updated AS (UPDATE drawers SET status = %s WHERE id = ANY(%s::uuid[]) AND status = 'pending' RETURNING id) SELECT count(*) AS cnt FROM updated",
            (decision, ids),
        )
        drawer_row = await cur.fetchone()
        cur = await conn.execute(
            "WITH updated AS (UPDATE facts SET status = %s WHERE id = ANY(%s::uuid[]) AND status = 'pending' RETURNING id) SELECT count(*) AS cnt FROM updated",
            (decision, ids),
        )
        fact_row = await cur.fetchone()
        cur = await conn.execute(
            "WITH updated AS (UPDATE tunnels SET status = %s WHERE id = ANY(%s::uuid[]) AND status = 'pending' RETURNING id) SELECT count(*) AS cnt FROM updated",
            (decision, ids),
        )
        tunnel_row = await cur.fetchone()
        await conn.commit()
    count = (drawer_row["cnt"] if drawer_row else 0) + (fact_row["cnt"] if fact_row else 0) + (tunnel_row["cnt"] if tunnel_row else 0)
    return {"decision": decision, "count": count}


async def hivemem_add_reference(
    pool: AsyncConnectionPool,
    title: str,
    url: str | None = None,
    author: str | None = None,
    ref_type: str | None = None,
    status: str = "read",
    notes: str | None = None,
    tags: list[str] | None = None,
    importance: int | None = None,
) -> dict:
    """Add a source reference."""
    tags_val = tags or []
    row = await fetch_one(
        pool,
        """
        INSERT INTO references_ (title, url, author, ref_type, status, notes, tags, importance)
        VALUES (%s, %s, %s, %s, %s, %s, %s, %s)
        RETURNING id, title, status
        """,
        (title, url, author, ref_type, status, notes, tags_val, importance),
    )
    return {"id": str(row["id"]), "title": row["title"], "status": row["status"]}


async def hivemem_link_reference(
    pool: AsyncConnectionPool,
    drawer_id: str,
    reference_id: str,
    relation: str = "source",
) -> dict:
    """Link a reference to a drawer."""
    row = await fetch_one(
        pool,
        """
        INSERT INTO drawer_references (drawer_id, reference_id, relation)
        VALUES (%s, %s, %s)
        RETURNING id
        """,
        (drawer_id, reference_id, relation),
    )
    return {"id": str(row["id"]), "drawer_id": drawer_id, "reference_id": reference_id, "relation": relation}


async def hivemem_register_agent(
    pool: AsyncConnectionPool,
    name: str,
    focus: str,
    autonomy: dict | None = None,
    schedule: str | None = None,
    model_routing: dict | None = None,
    tools: list[str] | None = None,
) -> dict:
    """Register or update an agent in the fleet."""
    import json
    autonomy_json = json.dumps(autonomy or {"default": "suggest_only"})
    routing_json = json.dumps(model_routing) if model_routing else None
    tools_val = tools or []
    row = await fetch_one(
        pool,
        """
        INSERT INTO agents (name, focus, autonomy, schedule, model_routing, tools)
        VALUES (%s, %s, %s::jsonb, %s, %s::jsonb, %s)
        ON CONFLICT (name) DO UPDATE
            SET focus = EXCLUDED.focus,
                autonomy = EXCLUDED.autonomy,
                schedule = EXCLUDED.schedule,
                model_routing = EXCLUDED.model_routing,
                tools = EXCLUDED.tools
        RETURNING name, focus
        """,
        (name, focus, autonomy_json, schedule, routing_json, tools_val),
    )
    return {"name": row["name"], "focus": row["focus"]}


async def hivemem_diary_write(
    pool: AsyncConnectionPool,
    agent: str,
    entry: str,
) -> dict:
    """Write an entry to an agent's diary."""
    row = await fetch_one(
        pool,
        "INSERT INTO agent_diary (agent, entry) VALUES (%s, %s) RETURNING id",
        (agent, entry),
    )
    return {"id": str(row["id"]), "agent": agent}


async def hivemem_update_blueprint(
    pool: AsyncConnectionPool,
    wing: str,
    title: str,
    narrative: str,
    hall_order: list[str] | None = None,
    key_drawers: list[str] | None = None,
    created_by: str | None = None,
) -> dict:
    """Create or update a Blueprint (append-only, atomic, serialized per wing)."""
    async with pool.connection() as conn:
        # Advisory lock prevents concurrent blueprint updates for the same wing
        await conn.execute("SELECT pg_advisory_xact_lock(hashtext(%s))", (f"blueprint:{wing}",))
        await conn.execute(
            "UPDATE blueprints SET valid_until = now() WHERE wing = %s AND valid_until IS NULL",
            (wing,),
        )
        cur = await conn.execute(
            """INSERT INTO blueprints (wing, title, narrative, hall_order, key_drawers, created_by)
               VALUES (%s, %s, %s, %s, %s::uuid[], %s)
               RETURNING id, wing, title""",
            (wing, title, narrative, hall_order or [], key_drawers or [], created_by),
        )
        row = await cur.fetchone()
        await conn.commit()
    return {
        "id": str(row["id"]),
        "wing": row["wing"],
        "title": row["title"],
    }


async def hivemem_update_identity(
    pool: AsyncConnectionPool,
    key: str,
    content: str,
) -> dict:
    """UPSERT into identity with rough token_count (len//4)."""
    token_count = len(content) // 4
    await execute(
        pool,
        """
        INSERT INTO identity (key, content, token_count, updated_at)
        VALUES (%s, %s, %s, now())
        ON CONFLICT (key) DO UPDATE
            SET content = EXCLUDED.content,
                token_count = EXCLUDED.token_count,
                updated_at = now()
        """,
        (key, content, token_count),
    )
    return {"key": key, "token_count": token_count}


async def hivemem_add_tunnel(
    pool: AsyncConnectionPool,
    from_drawer: str,
    to_drawer: str,
    relation: str,
    note: str | None = None,
    status: str = "committed",
    created_by: str = "system",
) -> dict:
    """Create a drawer-to-drawer tunnel (link)."""
    row = await fetch_one(
        pool,
        """
        INSERT INTO tunnels (from_drawer, to_drawer, relation, note, status, created_by)
        VALUES (%s, %s, %s, %s, %s, %s)
        RETURNING id, from_drawer, to_drawer, relation, note, status
        """,
        (from_drawer, to_drawer, relation, note, status, created_by),
    )
    return {
        "id": str(row["id"]),
        "from_drawer": str(row["from_drawer"]),
        "to_drawer": str(row["to_drawer"]),
        "relation": row["relation"],
        "note": row["note"],
        "status": row["status"],
    }


async def hivemem_remove_tunnel(
    pool: AsyncConnectionPool,
    tunnel_id: str,
) -> dict:
    """Soft-delete a tunnel by setting valid_until to now()."""
    await execute(
        pool,
        "UPDATE tunnels SET valid_until = now() WHERE id = %s AND valid_until IS NULL",
        (tunnel_id,),
    )
    return {"id": tunnel_id, "removed": True}
