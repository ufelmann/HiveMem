"""Ollama-backed embedding runtime.

Talks to a local Ollama server running a Qwen3 embedding model over HTTP,
using only the standard library (no new dependency). The model is
Matryoshka-trained (MRL), so its native vector can be legitimately sliced to
a shorter prefix -- pgvector's HNSW index caps at 2000 dimensions and the
native Qwen3-Embedding-8B output is 4096. Slicing breaks unit length, so the
sliced vector is re-normalised before it is returned; skipping that step
would make pgvector's cosine distance silently wrong.

Truncation is enforced by Ollama itself (`truncate` + `options.num_ctx`),
not by this module -- there is no Qwen3 tokenizer here and none is added.
"""

import json
import math
import os
import urllib.error
import urllib.request

OLLAMA_URL = os.environ.get("OLLAMA_URL", "http://hivemem-ollama:11434").rstrip("/")
OLLAMA_MODEL = os.environ.get("OLLAMA_MODEL", "qwen3-embedding:8b-q8_0")
DIMS = int(os.environ.get("EMBEDDING_DIMS", "1024"))
MAX_TOKENS = int(os.environ.get("EMBEDDING_MAX_TOKENS", "2560"))
KEEP_ALIVE = os.environ.get("EMBEDDING_KEEP_ALIVE", "5m")
MAX_CHARS = int(os.environ.get("EMBEDDING_MAX_CHARS", "8000"))

QUERY_PREFIX = ("Instruct: Given a search query, retrieve relevant passages "
                "that answer the query\nQuery: ")

INFO = {"model": "", "dimension": 0, "max_chars": 0}

# Last request body sent to Ollama; exposed for tests. Set even if the
# subsequent urlopen() call fails, so tests can assert on request shape
# without needing a successful transport.
last_request = None


def _post(payload, path="/api/embed", timeout=120):
    global last_request
    last_request = payload
    req = urllib.request.Request(
        OLLAMA_URL + path,
        data=json.dumps(payload).encode(),
        headers={"Content-Type": "application/json"},
    )
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return json.loads(r.read())


def _get(path, timeout=10):
    # No `data=` -- urllib.request.Request defaults to GET when the body is
    # omitted. Ollama's /api/tags rejects POST with 405 Method Not Allowed,
    # so this must stay a real GET, not _post() with an empty payload.
    req = urllib.request.Request(OLLAMA_URL + path)
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return json.loads(r.read())


def _raw_embed(text):
    body = _post({
        "model": OLLAMA_MODEL,
        "input": text,
        "keep_alive": KEEP_ALIVE,
        "truncate": True,                       # Ollama enforces the token cap
        "options": {"num_ctx": MAX_TOKENS},
    })
    return body["embeddings"][0]


def embed(text, mode="document"):
    if mode == "query":
        text = QUERY_PREFIX + text
    vec = _raw_embed(text)[:DIMS]
    norm = math.sqrt(sum(x * x for x in vec)) or 1e-9
    return [x / norm for x in vec]              # slicing breaks unit length; restore it


def bootstrap():
    global INFO
    probe = _raw_embed("test")
    if len(probe) < DIMS:
        raise RuntimeError(
            f"{OLLAMA_MODEL} returns {len(probe)} dimensions, fewer than EMBEDDING_DIMS={DIMS}")
    INFO = {
        # Identity encodes model, slice width, token cap and embed strategy, because
        # EmbeddingMigrationService re-encodes only when the model name changes.
        "model": f"{OLLAMA_MODEL}/mrl{DIMS}/t{MAX_TOKENS}/contentfirst",
        "dimension": DIMS,
        "max_chars": MAX_CHARS,
        "backend": "ollama",
        "native_dimension": len(probe),
    }
    return INFO


def info():
    return INFO


def health():
    _get("/api/tags")                            # liveness only -- an embed would
    return {"status": "ok", "model": INFO["model"]}   # refresh keep_alive forever
