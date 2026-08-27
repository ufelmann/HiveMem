"""Compare a running embedding service against reference vectors.

The ONNX path can return vectors of the correct shape and the wrong content --
last-token pooling reading the wrong position is the specific worry. Quantised
int8 against fp32 normally lands at cosine >= 0.99, while a wrong pooling
position collapses well below 0.9, so the two outcomes do not overlap.

Reference vectors come from a separate fp32 sentence-transformers run:

    python3 -c "
    import json, sys
    from sentence_transformers import SentenceTransformer
    texts = json.load(open('texts.json'))
    m = SentenceTransformer('Qwen/Qwen3-Embedding-0.6B')
    json.dump(m.encode(texts, normalize_embeddings=True).tolist(), open('ref.json','w'))
    "

Usage:
    python3 compare_reference.py --texts texts.json --reference ref.json \\
        --url http://127.0.0.1:8099/embeddings [--threshold 0.98]
"""

import argparse
import json
import math
import sys
import time
import urllib.request


def embed(url, text, timeout=300):
    req = urllib.request.Request(
        url,
        data=json.dumps({"text": text}).encode(),
        headers={"Content-Type": "application/json"},
    )
    started = time.monotonic()
    with urllib.request.urlopen(req, timeout=timeout) as response:
        body = json.loads(response.read())
    return body["vector"], (time.monotonic() - started) * 1000


def cosine(a, b):
    dot = sum(x * y for x, y in zip(a, b))
    na = math.sqrt(sum(x * x for x in a)) or 1e-9
    nb = math.sqrt(sum(x * x for x in b)) or 1e-9
    return dot / (na * nb)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--texts", required=True)
    parser.add_argument("--reference", required=True)
    parser.add_argument("--url", required=True)
    parser.add_argument("--threshold", type=float, default=0.98)
    args = parser.parse_args()

    texts = json.load(open(args.texts))
    reference = json.load(open(args.reference))
    if len(texts) != len(reference):
        sys.exit(f"texts={len(texts)} but reference={len(reference)}")

    worst = 1.0
    latencies = []
    for i, (text, ref) in enumerate(zip(texts, reference)):
        vector, ms = embed(args.url, text)
        similarity = cosine(vector, ref)
        latencies.append(ms)
        worst = min(worst, similarity)
        print(f"[{i:3d}] chars={len(text):7d} cos={similarity:.5f} {ms:8.1f} ms")

    latencies.sort()
    print(f"\nworst cosine : {worst:.5f} (threshold {args.threshold})")
    print(f"median latency: {latencies[len(latencies) // 2]:.1f} ms")
    print(f"max latency   : {latencies[-1]:.1f} ms")
    if worst < args.threshold:
        sys.exit("FAIL: at least one vector disagrees with the reference")
    print("PASS")


if __name__ == "__main__":
    main()
