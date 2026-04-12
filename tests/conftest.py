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

def _mock_encode(text, return_sparse=False):
    dim = hivemem.embeddings.get_dimension()
    vec = _word_hash_embed(text, dim=dim)
    if return_sparse:
        return {"dense": vec, "sparse": {}}
    return vec

hivemem.embeddings.encode = _mock_encode
hivemem.embeddings.encode_query = lambda text: _mock_encode(text)


# ── Testcontainer ──────────────────────────────────────────────────────


import pytest
from unittest.mock import patch

@pytest.fixture(autouse=True, scope="session")
def mock_embeddings():
    """Globally mock embedding operations to prevent HuggingFace downloads during tests."""
    # Note: 384 is the dimension for paraphrase-multilingual-MiniLM-L6-v2
    with patch("hivemem.embeddings.get_dimension", return_value=384), \
         patch("hivemem.embeddings.encode", return_value=[0.1] * 384), \
         patch("hivemem.embeddings.encode_query", return_value=[0.1] * 384), \
         patch("hivemem.embeddings.get_model", return_value=None):
        yield

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
        .with_kwargs(security_opt=["apparmor=unconfined"])
    )
    container.start()
    wait_for_logs(container, "database system is ready to accept connections", timeout=30)

    host = container.get_container_host_ip()
    port = container.get_exposed_port(5432)
    db_url = f"postgresql://hivemem:test@{host}:{port}/hivemem_test"

    # Retry connection -- PG restarts after initdb, "ready" log appears twice
    import time
    for attempt in range(10):
        try:
            with psycopg.connect(db_url, autocommit=True) as conn:
                conn.execute("SELECT 1")
            break
        except psycopg.OperationalError:
            time.sleep(1)
    else:
        raise RuntimeError("Could not connect to test database after 10 attempts")

    # Apply migrations (same path as production)
    from yoyo import get_backend, read_migrations

    migrations_dir = os.path.join(project_root, "migrations")
    yoyo_url = db_url.replace("postgresql://", "postgresql+psycopg://")
    backend = get_backend(yoyo_url)
    all_migrations = read_migrations(migrations_dir)
    backend.apply_migrations(backend.to_apply(all_migrations))

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
        await conn.execute("DELETE FROM tunnels")
        await conn.execute("DELETE FROM access_log")
        await conn.execute("DELETE FROM facts")
        await conn.execute("DELETE FROM drawers")
        await conn.execute("DELETE FROM api_tokens")
        await conn.commit()
    await p.close()
