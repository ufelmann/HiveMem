import json
import os

import numpy as np
import onnxruntime as ort
from tokenizers import Tokenizer

# --- Configuration ---------------------------------------------------------
# Resolution priority for the model directory:
#   1. MODEL_PATH -> use as-is (manually placed files, no HF contact)
#   2. MODEL_REPO -> snapshot_download into MODEL_CACHE/<slug>
MODEL_PATH = os.environ.get("MODEL_PATH", "").strip() or None
MODEL_REPO = os.environ.get(
    "MODEL_REPO",
    "sentence-transformers/paraphrase-multilingual-MiniLM-L12-v2",
)
MODEL_NAME = os.environ.get("MODEL_NAME", "").strip() or None

# Optional explicit file names within the model directory
ONNX_FILE = os.environ.get("ONNX_FILE", "").strip() or None
TOKENIZER_FILE = os.environ.get("TOKENIZER_FILE", "").strip() or None

QUERY_PREFIX = os.environ.get("QUERY_PREFIX", "")
DOCUMENT_PREFIX = os.environ.get("DOCUMENT_PREFIX", "")
POOLING = os.environ.get("POOLING", "mean").lower()
MAX_LENGTH = int(os.environ.get("MAX_LENGTH", "128"))
# Character cap the Java client applies before sending text. Same variable and
# default as backend_ollama.py so the two backends are configured alike; it is
# part of the identity because truncating at a different width changes vectors.
MAX_CHARS = int(os.environ.get("EMBEDDING_MAX_CHARS", "8000"))
CACHE_DIR = os.environ.get("MODEL_CACHE", "/app/models")
SKIP_BOOTSTRAP = os.environ.get("EMBEDDING_SKIP_BOOTSTRAP") == "1"

# File auto-detection order inside the model directory
ONNX_CANDIDATES = [
    "model_quantized.onnx",
    "model.onnx",
    "onnx/model_quantized.onnx",
    "onnx/model.onnx",
    "onnx/model_fp16.onnx",
]
TOKENIZER_CANDIDATES = ["tokenizer.json", "onnx/tokenizer.json"]

# Patterns pulled from HF when auto-downloading. Many repos ship dozens of ONNX
# variants; fetch only the minimum set needed for inference by default.
_DEFAULT_HF_PATTERNS = [
    "model_quantized.onnx",
    "model.onnx",
    "onnx/model_quantized.onnx",
    "onnx/model.onnx",
    "model_quantized.onnx_data",
    "model.onnx_data",
    "onnx/model_quantized.onnx_data",
    "onnx/model.onnx_data",
    "tokenizer.json",
    "tokenizer_config.json",
    "special_tokens_map.json",
    "config.json",
    "sentencepiece.bpe.model",
    "vocab.txt",
]
_patterns_env = os.environ.get("HF_DOWNLOAD_PATTERNS", "").strip()
HF_ALLOW_PATTERNS = (
    [p.strip() for p in _patterns_env.split(",") if p.strip()]
    if _patterns_env
    else _DEFAULT_HF_PATTERNS
)

tokenizer = None
session = None
INPUT_NAMES = set()
MODEL_DIMENSION = 0
INTRA_OP_THREADS = 0
EOS_ID = None
APPEND_EOS = False
INFO = {"model": MODEL_NAME or "", "dimension": 0}


def _cgroup_cpu_quota(path="/sys/fs/cgroup/cpu.max"):
    """Cores available to this cgroup, or None if unlimited/unreadable.

    cpu.max holds "<quota> <period>" in microseconds, or "max <period>" when
    uncapped. Rounds up: a 6.5-core quota should use 7 threads, not 6.
    """
    try:
        with open(path) as handle:
            parts = handle.read().split()
    except OSError:
        return None
    if len(parts) != 2 or parts[0] == "max":
        return None
    try:
        quota, period = int(parts[0]), int(parts[1])
    except ValueError:
        return None
    if quota <= 0 or period <= 0:
        return None
    return max(1, -(-quota // period))


def resolve_intra_op_threads():
    """Threads for ONNX Runtime's intra-op pool.

    Without this ORT sizes the pool from the *host* CPU count, which
    oversubscribes inside a cgroup-limited container: 26 threads on 6 cores
    measured 3538 ms per average cell against 1484 ms when sized correctly.
    CPU utilisation looks healthy either way (597%), so this cannot be
    diagnosed from load alone -- the resolved value is reported in /info.

    OMP_NUM_THREADS is deliberately not consulted: current ORT builds use
    their own thread pool, not OpenMP, and setting it changes nothing.

    In the production sidecar container, /sys/fs/cgroup/cpu.max reads
    "200000 100000" (a 2-core quota) while os.cpu_count() reports 12 -- the
    cgroup branch below is the one that actually fires there, and the
    os.cpu_count() fallback would be wrong by 6x if it were used instead.
    """
    raw = os.environ.get("ORT_INTRA_OP_THREADS", "").strip()
    if raw:
        try:
            explicit = int(raw)
        except ValueError:
            explicit = 0
        if explicit > 0:
            return explicit
    quota = _cgroup_cpu_quota()
    if quota:
        return quota
    return max(1, os.cpu_count() or 1)


def download_from_hf(repo, dest):
    print(f"[bootstrap] snapshot_download {repo} -> {dest}", flush=True)
    os.makedirs(dest, exist_ok=True)
    from huggingface_hub import snapshot_download

    snapshot_download(
        repo_id=repo,
        local_dir=dest,
        allow_patterns=HF_ALLOW_PATTERNS,
    )
    open(os.path.join(dest, ".ready"), "w").close()
    print("[bootstrap] Download complete", flush=True)


def resolve_model_dir():
    if MODEL_PATH:
        if not os.path.isdir(MODEL_PATH):
            raise FileNotFoundError(f"MODEL_PATH does not exist: {MODEL_PATH}")
        print(f"[bootstrap] Using manual MODEL_PATH={MODEL_PATH}", flush=True)
        return MODEL_PATH, "manual"

    slug = MODEL_REPO.replace("/", "__")
    dest = os.path.join(CACHE_DIR, slug)
    if os.path.exists(os.path.join(dest, ".ready")):
        print(f"[bootstrap] Cached model at {dest}", flush=True)
    else:
        download_from_hf(MODEL_REPO, dest)
    return dest, "hf_cache"


def find_onnx(model_dir):
    if ONNX_FILE:
        path = os.path.join(model_dir, ONNX_FILE)
        if not os.path.exists(path):
            raise FileNotFoundError(f"ONNX_FILE not found: {path}")
        return path
    for candidate in ONNX_CANDIDATES:
        path = os.path.join(model_dir, candidate)
        if os.path.exists(path):
            return path
    for root, _, files in os.walk(model_dir):
        for filename in sorted(files):
            if filename.endswith(".onnx"):
                return os.path.join(root, filename)
    raise FileNotFoundError(f"No .onnx file found in {model_dir}")


def find_tokenizer(model_dir):
    if TOKENIZER_FILE:
        path = os.path.join(model_dir, TOKENIZER_FILE)
        if not os.path.exists(path):
            raise FileNotFoundError(f"TOKENIZER_FILE not found: {path}")
        return path
    for candidate in TOKENIZER_CANDIDATES:
        path = os.path.join(model_dir, candidate)
        if os.path.exists(path):
            return path
    for root, _, files in os.walk(model_dir):
        if "tokenizer.json" in files:
            return os.path.join(root, "tokenizer.json")
    raise FileNotFoundError(f"No tokenizer.json found in {model_dir}")


def resolve_eos_id(model_dir):
    """The model's EOS token id from config.json, or None if it does not say.

    Only needed for last-token pooling: Qwen3-Embedding's reference
    implementation appends EOS before embedding, and pooling the wrong final
    position yields plausible-looking but wrong vectors.
    """
    path = os.path.join(model_dir, "config.json")
    try:
        with open(path) as handle:
            config = json.load(handle)
    except (OSError, ValueError):
        return None
    value = config.get("eos_token_id")
    if isinstance(value, list):
        value = value[0] if value else None
    return value if isinstance(value, int) else None


# ORT reports input types as strings; only the ones a KV cache can plausibly
# use are mapped, everything else falls back to float32. The map holds numpy
# *attribute names*, not the dtypes themselves, and onnx_dtype_to_numpy()
# resolves them via getattr() at call time rather than at import time -- that
# way an unsupported dtype fails loudly (AttributeError) when it is actually
# requested, instead of the module silently substituting float32 for every
# dtype the moment it is imported.
_ONNX_TO_NUMPY = {
    "tensor(float)": "float32",
    "tensor(float16)": "float16",
    "tensor(double)": "float64",
    "tensor(int64)": "int64",
    "tensor(int32)": "int32",
}


def onnx_dtype_to_numpy(type_name):
    return getattr(np, _ONNX_TO_NUMPY[type_name]) if type_name in _ONNX_TO_NUMPY else np.float32


def build_feed(input_ids, attention_mask, input_specs):
    """Assemble the input feed for one sequence from the graph's declared inputs.

    Encoder graphs need input_ids/attention_mask and sometimes token_type_ids.
    Decoder graphs (Qwen3-Embedding) additionally declare position_ids and a
    past_key_values.* pair per layer; a single forward pass supplies them as
    zero-length tensors. Shapes come from the graph, so no per-model constants
    are needed -- symbolic dimensions become 1, batch becomes 1 and the
    past-sequence axis (second from the right) becomes 0.
    """
    length = input_ids.shape[1]
    feed = {"input_ids": input_ids, "attention_mask": attention_mask}
    for spec in input_specs:
        name = spec.name
        if name in ("input_ids", "attention_mask"):
            continue
        if name == "token_type_ids":
            feed[name] = np.zeros_like(input_ids)
        elif name == "position_ids":
            feed[name] = np.arange(length, dtype=np.int64)[None, :]
        elif name.startswith("past_key_values"):
            # A KV-cache tensor needs a batch axis and a past-sequence axis as
            # distinct dimensions, so rank must be at least 3 (typically 4:
            # batch, heads, past_seq_len, head_dim). Below that, shape[0] and
            # shape[-2] refer to the same element (rank 2) or don't exist at
            # all (rank < 2); silently mangling that would produce a batch-0
            # tensor instead of an error, so fail loudly instead.
            if len(spec.shape) < 3:
                raise ValueError(
                    f"Unsupported past_key_values shape for input {name!r}: "
                    f"{spec.shape!r} (need rank >= 3 for a batch axis and a "
                    "past-sequence axis)"
                )
            shape = [d if isinstance(d, int) else 1 for d in spec.shape]
            shape[0], shape[-2] = 1, 0
            feed[name] = np.zeros(shape, dtype=onnx_dtype_to_numpy(spec.type))
    return feed


def mean_pooling(token_embeddings, attention_mask):
    mask_expanded = np.expand_dims(attention_mask, axis=-1)
    summed = np.sum(token_embeddings * mask_expanded, axis=1)
    counts = np.clip(np.sum(mask_expanded, axis=1), a_min=1e-9, a_max=None)
    return summed / counts


def cls_pooling(token_embeddings):
    return token_embeddings[:, 0, :]


def last_token_pooling(token_embeddings, attention_mask):
    """Embedding of the last non-masked token.

    Qwen3-Embedding pools over its final (EOS) token rather than the mean or a
    CLS position. Written index-based rather than as [:, -1, :] so it stays
    correct if padding is ever re-enabled.
    """
    lengths = np.sum(attention_mask, axis=1).astype(np.int64)
    idx = np.clip(lengths - 1, 0, token_embeddings.shape[1] - 1)
    return token_embeddings[np.arange(token_embeddings.shape[0]), idx, :]


def embed(text, mode="document"):
    if tokenizer is None or session is None:
        raise RuntimeError("Embedding runtime not initialized")

    if mode == "query" and QUERY_PREFIX:
        text = QUERY_PREFIX + text
    elif mode == "document" and DOCUMENT_PREFIX:
        text = DOCUMENT_PREFIX + text

    encoded = tokenizer.encode(text)
    ids = encoded.ids
    mask = encoded.attention_mask
    if APPEND_EOS:
        # Truncation already bounded ids at MAX_LENGTH; make room for EOS so the
        # appended token cannot push the sequence past the model's limit.
        ids = list(ids[: MAX_LENGTH - 1]) + [EOS_ID]
        mask = list(mask[: MAX_LENGTH - 1]) + [1]
    input_ids = np.array([ids], dtype=np.int64)
    attention_mask = np.array([mask], dtype=np.int64)
    inputs = build_feed(input_ids, attention_mask, session.get_inputs())

    outputs = session.run(None, inputs)
    if POOLING == "cls":
        embedding = cls_pooling(outputs[0])
    elif POOLING == "last_token":
        embedding = last_token_pooling(outputs[0], attention_mask)
    else:
        embedding = mean_pooling(outputs[0], attention_mask.astype(np.float32))

    norm = np.linalg.norm(embedding, axis=1, keepdims=True)
    return (embedding / np.clip(norm, a_min=1e-9, a_max=None))[0].tolist()


def build_info(model_name, dimension, source, model_dir, onnx_path, tokenizer_path):
    info = {
        # Identity encodes everything that changes the vectors, because
        # EmbeddingMigrationService re-encodes on a model-name or dimension change only.
        # Pooling mode is part of that: mean vs. last_token on an unchanged model
        # name and dimension would otherwise look identical while every stored
        # vector is stale.
        "model": f"{model_name}/mrl0/t{MAX_LENGTH}/c{MAX_CHARS}/{POOLING}/contentfirst",
        "dimension": dimension,
        "max_chars": MAX_CHARS,
        "source": source,
        "model_path": model_dir,
        "onnx_file": os.path.relpath(onnx_path, model_dir),
        "tokenizer_file": os.path.relpath(tokenizer_path, model_dir),
        "pooling": POOLING,
        "append_eos": APPEND_EOS,
        "max_length": MAX_LENGTH,
        "intra_op_threads": INTRA_OP_THREADS,
        "query_prefix": QUERY_PREFIX,
        "document_prefix": DOCUMENT_PREFIX,
        "inputs": sorted(INPUT_NAMES),
    }
    if source == "hf_cache":
        info["repo"] = MODEL_REPO
    return info


def bootstrap():
    global tokenizer, session, INPUT_NAMES, MODEL_NAME, MODEL_DIMENSION, INFO, INTRA_OP_THREADS
    global EOS_ID, APPEND_EOS

    model_dir, source = resolve_model_dir()
    onnx_path = find_onnx(model_dir)
    tokenizer_path = find_tokenizer(model_dir)

    if MODEL_NAME is None:
        MODEL_NAME = (
            os.path.basename(MODEL_PATH.rstrip("/"))
            if MODEL_PATH
            else MODEL_REPO.split("/")[-1]
        )

    tokenizer = Tokenizer.from_file(tokenizer_path)
    # No padding: inputs are embedded one at a time, so padding every request to
    # MAX_LENGTH would make short texts pay full-length inference cost.
    # Truncation still bounds the sequence length.
    tokenizer.no_padding()
    tokenizer.enable_truncation(max_length=MAX_LENGTH)

    if POOLING == "last_token":
        EOS_ID = resolve_eos_id(model_dir)
        if EOS_ID is None:
            raise RuntimeError(
                "POOLING=last_token needs an eos_token_id in config.json; "
                f"none found in {model_dir}")
        APPEND_EOS = tokenizer.encode("test").ids[-1] != EOS_ID
        print(f"[bootstrap] last_token pooling, eos_id={EOS_ID}, "
              f"append_eos={APPEND_EOS}", flush=True)

    INTRA_OP_THREADS = resolve_intra_op_threads()
    options = ort.SessionOptions()
    options.intra_op_num_threads = INTRA_OP_THREADS
    options.inter_op_num_threads = 1
    session = ort.InferenceSession(onnx_path, options)
    INPUT_NAMES = {inp.name for inp in session.get_inputs()}
    MODEL_DIMENSION = len(embed("test"))
    INFO = build_info(MODEL_NAME, MODEL_DIMENSION, source, model_dir, onnx_path, tokenizer_path)
    return INFO


def info():
    return INFO


def health():
    return {"status": "ok", "model": MODEL_NAME, "dimensions": MODEL_DIMENSION}


if not SKIP_BOOTSTRAP:
    bootstrap()
