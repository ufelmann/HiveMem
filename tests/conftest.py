"""Shared test fixtures — testcontainer with pgvector + AGE, mock embeddings."""

import hashlib
import math
import os
import subprocess
import sys

import psycopg
import pytest
from testcontainers.core.container import DockerContainer
from testcontainers.core.waiting_utils import wait_for_logs


# ── Mock embeddings at import time (before any hivemem module loads) ───


def _word_hash_embed(text, dim=1024):
    """Deterministic embedding: each word hashes to fixed positions."""
    vec = [0.0] * dim
    words = text.lower().split()
    for word in words:
        h = hashlib.md5(word.encode()).digest()
        for i in range(0, len(h), 2):
            idx = (h[i] << 8 | h[i + 1]) % dim
            vec[idx] += 1.0
    norm = math.sqrt(sum(x * x for x in vec)) or 1.0
    return [x / norm for x in vec]


# Patch before hivemem.embeddings is imported by any test module
import hivemem.embeddings  # noqa: E402

hivemem.embeddings.encode = lambda text, return_sparse=False: (
    {"dense": _word_hash_embed(text), "sparse": {}} if return_sparse else _word_hash_embed(text)
)
hivemem.embeddings.encode_query = lambda text: _word_hash_embed(text)


# ── Testcontainer ──────────────────────────────────────────────────────


@pytest.fixture(scope="session", autouse=True)
def test_db():
    """Build testdb image and start a container for the entire test session."""
    project_root = os.path.join(os.path.dirname(__file__), "..")
    subprocess.run(
        ["docker", "build", "-f", "Dockerfile.testdb", "-t", "hivemem-testdb:latest", "."],
        cwd=project_root,
        check=True,
        capture_output=True,
    )

    container = (
        DockerContainer("hivemem-testdb:latest")
        .with_env("POSTGRES_USER", "hivemem")
        .with_env("POSTGRES_PASSWORD", "test")
        .with_env("POSTGRES_DB", "hivemem_test")
        .with_exposed_ports(5432)
    )
    container.start()
    wait_for_logs(container, "database system is ready to accept connections", timeout=30)

    host = container.get_container_host_ip()
    port = container.get_exposed_port(5432)
    db_url = f"postgresql://hivemem:test@{host}:{port}/hivemem_test"

    # Apply schema
    with psycopg.connect(db_url, autocommit=True) as conn:
        schema_path = os.path.join(project_root, "hivemem", "schema.sql")
        with open(schema_path) as f:
            conn.execute(f.read())

    os.environ["HIVEMEM_TEST_DB_URL"] = db_url

    yield db_url

    container.stop()


@pytest.fixture
def db_url(test_db):
    return test_db


@pytest.fixture
async def pool(db_url):
    """Create a standalone pool per test (no global cache). Clean up data after."""
    from psycopg.rows import dict_row
    from psycopg_pool import AsyncConnectionPool

    p = AsyncConnectionPool(
        db_url, min_size=1, max_size=5, open=False,
        kwargs={"row_factory": dict_row},
    )
    await p.open()
    yield p

    # Clean up data
    async with p.connection() as conn:
        await conn.execute("DELETE FROM access_log")
        await conn.execute("DELETE FROM facts")
        await conn.execute("DELETE FROM drawers")
        await conn.commit()
    await p.close()
