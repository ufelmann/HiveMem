# Embedding Benchmark

One stdlib-only script that tells you two things about an HiveMem embedding
service:

1. **How fast** — min / p50 / p95 / max / mean latency over serial
   `POST /embeddings` requests, plus throughput.
2. **How good** — Spearman ρ and Pearson r between cosine similarity and
   human-annotated gold scores from a 24-pair STS-Benchmark sample.

Both metrics are printed with a one-line verdict so you can eyeball the health
of a deployment at a glance.

## Usage

```bash
# defaults to http://localhost:8080
python benchmarks/embedding-latency/run.py

# point at any running embedding service
python benchmarks/embedding-latency/run.py --url http://embeddings.internal:80

# env var also works
EMBEDDING_URL=http://embeddings.internal:80 python benchmarks/embedding-latency/run.py

# more latency samples
python benchmarks/embedding-latency/run.py --rounds 10

# dump every STS pair with gold + predicted similarity
python benchmarks/embedding-latency/run.py --show-pairs
```

No dependencies beyond the Python 3.10+ standard library.

## Reference values

### Speed (single request, serial, CPU-only)

| Hardware / runtime                    | p50 latency |
|---------------------------------------|-------------|
| Modern x86 CPU, ONNX INT8, batched    |    10–30 ms |
| Modern x86 CPU, ONNX FP32, MiniLM     |    50–120 ms|
| Older CPU (Ivy Bridge), ONNX FP32     |   200–400 ms|
| LXC with broken thread pinning        |   250–400 ms|
| GPU (T4 / L4), any model              |     2–10 ms |

Throughput roughly = 1000 / p50_ms for serial use; parallel clients scale
sub-linearly until CPU saturates.

### Quality (Spearman ρ on STS-Benchmark)

These are the published full-test-set scores (1379 pairs). Our 24-pair sample
tends to over-estimate by 0.03–0.08, so read them as a rough ceiling.

| Model                                              | STS-B ρ |
|----------------------------------------------------|---------|
| `all-mpnet-base-v2`                                |  0.886  |
| `paraphrase-multilingual-MiniLM-L12-v2` (default)  |  0.863  |
| `BAAI/bge-m3` (dense)                              |  0.847  |
| `all-MiniLM-L6-v2` (English only)                  |  0.825  |
| `BAAI/bge-small-en-v1.5`                           |  0.820  |
| OpenAI `text-embedding-3-large`                    |  0.816  |
| OpenAI `text-embedding-3-small`                    |  0.726  |
| OpenAI `text-embedding-ada-002`                    |  0.642  |

Verdict buckets the script uses:

- ρ ≥ 0.80 — excellent, on par with dedicated sentence encoders
- ρ ≥ 0.70 — good, typical for small multilingual models
- ρ ≥ 0.50 — weak, consider a stronger model
- ρ < 0.50 — poor, check model / pooling / tokenizer / transport

## Data

Sentences for the latency sampler are public-domain STS-B examples.

Gold-scored pairs in `sts_pairs.py` are a curated 24-pair sample from the
STS-Benchmark dev/test splits (CC BY-SA 4.0,
<https://ixa2.si.ehu.eus/stswiki/index.php/STSbenchmark>). For a full-test
MTEB-grade quality run, use the official
[MTEB](https://github.com/embeddings-benchmark/mteb) harness instead.
