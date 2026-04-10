"""Shared test fixtures — spins up a PostgreSQL testcontainer with pgvector + AGE."""

import os
import subprocess

import psycopg
import pytest
from testcontainers.core.container import DockerContainer
from testcontainers.core.waiting_utils import wait_for_logs


@pytest.fixture(scope="session", autouse=True)
def test_db():
    """Build testdb image and start a container for the entire test session."""
    # Build the test DB image (pgvector + AGE)
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

    # Set env var so db_url fixture and any direct os.environ reads work
    os.environ["HIVEMEM_TEST_DB_URL"] = db_url

    yield db_url

    container.stop()


@pytest.fixture
def db_url(test_db):
    return test_db


@pytest.fixture(autouse=True)
def _mock_embeddings(monkeypatch):
    """Replace real BGE-M3 embedding with a fast dummy (no torch needed)."""
    import random

    def dummy_encode(text, return_sparse=False):
        random.seed(hash(text) % (2**32))
        vec = [random.gauss(0, 1) for _ in range(1024)]
        if return_sparse:
            return {"dense": vec, "sparse": {}}
        return vec

    def dummy_encode_query(text):
        return dummy_encode(text)

    monkeypatch.setattr("hivemem.embeddings.encode", dummy_encode)
    monkeypatch.setattr("hivemem.embeddings.encode_query", dummy_encode_query)
