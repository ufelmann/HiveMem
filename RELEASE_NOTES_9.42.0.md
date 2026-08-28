# HiveMem 9.42.0 — ONNX embedding backend on CPU

## Upgrade warning: this release changes the ONNX backend's reported identity

`EmbeddingMigrationService` treats the embedding identity string as its re-encode
trigger. **Any installation running `EMBEDDING_BACKEND=onnx` will re-encode its entire
corpus once on the first start after this upgrade**, serially, at startup, with the HNSW
index dropped for the duration. A backup runs automatically first.

Installations on `EMBEDDING_BACKEND=ollama` are unaffected — that backend's identity
format is unchanged.

Before upgrading an ONNX installation, read *Migration window: raise the timeout and
disable retries together* in `documentation/architecture.md` and set both knobs for the
duration of the run.

The identity gained three components, each because it changes stored vectors and would
otherwise leave a stale index looking current:

```
{model}/mrl0/t{MAX_LENGTH}/c{MAX_CHARS}/{POOLING}/{onnx-variant}/{document-prefix}/contentfirst
```

- **pooling mode** — `mean` and `last_token` on the same model produce different vectors
- **the resolved ONNX variant** — a repo ships several quantisations; swapping between
  them changes the weights
- **`DOCUMENT_PREFIX`** — prepended to every document before encoding

## The ONNX backend can now run modern embedding models

- **`max_chars` comes from `EMBEDDING_MAX_CHARS`** instead of a hardcoded `500`, which
  discarded roughly 91 % of an average cell before the model saw it. Same variable and
  default (`8000`) as the Ollama backend.
- **Decoder architectures are supported.** The input feed is built from the graph's
  declared inputs, so models needing `position_ids` and `past_key_values.*` work; the
  shapes and dtypes are read off the graph rather than hardcoded per model.
- **`POOLING=last_token`** joins `mean` and `cls`. With it the backend reads
  `eos_token_id` from the model directory's `config.json` and refuses to start if it is
  absent, rather than silently pooling the wrong position.
- **`ONNX_FILE`** narrows the Hugging Face download to the file actually loaded plus its
  external-weights sibling. Previously the broad default pulled multi-gigabyte fp32
  weights alongside the quantised file in use. `model_int8.onnx` is now a recognised
  auto-detection candidate.

## Performance: ONNX Runtime is no longer oversubscribed in containers

Without explicit `SessionOptions`, onnxruntime sizes its intra-op thread pool from the
**host** CPU count, which oversubscribes inside a cgroup-limited container — measured 26
threads on 6 available cores, costing 3538 ms per average cell against 1484 ms when sized
correctly. CPU utilisation looks healthy in both cases, so this cannot be diagnosed from
load alone.

The pool is now sized from `ORT_INTRA_OP_THREADS`, else the cgroup quota in
`/sys/fs/cgroup/cpu.max`, else `os.cpu_count()`. The resolved value is reported in
`/info` as `intra_op_threads` so a misconfiguration is visible rather than merely slow.

## The corpus re-encode no longer crash-loops under a slow embed call

A cell slower than `HIVEMEM_EMBEDDING_TIMEOUT` was retried up to three times while the
sidecar — a `ThreadingHTTPServer` with no client-disconnect detection or cancellation —
kept computing the abandoned requests. One slow cell could stack four concurrent
inferences on the same cores, each slower than the last; when they failed the migration
rethrew, the container restarted, and because progress is persisted only on a clean pass,
the backup and full re-encode began again from the first record.

- `hivemem.embedding.max-retries` and `hivemem.embedding.retry-backoff-ms` are now bound
  to `HIVEMEM_EMBEDDING_MAX_RETRIES` and `HIVEMEM_EMBEDDING_RETRY_BACKOFF_MS`. Defaults
  are unchanged (3 and 500 ms). Set retries to `0` for a migration window.
- `EmbeddingBackfillService` now holds off until startup is complete **and** no re-encode
  is running. Its `@Scheduled` sweep previously fired before the migration runner and
  competed with it for the same cores.
- `docker-compose.yml` exposes `HIVEMEM_EMBEDDING_TIMEOUT`,
  `HIVEMEM_EMBEDDING_MAX_RETRIES` and `HIVEMEM_EMBEDDING_RETRY_BACKOFF_MS` so these are
  reachable without editing the compose file.

## New diagnostics

- **Bootstrap warns when `EMBEDDING_MAX_CHARS` exceeds what `MAX_LENGTH` tokens can
  represent.** Advertising a character budget the token cap cannot honour makes the
  client embed content that is then silently truncated — a well-formed vector that
  nothing downstream repairs.
- **`embedding-service/tools/compare_reference.py`** compares a running service against
  fp32 reference vectors and fails on a cosine threshold, a vector-length mismatch, or a
  service identity that changes mid-run. Intended as a gate before a model swap.

## Verification

The vector path was checked numerically against fp32 `sentence-transformers` reference
vectors over 17 real cells: **worst cosine 0.99969**.
