# Embedding Latency Benchmark

A tiny, dependency-free script that measures the HiveMem embedding service on
two axes:

1. **Latency** — min / p50 / p95 / max / mean, plus throughput, over a handful
   of serial `POST /embeddings` requests.
2. **Sanity of similarity** — cosine similarities over 5 known-similar sentence
   pairs (from STS-Benchmark) and a few clearly unrelated pairs, so regressions
   in the model or tokenizer stand out.

The ten sentences are public-domain STS-B samples and do not contain any
environment-specific information.

## Usage

```bash
# default: http://localhost:8080
python benchmarks/embedding-latency/bench.py

# point at any running embedding service
python benchmarks/embedding-latency/bench.py --url http://embeddings.internal:80

# or via env var
EMBEDDING_URL=http://embeddings.internal:80 python benchmarks/embedding-latency/bench.py

# more latency samples
python benchmarks/embedding-latency/bench.py --rounds 10
```

Requires only the Python standard library (no `pip install`).

## What "good" looks like

- Similar pairs cosine ≥ ~0.85 for well-trained multilingual MiniLM models.
- Unrelated pairs should be clearly lower (often < 0.3 for disjoint topics).
- Latency depends entirely on hardware and runtime (CPU-only ONNX on small
  hosts typically sits in the 150–400 ms/request range, modern CPUs or GPUs do
  much better).
