"""Embedding model loading and encoding — BGE-M3 (dense + sparse)."""

from FlagEmbedding import BGEM3FlagModel

_model: BGEM3FlagModel | None = None
MODEL_NAME = "BAAI/bge-m3"


def get_model() -> BGEM3FlagModel:
    """Load BGE-M3 model (singleton). ~2.2GB RAM."""
    global _model
    if _model is None:
        _model = BGEM3FlagModel(MODEL_NAME, use_fp16=True)
    return _model


def encode(text: str, return_sparse: bool = False) -> list[float] | dict:
    """Encode text to a 1024-dimensional dense vector.

    If return_sparse=True, returns {"dense": [...], "sparse": {...}}
    for hybrid search support.
    """
    model = get_model()
    result = model.encode(
        [text],
        return_dense=True,
        return_sparse=return_sparse,
    )
    dense = result["dense_vecs"][0].tolist()
    if return_sparse:
        sparse = {
            str(k): float(v)
            for k, v in result["lexical_weights"][0].items()
        }
        return {"dense": dense, "sparse": sparse}
    return dense


def encode_query(text: str) -> list[float]:
    """Encode a search query to a 1024-dimensional dense vector."""
    return encode(text)
