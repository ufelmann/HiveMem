"""Read-only tools for querying HiveMem."""

from __future__ import annotations

from datetime import datetime

from psycopg_pool import AsyncConnectionPool

from hivemem.db import fetch_all, fetch_one
from hivemem.embeddings import encode_query


async def hivemem_status(pool: AsyncConnectionPool) -> dict:
    """Counts of drawers, facts, edges, wings list, and last activity."""
    drawer_count = await fetch_one(pool, "SELECT count(*) AS cnt FROM active_drawers")
    fact_count = await fetch_one(pool, "SELECT count(*) AS cnt FROM active_facts")
    edge_count = await fetch_one(pool, "SELECT count(*) AS cnt FROM edges")
    pending_count = await fetch_one(pool, "SELECT count(*) AS cnt FROM pending_approvals")
    wings = await fetch_all(pool, "SELECT DISTINCT wing FROM active_drawers WHERE wing IS NOT NULL ORDER BY wing")
    last_activity = await fetch_one(
        pool,
        "SELECT created_at FROM drawers ORDER BY created_at DESC LIMIT 1",
    )
    return {
        "drawers": drawer_count["cnt"],
        "facts": fact_count["cnt"],
        "edges": edge_count["cnt"],
        "pending": pending_count["cnt"],
        "wings": [w["wing"] for w in wings],
        "last_activity": str(last_activity["created_at"]) if last_activity else None,
    }


async def hivemem_search(
    pool: AsyncConnectionPool,
    query: str,
    limit: int = 10,
    wing: str | None = None,
) -> list[dict]:
    """Semantic search over active drawers using encode_query + vector cosine distance."""
    vector = encode_query(query)
    vector_str = str(vector)

    if wing:
        sql = """
            SELECT id, content, summary, wing, room, hall, importance,
                   1 - (embedding <=> %s::vector) AS similarity
            FROM active_drawers
            WHERE wing = %s AND embedding IS NOT NULL
            ORDER BY embedding <=> %s::vector
            LIMIT %s
        """
        rows = await fetch_all(pool, sql, (vector_str, wing, vector_str, limit))
    else:
        sql = """
            SELECT id, content, summary, wing, room, hall, importance,
                   1 - (embedding <=> %s::vector) AS similarity
            FROM active_drawers
            WHERE embedding IS NOT NULL
            ORDER BY embedding <=> %s::vector
            LIMIT %s
        """
        rows = await fetch_all(pool, sql, (vector_str, vector_str, limit))

    return [
        {
            "id": str(row["id"]),
            "content": row["content"],
            "summary": row["summary"],
            "wing": row["wing"],
            "room": row["room"],
            "hall": row["hall"],
            "importance": row["importance"],
            "similarity": float(row["similarity"]),
        }
        for row in rows
    ]


async def hivemem_search_kg(
    pool: AsyncConnectionPool,
    subject: str | None = None,
    predicate: str | None = None,
    object_: str | None = None,
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
    sql = f"SELECT id, subject, predicate, object, confidence, valid_from, valid_until FROM active_facts WHERE {where} ORDER BY created_at DESC"
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
                  importance, summary, status, created_by, created_at, valid_from, valid_until
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
    entity: str,
    max_depth: int = 2,
) -> list[dict]:
    """Recursive CTE graph traversal on the edges table."""
    sql = """
        WITH RECURSIVE graph AS (
            SELECT from_entity, to_entity, relation, weight, 1 AS depth
            FROM edges
            WHERE from_entity = %s
            UNION ALL
            SELECT e.from_entity, e.to_entity, e.relation, e.weight, g.depth + 1
            FROM edges e
            JOIN graph g ON e.from_entity = g.to_entity
            WHERE g.depth < %s
        )
        SELECT DISTINCT from_entity, to_entity, relation, weight, depth
        FROM graph
        ORDER BY depth, from_entity
    """
    rows = await fetch_all(pool, sql, (entity, max_depth))
    return [
        {
            "from_entity": row["from_entity"],
            "to_entity": row["to_entity"],
            "relation": row["relation"],
            "weight": float(row["weight"]),
            "depth": row["depth"],
        }
        for row in rows
    ]


async def hivemem_time_machine(
    pool: AsyncConnectionPool,
    subject: str,
    as_of: datetime | None = None,
) -> list[dict]:
    """Facts valid at a point in time."""
    if as_of is None:
        sql = """
            SELECT id, subject, predicate, object, confidence, valid_from, valid_until
            FROM active_facts
            WHERE subject ILIKE %s
            ORDER BY valid_from DESC
        """
        rows = await fetch_all(pool, sql, (f"%{subject}%",))
    else:
        sql = """
            SELECT id, subject, predicate, object, confidence, valid_from, valid_until
            FROM facts
            WHERE subject ILIKE %s
              AND valid_from <= %s
              AND (valid_until IS NULL OR valid_until > %s)
              AND status = 'committed'
            ORDER BY valid_from DESC
        """
        rows = await fetch_all(pool, sql, (f"%{subject}%", as_of, as_of))

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
