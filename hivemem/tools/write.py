"""Write tools for modifying HiveMem."""

from __future__ import annotations

from datetime import datetime

from psycopg_pool import AsyncConnectionPool

from hivemem.db import execute, fetch_one, fetch_all
from hivemem.embeddings import encode


async def hivemem_add_drawer(
    pool: AsyncConnectionPool,
    content: str,
    wing: str | None = None,
    room: str | None = None,
    hall: str | None = None,
    source: str | None = None,
    tags: list[str] | None = None,
    importance: int | None = None,
    summary: str | None = None,
    status: str = "committed",
    created_by: str | None = None,
    valid_from: datetime | None = None,
) -> dict:
    """Encode content, insert into drawers, return {id, wing, room, hall, status}."""
    vector = encode(content)
    vector_str = str(vector)
    tags_val = tags or []

    row = await fetch_one(
        pool,
        """
        INSERT INTO drawers (content, embedding, wing, room, hall, source, tags,
                             importance, summary, status, created_by, valid_from)
        VALUES (%s, %s::vector, %s, %s, %s, %s, %s, %s, %s, %s, %s, COALESCE(%s, now()))
        RETURNING id, wing, room, hall, status
        """,
        (content, vector_str, wing, room, hall, source, tags_val,
         importance, summary, status, created_by, valid_from),
    )
    return {
        "id": str(row["id"]),
        "wing": row["wing"],
        "room": row["room"],
        "hall": row["hall"],
        "status": row["status"],
    }


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


async def hivemem_approve_pending(
    pool: AsyncConnectionPool,
    ids: list[str],
    decision: str,
) -> dict:
    """Approve or reject pending items (drawers and facts)."""
    count = 0
    for item_id in ids:
        await execute(
            pool,
            "UPDATE drawers SET status = %s WHERE id = %s AND status = 'pending'",
            (decision, item_id),
        )
        await execute(
            pool,
            "UPDATE facts SET status = %s WHERE id = %s AND status = 'pending'",
            (decision, item_id),
        )
        count += 1
    return {"decision": decision, "count": count}


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
