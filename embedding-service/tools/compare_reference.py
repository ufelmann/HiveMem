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
        --url http://127.0.0.1:8099/embeddings [--threshold 0.98] \\
        [--expect-model Qwen/Qwen3-Embedding-0.6B]
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
    return body, (time.monotonic() - started) * 1000


def cosine(a, b):
    dot = sum(x * y for x, y in zip(a, b))
    na = math.sqrt(sum(x * x for x in a)) or 1e-9
    nb = math.sqrt(sum(x * x for x in b)) or 1e-9
    return dot / (na * nb)


def check_vector_length(service_vector, reference_vector):
    """Reject a length mismatch before it reaches cosine().

    zip() in cosine() silently truncates to the shorter of the two vectors,
    so a service returning a shorter MRL slice of the same model (this
    codebase has a backend that does exactly that) would score cosine ~=1.0
    over the common prefix and PASS this gate -- the sole check standing in
    front of the re-encode. Returns an error string naming both lengths, or
    None when they agree.
    """
    if len(service_vector) != len(reference_vector):
        return (
            f"vector length mismatch: service returned {len(service_vector)} "
            f"dims, reference has {len(reference_vector)} dims -- different "
            "embedding dimension, comparison invalid"
        )
    return None


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--texts", required=True)
    parser.add_argument("--reference", required=True)
    parser.add_argument("--url", required=True)
    parser.add_argument("--threshold", type=float, default=0.98)
    parser.add_argument(
        "--expect-model",
        help="fail unless the service reports exactly this model name",
    )
    args = parser.parse_args()

    texts = json.load(open(args.texts))
    reference = json.load(open(args.reference))
    if not texts:
        sys.exit("FAIL: texts file is empty -- nothing to compare")
    if len(texts) != len(reference):
        sys.exit(f"texts={len(texts)} but reference={len(reference)}")

    worst = 1.0
    latencies = []
    model = dimension = None
    for i, (text, ref) in enumerate(zip(texts, reference)):
        body, ms = embed(args.url, text)
        if i == 0:
            model, dimension = body.get("model"), body.get("dimension")
            print(f"service model : {model}")
            print(f"dimension     : {dimension}")
            if args.expect_model is not None and model != args.expect_model:
                sys.exit(
                    f"FAIL: service reports model {model!r}, "
                    f"expected {args.expect_model!r} -- wrong service?"
                )
        elif (body.get("model"), body.get("dimension")) != (model, dimension):
            sys.exit(
                f"FAIL: service identity changed mid-run at request {i}: "
                f'was {model!r}/dim={dimension}, '
                f'now {body.get("model")!r}/dim={body.get("dimension")}'
            )
        length_error = check_vector_length(body["vector"], ref)
        if length_error is not None:
            sys.exit(f"FAIL: at request {i}: {length_error}")
        similarity = cosine(body["vector"], ref)
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
