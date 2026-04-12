import pytest
import os
from unittest.mock import patch, MagicMock
from hivemem.recompute_embeddings import check_and_recompute
from hivemem.embeddings import MODEL_NAME

@pytest.fixture(autouse=True)
def mock_recompute_deps():
    """Mock dependencies inside hivemem.recompute_embeddings."""
    # We must patch where they are USED, as they were imported via 'from ... import ...'
    with patch("hivemem.recompute_embeddings.get_dimension", return_value=384), \
         patch("hivemem.recompute_embeddings.encode", return_value=[0.1] * 384), \
         patch("subprocess.run"):
        yield

@pytest.mark.asyncio
async def test_recompute_embeddings_on_model_change(pool):
    """
    Test that check_and_recompute:
    1. Detects model/dimension mismatch in identity table.
    2. Updates drawers schema and recomputes embeddings.
    3. Updates identity table to current model/dimension.
    """
    current_model = MODEL_NAME
    current_dim = 384  # Expected for MiniLM
    
    # Setup initial state in 'identity' table representing an old model/dimension
    async with pool.connection() as conn:
        await conn.execute(
            "INSERT INTO identity (key, content) VALUES ('embedding_model', %s)",
            ("old-model",)
        )
        await conn.execute(
            "INSERT INTO identity (key, content) VALUES ('embedding_dimension', %s)",
            ("1024",)
        )
        
        # Insert a drawer with some content. 
        await conn.execute(
            "INSERT INTO drawers (content, wing, hall) VALUES (%s, %s, %s)",
            ("Test content for recomputation", "test-wing", "test-hall")
        )
        await conn.commit()

    # Trigger recomputation
    await check_and_recompute(pool)

    # Verify the results
    async with pool.connection() as conn:
        # Check identity table updates
        cur = await conn.execute("SELECT content FROM identity WHERE key = 'embedding_model'")
        row = await cur.fetchone()
        assert row is not None
        assert row['content'] == current_model

        cur = await conn.execute("SELECT content FROM identity WHERE key = 'embedding_dimension'")
        row = await cur.fetchone()
        assert row is not None
        assert int(row['content']) == current_dim

        # Check drawer's embedding
        cur = await conn.execute("SELECT embedding FROM drawers LIMIT 1")
        drawer = await cur.fetchone()
        embedding = drawer['embedding']
        
        assert embedding is not None
        
        # If psycopg returns the vector as a string (common for custom types like vector), 
        # we parse it as a list first.
        if isinstance(embedding, str):
            import json
            embedding = json.loads(embedding)
            
        assert len(embedding) == current_dim
