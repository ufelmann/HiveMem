"""Embedding model loading and encoding — BGE-M3 and SentenceTransformers."""

import os
import threading

_model = None
_model_lock = threading.Lock()
_dimension = None

MODEL_NAME = os.getenv(
    "HIVEMEM_EMBEDDING_MODEL", "sentence-transformers/paraphrase-multilingual-MiniLM-L6-v2"
)


def get_model():
    """Load embedding model (singleton, thread-safe)."""
    global _model
    if _model is None:
        with _model_lock:
            if _model is None:
                if "bge-m3" in MODEL_NAME.lower():
                    from FlagEmbedding import BGEM3FlagModel

                    _model = BGEM3FlagModel(MODEL_NAME, use_fp16=True)
                else:
                    from sentence_transformers import SentenceTransformer

                    _model = SentenceTransformer(MODEL_NAME)
    return _model


def get_dimension() -> int:
    """Get the embedding dimension (singleton, thread-safe)."""
    global _dimension
    if _dimension is None:
        model = get_model()
        if hasattr(model, "get_sentence_embedding_dimension"):
            _dimension = model.get_sentence_embedding_dimension()
        elif "bge-m3" in MODEL_NAME.lower():
            _dimension = 1024
        else:
            # Fallback: run a test encoding
            test_vec = model.encode("test")
            _dimension = len(test_vec)
    return _dimension


def encode(text: str, return_sparse: bool = False) -> list[float] | dict:
    """Encode text to a dense vector (and optionally sparse)."""
    model = get_model()

    if "bge-m3" in MODEL_NAME.lower():
        result = model.encode(
            [text],
            return_dense=True,
            return_sparse=return_sparse,
        )
        dense = result["dense_vecs"][0].tolist()
        if return_sparse:
            sparse = {
                str(k): float(v) for k, v in result["lexical_weights"][0].items()
            }
            return {"dense": dense, "sparse": sparse}
        return dense
    else:
        # SentenceTransformers path
        dense = model.encode(text).tolist()
        if return_sparse:
            # SentenceTransformers (mostly) don't support sparse natively
            return {"dense": dense, "sparse": {}}
        return dense


def encode_query(text: str) -> list[float]:
    """Encode a search query to a dense vector."""
    vec = encode(text)
    if isinstance(vec, dict):
        return vec["dense"]
    return vec
