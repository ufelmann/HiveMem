"""Tests for server.py's Handler.

server.py must read live state from the `backend` module (backend.info(),
backend.health(), backend.embed()) rather than a value captured at import
time -- a stale snapshot would keep serving the pre-bootstrap {"model": "",
"dimension": 0} forever, which the Java migration reads as a real model
change and re-encodes destructively for.

These tests load server.py with a fake `backend` module injected into
sys.modules, so no real ONNX/numpy/tokenizers dependency is needed.
"""

import http.client
import importlib.util
import json
import sys
import threading
import types
import unittest
from http.server import HTTPServer
from pathlib import Path

MODULE_PATH = Path(__file__).with_name("server.py")
MODULE_NAME = "embedding_service_server_under_test"


class FakeBackend:
    """Stand-in for backend.py: switches state only when bootstrap() runs."""

    def __init__(self, before, after=None, health_error=None):
        self._state = dict(before)
        self._after = dict(after) if after is not None else None
        self._health_error = health_error

    def bootstrap(self):
        if self._after is not None:
            self._state = dict(self._after)
        return dict(self._state)

    def info(self):
        return dict(self._state)

    def health(self):
        if self._health_error is not None:
            raise self._health_error
        return {"status": "ok", **self._state}

    def embed(self, text, mode="document"):
        return [0.1, 0.2, 0.3]


def load_server(backend):
    """Load a fresh server.py with `backend` installed as sys.modules['backend']."""
    fake_module = types.ModuleType("backend")
    fake_module.bootstrap = backend.bootstrap
    fake_module.info = backend.info
    fake_module.health = backend.health
    fake_module.embed = backend.embed
    sys.modules["backend"] = fake_module

    sys.modules.pop(MODULE_NAME, None)
    spec = importlib.util.spec_from_file_location(MODULE_NAME, MODULE_PATH)
    module = importlib.util.module_from_spec(spec)
    sys.modules[MODULE_NAME] = module
    spec.loader.exec_module(module)
    return module


class ServerHandlerTest(unittest.TestCase):
    def tearDown(self):
        sys.modules.pop(MODULE_NAME, None)
        sys.modules.pop("backend", None)

    def _start_server(self, mod):
        server = HTTPServer(("127.0.0.1", 0), mod.Handler)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        self.addCleanup(server.shutdown)
        self.addCleanup(server.server_close)
        self.addCleanup(thread.join, 2)
        return server

    def get(self, mod, path):
        status, body = self.get_with_status(mod, path)
        return body

    def get_with_status(self, mod, path):
        server = self._start_server(mod)
        conn = http.client.HTTPConnection(*server.server_address, timeout=5)
        try:
            conn.request("GET", path)
            resp = conn.getresponse()
            status = resp.status
            body = json.loads(resp.read())
        finally:
            conn.close()
        return status, body

    def post(self, mod, path, payload):
        server = self._start_server(mod)
        conn = http.client.HTTPConnection(*server.server_address, timeout=5)
        try:
            body = json.dumps(payload).encode()
            conn.request(
                "POST",
                path,
                body=body,
                headers={"Content-Type": "application/json", "Content-Length": str(len(body))},
            )
            resp = conn.getresponse()
            status = resp.status
            data = json.loads(resp.read())
        finally:
            conn.close()
        return status, data

    def test_info_reflects_post_bootstrap_state(self):
        mod = load_server(
            backend=FakeBackend(
                before={"model": "", "dimension": 0},
                after={
                    "model": "m/mrl1024/t2560/contentfirst",
                    "dimension": 1024,
                    "max_chars": 8000,
                },
            )
        )
        mod.backend.bootstrap()
        body = self.get(mod, "/info")
        self.assertEqual(body["model"], "m/mrl1024/t2560/contentfirst")
        self.assertEqual(body["max_chars"], 8000)

    def test_embeddings_echoes_live_model_and_dimension(self):
        mod = load_server(
            backend=FakeBackend(
                before={"model": "", "dimension": 0},
                after={
                    "model": "m/mrl1024/t2560/contentfirst",
                    "dimension": 1024,
                    "max_chars": 8000,
                },
            )
        )
        mod.backend.bootstrap()
        status, body = self.post(mod, "/embeddings", {"text": "hello"})
        self.assertEqual(status, 200)
        self.assertEqual(body["model"], "m/mrl1024/t2560/contentfirst")
        self.assertEqual(body["dimension"], 1024)

    def test_missing_text_returns_400(self):
        mod = load_server(backend=FakeBackend(before={"model": "m", "dimension": 4}))
        status, body = self.post(mod, "/embeddings", {})
        self.assertEqual(status, 400)
        self.assertIn("error", body)

    def test_unknown_path_returns_404(self):
        mod = load_server(backend=FakeBackend(before={"model": "m", "dimension": 4}))
        status, body = self.get_with_status(mod, "/does-not-exist")
        self.assertEqual(status, 404)
        self.assertEqual(body, {"error": "not found"})

    def test_health_reports_503_when_backend_is_down(self):
        # backend.health() (e.g. backend_ollama.py's unguarded /api/tags GET) can
        # raise when the upstream is unreachable. The handler must catch that and
        # answer 503 with a JSON body naming the cause, rather than letting the
        # exception close the connection with no response -- Docker's healthcheck
        # still goes red, but via status code, not a transport error.
        mod = load_server(
            backend=FakeBackend(
                before={"model": "m", "dimension": 4},
                health_error=ConnectionRefusedError("connection refused"),
            )
        )
        status, body = self.get_with_status(mod, "/health")
        self.assertEqual(status, 503)
        self.assertEqual(body["status"], "error")
        self.assertIn("connection refused", body["error"])


if __name__ == "__main__":
    unittest.main()
