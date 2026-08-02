"""Tests for backend selection in backend.py.

backend.py eagerly imports the selected backend module at import time (see
backend.py:_impl), so loading it here requires the same numpy/onnxruntime/
tokenizers stubs used by test_backend_onnx.py -- otherwise importing
backend_onnx would fail on missing native dependencies in the test
environment.
"""

import importlib.util
import sys
import types
import unittest
from os import environ as _ENV
from pathlib import Path
from unittest import mock

MODULE_PATH = Path(__file__).with_name("backend.py")
MODULE_NAME = "embedding_service_backend_under_test"


def stub_modules():
    fake_numpy = types.SimpleNamespace(
        int64=int,
        float32=float,
        array=lambda value, dtype=None: value,
        zeros_like=lambda value: value,
        expand_dims=lambda value, axis=-1: value,
        sum=lambda value, axis=None: value,
        clip=lambda value, a_min=None, a_max=None: value,
        linalg=types.SimpleNamespace(norm=lambda value, axis=None, keepdims=None: value),
    )

    class DummyTokenizer:
        @staticmethod
        def from_file(path):
            return DummyTokenizer()

        def no_padding(self):
            return None

        def enable_truncation(self, max_length):
            return None

    fake_tokenizers = types.SimpleNamespace(Tokenizer=DummyTokenizer)
    fake_onnxruntime = types.SimpleNamespace(InferenceSession=lambda path: object())
    return {
        "numpy": fake_numpy,
        "onnxruntime": fake_onnxruntime,
        "tokenizers": fake_tokenizers,
    }


def load_backend_module():
    sys.modules.pop(MODULE_NAME, None)
    sys.modules.pop("backend_onnx", None)
    spec = importlib.util.spec_from_file_location(MODULE_NAME, MODULE_PATH)
    module = importlib.util.module_from_spec(spec)
    sys.modules[MODULE_NAME] = module
    spec.loader.exec_module(module)
    return module


class BackendSelectionTest(unittest.TestCase):
    def setUp(self):
        # Skip the real ONNX bootstrap; backend.py's eager import of
        # backend_onnx must not try to load real model files here.
        self.env_patch = mock.patch.dict(_ENV, {"EMBEDDING_SKIP_BOOTSTRAP": "1"}, clear=False)
        self.env_patch.start()
        self.modules_patch = mock.patch.dict(sys.modules, stub_modules())
        self.modules_patch.start()

    def tearDown(self):
        sys.modules.pop(MODULE_NAME, None)
        sys.modules.pop("backend_onnx", None)
        self.modules_patch.stop()
        self.env_patch.stop()

    def test_defaults_to_onnx(self):
        module = load_backend_module()
        self.assertEqual(module.select_backend({}), "onnx")

    def test_unknown_backend_is_a_hard_failure(self):
        module = load_backend_module()
        with self.assertRaises(ValueError):
            module.select_backend({"EMBEDDING_BACKEND": "typo"})


if __name__ == "__main__":
    unittest.main()
