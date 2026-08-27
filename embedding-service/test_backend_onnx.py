import importlib.util
import os
import sys
import tempfile
import types
import unittest
from os import environ as _ENV
from pathlib import Path
from unittest import mock

import numpy as np


MODULE_PATH = Path(__file__).with_name("backend_onnx.py")


def load_module():
    spec = importlib.util.spec_from_file_location("embedding_service_backend_onnx", MODULE_PATH)
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


class FakeInput:
    """Stands in for an onnxruntime NodeArg from session.get_inputs()."""

    def __init__(self, name, shape, type_name="tensor(float)"):
        self.name = name
        self.shape = shape
        self.type = type_name


def load_module_with_real_numpy():
    """Load the backend with real numpy but a stubbed onnxruntime/tokenizers.

    The numeric functions (pooling, feed construction) cannot be exercised
    against the SimpleNamespace numpy stub used by the config tests.
    """
    stubs = stub_modules()
    del stubs["numpy"]
    with mock.patch.dict(_ENV, {"EMBEDDING_SKIP_BOOTSTRAP": "1"}, clear=False):
        with mock.patch.dict(sys.modules, stubs):
            sys.modules.pop("embedding_service_backend_onnx", None)
            return load_module()


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
    # Records the SessionOptions it was constructed with (instead of discarding
    # them) so tests can assert bootstrap() actually wires the resolved thread
    # count through to ONNX Runtime, not just that resolve_intra_op_threads()
    # computes a number nobody consumes.
    fake_onnxruntime = types.SimpleNamespace(
        InferenceSession=lambda path, options=None: types.SimpleNamespace(
            options=options, get_inputs=lambda: []),
        SessionOptions=lambda: types.SimpleNamespace(
            intra_op_num_threads=0, inter_op_num_threads=0),
    )
    return {
        "numpy": fake_numpy,
        "onnxruntime": fake_onnxruntime,
        "tokenizers": fake_tokenizers,
    }


class OnnxFeedTest(unittest.TestCase):
    def setUp(self):
        self.module = load_module_with_real_numpy()
        self.ids = np.array([[1, 2, 3]], dtype=np.int64)
        self.mask = np.array([[1, 1, 1]], dtype=np.int64)

    def tearDown(self):
        sys.modules.pop("embedding_service_backend_onnx", None)

    def test_encoder_feed_unchanged(self):
        specs = [FakeInput("input_ids", [1, 3], "tensor(int64)"),
                 FakeInput("attention_mask", [1, 3], "tensor(int64)")]
        feed = self.module.build_feed(self.ids, self.mask, specs)
        self.assertEqual(sorted(feed), ["attention_mask", "input_ids"])

    def test_token_type_ids_added_when_declared(self):
        specs = [FakeInput("input_ids", [1, 3], "tensor(int64)"),
                 FakeInput("attention_mask", [1, 3], "tensor(int64)"),
                 FakeInput("token_type_ids", [1, 3], "tensor(int64)")]
        feed = self.module.build_feed(self.ids, self.mask, specs)
        np.testing.assert_array_equal(feed["token_type_ids"], np.zeros_like(self.ids))

    def test_position_ids_added_when_declared(self):
        specs = [FakeInput("input_ids", [1, 3], "tensor(int64)"),
                 FakeInput("attention_mask", [1, 3], "tensor(int64)"),
                 FakeInput("position_ids", [1, 3], "tensor(int64)")]
        feed = self.module.build_feed(self.ids, self.mask, specs)
        np.testing.assert_array_equal(feed["position_ids"], np.array([[0, 1, 2]]))
        self.assertEqual(feed["position_ids"].dtype, np.int64)

    def test_past_key_values_are_empty_with_batch_one(self):
        specs = [
            FakeInput("input_ids", [1, 3], "tensor(int64)"),
            FakeInput("attention_mask", [1, 3], "tensor(int64)"),
            FakeInput("past_key_values.0.key",
                      ["batch_size", 8, "past_seq_len", 128], "tensor(float)"),
        ]
        feed = self.module.build_feed(self.ids, self.mask, specs)
        past = feed["past_key_values.0.key"]
        self.assertEqual(past.shape, (1, 8, 0, 128))
        self.assertEqual(past.dtype, np.float32)

    def test_past_key_values_honour_declared_dtype(self):
        specs = [
            FakeInput("input_ids", [1, 3], "tensor(int64)"),
            FakeInput("attention_mask", [1, 3], "tensor(int64)"),
            FakeInput("past_key_values.0.value",
                      ["batch_size", 8, "past_seq_len", 128], "tensor(float16)"),
        ]
        feed = self.module.build_feed(self.ids, self.mask, specs)
        self.assertEqual(feed["past_key_values.0.value"].dtype, np.float16)

    def test_unknown_dtype_falls_back_to_float32(self):
        self.assertEqual(self.module.onnx_dtype_to_numpy("tensor(weird)"), np.float32)


class AppOnnxConfigTest(unittest.TestCase):
    def setUp(self):
        self.module_name = "embedding_service_backend_onnx"
        self.env_patch = mock.patch.dict(_ENV, {"EMBEDDING_SKIP_BOOTSTRAP": "1"}, clear=False)
        self.env_patch.start()
        self.modules = mock.patch.dict(sys.modules, stub_modules())
        self.modules.start()
        sys.modules.pop(self.module_name, None)

    def tearDown(self):
        sys.modules.pop(self.module_name, None)
        self.modules.stop()
        self.env_patch.stop()

    def test_resolve_model_dir_prefers_manual_path(self):
        with tempfile.TemporaryDirectory() as model_dir:
            with mock.patch.dict(
                _ENV,
                {"EMBEDDING_SKIP_BOOTSTRAP": "1", "MODEL_PATH": model_dir},
                clear=True,
            ):
                module = load_module()
            resolved, source = module.resolve_model_dir()
            self.assertEqual((resolved, source), (model_dir, "manual"))

    def test_find_onnx_uses_explicit_override(self):
        with tempfile.TemporaryDirectory() as model_dir:
            Path(model_dir, "custom.onnx").write_text("x")
            with mock.patch.dict(
                _ENV,
                {"EMBEDDING_SKIP_BOOTSTRAP": "1", "ONNX_FILE": "custom.onnx"},
                clear=True,
            ):
                module = load_module()
            self.assertEqual(module.find_onnx(model_dir), os.path.join(model_dir, "custom.onnx"))

    def test_find_tokenizer_falls_back_to_nested_file(self):
        with tempfile.TemporaryDirectory() as model_dir:
            nested = Path(model_dir, "onnx")
            nested.mkdir()
            Path(nested, "tokenizer.json").write_text("{}")
            module = load_module()
            self.assertEqual(module.find_tokenizer(model_dir), str(nested / "tokenizer.json"))

    def test_build_info_reports_repo_only_for_cached_models(self):
        with mock.patch.dict(
            _ENV,
            {
                "EMBEDDING_SKIP_BOOTSTRAP": "1",
                "MODEL_REPO": "acme/model",
                "POOLING": "cls",
                "MAX_LENGTH": "512",
                "QUERY_PREFIX": "Q: ",
                "DOCUMENT_PREFIX": "D: ",
                "EMBEDDING_MAX_CHARS": "50000",
            },
            clear=True,
        ):
            module = load_module()
        module.INPUT_NAMES = {"attention_mask", "input_ids"}
        info = module.build_info(
            "demo-model",
            1024,
            "hf_cache",
            "/tmp/model",
            "/tmp/model/onnx/model.onnx",
            "/tmp/model/tokenizer.json",
        )
        self.assertEqual(info["repo"], "acme/model")
        self.assertEqual(info["pooling"], "cls")
        self.assertEqual(info["max_length"], 512)
        self.assertEqual(info["query_prefix"], "Q: ")
        self.assertEqual(info["document_prefix"], "D: ")
        self.assertEqual(info["inputs"], ["attention_mask", "input_ids"])
        # Identity encodes slicing strategy, token cap, char cap and embed-source
        # strategy so EmbeddingMigrationService re-encodes when any of them changes.
        self.assertEqual(info["model"], "demo-model/mrl0/t512/c50000/contentfirst")
        self.assertEqual(info["max_chars"], 50000)

    def test_max_chars_defaults_when_env_absent(self):
        with mock.patch.dict(_ENV, {"EMBEDDING_SKIP_BOOTSTRAP": "1"}, clear=True):
            module = load_module()
        module.INPUT_NAMES = {"attention_mask", "input_ids"}
        info = module.build_info(
            "demo-model", 384, "manual", "/tmp/m", "/tmp/m/model.onnx", "/tmp/m/tokenizer.json")
        self.assertEqual(info["max_chars"], 8000)
        self.assertTrue(info["model"].endswith("/c8000/contentfirst"))

    def test_build_info_reports_resolved_intra_op_threads(self):
        # intra_op_threads is the only operator-visible signal for this fix:
        # CPU utilisation looks identical (~597%) whether the pool is sized
        # correctly or oversubscribed, so /info has to carry the real number.
        with mock.patch.dict(_ENV, {"EMBEDDING_SKIP_BOOTSTRAP": "1"}, clear=True):
            module = load_module()
        module.INPUT_NAMES = {"attention_mask", "input_ids"}
        module.INTRA_OP_THREADS = 4
        info = module.build_info(
            "demo-model", 384, "manual", "/tmp/m", "/tmp/m/model.onnx", "/tmp/m/tokenizer.json")
        self.assertIn("intra_op_threads", info)
        self.assertEqual(info["intra_op_threads"], 4)


class OnnxThreadSizingTest(unittest.TestCase):
    def setUp(self):
        self.module_name = "embedding_service_backend_onnx"
        self.env_patch = mock.patch.dict(_ENV, {"EMBEDDING_SKIP_BOOTSTRAP": "1"}, clear=False)
        self.env_patch.start()
        self.modules = mock.patch.dict(sys.modules, stub_modules())
        self.modules.start()
        sys.modules.pop(self.module_name, None)
        self.module = load_module()

    def tearDown(self):
        sys.modules.pop(self.module_name, None)
        self.modules.stop()
        self.env_patch.stop()

    def _quota_file(self, text):
        path = Path(tempfile.mkdtemp(), "cpu.max")
        path.write_text(text)
        return str(path)

    def test_cgroup_quota_rounds_up(self):
        # 600000/100000 == 6 cores; 650000 is 6.5 and must round up to 7.
        self.assertEqual(self.module._cgroup_cpu_quota(self._quota_file("600000 100000")), 6)
        self.assertEqual(self.module._cgroup_cpu_quota(self._quota_file("650000 100000")), 7)

    def test_cgroup_quota_none_when_unlimited_or_unreadable(self):
        self.assertIsNone(self.module._cgroup_cpu_quota(self._quota_file("max 100000")))
        self.assertIsNone(self.module._cgroup_cpu_quota("/nonexistent/cpu.max"))
        self.assertIsNone(self.module._cgroup_cpu_quota(self._quota_file("garbage")))

    def test_env_override_wins_over_cgroup(self):
        with mock.patch.dict(_ENV, {"ORT_INTRA_OP_THREADS": "3"}, clear=False):
            with mock.patch.object(self.module, "_cgroup_cpu_quota", return_value=6):
                self.assertEqual(self.module.resolve_intra_op_threads(), 3)

    def test_falls_back_to_cgroup_then_cpu_count(self):
        with mock.patch.dict(_ENV, {"ORT_INTRA_OP_THREADS": ""}, clear=False):
            with mock.patch.object(self.module, "_cgroup_cpu_quota", return_value=6):
                self.assertEqual(self.module.resolve_intra_op_threads(), 6)
            with mock.patch.object(self.module, "_cgroup_cpu_quota", return_value=None):
                with mock.patch.object(self.module.os, "cpu_count", return_value=4):
                    self.assertEqual(self.module.resolve_intra_op_threads(), 4)

    def test_never_returns_less_than_one(self):
        with mock.patch.dict(_ENV, {"ORT_INTRA_OP_THREADS": "0"}, clear=False):
            with mock.patch.object(self.module, "_cgroup_cpu_quota", return_value=None):
                with mock.patch.object(self.module.os, "cpu_count", return_value=None):
                    self.assertEqual(self.module.resolve_intra_op_threads(), 1)

    def test_bootstrap_passes_resolved_thread_count_to_session_options(self):
        # The regression this guards against: reverting to a bare
        # ort.InferenceSession(onnx_path), or assigning the resolved thread
        # count to a misspelled SessionOptions attribute, would leave
        # resolve_intra_op_threads() computing a number nobody consumes.
        # SimpleNamespace accepts any attribute name silently, so only an
        # end-to-end check through bootstrap() catches that.
        with tempfile.TemporaryDirectory() as model_dir:
            Path(model_dir, "model.onnx").write_text("x")
            Path(model_dir, "tokenizer.json").write_text("{}")
            with mock.patch.dict(
                _ENV,
                {"EMBEDDING_SKIP_BOOTSTRAP": "1", "MODEL_PATH": model_dir},
                clear=True,
            ):
                module = load_module()
            with mock.patch.dict(_ENV, {"ORT_INTRA_OP_THREADS": ""}, clear=False), \
                    mock.patch.object(module, "_cgroup_cpu_quota", return_value=None), \
                    mock.patch.object(module.os, "cpu_count", return_value=4), \
                    mock.patch.object(module, "embed", return_value=[0.0]):
                module.bootstrap()
                expected_threads = module.resolve_intra_op_threads()
        self.assertEqual(expected_threads, 4)
        self.assertEqual(module.INTRA_OP_THREADS, 4)
        self.assertEqual(module.session.options.intra_op_num_threads, 4)
        self.assertEqual(module.session.options.inter_op_num_threads, 1)


if __name__ == "__main__":
    unittest.main()
