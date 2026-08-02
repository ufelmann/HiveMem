"""HTTP handler for the embedding sidecar.

Delegates all inference work to the `backend` module, which selects between
interchangeable runtimes (ONNX on CPU, Ollama on GPU, ...) via
EMBEDDING_BACKEND. The handler reads backend.info() per request rather than
caching a value at import time, so /info and /health always reflect the
post-bootstrap state.
"""

import json
from http.server import BaseHTTPRequestHandler

import backend


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    # Bound how long a keep-alive connection idles waiting for the next request; without this
    # a stalled/abandoned client connection ties up a handler thread forever.
    timeout = 30

    def do_POST(self):
        if self.path != "/embeddings":
            self._respond(404, {"error": "not found"})
            return
        try:
            length = int(self.headers.get("Content-Length", 0))
            body = json.loads(self.rfile.read(length))
        except (ValueError, UnicodeDecodeError):
            self._respond(400, {"error": "request body must be valid JSON"})
            return
        text = body.get("text") if isinstance(body, dict) else None
        if not isinstance(text, str):
            self._respond(400, {"error": "field 'text' is required and must be a string"})
            return
        mode = body.get("mode", "document")
        try:
            vector = backend.embed(text, mode=mode)
        except Exception as exc:  # keep the server alive; report the failure as HTTP 500
            self._respond(500, {"error": f"embedding failed: {exc}"})
            return
        i = backend.info()
        self._respond(200, {"vector": vector, "model": i["model"], "dimension": i["dimension"]})

    def do_GET(self):
        if self.path == "/info":
            self._respond(200, backend.info())
        elif self.path == "/health":
            try:
                self._respond(200, backend.health())
            except Exception as exc:  # keep the server alive and answer with 503
                self._respond(503, {"status": "error", "error": str(exc)})
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
