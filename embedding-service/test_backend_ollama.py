"""Tests for backend_ollama.py.

The module talks to Ollama over urllib.request.urlopen -- there is no live
Ollama server in this environment, so every test stubs that HTTP layer and
never attempts a real call.
"""

import importlib.util
import json
import math
import sys
import unittest
import urllib.error
import urllib.request
from os import environ as _ENV
from pathlib import Path
from unittest import mock

MODULE_PATH = Path(__file__).with_name("backend_ollama.py")
MODULE_NAME = "embedding_service_backend_ollama_under_test"


class _FakeResponse:
    """Stand-in for the context manager urlopen() returns."""

    def __init__(self, payload):
        self._payload = payload

    def __enter__(self):
        return self

    def __exit__(self, exc_type, exc_val, exc_tb):
        return False

    def read(self):
        return json.dumps(self._payload).encode()


class OllamaBackendTest(unittest.TestCase):
    def setUp(self):
        # Every mock.patch(...).start() registered below via load_ollama()/
        # load_ollama_failing() is unwound here, regardless of which test ran.
        self.addCleanup(mock.patch.stopall)

    def load_module(self, env):
        sys.modules.pop(MODULE_NAME, None)
        with mock.patch.dict(_ENV, env, clear=False):
            spec = importlib.util.spec_from_file_location(MODULE_NAME, MODULE_PATH)
            module = importlib.util.module_from_spec(spec)
            sys.modules[MODULE_NAME] = module
            spec.loader.exec_module(module)
        self.addCleanup(sys.modules.pop, MODULE_NAME, None)
        return module

    def load_ollama(self, dims, fake_vector, max_tokens=2560, keep_alive="5m"):
        module = self.load_module({
            "EMBEDDING_DIMS": str(dims),
            "EMBEDDING_MAX_TOKENS": str(max_tokens),
            "EMBEDDING_KEEP_ALIVE": keep_alive,
        })

        def fake_urlopen(req, timeout=120):
            if req.full_url.endswith("/api/tags"):
                return _FakeResponse({})
            return _FakeResponse({"embeddings": [fake_vector]})

        mock.patch.object(urllib.request, "urlopen", fake_urlopen).start()
        return module

    def load_ollama_failing(self, exc):
        module = self.load_module({})

        def fake_urlopen(req, timeout=120):
            raise exc

        mock.patch.object(urllib.request, "urlopen", fake_urlopen).start()
        return module

    def test_slices_and_renormalises(self):
        mod = self.load_ollama(dims=4, fake_vector=[3.0, 4.0, 100.0, 100.0])
        v = mod.embed("hello", mode="document")
        self.assertEqual(len(v), 4)
        self.assertAlmostEqual(math.sqrt(sum(x * x for x in v)), 1.0, places=6)

    def test_slice_shortens_and_renormalises(self):
        # The sliced prefix [3.0, 4.0] has norm 5, not 1 -- so this only
        # passes if embed() actually re-normalises after slicing. A prefix
        # like [1.0, 0.0] would pass with or without that step.
        mod = self.load_ollama(dims=2, fake_vector=[3.0, 4.0, 99.0, 99.0])
        v = mod.embed("hello", mode="document")
        self.assertEqual(v, [0.6, 0.8])

    def test_query_mode_prepends_instruct_prefix(self):
        mod = self.load_ollama(dims=2, fake_vector=[1.0, 0.0])
        mod.embed("wie geht das backup", mode="query")
        self.assertTrue(mod.last_request["input"].startswith("Instruct:"))
        mod.embed("a document", mode="document")
        self.assertFalse(mod.last_request["input"].startswith("Instruct:"))

    def test_request_carries_truncate_and_num_ctx_and_keep_alive(self):
        mod = self.load_ollama(dims=2, fake_vector=[1.0, 0.0], max_tokens=2560, keep_alive="5m")
        mod.embed("x", mode="document")
        self.assertTrue(mod.last_request["truncate"])
        self.assertEqual(mod.last_request["options"]["num_ctx"], 2560)
        self.assertEqual(mod.last_request["keep_alive"], "5m")

    def test_bootstrap_rejects_a_model_shorter_than_the_requested_dims(self):
        # assertRaisesRegex, not assertRaises: bootstrap can fail for several
        # reasons and a bare class assertion would pass for the wrong one.
        with self.assertRaisesRegex(RuntimeError, "fewer than EMBEDDING_DIMS"):
            self.load_ollama(dims=1024, fake_vector=[1.0, 0.0]).bootstrap()

    def test_health_does_not_embed(self):
        # The compose healthcheck runs every 15s; if health() embedded it
        # would refresh Ollama's keep_alive forever and defeat idle unload.
        mod = self.load_ollama(dims=2, fake_vector=[1.0, 0.0])
        mod.bootstrap()

        def fail_if_embed(req, timeout=120):
            body = json.loads(req.data.decode())
            self.assertEqual(req.full_url, "http://hivemem-ollama:11434/api/tags")
            self.assertNotIn("input", body)
            return _FakeResponse({})

        mock.patch.object(urllib.request, "urlopen", fail_if_embed).start()
        result = mod.health()
        self.assertEqual(result["status"], "ok")

    def test_transport_error_propagates(self):
        mod = self.load_ollama_failing(urllib.error.URLError("connection refused"))
        with self.assertRaises(urllib.error.URLError):
            mod.embed("x", mode="document")


if __name__ == "__main__":
    unittest.main()
