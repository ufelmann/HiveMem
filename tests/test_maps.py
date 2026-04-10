"""E2E tests for Maps of Content."""

from hivemem.tools.write import hivemem_update_map
from hivemem.tools.read import hivemem_get_map
from hivemem.db import execute, fetch_all


async def test_create_map(pool):
    """Create a map for a wing."""
    result = await hivemem_update_map(
        pool, wing="engineering",
        title="Engineering: Active Decisions",
        narrative="Current focus is on auth migration and HiveMem development.",
        room_order=["auth", "hivemem", "infra"],
    )
    assert result["wing"] == "engineering"
    assert result["title"] == "Engineering: Active Decisions"
    await execute(pool, "DELETE FROM maps")


async def test_get_map(pool):
    """Get map for a specific wing."""
    await hivemem_update_map(pool, wing="eng", title="Eng Map", narrative="Engineering overview")
    maps = await hivemem_get_map(pool, wing="eng")
    assert len(maps) == 1
    assert maps[0]["title"] == "Eng Map"
    assert maps[0]["narrative"] == "Engineering overview"
    await execute(pool, "DELETE FROM maps")


async def test_get_all_maps(pool):
    """Get maps for all wings."""
    await hivemem_update_map(pool, wing="eng", title="Eng", narrative="Engineering")
    await hivemem_update_map(pool, wing="personal", title="Personal", narrative="Personal stuff")
    maps = await hivemem_get_map(pool)
    assert len(maps) == 2
    wings = [m["wing"] for m in maps]
    assert "eng" in wings
    assert "personal" in wings
    await execute(pool, "DELETE FROM maps")


async def test_update_map_append_only(pool):
    """Updating a map closes the old one and creates a new one."""
    await hivemem_update_map(pool, wing="eng", title="V1", narrative="First version")
    await hivemem_update_map(pool, wing="eng", title="V2", narrative="Updated version")

    # Only V2 in active_maps
    maps = await hivemem_get_map(pool, wing="eng")
    assert len(maps) == 1
    assert maps[0]["title"] == "V2"

    # V1 still exists in DB with valid_until set
    all_maps = await fetch_all(pool, "SELECT title, valid_until FROM maps WHERE wing = 'eng' ORDER BY valid_from")
    assert len(all_maps) == 2
    assert all_maps[0]["valid_until"] is not None  # V1 closed
    assert all_maps[1]["valid_until"] is None  # V2 active
    await execute(pool, "DELETE FROM maps")


async def test_map_with_room_order(pool):
    """Map stores room order."""
    await hivemem_update_map(
        pool, wing="eng", title="Ordered",
        narrative="With rooms", room_order=["auth", "search", "infra"],
    )
    maps = await hivemem_get_map(pool, wing="eng")
    assert maps[0]["room_order"] == ["auth", "search", "infra"]
    await execute(pool, "DELETE FROM maps")
