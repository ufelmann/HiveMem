import json
import numpy as np
import onnxruntime as ort
from tokenizers import Tokenizer
from http.server import HTTPServer, BaseHTTPRequestHandler

MODEL_NAME = "paraphrase-multilingual-MiniLM-L12-v2"

tokenizer = Tokenizer.from_file("/app/model/tokenizer.json")
tokenizer.enable_padding(length=128)
tokenizer.enable_truncation(max_length=128)
session = ort.InferenceSession("/app/model/model_quantized.onnx")

def mean_pooling(token_embeddings, attention_mask):
    mask_expanded = np.expand_dims(attention_mask, axis=-1)
    summed = np.sum(token_embeddings * mask_expanded, axis=1)
    counts = np.clip(np.sum(mask_expanded, axis=1), a_min=1e-9, a_max=None)
    return summed / counts

def embed(text):
    encoded = tokenizer.encode(text)
    input_ids = np.array([encoded.ids], dtype=np.int64)
    attention_mask = np.array([encoded.attention_mask], dtype=np.int64)
    token_type_ids = np.zeros_like(input_ids)
    outputs = session.run(None, {"input_ids": input_ids, "attention_mask": attention_mask, "token_type_ids": token_type_ids})
    embedding = mean_pooling(outputs[0], attention_mask.astype(np.float32))
    norm = np.linalg.norm(embedding, axis=1, keepdims=True)
    return (embedding / np.clip(norm, a_min=1e-9, a_max=None))[0].tolist()

# Determine dimension from a test embedding at startup
MODEL_DIMENSION = len(embed("test"))

class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    def do_POST(self):
        if self.path == "/embeddings":
            length = int(self.headers.get("Content-Length", 0))
            raw = self.rfile.read(length)
            body = json.loads(raw)
            vector = embed(body["text"])
            self._respond(200, {"vector": vector, "model": MODEL_NAME, "dimension": MODEL_DIMENSION})
        else:
            self._respond(404, {"error": "not found"})

    def do_GET(self):
        if self.path == "/info":
            self._respond(200, {"model": MODEL_NAME, "dimension": MODEL_DIMENSION})
        elif self.path == "/health":
            self._respond(200, {"status": "ok", "model": MODEL_NAME, "dimensions": MODEL_DIMENSION})
        else:
            self._respond(404, {"error": "not found"})

    def _respond(self, code, data):
        body = json.dumps(data).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, format, *args):
        pass

if __name__ == "__main__":
    print(f"Embedding service ready on port 80 (model={MODEL_NAME}, dimension={MODEL_DIMENSION})", flush=True)
    HTTPServer(("0.0.0.0", 80), Handler).serve_forever()
