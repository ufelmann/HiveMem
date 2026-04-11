"""Read-only tools for querying HiveMem."""

from __future__ import annotations

import asyncio
from datetime import datetime

from psycopg_pool import AsyncConnectionPool

from hivemem.db import fetch_all, fetch_one, execute
from hivemem.embeddings import encode_query


async def hivemem_status(pool: AsyncConnectionPool) -> dict:
    """Counts of drawers, facts, edges, wings list, and last activity."""
    counts = await fetch_one(
        pool,
        """SELECT
            (SELECT count(*) FROM active_drawers) AS drawers,
            (SELECT count(*) FROM active_facts) AS facts,
            (SELECT count(*) FROM active_edges) AS edges,
            (SELECT count(*) FROM pending_approvals) AS pending,
            (SELECT max(created_at) FROM drawers) AS last_activity""",
    )
    wings = await fetch_all(
        pool,
        "SELECT DISTINCT wing FROM active_drawers WHERE wing IS NOT NULL ORDER BY wing",
    )
    return {
        "drawers": counts["drawers"],
        "facts": counts["facts"],
        "edges": counts["edges"],
        "pending": counts["pending"],
        "last_activity": counts["last_activity"].isoformat() if counts["last_activity"] else None,
        "wings": [w["wing"] for w in wings],
    }


async def hivemem_search(
    pool: AsyncConnectionPool,
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
    """5-signal ranked search: semantic + keyword + recency + importance + popularity."""
    vector = await asyncio.to_thread(encode_query, query)
    vector_str = str(vector)

    rows = await fetch_all(
        pool,
        """SELECT * FROM ranked_search(
            %s::vector, %s::text, %s::text, %s::text, %s::text, %s::integer,
            %s::real, %s::real, %s::real, %s::real, %s::real
        )""",
        (vector_str, query, wing, room, hall, limit,
         weight_semantic, weight_keyword, weight_recency,
         weight_importance, weight_popularity),
    )
    return [
        {
            "id": str(row["id"]),
            "content": row["content"],
            "summary": row["summary"],
            "wing": row["wing"],
            "room": row["room"],
            "hall": row["hall"],
            "tags": row["tags"] or [],
            "importance": row["importance"],
            "created_at": str(row["created_at"]),
            "score_semantic": round(float(row["score_semantic"]), 4),
            "score_keyword": round(float(row["score_keyword"]), 4),
            "score_recency": round(float(row["score_recency"]), 4),
            "score_importance": round(float(row["score_importance"]), 4),
            "score_popularity": round(float(row["score_popularity"]), 4),
            "score_total": round(float(row["score_total"]), 4),
        }
        for row in rows
    ]


async def hivemem_search_kg(
    pool: AsyncConnectionPool,
    subject: str | None = None,
    predicate: str | None = None,
    object_: str | None = None,
    limit: int = 100,
) -> list[dict]:
    """ILIKE search on active facts."""
    conditions = []
    params = []
    if subject:
        conditions.append("subject ILIKE %s")
        params.append(f"%{subject}%")
    if predicate:
        conditions.append("predicate ILIKE %s")
        params.append(f"%{predicate}%")
    if object_:
        conditions.append("object ILIKE %s")
        params.append(f"%{object_}%")

    where = " AND ".join(conditions) if conditions else "TRUE"
    sql = f"SELECT id, subject, predicate, object, confidence, valid_from, valid_until FROM active_facts WHERE {where} ORDER BY created_at DESC LIMIT %s"
    params.append(limit)
    rows = await fetch_all(pool, sql, tuple(params))
    return [
        {
            "id": str(row["id"]),
            "subject": row["subject"],
            "predicate": row["predicate"],
            "object": row["object"],
            "confidence": float(row["confidence"]),
            "valid_from": str(row["valid_from"]) if row["valid_from"] else None,
            "valid_until": str(row["valid_until"]) if row["valid_until"] else None,
        }
        for row in rows
    ]


async def hivemem_get_drawer(pool: AsyncConnectionPool, drawer_id: str) -> dict | None:
    """Get a single drawer by UUID."""
    row = await fetch_one(
        pool,
        """SELECT id, parent_id, content, wing, room, hall, source, tags,
                  importance, summary, key_points, insight, actionability,
                  status, created_by, created_at, valid_from, valid_until
           FROM drawers WHERE id = %s""",
        (drawer_id,),
    )
    if not row:
        return None
    return {
        "id": str(row["id"]),
        "parent_id": str(row["parent_id"]) if row["parent_id"] else None,
        "content": row["content"],
        "wing": row["wing"],
        "room": row["room"],
        "hall": row["hall"],
        "source": row["source"],
        "tags": row["tags"] or [],
        "importance": row["importance"],
        "summary": row["summary"],
        "key_points": row["key_points"] or [],
        "insight": row["insight"],
        "actionability": row["actionability"],
        "status": row["status"],
        "created_by": row["created_by"],
        "created_at": str(row["created_at"]),
        "valid_from": str(row["valid_from"]),
        "valid_until": str(row["valid_until"]) if row["valid_until"] else None,
    }


async def hivemem_list_wings(pool: AsyncConnectionPool) -> list[dict]:
    """Wing stats from active drawers."""
    sql = """
        SELECT wing,
               count(DISTINCT room) AS room_count,
               count(*) AS drawer_count
        FROM active_drawers
        WHERE wing IS NOT NULL
        GROUP BY wing
        ORDER BY wing
    """
    rows = await fetch_all(pool, sql)
    return [
        {
            "wing": row["wing"],
            "room_count": row["room_count"],
            "drawer_count": row["drawer_count"],
        }
        for row in rows
    ]


async def hivemem_list_rooms(pool: AsyncConnectionPool, wing: str) -> list[dict]:
    """List rooms within a wing from active drawers."""
    sql = """
        SELECT room, count(*) AS drawer_count
        FROM active_drawers
        WHERE wing = %s AND room IS NOT NULL
        GROUP BY room
        ORDER BY room
    """
    rows = await fetch_all(pool, sql, (wing,))
    return [
        {"room": row["room"], "drawer_count": row["drawer_count"]}
        for row in rows
    ]


async def hivemem_traverse(
    pool: AsyncConnectionPool,
    drawer_id: str,
    max_depth: int = 2,
    relation_filter: str | None = None,
) -> list[dict]:
    """Bidirectional recursive CTE graph traversal on active_edges."""
    if relation_filter:
        sql = """
            WITH RECURSIVE
            bidir AS (
                SELECT from_drawer AS node, to_drawer AS neighbor, from_drawer, to_drawer, relation, note
                FROM active_edges WHERE relation = %s
                UNION ALL
                SELECT to_drawer AS node, from_drawer AS neighbor, from_drawer, to_drawer, relation, note
                FROM active_edges WHERE relation = %s
            ),
            graph AS (
                SELECT from_drawer, to_drawer, relation, note, neighbor, 1 AS depth
                FROM bidir
                WHERE node = %s
                UNION
                SELECT b.from_drawer, b.to_drawer, b.relation, b.note, b.neighbor, g.depth + 1
                FROM bidir b
                JOIN graph g ON b.node = g.neighbor
                WHERE g.depth < %s
            )
            SELECT DISTINCT from_drawer, to_drawer, relation, note, depth
            FROM graph
            ORDER BY depth, from_drawer
        """
        rows = await fetch_all(pool, sql, (
            relation_filter, relation_filter,
            drawer_id,
            max_depth,
        ))
    else:
        sql = """
            WITH RECURSIVE
            bidir AS (
                SELECT from_drawer AS node, to_drawer AS neighbor, from_drawer, to_drawer, relation, note
                FROM active_edges
                UNION ALL
                SELECT to_drawer AS node, from_drawer AS neighbor, from_drawer, to_drawer, relation, note
                FROM active_edges
            ),
            graph AS (
                SELECT from_drawer, to_drawer, relation, note, neighbor, 1 AS depth
                FROM bidir
                WHERE node = %s
                UNION
                SELECT b.from_drawer, b.to_drawer, b.relation, b.note, b.neighbor, g.depth + 1
                FROM bidir b
                JOIN graph g ON b.node = g.neighbor
                WHERE g.depth < %s
            )
            SELECT DISTINCT from_drawer, to_drawer, relation, note, depth
            FROM graph
            ORDER BY depth, from_drawer
        """
        rows = await fetch_all(pool, sql, (
            drawer_id,
            max_depth,
        ))

    return [
        {
            "from_drawer": str(row["from_drawer"]),
            "to_drawer": str(row["to_drawer"]),
            "relation": row["relation"],
            "note": row["note"],
            "depth": row["depth"],
        }
        for row in rows
    ]


async def hivemem_quick_facts(
    pool: AsyncConnectionPool,
    entity: str,
) -> list[dict]:
    """Get all active facts about an entity (as subject or object)."""
    rows = await fetch_all(
        pool,
        "SELECT * FROM quick_facts(%s)",
        (entity,),
    )
    return [
        {
            "id": str(row["id"]),
            "subject": row["subject"],
            "predicate": row["predicate"],
            "object": row["object"],
            "confidence": float(row["confidence"]),
            "valid_from": str(row["valid_from"]),
        }
        for row in rows
    ]


async def hivemem_time_machine(
    pool: AsyncConnectionPool,
    subject: str,
    as_of: datetime | None = None,
    limit: int = 100,
) -> list[dict]:
    """Facts valid at a point in time."""
    if as_of is None:
        sql = """
            SELECT id, subject, predicate, object, confidence, valid_from, valid_until
            FROM active_facts
            WHERE subject ILIKE %s
            ORDER BY valid_from DESC
            LIMIT %s
        """
        rows = await fetch_all(pool, sql, (f"%{subject}%", limit))
    else:
        sql = """
            SELECT id, subject, predicate, object, confidence, valid_from, valid_until
            FROM facts
            WHERE subject ILIKE %s
              AND valid_from <= %s
              AND (valid_until IS NULL OR valid_until > %s)
              AND status = 'committed'
            ORDER BY valid_from DESC
            LIMIT %s
        """
        rows = await fetch_all(pool, sql, (f"%{subject}%", as_of, as_of, limit))

    return [
        {
            "id": str(row["id"]),
            "subject": row["subject"],
            "predicate": row["predicate"],
            "object": row["object"],
            "confidence": float(row["confidence"]),
            "valid_from": str(row["valid_from"]),
            "valid_until": str(row["valid_until"]) if row["valid_until"] else None,
        }
        for row in rows
    ]


async def hivemem_drawer_history(
    pool: AsyncConnectionPool,
    drawer_id: str,
) -> list[dict]:
    """Get all versions of a drawer via parent_id chain."""
    rows = await fetch_all(
        pool,
        "SELECT * FROM drawer_history(%s)",
        (drawer_id,),
    )
    return [
        {
            "id": str(row["id"]),
            "parent_id": str(row["parent_id"]) if row["parent_id"] else None,
            "summary": row["summary"],
            "created_by": row["created_by"],
            "valid_from": str(row["valid_from"]),
            "valid_until": str(row["valid_until"]) if row["valid_until"] else None,
        }
        for row in rows
    ]


async def hivemem_fact_history(
    pool: AsyncConnectionPool,
    fact_id: str,
) -> list[dict]:
    """Get all versions of a fact via parent_id chain."""
    rows = await fetch_all(
        pool,
        "SELECT * FROM fact_history(%s)",
        (fact_id,),
    )
    return [
        {
            "id": str(row["id"]),
            "parent_id": str(row["parent_id"]) if row["parent_id"] else None,
            "subject": row["subject"],
            "predicate": row["predicate"],
            "object": row["object"],
            "created_by": row["created_by"],
            "valid_from": str(row["valid_from"]),
            "valid_until": str(row["valid_until"]) if row["valid_until"] else None,
        }
        for row in rows
    ]


async def hivemem_pending_approvals(pool: AsyncConnectionPool) -> list[dict]:
    """List all pending agent suggestions."""
    rows = await fetch_all(pool, "SELECT * FROM pending_approvals")
    return [
        {
            "type": row["type"],
            "id": str(row["id"]),
            "description": row["description"],
            "wing": row["wing"],
            "room": row["room"],
            "created_by": row["created_by"],
            "created_at": str(row["created_at"]),
        }
        for row in rows
    ]


async def hivemem_log_access(
    pool: AsyncConnectionPool,
    drawer_id: str | None = None,
    fact_id: str | None = None,
    accessed_by: str = "user",
) -> dict:
    """Log an access event for popularity tracking."""
    await execute(
        pool,
        "INSERT INTO access_log (drawer_id, fact_id, accessed_by) VALUES (%s, %s, %s)",
        (drawer_id, fact_id, accessed_by),
    )
    return {"logged": True}


async def hivemem_refresh_popularity(pool: AsyncConnectionPool) -> dict:
    """Refresh the drawer_popularity materialized view."""
    await execute(pool, "REFRESH MATERIALIZED VIEW CONCURRENTLY drawer_popularity")
    row = await fetch_one(pool, "SELECT count(*) AS cnt FROM drawer_popularity")
    return {"refreshed": True, "drawer_count": row["cnt"]}


async def hivemem_reading_list(
    pool: AsyncConnectionPool,
    ref_type: str | None = None,
    limit: int = 20,
) -> list[dict]:
    """Show unread and in-progress references."""
    if ref_type:
        sql = """
            SELECT r.id, r.title, r.url, r.author, r.ref_type, r.status, r.importance, r.created_at,
                   count(dr.id) AS linked_drawers
            FROM references_ r
            LEFT JOIN drawer_references dr ON dr.reference_id = r.id
            WHERE r.status IN ('unread', 'reading') AND r.ref_type = %s
            GROUP BY r.id
            ORDER BY r.importance ASC NULLS LAST, r.created_at DESC
            LIMIT %s
        """
        rows = await fetch_all(pool, sql, (ref_type, limit))
    else:
        sql = """
            SELECT r.id, r.title, r.url, r.author, r.ref_type, r.status, r.importance, r.created_at,
                   count(dr.id) AS linked_drawers
            FROM references_ r
            LEFT JOIN drawer_references dr ON dr.reference_id = r.id
            WHERE r.status IN ('unread', 'reading')
            GROUP BY r.id
            ORDER BY r.importance ASC NULLS LAST, r.created_at DESC
            LIMIT %s
        """
        rows = await fetch_all(pool, sql, (limit,))

    return [
        {
            "id": str(row["id"]),
            "title": row["title"],
            "url": row["url"],
            "author": row["author"],
            "ref_type": row["ref_type"],
            "status": row["status"],
            "importance": row["importance"],
            "linked_drawers": row["linked_drawers"],
            "created_at": str(row["created_at"]),
        }
        for row in rows
    ]


async def hivemem_list_agents(pool: AsyncConnectionPool) -> list[dict]:
    """List all registered agents."""
    rows = await fetch_all(pool, "SELECT name, focus, schedule, created_at FROM agents ORDER BY name")
    return [
        {
            "name": row["name"],
            "focus": row["focus"],
            "schedule": row["schedule"],
            "created_at": str(row["created_at"]),
        }
        for row in rows
    ]


async def hivemem_diary_read(
    pool: AsyncConnectionPool,
    agent: str,
    last_n: int = 10,
) -> list[dict]:
    """Read recent diary entries for an agent."""
    rows = await fetch_all(
        pool,
        "SELECT id, agent, entry, created_at FROM agent_diary WHERE agent = %s ORDER BY created_at DESC LIMIT %s",
        (agent, last_n),
    )
    return [
        {
            "id": str(row["id"]),
            "agent": row["agent"],
            "entry": row["entry"],
            "created_at": str(row["created_at"]),
        }
        for row in rows
    ]


async def hivemem_get_map(
    pool: AsyncConnectionPool,
    wing: str | None = None,
) -> list[dict]:
    """Get active Maps of Content."""
    if wing:
        sql = """SELECT id, wing, title, narrative, room_order, key_drawers, created_by, valid_from
                 FROM active_maps WHERE wing = %s ORDER BY valid_from DESC"""
        rows = await fetch_all(pool, sql, (wing,))
    else:
        sql = """SELECT id, wing, title, narrative, room_order, key_drawers, created_by, valid_from
                 FROM active_maps ORDER BY wing, valid_from DESC"""
        rows = await fetch_all(pool, sql)
    return [
        {
            "id": str(row["id"]),
            "wing": row["wing"],
            "title": row["title"],
            "narrative": row["narrative"],
            "room_order": row["room_order"] or [],
            "key_drawers": [str(d) for d in row["key_drawers"]] if row["key_drawers"] else [],
            "created_by": row["created_by"],
            "valid_from": str(row["valid_from"]),
        }
        for row in rows
    ]


async def hivemem_wake_up(pool: AsyncConnectionPool) -> dict:
    """Load l0_identity and l1_critical from identity table."""
    rows = await fetch_all(
        pool,
        "SELECT key, content, token_count FROM identity WHERE key IN ('l0_identity', 'l1_critical') ORDER BY key",
    )
    result = {}
    for row in rows:
        result[row["key"]] = {
            "content": row["content"],
            "token_count": row["token_count"],
        }
    return result
