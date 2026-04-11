"""Import tools for ingesting files into HiveMem."""

from __future__ import annotations

import glob
import os
from pathlib import Path

from psycopg_pool import AsyncConnectionPool

from hivemem.tools.write import hivemem_add_drawer

# Only allow imports from these directories (resolved, no symlink escape)
ALLOWED_IMPORT_DIRS = [
    Path("/data/imports"),
    Path("/tmp"),
]


def _validate_path(raw_path: str) -> Path:
    """Resolve path and check it's inside an allowed directory."""
    resolved = Path(raw_path).resolve()
    for allowed in ALLOWED_IMPORT_DIRS:
        try:
            resolved.relative_to(allowed.resolve())
            return resolved
        except ValueError:
            continue
    raise PermissionError(
        f"Path outside allowed import directories. "
        f"Allowed: {', '.join(str(d) for d in ALLOWED_IMPORT_DIRS)}"
    )


async def hivemem_mine_file(
    pool: AsyncConnectionPool,
    file_path: str,
    wing: str | None = None,
    hall: str | None = None,
    room: str | None = None,
) -> dict:
    """Read a file and store its content as a drawer."""
    safe_path = _validate_path(file_path)
    with open(safe_path, "r", encoding="utf-8") as f:
        content = f.read()

    if not content.strip():
        return {"drawers_created": 0, "drawer_id": None, "file": file_path}

    result = await hivemem_add_drawer(
        pool,
        content=content,
        wing=wing,
        hall=hall,
        room=room,
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
    room: str | None = None,
    extensions: list[str] | None = None,
) -> dict:
    """Glob for files and call mine_file for each."""
    _validate_path(dir_path)  # check base dir is allowed
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
                pool, file_path, wing=wing, room=room
            )
            drawers_created += result["drawers_created"]
        except Exception as e:
            errors.append({"file": os.path.basename(file_path), "error": type(e).__name__})

    return {
        "files_processed": len(files),
        "drawers_created": drawers_created,
        "errors": errors,
    }
