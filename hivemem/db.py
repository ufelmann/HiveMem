"""Database connection pool and helpers."""

from psycopg.rows import dict_row
from psycopg_pool import AsyncConnectionPool

_pools: dict[str, AsyncConnectionPool] = {}


async def get_pool(db_url: str) -> AsyncConnectionPool:
    """Get or create an async connection pool for the given URL."""
    if db_url not in _pools:
        pool = AsyncConnectionPool(
            db_url,
            min_size=2,
            max_size=20,
            open=False,
            kwargs={"row_factory": dict_row},
        )
        await pool.open()
        _pools[db_url] = pool
    return _pools[db_url]


async def close_pool(db_url: str) -> None:
    """Close and remove the pool for the given URL."""
    pool = _pools.pop(db_url, None)
    if pool:
        await pool.close()


async def execute(pool: AsyncConnectionPool, query: str, params: tuple = ()) -> None:
    """Execute a write query."""
    async with pool.connection() as conn:
        await conn.execute(query, params)
        await conn.commit()


async def fetch_one(
    pool: AsyncConnectionPool, query: str, params: tuple = ()
) -> dict | None:
    """Fetch a single row as dict."""
    async with pool.connection() as conn:
        cur = await conn.execute(query, params)
        return await cur.fetchone()


async def fetch_all(
    pool: AsyncConnectionPool, query: str, params: tuple = ()
) -> list[dict]:
    """Fetch all rows as list of dicts."""
    async with pool.connection() as conn:
        cur = await conn.execute(query, params)
        return await cur.fetchall()
