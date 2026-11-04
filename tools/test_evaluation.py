"""The evaluation code itself, on hand-worked examples. No models, API calls or datasets. Run: python -m pytest tools/test_evaluation.py"""
import importlib.util
import os

import numpy as np
import pytest

HERE = os.path.dirname(os.path.abspath(__file__))


def module(name, relative):
    spec = importlib.util.spec_from_file_location(name, os.path.join(HERE, relative))
    m = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(m)
    return m


claim_eval = module("claim_eval", "vision-eval/claim_eval.py")
leakage = module("leakage_check", "second-opinion/leakage_check.py")
fraud = module("fraud_eval", "fraud-eval/fraud_eval.py")


def test_per_class_by_hand():
    m = claim_eval.per_class(["A", "A", "A", "B"], ["A", "A", "B", "B"], ["A", "B", "C"])
    assert m["A"]["precision"] == 1.0 and m["A"]["recall"] == pytest.approx(2 / 3) and m["A"]["missRate"] == pytest.approx(1 / 3)
    assert m["B"]["precision"] == 0.5 and m["B"]["recall"] == 1.0 and m["B"]["f1"] == pytest.approx(2 / 3)
    assert m["C"]["precision"] is None and m["C"]["recall"] is None, "nothing to divide by is unknown, not zero"


def test_confusion_counts_every_pair():
    assert claim_eval.confusion(["A", "A", "B"], ["A", "B", "B"], ["A", "B"], ["A", "B"]) == [[1, 1], [0, 1]]


def test_brier_and_calibration_by_hand():
    assert claim_eval.brier([1.0, 0.5], [True, False]) == pytest.approx(0.125)
    # Bin 0.8-1.0: mean stated 0.9, share right 0.5, so an error of 0.4 across all the answers.
    assert claim_eval.ece([0.9, 0.9], [True, False]) == pytest.approx(0.4)
    assert claim_eval.ece([0.9] * 10, [True] * 9 + [False]) == pytest.approx(0.0)


def test_reliability_skips_empty_bins_and_invents_no_points():
    assert [b[2] for b in claim_eval.reliability([0.1, 0.9, 0.95], [False, True, True])] == [1, 2]


def test_wilson_stays_within_bounds_and_is_wide_on_little_data():
    lo, hi = claim_eval.wilson(1, 1)
    assert 0 <= lo < 1 and hi == pytest.approx(1.0)
    assert lo < 0.3, "one right answer out of one proves little"
    assert claim_eval.wilson(0, 0) is None


def test_percentile_nearest_rank():
    v = list(range(1, 101))
    assert claim_eval.percentile(v, 50) == 50 and claim_eval.percentile(v, 95) == 95 and claim_eval.percentile(v, 100) == 100


def test_claims_group_by_claim_and_a_failed_claim_has_no_answer():
    label = {"primaryZone": "A", "zones": ["A"]}
    rows = [{"claimId": "c1", "label": label, "prediction": {"confidence": 0.5, "damageType": "A"}},
            {"claimId": "c1", "label": label, "prediction": {"confidence": 0.9, "damageType": "B"}},
            {"claimId": "c2", "label": label, "error": "timeout"}]
    claims = {c["claimId"]: c for c in claim_eval.to_claims(rows)}
    assert claims["c1"]["photos"] == 2 and claims["c1"]["answer"]["prediction"]["damageType"] == "B"
    assert claims["c2"]["answer"] is None


def test_photos_without_a_claim_id_each_stand_alone():
    label = {"primaryZone": "A", "zones": ["A"]}
    assert len(claim_eval.to_claims([{"label": label}, {"label": label}])) == 2


def unit(*rows):
    x = np.array(rows, float)
    return x / np.linalg.norm(x, axis=1, keepdims=True)


def test_near_duplicates_group_transitively_and_strangers_do_not():
    X = unit([1, 0, 0], [1, 0.01, 0], [1, 0.02, 0], [0, 1, 0])
    groups = leakage.near_duplicate_groups(X, 0.99)
    assert groups[0] == groups[1] == groups[2]
    assert groups[3] != groups[0]


def test_no_group_is_ever_on_both_sides_of_the_holdout():
    groups = np.array([0, 0, 0, 1, 1, 2, 3, 3, 4, 5, 6, 6, 7, 8, 9])
    train, held = leakage.group_holdout(groups, 0.2, seed=7)
    assert not set(groups[train]) & set(groups[held])
    assert len(train) + len(held) == len(groups) and len(held) > 0


def test_the_holdout_is_reproducible_and_the_seed_matters():
    groups = np.arange(200) // 2
    first = leakage.group_holdout(groups, 0.2, seed=7)[1]
    assert np.array_equal(first, leakage.group_holdout(groups, 0.2, seed=7)[1])
    assert not np.array_equal(first, leakage.group_holdout(groups, 0.2, seed=8)[1])


def test_calibration_error_is_zero_when_confidence_is_honest():
    assert leakage.expected_calibration_error([0.75] * 4, [1, 1, 1, 0]) == pytest.approx(0.0)
    assert leakage.expected_calibration_error([0.95] * 2, [0, 0]) == pytest.approx(0.95)


def test_scores_by_hand():
    leakage_cars = [{"zones": ["FRONT_BUMPER", "SIDE_PANEL"]}, {"zones": ["REAR_BUMPER"]}]
    p = np.array([[0.1, 0.0, 0.9, 0.0], [0.9, 0.1, 0.0, 0.0]])
    s = leakage.scores(p, np.array([0, 1]), leakage_cars)
    assert s["strict"] == 0.0 and s["lenient"] == 0.5


def test_fraud_sweep_by_hand():
    s, y = [0.9, 0.8, 0.7, 0.6], [1, 0, 1, 0]
    half = fraud.threshold_sweep(s, y, [0.5])[0]
    assert half["flagged"] == 2 and half["threshold"] == 0.8 and half["precision"] == 0.5 and half["recall"] == 0.5


def test_fraud_average_precision_by_hand():
    assert fraud.average_precision([0.9, 0.8, 0.7, 0.6], [1, 0, 1, 0]) == pytest.approx((1 + 2 / 3) / 2)
    assert fraud.average_precision([0.9, 0.8], [1, 1]) == 1.0


def test_fraud_false_positives_count_only_genuine_flagged_claims():
    params = [{"name": "a", "label": "A", "type": "binary", "weight": 2.0}, {"name": "b", "label": "B", "type": "binary", "weight": 1.0}]
    test = [{"a": True, "b": False}, {"a": True, "b": True}, {"a": False, "b": False}]
    count, rows = fraud.false_positive_reasons(test, [2.0, 3.0, 0.0], [0, 1, 0], 1.0, params)
    assert count == 1 and rows == [("a", 1, 2.0)]


def test_the_fraud_generator_is_reproducible():
    first, _ = fraud.dataset()
    second, _ = fraud.dataset()
    assert first[:50] == second[:50]
