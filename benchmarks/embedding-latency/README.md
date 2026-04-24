# Embedding Benchmark

One stdlib-only script that tells you two things about an HiveMem embedding
service:

1. **How fast** — min / p50 / p95 / max / mean latency over serial
   `POST /embeddings` requests, plus throughput.
2. **How good** — Spearman ρ and Pearson r between cosine similarity and
   gold-annotated scores for a set of paragraph-length, mixed-language
   (EN/DE) sample pairs that mirror real HiveMem content.

The test data lives in `data.py`, is entirely synthetic, covers generic
software-engineering topics, and contains no private or
deployment-specific information.

## Why paragraph-length, EN/DE samples

Short STS-Benchmark sentences flatter small models: they fit into the
128-token window of a MiniLM-class encoder without truncation and do not
exercise the cross-language capability HiveMem relies on. Real HiveMem cells
are much closer to the samples in `data.py`:

- 150–300 words per paragraph, often several paragraphs per cell
- English and German frequently mixed within the same project
- Technical, dense, long enough to hit the model's 128-token cap

Running the benchmark against a paragraph set therefore reflects the actual
operating conditions — if the model silently truncates at token 128, the
quality score will drop with it.

## Usage

```bash
python benchmarks/embedding-latency/run.py
python benchmarks/embedding-latency/run.py --url http://embeddings.internal:80
EMBEDDING_URL=http://embeddings.internal:80 python benchmarks/embedding-latency/run.py
python benchmarks/embedding-latency/run.py --rounds 10
python benchmarks/embedding-latency/run.py --show-pairs
```

No dependencies beyond Python 3.10+ stdlib.

## Reference values

### Speed (single request, serial, CPU-only)

| Hardware / runtime                    | p50 latency |
|---------------------------------------|-------------|
| GPU (T4 / L4), any model              |     2–10 ms |
| Modern x86 CPU, ONNX INT8, batched    |    10–30 ms |
| Modern x86 CPU, ONNX FP32, MiniLM     |   50–120 ms |
| Older CPU (Ivy Bridge), ONNX FP32     |  200–400 ms |
| LXC with broken thread pinning        |  250–400 ms |

Paragraph-length inputs do not slow a 128-token model down: once the tokeniser
hits the cap, the rest is discarded, so p50 is essentially the same as for
short sentences.

### Quality (Spearman ρ on paragraph-length pairs)

Different benchmark data than the public STS-Benchmark test set, so these
numbers are not directly comparable to MTEB leaderboard scores. Published
full-test STS-B ρ for the same model families gives a rough ordering:

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

Verdict buckets the script uses for its own gold set:

- ρ ≥ 0.80 — model separates HiveMem-shaped content cleanly
- ρ ≥ 0.70 — acceptable for paragraph-length mixed-language memories
- ρ ≥ 0.50 — truncation or language coverage is hurting recall
- ρ < 0.50 — check model / pooling / tokeniser / transport

### Cross-lingual indicator

The script prints the predicted cosine for EN↔DE translation pairs on its
own. A healthy multilingual encoder scores translations at ≥ 0.80; dropping
below about 0.70 means the model cannot confidently match a German note to
its English equivalent, which would degrade cross-language search inside
HiveMem.

## Data

All sample paragraphs and gold scores are in `data.py`. Edit that file to add,
remove or reweight pairs — `run.py` picks them up automatically.
