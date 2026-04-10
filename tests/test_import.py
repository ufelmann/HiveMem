"""Tests for import tools."""

import os
import tempfile

import pytest

from hivemem.db import close_pool, execute, fetch_one, get_pool
from hivemem.tools.import_tools import hivemem_mine_directory, hivemem_mine_file


@pytest.fixture
async def pool(db_url):
    """Get a connection pool for the test DB."""
    p = await get_pool(db_url)
    yield p
    await close_pool(db_url)


@pytest.fixture
async def clean_pool(pool):
    """Clean tables before import tests."""
    await execute(pool, "DELETE FROM edges")
    await execute(pool, "DELETE FROM facts")
    await execute(pool, "DELETE FROM drawers")
    await execute(pool, "DELETE FROM identity")
    return pool


async def test_mine_file(clean_pool):
    with tempfile.NamedTemporaryFile(mode="w", suffix=".md", delete=False) as f:
        f.write("# Test Document\n\nThis is a test file for mining.")
        tmp_path = f.name

    try:
        result = await hivemem_mine_file(
            clean_pool, tmp_path, wing="docs", room="test"
        )
        assert result["drawers_created"] == 1
        assert result["drawer_id"] is not None
        assert result["file"] == tmp_path

        # Verify the drawer was stored
        row = await fetch_one(
            clean_pool,
            "SELECT content, source, wing FROM drawers WHERE id = %s",
            (result["drawer_id"],),
        )
        assert "Test Document" in row["content"]
        assert row["source"] == tmp_path
        assert row["wing"] == "docs"
    finally:
        os.unlink(tmp_path)


async def test_mine_file_empty(clean_pool):
    with tempfile.NamedTemporaryFile(mode="w", suffix=".md", delete=False) as f:
        f.write("")
        tmp_path = f.name

    try:
        result = await hivemem_mine_file(clean_pool, tmp_path)
        assert result["drawers_created"] == 0
        assert result["drawer_id"] is None
    finally:
        os.unlink(tmp_path)


async def test_mine_directory(clean_pool):
    with tempfile.TemporaryDirectory() as tmpdir:
        # Create test files
        with open(os.path.join(tmpdir, "readme.md"), "w") as f:
            f.write("# Readme\n\nProject documentation.")

        with open(os.path.join(tmpdir, "notes.txt"), "w") as f:
            f.write("Some notes about the project.")

        with open(os.path.join(tmpdir, "config.yaml"), "w") as f:
            f.write("key: value\nname: test")

        # Create a subdirectory with a file
        subdir = os.path.join(tmpdir, "sub")
        os.makedirs(subdir)
        with open(os.path.join(subdir, "deep.md"), "w") as f:
            f.write("# Deep File\n\nNested content.")

        # Create a file that should be skipped (wrong extension)
        with open(os.path.join(tmpdir, "skip.py"), "w") as f:
            f.write("print('skip me')")

        result = await hivemem_mine_directory(
            clean_pool, tmpdir, wing="import-test"
        )
        assert result["files_processed"] == 4  # .md, .txt, .yaml, sub/.md
        assert result["drawers_created"] == 4
        assert result["errors"] == []


async def test_mine_directory_custom_extensions(clean_pool):
    with tempfile.TemporaryDirectory() as tmpdir:
        with open(os.path.join(tmpdir, "code.py"), "w") as f:
            f.write("def hello(): pass")

        with open(os.path.join(tmpdir, "readme.md"), "w") as f:
            f.write("# Readme")

        result = await hivemem_mine_directory(
            clean_pool, tmpdir, extensions=[".py"]
        )
        assert result["files_processed"] == 1
        assert result["drawers_created"] == 1


async def test_mine_directory_empty(clean_pool):
    with tempfile.TemporaryDirectory() as tmpdir:
        result = await hivemem_mine_directory(clean_pool, tmpdir)
        assert result["files_processed"] == 0
        assert result["drawers_created"] == 0
        assert result["errors"] == []
