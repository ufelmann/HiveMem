"""E2E tests for Blueprints (Maps of Content)."""

from hivemem.tools.write import hivemem_update_blueprint
from hivemem.tools.read import hivemem_get_blueprint
from hivemem.db import execute, fetch_all


async def test_create_blueprint(pool):
    """Create a blueprint for a wing."""
    result = await hivemem_update_blueprint(
        pool, wing="engineering",
        title="Engineering: Active Decisions",
        narrative="Current focus is on auth migration and HiveMem development.",
        hall_order=["auth", "hivemem", "infra"],
    )
    assert result["wing"] == "engineering"
    assert result["title"] == "Engineering: Active Decisions"
    await execute(pool, "DELETE FROM maps")


async def test_get_blueprint(pool):
    """Get blueprint for a specific wing."""
    await hivemem_update_blueprint(pool, wing="eng", title="Eng Map", narrative="Engineering overview")
    blueprints = await hivemem_get_blueprint(pool, wing="eng")
    assert len(blueprints) == 1
    assert blueprints[0]["title"] == "Eng Map"
    assert blueprints[0]["narrative"] == "Engineering overview"
    await execute(pool, "DELETE FROM maps")


async def test_get_all_blueprints(pool):
    """Get blueprints for all wings."""
    await hivemem_update_blueprint(pool, wing="eng", title="Eng", narrative="Engineering")
    await hivemem_update_blueprint(pool, wing="personal", title="Personal", narrative="Personal stuff")
    blueprints = await hivemem_get_blueprint(pool)
    assert len(blueprints) == 2
    wings = [m["wing"] for m in blueprints]
    assert "eng" in wings
    assert "personal" in wings
    await execute(pool, "DELETE FROM maps")


async def test_update_blueprint_append_only(pool):
    """Updating a blueprint closes the old one and creates a new one."""
    await hivemem_update_blueprint(pool, wing="eng", title="V1", narrative="First version")
    await hivemem_update_blueprint(pool, wing="eng", title="V2", narrative="Updated version")

    # Only V2 in active_maps
    blueprints = await hivemem_get_blueprint(pool, wing="eng")
    assert len(blueprints) == 1
    assert blueprints[0]["title"] == "V2"

    # V1 still exists in DB with valid_until set
    all_maps = await fetch_all(pool, "SELECT title, valid_until FROM maps WHERE wing = 'eng' ORDER BY valid_from")
    assert len(all_maps) == 2
    assert all_maps[0]["valid_until"] is not None  # V1 closed
    assert all_maps[1]["valid_until"] is None  # V2 active
    await execute(pool, "DELETE FROM maps")


async def test_blueprint_with_hall_order(pool):
    """Blueprint stores hall order."""
    await hivemem_update_blueprint(
        pool, wing="eng", title="Ordered",
        narrative="With halls", hall_order=["auth", "search", "infra"],
    )
    blueprints = await hivemem_get_blueprint(pool, wing="eng")
    assert blueprints[0]["hall_order"] == ["auth", "search", "infra"]
    await execute(pool, "DELETE FROM maps")
