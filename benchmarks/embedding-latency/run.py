"""Combined embedding benchmark: how fast, and how good, on HiveMem-shaped data.

Runs a latency sampler and a gold-scored similarity check against an HiveMem
embedding service using paragraph-length, mixed-language (EN/DE) samples that
mirror real HiveMem content. Emits a single consolidated report with
bucketed verdicts for speed and quality.

Usage:
    python run.py                                      # http://localhost:8080
    python run.py --url http://embeddings.local:80
    EMBEDDING_URL=http://... python run.py
    python run.py --rounds 5 --show-pairs
"""

from __future__ import annotations

import argparse
import json
import math
import os
import time
import urllib.request

from data import LATENCY_KEYS, PAIRS, PARAGRAPHS


def embed(url: str, text: str) -> tuple[list[float], float]:
    body = json.dumps({"text": text, "mode": "document"}).encode()
    req = urllib.request.Request(
        url, data=body, headers={"Content-Type": "application/json"}
    )
    t0 = time.perf_counter()
    with urllib.request.urlopen(req) as r:
        data = json.loads(r.read())
    return data["vector"], (time.perf_counter() - t0) * 1000


def cosine(a: list[float], b: list[float]) -> float:
    dot = sum(x * y for x, y in zip(a, b))
    na = math.sqrt(sum(x * x for x in a))
    nb = math.sqrt(sum(x * x for x in b))
    return dot / (na * nb) if na and nb else 0.0


def rankdata(values: list[float]) -> list[float]:
    indexed = sorted(enumerate(values), key=lambda kv: kv[1])
    ranks = [0.0] * len(values)
    i = 0
    while i < len(indexed):
        j = i
        while j + 1 < len(indexed) and indexed[j + 1][1] == indexed[i][1]:
            j += 1
        avg = (i + j) / 2 + 1
        for k in range(i, j + 1):
            ranks[indexed[k][0]] = avg
        i = j + 1
    return ranks


def spearman(x: list[float], y: list[float]) -> float:
    rx, ry = rankdata(x), rankdata(y)
    n = len(x)
    mx, my = sum(rx) / n, sum(ry) / n
    num = sum((rx[i] - mx) * (ry[i] - my) for i in range(n))
    dx = math.sqrt(sum((r - mx) ** 2 for r in rx))
    dy = math.sqrt(sum((r - my) ** 2 for r in ry))
    return num / (dx * dy) if dx and dy else 0.0


def pearson(x: list[float], y: list[float]) -> float:
    n = len(x)
    mx, my = sum(x) / n, sum(y) / n
    num = sum((x[i] - mx) * (y[i] - my) for i in range(n))
    dx = math.sqrt(sum((v - mx) ** 2 for v in x))
    dy = math.sqrt(sum((v - my) ** 2 for v in y))
    return num / (dx * dy) if dx and dy else 0.0


def percentile(sorted_values: list[float], p: float) -> float:
    idx = min(len(sorted_values) - 1, int(len(sorted_values) * p))
    return sorted_values[idx]


def speed_verdict(p50_ms: float) -> str:
    if p50_ms < 50:
        return "excellent (GPU-class)"
    if p50_ms < 150:
        return "good (modern CPU or batched)"
    if p50_ms < 400:
        return "ok (CPU-only on small hosts)"
    return "slow — investigate runtime/threads"


def quality_verdict(rho: float) -> str:
    if rho >= 0.80:
        return "excellent — model separates HiveMem-shaped content cleanly"
    if rho >= 0.70:
        return "good — acceptable for paragraph-length mixed-language memories"
    if rho >= 0.50:
        return "weak — truncation or language coverage may be hurting recall"
    return "poor — check model / pooling / tokenizer / transport"


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
        help="Repeat latency paragraphs this many times (default: %(default)s).",
    )
    parser.add_argument(
        "--show-pairs",
        action="store_true",
        help="Print every pair with gold + predicted similarity.",
    )
    args = parser.parse_args()

    base = args.url.rstrip("/")
    embeddings_url = f"{base}/embeddings"
    info_url = f"{base}/info"

    print(f"=== Embedding Benchmark against {base} ===")
    try:
        with urllib.request.urlopen(info_url) as r:
            info = json.loads(r.read())
        print(
            f"Model: {info.get('model')}  dim={info.get('dimension')}  "
            f"pooling={info.get('pooling')}  max_length={info.get('max_length')}"
        )
    except Exception as exc:
        print(f"(could not fetch {info_url}: {exc})")

    # Warm-up
    embed(embeddings_url, "warmup")

    # Speed
    latencies: list[float] = []
    for _ in range(args.rounds):
        for key in LATENCY_KEYS:
            _, ms = embed(embeddings_url, PARAGRAPHS[key])
            latencies.append(ms)
    latencies.sort()
    total_ms = sum(latencies)
    p50 = percentile(latencies, 0.50)

    # Quality
    cache: dict[str, list[float]] = {}

    def embed_cached(key: str) -> list[float]:
        if key not in cache:
            cache[key] = embed(embeddings_url, PARAGRAPHS[key])[0]
        return cache[key]

    gold: list[float] = []
    predicted: list[float] = []
    for a, b, score, _ in PAIRS:
        gold.append(score)
        predicted.append(cosine(embed_cached(a), embed_cached(b)))
    rho = spearman(predicted, gold)
    r = pearson(predicted, gold)

    avg_chars = sum(len(PARAGRAPHS[k]) for k in LATENCY_KEYS) / len(LATENCY_KEYS)

    print()
    print("Speed (paragraph-length samples, serial requests, warmup excluded):")
    print(f"  samples     : {len(latencies)} (avg {int(avg_chars)} chars)")
    print(f"  min         : {min(latencies):6.2f} ms")
    print(f"  p50         : {p50:6.2f} ms")
    print(f"  p95         : {percentile(latencies, 0.95):6.2f} ms")
    print(f"  max         : {max(latencies):6.2f} ms")
    print(f"  mean        : {total_ms / len(latencies):6.2f} ms")
    print(f"  throughput  : {len(latencies) * 1000 / total_ms:.1f} req/s")
    print(f"  verdict     : {speed_verdict(p50)}")

    print()
    print("Quality (HiveMem-shaped gold pairs):")
    print(f"  pairs       : {len(PAIRS)}")
    print(f"  Spearman rho: {rho:.4f}")
    print(f"  Pearson  r  : {r:.4f}")
    print(f"  verdict     : {quality_verdict(rho)}")

    # Separate indicator: cross-lingual pairs only.
    cl_gold = [g for (a, b, g, _), _ in zip(PAIRS, predicted) if a.endswith("_en") and b.endswith("_de")]
    cl_pred = [p for (a, b, _, _), p in zip(PAIRS, predicted) if a.endswith("_en") and b.endswith("_de")]
    if cl_gold:
        print()
        print("Cross-lingual (EN<->DE translation pairs only):")
        for (a, b, g, _), p in zip(PAIRS, predicted):
            if a.endswith("_en") and b.endswith("_de"):
                print(f"  {a} <-> {b}   gold={g:.2f}  pred={p:.4f}")

    if args.show_pairs:
        print()
        print(f"  {'gold':>5}  {'pred':>6}  reasoning")
        for (a, b, g, why), p in zip(PAIRS, predicted):
            print(f"  {g:5.2f}  {p:6.4f}  {a} <-> {b}  ({why})")


if __name__ == "__main__":
    main()
