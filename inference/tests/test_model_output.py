import unittest
import numpy as np
from model_output import extract_prediction, softmax


class ModelOutputTest(unittest.TestCase):
    def test_named_outputs_are_mapped_and_logits_are_softmaxed(self):
        result = extract_prediction({
            "hidden_states": np.zeros((1, 1024), dtype=np.float32),
            "logits_age": np.array([[0.423]], dtype=np.float32),
            "logits_gender": np.array([[-2.0, 3.0, 0.5]], dtype=np.float32),
        })
        self.assertAlmostEqual(42.3, result["age"], places=4)
        self.assertGreater(result["male"], result["child"])
        self.assertGreater(result["child"], result["female"])
        self.assertAlmostEqual(1.0, result["female"] + result["male"] + result["child"], places=6)

    def test_rejects_missing_or_invalid_named_outputs(self):
        with self.assertRaises(ValueError):
            extract_prediction({"logits_age": [[0.4]]})
        with self.assertRaises(ValueError):
            extract_prediction({"logits_age": [[1.2]], "logits_gender": [[0, 1, 2]]})

    def test_softmax_is_stable(self):
        values = softmax(np.array([1000.0, 999.0, 998.0]))
        self.assertTrue(np.isfinite(values).all())
        self.assertAlmostEqual(1.0, float(values.sum()), places=6)
