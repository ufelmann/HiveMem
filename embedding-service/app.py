import json
from http.server import HTTPServer

from app_onnx import Handler, INFO


if __name__ == "__main__":
    print("[bootstrap] /info =", json.dumps(INFO, indent=2), flush=True)
    print("Embedding service listening on port 80", flush=True)
    HTTPServer(("0.0.0.0", 80), Handler).serve_forever()
