"""Write tools for modifying HiveMem."""

from __future__ import annotations

from datetime import datetime

from psycopg_pool import AsyncConnectionPool

from hivemem.db import execute, fetch_one
from hivemem.embeddings import encode


async def hivemem_add_drawer(
    pool: AsyncConnectionPool,
    content: str,
    wing: str | None = None,
    room: str | None = None,
    hall: str | None = None,
    source: str | None = None,
    tags: list[str] | None = None,
    valid_from: datetime | None = None,
) -> dict:
    """Encode content, insert into drawers, return {id, wing, room}."""
    vector = encode(content)
    vector_str = str(vector)
    tags_val = tags or []

    row = await fetch_one(
        pool,
        """
        INSERT INTO drawers (content, embedding, wing, room, hall, source, tags, valid_from)
        VALUES (%s, %s::vector, %s, %s, %s, %s, %s, COALESCE(%s, now()))
        RETURNING id, wing, room
        """,
        (content, vector_str, wing, room, hall, source, tags_val, valid_from),
    )
    return {
        "id": str(row["id"]),
        "wing": row["wing"],
        "room": row["room"],
    }


async def hivemem_kg_add(
    pool: AsyncConnectionPool,
    subject: str,
    predicate: str,
    object_: str,
    confidence: float = 1.0,
    source_id: str | None = None,
    valid_from: datetime | None = None,
) -> dict:
    """Insert into facts, return {id, subject, predicate, object}."""
    row = await fetch_one(
        pool,
        """
        INSERT INTO facts (subject, predicate, object, confidence, source_id, valid_from)
        VALUES (%s, %s, %s, %s, %s, COALESCE(%s, now()))
        RETURNING id, subject, predicate, object
        """,
        (subject, predicate, object_, confidence, source_id, valid_from),
    )
    return {
        "id": str(row["id"]),
        "subject": row["subject"],
        "predicate": row["predicate"],
        "object": row["object"],
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
