"""Selects the embedding runtime backend via EMBEDDING_BACKEND.

The onnx backend (CPU, onnxruntime) is the default so a clone without a GPU
still runs out of the box. The ollama backend targets a GPU-backed Ollama
instance. An unknown value is a hard failure -- never a silent fallback to
onnx, since that could mask a misconfigured deployment.
"""

import importlib
import os

_BACKENDS = {"onnx": "backend_onnx", "ollama": "backend_ollama"}


def select_backend(env):
    name = (env.get("EMBEDDING_BACKEND") or "onnx").strip().lower()
    if name not in _BACKENDS:
        raise ValueError(f"Unknown EMBEDDING_BACKEND {name!r}; expected one of {sorted(_BACKENDS)}")
    return name


_impl = importlib.import_module(_BACKENDS[select_backend(os.environ)])

bootstrap = _impl.bootstrap
info = _impl.info
health = _impl.health
embed = _impl.embed
