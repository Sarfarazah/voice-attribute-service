"""Validated mapping from audonnx's named model outputs to API values.

The public audonnx example returns a mapping containing ``hidden_states``,
``logits_age`` and ``logits_gender``.  The ONNX example includes negative
gender values, so logits_gender is handled as logits, not probabilities.
"""
from collections.abc import Mapping
import math
import numpy as np


def softmax(values: np.ndarray) -> np.ndarray:
    values = np.asarray(values, dtype=np.float32).reshape(-1)
    if values.size == 0 or not np.all(np.isfinite(values)):
        raise ValueError("gender logits must be finite")
    shifted = values - np.max(values)
    exp = np.exp(shifted)
    return exp / np.sum(exp)


def extract_prediction(outputs: Mapping[str, object]) -> dict[str, float]:
    """Extract age (years) and female/male/child probabilities.

    audEERING documents the class order as female, male, child and age as a
    single score approximately in [0, 1], corresponding to 0--100 years.
    """
    if not isinstance(outputs, Mapping):
        raise ValueError("model output must be a mapping")
    try:
        age_values = np.asarray(outputs["logits_age"], dtype=np.float32).reshape(-1)
        gender_logits = np.asarray(outputs["logits_gender"], dtype=np.float32).reshape(-1)
    except (KeyError, TypeError, ValueError) as exc:
        raise ValueError("model output is missing required named outputs") from exc

    if age_values.size != 1 or not math.isfinite(float(age_values[0])):
        raise ValueError("age output must contain one finite value")
    age_score = float(age_values[0])
    if not 0.0 <= age_score <= 1.0:
        raise ValueError("age output is outside the documented range")
    if gender_logits.size != 3:
        raise ValueError("gender output must contain female, male, and child logits")

    female, male, child = (float(value) for value in softmax(gender_logits))
    return {"age": age_score * 100.0, "female": female, "male": male, "child": child}
