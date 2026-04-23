"""Embedding service latency + similarity benchmark.

Fires a small, reproducible set of STS-Benchmark style sentence pairs at an
HiveMem embedding service and reports:

  * per-request latency (min, p50, p95, max, mean, throughput)
  * cosine similarity for 5 known-similar pairs (expected near 1.0)
  * cosine similarity for a few unrelated pairs (expected much lower)

The sentences are the canonical STS-B examples used to sanity-check sentence
embedding models. They are short, English, and public domain test data.

Usage:
    python bench.py                                   # defaults to http://localhost:8080
    python bench.py --url http://embeddings.local:80
    EMBEDDING_URL=http://... python bench.py
"""

from __future__ import annotations

import argparse
import json
import math
import os
import time
import urllib.request


# STS-Benchmark style pairs (SemEval). Five near-paraphrase pairs, then
# a few unrelated pairs via cross-indexing.
SENTENCES = [
    "A plane is taking off.",
    "An air plane is taking off.",
    "A man is playing a large flute.",
    "A man is playing a flute.",
    "A man is spreading shredded cheese on a pizza.",
    "A man is spreading shredded cheese on an uncooked pizza.",
    "Three men are playing chess.",
    "Two men are playing chess.",
    "A man is playing the cello.",
    "A man seated is playing the cello.",
]

SIMILAR_PAIRS = [(0, 1), (2, 3), (4, 5), (6, 7), (8, 9)]
UNRELATED_PAIRS = [(0, 6), (2, 8), (4, 7)]


def embed(url: str, text: str) -> tuple[list[float], float]:
    body = json.dumps({"text": text, "mode": "document"}).encode()
    req = urllib.request.Request(
        url, data=body, headers={"Content-Type": "application/json"}
    )
    t0 = time.perf_counter()
    with urllib.request.urlopen(req) as r:
        data = json.loads(r.read())
    elapsed_ms = (time.perf_counter() - t0) * 1000
    return data["vector"], elapsed_ms


def cosine(a: list[float], b: list[float]) -> float:
    dot = sum(x * y for x, y in zip(a, b))
    na = math.sqrt(sum(x * x for x in a))
    nb = math.sqrt(sum(x * x for x in b))
    return dot / (na * nb)


def percentile(sorted_values: list[float], p: float) -> float:
    idx = min(len(sorted_values) - 1, int(len(sorted_values) * p))
    return sorted_values[idx]


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument(
        "--url",
        default=os.environ.get("EMBEDDING_URL", "http://localhost:8080"),
        help="Base URL of the embedding service (default: %(default)s or $EMBEDDING_URL).",
    )
    parser.add_argument(
        "--rounds",
        type=int,
        default=3,
        help="Repeat the sentence list this many times for latency sampling (default: %(default)s).",
    )
    args = parser.parse_args()

    base = args.url.rstrip("/")
    embeddings_url = f"{base}/embeddings"
    info_url = f"{base}/info"

    # Model info
    try:
        with urllib.request.urlopen(info_url) as r:
            info = json.loads(r.read())
        print(
            f"Model: {info.get('model')}  dim={info.get('dimension')}  pooling={info.get('pooling')}"
        )
    except Exception as exc:  # /info is optional
        print(f"(could not fetch {info_url}: {exc})")

    # Warm-up
    embed(embeddings_url, "warmup")

    # Capture vectors once for similarity scoring
    vectors: list[list[float]] = []
    for sentence in SENTENCES:
        v, _ = embed(embeddings_url, sentence)
        vectors.append(v)

    # Latency sampling
    latencies: list[float] = []
    for _ in range(args.rounds):
        for sentence in SENTENCES:
            _, ms = embed(embeddings_url, sentence)
            latencies.append(ms)

    latencies.sort()
    total_ms = sum(latencies)
    print()
    print(f"Latency over {len(latencies)} requests (warmup excluded):")
    print(f"  min  : {min(latencies):6.2f} ms")
    print(f"  p50  : {percentile(latencies, 0.50):6.2f} ms")
    print(f"  p95  : {percentile(latencies, 0.95):6.2f} ms")
    print(f"  max  : {max(latencies):6.2f} ms")
    print(f"  mean : {total_ms / len(latencies):6.2f} ms")
    print(f"  throughput: {len(latencies) * 1000 / total_ms:.1f} req/s")

    print()
    print("Paired similarities (expected near 1.0):")
    for i, j in SIMILAR_PAIRS:
        sim = cosine(vectors[i], vectors[j])
        print(f"  [{i}]<->[{j}]  {sim:.4f}   {SENTENCES[i]!r:55s} vs {SENTENCES[j]!r}")

    print()
    print("Unrelated pairs (expected noticeably lower):")
    for i, j in UNRELATED_PAIRS:
        sim = cosine(vectors[i], vectors[j])
        print(f"  [{i}]<->[{j}]  {sim:.4f}   {SENTENCES[i]!r:55s} vs {SENTENCES[j]!r}")


if __name__ == "__main__":
    main()
