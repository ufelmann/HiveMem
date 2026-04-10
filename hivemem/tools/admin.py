"""Admin tools for HiveMem health and diagnostics."""

from __future__ import annotations

import shutil

from psycopg_pool import AsyncConnectionPool

from hivemem.db import fetch_all, fetch_one


async def hivemem_health(pool: AsyncConnectionPool) -> dict:
    """Check DB connection, extension versions, counts, db size, disk free."""
    # DB connection check
    check = await fetch_one(pool, "SELECT 1 AS ok")
    db_ok = check is not None and check["ok"] == 1

    # Extension versions
    extensions = await fetch_all(
        pool,
        "SELECT extname, extversion FROM pg_extension WHERE extname IN ('vector', 'age')",
    )
    ext_versions = {row["extname"]: row["extversion"] for row in extensions}

    # Counts
    drawer_count = await fetch_one(pool, "SELECT count(*) AS cnt FROM drawers")
    fact_count = await fetch_one(pool, "SELECT count(*) AS cnt FROM facts")

    # Database size
    db_size = await fetch_one(
        pool, "SELECT pg_size_pretty(pg_database_size(current_database())) AS size"
    )

    # Disk free (local machine)
    disk = shutil.disk_usage("/")
    disk_free_gb = round(disk.free / (1024**3), 2)

    return {
        "db_connected": db_ok,
        "extensions": ext_versions,
        "drawers": drawer_count["cnt"],
        "facts": fact_count["cnt"],
        "db_size": db_size["size"],
        "disk_free_gb": disk_free_gb,
    }
