import importlib.util
import sys
import unittest
from pathlib import Path

MODULE_PATH = Path(__file__).with_name("compare_reference.py")


def load_module():
    spec = importlib.util.spec_from_file_location("compare_reference_tool", MODULE_PATH)
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


class CompareReferenceTest(unittest.TestCase):
    def setUp(self):
        self.module = load_module()

    def tearDown(self):
        sys.modules.pop("compare_reference_tool", None)

    def test_check_vector_length_accepts_matching_lengths(self):
        self.assertIsNone(self.module.check_vector_length([1.0, 2.0, 3.0], [4.0, 5.0, 6.0]))

    def test_check_vector_length_rejects_mismatch_naming_both_lengths(self):
        # The regression this guards against: cosine() uses zip(a, b), which
        # silently truncates to the shorter vector. A service returning a
        # shorter MRL slice of the same model would then score ~1.0 over the
        # common prefix and PASS -- this is the sole gate in front of the
        # re-encode, so the mismatch must be caught before cosine() runs.
        error = self.module.check_vector_length([1.0] * 512, [1.0] * 1024)
        self.assertIsNotNone(error)
        self.assertIn("512", error)
        self.assertIn("1024", error)

    def test_cosine_of_identical_vectors_is_one(self):
        self.assertAlmostEqual(self.module.cosine([1.0, 0.0], [1.0, 0.0]), 1.0)

    def test_cosine_would_hide_a_truncated_slice_without_the_length_check(self):
        # Demonstrates why check_vector_length() must run before cosine():
        # a shorter MRL slice that agrees with the reference's leading dims
        # still scores a near-perfect cosine over the common prefix.
        reference = [1.0, 0.0, 0.0, 0.0]
        sliced_service_vector = [1.0, 0.0]
        self.assertAlmostEqual(self.module.cosine(sliced_service_vector, reference), 1.0)
        self.assertIsNotNone(
            self.module.check_vector_length(sliced_service_vector, reference))


if __name__ == "__main__":
    unittest.main()
