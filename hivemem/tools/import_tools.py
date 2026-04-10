"""Import tools for ingesting files into HiveMem."""

from __future__ import annotations

import glob
import os

from psycopg_pool import AsyncConnectionPool

from hivemem.tools.write import hivemem_add_drawer


async def hivemem_mine_file(
    pool: AsyncConnectionPool,
    file_path: str,
    wing: str | None = None,
    room: str | None = None,
    hall: str | None = None,
) -> dict:
    """Read a file and store its content as a drawer."""
    with open(file_path, "r", encoding="utf-8") as f:
        content = f.read()

    if not content.strip():
        return {"drawers_created": 0, "drawer_id": None, "file": file_path}

    result = await hivemem_add_drawer(
        pool,
        content=content,
        wing=wing,
        room=room,
        hall=hall,
        source=file_path,
    )
    return {
        "drawers_created": 1,
        "drawer_id": result["id"],
        "file": file_path,
    }


async def hivemem_mine_directory(
    pool: AsyncConnectionPool,
    dir_path: str,
    wing: str | None = None,
    hall: str | None = None,
    extensions: list[str] | None = None,
) -> dict:
    """Glob for files and call mine_file for each."""
    if extensions is None:
        extensions = [".md", ".txt", ".yaml"]

    files = []
    for ext in extensions:
        pattern = os.path.join(dir_path, f"**/*{ext}")
        files.extend(glob.glob(pattern, recursive=True))

    files.sort()
    drawers_created = 0
    errors = []

    for file_path in files:
        try:
            result = await hivemem_mine_file(
                pool, file_path, wing=wing, hall=hall
            )
            drawers_created += result["drawers_created"]
        except Exception as e:
            errors.append({"file": file_path, "error": str(e)})

    return {
        "files_processed": len(files),
        "drawers_created": drawers_created,
        "errors": errors,
    }
