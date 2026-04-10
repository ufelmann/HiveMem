"""Seed the identity layers with an example profile.

Customize L0_IDENTITY and L1_CRITICAL with your own information before running.
"""

import asyncio
import os
import sys

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

from hivemem.db import get_pool
from hivemem.tools.write import hivemem_update_identity

DB_URL = os.environ.get(
    "HIVEMEM_DB_URL",
    "postgresql://hivemem:hivemem_local_only@localhost:5432/hivemem",
)

# ── CUSTOMIZE THESE WITH YOUR OWN IDENTITY ──────────────────────────────

L0_IDENTITY = """Alice Example. Software engineer at Acme Corp.
Tech lead for the platform team (8-12 people).
Core products: API Gateway, Auth Service, Data Pipeline.
Tech stack: Python, PostgreSQL, Docker, Redis."""

L1_CRITICAL = """Current focus: HiveMem knowledge system deployment.
Active projects: API Gateway v2 migration, Auth service refactor.
Preferences: PostgreSQL over NoSQL, trunk-based dev, small PRs, clear documentation."""


async def main():
    pool = await get_pool(DB_URL)
    await hivemem_update_identity(pool, key="l0_identity", content=L0_IDENTITY)
    await hivemem_update_identity(pool, key="l1_critical", content=L1_CRITICAL)
    print("Identity seeded:")
    print(f"  L0: {len(L0_IDENTITY)} chars")
    print(f"  L1: {len(L1_CRITICAL)} chars")
    await pool.close()


if __name__ == "__main__":
    asyncio.run(main())
