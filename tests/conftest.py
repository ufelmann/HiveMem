"""Shared test fixtures."""

import os

import psycopg
import pytest

TEST_DB_URL = os.environ.get(
    "HIVEMEM_TEST_DB_URL",
    "postgresql://hivemem@/hivemem_test?host=/var/run/postgresql",
)


@pytest.fixture(scope="session", autouse=True)
def setup_test_db():
    """Create test database and apply schema."""
    admin_url = TEST_DB_URL.rsplit("/", 1)[0] + "/postgres"
    with psycopg.connect(admin_url, autocommit=True) as conn:
        conn.execute("DROP DATABASE IF EXISTS hivemem_test")
        conn.execute("CREATE DATABASE hivemem_test")

    with psycopg.connect(TEST_DB_URL, autocommit=True) as conn:
        schema_path = os.path.join(
            os.path.dirname(__file__), "..", "hivemem", "schema.sql"
        )
        with open(schema_path) as f:
            conn.execute(f.read())

    yield

    with psycopg.connect(admin_url, autocommit=True) as conn:
        conn.execute(
            "SELECT pg_terminate_backend(pid) FROM pg_stat_activity "
            "WHERE datname = 'hivemem_test' AND pid <> pg_backend_pid()"
        )
        conn.execute("DROP DATABASE IF EXISTS hivemem_test")


@pytest.fixture
def db_url():
    return TEST_DB_URL
