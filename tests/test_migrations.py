"""Test that yoyo migrations apply correctly."""

import os

import psycopg


def test_yoyo_tracking_table_exists(db_url):
    """yoyo creates its tracking table after migration."""
    with psycopg.connect(db_url, autocommit=True) as conn:
        row = conn.execute(
            "SELECT EXISTS (SELECT 1 FROM information_schema.tables WHERE table_name = '_yoyo_migration')"
        ).fetchone()
    assert row[0] is True


def test_all_migrations_applied(db_url):
    """All migration files are marked as applied."""
    project_root = os.path.join(os.path.dirname(__file__), "..")
    migrations_dir = os.path.join(project_root, "migrations")

    from yoyo import get_backend, read_migrations

    yoyo_url = db_url.replace("postgresql://", "postgresql+psycopg://")
    backend = get_backend(yoyo_url)
    all_migrations = read_migrations(migrations_dir)
    pending = backend.to_apply(all_migrations)
    assert len(list(pending)) == 0, f"Pending migrations: {[m.id for m in pending]}"


def test_rerun_migrations_idempotent(db_url):
    """Re-applying all migrations does not error."""
    project_root = os.path.join(os.path.dirname(__file__), "..")
    migrations_dir = os.path.join(project_root, "migrations")

    from yoyo import get_backend, read_migrations

    yoyo_url = db_url.replace("postgresql://", "postgresql+psycopg://")
    backend = get_backend(yoyo_url)
    all_migrations = read_migrations(migrations_dir)
    pending = backend.to_apply(all_migrations)
    assert len(list(pending)) == 0


def test_final_schema_has_v2_edges(db_url):
    """After all migrations, edges table has v2 columns."""
    with psycopg.connect(db_url, autocommit=True) as conn:
        row = conn.execute(
            "SELECT column_name FROM information_schema.columns "
            "WHERE table_name = 'edges' AND column_name = 'from_drawer'"
        ).fetchone()
    assert row is not None


def test_final_schema_has_active_edges_view(db_url):
    """After all migrations, active_edges view exists."""
    with psycopg.connect(db_url, autocommit=True) as conn:
        row = conn.execute(
            "SELECT EXISTS (SELECT 1 FROM information_schema.views WHERE table_name = 'active_edges')"
        ).fetchone()
    assert row[0] is True
