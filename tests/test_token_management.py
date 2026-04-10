"""Integration tests for token management."""

import pytest
from hivemem.db import fetch_one
from hivemem.security import (
    create_token,
    list_tokens,
    revoke_token,
    get_token_info,
    validate_token,
)


async def test_api_tokens_table_exists(pool):
    """api_tokens table was created by schema."""
    row = await fetch_one(
        pool,
        "SELECT count(*) AS cnt FROM information_schema.tables "
        "WHERE table_name = 'api_tokens'",
    )
    assert row["cnt"] == 1


async def test_create_token(pool):
    """Create a token, get back plaintext, validate it."""
    plaintext = await create_token(pool, "test-admin", "admin")
    assert isinstance(plaintext, str)
    assert len(plaintext) > 20

    info = await get_token_info(pool, "test-admin")
    assert info["name"] == "test-admin"
    assert info["role"] == "admin"
    assert info["revoked_at"] is None


async def test_create_duplicate_name_fails(pool):
    """Duplicate token name raises ValueError."""
    await create_token(pool, "dup-test", "reader")
    with pytest.raises(ValueError, match="already exists"):
        await create_token(pool, "dup-test", "reader")


async def test_create_invalid_role_fails(pool):
    """Invalid role raises ValueError."""
    with pytest.raises(ValueError, match="Invalid role"):
        await create_token(pool, "bad-role", "superuser")


async def test_validate_token_returns_identity(pool):
    """Valid token returns name and role."""
    plaintext = await create_token(pool, "val-test", "writer")
    identity = await validate_token(pool, plaintext)
    assert identity["name"] == "val-test"
    assert identity["role"] == "writer"


async def test_validate_invalid_token_returns_none(pool):
    """Unknown token returns None."""
    identity = await validate_token(pool, "bogus-token-value")
    assert identity is None


async def test_list_tokens(pool):
    """List shows all tokens with metadata, no hashes."""
    await create_token(pool, "list-a", "admin")
    await create_token(pool, "list-b", "reader")
    tokens = await list_tokens(pool)
    names = [t["name"] for t in tokens]
    assert "list-a" in names
    assert "list-b" in names
    # No hash or plaintext in output
    for t in tokens:
        assert "token_hash" not in t
        assert "plaintext" not in t


async def test_revoke_token(pool):
    """Revoked token fails validation."""
    plaintext = await create_token(pool, "rev-test", "writer")
    await revoke_token(pool, "rev-test")

    identity = await validate_token(pool, plaintext)
    assert identity is None

    info = await get_token_info(pool, "rev-test")
    assert info["revoked_at"] is not None


async def test_revoke_nonexistent_raises(pool):
    """Revoking unknown name raises ValueError."""
    with pytest.raises(ValueError, match="not found"):
        await revoke_token(pool, "ghost")


async def test_expired_token_fails_validation(pool):
    """Token with expires_at in the past fails validation."""
    from hivemem.db import execute

    plaintext = await create_token(pool, "exp-test", "reader")
    # Manually set expires_at to the past
    await execute(
        pool,
        "UPDATE api_tokens SET expires_at = now() - interval '1 hour' WHERE name = 'exp-test'",
    )
    identity = await validate_token(pool, plaintext)
    assert identity is None


async def test_create_token_with_expiry(pool):
    """Token with future expiry validates successfully."""
    plaintext = await create_token(pool, "expiry-test", "admin", expires_in_days=90)
    identity = await validate_token(pool, plaintext)
    assert identity is not None

    info = await get_token_info(pool, "expiry-test")
    assert info["expires_at"] is not None
